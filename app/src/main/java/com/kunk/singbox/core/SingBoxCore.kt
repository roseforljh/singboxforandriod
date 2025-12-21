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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
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

    companion object {
        private const val TAG = "SingBoxCore"

        private val libboxSetupDone = AtomicBoolean(false)
        // 最近一次原生测速预热时间（避免每次都预热）
        @Volatile
        private var lastNativeWarmupAt: Long = 0

        @Volatile
        private var instance: SingBoxCore? = null

        fun getInstance(context: Context): SingBoxCore {
            return instance ?: synchronized(this) {
                instance ?: SingBoxCore(context.applicationContext).also { instance = it }
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
     * 优先尝试调用 NekoBox 内核的 urlTest 方法，失败则回退到本地 HTTP 代理测速
     */
    private suspend fun testOutboundLatencyWithLibbox(outbound: Outbound, settings: com.kunk.singbox.model.AppSettings? = null): Long = withContext(Dispatchers.IO) {
        if (!libboxAvailable) return@withContext -1L
        
        val finalSettings = settings ?: SettingsRepository.getInstance(context).settings.first()
        val url = adjustUrlForMode(finalSettings.latencyTestUrl, finalSettings.latencyTestMethod)
        
        // 尝试使用 NekoBox 原生 urlTest
        val nativeRtt = testWithLibboxStaticUrlTest(outbound, url, 5000, finalSettings.latencyTestMethod)
        if (nativeRtt >= 0) {
            return@withContext nativeRtt
        }

        // 回退方案：本地 HTTP 代理测速
        return@withContext try {
            val fallbackUrl = try {
                if (finalSettings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                    adjustUrlForMode("http://cp.cloudflare.com/generate_204", finalSettings.latencyTestMethod)
                } else {
                    adjustUrlForMode("https://cp.cloudflare.com/generate_204", finalSettings.latencyTestMethod)
                }
            } catch (_: Exception) { url }
            testWithLocalHttpProxy(outbound, url, fallbackUrl, 5000)
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

    private suspend fun testWithLocalHttpProxy(outbound: Outbound, targetUrl: String, fallbackUrl: String? = null, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        val port = allocateLocalPort()
        val inbound = com.kunk.singbox.model.Inbound(
            type = "http",
            tag = "test-in",
            listen = "127.0.0.1",
            listenPort = port
        )
        val direct = com.kunk.singbox.model.Outbound(type = "direct", tag = "direct")

        val settings = SettingsRepository.getInstance(context).settings.first()

        val config = SingBoxConfig(
            log = com.kunk.singbox.model.LogConfig(level = "warn", timestamp = true),
            dns = com.kunk.singbox.model.DnsConfig(
                servers = listOf(
                    com.kunk.singbox.model.DnsServer(
                        tag = "dns-bootstrap",
                        address = "223.5.5.5",
                        detour = "direct",
                        strategy = "ipv4_only"
                    ),
                    com.kunk.singbox.model.DnsServer(
                        tag = "local",
                        address = settings.localDns.ifBlank { "https://dns.alidns.com/dns-query" },
                        detour = "direct",
                        addressResolver = "dns-bootstrap"
                    ),
                    com.kunk.singbox.model.DnsServer(
                        tag = "remote",
                        address = settings.remoteDns.ifBlank { "https://dns.google/dns-query" },
                        detour = "direct",
                        addressResolver = "dns-bootstrap"
                    )
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

            // Let the core initialize routing/DNS briefly to avoid measuring cold-start overhead.
            delay(150)

            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()

            fun runOnce(url: String): Long {
                val req = Request.Builder().url(url).get().build()
                val t0 = System.nanoTime()
                client.newCall(req).execute().use { resp ->
                    if (resp.code >= 400) {
                        throw java.io.IOException("HTTP proxy test failed with code=${resp.code}")
                    }
                    resp.body?.close()
                }
                return (System.nanoTime() - t0) / 1_000_000
            }

            try {
                runOnce(targetUrl)
            } catch (e: Exception) {
                val fb = fallbackUrl
                if (!fb.isNullOrBlank() && fb != targetUrl) {
                    try {
                        runOnce(fb)
                    } catch (e2: Exception) {
                        Log.w(TAG, "HTTP proxy native test error: primary=${e.message}, fallback=${e2.message}")
                        -1L
                    }
                } else {
                    Log.w(TAG, "HTTP proxy native test error: ${e.message}")
                    -1L
                }
            }
        } finally {
            try { service?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun testWithLibboxStaticUrlTest(
        outbound: Outbound,
        targetUrl: String,
        timeoutMs: Int,
        method: LatencyTestMethod
    ): Long = withContext(Dispatchers.IO) {
        if (!libboxAvailable) return@withContext -1L
        try {
            ensureLibboxSetup(context)
            val selectorJson = "{\"tag\":\"" + outbound.tag + "\"}"

            // 动态查找 NekoBox 原生 urlTest 方法
            if (discoveredUrlTestMethod == null) {
                val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
                // 查找签名匹配的方法：urlTest(String groupTag, String url, long timeout, PlatformInterface pi)
                // 注意：NekoBox 的实现可能参数略有不同，需要灵活匹配
                for (method in libboxClass.methods) {
                    if (method.name.equals("urlTest", ignoreCase = true) ||
                        method.name.equals("urlTestOnRunning", ignoreCase = true)) {
                        
                        // 简单的参数签名检查，根据实际 libbox 调整
                        // 通常是 (String groupTag, String url, long/int timeout, ...)
                        val paramTypes = method.parameterTypes
                        if (paramTypes.size >= 2 &&
                            paramTypes[0] == String::class.java &&
                            paramTypes[1] == String::class.java) {
                            
                            discoveredUrlTestMethod = method
                            Log.i(TAG, "Found native URLTest method: ${method.name}")
                            break
                        }
                    }
                }
            }

            val m = discoveredUrlTestMethod
            if (m == null) {
                // Log.d(TAG, "Native URLTest method not found, will use fallback.")
                return@withContext -1L
            }

            return@withContext try {
                val pi = TestPlatformInterface(context)
                val args = buildUrlTestArgs(m.parameterTypes, selectorJson, targetUrl, pi)
                val result = m.invoke(null, *args)
                val rtt = when {
                    m.returnType == Long::class.javaPrimitiveType -> result as Long
                    else -> extractDelayFromUrlTest(result, method)
                }
                if (rtt < 0) {
                    Log.w(TAG, "Offline URLTest RTT returned negative: $rtt")
                }
                rtt
            } catch (e: Exception) {
                Log.w(TAG, "Offline URLTest RTT invoke failed: ${e.javaClass.simpleName}: ${e.message}")
                -1L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline URLTest RTT setup failed: ${e.javaClass.simpleName}: ${e.message}")
            -1L
        }
    }

    private suspend fun testWithTemporaryServiceUrlTestOnRunning(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        method: LatencyTestMethod
    ): Long = withContext(Dispatchers.IO) {
        // 尝试调用 native 方法 (如果 VPN 正在运行)
        if (SingBoxService.isRunning && libboxAvailable) {
             val rtt = testWithLibboxStaticUrlTest(outbound, targetUrl, timeoutMs, method)
             if (rtt >= 0) return@withContext rtt
        }
        
        // 内核不支持或未运行，直接走 HTTP 代理测速
        testWithLocalHttpProxy(outbound, targetUrl, fallbackUrl, timeoutMs)
    }

    private suspend fun testOutboundsLatencyOfflineWithTemporaryService(
        outbounds: List<Outbound>,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        method: LatencyTestMethod,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        outbounds.forEach { outbound ->
            onResult(outbound.tag, testWithLocalHttpProxy(outbound, targetUrl, fallbackUrl, timeoutMs))
        }
    }

    /**
     * 测试单个节点的延迟
     * @param outbound 节点出站配置
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
    suspend fun testOutboundLatency(outbound: Outbound): Long = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()

        // When VPN is running, prefer running-instance URLTest.
        // When VPN is stopped, try Libbox static URLTest first, then local HTTP proxy fallback.
        if (SingBoxService.isRunning) {
            return@withContext testOutboundLatencyWithLibbox(outbound, settings)
        }

        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)

        val fallbackUrl = try {
            if (settings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                adjustUrlForMode("http://cp.cloudflare.com/generate_204", settings.latencyTestMethod)
            } else {
                adjustUrlForMode("https://cp.cloudflare.com/generate_204", settings.latencyTestMethod)
            }
        } catch (_: Exception) { url }

        val rtt = testWithTemporaryServiceUrlTestOnRunning(outbound, url, fallbackUrl, 5000, settings.latencyTestMethod)
        if (rtt >= 0) {
            Log.i(TAG, "Offline URLTest RTT: ${outbound.tag} -> ${rtt} ms")
            return@withContext rtt
        }

        val fallback = testWithLocalHttpProxy(outbound, url, fallbackUrl, 5000)
        Log.i(TAG, "Offline HTTP fallback: ${outbound.tag} -> ${fallback} ms")
        return@withContext fallback
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

        // Native-only batch test: libbox URLTest first.
        if (libboxAvailable && SingBoxService.isRunning) {
            // 先做一次轻量预热，避免批量首个请求落在 link 验证/路由冷启动窗口
            try {
                val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
                val warmupOutbound = outbounds.firstOrNull()
                if (warmupOutbound != null) {
                    val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
                    maybeWarmupNative(libboxClass, url)
                }
            } catch (_: Exception) { }
            val semaphore = Semaphore(permits = 6)
            coroutineScope {
                val jobs = outbounds.map { outbound ->
                    async {
                        semaphore.withPermit {
                            val latency = testOutboundLatencyWithLibbox(outbound, settings)
                            onResult(outbound.tag, latency)
                        }
                    }
                }
                jobs.awaitAll()
            }
            return@withContext
        }

        // VPN is not running: create one temporary registered core and test outbounds on it.
        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
        val fallbackUrl = try {
            if (settings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                adjustUrlForMode("http://cp.cloudflare.com/generate_204", settings.latencyTestMethod)
            } else {
                adjustUrlForMode("https://cp.cloudflare.com/generate_204", settings.latencyTestMethod)
            }
        } catch (_: Exception) { url }
        testOutboundsLatencyOfflineWithTemporaryService(outbounds, url, fallbackUrl, 5000, settings.latencyTestMethod, onResult)
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

        override fun usePlatformInterfaceGetter(): Boolean = true

        override fun usePlatformDefaultInterfaceMonitor(): Boolean = true

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
                    currentInterfaceListener?.updateDefaultInterface("", 0)
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
                    currentInterfaceListener?.updateDefaultInterface(interfaceName, index)
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
                            // type = ... (Field removed/renamed in v1.10)
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
        // override fun localDNSTransport(): LocalDNSTransport? = null
        // override fun systemCertificates(): StringIterator? = null
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
    }
}
