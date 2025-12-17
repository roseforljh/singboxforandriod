package com.kunk.singbox.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.*
import com.kunk.singbox.utils.ClashConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 配置仓库 - 负责获取、解析和存储配置
 */
class ConfigRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "ConfigRepository"
        
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
    
    private val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())
    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()
    
    private val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()
    
    private val _nodeGroups = MutableStateFlow<List<String>>(listOf("全部"))
    val nodeGroups: StateFlow<List<String>> = _nodeGroups.asStateFlow()
    
    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()
    
    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()
    
    // 存储每个配置对应的原始配置和节点
    private val profileConfigs = mutableMapOf<String, SingBoxConfig>()
    private val profileNodes = mutableMapOf<String, List<NodeUi>>()
    
    private val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }
    
    private val profilesFile: File
        get() = File(context.filesDir, "profiles.json")
    
    init {
        loadSavedProfiles()
    }
    
    private fun loadSavedProfiles() {
        try {
            if (profilesFile.exists()) {
                val json = profilesFile.readText()
                val savedData = gson.fromJson(json, SavedProfilesData::class.java)
                _profiles.value = savedData.profiles
                _activeProfileId.value = savedData.activeProfileId
                
                // 加载每个配置的节点
                savedData.profiles.forEach { profile ->
                    val configFile = File(configDir, "${profile.id}.json")
                    if (configFile.exists()) {
                        try {
                            val configJson = configFile.readText()
                            val config = gson.fromJson(configJson, SingBoxConfig::class.java)
                            profileConfigs[profile.id] = config
                            val nodes = extractNodesFromConfig(config, profile.id)
                            profileNodes[profile.id] = nodes
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
                // 如果有活跃配置，加载其节点
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
            e.printStackTrace()
        }
    }
    
    private fun saveProfiles() {
        try {
            val data = SavedProfilesData(
                profiles = _profiles.value,
                activeProfileId = _activeProfileId.value
            )
            profilesFile.writeText(gson.toJson(data))
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "sing-box/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
            
            val responseBody = response.body?.string() 
                ?: return@withContext Result.failure(Exception("空响应"))
            
            onProgress("正在解析配置...")
            
            // 尝试解析配置
            val config = parseSubscriptionResponse(responseBody)
                ?: return@withContext Result.failure(Exception("无法解析配置格式"))
            
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
            profileConfigs[profileId] = config
            profileNodes[profileId] = nodes
            
            // 更新状态
            _profiles.update { it + profile }
            saveProfiles()
            
            // 如果是第一个配置，自动激活
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

    suspend fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        try {
            onProgress("正在解析配置...")

            val normalized = normalizeImportedContent(content)
            val config = parseSubscriptionResponse(normalized)
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

            profileConfigs[profileId] = config
            profileNodes[profileId] = nodes

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
        val trimmed = content.trim()
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
        
        // 2. 尝试解析为 Clash YAML
        try {
            val config = ClashConfigParser.parse(normalizedContent)
            if (config != null && config.outbounds != null && config.outbounds.isNotEmpty()) {
                return config
            }
        } catch (e: Exception) {
            // 继续尝试其他格式
        }

        // 3. 尝试 Base64 解码后解析
        try {
            val decoded = String(Base64.decode(normalizedContent.trim(), Base64.DEFAULT))
            
            // 尝试解析解码后的内容为 JSON
            try {
                val config = gson.fromJson(decoded, SingBoxConfig::class.java)
                if (config.outbounds != null && config.outbounds.isNotEmpty()) {
                    return config
                }
            } catch (e: Exception) {}

            // 尝试解析解码后的内容为 Clash YAML
            try {
                val config = ClashConfigParser.parse(decoded)
                if (config != null && config.outbounds != null && config.outbounds.isNotEmpty()) {
                    return config
                }
            } catch (e: Exception) {}

        } catch (e: Exception) {
            // 继续尝试其他格式
        }
        
        // 4. 尝试解析为节点链接列表 (每行一个链接)
        try {
            val lines = normalizedContent.trim().lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                // 尝试 Base64 解码整体
                val decoded = try {
                    String(Base64.decode(normalizedContent.trim(), Base64.DEFAULT))
                } catch (e: Exception) {
                    normalizedContent
                }
                
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
                "http", "h2" -> TransportConfig(
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
            e.printStackTrace()
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
            val port = uri.port
            
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
            e.printStackTrace()
        }
        return null
    }
    
    private fun parseHysteriaLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria Node", "UTF-8")
            val server = uri.host
            val port = uri.port
            
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
            "shadowtls", "ssh"
        )
        
        for (outbound in outbounds) {
            if (outbound.type in proxyTypes) {
                val group = nodeToGroup[outbound.tag] ?: "未分组"
                val regionFlag = detectRegionFlag(outbound.tag)
                
                // 如果名称中已经包含该国旗，则不再添加
                val finalRegionFlag = if (outbound.tag.contains(regionFlag)) null else regionFlag

                nodes.add(
                    NodeUi(
                        id = UUID.randomUUID().toString(),
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
     * 根据节点名称检测地区标志
     */
    private fun detectRegionFlag(name: String): String {
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("香港") || lowerName.contains("hk") || lowerName.contains("hong") -> "🇭🇰"
            lowerName.contains("台湾") || lowerName.contains("tw") || lowerName.contains("taiwan") -> "🇹🇼"
            lowerName.contains("日本") || lowerName.contains("jp") || lowerName.contains("japan") || lowerName.contains("tokyo") -> "🇯🇵"
            lowerName.contains("新加坡") || lowerName.contains("sg") || lowerName.contains("singapore") -> "🇸🇬"
            lowerName.contains("美国") || lowerName.contains("us") || lowerName.contains("united states") || lowerName.contains("america") -> "🇺🇸"
            lowerName.contains("韩国") || lowerName.contains("kr") || lowerName.contains("korea") -> "🇰🇷"
            lowerName.contains("英国") || lowerName.contains("uk") || lowerName.contains("britain") -> "🇬🇧"
            lowerName.contains("德国") || lowerName.contains("de") || lowerName.contains("germany") -> "🇩🇪"
            lowerName.contains("法国") || lowerName.contains("fr") || lowerName.contains("france") -> "🇫🇷"
            lowerName.contains("加拿大") || lowerName.contains("ca") || lowerName.contains("canada") -> "🇨🇦"
            lowerName.contains("澳大利亚") || lowerName.contains("au") || lowerName.contains("australia") -> "🇦🇺"
            lowerName.contains("俄罗斯") || lowerName.contains("ru") || lowerName.contains("russia") -> "🇷🇺"
            lowerName.contains("印度") || lowerName.contains("in") || lowerName.contains("india") -> "🇮🇳"
            lowerName.contains("巴西") || lowerName.contains("br") || lowerName.contains("brazil") -> "🇧🇷"
            lowerName.contains("荷兰") || lowerName.contains("nl") || lowerName.contains("netherlands") -> "🇳🇱"
            lowerName.contains("土耳其") || lowerName.contains("tr") || lowerName.contains("turkey") -> "🇹🇷"
            lowerName.contains("阿根廷") || lowerName.contains("ar") || lowerName.contains("argentina") -> "🇦🇷"
            lowerName.contains("马来西亚") || lowerName.contains("my") || lowerName.contains("malaysia") -> "🇲🇾"
            lowerName.contains("泰国") || lowerName.contains("th") || lowerName.contains("thailand") -> "🇹🇭"
            lowerName.contains("越南") || lowerName.contains("vn") || lowerName.contains("vietnam") -> "🇻🇳"
            lowerName.contains("菲律宾") || lowerName.contains("ph") || lowerName.contains("philippines") -> "🇵🇭"
            lowerName.contains("印尼") || lowerName.contains("id") || lowerName.contains("indonesia") -> "🇮🇩"
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
    
    suspend fun setActiveNode(nodeId: String): Boolean {
        _activeNodeId.value = nodeId
        
        // 如果 VPN 正在运行，尝试通过 API 热切换节点
        if (com.kunk.singbox.service.SingBoxService.isRunning) {
            val node = _nodes.value.find { it.id == nodeId }
            if (node != null) {
                try {
                    val clashApi = singBoxCore.getClashApiClient()
                    
                    // 先获取当前选中的节点
                    val beforeSwitch = clashApi.getCurrentSelection("PROXY")
                    Log.d(TAG, "Before switch: current selection = $beforeSwitch, target = ${node.name}")
                    
                    val success = clashApi.selectProxy("PROXY", node.name)
                    
                    if (success) {
                        // 验证切换是否生效
                        val afterSwitch = clashApi.getCurrentSelection("PROXY")
                        Log.d(TAG, "After switch: current selection = $afterSwitch")
                        
                        if (afterSwitch == node.name) {
                            Log.d(TAG, "Hot switched to node: ${node.name} - VERIFIED")
                            return true
                        } else {
                            Log.e(TAG, "Switch verification failed! Expected: ${node.name}, Got: $afterSwitch")
                            return false
                        }
                    } else {
                        Log.e(TAG, "Failed to hot switch node: ${node.name}")
                        return false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during hot switch", e)
                    return false
                }
            }
        }
        return true
    }
    
    fun deleteProfile(profileId: String) {
        _profiles.update { list -> list.filter { it.id != profileId } }
        profileConfigs.remove(profileId)
        profileNodes.remove(profileId)
        
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
        return withContext(Dispatchers.IO) {
            try {
                // 找到节点对应的 Outbound 配置
                val node = _nodes.value.find { it.id == nodeId }
                if (node == null) {
                    Log.e(TAG, "Node not found: $nodeId")
                    return@withContext -1L
                }
                
                // 从配置中获取对应的 Outbound
                val config = profileConfigs[node.sourceProfileId]
                if (config == null) {
                    Log.e(TAG, "Config not found for profile: ${node.sourceProfileId}")
                    return@withContext -1L
                }
                
                val outbound = config.outbounds?.find { it.tag == node.name }
                if (outbound == null) {
                    Log.e(TAG, "Outbound not found: ${node.name}")
                    return@withContext -1L
                }
                
                // 使用 SingBoxCore 进行真正的延迟测试
                Log.d(TAG, "Testing latency for node: ${node.name} (${outbound.type})")
                val fixedOutbound = fixOutboundForRuntime(outbound)
                val latency = singBoxCore.testOutboundLatency(fixedOutbound)
                
                // 更新节点延迟
                _nodes.update { list ->
                    list.map {
                        if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else null) else it
                    }
                }
                
                // 同时更新内存中的 profileNodes
                profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                    if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else null) else it
                } ?: emptyList()
                
                Log.d(TAG, "Latency test result for ${node.name}: ${latency}ms")
                latency
            } catch (e: Exception) {
                Log.e(TAG, "Latency test error for $nodeId", e)
                -1L
            }
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
    }

    suspend fun testAllNodesLatency() = withContext(Dispatchers.IO) {
        val nodes = _nodes.value
        Log.d(TAG, "Starting latency test for ${nodes.size} nodes")

        // 构建需要测试的 outbounds 列表，使用 singBoxCore 批量测试，避免并发启动多个临时服务导致崩溃
        val outbounds = ArrayList<com.kunk.singbox.model.Outbound>()
        val tagToNodeId = HashMap<String, String>()
        val tagToProfileId = HashMap<String, String>()

        for (node in nodes) {
            val config = profileConfigs[node.sourceProfileId] ?: continue
            val outbound = config.outbounds?.find { it.tag == node.name } ?: continue
            outbounds.add(fixOutboundForRuntime(outbound))
            tagToNodeId[node.name] = node.id
            tagToProfileId[node.name] = node.sourceProfileId
        }

        // 顺序测试每个节点
        for (outbound in outbounds) {
            val tag = outbound.tag
            val nodeId = tagToNodeId[tag] ?: continue
            val profileId = tagToProfileId[tag] ?: continue

            try {
                // 测试单个节点延迟
                val latency = singBoxCore.testOutboundLatency(outbound)
                
                // 更新状态
                _nodes.update { list ->
                    list.map {
                        if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else null) else it
                    }
                }

                profileNodes[profileId] = profileNodes[profileId]?.map {
                    if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else null) else it
                } ?: emptyList()

                Log.d(TAG, "Latency test result for $tag: ${latency}ms")
                
                // 短暂延迟以避免UI刷新过快或系统负载过高
                kotlinx.coroutines.delay(50)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to test latency for $tag", e)
            }
        }

        Log.d(TAG, "Latency test completed for all nodes")
    }

    suspend fun updateAllProfiles() {
        val enabledProfiles = _profiles.value.filter { it.enabled && it.type == ProfileType.Subscription }
        enabledProfiles.forEach { profile ->
            updateProfile(profile.id)
        }
    }
    
    suspend fun updateProfile(profileId: String): Result<Unit> {
        val profile = _profiles.value.find { it.id == profileId }
            ?: return Result.failure(Exception("配置不存在"))
        
        if (profile.url.isNullOrBlank()) {
            return Result.failure(Exception("无订阅链接"))
        }
        
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Updating) else it
            }
        }
        
        return try {
            val result = importFromSubscriptionUpdate(profile)
            _profiles.update { list ->
                list.map {
                    if (it.id == profileId) it.copy(
                        updateStatus = UpdateStatus.Success,
                        lastUpdated = System.currentTimeMillis()
                    ) else it
                }
            }
            
            // 延迟后重置状态
            kotlinx.coroutines.delay(2000)
            _profiles.update { list ->
                list.map {
                    if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Idle) else it
                }
            }
            
            result
        } catch (e: Exception) {
            _profiles.update { list ->
                list.map {
                    if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Failed) else it
                }
            }
            kotlinx.coroutines.delay(2000)
            _profiles.update { list ->
                list.map {
                    if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Idle) else it
                }
            }
            Result.failure(e)
        }
    }
    
    private suspend fun importFromSubscriptionUpdate(profile: ProfileUi): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(profile.url!!)
                .header("User-Agent", "sing-box/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            
            val responseBody = response.body?.string() 
                ?: return@withContext Result.failure(Exception("空响应"))
            
            val config = parseSubscriptionResponse(responseBody)
                ?: return@withContext Result.failure(Exception("无法解析配置"))
            
            val nodes = extractNodesFromConfig(config, profile.id)
            
            // 更新存储
            val configFile = File(configDir, "${profile.id}.json")
            configFile.writeText(gson.toJson(config))
            
            profileConfigs[profile.id] = config
            profileNodes[profile.id] = nodes
            
            // 如果是当前活跃配置，更新节点列表
            if (_activeProfileId.value == profile.id) {
                _nodes.value = nodes
                updateNodeGroups(nodes)
            }
            
            saveProfiles()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 生成用于 VPN 服务的配置文件
     * @return 配置文件路径，null 表示失败
     */
    suspend fun generateConfigFile(): String? = withContext(Dispatchers.IO) {
        try {
            val activeId = _activeProfileId.value ?: return@withContext null
            val config = profileConfigs[activeId] ?: return@withContext null
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

        // Fix interval
        val interval = result.interval
        if (interval != null && !interval.contains(Regex("[a-zA-Z]"))) {
            result = result.copy(interval = "${interval}s")
        }

        // Fix flow
        val cleanedFlow = result.flow?.takeIf { it.isNotBlank() }
        if (cleanedFlow != result.flow) {
            result = result.copy(flow = cleanedFlow)
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
        Log.d(TAG, "Building ad-block routing rules with rule-set")
        
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
        val mirrorUrl = settings.ghProxyMirror.url
        return RuleSetConfig(
            tag = "geosite-category-ads-all",
            type = "remote",
            format = "binary",
            // 使用用户选择的镜像加速
            url = "${mirrorUrl}https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
            downloadDetour = "direct",
            updateInterval = "24h"
        )
    }
    
    /**
     * 构建自定义规则集配置
     */
    private fun buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig> {
        return settings.ruleSets.map { ruleSet ->
            RuleSetConfig(
                tag = ruleSet.tag,
                type = ruleSet.type.name.lowercase(),
                format = ruleSet.format,
                url = if (ruleSet.type == RuleSetType.REMOTE) ruleSet.url else null,
                // 本地路径暂未处理，通常需要复制到工作目录或通过 assets 读取
                // path = if (ruleSet.type == RuleSetType.LOCAL) ruleSet.path else null,
                downloadDetour = "direct", // 下载规则集走直连
                updateInterval = "24h"
            )
        }
    }

    /**
     * 构建自定义规则集路由规则
     */
    private fun buildCustomRuleSetRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        // 记录所有可用的 outbound tags，用于调试
        val availableTags = outbounds.map { it.tag }
        Log.d(TAG, "Available outbound tags for rule matching: $availableTags")
        
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
        
        Log.d(TAG, "Sorted rule sets order: ${sortedRuleSets.map { "${it.tag}(${it.outboundMode})" }}")
        
        sortedRuleSets.forEach { ruleSet ->
            Log.d(TAG, "Processing rule set: ${ruleSet.tag}, mode=${ruleSet.outboundMode}, value=${ruleSet.outboundValue}")
            
            val outboundTag = when (ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val nodeName = ruleSet.outboundValue
                    val found = outbounds.any { it.tag == nodeName }
                    Log.d(TAG, "NODE mode: nodeName='$nodeName', found=$found")
                    if (!nodeName.isNullOrEmpty() && found) {
                        nodeName
                    } else {
                        Log.w(TAG, "Node '$nodeName' not found in outbounds, falling back to $defaultProxyTag")
                        defaultProxyTag
                    }
                }
                RuleSetOutboundMode.PROFILE -> {
                     // 暂不支持直接指向 Profile，简化为默认代理
                     // 如果未来支持 Profile 作为 Outbound (如 Selector)，需在此扩展
                     defaultProxyTag
                }
                RuleSetOutboundMode.GROUP -> {
                    val groupName = ruleSet.outboundValue
                    // 假设已为分组创建了 Selector，或者直接查找属于该组的节点
                    // 目前简化处理：如果在 outbounds 中找到了同名 tag (如 Selector)，则使用，否则默认
                    if (!groupName.isNullOrEmpty() && outbounds.any { it.tag == groupName }) {
                         groupName
                    } else {
                         // TODO: 为节点组创建专用的 Selector Outbound
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
            
            Log.d(TAG, "Added rule set rule: ${ruleSet.tag} -> $outboundTag (inbounds: $inboundTags)")
        }

        return rules
    }

    /**
     * 构建应用分流路由规则
     */
    private fun buildAppRoutingRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()
        
        // 1. 处理应用规则（单个应用）
        settings.appRules.filter { it.enabled }.forEach { rule ->
            val outboundTag = when (rule.outbound) {
                OutboundTag.DIRECT -> "direct"
                OutboundTag.PROXY -> {
                    // 如果指定了特定节点，使用该节点；否则使用默认代理选择器
                    if (!rule.specificNodeId.isNullOrEmpty() && 
                        outbounds.any { it.tag == rule.specificNodeId }) {
                        rule.specificNodeId
                    } else {
                        defaultProxyTag
                    }
                }
                OutboundTag.BLOCK -> "block"
            }
            
            rules.add(RouteRule(
                packageName = listOf(rule.packageName),
                outbound = outboundTag
            ))
            
            Log.d(TAG, "Added app rule: ${rule.appName} (${rule.packageName}) -> $outboundTag")
        }
        
        // 2. 处理应用分组
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val outboundTag = when (group.outbound) {
                OutboundTag.DIRECT -> "direct"
                OutboundTag.PROXY -> {
                    if (!group.specificNodeId.isNullOrEmpty() && 
                        outbounds.any { it.tag == group.specificNodeId }) {
                        group.specificNodeId
                    } else {
                        defaultProxyTag
                    }
                }
                OutboundTag.BLOCK -> "block"
            }
            
            // 将分组中的所有应用包名添加到一条规则中
            val packageNames = group.apps.map { it.packageName }
            if (packageNames.isNotEmpty()) {
                rules.add(RouteRule(
                    packageName = packageNames,
                    outbound = outboundTag
                ))
                
                Log.d(TAG, "Added app group rule: ${group.name} (${packageNames.size} apps) -> $outboundTag")
            }
        }
        
        Log.d(TAG, "Generated ${rules.size} app routing rules")
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

        val singboxTempDir = File(context.cacheDir, "singbox_temp").also { it.mkdirs() }

        // 添加 Clash API 配置
        val experimental = ExperimentalConfig(
            clashApi = ClashApiConfig(
                externalController = "127.0.0.1:9090",
                secret = ""
            ),
            cacheFile = CacheFileConfig(
                enabled = false,
                path = File(singboxTempDir, "cache_run.db").absolutePath,
                storeFakeip = settings.fakeDnsEnabled
            )
        )
        
        // 添加入站配置
        val inbounds = mutableListOf<Inbound>()
        if (settings.tunEnabled) {
            inbounds.add(
                Inbound(
                    type = "tun",
                    tag = "tun-in",
                    interfaceName = settings.tunInterfaceName,
                    inet4Address = listOf("172.19.0.1/30"),
                    mtu = settings.tunMtu,
                    autoRoute = settings.autoRoute,
                    strictRoute = settings.strictRoute,
                    stack = settings.tunStack.name.lowercase(), // gvisor/system/mixed/lwip
                    sniff = true,
                    sniffOverrideDestination = true
                )
            )
        } else {
            // 如果禁用 TUN，则添加混合入站（HTTP+SOCKS），方便本地代理使用
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = "127.0.0.1",
                    listenPort = 2080,
                    sniff = true,
                    sniffOverrideDestination = true
                )
            )
        }
        
        // 添加 DNS 配置
        val dnsServers = mutableListOf<DnsServer>()
        val dnsRules = mutableListOf<DnsRule>()

        // 远程 DNS
        dnsServers.add(
            DnsServer(
                tag = "remote",
                address = settings.remoteDns
            )
        )

        // 本地 DNS
        dnsServers.add(
            DnsServer(
                tag = "local",
                address = settings.localDns,
                detour = "direct"
            )
        )

        // Fake DNS
        if (settings.fakeDnsEnabled) {
            dnsServers.add(
                DnsServer(
                    tag = "fakeip",
                    type = "fakeip",
                    inet4Range = settings.fakeIpRange
                )
            )
            // 规则：所有 A/AAAA 查询走 fakeip
            dnsRules.add(
                DnsRule(
                    queryType = listOf("A", "AAAA"),
                    server = "fakeip"
                )
            )
        }

        val dns = DnsConfig(
            servers = dnsServers,
            rules = dnsRules,
            strategy = when (settings.dnsStrategy) {
                DnsStrategy.PREFER_IPV4 -> "prefer_ipv4"
                DnsStrategy.PREFER_IPV6 -> "prefer_ipv6"
                DnsStrategy.ONLY_IPV4 -> "ipv4_only"
                DnsStrategy.ONLY_IPV6 -> "ipv6_only"
            },
            disableCache = !settings.dnsCacheEnabled
        )
        
        // 修复 outbounds
        val fixedOutbounds = baseConfig.outbounds?.map { outbound ->
            fixOutboundForRuntime(outbound)
        }?.toMutableList() ?: mutableListOf()
        
        // 确保必要的 outbounds 存在
        if (fixedOutbounds.none { it.tag == "direct" }) {
            fixedOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        if (fixedOutbounds.none { it.tag == "block" }) {
            fixedOutbounds.add(Outbound(type = "block", tag = "block"))
        }
        if (fixedOutbounds.none { it.tag == "dns-out" }) {
            fixedOutbounds.add(Outbound(type = "dns", tag = "dns-out"))
        }
        
        // 收集所有代理节点名称
        val proxyTags = fixedOutbounds.filter {
            it.type in listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria")
        }.map { it.tag }.toMutableList()

        // 创建一个主 Selector
        val selectorTag = "PROXY"
        val selectorOutbound = Outbound(
            type = "selector",
            tag = selectorTag,
            outbounds = proxyTags,
            default = activeNode?.name, // 设置默认选中项
            interruptExistConnections = true // 切换节点时断开现有连接，确保立即生效
        )
        
        // 将 Selector 添加到 outbounds 列表的最前面（或者合适的位置）
        fixedOutbounds.add(0, selectorOutbound)
        
        Log.d(TAG, "Created selector '$selectorTag' with ${proxyTags.size} nodes. Default: ${activeNode?.name}")
        
        // 构建应用分流规则
        val appRoutingRules = buildAppRoutingRules(settings, selectorTag, fixedOutbounds)
        
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

        // 构建自定义规则集配置和路由规则
        val customRuleSets = buildCustomRuleSets(settings)
        val customRuleSetRules = buildCustomRuleSetRules(settings, selectorTag, fixedOutbounds)
        
        // 添加路由配置（使用在线规则集，sing-box 1.12.0+）
        // 合并规则集时去重，以 customRuleSets 为准（用户配置优先）
        val adBlockTags = adBlockRuleSet.map { it.tag }.toSet()
        val filteredAdBlockRuleSets = adBlockRuleSet.filter { rs ->
            customRuleSets.none { it.tag == rs.tag }
        }
        val allRules = listOf(
            // DNS 流量走 dns-out
            RouteRule(protocol = listOf("dns"), outbound = "dns-out")
        ) + appRoutingRules + adBlockRules + customRuleSetRules
        
        // 记录所有生成的路由规则
        Log.d(TAG, "=== Generated Route Rules (${allRules.size} total) ===")
        allRules.forEachIndexed { index, rule ->
            val ruleDesc = buildString {
                rule.protocol?.let { append("protocol=$it ") }
                rule.ruleSet?.let { append("ruleSet=$it ") }
                rule.packageName?.let { append("pkg=$it ") }
                rule.domain?.let { append("domain=$it ") }
                rule.inbound?.let { append("inbound=$it ") }
                append("-> ${rule.outbound}")
            }
            Log.d(TAG, "  Rule[$index]: $ruleDesc")
        }
        Log.d(TAG, "=== Final outbound: $selectorTag ===")
        
        val route = RouteConfig(
            ruleSet = filteredAdBlockRuleSets + customRuleSets,
            rules = allRules,
            finalOutbound = selectorTag, // 路由指向 Selector
            autoDetectInterface = true
        )
        
        return baseConfig.copy(
            log = log,
            experimental = experimental,
            inbounds = inbounds,
            dns = dns,
            route = route,
            outbounds = fixedOutbounds
        )
    }
    
    /**
     * 获取当前活跃配置的原始 JSON
     */
    fun getActiveConfig(): SingBoxConfig? {
        return _activeProfileId.value?.let { profileConfigs[it] }
    }
    
    /**
     * 获取指定配置的原始 JSON
     */
    fun getConfig(profileId: String): SingBoxConfig? {
        return profileConfigs[profileId]
    }
    
    /**
     * 根据节点ID获取节点的Outbound配置
     */
    fun getOutboundByNodeId(nodeId: String): Outbound? {
        val node = _nodes.value.find { it.id == nodeId } ?: return null
        val config = profileConfigs[node.sourceProfileId] ?: return null
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
        val config = profileConfigs[profileId] ?: return

        // 过滤掉要删除的节点
        val newOutbounds = config.outbounds?.filter { it.tag != node.name }
        val newConfig = config.copy(outbounds = newOutbounds)

        // 更新内存中的配置
        profileConfigs[profileId] = newConfig
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes

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
        val config = profileConfigs[profileId] ?: return

        // 更新对应节点的 tag
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) it.copy(tag = newName) else it
        }
        val newConfig = config.copy(outbounds = newOutbounds)

        // 更新内存中的配置
        profileConfigs[profileId] = newConfig
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes

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
        val config = profileConfigs[profileId] ?: return

        // 更新对应节点
        // 注意：这里假设 newOutbound.tag 已经包含了可能的新名称
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) newOutbound else it
        }
        val newConfig = config.copy(outbounds = newOutbounds)

        // 更新内存中的配置
        profileConfigs[profileId] = newConfig
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes

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
        val node = _nodes.value.find { it.id == nodeId }
        if (node == null) {
             Log.e(TAG, "exportNode: Node not found in UI list: $nodeId")
             return null
        }
        
        val config = profileConfigs[node.sourceProfileId]
        if (config == null) {
             Log.e(TAG, "exportNode: Config not found for profile: ${node.sourceProfileId}")
             return null
        }
        
        val outbound = config.outbounds?.find { it.tag == node.name }
        if (outbound == null) {
             Log.e(TAG, "exportNode: Outbound not found in config with tag: ${node.name}")
             return null
        }
        
        Log.d(TAG, "exportNode: Found outbound ${outbound.tag} of type ${outbound.type}")

        return when (outbound.type) {
            "vless" -> generateVLessLink(outbound)
            "vmess" -> generateVMessLink(outbound)
            "shadowsocks" -> generateShadowsocksLink(outbound)
            "trojan" -> generateTrojanLink(outbound)
            "hysteria2" -> {
                val link = generateHysteria2Link(outbound)
                Log.d(TAG, "exportNode: Generated hy2 link: $link")
                link
            }
            "hysteria" -> generateHysteriaLink(outbound)
            else -> {
                Log.e(TAG, "exportNode: Unsupported type ${outbound.type}")
                null
            }
        }
    }

    private fun generateVLessLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: return ""
        val server = outbound.server ?: return ""
        val port = outbound.serverPort ?: 443
        val params = mutableListOf<String>()
        
        params.add("type=${outbound.transport?.type ?: "tcp"}")
        params.add("encryption=none")
        
        outbound.flow?.let { params.add("flow=$it") }
        
        if (outbound.tls?.enabled == true) {
            if (outbound.tls.reality?.enabled == true) {
                 params.add("security=reality")
                 outbound.tls.reality.publicKey?.let { params.add("pbk=$it") }
                 outbound.tls.reality.shortId?.let { params.add("sid=$it") }
                 outbound.tls.serverName?.let { params.add("sni=$it") }
            } else {
                 params.add("security=tls")
                 outbound.tls.serverName?.let { params.add("sni=$it") }
            }
            outbound.tls.utls?.fingerprint?.let { params.add("fp=$it") }
            if (outbound.tls.insecure == true) {
                params.add("allowInsecure=1")
            }
            outbound.tls.alpn?.let { 
                if (it.isNotEmpty()) params.add("alpn=${it.joinToString(",")}") 
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
                host?.let { params.add("host=$it") }
                
                var path = outbound.transport.path ?: "/"
                // Handle early data (ed)
                outbound.transport.maxEarlyData?.let { ed ->
                    if (ed != 0) { // Only add if not 0, though usually it's 2048 or something
                        val separator = if (path.contains("?")) "&" else "?"
                        path = "$path${separator}ed=$ed"
                    }
                }
                
                params.add("path=${java.net.URLEncoder.encode(path, "UTF-8")}") 
            }
            "grpc" -> {
                outbound.transport.serviceName?.let { 
                    params.add("serviceName=${java.net.URLEncoder.encode(it, "UTF-8")}") 
                }
                params.add("mode=gun")
            }
            "http", "h2" -> {
                 outbound.transport.path?.let { params.add("path=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                 outbound.transport.host?.firstOrNull()?.let { params.add("host=$it") }
            }
        }

        val query = params.joinToString("&")
        val name = java.net.URLEncoder.encode(outbound.tag, "UTF-8").replace("+", "%20")
        
        return "vless://$uuid@$server:$port?$query#$name"
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
        val userInfo = "${outbound.method}:${outbound.password}"
        val encodedUserInfo = Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)
        val serverPart = "${outbound.server}:${outbound.serverPort}"
        val name = java.net.URLEncoder.encode(outbound.tag, "UTF-8").replace("+", "%20")
        return "ss://$encodedUserInfo@$serverPart#$name"
    }
    
    private fun generateTrojanLink(outbound: Outbound): String {
         val password = java.net.URLEncoder.encode(outbound.password ?: "", "UTF-8")
         val server = outbound.server ?: ""
         val port = outbound.serverPort ?: 443
         val name = java.net.URLEncoder.encode(outbound.tag, "UTF-8").replace("+", "%20")
         
         val params = mutableListOf<String>()
         if (outbound.tls?.enabled == true) {
             params.add("security=tls")
             outbound.tls.serverName?.let { params.add("sni=$it") }
             if (outbound.tls.insecure == true) params.add("allowInsecure=1")
         }
         
         val query = params.joinToString("&")
         return "trojan://$password@$server:$port?$query#$name"
    }

    private fun generateHysteria2Link(outbound: Outbound): String {
         val password = java.net.URLEncoder.encode(outbound.password ?: "", "UTF-8")
         val server = outbound.server ?: ""
         val port = outbound.serverPort ?: 443
         val name = java.net.URLEncoder.encode(outbound.tag, "UTF-8").replace("+", "%20")
         
         val params = mutableListOf<String>()
         
         outbound.tls?.serverName?.let { params.add("sni=$it") }
         if (outbound.tls?.insecure == true) params.add("insecure=1")
         
         outbound.obfs?.let { obfs ->
             obfs.type?.let { params.add("obfs=$it") }
             obfs.password?.let { params.add("obfs-password=$it") }
         }
         
         val query = params.joinToString("&")
         return "hysteria2://$password@$server:$port?$query#$name"
    }

    private fun generateHysteriaLink(outbound: Outbound): String {
         val server = outbound.server ?: ""
         val port = outbound.serverPort ?: 443
         val name = java.net.URLEncoder.encode(outbound.tag, "UTF-8").replace("+", "%20")
         
         val params = mutableListOf<String>()
         outbound.authStr?.let { params.add("auth=$it") }
         outbound.upMbps?.let { params.add("upmbps=$it") }
         outbound.downMbps?.let { params.add("downmbps=$it") }
         
         outbound.tls?.serverName?.let { params.add("sni=$it") }
         if (outbound.tls?.insecure == true) params.add("insecure=1")
         outbound.tls?.alpn?.let { 
             if (it.isNotEmpty()) params.add("alpn=${it.joinToString(",")}") 
         }
         
         outbound.obfs?.let { obfs ->
             obfs.type?.let { params.add("obfs=$it") }
         }

         val query = params.joinToString("&")
         return "hysteria://$server:$port?$query#$name"
    }
}
data class SavedProfilesData(
    val profiles: List<ProfileUi>,
    val activeProfileId: String?
)

/**
 * VMess 链接配置格式
 */
data class VMessLinkConfig(
    val v: String? = null,
    val ps: String? = null,      // 名称
    val add: String? = null,     // 服务器地址
    val port: String? = null,    // 端口
    val id: String? = null,      // UUID
    val aid: String? = null,     // alterId
    val scy: String? = null,     // 加密方式
    val net: String? = null,     // 传输协议
    val type: String? = null,    // 伪装类型
    val host: String? = null,    // 伪装域名
    val path: String? = null,    // 路径
    val tls: String? = null,     // TLS
    val sni: String? = null,     // SNI
    val alpn: String? = null,
    val fp: String? = null,      // fingerprint
    val packetEncoding: String? = null // packet encoding
)
