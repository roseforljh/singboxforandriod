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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections

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
    
    companion object {
        private const val TAG = "SingBoxCore"
        private const val URL_TEST_URL = "https://www.gstatic.com/generate_204"
        private const val URL_TEST_TIMEOUT = 5000 // 5 seconds
        
        @Volatile
        private var instance: SingBoxCore? = null
        
        fun getInstance(context: Context): SingBoxCore {
            return instance ?: synchronized(this) {
                instance ?: SingBoxCore(context.applicationContext).also { 
                    instance = it 
                }
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
            Log.d(TAG, "Libbox initialized successfully")
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
                val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
                Log.d(TAG, "Libbox class loaded successfully")
                
                // 尝试 setup
                try {
                    // public static void setup(String baseDir, String workingDir, String tempDir, boolean debug)
                    val setupMethod = libboxClass.getMethod("setup", String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    // Base dir should be passed as the first argument
                    setupMethod.invoke(null, context.filesDir.absolutePath, workDir.absolutePath, tempDir.absolutePath, false)
                    Log.d(TAG, "Libbox setup succeeded")
                } catch (e: NoSuchMethodException) {
                     // Try old signature if new one fails (String, String, boolean)
                     try {
                        val setupMethod = libboxClass.getMethod("setup", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                        setupMethod.invoke(null, workDir.absolutePath, tempDir.absolutePath, false)
                        Log.d(TAG, "Libbox setup succeeded (legacy)")
                     } catch (e2: Exception) {
                         Log.w(TAG, "Libbox setup failed: ${e2.message}")
                     }
                } catch (e: Exception) {
                    Log.w(TAG, "Libbox setup failed: ${e.message}")
                }
            
            Log.d(TAG, "Libbox available for VPN service")
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
            return@withContext testOutboundLatencyWithClashApi(outbound)
        }
        
        // 如果 VPN 未运行，启动临时服务进行测试
        // 注意：单个节点测试启动服务开销较大，建议使用 testOutboundsLatency 批量测试
        try {
            startTestService(listOf(outbound))
            // 等待服务完全启动和 API 就绪
            var retry = 0
            while (retry < 10) {
                delay(500)
                if (clashApiClient.isAvailable()) break
                retry++
            }
            if (retry >= 10) {
                Log.e(TAG, "Temporary service API failed to start")
                return@withContext -1L
            }
            val latency = testOutboundLatencyWithClashApi(outbound)
            return@withContext latency
        } catch (e: Exception) {
            Log.e(TAG, "Failed to test latency with temporary service", e)
            return@withContext -1L
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
        var serviceStartedByMe = false
        
        try {
            if (!SingBoxService.isRunning) {
                Log.i(TAG, "VPN not running, starting temporary service for real latency test")
                try {
                    startTestService(outbounds)
                    serviceStartedByMe = true
                    // 等待服务完全启动和 API 就绪
                    // 简单的延时，实际应该轮询 API 直到成功
                    var retry = 0
                    while (retry < 10) {
                        delay(500)
                        if (clashApiClient.isAvailable()) break
                        retry++
                    }
                    if (retry >= 10) {
                        Log.e(TAG, "Temporary service API failed to start")
                        outbounds.forEach { onResult(it.tag, -1L) }
                        return@withContext
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start temporary test service", e)
                    outbounds.forEach { onResult(it.tag, -1L) }
                    return@withContext
                }
            } else {
                Log.i(TAG, "VPN is running, using existing service for latency test")
            }

            Log.i(TAG, "Starting real latency test for ${outbounds.size} nodes via Clash API")
            
            for (outbound in outbounds) {
                val latency = testOutboundLatencyWithClashApi(outbound)
                onResult(outbound.tag, latency)
            }
        } finally {
            if (serviceStartedByMe) {
                stopTestService()
            }
        }
    }

    private fun startTestService(outbounds: List<Outbound>) {
        if (testService != null) return

        val clashPort = allocateLocalPort()
        val clashBaseUrl = "http://127.0.0.1:$clashPort"
        val config = buildBatchTestConfig(outbounds, clashPort)
        val configJson = gson.toJson(config)
        Log.d(TAG, "Batch test config: $configJson")
        
        try {
            val setupOptions = SetupOptions().apply {
                basePath = context.filesDir.absolutePath
                workingPath = workDir.absolutePath
                this.tempPath = tempDir.absolutePath
            }
            try {
                Libbox.setup(setupOptions)
            } catch (e: Exception) {
                Log.w(TAG, "Libbox setup warning: ${e.message}")
            }

            // Point ClashApiClient to the temporary service controller.
            testServiceClashBaseUrl = clashBaseUrl
            clashApiClient.setBaseUrl(clashBaseUrl)

            // 使用 TestPlatformInterface，它不会尝试建立 TUN
            val platformInterface = TestPlatformInterface(context)
            testService = Libbox.newService(configJson, platformInterface)
            testService?.start()
            Log.d(TAG, "Temporary test service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start temporary test service", e)
            stopTestService()
            throw e
        }
    }
    
    private fun stopTestService() {
        val serviceToClose = testService
        val oldTestBaseUrl = testServiceClashBaseUrl
        testService = null
        testServiceClashBaseUrl = null

        try {
            serviceToClose?.close()
            if (serviceToClose != null) {
                Log.d(TAG, "Temporary test service stopped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping test service", e)
        } finally {
            // Restore default controller for the main service.
            oldTestBaseUrl?.let {
                clashApiClient.setBaseUrl("http://127.0.0.1:9090")
            }
        }
    }

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
                          secret = ""
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
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    /**
     * 使用 Clash API 进行真实延迟测试（VPN 运行时）
     */
    private suspend fun testOutboundLatencyWithClashApi(outbound: Outbound): Long {
        return try {
            val delay = clashApiClient.testProxyDelay(outbound.tag)
            if (delay > 0) {
                Log.d(TAG, "Real latency for ${outbound.tag}: ${delay}ms")
            } else {
                Log.w(TAG, "Real latency test failed for ${outbound.tag} (result: $delay)")
            }
            delay
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
            Log.d("SingBoxCoreTest", "libbox: $message")
        }
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }
}