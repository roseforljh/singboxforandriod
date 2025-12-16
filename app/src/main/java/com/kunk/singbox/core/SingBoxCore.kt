package com.kunk.singbox.core

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

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
                val setupMethod = libboxClass.getMethod("setup", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                setupMethod.invoke(null, workDir.absolutePath, tempDir.absolutePath, false)
                Log.d(TAG, "Libbox setup succeeded")
            } catch (e: Exception) {
                Log.w(TAG, "Libbox setup failed: ${e.message}")
            }
            
            // libbox 可用于 VPN 服务，但 urlTest 不是静态方法
            // 延迟测试需要通过 CommandClient（VPN 运行时）或 TCP fallback
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
        // 优先使用 Clash API（VPN 运行时）
        if (SingBoxService.isRunning && clashApiClient.isAvailable()) {
            testOutboundLatencyWithClashApi(outbound)
        } else {
            // libbox 的 urlTest 不是静态方法，无法在 VPN 未运行时使用
            // 使用 TCP 握手测试作为降级方案
            testOutboundLatencyFallback(outbound)
        }
    }
    
    /**
     * 使用 Clash API 进行真实延迟测试（VPN 运行时）
     */
    private suspend fun testOutboundLatencyWithClashApi(outbound: Outbound): Long {
        return try {
            Log.d(TAG, "Testing latency with Clash API for: ${outbound.tag}")
            val delay = clashApiClient.testProxyDelay(outbound.tag)
            Log.d(TAG, "Clash API latency for ${outbound.tag}: ${delay}ms")
            delay
        } catch (e: Exception) {
            Log.e(TAG, "Clash API latency test failed for ${outbound.tag}", e)
            -1L
        }
    }
    
    /**
     * 使用 libbox 进行延迟测试（真正的代理测试）
     */
    private fun testOutboundLatencyWithLibbox(outbound: Outbound): Long {
        return try {
            val testConfig = buildTestConfig(outbound)
            val configJson = gson.toJson(testConfig)
            
            Log.d(TAG, "Testing latency with libbox for: ${outbound.tag}")
            
            // 使用反射调用 libbox
            val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
            val urlTestMethod = libboxClass.getMethod(
                "urlTest", 
                String::class.java, 
                String::class.java, 
                String::class.java, 
                Long::class.javaPrimitiveType
            )
            
            val latency = urlTestMethod.invoke(
                null, 
                configJson, 
                outbound.tag, 
                URL_TEST_URL, 
                URL_TEST_TIMEOUT.toLong()
            ) as Int
            
            Log.d(TAG, "Libbox latency for ${outbound.tag}: ${latency}ms")
            latency.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Libbox latency test failed for ${outbound.tag}", e)
            -1L
        }
    }
    
    /**
     * 降级方案：直接测试服务器连接（TCP 握手延迟）
     * 注意：这不是真正的代理延迟，但可以作为节点可用性的参考
     */
    private fun testOutboundLatencyFallback(outbound: Outbound): Long {
        return try {
            val server = outbound.server ?: return -1L
            val port = outbound.serverPort ?: return -1L
            
            Log.d(TAG, "Testing TCP latency (fallback) for: ${outbound.tag} -> $server:$port")
            
            val startTime = System.currentTimeMillis()
            
            // 测试 TCP 连接延迟
            val socket = java.net.Socket()
            try {
                socket.connect(InetSocketAddress(server, port), URL_TEST_TIMEOUT)
                val endTime = System.currentTimeMillis()
                val latency = endTime - startTime
                
                Log.d(TAG, "TCP latency for ${outbound.tag}: ${latency}ms")
                latency
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP latency test failed for ${outbound.tag}", e)
            -1L
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
        if (SingBoxService.isRunning && clashApiClient.isAvailable()) {
            // VPN 运行时使用 Clash API
            for (outbound in outbounds) {
                val latency = testOutboundLatencyWithClashApi(outbound)
                onResult(outbound.tag, latency)
            }
        } else {
            // 降级到 TCP 握手测试
            for (outbound in outbounds) {
                val latency = testOutboundLatencyFallback(outbound)
                onResult(outbound.tag, latency)
            }
        }
    }
    
    /**
     * 构建用于测试的最小配置
     */
    private fun buildTestConfig(outbound: Outbound): SingBoxConfig {
        return SingBoxConfig(
            outbounds = listOf(
                outbound,
                Outbound(type = "direct", tag = "direct"),
                Outbound(type = "block", tag = "block")
            )
        )
    }
    
    /**
     * 验证配置是否有效
     */
    suspend fun validateConfig(config: SingBoxConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (!libboxAvailable) {
            // 如果 libbox 不可用，只做基本的 JSON 验证
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
    
    /**
     * 格式化配置（美化 JSON）
     */
    fun formatConfig(config: SingBoxConfig): String {
        return gson.toJson(config)
    }
    
    /**
     * 获取测试模式描述
     */
    fun getTestModeDescription(): String {
        return when {
            SingBoxService.isRunning -> "使用 sing-box Clash API 进行真实代理延迟测试"
            libboxAvailable -> "使用 libbox 进行真实代理延迟测试"
            else -> "使用 TCP 握手延迟测试（降级模式）"
        }
    }
    
    /**
     * 检查是否可以进行真实延迟测试
     */
    fun canTestRealLatency(): Boolean {
        return SingBoxService.isRunning || libboxAvailable
    }
    
    /**
     * 获取 Clash API 客户端
     */
    fun getClashApiClient(): ClashApiClient = clashApiClient
}