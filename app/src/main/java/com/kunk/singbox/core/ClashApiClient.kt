package com.kunk.singbox.core

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.SocketException
import java.util.concurrent.TimeUnit

/**
 * Clash API 客户端
 * 用于通过 sing-box 的 Clash API 测试节点延迟
 */
class ClashApiClient(
    baseUrl: String = "http://127.0.0.1:9090"
) {
    private val secretProvider: () -> String = { com.kunk.singbox.utils.SecurityUtils.getClashApiSecret() }
    private val secret: String get() = secretProvider()
    companion object {
        private const val TAG = "ClashApiClient"
        // 使用 Cloudflare 的测试 URL，比 gstatic 更快
        private const val DEFAULT_TEST_URL = "https://cp.cloudflare.com/generate_204"
        private const val DEFAULT_TIMEOUT = 5000L // 5秒超时
    }
    
    private val gson = Gson()
    @Volatile
    private var baseUrl: String = baseUrl

    @Volatile
    private var trafficWebSocket: WebSocket? = null

    fun setBaseUrl(baseUrl: String) {
        this.baseUrl = baseUrl.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    private val client = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 100
            maxRequestsPerHost = 100
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS) // 比 DEFAULT_TIMEOUT 稍长，确保 API 层面先超时
        .build()
    
    /**
     * 获取所有代理节点
     */
    suspend fun getProxies(): ProxiesResponse? = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("proxies")
                ?.build()
                ?: run {
                    Log.e(TAG, "Invalid baseUrl: $baseUrl")
                    return@withContext null
                }
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
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("proxies")
                ?.addPathSegment(proxyName)
                ?.addPathSegment("delay")
                ?.addQueryParameter("timeout", timeout.toString())
                ?.addQueryParameter("url", testUrl)
                ?.build()
                ?: run {
                    Log.e(TAG, "Invalid baseUrl: $baseUrl")
                    return@withContext -1L
                }
            
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
                        Log.v(TAG, "Delay for $proxyName: ${result.delay}ms")
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
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("version")
                ?.build()
                ?: return@withContext false
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
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 选择代理节点
     * PUT /proxies/{selectorName}
     * Body: {"name": "proxyName"}
     */
    suspend fun selectProxy(selectorName: String, proxyName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("proxies")
                ?.addPathSegment(selectorName)
                ?.build()
                ?: return@withContext false

            val json = gson.toJson(mapOf("name" to proxyName))
            val body = okhttp3.RequestBody.create(
                "application/json; charset=utf-8".toMediaType(),
                json
            )

            Log.v(TAG, "Selecting proxy: selector=$selectorName, proxy=$proxyName, url=$url")

            val request = Request.Builder()
                .url(url)
                .apply {
                    if (secret.isNotEmpty()) {
                        addHeader("Authorization", "Bearer $secret")
                    }
                }
                .put(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to select proxy: ${response.code} ${response.message}")
                } else {
                    Log.i(TAG, "Successfully selected proxy: $proxyName")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting proxy", e)
            false
        }
    }

    /**
     * 获取当前 selector 选中的代理
     */
    suspend fun getCurrentSelection(selectorName: String): String? = withContext(Dispatchers.IO) {
        try {
            val proxies = getProxies() ?: return@withContext null
            val selector = proxies.proxies[selectorName]
            Log.v(TAG, "Current selection for $selectorName: ${selector?.now}")
            selector?.now
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current selection", e)
            null
        }
    }
    /**
     * 连接到流量 WebSocket
     */
    fun connectTrafficWebSocket(onTraffic: (up: Long, down: Long) -> Unit): WebSocket? {
        trafficWebSocket?.close(1000, "Replaced")
        trafficWebSocket = null

        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("traffic")
            ?.apply {
                if (secret.isNotEmpty()) {
                    addQueryParameter("token", secret)
                }
            }
            ?.build()
            ?: return null

        val request = Request.Builder()
            .url(url)
            .build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val traffic = gson.fromJson(text, TrafficResponse::class.java)
                    onTraffic(traffic.up, traffic.down)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing traffic message", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (trafficWebSocket === webSocket) {
                    trafficWebSocket = null
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (trafficWebSocket === webSocket) {
                    trafficWebSocket = null
                }

                if (t is SocketException && t.message == "Socket closed") {
                    Log.d(TAG, "Traffic WebSocket closed")
                    return
                }

                Log.e(TAG, "Traffic WebSocket failure", t)
            }
        })

        trafficWebSocket = socket
        return socket
    }
}

data class TrafficResponse(
    @SerializedName("up") val up: Long,
    @SerializedName("down") val down: Long
)

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
