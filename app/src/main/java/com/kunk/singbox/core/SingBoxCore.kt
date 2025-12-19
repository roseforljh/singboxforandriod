package com.kunk.singbox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import com.kunk.singbox.service.SingBoxService
import io.nekohasekai.libbox.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.reflect.Modifier
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sing-box 核心封装类
 * 负责与 libbox 交互，提供延迟测试等功能
 * 
 * 如果 libbox 不可用，将使用降级方案进行测试
 */
class SingBoxCore private constructor(private val context: Context) {
    
    private val gson = Gson()
    private val workDir: File = File(context.filesDir, "singbox_work")
    private val tempDir: File = File(context.cacheDir, "singbox_temp")
    
    // libbox 是否可用
    private var libboxAvailable = false
    
    // Clash API 客户端
    private val clashApiClient = ClashApiClient()
    
    // 临时测试服务（用于在 VPN 未运行时进行延迟测试）
    private var testService: BoxService? = null
    private var testServiceClashBaseUrl: String? = null
    private var testServiceClashPort: Int = 0
    private var testPlatformInterface: TestPlatformInterface? = null
    
    // 后台保活相关
    private var testServiceStartTime: Long = 0
    private var testServiceOutboundTags: MutableSet<String> = mutableSetOf()
    private var keepAliveJob: kotlinx.coroutines.Job? = null
    private val serviceLock = Any()
    
    // 基准延迟（非网络开销），用于校准
    private var baselineLatency: Long = 0
    private var baselineCalibrated = false
    
    companion object {
        private const val TAG = "SingBoxCore"
        private const val URL_TEST_URL = "https://www.gstatic.com/generate_204"
        private const val URL_TEST_TIMEOUT = 5000 // 5 seconds
        private const val TEST_SERVICE_KEEP_ALIVE_MS = 30_000L // 保活 30 秒

        private val libboxSetupDone = AtomicBoolean(false)
        // 最近一次原生测速预热时间（避免每次都预热）
        @Volatile
        private var lastNativeWarmupAt: Long = 0
        
        @Volatile
        private var instance: SingBoxCore? = null
        
        fun getInstance(context: Context): SingBoxCore {
            return instance ?: synchronized(this) {
                instance ?: SingBoxCore(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun ensureLibboxSetup(context: Context) {
            if (libboxSetupDone.get()) return

            val appContext = context.applicationContext
            val workDir = File(appContext.filesDir, "singbox_work").also { it.mkdirs() }
            val tempDir = File(appContext.cacheDir, "singbox_temp").also { it.mkdirs() }

            val setupOptions = SetupOptions().apply {
                basePath = appContext.filesDir.absolutePath
                workingPath = workDir.absolutePath
                this.tempPath = tempDir.absolutePath
            }

            if (!libboxSetupDone.compareAndSet(false, true)) return
            try {
                Libbox.setup(setupOptions)
            } catch (e: Exception) {
                libboxSetupDone.set(false)
                Log.w(TAG, "Libbox setup warning: ${e.message}")
            }
        }
    }
    
    init {
        // 确保工作目录存在
        workDir.mkdirs()
        tempDir.mkdirs()
        
        // 尝试初始化 libbox
        libboxAvailable = initLibbox()
        
        if (libboxAvailable) {
            Log.i(TAG, "Libbox initialized successfully")
        } else {
            Log.w(TAG, "Libbox not available, using fallback mode")
        }
    }
    
    /**
     * 尝试初始化 libbox
     */
    private fun initLibbox(): Boolean {
        return try {
            // 尝试加载 libbox 类
            Class.forName("io.nekohasekai.libbox.Libbox")
            ensureLibboxSetup(context)
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Libbox class not found - AAR not included")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize libbox: ${e.message}", e)
            false
        }
    }
    
    /**
     * 检查 libbox 是否可用
     */
    fun isLibboxAvailable(): Boolean = libboxAvailable

    private fun maybeWarmupNative(libboxClass: Class<*>, url: String) {
        val now = System.currentTimeMillis()
        if (now - lastNativeWarmupAt < 1200L) return
        try {
            val m = libboxClass.methods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers)
                        && method.parameterTypes.size == 3
                        && method.parameterTypes[0] == String::class.java
                        && method.parameterTypes[1] == String::class.java
                        && (method.parameterTypes[2] == Long::class.javaPrimitiveType || method.parameterTypes[2] == Int::class.javaPrimitiveType)
                        && method.name.endsWith("urlTestOnRunning", ignoreCase = true)
            } ?: return
            // 仅传 tag=direct 进行一次快速预热，忽略结果
            val outboundJson = "{" + "\"tag\":\"direct\"" + "}"
            try {
                m.invoke(null, outboundJson, url, 1000L)
            } catch (_: Exception) { }
            lastNativeWarmupAt = System.currentTimeMillis()
        } catch (_: Exception) { }
    }

