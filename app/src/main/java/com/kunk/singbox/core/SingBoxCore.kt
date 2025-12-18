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
import com.kunk.singbox.service.SingBoxService
import io.nekohasekai.libbox.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    
    /**
     * 测试单个节点的延迟
     * @param outbound 节点出站配置
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
    suspend fun testOutboundLatency(outbound: Outbound): Long = withContext(Dispatchers.IO) {
        if (SingBoxService.isRunning) {
            // VPN 运行时，确保使用正确的 Clash API 地址
            clashApiClient.setBaseUrl("http://127.0.0.1:9090")
            return@withContext testOutboundLatencyWithClashApi(outbound)
        }
        
        try {
            ensureTestServiceRunning(listOf(outbound))
            testOutboundLatencyWithClashApi(outbound)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to test latency with temporary service", e)
            -1L
        } finally {
            stopTestService()
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
        testService = null
        testServiceClashBaseUrl = null

        testServiceOutboundTags.clear()
        testServiceStartTime = 0
        testServiceClashPort = 0
        
        try {
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
            
            val rawDelay = clashApiClient.testProxyDelay(outbound.tag)
            if (rawDelay > 0) {
                val calibratedDelay = calibrateLatency(rawDelay)
                Log.v(TAG, "Latency for ${outbound.tag}: raw=${rawDelay}ms, calibrated=${calibratedDelay}ms (baseline=${baselineLatency}ms)")
                calibratedDelay
            } else {
                Log.w(TAG, "Latency test failed for ${outbound.tag} (result: $rawDelay)")
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