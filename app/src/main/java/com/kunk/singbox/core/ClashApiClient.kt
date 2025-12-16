package com.kunk.singbox.core

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Clash API 客户端
 * 用于通过 sing-box 的 Clash API 测试节点延迟
 */
class ClashApiClient(
    private val baseUrl: String = "http://127.0.0.1:9090",
    private val secret: String = ""
) {
    companion object {
        private const val TAG = "ClashApiClient"
        private const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
        private const val DEFAULT_TIMEOUT = 5000L
    }
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    /**
     * 获取所有代理节点
     */
    suspend fun getProxies(): ProxiesResponse? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/proxies")
                .apply {
                    if (secret.isNotEmpty()) {
                        addHeader("Authorization", "Bearer $secret")
                    }
                }
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.let { body ->
                        gson.fromJson(body, ProxiesResponse::class.java)
                    }
                } else {
                    Log.e(TAG, "Failed to get proxies: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting proxies", e)
            null
        }
    }
    
    /**
     * 测试单个节点延迟
     * @param proxyName 节点名称
     * @param testUrl 测试 URL
     * @param timeout 超时时间（毫秒）
     * @return 延迟（毫秒），-1 表示失败
     */
    suspend fun testProxyDelay(
        proxyName: String,
        testUrl: String = DEFAULT_TEST_URL,
        timeout: Long = DEFAULT_TIMEOUT
    ): Long = withContext(Dispatchers.IO) {
        try {
            val encodedName = java.net.URLEncoder.encode(proxyName, "UTF-8")
            val url = "$baseUrl/proxies/$encodedName/delay?timeout=$timeout&url=$testUrl"
            
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (secret.isNotEmpty()) {
                        addHeader("Authorization", "Bearer $secret")
                    }
                }
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.let { body ->
                        val result = gson.fromJson(body, DelayResponse::class.java)
                        Log.d(TAG, "Delay for $proxyName: ${result.delay}ms")
                        result.delay.toLong()
                    } ?: -1L
                } else {
                    Log.e(TAG, "Delay test failed for $proxyName: ${response.code}")
                    -1L
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error testing delay for $proxyName", e)
            -1L
        }
    }
    
    /**
     * 批量测试节点延迟
     */
    suspend fun testProxiesDelay(
        proxyNames: List<String>,
        testUrl: String = DEFAULT_TEST_URL,
        timeout: Long = DEFAULT_TIMEOUT,
        onResult: (name: String, delay: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        for (name in proxyNames) {
            val delay = testProxyDelay(name, testUrl, timeout)
            onResult(name, delay)
        }
    }
    
    /**
     * 检查 Clash API 是否可用
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/version")
                .apply {
                    if (secret.isNotEmpty()) {
                        addHeader("Authorization", "Bearer $secret")
                    }
                }
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
}

// API 响应数据类
data class ProxiesResponse(
    @SerializedName("proxies")
    val proxies: Map<String, ProxyInfo>
)

data class ProxyInfo(
    @SerializedName("name")
    val name: String,
    @SerializedName("type")
    val type: String,
    @SerializedName("all")
    val all: List<String>? = null,
    @SerializedName("now")
    val now: String? = null,
    @SerializedName("history")
    val history: List<HistoryItem>? = null
)

data class HistoryItem(
    @SerializedName("time")
    val time: String,
    @SerializedName("delay")
    val delay: Int
)

data class DelayResponse(
    @SerializedName("delay")
    val delay: Int
)
