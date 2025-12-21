package com.kunk.singbox.repository

import android.content.Intent
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.*
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * 配置仓库 - 负责获取、解析和存储配置
 */
class ConfigRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "ConfigRepository"
        
        // User-Agent 列表，按优先级排序
        private val USER_AGENTS = listOf(
            "sing-box/1.8.0",               // Sing-box - 返回原生 JSON
            "ClashMeta/1.16.0",             // ClashMeta - 返回 YAML
            "Clash/1.16.0",                 // Clash - 返回 YAML
            "SFA/1.8.0",                    // Sing-box for Android
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" // Browser
        )
        
        @Volatile
        private var instance: ConfigRepository? = null
        
        fun getInstance(context: Context): ConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val gson = Gson()
    private val singBoxCore = SingBoxCore.getInstance(context)
    private val settingsRepository = SettingsRepository.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val subscriptionManager = SubscriptionManager(listOf(
        SingBoxParser(gson),
        com.kunk.singbox.utils.parser.ClashYamlParser(),
        Base64Parser { NodeLinkParser(gson).parse(it) }
    ))

    private val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())
    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()
    
    private val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    private val _allNodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val allNodes: StateFlow<List<NodeUi>> = _allNodes.asStateFlow()
    
    private val _nodeGroups = MutableStateFlow<List<String>>(listOf("全部"))
    val nodeGroups: StateFlow<List<String>> = _nodeGroups.asStateFlow()

    private val _allNodeGroups = MutableStateFlow<List<String>>(emptyList())
    val allNodeGroups: StateFlow<List<String>> = _allNodeGroups.asStateFlow()
    
    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()
    
    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()
    
    private val maxConfigCacheSize = 2
    private val configCache = ConcurrentHashMap<String, SingBoxConfig>()
    private val configCacheOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val profileNodes = ConcurrentHashMap<String, List<NodeUi>>()
    private val profileResetJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val inFlightLatencyTests = ConcurrentHashMap<String, Deferred<Long>>()
    
    private val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }
    
    private val profilesFile: File
        get() = File(context.filesDir, "profiles.json")
    
    init {
        loadSavedProfiles()
    }
    
    private fun loadConfig(profileId: String): SingBoxConfig? {
        configCache[profileId]?.let { return it }

        val configFile = File(configDir, "$profileId.json")
        if (!configFile.exists()) return null

        return try {
            val configJson = configFile.readText()
            val config = gson.fromJson(configJson, SingBoxConfig::class.java)
            cacheConfig(profileId, config)
            config
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config for profile: $profileId", e)
            null
        }
    }

    private fun cacheConfig(profileId: String, config: SingBoxConfig) {
        configCache[profileId] = config
        configCacheOrder.remove(profileId)
        configCacheOrder.addLast(profileId)
        while (configCache.size > maxConfigCacheSize && configCacheOrder.isNotEmpty()) {
            val oldest = configCacheOrder.pollFirst()
            if (oldest != null && oldest != profileId) {
                configCache.remove(oldest)
            }
        }
    }

    private fun removeCachedConfig(profileId: String) {
        configCache.remove(profileId)
        configCacheOrder.remove(profileId)
    }

    private fun saveProfiles() {
        try {
            val data = SavedProfilesData(
                profiles = _profiles.value,
                activeProfileId = _activeProfileId.value
            )
            val json = gson.toJson(data)
            
            // Robust atomic write implementation
            val tmpFile = File(profilesFile.parent, "${profilesFile.name}.tmp")
            try {
                tmpFile.writeText(json)
                if (tmpFile.exists() && tmpFile.length() > 0) {
                    if (profilesFile.exists()) {
                        profilesFile.delete()
                    }
                    if (!tmpFile.renameTo(profilesFile)) {
                        Log.e(TAG, "Rename failed, falling back to copy")
                        tmpFile.copyTo(profilesFile, overwrite = true)
                        tmpFile.delete()
                    }
                    Log.d(TAG, "Profiles saved successfully")
                } else {
                    Log.e(TAG, "Tmp file is empty, skipping save to prevent data corruption")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Internal error during save write", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profiles", e)
        }
    }
    
    private fun updateAllNodesAndGroups() {
        val all = profileNodes.values.flatten()
        _allNodes.value = all
        
        val groups = all.map { it.group }.distinct().sorted()
        _allNodeGroups.value = groups
    }

    private fun updateLatencyInAllNodes(nodeId: String, latency: Long) {
        _allNodes.update { list ->
            list.map {
                if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else null) else it
            }
        }
    }

    private fun loadSavedProfiles() {
        try {
            if (profilesFile.exists()) {
                val json = profilesFile.readText()
                val savedData = gson.fromJson(json, SavedProfilesData::class.java)
                
                // Gson 有时会将泛型列表中的对象反序列化为 LinkedTreeMap，而不是目标对象 (ProfileUi)
                // 这通常发生在类型擦除或混淆导致类型信息丢失的情况下
                // 强制转换或重新映射以确保类型正确
                val safeProfiles = savedData.profiles.map { profile ->
                    // 强制转换为 Any? 以绕过编译器的类型检查，
                    // 因为在运行时 profile 可能是 LinkedTreeMap (类型擦除导致)
                    // 即使声明类型是 ProfileUi
                    val obj = profile as Any?
                    if (obj is com.google.gson.internal.LinkedTreeMap<*, *>) {
                        val jsonStr = gson.toJson(obj)
                        gson.fromJson(jsonStr, ProfileUi::class.java)
                    } else {
                        profile
                    }
                }

                // 加载时重置所有配置的更新状态为 Idle，防止因异常退出导致一直显示更新中
                _profiles.value = safeProfiles.map {
                    it.copy(updateStatus = UpdateStatus.Idle)
                }
                _activeProfileId.value = savedData.activeProfileId
                
                // 加载每个配置的节点
                savedData.profiles.forEach { profile ->
                    val configFile = File(configDir, "${profile.id}.json")
                    if (configFile.exists()) {
                        try {
                            val configJson = configFile.readText()
                            val config = gson.fromJson(configJson, SingBoxConfig::class.java)
                            val nodes = extractNodesFromConfig(config, profile.id)
                            profileNodes[profile.id] = nodes

                            if (profile.id == savedData.activeProfileId) {
                                cacheConfig(profile.id, config)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load config for profile: ${profile.id}", e)
                        }
                    }
                }
                
                updateAllNodesAndGroups()
                
                _activeProfileId.value?.let { activeId ->
                    profileNodes[activeId]?.let { nodes ->
                        _nodes.value = nodes
                        updateNodeGroups(nodes)
                        if (nodes.isNotEmpty()) {
                            _activeNodeId.value = nodes.first().id
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved profiles", e)
        }
    }
    
    /**
     * 从订阅 URL 导入配置
     */
    /**
     * 使用多种 User-Agent 尝试获取订阅内容
     * 如果解析失败，依次尝试其他 UA
     *
     * @param url 订阅链接
     * @param onProgress 进度回调
     * @return 解析成功的配置，如果所有尝试都失败则返回 null
     */
    private fun fetchAndParseSubscription(
        url: String,
        onProgress: (String) -> Unit = {}
    ): SingBoxConfig? {
        var lastError: Exception? = null
        
        for ((index, userAgent) in USER_AGENTS.withIndex()) {
            try {
                onProgress("尝试获取订阅 (${index + 1}/${USER_AGENTS.size})...")
                Log.v(TAG, "Trying subscription with User-Agent: $userAgent")
                
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/yaml,text/yaml,text/plain,application/json,*/*")
                    .build()

                var parsedConfig: SingBoxConfig? = null
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Request failed with UA '$userAgent': HTTP ${response.code}")
                        return@use
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) {
                        Log.w(TAG, "Empty response with UA '$userAgent'")
                        return@use
                    }

                    val contentType = response.header("Content-Type") ?: ""
                    Log.v(
                        TAG,
                        "Subscription response meta: ua='$userAgent' ct='$contentType' len=${responseBody.length} head='${sanitizeSubscriptionSnippet(responseBody)}'"
                    )

                    onProgress("正在解析配置...")

                    val config = parseSubscriptionResponse(responseBody)
                    if (config != null && config.outbounds != null && config.outbounds.isNotEmpty()) {
                        parsedConfig = config
                    } else {
                        Log.w(TAG, "Failed to parse response with UA '$userAgent'")
                    }
                }

                if (parsedConfig != null) {
                    Log.i(TAG, "Successfully parsed subscription with UA '$userAgent', got ${parsedConfig!!.outbounds?.size ?: 0} outbounds")
                    return parsedConfig
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error with UA '$userAgent': ${e.message}")
                lastError = e
            }
        }
        
        // 所有 UA 都失败了，记录最后的错误
        lastError?.let { Log.e(TAG, "All User-Agents failed", it) }
        return null
    }

    private fun sanitizeSubscriptionSnippet(body: String, maxLen: Int = 220): String {
        var s = body
            .replace("\r", "")
            .replace("\n", "\\n")
            .trim()
        if (s.length > maxLen) s = s.substring(0, maxLen)

        s = s.replace(Regex("(?i)uuid\\s*[:=]\\s*[^\\\\n]+"), "uuid:***")
        s = s.replace(Regex("(?i)password\\s*[:=]\\s*[^\\\\n]+"), "password:***")
        s = s.replace(Regex("(?i)token\\s*[:=]\\s*[^\\\\n]+"), "token:***")
        return s
    }

    private fun parseClashYamlConfig(content: String): SingBoxConfig? {
        val t = content.trim()
        if (t.isBlank()) return null
        if (t.startsWith("{") || t.startsWith("[")) return null

        val root = try {
            Yaml().load<Any>(t)
        } catch (e: YAMLException) {
            Log.v(TAG, "Clash YAML parse failed: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.v(TAG, "Clash YAML parse failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }

        val rootMap = (root as? Map<*, *>) ?: return null
        val proxiesRaw = rootMap["proxies"] as? List<*> ?: return null

        fun asString(v: Any?): String? = when (v) {
            is String -> v
            is Number -> v.toString()
            is Boolean -> v.toString()
            else -> null
        }

        fun asInt(v: Any?): Int? = when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }

        fun asBool(v: Any?): Boolean? = when (v) {
            is Boolean -> v
            is String -> when (v.lowercase()) {
                "true", "1", "yes", "y" -> true
                "false", "0", "no", "n" -> false
                else -> null
            }
            else -> null
        }

        fun asStringList(v: Any?): List<String>? {
            return when (v) {
                is List<*> -> v.mapNotNull { asString(it) }.takeIf { it.isNotEmpty() }
                is String -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                else -> null
            }
        }

        fun parseProxy(proxyMap: Map<*, *>): Outbound? {
            val name = asString(proxyMap["name"]) ?: return null
            val type = asString(proxyMap["type"])?.lowercase() ?: return null

            val server = asString(proxyMap["server"])
            val port = asInt(proxyMap["port"])

            return when (type) {
                "vless" -> {
                    val uuid = asString(proxyMap["uuid"]) ?: return null
                    val network = asString(proxyMap["network"])?.lowercase()
                    val tlsEnabled = asBool(proxyMap["tls"]) == true
                    val serverName = asString(proxyMap["servername"]) ?: asString(proxyMap["sni"]) ?: server
                    val fingerprint = asString(proxyMap["client-fingerprint"])
                    val insecure = asBool(proxyMap["skip-cert-verify"]) == true
                    val alpn = asStringList(proxyMap["alpn"])
                    val finalAlpn = if (tlsEnabled && network == "ws" && (alpn == null || alpn.isEmpty())) listOf("http/1.1") else alpn

                    val tlsConfig = if (tlsEnabled) {
                        TlsConfig(
                            enabled = true,
                            serverName = serverName,
                            insecure = insecure,
                            alpn = finalAlpn,
                            utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                        )
                    } else {
                        null
                    }

                    val transport = when (network) {
                        "ws" -> {
                            val wsOpts = proxyMap["ws-opts"] as? Map<*, *>
                            val path = asString(wsOpts?.get("path")) ?: "/"
                            val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                            val headers = mutableMapOf<String, String>()
                            headersRaw?.forEach { (k, v) ->
                                val ks = asString(k) ?: return@forEach
                                val vs = asString(v) ?: return@forEach
                                headers[ks] = vs
                            }
                            val host = headers["Host"] ?: headers["host"] ?: serverName
                            val userAgent = if (fingerprint?.contains("chrome", ignoreCase = true) == true) {
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                            } else {
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                            }
                            val finalHeaders = mutableMapOf<String, String>()
                            if (!host.isNullOrBlank()) finalHeaders["Host"] = host
                            finalHeaders["User-Agent"] = userAgent

                            TransportConfig(
                                type = "ws",
                                path = path,
                                headers = finalHeaders,
                                maxEarlyData = 2048,
                                earlyDataHeaderName = "Sec-WebSocket-Protocol"
                            )
                        }
                        "grpc" -> {
                            val grpcOpts = proxyMap["grpc-opts"] as? Map<*, *>
                            val serviceName = asString(grpcOpts?.get("grpc-service-name"))
                                ?: asString(grpcOpts?.get("service-name"))
                                ?: asString(proxyMap["grpc-service-name"])
                                ?: ""
                            TransportConfig(type = "grpc", serviceName = serviceName)
                        }
                        "h2", "http" -> {
                            val path = asString(proxyMap["path"])
                            val host = asString(proxyMap["host"])?.let { listOf(it) }
                            TransportConfig(type = "http", path = path, host = host)
                        }
                        else -> null
                    }

                    Outbound(
                        type = "vless",
                        tag = name,
                        server = server,
                        serverPort = port,
                        uuid = uuid,
                        tls = tlsConfig,
                        transport = transport
                    )
                }
                "hysteria2", "hy2" -> {
                    val password = asString(proxyMap["password"]) ?: return null
                    val sni = asString(proxyMap["sni"]) ?: server
                    val insecure = asBool(proxyMap["skip-cert-verify"]) == true
                    val alpn = asStringList(proxyMap["alpn"])
                    Outbound(
                        type = "hysteria2",
                        tag = name,
                        server = server,
                        serverPort = port,
                        password = password,
                        tls = TlsConfig(
                            enabled = true,
                            serverName = sni,
                            insecure = insecure,
                            alpn = alpn
                        )
                    )
                }
                "ss", "shadowsocks" -> {
                    val method = asString(proxyMap["cipher"]) ?: asString(proxyMap["method"]) ?: return null
                    val password = asString(proxyMap["password"]) ?: return null
                    Outbound(
                        type = "shadowsocks",
                        tag = name,
                        server = server,
                        serverPort = port,
                        method = method,
                        password = password
                    )
                }
                "trojan" -> {
                    val password = asString(proxyMap["password"]) ?: return null
                    val sni = asString(proxyMap["sni"]) ?: server
                    val insecure = asBool(proxyMap["skip-cert-verify"]) == true
                    val alpn = asStringList(proxyMap["alpn"])
                    
                    val network = asString(proxyMap["network"])
                    val transport = if (network == "ws") {
                        val wsOpts = proxyMap["ws-opts"] as? Map<*, *>
                        val path = asString(wsOpts?.get("path")) ?: "/"
                        val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                        val headers = mutableMapOf<String, String>()
                        headersRaw?.forEach { (k, v) ->
                            val ks = asString(k) ?: return@forEach
                            val vs = asString(v) ?: return@forEach
                            headers[ks] = vs
                        }
                        if (!headers.containsKey("Host") && !sni.isNullOrBlank()) {
                            headers["Host"] = sni
                        }
                        TransportConfig(
                            type = "ws",
                            path = path,
                            headers = headers
                        )
                    } else if (network == "grpc") {
                         val grpcOpts = proxyMap["grpc-opts"] as? Map<*, *>
                         val serviceName = asString(grpcOpts?.get("grpc-service-name"))
                             ?: asString(grpcOpts?.get("service-name")) ?: ""
                         TransportConfig(type = "grpc", serviceName = serviceName)
                    } else null

                    Outbound(
                        type = "trojan",
                        tag = name,
                        server = server,
                        serverPort = port,
                        password = password,
                        tls = TlsConfig(
                            enabled = true,
                            serverName = sni,
                            insecure = insecure,
                            alpn = alpn
                        ),
                        transport = transport
                    )
                }
                "vmess" -> {
                    val uuid = asString(proxyMap["uuid"]) ?: return null
                    val alterId = asInt(proxyMap["alterId"]) ?: 0
                    val cipher = asString(proxyMap["cipher"]) ?: "auto"
                    val network = asString(proxyMap["network"])
                    val tlsEnabled = asBool(proxyMap["tls"]) == true
                    val sni = asString(proxyMap["servername"]) ?: asString(proxyMap["sni"]) ?: server
                    val insecure = asBool(proxyMap["skip-cert-verify"]) == true
                    val alpn = asStringList(proxyMap["alpn"])
                    val finalAlpn = if (tlsEnabled && network == "ws" && (alpn == null || alpn.isEmpty())) listOf("http/1.1") else alpn
                    
                    val tlsConfig = if (tlsEnabled) {
                        TlsConfig(
                            enabled = true,
                            serverName = sni,
                            insecure = insecure,
                            alpn = finalAlpn
                        )
                    } else null

                    val transport = when (network) {
                        "ws" -> {
                            val wsOpts = proxyMap["ws-opts"] as? Map<*, *>
                            val path = asString(wsOpts?.get("path")) ?: "/"
                            val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                            val headers = mutableMapOf<String, String>()
                            headersRaw?.forEach { (k, v) ->
                                val ks = asString(k) ?: return@forEach
                                val vs = asString(v) ?: return@forEach
                                headers[ks] = vs
                            }
                            if (!headers.containsKey("Host") && !sni.isNullOrBlank()) {
                                headers["Host"] = sni
                            }
                            TransportConfig(
                                type = "ws",
                                path = path,
                                headers = headers
                            )
                        }
                        "grpc" -> {
                            val grpcOpts = proxyMap["grpc-opts"] as? Map<*, *>
                            val serviceName = asString(grpcOpts?.get("grpc-service-name"))
                                ?: asString(grpcOpts?.get("service-name")) ?: ""
                            TransportConfig(type = "grpc", serviceName = serviceName)
                        }
                        "h2", "http" -> {
                             val h2Opts = proxyMap["h2-opts"] as? Map<*, *>
                             val path = asString(h2Opts?.get("path"))
                             val host = asStringList(h2Opts?.get("host"))
                             TransportConfig(type = "http", path = path, host = host)
                        }
                        else -> null
                    }

                    Outbound(
                        type = "vmess",
                        tag = name,
                        server = server,
                        serverPort = port,
                        uuid = uuid,
                        alterId = alterId,
                        security = cipher,
                        tls = tlsConfig,
                        transport = transport
                    )
                }
                else -> null
            }
        }

        val outbounds = mutableListOf<Outbound>()
        for (p in proxiesRaw) {
            val m = p as? Map<*, *> ?: continue
            val ob = parseProxy(m)
            if (ob != null) outbounds.add(ob)
        }

        val proxyGroupsRaw = rootMap["proxy-groups"] as? List<*>
        if (proxyGroupsRaw != null) {
            for (g in proxyGroupsRaw) {
                val gm = g as? Map<*, *> ?: continue
                val name = asString(gm["name"]) ?: continue
                val type = asString(gm["type"])?.lowercase() ?: continue
                val proxies = (gm["proxies"] as? List<*>)?.mapNotNull { asString(it) }?.filter { it.isNotBlank() } ?: emptyList()
                if (proxies.isEmpty()) continue

                when (type) {
                    "select", "selector" -> {
                        outbounds.add(
                            Outbound(
                                type = "selector",
                                tag = name,
                                outbounds = proxies,
                                default = proxies.firstOrNull(),
                                interruptExistConnections = false
                            )
                        )
                    }
                    "url-test", "urltest" -> {
                        val url = asString(gm["url"]) ?: "https://www.gstatic.com/generate_204"
                        val interval = asString(gm["interval"]) ?: asInt(gm["interval"])?.toString() ?: "300s"
                        val tolerance = asInt(gm["tolerance"]) ?: 50
                        outbounds.add(
                            Outbound(
                                type = "urltest",
                                tag = name,
                                outbounds = proxies,
                                url = url,
                                interval = interval,
                                tolerance = tolerance,
                                interruptExistConnections = false
                            )
                        )
                    }
                }
            }
        }

        if (outbounds.none { it.tag == "direct" }) {
            outbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        if (outbounds.none { it.tag == "block" }) {
            outbounds.add(Outbound(type = "block", tag = "block"))
        }
        if (outbounds.none { it.tag == "dns-out" }) {
            outbounds.add(Outbound(type = "dns", tag = "dns-out"))
        }

        return SingBoxConfig(outbounds = outbounds)
    }
    
    /**
     * 从订阅 URL 导入配置
     */
    suspend fun importFromSubscription(
        name: String,
        url: String,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        try {
            onProgress("正在获取订阅...")
            
            // 使用智能 User-Agent 切换策略获取订阅
            val config = fetchAndParseSubscription(url, onProgress)
                ?: return@withContext Result.failure(Exception("无法解析配置格式，已尝试所有 User-Agent"))
            
            onProgress("正在提取节点...")
            
            val profileId = UUID.randomUUID().toString()
            val nodes = extractNodesFromConfig(config, profileId)
            
            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception("未找到有效节点"))
            }
            
            // 保存配置文件
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(config))
            
            // 创建配置
            val profile = ProfileUi(
                id = profileId,
                name = name,
                type = ProfileType.Subscription,
                url = url,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                updateStatus = UpdateStatus.Idle
            )
            
            // 保存到内存
            cacheConfig(profileId, config)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()
            
            // 更新状态
            _profiles.update { it + profile }
            saveProfiles()
            
            // 如果是第一个配置，自动激活
            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }
            
            onProgress("导入成功，共 ${nodes.size} 个节点")
            
            Result.success(profile)
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Subscription fetch timeout", e)
            Result.failure(Exception("订阅获取超时，请检查网络连接"))
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "DNS resolution failed", e)
            Result.failure(Exception("域名解析失败，请检查网络或订阅地址"))
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            Log.e(TAG, "SSL handshake failed", e)
            Result.failure(Exception("SSL证书验证失败，请检查订阅地址"))
        } catch (e: Exception) {
            Log.e(TAG, "Subscription import failed", e)
            Result.failure(Exception("导入失败: ${e.message ?: "未知错误"}"))
        }
    }

    suspend fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        try {
            onProgress("正在解析配置...")

            val normalized = normalizeImportedContent(content)
            val config = subscriptionManager.parse(normalized)
                ?: return@withContext Result.failure(Exception("无法解析配置格式"))

            onProgress("正在提取节点...")

            val profileId = UUID.randomUUID().toString()
            val nodes = extractNodesFromConfig(config, profileId)

            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception("未找到有效节点"))
            }

            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(config))

            val profile = ProfileUi(
                id = profileId,
                name = name,
                type = profileType,
                url = null,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                updateStatus = UpdateStatus.Idle
            )

            cacheConfig(profileId, config)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()

            _profiles.update { it + profile }
            saveProfiles()

            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }

            onProgress("导入成功，共 ${nodes.size} 个节点")

            Result.success(profile)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun normalizeImportedContent(content: String): String {
        val trimmed = content.trim().trimStart('\uFEFF')
        val lines = trimmed.lines().toMutableList()

        fun isFenceLine(line: String): Boolean {
            val t = line.trim()
            if (t.startsWith("```")) return true
            return t.length >= 2 && t.all { it == '`' }
        }

        if (lines.isNotEmpty() && isFenceLine(lines.first())) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && isFenceLine(lines.last())) {
            lines.removeAt(lines.lastIndex)
        }

        return lines.joinToString("\n").trim()
    }

    private fun tryDecodeBase64(content: String): String? {
        val s = content.trim().trimStart('\uFEFF')
        if (s.isBlank()) return null
        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = Base64.decode(s, flags)
                val text = String(decoded)
                if (text.isNotBlank()) return text
            } catch (_: Exception) {}
        }
        return null
    }
    
    /**
     * 解析订阅响应
     */
    private fun parseSubscriptionResponse(content: String): SingBoxConfig? {
        val normalizedContent = normalizeImportedContent(content)

        // 1. 尝试直接解析为 sing-box JSON
        try {
            val config = gson.fromJson(normalizedContent, SingBoxConfig::class.java)
            if (config.outbounds != null && config.outbounds.isNotEmpty()) {
                return config
            }
        } catch (e: JsonSyntaxException) {
            // 继续尝试其他格式
        }

        // 1.5 尝试解析 Clash YAML
        try {
            val yamlConfig = parseClashYamlConfig(normalizedContent)
            if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                return yamlConfig
            }
        } catch (_: Exception) {
        }
        
        // 2. 尝试 Base64 解码后解析
        try {
            val decoded = tryDecodeBase64(normalizedContent)
            if (decoded.isNullOrBlank()) {
                throw IllegalStateException("base64 decode failed")
            }
            
            // 尝试解析解码后的内容为 JSON
            try {
                val config = gson.fromJson(decoded, SingBoxConfig::class.java)
                if (config.outbounds != null && config.outbounds.isNotEmpty()) {
                    return config
                }
            } catch (e: Exception) {}

            try {
                val yamlConfig = parseClashYamlConfig(decoded)
                if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                    return yamlConfig
                }
            } catch (_: Exception) {
            }

        } catch (e: Exception) {
            // 继续尝试其他格式
        }
        
        // 3. 尝试解析为节点链接列表 (每行一个链接)
        try {
            val lines = normalizedContent.trim().lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                // 尝试 Base64 解码整体
                val decoded = tryDecodeBase64(normalizedContent) ?: normalizedContent
                
                val decodedLines = decoded.trim().lines().filter { it.isNotBlank() }
                val outbounds = mutableListOf<Outbound>()
                
                for (line in decodedLines) {
                    val cleanedLine = line.trim()
                        .removePrefix("- ")
                        .removePrefix("• ")
                        .trim()
                        .trim('`', '"', '\'')
                    val outbound = parseNodeLink(cleanedLine)
                    if (outbound != null) {
                        outbounds.add(outbound)
                    }
                }
                
                if (outbounds.isNotEmpty()) {
                    // 创建一个包含这些节点的配置
                    return SingBoxConfig(
                        outbounds = outbounds + listOf(
                            Outbound(type = "direct", tag = "direct"),
                            Outbound(type = "block", tag = "block")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return null
    }
    
    /**
     * 解析单个节点链接
     */
    private fun parseNodeLink(link: String): Outbound? {
        return when {
            link.startsWith("ss://") -> parseShadowsocksLink(link)
            link.startsWith("vmess://") -> parseVMessLink(link)
            link.startsWith("vless://") -> parseVLessLink(link)
            link.startsWith("trojan://") -> parseTrojanLink(link)
            link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2Link(link)
            link.startsWith("hysteria://") -> parseHysteriaLink(link)
            link.startsWith("anytls://") -> parseAnyTLSLink(link)
            link.startsWith("tuic://") -> parseTuicLink(link)
            link.startsWith("wireguard://") -> parseWireGuardLink(link)
            link.startsWith("ssh://") -> parseSSHLink(link)
            else -> null
        }
    }
    
    private fun parseShadowsocksLink(link: String): Outbound? {
        try {
            // ss://BASE64(method:password)@server:port#name
            // 或 ss://BASE64(method:password@server:port)#name
            val uri = link.removePrefix("ss://")
            val nameIndex = uri.lastIndexOf('#')
            val name = if (nameIndex > 0) java.net.URLDecoder.decode(uri.substring(nameIndex + 1), "UTF-8") else "SS Node"
            val mainPart = if (nameIndex > 0) uri.substring(0, nameIndex) else uri
            
            val atIndex = mainPart.lastIndexOf('@')
            if (atIndex > 0) {
                // 新格式: BASE64(method:password)@server:port
                val userInfo = String(Base64.decode(mainPart.substring(0, atIndex), Base64.URL_SAFE or Base64.NO_PADDING))
                val serverPart = mainPart.substring(atIndex + 1)
                val colonIndex = serverPart.lastIndexOf(':')
                val server = serverPart.substring(0, colonIndex)
                val port = serverPart.substring(colonIndex + 1).toInt()
                val methodPassword = userInfo.split(":", limit = 2)
                
                return Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = methodPassword[0],
                    password = methodPassword.getOrElse(1) { "" }
                )
            } else {
                // 旧格式: BASE64(method:password@server:port)
                val decoded = String(Base64.decode(mainPart, Base64.URL_SAFE or Base64.NO_PADDING))
                val regex = Regex("(.+):(.+)@(.+):(\\d+)")
                val match = regex.find(decoded)
                if (match != null) {
                    val (method, password, server, port) = match.destructured
                    return Outbound(
                        type = "shadowsocks",
                        tag = name,
                        server = server,
                        serverPort = port.toInt(),
                        method = method,
                        password = password
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    /**
     * 解析 WireGuard 链接
     * 格式: wireguard://private_key@server:port?public_key=...&preshared_key=...&address=...&mtu=...#name
     */
    private fun parseWireGuardLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "WireGuard Node", "UTF-8")
            val privateKey = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 51820
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val peerPublicKey = params["public_key"] ?: params["peer_public_key"] ?: ""
            val preSharedKey = params["preshared_key"] ?: params["pre_shared_key"]
            val address = params["address"] ?: params["ip"]
            val mtu = params["mtu"]?.toIntOrNull() ?: 1420
            val reserved = params["reserved"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            
            val localAddresses = address?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            
            val peer = WireGuardPeer(
                server = server,
                serverPort = port,
                publicKey = peerPublicKey,
                preSharedKey = preSharedKey,
                allowedIps = listOf("0.0.0.0/0", "::/0"), // 默认全路由
                reserved = reserved
            )
            
            return Outbound(
                type = "wireguard",
                tag = name,
                localAddress = localAddresses,
                privateKey = privateKey,
                peers = listOf(peer),
                mtu = mtu
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * 解析 SSH 链接
     * 格式: ssh://user:password@server:port?params#name
     */
    private fun parseSSHLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "SSH Node", "UTF-8")
            val userInfo = uri.userInfo ?: ""
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 22
            
            val colonIndex = userInfo.indexOf(':')
            val user = if (colonIndex > 0) userInfo.substring(0, colonIndex) else userInfo
            val password = if (colonIndex > 0) userInfo.substring(colonIndex + 1) else null
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val privateKey = params["private_key"]
            val privateKeyPassphrase = params["private_key_passphrase"]
            val hostKey = params["host_key"]?.split(",")
            val clientVersion = params["client_version"]
            
            return Outbound(
                type = "ssh",
                tag = name,
                server = server,
                serverPort = port,
                user = user,
                password = password,
                privateKey = privateKey,
                privateKeyPassphrase = privateKeyPassphrase,
                hostKey = hostKey,
                clientVersion = clientVersion
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseVMessLink(link: String): Outbound? {
        try {
            val base64Part = link.removePrefix("vmess://")
            val decoded = String(Base64.decode(base64Part, Base64.DEFAULT))
            val json = gson.fromJson(decoded, VMessLinkConfig::class.java)
            
            // 如果是 WS 且开启了 TLS，但没有指定 ALPN，默认强制使用 http/1.1
            val alpn = json.alpn?.split(",")?.filter { it.isNotBlank() }
            val finalAlpn = if (json.tls == "tls" && json.net == "ws" && (alpn == null || alpn.isEmpty())) {
                listOf("http/1.1")
            } else {
                alpn
            }

            val tlsConfig = if (json.tls == "tls") {
                TlsConfig(
                    enabled = true,
                    serverName = json.sni ?: json.host ?: json.add,
                    alpn = finalAlpn,
                    utls = json.fp?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
            } else null
            
            val transport = when (json.net) {
                "ws" -> {
                    val host = json.host ?: json.sni ?: json.add
                    val userAgent = if (json.fp?.contains("chrome") == true) {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                    } else {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                    }
                    val headers = mutableMapOf<String, String>()
                    if (!host.isNullOrBlank()) {
                        headers["Host"] = host
                    }
                    headers["User-Agent"] = userAgent

                    TransportConfig(
                        type = "ws",
                        path = json.path ?: "/",
                        headers = headers,
                        maxEarlyData = 2048,
                        earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    )
                }
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = json.path ?: ""
                )
                "h2" -> TransportConfig(
                    type = "http",
                    host = json.host?.let { listOf(it) },
                    path = json.path
                )
                "tcp" -> null
                else -> null
            }
            
            return Outbound(
                type = "vmess",
                tag = json.ps ?: "VMess Node",
                server = json.add,
                serverPort = json.port?.toIntOrNull() ?: 443,
                uuid = json.id,
                alterId = json.aid?.toIntOrNull() ?: 0,
                security = json.scy ?: "auto",
                tls = tlsConfig,
                transport = transport,
                packetEncoding = json.packetEncoding ?: "xudp"
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun parseVLessLink(link: String): Outbound? {
        try {
            // vless://uuid@server:port?params#name
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "VLESS Node", "UTF-8")
            val uuid = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val security = params["security"] ?: "none"
            val sni = params["sni"] ?: params["host"] ?: server
            val insecure = params["allowInsecure"] == "1" || params["insecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]
            val packetEncoding = params["packetEncoding"] ?: "xudp"
            val transportType = params["type"] ?: "tcp"
            val flow = params["flow"]?.takeIf { it.isNotBlank() }

            val finalAlpnList = if (security == "tls" && transportType == "ws" && (alpnList == null || alpnList.isEmpty())) {
                listOf("http/1.1")
            } else {
                alpnList
            }
            
            val tlsConfig = when (security) {
                "tls" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = finalAlpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
                "reality" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = finalAlpnList,
                    reality = RealityConfig(
                        enabled = true,
                        publicKey = params["pbk"],
                        shortId = params["sid"]
                    ),
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
                else -> null
            }
            
            val transport = when (transportType) {
                "ws" -> {
                    val host = params["host"] ?: sni
                    val rawWsPath = params["path"] ?: "/"
                    
                    // 从路径中提取 ed 参数
                    val earlyDataSize = params["ed"]?.toIntOrNull()
                        ?: Regex("""(?:\?|&)ed=(\d+)""")
                            .find(rawWsPath)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    val maxEarlyData = earlyDataSize ?: 2048
                    
                    // 从路径中移除 ed 参数，只保留纯路径
                    val cleanPath = rawWsPath
                        .replace(Regex("""\?ed=\d+(&|$)"""), "")
                        .replace(Regex("""&ed=\d+"""), "")
                        .trimEnd('?', '&')
                        .ifEmpty { "/" }
                    
                    val userAgent = if (fingerprint?.contains("chrome") == true) {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                    } else {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                    }
                    val headers = mutableMapOf<String, String>()
                    if (!host.isNullOrBlank()) {
                        headers["Host"] = host
                    }
                    headers["User-Agent"] = userAgent

                    TransportConfig(
                        type = "ws",
                        path = cleanPath,
                        headers = headers,
                        maxEarlyData = maxEarlyData,
                        earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    )
                }
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = params["serviceName"] ?: params["sn"] ?: ""
                )
                "http", "h2", "httpupgrade" -> TransportConfig(
                    type = "http",
                    path = params["path"],
                    host = params["host"]?.let { listOf(it) }
                )
                "tcp" -> null
                else -> null
            }
            
            return Outbound(
                type = "vless",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                flow = flow,
                tls = tlsConfig,
                transport = transport,
                packetEncoding = packetEncoding
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun parseTrojanLink(link: String): Outbound? {
        try {
            // trojan://password@server:port?params#name
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Trojan Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val sni = params["sni"] ?: params["host"] ?: server
            val insecure = params["allowInsecure"] == "1" || params["insecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]
            
            val transportType = params["type"] ?: "tcp"
            val transport = when (transportType) {
                "ws" -> {
                    val host = params["host"] ?: sni
                    val userAgent = if (fingerprint?.contains("chrome") == true) {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                    } else {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                    }
                    val headers = mutableMapOf<String, String>()
                    if (!host.isNullOrBlank()) {
                        headers["Host"] = host
                    }
                    headers["User-Agent"] = userAgent

                    TransportConfig(
                        type = "ws",
                        path = params["path"] ?: "/",
                        headers = headers,
                        maxEarlyData = 2048,
                        earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    )
                }
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = params["serviceName"] ?: params["sn"] ?: ""
                )
                "tcp" -> null
                else -> null
            }
            
            return Outbound(
                type = "trojan",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                ),
                transport = transport
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse trojan link", e)
        }
        return null
    }
    
    private fun parseHysteria2Link(link: String): Outbound? {
        try {
            // hysteria2://password@server:port?params#name
            val uri = java.net.URI(link.replace("hy2://", "hysteria2://"))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria2 Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port == -1) 443 else uri.port
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            return Outbound(
                type = "hysteria2",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: server,
                    insecure = params["insecure"] == "1"
                ),
                obfs = params["obfs"]?.let { obfsType ->
                    ObfsConfig(
                        type = obfsType,
                        password = params["obfs-password"]
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse hysteria2 link", e)
        }
        return null
    }
    
    private fun parseHysteriaLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port == -1) 443 else uri.port
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            return Outbound(
                type = "hysteria",
                tag = name,
                server = server,
                serverPort = port,
                authStr = params["auth"],
                upMbps = params["upmbps"]?.toIntOrNull(),
                downMbps = params["downmbps"]?.toIntOrNull(),
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: server,
                    insecure = params["insecure"] == "1",
                    alpn = params["alpn"]?.split(",")
                ),
                obfs = params["obfs"]?.let { ObfsConfig(type = it) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse hysteria link", e)
        }
        return null
    }
    
    /**
     * 解析 AnyTLS 链接
     * 格式: anytls://password@server:port?params#name
     */
    private fun parseAnyTLSLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "AnyTLS Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val sni = params["sni"] ?: server
            val insecure = params["insecure"] == "1" || params["allowInsecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]
            
            return Outbound(
                type = "anytls",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                ),
                idleSessionCheckInterval = params["idle_session_check_interval"],
                idleSessionTimeout = params["idle_session_timeout"],
                minIdleSession = params["min_idle_session"]?.toIntOrNull()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    /**
     * 解析 TUIC 链接
     * 格式: tuic://uuid:password@server:port?params#name
     */
    private fun parseTuicLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "TUIC Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            // 解析 userInfo: uuid:password
            val userInfo = uri.userInfo ?: ""
            val colonIndex = userInfo.indexOf(':')
            val uuid = if (colonIndex > 0) userInfo.substring(0, colonIndex) else userInfo
            val password = if (colonIndex > 0) userInfo.substring(colonIndex + 1) else ""
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val sni = params["sni"] ?: server
            val insecure = params["allow_insecure"] == "1" || params["allowInsecure"] == "1" || params["insecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]
            
            return Outbound(
                type = "tuic",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                password = password,
                congestionControl = params["congestion_control"] ?: params["congestion"] ?: "bbr",
                udpRelayMode = params["udp_relay_mode"] ?: "native",
                zeroRttHandshake = params["reduce_rtt"] == "1" || params["zero_rtt"] == "1",
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    /**
     * 从配置中提取节点
     */
    private fun extractNodesFromConfig(config: SingBoxConfig, profileId: String): List<NodeUi> {
        val nodes = mutableListOf<NodeUi>()
        val outbounds = config.outbounds ?: return nodes

        fun stableNodeId(profileId: String, outboundTag: String): String {
            val key = "$profileId|$outboundTag"
            return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        }
        
        // 收集所有 selector 和 urltest 的 outbounds 作为分组
        val groupOutbounds = outbounds.filter { 
            it.type == "selector" || it.type == "urltest" 
        }
        
        // 创建节点到分组的映射
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }
        
        // 过滤出代理节点
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls"
        )
        
        for (outbound in outbounds) {
            if (outbound.type in proxyTypes) {
                val group = nodeToGroup[outbound.tag] ?: "未分组"
                val regionFlag = detectRegionFlag(outbound.tag)
                
                // 如果名称中已经包含该国旗，或者名称中已经包含任意国旗emoji，则不再添加
                // 1. 如果 tag 包含了检测到的 regionFlag，则 finalRegionFlag = null
                // 2. 如果 tag 包含了其他国旗 Emoji，是否还要显示 regionFlag？
                //    这里我们采取保守策略：只要 tag 中包含任何国旗 Emoji，就不再添加自动检测的国旗。
                //    这可以避免 "🇩🇪 德国" 被显示为 "🇩🇪 🇩🇪 德国"，或者 "🇺🇸 美国" 被显示为 "🇺🇸 🇺🇸 美国"。
                //    同时也能处理 "🇸🇬 新加坡" 这种已经自带国旗的情况。
                
                val hasFlagEmoji = containsFlagEmoji(outbound.tag)
                val finalRegionFlag = if (outbound.tag.contains(regionFlag) || hasFlagEmoji) null else regionFlag

                // 2025 规范：确保 tag 已经应用了协议后缀（在 SubscriptionManager 中处理过了）
                // 这里我们只需确保 NodeUi 能够正确显示国旗
                
                nodes.add(
                    NodeUi(
                        id = stableNodeId(profileId, outbound.tag),
                        name = outbound.tag,
                        protocol = outbound.type,
                        group = group,
                        regionFlag = finalRegionFlag,
                        latencyMs = null,
                        isFavorite = false,
                        sourceProfileId = profileId,
                        tags = buildList {
                            outbound.tls?.let { 
                                if (it.enabled == true) add("TLS")
                                it.reality?.let { r -> if (r.enabled == true) add("Reality") }
                            }
                            outbound.transport?.type?.let { add(it.uppercase()) }
                        }
                    )
                )
            }
        }
        
        return nodes
    }
    
    /**
     * 检测字符串是否包含国旗 Emoji
     */
    private fun containsFlagEmoji(str: String): Boolean {
        // 匹配区域指示符符号 (Regional Indicator Symbols) U+1F1E6..U+1F1FF
        // 两个区域指示符符号组成一个国旗
        // Java/Kotlin 中，这些字符是代理对 (Surrogate Pairs)
        // U+1F1E6 是 \uD83C\uDDE6
        // U+1F1FF 是 \uD83C\uDDFF
        // 正则表达式匹配两个连续的区域指示符
        val regex = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")
        
        // 另外，有些国旗 Emoji 可能不在这个范围内，或者已经被渲染为 Emoji
        // 我们也可以尝试匹配常见的国旗 Emoji 字符范围
        // 或者简单地，如果字符串包含任何 Emoji，我们可能都需要谨慎
        // 但目前先专注于国旗
        
        return regex.containsMatchIn(str)
    }

    /**
     * 根据节点名称检测地区标志

     * 使用词边界匹配，避免 "us" 匹配 "music" 等误报
     */
    private fun detectRegionFlag(name: String): String {
        val lowerName = name.lowercase()
        
        fun matchWord(vararg words: String): Boolean {
            return words.any { word ->
                val regex = Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)")
                regex.containsMatchIn(lowerName)
            }
        }
        
        return when {
            lowerName.contains("香港") || matchWord("hk") || lowerName.contains("hong kong") -> "🇭🇰"
            lowerName.contains("台湾") || matchWord("tw") || lowerName.contains("taiwan") -> "🇹🇼"
            lowerName.contains("日本") || matchWord("jp") || lowerName.contains("japan") || lowerName.contains("tokyo") -> "🇯🇵"
            lowerName.contains("新加坡") || matchWord("sg") || lowerName.contains("singapore") -> "🇸🇬"
            lowerName.contains("美国") || matchWord("us", "usa") || lowerName.contains("united states") || lowerName.contains("america") -> "🇺🇸"
            lowerName.contains("韩国") || matchWord("kr") || lowerName.contains("korea") -> "🇰🇷"
            lowerName.contains("英国") || matchWord("uk", "gb") || lowerName.contains("britain") || lowerName.contains("england") -> "🇬🇧"
            lowerName.contains("德国") || matchWord("de") || lowerName.contains("germany") -> "🇩🇪"
            lowerName.contains("法国") || matchWord("fr") || lowerName.contains("france") -> "🇫🇷"
            lowerName.contains("加拿大") || matchWord("ca") || lowerName.contains("canada") -> "🇨🇦"
            lowerName.contains("澳大利亚") || matchWord("au") || lowerName.contains("australia") -> "🇦🇺"
            lowerName.contains("俄罗斯") || matchWord("ru") || lowerName.contains("russia") -> "🇷🇺"
            lowerName.contains("印度") || matchWord("in") || lowerName.contains("india") -> "🇮🇳"
            lowerName.contains("巴西") || matchWord("br") || lowerName.contains("brazil") -> "🇧🇷"
            lowerName.contains("荷兰") || matchWord("nl") || lowerName.contains("netherlands") -> "🇳🇱"
            lowerName.contains("土耳其") || matchWord("tr") || lowerName.contains("turkey") -> "🇹🇷"
            lowerName.contains("阿根廷") || matchWord("ar") || lowerName.contains("argentina") -> "🇦🇷"
            lowerName.contains("马来西亚") || matchWord("my") || lowerName.contains("malaysia") -> "🇲🇾"
            lowerName.contains("泰国") || matchWord("th") || lowerName.contains("thailand") -> "🇹🇭"
            lowerName.contains("越南") || matchWord("vn") || lowerName.contains("vietnam") -> "🇻🇳"
            lowerName.contains("菲律宾") || matchWord("ph") || lowerName.contains("philippines") -> "🇵🇭"
            lowerName.contains("印尼") || matchWord("id") || lowerName.contains("indonesia") -> "🇮🇩"
            else -> "🌐"
        }
    }
    
    private fun updateNodeGroups(nodes: List<NodeUi>) {
        val groups = nodes.map { it.group }.distinct().sorted()
        _nodeGroups.value = listOf("全部") + groups
    }
    
    fun setActiveProfile(profileId: String) {
        _activeProfileId.value = profileId
        profileNodes[profileId]?.let { nodes ->
            _nodes.value = nodes
            updateNodeGroups(nodes)
            if (nodes.isNotEmpty() && _activeNodeId.value !in nodes.map { it.id }) {
                _activeNodeId.value = nodes.first().id
            }
        }
        saveProfiles()
    }
    
    sealed class NodeSwitchResult {
        data object Success : NodeSwitchResult()
        data object NotRunning : NodeSwitchResult()
        data class Failed(val reason: String) : NodeSwitchResult()
    }

    suspend fun setActiveNode(nodeId: String): Boolean {
        val result = setActiveNodeWithResult(nodeId)
        return result is NodeSwitchResult.Success || result is NodeSwitchResult.NotRunning
    }

    suspend fun setActiveNodeWithResult(nodeId: String): NodeSwitchResult {
        _activeNodeId.value = nodeId
        
        if (!com.kunk.singbox.service.SingBoxService.isRunning) {
            return NodeSwitchResult.NotRunning
        }
        
        return withContext(Dispatchers.IO) {
            val node = _nodes.value.find { it.id == nodeId }
            if (node == null) {
                Log.w(TAG, "Node not found: $nodeId")
                return@withContext NodeSwitchResult.Success
            }
            
            try {
                val configPath = generateConfigFile()
                if (configPath.isNullOrBlank()) {
                    val msg = "配置生成失败"
                    Log.e(TAG, msg)
                    return@withContext NodeSwitchResult.Failed(msg)
                }

                val configPathStr: String = configPath

                val intent = Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_START
                    putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPathStr)
                }

                // Service is already running (VPN active), so startService is sufficient.
                // We still keep the SDK gate to avoid background restrictions if the service was somehow not running.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                Log.i(TAG, "Switched node by restarting VPN: ${node.name}")
                NodeSwitchResult.Success
            } catch (e: Exception) {
                val msg = "切换异常: ${e.message ?: "未知错误"}"
                Log.e(TAG, "Error during hot switch", e)
                NodeSwitchResult.Failed(msg)
            }
        }
    }

    suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean {
        if (proxyName.isNullOrBlank()) return false

        val activeProfileId = _activeProfileId.value
        val candidates = if (activeProfileId != null) {
            _nodes.value + _allNodes.value.filter { it.sourceProfileId != activeProfileId }
        } else {
            _allNodes.value
        }

        val matched = candidates.firstOrNull { it.name == proxyName } ?: return false
        if (_activeNodeId.value == matched.id) return true

        _activeNodeId.value = matched.id
        Log.i(TAG, "Synced active node from service selection: $proxyName -> ${matched.id}")
        return true
    }
    
    fun deleteProfile(profileId: String) {
        _profiles.update { list -> list.filter { it.id != profileId } }
        removeCachedConfig(profileId)
        profileNodes.remove(profileId)
        updateAllNodesAndGroups()
        
        // 删除配置文件
        File(configDir, "$profileId.json").delete()
        
        if (_activeProfileId.value == profileId) {
            val newActiveId = _profiles.value.firstOrNull()?.id
            _activeProfileId.value = newActiveId
            if (newActiveId != null) {
                setActiveProfile(newActiveId)
            } else {
                _nodes.value = emptyList()
                _nodeGroups.value = listOf("全部")
                _activeNodeId.value = null
            }
        }
        saveProfiles()
    }
    
    fun toggleProfileEnabled(profileId: String) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(enabled = !it.enabled) else it
            }
        }
        saveProfiles()
    }

    /**
     * 测试单个节点的延迟（真正通过代理测试）
     * @param nodeId 节点 ID
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
    suspend fun testNodeLatency(nodeId: String): Long {
        val existing = inFlightLatencyTests[nodeId]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<Long>()
        val prev = inFlightLatencyTests.putIfAbsent(nodeId, deferred)
        if (prev != null) {
            return prev.await()
        }

        try {
            val result = withContext(Dispatchers.IO) {
                try {
                    val node = _nodes.value.find { it.id == nodeId }
                    if (node == null) {
                        Log.e(TAG, "Node not found: $nodeId")
                        return@withContext -1L
                    }

                    val config = loadConfig(node.sourceProfileId)
                    if (config == null) {
                        Log.e(TAG, "Config not found for profile: ${node.sourceProfileId}")
                        return@withContext -1L
                    }

                    val outbound = config.outbounds?.find { it.tag == node.name }
                    if (outbound == null) {
                        Log.e(TAG, "Outbound not found: ${node.name}")
                        return@withContext -1L
                    }

                    Log.v(TAG, "Testing latency for node: ${node.name} (${outbound.type})")
                    val fixedOutbound = fixOutboundForRuntime(outbound)
                    val latency = singBoxCore.testOutboundLatency(fixedOutbound)

                    _nodes.update { list ->
                        list.map {
                            if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else null) else it
                        }
                    }

                    profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                        if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else null) else it
                    } ?: emptyList()
                    updateLatencyInAllNodes(nodeId, latency)

                    Log.v(TAG, "Latency test result for ${node.name}: ${latency}ms")
                    latency
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        -1L
                    } else {
                        Log.e(TAG, "Latency test error for $nodeId", e)
                        -1L
                    }
                }
            }
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            deferred.complete(-1L)
            return -1L
        } finally {
            inFlightLatencyTests.remove(nodeId, deferred)
        }
    }

    /**
     * 批量测试所有节点的延迟
     * 使用并发方式提高效率
     */
    suspend fun clearAllNodesLatency() = withContext(Dispatchers.IO) {
        _nodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }
        
        // Update profileNodes map
        profileNodes.keys.forEach { profileId ->
            profileNodes[profileId] = profileNodes[profileId]?.map {
                it.copy(latencyMs = null)
            } ?: emptyList()
        }
        _allNodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }
    }

    suspend fun testAllNodesLatency() = withContext(Dispatchers.IO) {
        val nodes = _nodes.value
        Log.d(TAG, "Starting latency test for ${nodes.size} nodes")

        data class NodeTestInfo(
            val outbound: Outbound,
            val nodeId: String,
            val profileId: String
        )

        val testInfoList = nodes.mapNotNull { node ->
            val config = loadConfig(node.sourceProfileId) ?: return@mapNotNull null
            val outbound = config.outbounds?.find { it.tag == node.name } ?: return@mapNotNull null
            NodeTestInfo(fixOutboundForRuntime(outbound), node.id, node.sourceProfileId)
        }

        if (testInfoList.isEmpty()) {
            Log.w(TAG, "No valid nodes to test")
            return@withContext
        }

        val outbounds = testInfoList.map { it.outbound }
        val tagToInfo = testInfoList.associateBy { it.outbound.tag }

        singBoxCore.testOutboundsLatency(outbounds) { tag, latency ->
            val info = tagToInfo[tag] ?: return@testOutboundsLatency
            val latencyValue = if (latency > 0) latency else null
            
            _nodes.update { list ->
                list.map {
                    if (it.id == info.nodeId) it.copy(latencyMs = latencyValue) else it
                }
            }

            profileNodes[info.profileId] = profileNodes[info.profileId]?.map {
                if (it.id == info.nodeId) it.copy(latencyMs = latencyValue) else it
            } ?: emptyList()
            
            updateLatencyInAllNodes(info.nodeId, latency)

            Log.d(TAG, "Latency: ${info.outbound.tag} = ${latency}ms")
        }

        Log.d(TAG, "Latency test completed for all nodes")
    }

    suspend fun updateAllProfiles(): BatchUpdateResult {
        val enabledProfiles = _profiles.value.filter { it.enabled && it.type == ProfileType.Subscription }
        
        if (enabledProfiles.isEmpty()) {
            return BatchUpdateResult()
        }
        
        val results = mutableListOf<SubscriptionUpdateResult>()
        
        enabledProfiles.forEach { profile ->
            val result = updateProfile(profile.id)
            results.add(result)
        }
        
        return BatchUpdateResult(
            successWithChanges = results.count { it is SubscriptionUpdateResult.SuccessWithChanges },
            successNoChanges = results.count { it is SubscriptionUpdateResult.SuccessNoChanges },
            failed = results.count { it is SubscriptionUpdateResult.Failed },
            details = results
        )
    }
    
    suspend fun updateProfile(profileId: String): SubscriptionUpdateResult {
        val profile = _profiles.value.find { it.id == profileId }
            ?: return SubscriptionUpdateResult.Failed("未知配置", "配置不存在")
        
        if (profile.url.isNullOrBlank()) {
            return SubscriptionUpdateResult.Failed(profile.name, "无订阅链接")
        }
        
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Updating) else it
            }
        }
        
        val result = try {
            importFromSubscriptionUpdate(profile)
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "未知错误")
        }

        // 更新状态为 Success/Failed
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(
                    updateStatus = if (result is SubscriptionUpdateResult.Failed) UpdateStatus.Failed else UpdateStatus.Success,
                    lastUpdated = if (result is SubscriptionUpdateResult.Failed) it.lastUpdated else System.currentTimeMillis()
                ) else it
            }
        }

        // 异步延迟重置状态，不阻塞当前方法返回
        profileResetJobs.remove(profileId)?.cancel()
        val resetJob = scope.launch {
            kotlinx.coroutines.delay(2000)
            _profiles.update { list ->
                list.map {
                    if (it.id == profileId && it.updateStatus != UpdateStatus.Updating) {
                        it.copy(updateStatus = UpdateStatus.Idle)
                    } else {
                        it
                    }
                }
            }
        }
        resetJob.invokeOnCompletion {
            profileResetJobs.remove(profileId, resetJob)
        }
        profileResetJobs[profileId] = resetJob
        
        return result
    }
    
    private suspend fun importFromSubscriptionUpdate(profile: ProfileUi): SubscriptionUpdateResult = withContext(Dispatchers.IO) {
        try {
            // 获取旧的节点列表用于比较
            val oldNodes = profileNodes[profile.id] ?: emptyList()
            val oldNodeNames = oldNodes.map { it.name }.toSet()
            
            // 使用智能 User-Agent 切换策略获取订阅
            val config = fetchAndParseSubscription(profile.url!!) { /* 静默更新，不显示进度 */ }
                ?: return@withContext SubscriptionUpdateResult.Failed(profile.name, "无法解析配置")
            
            val newNodes = extractNodesFromConfig(config, profile.id)
            val newNodeNames = newNodes.map { it.name }.toSet()
            
            // 计算变化
            val addedNodes = newNodeNames - oldNodeNames
            val removedNodes = oldNodeNames - newNodeNames
            
            // 更新存储
            val configFile = File(configDir, "${profile.id}.json")
            configFile.writeText(gson.toJson(config))
            
            cacheConfig(profile.id, config)
            profileNodes[profile.id] = newNodes
            updateAllNodesAndGroups()
            
            // 如果是当前活跃配置，更新节点列表
            if (_activeProfileId.value == profile.id) {
                _nodes.value = newNodes
                updateNodeGroups(newNodes)
            }
            
            saveProfiles()
            
            // 返回结果
            if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty()) {
                SubscriptionUpdateResult.SuccessWithChanges(
                    profileName = profile.name,
                    addedCount = addedNodes.size,
                    removedCount = removedNodes.size,
                    totalCount = newNodes.size
                )
            } else {
                SubscriptionUpdateResult.SuccessNoChanges(
                    profileName = profile.name,
                    totalCount = newNodes.size
                )
            }
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "未知错误")
        }
    }
    
    /**
     * 生成用于 VPN 服务的配置文件
     * @return 配置文件路径，null 表示失败
     */
    suspend fun generateConfigFile(): String? = withContext(Dispatchers.IO) {
        try {
            val activeId = _activeProfileId.value ?: return@withContext null
            val config = loadConfig(activeId) ?: return@withContext null
            val activeNodeId = _activeNodeId.value
            val activeNode = _nodes.value.find { it.id == activeNodeId }
            
            // 获取当前设置
            val settings = settingsRepository.settings.first()

            // 构建完整的运行配置
            val runConfig = buildRunConfig(config, activeNode, settings)
            
            // 写入临时配置文件
            val configFile = File(context.filesDir, "running_config.json")
            configFile.writeText(gson.toJson(runConfig))
            
            Log.d(TAG, "Generated config file: ${configFile.absolutePath}")
            configFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate config file", e)
            null
        }
    }
    
    /**
     * 运行时修复 Outbound 配置
     * 包括：修复 interval 单位、清理 flow、补充 ALPN、补充 User-Agent
     */
    private fun fixOutboundForRuntime(outbound: Outbound): Outbound {
        var result = outbound

        val interval = result.interval
        if (interval != null) {
            val fixedInterval = when {
                interval.matches(Regex("^\\d+$")) -> "${interval}s"
                interval.matches(Regex("^\\d+\\.\\d+$")) -> "${interval}s"
                interval.matches(Regex("^\\d+[smhd]$", RegexOption.IGNORE_CASE)) -> interval.lowercase()
                else -> interval
            }
            if (fixedInterval != interval) {
                result = result.copy(interval = fixedInterval)
            }
        }

        // Fix flow
        val cleanedFlow = result.flow?.takeIf { it.isNotBlank() }
        if (cleanedFlow != result.flow) {
            result = result.copy(flow = cleanedFlow)
        }

        // Fix URLTest - Convert to selector to avoid sing-box core panic during InterfaceUpdated
        // The urltest implementation in some sing-box versions has race condition issues
        if (result.type == "urltest" || result.type == "url-test") {
            var newOutbounds = result.outbounds
            if (newOutbounds.isNullOrEmpty()) {
                newOutbounds = listOf("direct")
            }
            
            // Convert urltest to selector to avoid crash
            result = result.copy(
                type = "selector",
                outbounds = newOutbounds,
                default = newOutbounds.firstOrNull(),
                interruptExistConnections = false,
                // Clear urltest-specific fields
                url = null,
                interval = null,
                tolerance = null
            )
            Log.d(TAG, "Converted urltest '${result.tag}' to selector with ${newOutbounds.size} nodes")
        }
        
        // Fix Selector empty outbounds
        if (result.type == "selector" && result.outbounds.isNullOrEmpty()) {
            result = result.copy(outbounds = listOf("direct"))
        }

        // Fix ALPN for WS
        val tls = result.tls
        if (result.transport?.type == "ws" && tls?.enabled == true && (tls.alpn == null || tls.alpn.isEmpty())) {
            result = result.copy(tls = tls.copy(alpn = listOf("http/1.1")))
        }

        // Fix User-Agent and path for WS
        val transport = result.transport
        if (transport != null && transport.type == "ws") {
            val headers = transport.headers?.toMutableMap() ?: mutableMapOf()
            var needUpdate = false
            
            // 如果没有 Host，尝试从 SNI 或 Server 获取
            if (!headers.containsKey("Host")) {
                val host = transport.host?.firstOrNull()
                    ?: result.tls?.serverName
                    ?: result.server
                if (!host.isNullOrBlank()) {
                    headers["Host"] = host
                    needUpdate = true
                }
            }
            
            // 补充 User-Agent
            if (!headers.containsKey("User-Agent")) {
                val fingerprint = result.tls?.utls?.fingerprint
                val userAgent = if (fingerprint?.contains("chrome") == true) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                }
                headers["User-Agent"] = userAgent
                needUpdate = true
            }
            
            // 清理路径中的 ed 参数
            val rawPath = transport.path ?: "/"
            val cleanPath = rawPath
                .replace(Regex("""\?ed=\d+(&|$)"""), "")
                .replace(Regex("""&ed=\d+"""), "")
                .trimEnd('?', '&')
                .ifEmpty { "/" }
            
            val pathChanged = cleanPath != rawPath
            
            if (needUpdate || pathChanged) {
                result = result.copy(transport = transport.copy(
                    headers = headers,
                    path = cleanPath
                ))
            }
        }

        return result
    }

    /**
     * 构建广告拦截路由规则（使用在线规则集）
     */
    private fun buildAdBlockRules(): List<RouteRule> {
        Log.v(TAG, "Building ad-block routing rules with rule-set")
        
        return listOf(
            RouteRule(
                ruleSet = listOf("geosite-category-ads-all"),
                outbound = "block"
            )
        )
    }
    
    /**
     * 构建广告拦截规则集配置
     */
    private fun buildAdBlockRuleSet(settings: AppSettings): RuleSetConfig {
        // 使用本地缓存路径，避免启动时下载
        val ruleSetRepo = RuleSetRepository.getInstance(context)
        val localPath = ruleSetRepo.getRuleSetPath("geosite-category-ads-all")
        
        return RuleSetConfig(
            tag = "geosite-category-ads-all",
            type = "local",
            format = "binary",
            path = localPath
        )
    }
    
    /**
     * 构建自定义规则集配置
     */
    private fun buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig> {
        val ruleSetRepo = RuleSetRepository.getInstance(context)
        
        return settings.ruleSets.map { ruleSet ->
            if (ruleSet.type == RuleSetType.REMOTE) {
                // 远程规则集：使用预下载的本地缓存
                val localPath = ruleSetRepo.getRuleSetPath(ruleSet.tag)
                RuleSetConfig(
                    tag = ruleSet.tag,
                    type = "local",
                    format = ruleSet.format,
                    path = localPath
                )
            } else {
                // 本地规则集：直接使用用户指定的路径
                RuleSetConfig(
                    tag = ruleSet.tag,
                    type = "local",
                    format = ruleSet.format,
                    path = ruleSet.path
                )
            }
        }
    }

    /**
     * 构建自定义规则集路由规则
     */
    private fun buildCustomRuleSetRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        // 记录所有可用的 outbound tags，用于调试
        val availableTags = outbounds.map { it.tag }
        Log.v(TAG, "Available outbound tags for rule matching: $availableTags")
        
        // 对规则集进行排序：更具体的规则应该排在前面
        // 优先级：单节点/分组 > 代理 > 直连 > 拦截
        // 同时，特定服务的规则（如 google, youtube）应该优先于泛化规则（如 geolocation-!cn）
        val sortedRuleSets = settings.ruleSets.filter { it.enabled }.sortedWith(
            compareBy(
                // 泛化规则排后面（如 geolocation-!cn, geolocation-cn）
                { ruleSet ->
                    when {
                        ruleSet.tag.contains("geolocation-!cn") -> 100
                        ruleSet.tag.contains("geolocation-cn") -> 99
                        ruleSet.tag.contains("!cn") -> 98
                        else -> 0
                    }
                },
                // 单节点模式的规则优先
                { ruleSet ->
                    when (ruleSet.outboundMode) {
                        RuleSetOutboundMode.NODE -> 0
                        RuleSetOutboundMode.GROUP -> 1
                        RuleSetOutboundMode.PROXY -> 2
                        RuleSetOutboundMode.DIRECT -> 3
                        RuleSetOutboundMode.BLOCK -> 4
                        RuleSetOutboundMode.PROFILE -> 2
                        null -> 5
                    }
                }
            )
        )
        
        Log.v(TAG, "Sorted rule sets order: ${sortedRuleSets.map { "${it.tag}(${it.outboundMode})" }}")
        
        sortedRuleSets.forEach { ruleSet ->
            Log.v(TAG, "Processing rule set: ${ruleSet.tag}, mode=${ruleSet.outboundMode}, value=${ruleSet.outboundValue}")
            
            val outboundTag = when (ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(ruleSet.outboundValue)
                    if (resolvedTag != null) {
                         resolvedTag
                    } else {
                         Log.w(TAG, "Node ID '${ruleSet.outboundValue}' not resolved to any tag, falling back to $defaultProxyTag")
                         defaultProxyTag
                    }
                }
                RuleSetOutboundMode.PROFILE -> {
                     val profileId = ruleSet.outboundValue
                     val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                     val tag = "P:$profileName"
                     if (outbounds.any { it.tag == tag }) {
                         tag
                     } else {
                         defaultProxyTag
                     }
                }
                RuleSetOutboundMode.GROUP -> {
                    val groupName = ruleSet.outboundValue
                    if (!groupName.isNullOrEmpty() && outbounds.any { it.tag == groupName }) {
                         groupName
                    } else {
                         defaultProxyTag
                    }
                }
            }

            // 处理入站限制
            val inboundTags = if (ruleSet.inbounds.isNullOrEmpty()) {
                null
            } else {
                // 将简化的 "tun", "mixed" 映射为实际的 inbound tag
                ruleSet.inbounds.map {
                    when(it) {
                        "tun" -> "tun-in"
                        "mixed" -> "mixed-in" // 假设有这个 inbound
                        else -> it
                    }
                }
            }

            rules.add(RouteRule(
                ruleSet = listOf(ruleSet.tag),
                outbound = outboundTag,
                inbound = inboundTags
            ))
            
            Log.v(TAG, "Added rule set rule: ${ruleSet.tag} -> $outboundTag (inbounds: $inboundTags)")
        }

        return rules
    }

    /**
     * 构建应用分流路由规则
     */
    /**
     * 构建自定义简单路由规则（单域名、域名后缀、IP 等）
     */
    private fun buildCustomRules(
        settings: AppSettings,
        defaultProxyTag: String
    ): List<RouteRule> {
        return settings.customRules.filter { it.enabled }.map { rule ->
            val outbound = when (rule.outbound) {
                OutboundTag.DIRECT -> "direct"
                OutboundTag.PROXY -> defaultProxyTag
                OutboundTag.BLOCK -> "block"
            }

            when (rule.type) {
                RuleType.DOMAIN -> RouteRule(domain = listOf(rule.value), outbound = outbound)
                RuleType.DOMAIN_SUFFIX -> RouteRule(domainSuffix = listOf(rule.value), outbound = outbound)
                RuleType.DOMAIN_KEYWORD -> RouteRule(domainKeyword = listOf(rule.value), outbound = outbound)
                RuleType.IP_CIDR -> RouteRule(ipCidr = listOf(rule.value), outbound = outbound)
                RuleType.GEOIP -> RouteRule(ipCidr = listOf("geoip:${rule.value}"), outbound = outbound) 
                RuleType.GEOSITE -> RouteRule(ruleSet = listOf("geosite:${rule.value}"), outbound = outbound)
                RuleType.PORT -> {
                    val port = rule.value.toIntOrNull()
                    if (port != null) RouteRule(port = listOf(port), outbound = outbound) else null
                }
                RuleType.PROCESS_NAME -> RouteRule(processName = listOf(rule.value), outbound = outbound)
            }
        }.filterNotNull()
    }

    private fun buildAppRoutingRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        fun resolveUidByPackageName(pkg: String): Int {
            return try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                info.uid
            } catch (_: Exception) {
                0
            }
        }
        
        fun resolveOutboundTag(mode: RuleSetOutboundMode?, value: String?): String {
            return when (mode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(value)
                    if (resolvedTag != null) resolvedTag else defaultProxyTag
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = value
                    val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) tag else defaultProxyTag
                }
                RuleSetOutboundMode.GROUP -> {
                    if (value.isNullOrBlank()) return defaultProxyTag
                    if (outbounds.any { it.tag == value }) value else defaultProxyTag
                }
            }
        }
        
        // 1. 处理应用规则（单个应用）
        settings.appRules.filter { it.enabled }.forEach { rule ->
            val outboundTag = resolveOutboundTag(rule.outboundMode, rule.outboundValue)

            val uid = resolveUidByPackageName(rule.packageName)
            if (uid > 0) {
                rules.add(
                    RouteRule(
                        userId = listOf(uid),
                        outbound = outboundTag
                    )
                )
            }

            rules.add(
                RouteRule(
                    packageName = listOf(rule.packageName),
                    outbound = outboundTag
                )
            )
            
            Log.v(TAG, "Added app rule: ${rule.appName} (${rule.packageName}) -> $outboundTag")
        }
        
        // 2. 处理应用分组
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val outboundTag = resolveOutboundTag(group.outboundMode, group.outboundValue)
            
            // 将分组中的所有应用包名添加到一条规则中
            val packageNames = group.apps.map { it.packageName }
            if (packageNames.isNotEmpty()) {
                val uids = packageNames.map { resolveUidByPackageName(it) }.filter { it > 0 }.distinct()
                if (uids.isNotEmpty()) {
                    rules.add(
                        RouteRule(
                            userId = uids,
                            outbound = outboundTag
                        )
                    )
                }

                rules.add(
                    RouteRule(
                        packageName = packageNames,
                        outbound = outboundTag
                    )
                )
                
                Log.v(TAG, "Added app group rule: ${group.name} (${packageNames.size} apps) -> $outboundTag")
            }
        }
        
        Log.v(TAG, "Generated ${rules.size} app routing rules")
        return rules
    }
    
    /**
     * 构建运行时配置
     */
    private fun buildRunConfig(baseConfig: SingBoxConfig, activeNode: NodeUi?, settings: AppSettings): SingBoxConfig {
        // 配置日志级别为 warn 以减少日志量
        val log = LogConfig(
            level = "warn",
            timestamp = true
        )

        // 使用 filesDir 而非 cacheDir，确保 FakeIP 缓存不会被系统清理
        val singboxDataDir = File(context.filesDir, "singbox_data").also { it.mkdirs() }

        val experimental = ExperimentalConfig(
            cacheFile = CacheFileConfig(
                enabled = true,
                path = File(singboxDataDir, "cache.db").absolutePath,
                storeFakeip = settings.fakeDnsEnabled
            )
        )
        
        // 添加入站配置
        val inbounds = mutableListOf<Inbound>()
        
        // 1. 添加混合入站 (Mixed Port)
        if (settings.proxyPort > 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
                    listenPort = settings.proxyPort,
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        }

        if (settings.tunEnabled) {
            inbounds.add(
                Inbound(
                    type = "tun",
                    tag = "tun-in",
                    interfaceName = settings.tunInterfaceName,
                    inet4Address = listOf("172.19.0.1/30"),
                    mtu = settings.tunMtu,
                    autoRoute = false, // Handled by Android VpnService
                    strictRoute = false, // Can cause issues on some Android versions
                    stack = settings.tunStack.name.lowercase(), // gvisor/system/mixed/lwip
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        } else if (settings.proxyPort <= 0) {
            // 如果禁用 TUN 且未设置自定义端口，则添加默认混合入站（HTTP+SOCKS），方便本地代理使用
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = "127.0.0.1",
                    listenPort = 2080,
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        }
        
        // 添加 DNS 配置
        val dnsServers = mutableListOf<DnsServer>()
        val dnsRules = mutableListOf<DnsRule>()

        // 0. Bootstrap DNS (必须是 IP，用于解析其他 DoH/DoT 域名)
        // 使用多个 IP 以提高可靠性
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap",
                address = "223.5.5.5", // AliDNS IP
                detour = "direct",
                strategy = "ipv4_only"
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap-backup",
                address = "119.29.29.29", // DNSPod IP
                detour = "direct",
                strategy = "ipv4_only"
            )
        )
        // 也可以使用一个多地址的 Server (如果内核支持)
        
        // 1. 本地 DNS
        val localDnsAddr = settings.localDns.takeIf { it.isNotBlank() } ?: "https://dns.alidns.com/dns-query"
        dnsServers.add(
            DnsServer(
                tag = "local",
                address = localDnsAddr,
                detour = "direct",
                strategy = mapDnsStrategy(settings.directDnsStrategy),
                addressResolver = "dns-bootstrap"
            )
        )

        // 2. 远程 DNS (走代理)
        val remoteDnsAddr = settings.remoteDns.takeIf { it.isNotBlank() } ?: "https://dns.google/dns-query"
        dnsServers.add(
            DnsServer(
                tag = "remote",
                address = remoteDnsAddr,
                detour = "PROXY",
                strategy = mapDnsStrategy(settings.remoteDnsStrategy),
                addressResolver = "dns-bootstrap" // 必须指定解析器
            )
        )

        if (settings.fakeDnsEnabled) {
            dnsServers.add(
                DnsServer(
                    tag = "fakeip-dns",
                    address = "fakeip"
                )
            )
        }

        // 3. 备用公共 DNS (直接连接，用于 bootstrap 和兜底)
        dnsServers.add(
            DnsServer(
                tag = "google-dns",
                address = "8.8.8.8",
                detour = "direct"
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "cloudflare-dns",
                address = "1.1.1.1",
                detour = "direct"
            )
        )

        // 4. 备用国内 DNS
        dnsServers.add(
            DnsServer(
                tag = "dnspod",
                address = "119.29.29.29",
                detour = "direct",
                strategy = mapDnsStrategy(settings.directDnsStrategy)
            )
        )

        // 优化：代理类域名的 DNS 处理
        val proxyRuleSets = mutableListOf<String>()
        val possibleProxyTags = listOf(
            "geosite-geolocation-!cn", "geosite-google", "geosite-openai", 
            "geosite-youtube", "geosite-telegram", "geosite-github", 
            "geosite-twitter", "geosite-netflix", "geosite-apple",
            "geosite-facebook", "geosite-instagram", "geosite-tiktok",
            "geosite-disney", "geosite-microsoft", "geosite-amazon"
        )
        possibleProxyTags.forEach { tag ->
            if (settings.ruleSets.any { it.tag == tag }) proxyRuleSets.add(tag)
        }

        if (proxyRuleSets.isNotEmpty()) {
            if (settings.fakeDnsEnabled) {
                // 如果开启了 FakeIP，代理域名必须返回 FakeIP 以支持域名分流规则
                dnsRules.add(
                    DnsRule(
                        ruleSet = proxyRuleSets,
                        server = "fakeip-dns"
                    )
                )
            } else {
                // 未开启 FakeIP，则使用远程 DNS
                dnsRules.add(
                    DnsRule(
                        ruleSet = proxyRuleSets,
                        server = "remote"
                    )
                )
            }
        }

        // 优化：直连/绕过类域名的 DNS 强制走 local
        val directRuleSets = mutableListOf<String>()
        if (settings.ruleSets.any { it.tag == "geosite-cn" }) directRuleSets.add("geosite-cn")
        
        if (directRuleSets.isNotEmpty()) {
            dnsRules.add(
                DnsRule(
                    ruleSet = directRuleSets,
                    server = "local"
                )
            )
        }
        
        // 5. 应用特定 DNS 规则（确保应用分流的应用 DNS 走正确的服务器）
        val appPackagesForDns = (settings.appRules.filter { it.enabled }.map { it.packageName } +
                settings.appGroups.filter { it.enabled }.flatMap { it.apps.map { it.packageName } }).distinct()
        
        if (appPackagesForDns.isNotEmpty()) {
            val serverTag = if (settings.fakeDnsEnabled) "fakeip-dns" else "remote"
            val uids = appPackagesForDns.map {
                try {
                    context.packageManager.getApplicationInfo(it, 0).uid
                } catch (_: Exception) {
                    0
                }
            }.filter { it > 0 }.distinct()

            if (uids.isNotEmpty()) {
                dnsRules.add(
                    0,
                    DnsRule(
                        userId = uids,
                        server = serverTag
                    )
                )
            }

            dnsRules.add(
                if (uids.isNotEmpty()) 1 else 0,
                DnsRule(
                    packageName = appPackagesForDns,
                    server = serverTag
                )
            )
        }
        
        // Fake DNS 兜底
        if (settings.fakeDnsEnabled) {
            dnsRules.add(
                DnsRule(
                    queryType = listOf("A", "AAAA"),
                    server = "fakeip-dns"
                )
            )
        }

        val fakeIpConfig = if (settings.fakeDnsEnabled) {
            DnsFakeIpConfig(
                enabled = true,
                inet4Range = "198.18.0.0/15",
                inet6Range = "fc00::/18"
            )
        } else {
            null
        }

        val dns = DnsConfig(
            servers = dnsServers,
            rules = dnsRules,
            finalServer = "local", // 兜底使用本地 DNS
            strategy = mapDnsStrategy(settings.dnsStrategy),
            disableCache = !settings.dnsCacheEnabled,
            independentCache = true,
            fakeip = fakeIpConfig
        )
        
        val rawOutbounds = baseConfig.outbounds
        if (rawOutbounds.isNullOrEmpty()) {
            Log.w(TAG, "No outbounds found in base config, adding defaults")
        }
        val fixedOutbounds = rawOutbounds?.map { outbound ->
            var fixed = fixOutboundForRuntime(outbound)
            // 启用 TCP Fast Open
            if (fixed.type in listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria", "anytls", "tuic")) {
                fixed = fixed.copy(tcpFastOpen = true)
            }
            fixed
        }?.toMutableList() ?: mutableListOf()
        
        if (fixedOutbounds.none { it.tag == "direct" }) {
            fixedOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        if (fixedOutbounds.none { it.tag == "block" }) {
            fixedOutbounds.add(Outbound(type = "block", tag = "block"))
        }
        if (fixedOutbounds.none { it.tag == "dns-out" }) {
            fixedOutbounds.add(Outbound(type = "dns", tag = "dns-out"))
        }

        // --- 处理跨配置节点引用 ---
        val activeProfileId = _activeProfileId.value
        val allNodes = _allNodes.value
        val requiredNodeIds = mutableSetOf<String>()
        val requiredGroupNames = mutableSetOf<String>()
        val requiredProfileIds = mutableSetOf<String>()

        fun resolveNodeRefToId(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("::", limit = 2)
            if (parts.size == 2) {
                val profileId = parts[0]
                val nodeName = parts[1]
                return allNodes.firstOrNull { it.sourceProfileId == profileId && it.name == nodeName }?.id
            }
            if (allNodes.any { it.id == value }) return value
            val node = if (activeProfileId != null) {
                allNodes.firstOrNull { it.sourceProfileId == activeProfileId && it.name == value }
                    ?: allNodes.firstOrNull { it.name == value }
            } else {
                allNodes.firstOrNull { it.name == value }
            }
            return node?.id
        }

        // 收集所有规则中引用的节点 ID、组名称和配置 ID
        settings.appRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.GROUP -> rule.outboundValue?.let { requiredGroupNames.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            when (group.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(group.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.GROUP -> group.outboundValue?.let { requiredGroupNames.add(it) }
                RuleSetOutboundMode.PROFILE -> group.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.ruleSets.filter { it.enabled }.forEach { ruleSet ->
            when (ruleSet.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(ruleSet.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.GROUP -> ruleSet.outboundValue?.let { requiredGroupNames.add(it) }
                RuleSetOutboundMode.PROFILE -> ruleSet.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }

        // 确保当前选中的节点始终可用
        activeNode?.let { requiredNodeIds.add(it.id) }

        // 将所需组和配置中的所有节点 ID 也加入到 requiredNodeIds
        requiredGroupNames.forEach { groupName ->
            allNodes.filter { it.group == groupName }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }
        requiredProfileIds.forEach { profileId ->
            allNodes.filter { it.sourceProfileId == profileId }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }

        // 建立 NodeID -> OutboundTag 的映射
        val nodeTagMap = mutableMapOf<String, String>()
        val existingTags = fixedOutbounds.map { it.tag }.toMutableSet()

        // 1. 先映射当前配置中的节点
        if (activeProfileId != null) {
            allNodes.filter { it.sourceProfileId == activeProfileId }.forEach { node ->
                if (existingTags.contains(node.name)) {
                    nodeTagMap[node.id] = node.name
                }
            }
        }

        // 2. 处理需要引入的外部节点
        requiredNodeIds.forEach { nodeId ->
            if (nodeTagMap.containsKey(nodeId)) return@forEach // 已经在当前配置中

            val node = allNodes.find { it.id == nodeId } ?: return@forEach
            val sourceProfileId = node.sourceProfileId
            
            // 如果是当前配置但没找到tag(可能改名了?), 跳过
            if (sourceProfileId == activeProfileId) return@forEach

            // 加载外部配置
            val sourceConfig = loadConfig(sourceProfileId) ?: return@forEach
            val sourceOutbound = sourceConfig.outbounds?.find { it.tag == node.name } ?: return@forEach
            
            // 运行时修复
            var fixedSourceOutbound = fixOutboundForRuntime(sourceOutbound)
            
            // 启用 TCP Fast Open
            if (fixedSourceOutbound.type in listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria", "anytls", "tuic")) {
                fixedSourceOutbound = fixedSourceOutbound.copy(tcpFastOpen = true)
            }
            
            // 处理标签冲突
            var finalTag = fixedSourceOutbound.tag
            if (existingTags.contains(finalTag)) {
                // 冲突，生成新标签: Name_ProfileSuffix
                val suffix = sourceProfileId.take(4)
                finalTag = "${finalTag}_$suffix"
                // 如果还冲突 (极小概率), 再加随机
                if (existingTags.contains(finalTag)) {
                    finalTag = "${finalTag}_${java.util.UUID.randomUUID().toString().take(4)}"
                }
                fixedSourceOutbound = fixedSourceOutbound.copy(tag = finalTag)
            }
            
            // 添加到 outbounds
            fixedOutbounds.add(fixedSourceOutbound)
            existingTags.add(finalTag)
            nodeTagMap[nodeId] = finalTag
            
            Log.d(TAG, "Imported external node: ${node.name} -> $finalTag from profile $sourceProfileId")
        }

        // 3. 处理需要的节点组 (Merge/Create selectors)
        requiredGroupNames.forEach { groupName ->
            val nodesInGroup = allNodes.filter { it.group == groupName }
            val nodeTags = nodesInGroup.mapNotNull { nodeTagMap[it.id] }
            
            if (nodeTags.isNotEmpty()) {
                val existingIndex = fixedOutbounds.indexOfFirst { it.tag == groupName }
                if (existingIndex >= 0) {
                    val existing = fixedOutbounds[existingIndex]
                    if (existing.type == "selector" || existing.type == "urltest") {
                        // Merge tags: existing + new (deduplicated)
                        val combinedTags = ((existing.outbounds ?: emptyList()) + nodeTags).distinct()
                        // 确保列表不为空
                        val safeTags = if (combinedTags.isEmpty()) listOf("direct") else combinedTags
                        fixedOutbounds[existingIndex] = existing.copy(outbounds = safeTags)
                        Log.d(TAG, "Updated group '$groupName' with ${safeTags.size} nodes")
                    } else {
                        Log.w(TAG, "Tag collision: '$groupName' is needed as group but exists as ${existing.type}")
                    }
                } else {
                    // Create new selector
                    val newSelector = Outbound(
                        type = "selector",
                        tag = groupName,
                        outbounds = nodeTags.distinct(),
                        interruptExistConnections = false
                    )
                    // Insert at beginning to ensure visibility/precedence
                    fixedOutbounds.add(0, newSelector)
                    Log.d(TAG, "Created synthetic group '$groupName' with ${nodeTags.size} nodes")
                }
            }
        }

        // 4. 处理需要的配置 (Create Profile selectors)
        requiredProfileIds.forEach { profileId ->
            val profileNodes = allNodes.filter { it.sourceProfileId == profileId }
            val nodeTags = profileNodes.mapNotNull { nodeTagMap[it.id] }
            val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
            val tag = "P:$profileName" // 使用 P: 前缀区分配置选择器

            if (nodeTags.isNotEmpty()) {
                val existingIndex = fixedOutbounds.indexOfFirst { it.tag == tag }
                if (existingIndex < 0) {
                    val newSelector = Outbound(
                        type = "selector",
                        tag = tag,
                        outbounds = nodeTags.distinct(),
                        interruptExistConnections = false
                    )
                    fixedOutbounds.add(0, newSelector)
                    Log.d(TAG, "Created synthetic profile selector '$tag' for profile $profileId with ${nodeTags.size} nodes")
                }
            }
        }
        
        // 收集所有代理节点名称 (包括新添加的外部节点)
        val proxyTags = fixedOutbounds.filter {
            it.type in listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria", "anytls", "tuic")
        }.map { it.tag }.toMutableList()

        // 创建一个主 Selector
        val selectorTag = "PROXY"

        // 确保代理列表不为空，否则 Selector/URLTest 会崩溃
        if (proxyTags.isEmpty()) {
            proxyTags.add("direct")
        }

        val selectorDefault = activeNode
            ?.let { nodeTagMap[it.id] ?: it.name }
            ?.takeIf { it in proxyTags }
            ?: proxyTags.firstOrNull()

        val selectorOutbound = Outbound(
            type = "selector",
            tag = selectorTag,
            outbounds = proxyTags,
            default = selectorDefault, // 设置默认选中项（确保存在于 outbounds 中）
            interruptExistConnections = true // 切换节点时断开现有连接，确保立即生效
        )
        
        // 避免重复 tag：订阅配置通常已自带 PROXY selector
        // 若已存在同 tag outbound，直接替换（并删除多余重复项）
        val existingProxyIndexes = fixedOutbounds.withIndex()
            .filter { it.value.tag == selectorTag }
            .map { it.index }
        if (existingProxyIndexes.isNotEmpty()) {
            existingProxyIndexes.asReversed().forEach { idx ->
                fixedOutbounds.removeAt(idx)
            }
        }

        // 将 Selector 添加到 outbounds 列表的最前面（或者合适的位置）
        fixedOutbounds.add(0, selectorOutbound)
        
        Log.d(TAG, "Created selector '$selectorTag' with ${proxyTags.size} nodes. Default: ${activeNode?.name}")
        
        // 定义节点标签解析器
        val nodeTagResolver: (String?) -> String? = { value ->
            if (value.isNullOrBlank()) {
                null
            } else {
                nodeTagMap[value]
                    ?: resolveNodeRefToId(value)?.let { nodeTagMap[it] }
                    ?: if (fixedOutbounds.any { it.tag == value }) value else null
            }
        }

        // 构建应用分流规则
        val appRoutingRules = buildAppRoutingRules(settings, selectorTag, fixedOutbounds, nodeTagResolver)
        
        // 构建广告拦截规则和规则集
        val adBlockRules = if (settings.blockAds) {
            buildAdBlockRules()
        } else {
            emptyList()
        }
        
        val adBlockRuleSet = if (settings.blockAds) {
            listOf(buildAdBlockRuleSet(settings))
        } else {
            emptyList()
        }

        // 构建自定义简单路由规则
        val customRules = buildCustomRules(settings, selectorTag)

        // 构建自定义规则集配置和路由规则
        val customRuleSets = buildCustomRuleSets(settings)
        val customRuleSetRules = buildCustomRuleSetRules(settings, selectorTag, fixedOutbounds, nodeTagResolver)
        
        // 添加路由配置（使用在线规则集，sing-box 1.12.0+）
        // 合并规则集时去重，以 customRuleSets 为准（用户配置优先）
        val adBlockTags = adBlockRuleSet.map { it.tag }.toSet()
        val filteredAdBlockRuleSets = adBlockRuleSet.filter { rs ->
            customRuleSets.none { it.tag == rs.tag }
        }
        
        val quicRule = if (settings.blockQuic) {
            listOf(RouteRule(protocol = listOf("udp"), port = listOf(443), outbound = "block"))
        } else {
            emptyList()
        }

        // 局域网绕过规则
        val bypassLanRules = if (settings.bypassLan) {
            listOf(
                RouteRule(
                    ipCidr = listOf(
                        "10.0.0.0/8",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "fc00::/7",
                        "127.0.0.0/8",
                        "::1/128"
                    ),
                    outbound = "direct"
                )
            )
        } else {
            emptyList()
        }

        val dnsTrafficRule = listOf(RouteRule(protocol = listOf("dns"), outbound = "dns-out"))

        val allRules = when (settings.routingMode) {
            RoutingMode.GLOBAL_PROXY -> dnsTrafficRule
            RoutingMode.GLOBAL_DIRECT -> dnsTrafficRule + listOf(RouteRule(outbound = "direct"))
            RoutingMode.RULE -> {
                // 路由规则优先级顺序：
                // 1. DNS 流量
                // 2. 绕过局域网
                // 3. 屏蔽 QUIC
                // 4. 广告拦截（最高拦截优先级）
                // 5. 自定义简单规则（用户显式规则优先级最高）
                // 6. 自定义规则集规则（如 Telegram 分流、绕过大陆）
                // 7. 应用分流规则（作为兜底匹配）
                dnsTrafficRule + bypassLanRules + quicRule + adBlockRules + customRules + customRuleSetRules + appRoutingRules
            }
        }
        
        // 记录所有生成的路由规则
        Log.v(TAG, "=== Generated Route Rules (${allRules.size} total) ===")
        allRules.forEachIndexed { index, rule ->
            val ruleDesc = buildString {
                rule.protocol?.let { append("protocol=$it ") }
                rule.ruleSet?.let { append("ruleSet=$it ") }
                rule.packageName?.let { append("pkg=$it ") }
                rule.domain?.let { append("domain=$it ") }
                rule.inbound?.let { append("inbound=$it ") }
                append("-> ${rule.outbound}")
            }
            Log.v(TAG, "  Rule[$index]: $ruleDesc")
        }
        Log.v(TAG, "=== Final outbound: $selectorTag ===")
        
        val route = RouteConfig(
            ruleSet = filteredAdBlockRuleSets + customRuleSets,
            rules = allRules,
            finalOutbound = selectorTag, // 路由指向 Selector
            findProcess = true,
            autoDetectInterface = true
        )

        // Final safety check: Filter out non-existent references in Selector/URLTest
        val allOutboundTags = fixedOutbounds.map { it.tag }.toSet()
        val safeOutbounds = fixedOutbounds.map { outbound ->
            if (outbound.type == "selector" || outbound.type == "urltest" || outbound.type == "url-test") {
                val validRefs = outbound.outbounds?.filter { allOutboundTags.contains(it) } ?: emptyList()
                val safeRefs = if (validRefs.isEmpty()) listOf("direct") else validRefs
                
                if (safeRefs.size != (outbound.outbounds?.size ?: 0)) {
                    Log.w(TAG, "Filtered invalid refs in ${outbound.tag}: ${outbound.outbounds} -> $safeRefs")
                }
                outbound.copy(outbounds = safeRefs)
            } else {
                outbound
            }
        }
        
        return baseConfig.copy(
            log = log,
            experimental = experimental,
            inbounds = inbounds,
            dns = dns,
            route = route,
            outbounds = safeOutbounds
        )
    }
    
    /**
     * 获取当前活跃配置的原始 JSON
     */
    fun getActiveConfig(): SingBoxConfig? {
        val id = _activeProfileId.value ?: return null
        return loadConfig(id)
    }
    
    /**
     * 获取指定配置的原始 JSON
     */
    fun getConfig(profileId: String): SingBoxConfig? {
        return loadConfig(profileId)
    }
    
    private fun mapDnsStrategy(strategy: DnsStrategy): String? {
        return when (strategy) {
            DnsStrategy.AUTO -> null
            DnsStrategy.PREFER_IPV4 -> "prefer_ipv4"
            DnsStrategy.PREFER_IPV6 -> "prefer_ipv6"
            DnsStrategy.ONLY_IPV4 -> "ipv4_only"
            DnsStrategy.ONLY_IPV6 -> "ipv6_only"
        }
    }

    /**
     * 根据设置中的 IP 地址解析并修复 Outbound
     */
    fun getOutboundByNodeId(nodeId: String): Outbound? {
        val node = _nodes.value.find { it.id == nodeId } ?: return null
        val config = loadConfig(node.sourceProfileId) ?: return null
        return config.outbounds?.find { it.tag == node.name }
    }
    
    /**
     * 根据节点ID获取NodeUi
     */
    fun getNodeById(nodeId: String): NodeUi? {
        return _nodes.value.find { it.id == nodeId }
    }
    
    /**
     * 删除节点
     */
    fun deleteNode(nodeId: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 过滤掉要删除的节点
        val newOutbounds = config.outbounds?.filter { it.tag != node.name }
        val newConfig = config.copy(outbounds = newOutbounds)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes
        updateAllNodesAndGroups()

        // 保存文件
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果是当前活跃配置，更新UI状态
        if (_activeProfileId.value == profileId) {
            _nodes.value = newNodes
            updateNodeGroups(newNodes)
            
            // 如果删除的是当前选中节点，重置选中
            if (_activeNodeId.value == nodeId) {
                _activeNodeId.value = newNodes.firstOrNull()?.id
            }
        }
        
        saveProfiles()
    }

    /**
     * 重命名节点
     */
    fun renameNode(nodeId: String, newName: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 更新对应节点的 tag
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) it.copy(tag = newName) else it
        }
        val newConfig = config.copy(outbounds = newOutbounds)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes
        updateAllNodesAndGroups()

        // 保存文件
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果是当前活跃配置，更新UI状态
        if (_activeProfileId.value == profileId) {
            _nodes.value = newNodes
            updateNodeGroups(newNodes)
            
            // 如果重命名的是当前选中节点，更新 activeNodeId
            if (_activeNodeId.value == nodeId) {
                val newNode = newNodes.find { it.name == newName }
                if (newNode != null) {
                    _activeNodeId.value = newNode.id
                }
            }
        }
        
        saveProfiles()
    }

    /**
     * 更新节点配置
     */
    fun updateNode(nodeId: String, newOutbound: Outbound) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 更新对应节点
        // 注意：这里假设 newOutbound.tag 已经包含了可能的新名称
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) newOutbound else it
        }
        val newConfig = config.copy(outbounds = newOutbounds)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes
        updateAllNodesAndGroups()

        // 保存文件
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果是当前活跃配置，更新UI状态
        if (_activeProfileId.value == profileId) {
            _nodes.value = newNodes
            updateNodeGroups(newNodes)
            
            // 如果更新的是当前选中节点，尝试恢复选中状态
            if (_activeNodeId.value == nodeId) {
                val newNode = newNodes.find { it.name == newOutbound.tag }
                if (newNode != null) {
                    _activeNodeId.value = newNode.id
                }
            }
        }
        
        saveProfiles()
    }

    /**
     * 导出节点链接
     */
    fun exportNode(nodeId: String): String? {
        val node = _nodes.value.find { it.id == nodeId } ?: run {
            Log.e(TAG, "exportNode: Node not found in UI list: $nodeId")
            return null
        }

        val config = loadConfig(node.sourceProfileId) ?: run {
            Log.e(TAG, "exportNode: Config not found for profile: ${node.sourceProfileId}")
            return null
        }

        val outbound = config.outbounds?.find { it.tag == node.name } ?: run {
            Log.e(TAG, "exportNode: Outbound not found in config with tag: ${node.name}")
            return null
        }

        Log.d(TAG, "exportNode: Found outbound ${outbound.tag} of type ${outbound.type}")

        val link = when (outbound.type) {
            "vless" -> generateVLessLink(outbound)
            "vmess" -> generateVMessLink(outbound)
            "shadowsocks" -> generateShadowsocksLink(outbound)
            "trojan" -> generateTrojanLink(outbound)
            "hysteria2" -> {
                val hy2 = generateHysteria2Link(outbound)
                Log.d(TAG, "exportNode: Generated hy2 link: $hy2")
                hy2
            }
            "hysteria" -> generateHysteriaLink(outbound)
            "anytls" -> generateAnyTLSLink(outbound)
            "tuic" -> generateTuicLink(outbound)
            else -> {
                Log.e(TAG, "exportNode: Unsupported type ${outbound.type}")
                null
            }
        }

        return link?.takeIf { it.isNotBlank() }
    }

    private fun encodeUrlComponent(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun formatServerHost(server: String): String {
        val s = server.trim()
        return if (s.contains(":") && !s.startsWith("[") && !s.endsWith("]")) {
            "[$s]"
        } else {
            s
        }
    }

    private fun buildOptionalQuery(params: List<String>): String {
        val query = params.filter { it.isNotBlank() }.joinToString("&")
        return if (query.isNotEmpty()) "?$query" else ""
    }

    private fun generateVLessLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val params = mutableListOf<String>()
        
        params.add("type=${outbound.transport?.type ?: "tcp"}")
        params.add("encryption=none")
        
        outbound.flow?.let { params.add("flow=$it") }
        
        if (outbound.tls?.enabled == true) {
            if (outbound.tls.reality?.enabled == true) {
                 params.add("security=reality")
                 outbound.tls.reality.publicKey?.let { params.add("pbk=${encodeUrlComponent(it)}") }
                 outbound.tls.reality.shortId?.let { params.add("sid=${encodeUrlComponent(it)}") }
                 outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            } else {
                 params.add("security=tls")
                 outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            }
            outbound.tls.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
            if (outbound.tls.insecure == true) {
                params.add("allowInsecure=1")
            }
            outbound.tls.alpn?.let { 
                if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}") 
            }
        } else {
             // params.add("security=none") // default is none
        }
        
        outbound.packetEncoding?.let { params.add("packetEncoding=$it") }
        
        // Transport specific
        when (outbound.transport?.type) {
            "ws" -> {
                val host = outbound.transport.headers?.get("Host") 
                    ?: outbound.transport.host?.firstOrNull()
                host?.let { params.add("host=${encodeUrlComponent(it)}") }
                
                var path = outbound.transport.path ?: "/"
                // Handle early data (ed)
                outbound.transport.maxEarlyData?.let { ed ->
                    if (ed != 0) { // Only add if not 0, though usually it's 2048 or something
                        val separator = if (path.contains("?")) "&" else "?"
                        path = "$path${separator}ed=$ed"
                    }
                }
                
                params.add("path=${encodeUrlComponent(path)}") 
            }
            "grpc" -> {
                outbound.transport.serviceName?.let { 
                    params.add("serviceName=${encodeUrlComponent(it)}") 
                }
                params.add("mode=gun")
            }
            "http", "h2" -> {
                 outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                 outbound.transport.host?.firstOrNull()?.let { params.add("host=${encodeUrlComponent(it)}") }
            }
        }

        val name = encodeUrlComponent(outbound.tag)
        val queryPart = buildOptionalQuery(params)
        return "vless://$uuid@$server:$port${queryPart}#$name"
    }

    private fun generateVMessLink(outbound: Outbound): String {
        // Simple implementation for VMess
        try {
            val json = VMessLinkConfig(
                v = "2",
                ps = outbound.tag,
                add = outbound.server,
                port = outbound.serverPort?.toString(),
                id = outbound.uuid,
                aid = outbound.alterId?.toString(),
                scy = outbound.security,
                net = outbound.transport?.type ?: "tcp",
                type = "none",
                host = outbound.transport?.headers?.get("Host") ?: outbound.transport?.host?.firstOrNull() ?: "",
                path = outbound.transport?.path ?: "",
                tls = if (outbound.tls?.enabled == true) "tls" else "",
                sni = outbound.tls?.serverName ?: "",
                alpn = outbound.tls?.alpn?.joinToString(","),
                fp = outbound.tls?.utls?.fingerprint
            )
            val jsonStr = gson.toJson(json)
            val base64 = Base64.encodeToString(jsonStr.toByteArray(), Base64.NO_WRAP)
            return "vmess://$base64"
        } catch (e: Exception) {
            return ""
        }
    }

    private fun generateShadowsocksLink(outbound: Outbound): String {
        val method = outbound.method ?: return ""
        val password = outbound.password ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: return ""
        val userInfo = "$method:$password"
        val encodedUserInfo = Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)
        val serverPart = "$server:$port"
        val name = encodeUrlComponent(outbound.tag)
        return "ss://$encodedUserInfo@$serverPart#$name"
    }
    
    private fun generateTrojanLink(outbound: Outbound): String {
         val password = encodeUrlComponent(outbound.password ?: "")
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         if (outbound.tls?.enabled == true) {
             params.add("security=tls")
             outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
             if (outbound.tls.insecure == true) params.add("allowInsecure=1")
         }

         val queryPart = buildOptionalQuery(params)
         return "trojan://$password@$server:$port${queryPart}#$name"
    }

    private fun generateHysteria2Link(outbound: Outbound): String {
         val password = encodeUrlComponent(outbound.password ?: "")
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         
         outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
         if (outbound.tls?.insecure == true) params.add("insecure=1")
         
         outbound.obfs?.let { obfs ->
             obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
             obfs.password?.let { params.add("obfs-password=${encodeUrlComponent(it)}") }
         }

         val queryPart = buildOptionalQuery(params)
         return "hysteria2://$password@$server:$port${queryPart}#$name"
    }

    private fun generateHysteriaLink(outbound: Outbound): String {
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         outbound.authStr?.let { params.add("auth=${encodeUrlComponent(it)}") }
         outbound.upMbps?.let { params.add("upmbps=$it") }
         outbound.downMbps?.let { params.add("downmbps=$it") }
         
         outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
         if (outbound.tls?.insecure == true) params.add("insecure=1")
         outbound.tls?.alpn?.let { 
             if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}") 
         }
         
         outbound.obfs?.let { obfs ->
             obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
         }

         val queryPart = buildOptionalQuery(params)
         return "hysteria://$server:$port${queryPart}#$name"
    }
    
    /**
     * 生成 AnyTLS 链接
     */
    private fun generateAnyTLSLink(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)
        
        val params = mutableListOf<String>()
        
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
        
        outbound.idleSessionCheckInterval?.let { params.add("idle_session_check_interval=$it") }
        outbound.idleSessionTimeout?.let { params.add("idle_session_timeout=$it") }
        outbound.minIdleSession?.let { params.add("min_idle_session=$it") }
        
        val queryPart = buildOptionalQuery(params)
        return "anytls://$password@$server:$port${queryPart}#$name"
    }
    
    /**
     * 生成 TUIC 链接
     */
    private fun generateTuicLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: ""
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)
        
        val params = mutableListOf<String>()
        
        outbound.congestionControl?.let { params.add("congestion_control=${encodeUrlComponent(it)}") }
        outbound.udpRelayMode?.let { params.add("udp_relay_mode=${encodeUrlComponent(it)}") }
        if (outbound.zeroRttHandshake == true) params.add("reduce_rtt=1")
        
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("allow_insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
        
        val queryPart = buildOptionalQuery(params)
        return "tuic://$uuid:$password@$server:$port${queryPart}#$name"
    }
}