    /**
     * 使用 Libbox 原生方法进行延迟测试
     * 自动尝试多种可能的签名并记录发现的方法
     */
    private suspend fun testOutboundLatencyWithLibbox(outbound: Outbound, settings: com.kunk.singbox.model.AppSettings? = null): Long = withContext(Dispatchers.IO) {
        if (!libboxAvailable) return@withContext -1L
        
        try {
            val finalSettings = settings ?: SettingsRepository.getInstance(context).settings.first()
            val url = adjustUrlForMode(finalSettings.latencyTestUrl, finalSettings.latencyTestMethod)
            val fallbackUrl = try {
                if (finalSettings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                    adjustUrlForMode("http://cp.cloudflare.com/generate_204", finalSettings.latencyTestMethod)
                } else {
                    adjustUrlForMode("https://cp.cloudflare.com/generate_204", finalSettings.latencyTestMethod)
                }
            } catch (_: Exception) { url }
            val timeout = 5000L
            val outboundJson = gson.toJson(outbound)
            
            val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")

            // Prefer running-instance native URL test when VPN is running
            if (SingBoxService.isRunning) {
                try {
                    // 先做一次轻量预热，规避 VPN link 验证/路由冷启动瞬态
                    maybeWarmupNative(libboxClass, url)
                    val runningMethods = libboxClass.methods.filter { m ->
                        Modifier.isStatic(m.modifiers)
                                && m.parameterTypes.size == 3
                                && m.parameterTypes[0] == String::class.java
                                && m.parameterTypes[1] == String::class.java
                                && (m.parameterTypes[2] == Long::class.javaPrimitiveType || m.parameterTypes[2] == Int::class.javaPrimitiveType)
                                && m.name.endsWith("urlTestOnRunning", ignoreCase = true)
                    }
                    for (m in runningMethods) {
                        try {
                            var r = m.invoke(null, outboundJson, url, 5000L) as Long
                            if (r >= 0) {
                                Log.i(TAG, "Invoked running-instance native URL test: ${m.name} -> $r ms")
                                return@withContext r
                            }
                            // One quick retry for transient states (route selection, DNS warmup)
                            delay(250)
                            r = m.invoke(null, outboundJson, url, 5000L) as Long
                            if (r >= 0) {
                                Log.i(TAG, "Invoked running-instance native URL test (retry): ${m.name} -> $r ms")
                                return@withContext r
                            }
                        } catch (_: Exception) { }
                    }
                    // Fallback URL once if initial attempts failed
                    if (fallbackUrl != url) {
                        for (m in runningMethods) {
                            try {
                                var r = m.invoke(null, outboundJson, fallbackUrl, 5000L) as Long
                                if (r >= 0) {
                                    Log.i(TAG, "Invoked running-instance native URL test (fallback URL): ${m.name} -> $r ms")
                                    return@withContext r
                                }
                                delay(250)
                                r = m.invoke(null, outboundJson, fallbackUrl, 5000L) as Long
                                if (r >= 0) {
                                    Log.i(TAG, "Invoked running-instance native URL test (fallback URL retry): ${m.name} -> $r ms")
                                    return@withContext r
                                }
                            } catch (_: Exception) { }
                        }
                    }
                } catch (_: Exception) { }
            }
            
            // 尝试从缓存中获取之前发现的方法，避免每次都反射查找
            var methodToUse = discoveredUrlTestMethod
            var methodType = discoveredMethodType // 0: long, 1: URLTest object

            if (methodToUse == null) {
                // 遍历 Libbox 类的所有方法进行匹配
                val methods = libboxClass.methods
                for (m in methods) {
                    val name = m.name
                    val params = m.parameterTypes
                    
                    // 匹配签名: (String, String, long/int) 或 (String, String, long/int, PlatformInterface)
                    if ((name.equals("urlTest", true) || name.equals("newURLTest", true)) && (params.size == 3 || params.size == 4) && 
                        params[0] == String::class.java && params[1] == String::class.java &&
                        (params[2] == Long::class.javaPrimitiveType || params[2] == Int::class.javaPrimitiveType)) {
                        
                        methodToUse = m
                        methodType = if (m.returnType == Long::class.javaPrimitiveType) 0 else 1
                        discoveredUrlTestMethod = m
                        discoveredMethodType = methodType
                        Log.i(TAG, "Discovered native URL test method: ${m.name}(${params.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                        break
                    }
                }
                // 若按方法名未找到，进一步按签名匹配（兼容被混淆的库）
                if (methodToUse == null) {
                    for (m in methods) {
                        val params = m.parameterTypes
                        if ((params.size == 3 || (params.size == 4 && params[3].isInterface)) &&
                            params[0] == String::class.java && params[1] == String::class.java &&
                            (params[2] == Long::class.javaPrimitiveType || params[2] == Int::class.javaPrimitiveType) &&
                            Modifier.isStatic(m.modifiers)
                        ) {
                            methodToUse = m
                            methodType = if (m.returnType == Long::class.javaPrimitiveType) 0 else 1
                            discoveredUrlTestMethod = m
                            discoveredMethodType = methodType
                            Log.i(TAG, "Discovered native URL test method by signature: ${m.name}(${params.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                            break
                        }
                    }
                }

                if (methodToUse == null) {
                    for (m in methods) {
                        val params = m.parameterTypes
                        val okParams = (params.size == 3 || (params.size == 4 && params[3].isInterface)) &&
                                params[0] == String::class.java && params[1] == String::class.java &&
                                (params[2] == Long::class.javaPrimitiveType || params[2] == Int::class.javaPrimitiveType) &&
                                Modifier.isStatic(m.modifiers)
                        if (okParams) {
                            try {
                                val pi = testPlatformInterface ?: TestPlatformInterface(context)
                                val args = buildUrlTestArgs(params, outboundJson, url, pi)
                                val rt = m.returnType
                                if (rt == Long::class.javaPrimitiveType || hasDelayAccessors(rt)) {
                                    val result = m.invoke(null, *args)
                                    Log.i(TAG, "Invoked candidate native URL test method: ${m.name}(${params.joinToString { it.simpleName }}) -> ${rt.simpleName}")
                                    return@withContext when {
                                        rt == Long::class.javaPrimitiveType -> result as Long
                                        else -> extractDelayFromUrlTest(result, finalSettings.latencyTestMethod)
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    }

                    // 使用备用 URL 再尝试一次静态原生方法
                    if (fallbackUrl != url) {
                        for (m in methods) {
                            val params = m.parameterTypes
                            val okParams = (params.size == 3 || (params.size == 4 && params[3].isInterface)) &&
                                    params[0] == String::class.java && params[1] == String::class.java &&
                                    (params[2] == Long::class.javaPrimitiveType || params[2] == Int::class.javaPrimitiveType) &&
                                    Modifier.isStatic(m.modifiers)
                            if (okParams) {
                                try {
                                    val pi = testPlatformInterface ?: TestPlatformInterface(context)
                                    val args = buildUrlTestArgs(params, outboundJson, fallbackUrl, pi)
                                    val rt = m.returnType
                                    if (rt == Long::class.javaPrimitiveType || hasDelayAccessors(rt)) {
                                        val result = m.invoke(null, *args)
                                        Log.i(TAG, "Invoked static native URL test method (fallback URL): ${m.name}(${params.joinToString { it.simpleName }}) -> ${rt.simpleName}")
                                        return@withContext when {
                                            rt == Long::class.javaPrimitiveType -> result as Long
                                            else -> extractDelayFromUrlTest(result, finalSettings.latencyTestMethod)
                                        }
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    
                    try {
                        val candidates = methods.filter { (it.parameterTypes.size == 3 || (it.parameterTypes.size == 4 && it.parameterTypes[3].isInterface)) && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java && (it.parameterTypes[2] == Long::class.javaPrimitiveType || it.parameterTypes[2] == Int::class.javaPrimitiveType) && Modifier.isStatic(it.modifiers) }
                        Log.i(TAG, "Libbox static candidates count: ${candidates.size}")
                        if (candidates.isNotEmpty()) {
                            Log.i(TAG, "Libbox static candidates: " + candidates.joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ") -> " + it.returnType.simpleName })
                        }
                    } catch (_: Exception) { }
                }
            }

            // 如果找到方法，执行它
            methodToUse?.let { method ->
                val params = method.parameterTypes
                val timeoutParam = if (params[2] == Int::class.javaPrimitiveType) 5000 else 5000L
                
                val args = if (params.size == 4 && params[3].isInterface) {
                    // 需要 PlatformInterface
                    val pi = testPlatformInterface ?: TestPlatformInterface(context)
                    arrayOf(outboundJson, url, timeoutParam, pi)
                } else {
                    arrayOf(outboundJson, url, timeoutParam)
                }
                
                val result = method.invoke(null, *args)
                
                return@withContext when (methodType) {
                    0 -> result as Long // 直接返回 long 延迟
                    1 -> { // 返回 URLTest 对象
                        extractDelayFromUrlTest(result, finalSettings.latencyTestMethod)
                    }
                    else -> -1L
                }
            }

            // 如果仍然找不到，尝试检查 BoxService (虽然通常是在 Libbox 静态类中)
            if (methodToUse == null) {
                try {
                    val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
                    val allMethods = libboxClass.methods
                    Log.i(TAG, "Libbox all methods: " + allMethods.joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" })
                    
                    val boxServiceClass = Class.forName("io.nekohasekai.libbox.BoxService")
                    val allInstMethods = boxServiceClass.methods
                    Log.i(TAG, "BoxService all methods: " + allInstMethods.joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" })
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to list methods: ${e.message}")
                }
                Log.w(TAG, "Libbox native URL test methods still not found after discovery")
            }
            if (methodToUse == null) {
                try {
                    val boxServiceClass = Class.forName("io.nekohasekai.libbox.BoxService")
                    val instanceMethods = boxServiceClass.methods
                    var instMethod: java.lang.reflect.Method? = null
                    var instMethodType = 0
                    for (m in instanceMethods) {
                        val name = m.name
                        val params = m.parameterTypes
                        if ((name.equals("urlTest", true) || name.equals("newURLTest", true)) && (params.size == 3 || params.size == 4) &&
                            params[0] == String::class.java && params[1] == String::class.java &&
                            (params[2] == Long::class.javaPrimitiveType || params[2] == Int::class.javaPrimitiveType) &&
                            !java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                            instMethod = m
                            instMethodType = if (m.returnType == Long::class.javaPrimitiveType) 0 else 1
                            break
                        }
                    }
                    if (instMethod == null) {
                        for (m in instanceMethods) {
                            val params = m.parameterTypes
                            if ((params.size == 3 || (params.size == 4 && params[3].isInterface)) &&
                                params[0] == String::class.java && params[1] == String::class.java &&
                                (params[2] == Long::class.javaPrimitiveType || params[2] == Int::class.javaPrimitiveType) &&
                                !java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                                instMethod = m
                                instMethodType = if (m.returnType == Long::class.javaPrimitiveType) 0 else 1
                                break
                            }
                        }
                    }
                    if (instMethod != null) {
                        ensureLibboxSetup(context)
                        val pi = testPlatformInterface ?: TestPlatformInterface(context)
                        val minimalConfig = SingBoxConfig(
                            log = com.kunk.singbox.model.LogConfig(level = "warn", timestamp = true)
                        )
                        val cfgJson = gson.toJson(minimalConfig)
                        val service = Libbox.newService(cfgJson, pi)
                        try {
                            try { service.start() } catch (_: Exception) {}
                            val p = instMethod!!.parameterTypes
                            val args = buildUrlTestArgs(p, outboundJson, url, pi)
                            val result = instMethod!!.invoke(service, *args)
                            Log.i(TAG, "Invoked BoxService native URL test method: ${instMethod!!.name}(${p.joinToString { it.simpleName }}) -> ${instMethod!!.returnType.simpleName}")
                            return@withContext when (instMethodType) {
                                0 -> result as Long
                                1 -> extractDelayFromUrlTest(result, finalSettings.latencyTestMethod)
                                else -> -1L
                            }
                        } finally {
                            try { service.close() } catch (_: Exception) {}
                        }
                    }

                    ensureLibboxSetup(context)
                    val pi = testPlatformInterface ?: TestPlatformInterface(context)
                    val minimalConfig = SingBoxConfig(
                        log = com.kunk.singbox.model.LogConfig(level = "warn", timestamp = true)
                    )
                    val cfgJson = gson.toJson(minimalConfig)
                    val service = Libbox.newService(cfgJson, pi)
                    try {
                        try { service.start() } catch (_: Exception) {}
                        for (m in instanceMethods) {
                            val params = m.parameterTypes
                            val okParams = (params.size == 3 || (params.size == 4 && params[3].isInterface)) &&
                                    params[0] == String::class.java && params[1] == String::class.java &&
                                    (params[2] == Long::class.javaPrimitiveType || params[2] == Int::class.javaPrimitiveType) &&
                                    !Modifier.isStatic(m.modifiers)
                            if (okParams) {
                                try {
                                    val args = buildUrlTestArgs(params, outboundJson, url, pi)
                                    val rt = m.returnType
                                    if (rt == Long::class.javaPrimitiveType || hasDelayAccessors(rt)) {
                                        val result = m.invoke(service, *args)
                                        Log.i(TAG, "Invoked BoxService candidate method: ${m.name}(${params.joinToString { it.simpleName }}) -> ${rt.simpleName}")
                                        return@withContext when {
                                            rt == Long::class.javaPrimitiveType -> result as Long
                                            else -> extractDelayFromUrlTest(result, finalSettings.latencyTestMethod)
                                        }
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                        
                        try {
                            val candidates = instanceMethods.filter { (it.parameterTypes.size == 3 || (it.parameterTypes.size == 4 && it.parameterTypes[3].isInterface)) && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java && (it.parameterTypes[2] == Long::class.javaPrimitiveType || it.parameterTypes[2] == Int::class.javaPrimitiveType) && !Modifier.isStatic(it.modifiers) }
                            Log.i(TAG, "BoxService instance candidates count: ${candidates.size}")
                            if (candidates.isNotEmpty()) {
                                Log.i(TAG, "BoxService instance candidates: " + candidates.joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ") -> " + it.returnType.simpleName })
                            }
                        } catch (_: Exception) { }
                    } finally {
                        try { service.close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Libbox native URL test failed: ${e.message}")
        }

        // 使用本地 HTTP 入站 + 代理的方式进行原生测速（不依赖 Clash API）
        return@withContext try {
            val finalSettings = settings ?: SettingsRepository.getInstance(context).settings.first()
            val finalUrl = adjustUrlForMode(finalSettings.latencyTestUrl, finalSettings.latencyTestMethod)
            testWithLocalHttpProxy(outbound, finalUrl, 5000)
        } catch (e: Exception) {
            Log.w(TAG, "Native HTTP proxy test failed: ${e.message}")
            -1L
        }
    }

    private var discoveredUrlTestMethod: java.lang.reflect.Method? = null
    private var discoveredMethodType: Int = 0 // 0: long, 1: URLTest object
    
    private fun adjustUrlForMode(original: String, method: LatencyTestMethod): String {
        return try {
            val u = URI(original)
            val host = u.host ?: return original
            val path = if ((u.path ?: "").isNotEmpty()) u.path else "/"
            val query = u.query
            val fragment = u.fragment
            val userInfo = u.userInfo
            val port = u.port
            when (method) {
                LatencyTestMethod.TCP -> URI("http", userInfo, host, if (port == -1) -1 else port, path, query, fragment).toString()
                LatencyTestMethod.HANDSHAKE -> URI("https", userInfo, host, if (port == -1) -1 else port, path, query, fragment).toString()
                else -> original
            }
        } catch (_: Exception) {
            original
        }
    }
    
    private fun extractDelayFromUrlTest(resultObj: Any?, method: LatencyTestMethod): Long {
        if (resultObj == null) return -1L
        fun tryGet(names: Array<String>): Long? {
            for (n in names) {
                try {
                    val m = resultObj.javaClass.getMethod(n)
                    val v = m.invoke(resultObj)
                    when (v) {
                        is Long -> if (v > 0) return v
                        is Int -> if (v > 0) return v.toLong()
                    }
                } catch (_: Exception) { }
                try {
                    val f = try { resultObj.javaClass.getDeclaredField(n) } catch (_: Exception) { null }
                    if (f != null) {
                        f.isAccessible = true
                        val v = f.get(resultObj)
                        when (v) {
                            is Long -> if (v > 0) return v
                            is Int -> if (v > 0) return v.toLong()
                        }
                    }
                } catch (_: Exception) { }
            }
            return null
        }
        // 优先按模式取特定指标，取不到再回落到通用 delay
        val valueByMode = when (method) {
            LatencyTestMethod.TCP -> tryGet(arrayOf("tcpDelay", "getTcpDelay", "tcp", "connectDelay", "getConnectDelay", "connect"))
            LatencyTestMethod.HANDSHAKE -> tryGet(arrayOf("handshakeDelay", "getHandshakeDelay", "tlsDelay", "getTlsDelay", "handshake", "tls"))
            else -> tryGet(arrayOf("delay", "getDelay", "rtt", "latency", "getLatency"))
        }
        if (valueByMode != null) return valueByMode
        // 最后通用兜底
        return tryGet(arrayOf("delay", "getDelay")) ?: -1L
    }

    private fun hasDelayAccessors(rt: Class<*>): Boolean {
        val methodNames = arrayOf(
            "delay", "getDelay", "rtt", "latency", "getLatency",
            "tcpDelay", "getTcpDelay", "connectDelay", "getConnectDelay",
            "handshakeDelay", "getHandshakeDelay", "tlsDelay", "getTlsDelay"
        )
        try {
            for (name in methodNames) {
                try {
                    val m = rt.getMethod(name)
                    val rtpe = m.returnType
                    if ((rtpe == Long::class.javaPrimitiveType || rtpe == Long::class.java ||
                                rtpe == Int::class.javaPrimitiveType || rtpe == Int::class.java) && m.parameterCount == 0) {
                        return true
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        try {
            val fields = rt.declaredFields
            for (f in fields) {
                if (methodNames.any { it.equals(f.name, ignoreCase = true) }) {
                    val t = f.type
                    if (t == Long::class.javaPrimitiveType || t == Long::class.java ||
                        t == Int::class.javaPrimitiveType || t == Int::class.java) {
                        return true
                    }
                }
            }
        } catch (_: Exception) { }
        return false
    }

    private fun buildUrlTestArgs(
        params: Array<Class<*>>,
        outboundJson: String,
        url: String,
        pi: Any
    ): Array<Any> {
        val args = ArrayList<Any>(params.size)
        args.add(outboundJson)
        args.add(url)
        if (params.size >= 3) {
            val p2 = params[2]
            args.add(if (p2 == Int::class.javaPrimitiveType) 5000 else 5000L)
        }
        if (params.size >= 4) {
            args.add(pi)
        }
        return args.toTypedArray()
    }

    private suspend fun testWithLocalHttpProxy(outbound: Outbound, targetUrl: String, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        val port = allocateLocalPort()
        val inbound = com.kunk.singbox.model.Inbound(
            type = "http",
            tag = "test-in",
            listen = "127.0.0.1",
            listenPort = port
        )
        val direct = com.kunk.singbox.model.Outbound(type = "direct", tag = "direct")

        val config = SingBoxConfig(
            log = com.kunk.singbox.model.LogConfig(level = "warn", timestamp = true),
            dns = com.kunk.singbox.model.DnsConfig(
                servers = listOf(
                    com.kunk.singbox.model.DnsServer(tag = "google", address = "8.8.8.8", detour = "direct"),
                    com.kunk.singbox.model.DnsServer(tag = "local", address = "223.5.5.5", detour = "direct")
                )
            ),
            inbounds = listOf(inbound),
            outbounds = listOf(outbound, direct),
            route = com.kunk.singbox.model.RouteConfig(
                rules = listOf(
                    com.kunk.singbox.model.RouteRule(protocol = listOf("dns"), outbound = "direct"),
                    com.kunk.singbox.model.RouteRule(inbound = listOf("test-in"), outbound = outbound.tag)
                ),
                finalOutbound = "direct",
                autoDetectInterface = true
            ),
            experimental = null
        )

        val configJson = gson.toJson(config)
        var service: BoxService? = null
        try {
            ensureLibboxSetup(context)
            val platformInterface = TestPlatformInterface(context)
            service = Libbox.newService(configJson, platformInterface)
            service.start()

            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()

            val req = Request.Builder().url(targetUrl).get().build()
            val t0 = System.nanoTime()
            client.newCall(req).execute().use { resp ->
                // 消耗少量响应体以确保建立完成
                resp.body?.close()
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            elapsedMs
        } catch (e: Exception) {
            Log.w(TAG, "HTTP proxy native test error: ${e.message}")
            -1L
        } finally {
            try { service?.close() } catch (_: Exception) {}
        }
    }
    
    /**
     * 测试单个节点的延迟
     * @param outbound 节点出站配置
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
    suspend fun testOutboundLatency(outbound: Outbound): Long = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
        
        // 当 VPN 正在运行且启用了原生测速，优先使用运行实例原生 URLTest
        if (SingBoxService.isRunning && settings.useLibboxUrlTest) {
            val nativeLatency = testOutboundLatencyWithLibbox(outbound, settings)
            if (nativeLatency >= 0) return@withContext nativeLatency
            // 原生失败时，回退到 Clash API，提升成功率
            clashApiClient.setBaseUrl("http://127.0.0.1:9090")
            return@withContext testOutboundLatencyWithClashApi(outbound)
        }

        // VPN 未运行时：为稳定起见，优先使用临时服务 + Clash API；仅在设置关闭原生时也走 Clash API
        try {
            ensureTestServiceRunning(listOf(outbound))
            var clashLatency = testOutboundLatencyWithClashApi(outbound)
            if (clashLatency < 0 && settings.useLibboxUrlTest) {
                // 服务可能尚在启动，等待片刻再试一次
                delay(250)
                clashLatency = testOutboundLatencyWithClashApi(outbound)
            }
            if (clashLatency >= 0 || !settings.useLibboxUrlTest) return@withContext clashLatency
            // Clash API 仍失败且开启原生，则再尝试原生静态路径
            return@withContext testOutboundLatencyWithLibbox(outbound, settings)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to test latency with temporary service", e)
            -1L
        } finally {
            if (!SingBoxService.isRunning) {
                stopTestService()
            }
        }
    }
    
    /**
     * 批量测试节点延迟
     * @param outbounds 节点列表
     * @param onResult 每个节点测试完成后的回调
     */
    suspend fun testOutboundsLatency(
        outbounds: List<Outbound>,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
        
        // 优先使用原生 URLTest（运行实例路径，降低并发至 6），对 -1 做 Clash API 回退
        if (libboxAvailable && settings.useLibboxUrlTest && SingBoxService.isRunning) {
            // 先做一次轻量预热，避免批量首个请求落在 link 验证/路由冷启动窗口
            try {
                val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
                val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
                maybeWarmupNative(libboxClass, url)
            } catch (_: Exception) { }
            val semaphore = Semaphore(permits = 6)
            coroutineScope {
                val jobs = outbounds.map { outbound ->
                    async {
                        semaphore.withPermit {
                            var latency = testOutboundLatencyWithLibbox(outbound, settings)
                            if (latency < 0) {
                                try {
                                    clashApiClient.setBaseUrl("http://127.0.0.1:9090")
                                    latency = testOutboundLatencyWithClashApi(outbound)
                                } catch (_: Exception) {}
                            }
                            onResult(outbound.tag, latency)
                        }
                    }
                }
                jobs.awaitAll()
            }
            return@withContext
        }

        // 其它情况按 Clash API 路径
        if (SingBoxService.isRunning) {
            performClashApiBatchTest(outbounds, onResult)
            return@withContext
        }

        // 默认使用临时服务的 Clash API 批量测试
        performClashApiBatchTest(outbounds, onResult)
    }

    private suspend fun performClashApiBatchTest(
        outbounds: List<Outbound>,
        onResult: (tag: String, latency: Long) -> Unit
    ) = coroutineScope {
        try {
            if (!SingBoxService.isRunning) {
                ensureTestServiceRunning(outbounds)
            } else {
                clashApiClient.setBaseUrl("http://127.0.0.1:9090")
            }

            val semaphore = kotlinx.coroutines.sync.Semaphore(permits = 4)
            val jobs = outbounds.map { outbound ->
                async {
                    semaphore.withPermit {
                        try {
                            val latency = testOutboundLatencyWithClashApi(outbound)
                            onResult(outbound.tag, latency)
                        } catch (e: Exception) {
                            Log.e(TAG, "Latency test failed for ${outbound.tag}", e)
                            onResult(outbound.tag, -1L)
                        }
                    }
                }
            }
            jobs.awaitAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start test service", e)
            outbounds.forEach { onResult(it.tag, -1L) }
        } finally {
            if (!SingBoxService.isRunning) {
                stopTestService()
            }
        }
    }
    
    /**
     * 确保测试服务正在运行，如果已运行则检查是否需要更新配置
     */
    private suspend fun ensureTestServiceRunning(outbounds: List<Outbound>) {
        val needStart = synchronized(serviceLock) {
            val newTags = outbounds.map { it.tag }.toSet()
            
            // 检查是否需要重启服务（新节点不在当前配置中）
            val needRestart = testService != null && !testServiceOutboundTags.containsAll(newTags)
            
            if (testService != null && !needRestart) {
                // 服务已运行且包含所需节点，重置保活计时器
                Log.v(TAG, "Reusing existing test service")
                testServiceStartTime = System.currentTimeMillis()
                scheduleServiceShutdown()
                return@synchronized false
            }
            
            if (needRestart) {
                Log.d(TAG, "Restarting test service with new nodes")
                stopTestServiceInternal()
            }
            true
        }
        
        // 启动新服务（在锁外执行，避免持锁时间过长）
        if (needStart) {
            startTestServiceWithKeepAlive(outbounds)
        }
    }
    
    /**
     * 启动带保活功能的测试服务
     */
    private suspend fun startTestServiceWithKeepAlive(outbounds: List<Outbound>) {
        synchronized(serviceLock) {
            if (testService != null) return
            
            testServiceClashPort = allocateLocalPort()
            val clashBaseUrl = "http://127.0.0.1:$testServiceClashPort"
            val config = buildBatchTestConfig(outbounds, testServiceClashPort)
            val configJson = gson.toJson(config)
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Batch test config length: ${configJson.length}")
            }
            
            try {
                ensureLibboxSetup(context)

                testServiceClashBaseUrl = clashBaseUrl
                clashApiClient.setBaseUrl(clashBaseUrl)
                testServiceOutboundTags = outbounds.map { it.tag }.toMutableSet()
                testServiceStartTime = System.currentTimeMillis()

                val platformInterface = TestPlatformInterface(context)
                testPlatformInterface = platformInterface
                testService = Libbox.newService(configJson, platformInterface)
                testService?.start()
                Log.i(TAG, "Test service started with keep-alive")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start test service", e)
                stopTestServiceInternal()
                throw e
            }
        }
        
        // 等待 API 就绪
        var retry = 0
        while (retry < 20) {
            delay(100)
            if (clashApiClient.isAvailable()) {
                Log.v(TAG, "Test service API ready after ${(retry + 1) * 100}ms")
                break
            }
            retry++
        }
        if (retry >= 20) {
            Log.e(TAG, "Test service API failed to start")
            stopTestService()
            throw Exception("Test service API failed to start")
        }
        
        // 调度自动关闭
        scheduleServiceShutdown()
    }
    
    private val keepAliveSupervisorJob = SupervisorJob()
    private val keepAliveScope = CoroutineScope(Dispatchers.IO + keepAliveSupervisorJob)

    private fun scheduleServiceShutdown() {
        keepAliveJob?.cancel()
        keepAliveJob = keepAliveScope.launch {
            try {
                delay(TEST_SERVICE_KEEP_ALIVE_MS)
                synchronized(serviceLock) {
                    val elapsed = System.currentTimeMillis() - testServiceStartTime
                    if (elapsed >= TEST_SERVICE_KEEP_ALIVE_MS) {
                        Log.v(TAG, "Test service keep-alive expired, stopping")
                        stopTestServiceInternal()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.v(TAG, "Keep-alive job cancelled")
            }
        }
    }

    /**
     * 强制停止测试服务（公开方法，供外部调用）
     */
    fun stopTestService() {
        synchronized(serviceLock) {
            keepAliveJob?.cancel()
            keepAliveJob = null
            stopTestServiceInternal()
        }
    }
    
    /**
     * 内部停止服务方法（需要在锁内调用）
     */
    private fun stopTestServiceInternal() {
        val serviceToClose = testService
        val oldTestBaseUrl = testServiceClashBaseUrl
        val platformToClose = testPlatformInterface
        testService = null
        testServiceClashBaseUrl = null
        testPlatformInterface = null

        testServiceOutboundTags.clear()
        testServiceStartTime = 0
        testServiceClashPort = 0
        
        try {
            try {
                platformToClose?.closeDefaultInterfaceMonitor(null)
            } catch (_: Exception) {
            }
            serviceToClose?.close()
            if (serviceToClose != null) {
                Log.i(TAG, "Test service stopped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping test service", e)
        } finally {
            oldTestBaseUrl?.let {
                clashApiClient.setBaseUrl("http://127.0.0.1:9090")
            }
        }
    }
    
    /**
     * 检查测试服务是否正在运行
     */
    fun isTestServiceRunning(): Boolean = synchronized(serviceLock) { testService != null }

    private fun buildBatchTestConfig(outbounds: List<Outbound>, clashPort: Int): SingBoxConfig {
        // 创建一个包含所有待测试节点的配置
        // 必须确保 tags 唯一，这里假设传入的 outbounds 已经是唯一的或者 sing-box 能处理
        // 添加一个 direct 出站以防万一
        val direct = Outbound(type = "direct", tag = "direct")
        
        // 过滤掉 direct 和 block，避免重复（虽然 config repo 应该已经处理了）
        val testOutbounds = ArrayList<Outbound>()
        testOutbounds.addAll(outbounds.filter { it.type != "direct" && it.type != "block" })
        testOutbounds.add(direct)
        
              return SingBoxConfig(
                  log = com.kunk.singbox.model.LogConfig(level = "warn", timestamp = true),
                  dns = com.kunk.singbox.model.DnsConfig(
                      servers = listOf(
                          com.kunk.singbox.model.DnsServer(tag = "google", address = "8.8.8.8", detour = "direct"),
                          com.kunk.singbox.model.DnsServer(tag = "local", address = "223.5.5.5", detour = "direct")
                      )
                  ),
              experimental = com.kunk.singbox.model.ExperimentalConfig(
                        clashApi = com.kunk.singbox.model.ClashApiConfig(
                            externalController = "127.0.0.1:$clashPort",
                            secret = com.kunk.singbox.utils.SecurityUtils.getClashApiSecret()
                        ),
                      cacheFile = com.kunk.singbox.model.CacheFileConfig(
                          enabled = false,
                          path = File(tempDir, "cache_test.db").absolutePath,
                          storeFakeip = false
                      )
                  ),
              // 不包含 inbounds，这样 libbox 不会尝试打开 TUN
              inbounds = null,
              outbounds = testOutbounds,
              route = com.kunk.singbox.model.RouteConfig(
                  rules = listOf(
                      com.kunk.singbox.model.RouteRule(protocol = listOf("dns"), outbound = "direct")
                  ),
                  finalOutbound = "direct",
                  autoDetectInterface = true
              )
          )
    }

    private fun allocateLocalPort(): Int {
        var attempts = 0
        val maxAttempts = 10
        while (attempts < maxAttempts) {
            try {
                val socket = ServerSocket(0)
                socket.reuseAddress = true
                val port = socket.localPort
                socket.close()
                if (isPortAvailable(port)) {
                    return port
                }
            } catch (e: Exception) {
                Log.w(TAG, "Port allocation attempt $attempts failed", e)
            }
            attempts++
        }
        throw RuntimeException("Failed to allocate local port after $maxAttempts attempts")
    }
    
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 校准基准延迟（测量非网络开销）
     * 通过测试 direct 出站访问本地 Clash API 的响应时间
     */
    private val baselineMutex = Mutex()
    private suspend fun calibrateBaseline() {
        if (baselineCalibrated) return
        
        baselineMutex.withLock {
            if (baselineCalibrated) return@withLock
            
            try {
                // 测试 direct 出站访问本地 Clash API 的延迟作为基准
                val startTime = System.currentTimeMillis()
                val available = clashApiClient.isAvailable()
                val elapsed = System.currentTimeMillis() - startTime
                
                if (available) {
                    // 多次测量取平均值
                    var totalTime = elapsed
                    repeat(2) {
                        val t1 = System.currentTimeMillis()
                        clashApiClient.isAvailable()
                        totalTime += System.currentTimeMillis() - t1
                    }
                    baselineLatency = totalTime / 3
                    baselineCalibrated = true
                    Log.v(TAG, "Baseline latency calibrated: ${baselineLatency}ms")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to calibrate baseline", e)
            }
        }
    }
    
    /**
     * 校正延迟值，减去非网络开销
     */
    private fun calibrateLatency(rawLatency: Long): Long {
        if (rawLatency <= 0) return rawLatency
        if (!baselineCalibrated || baselineLatency <= 0) return rawLatency
        
        val calibrated = rawLatency - baselineLatency
        return if (calibrated > 0) calibrated else rawLatency
    }

    /**
     * 使用 Clash API 进行真实延迟测试（VPN 运行时）
     */
    private suspend fun testOutboundLatencyWithClashApi(outbound: Outbound): Long {
        return try {
            // 如果尚未校准，先校准基准延迟
            if (!baselineCalibrated) {
                calibrateBaseline()
            }
            
            val settings = SettingsRepository.getInstance(context).settings.first()
            val type = when (settings.latencyTestMethod) {
                LatencyTestMethod.TCP -> "tcp"
                LatencyTestMethod.HANDSHAKE -> "handshake"
                else -> "real"
            }
            
            val rawDelay = clashApiClient.testProxyDelay(outbound.tag, testUrl = settings.latencyTestUrl, type = type)
            if (rawDelay > 0) {
                val calibratedDelay = calibrateLatency(rawDelay)
                Log.v(TAG, "Latency for ${outbound.tag}: raw=${rawDelay}ms, type=$type, calibrated=${calibratedDelay}ms (baseline=${baselineLatency}ms)")
                calibratedDelay
            } else {
                Log.w(TAG, "Latency test failed for ${outbound.tag} (result: $rawDelay, type=$type)")
                rawDelay
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clash API latency test failed for ${outbound.tag}: ${e.message}")
            -1L
        }
    }

    /**
     * 验证配置是否有效
     */
    suspend fun validateConfig(config: SingBoxConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (!libboxAvailable) {
            return@withContext try {
                gson.toJson(config)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        
        try {
            val configJson = gson.toJson(config)
            val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
            val checkConfigMethod = libboxClass.getMethod("checkConfig", String::class.java)
            checkConfigMethod.invoke(null, configJson)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Config validation failed", e)
            Result.failure(e)
        }
    }
    
    fun formatConfig(config: SingBoxConfig): String = gson.toJson(config)
    
    fun getTestModeDescription(): String = "使用 Clash API 进行真实延迟测试"
    
    fun canTestRealLatency(): Boolean = true // Always true now as we spin up service if needed
    
    fun getClashApiClient(): ClashApiClient = clashApiClient

    // --- Inner Classes for Platform Interface ---

    private class TestPlatformInterface(private val context: Context) : PlatformInterface {
        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private var networkCallback: ConnectivityManager.NetworkCallback? = null
        private var currentInterfaceListener: InterfaceUpdateListener? = null
        private var defaultInterfaceName: String = ""

        override fun autoDetectInterfaceControl(fd: Int) {
            // No TUN, no protect needed usually, but safe to ignore or log
        }

        override fun openTun(options: TunOptions?): Int {
            // Should not be called as we don't provide tun inbound
            Log.w(TAG, "TestPlatformInterface: openTun called unexpected!")
            return -1
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            currentInterfaceListener = listener
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateDefaultInterface(network)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    updateDefaultInterface(network)
                }
                override fun onLost(network: Network) {
                    currentInterfaceListener?.updateDefaultInterface("", 0, false, false)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                connectivityManager.registerNetworkCallback(request, networkCallback!!)
                connectivityManager.activeNetwork?.let { updateDefaultInterface(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start interface monitor", e)
            }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            networkCallback?.let { 
                try {
                    connectivityManager.unregisterNetworkCallback(it) 
                } catch (e: Exception) {}
            }
            networkCallback = null
            currentInterfaceListener = null
        }

        private fun updateDefaultInterface(network: Network) {
            try {
                val linkProperties = connectivityManager.getLinkProperties(network)
                val interfaceName = linkProperties?.interfaceName ?: ""
                if (interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName) {
                    defaultInterfaceName = interfaceName
                    val index = try {
                        NetworkInterface.getByName(interfaceName)?.index ?: 0
                    } catch (e: Exception) { 0 }
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
                    val isConstrained = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) != true
                    currentInterfaceListener?.updateDefaultInterface(interfaceName, index, isExpensive, isConstrained)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update default interface", e)
            }
        }

        override fun getInterfaces(): NetworkInterfaceIterator? {
            return try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                object : NetworkInterfaceIterator {
                    private val iterator = interfaces.filter { it.isUp && !it.isLoopback }.iterator()
                    override fun hasNext(): Boolean = iterator.hasNext()
                    override fun next(): io.nekohasekai.libbox.NetworkInterface {
                        val iface = iterator.next()
                        return io.nekohasekai.libbox.NetworkInterface().apply {
                            name = iface.name
                            index = iface.index
                            mtu = iface.mtu
                            type = when {
                                iface.name.startsWith("wlan") -> 0
                                iface.name.startsWith("rmnet") || iface.name.startsWith("ccmni") -> 1
                                iface.name.startsWith("eth") -> 2
                                else -> 3
                            }
                            var flagsStr = 0
                            if (iface.isUp) flagsStr = flagsStr or 1
                            if (iface.isLoopback) flagsStr = flagsStr or 4
                            if (iface.isPointToPoint) flagsStr = flagsStr or 8
                            if (iface.supportsMulticast()) flagsStr = flagsStr or 16
                            flags = flagsStr
                            val addrList = ArrayList<String>()
                            for (addr in iface.interfaceAddresses) {
                                val ip = addr.address.hostAddress
                                val cleanIp = if (ip != null && ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                                if (cleanIp != null) addrList.add("$cleanIp/${addr.networkPrefixLength}")
                            }
                            addresses = StringIteratorImpl(addrList)
                        }
                    }
                }
            } catch (e: Exception) { null }
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
        override fun useProcFS(): Boolean = false
        override fun findConnectionOwner(p0: Int, p1: String?, p2: Int, p3: String?, p4: Int): Int = 0
        override fun packageNameByUid(p0: Int): String = ""
        override fun uidByPackageName(p0: String?): Int = 0
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() {}
        override fun sendNotification(p0: io.nekohasekai.libbox.Notification?) {}
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun systemCertificates(): StringIterator? = null
        override fun writeLog(message: String?) {
            Log.v("SingBoxCoreTest", "libbox: $message")
            message?.let {
                com.kunk.singbox.repository.LogRepository.getInstance().addLog("[Test] $it")
            }
        }
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }
    
    fun cleanup() {
        synchronized(serviceLock) {
            keepAliveJob?.cancel()
            keepAliveJob = null
            keepAliveSupervisorJob.cancel()
            stopTestServiceInternal()
        }
    }
}
