package com.kunk.singbox.utils

import com.kunk.singbox.model.*
import org.yaml.snakeyaml.Yaml
import java.util.UUID

object ClashConfigParser {

    fun parse(yamlContent: String): SingBoxConfig? {
        return try {
            val yaml = Yaml()
            val data = yaml.load<Map<String, Any>>(yamlContent)
            
            val outbounds = mutableListOf<Outbound>()
            
            // 1. Parse Proxies
            val proxies = data["proxies"] as? List<Map<String, Any>>
            proxies?.forEach { proxyMap ->
                val outbound = parseProxy(proxyMap)
                if (outbound != null) {
                    outbounds.add(outbound)
                }
            }
            
            // 2. Parse Proxy Groups
            val proxyGroups = data["proxy-groups"] as? List<Map<String, Any>>
            proxyGroups?.forEach { groupMap ->
                val outbound = parseProxyGroup(groupMap)
                if (outbound != null) {
                    outbounds.add(outbound)
                }
            }
            
            // 3. Add default outbounds if not present
            outbounds.add(Outbound(type = "direct", tag = "direct"))
            outbounds.add(Outbound(type = "block", tag = "block"))
            outbounds.add(Outbound(type = "dns", tag = "dns-out"))

            SingBoxConfig(
                outbounds = outbounds
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseProxy(map: Map<String, Any>): Outbound? {
        val type = map["type"] as? String ?: return null
        val name = map["name"] as? String ?: "Unknown"
        val server = map["server"] as? String ?: ""
        val port = (map["port"] as? Number)?.toInt() ?: 443
        val uuid = map["uuid"] as? String
        val password = map["password"] as? String
        
        // TLS Config - 检测多种方式
        val tlsEnabled = map["tls"] as? Boolean == true
        val skipCertVerify = map["skip-cert-verify"] as? Boolean == true
        val serverName = map["servername"] as? String ?: map["sni"] as? String
        val alpn = (map["alpn"] as? List<*>)?.mapNotNull { it?.toString() }
        val fingerprint = map["client-fingerprint"] as? String
        
        // Packet Encoding
        val packetEncoding = map["packet-encoding"] as? String
        
        // Reality 配置 (VLESS 特有)
        val realityOpts = map["reality-opts"] as? Map<String, Any>
        val hasReality = realityOpts != null
        val realityPublicKey = realityOpts?.get("public-key") as? String
        val realityShortId = realityOpts?.get("short-id") as? String
        
        // Flow 字段 (VLESS XTLS)
        val flow = map["flow"] as? String
        
        // 对于 VLESS，如果有 reality-opts 或 tls=true，都需要启用 TLS
        // 对于 Trojan、Hysteria2、TUIC、AnyTLS，默认启用 TLS
        val needsTls = tlsEnabled || hasReality || type == "trojan" || type == "hysteria2" || type == "hysteria" || type == "tuic" || type == "anytls"
        
        // 如果是 WS 且开启了 TLS，但没有指定 ALPN，默认强制使用 http/1.1
        // 这是为了避免服务端尝试协商 h2 导致 WS 握手失败
        val finalAlpn = if (needsTls && map["network"] as? String == "ws" && (alpn == null || alpn.isEmpty())) {
            listOf("http/1.1")
        } else {
            alpn
        }
        
        val tlsConfig = if (needsTls) {
            TlsConfig(
                enabled = true,
                serverName = serverName ?: server,
                insecure = skipCertVerify,
                alpn = finalAlpn,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) },
                reality = if (hasReality) RealityConfig(
                    enabled = true,
                    publicKey = realityPublicKey,
                    shortId = realityShortId
                ) else null
            )
        } else null

        // Transport Config
        val network = map["network"] as? String
        val wsOpts = map["ws-opts"] as? Map<String, Any>
        val grpcOpts = map["grpc-opts"] as? Map<String, Any>
        val h2Opts = map["h2-opts"] as? Map<String, Any>
        val httpOpts = map["http-opts"] as? Map<String, Any>
        
        // 从 ws-opts 获取 headers 中的 Host
        val wsHeaders = wsOpts?.get("headers") as? Map<*, *>
        val wsPath = wsOpts?.get("path") as? String
        val wsEarlyDataHeaderName = wsOpts?.get("early-data-header-name") as? String
        val wsMaxEarlyData = (wsOpts?.get("max-early-data") as? Number)?.toInt()
        val wsEarlyDataFromPath = wsPath?.let { path ->
            Regex("""(?:\\?|&)ed=(\\d+)""")
                .find(path)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }
        val finalWsMaxEarlyData = wsMaxEarlyData ?: wsEarlyDataFromPath
        val finalWsEarlyDataHeaderName = wsEarlyDataHeaderName ?: if (finalWsMaxEarlyData != null) {
            "Sec-WebSocket-Protocol"
        } else {
            null
        }
        
        // 查找 Host (忽略大小写)
        val wsHostKey = wsHeaders?.keys?.find { it.toString().equals("host", ignoreCase = true) }
        val wsHost = wsHostKey?.let { wsHeaders[it]?.toString() }

        val transportConfig = when (network) {
            "ws" -> {
                // WebSocket: host 放入 headers
                val baseHeaders = wsHeaders?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
                val finalHeaders = if (wsHost != null) {
                    // 移除旧的 host key (可能是小写)，添加规范的 Host
                    baseHeaders.filterKeys { !it.equals("host", ignoreCase = true) } + ("Host" to wsHost)
                } else {
                    // 如果没有 Host header，尝试使用 SNI
                    if (!serverName.isNullOrBlank()) {
                        baseHeaders + ("Host" to serverName)
                    } else {
                        baseHeaders
                    }
                }
                
                TransportConfig(
                    type = "ws",
                    path = wsPath ?: "/",
                    headers = if (finalHeaders.isNotEmpty()) finalHeaders else null,
                    earlyDataHeaderName = finalWsEarlyDataHeaderName,
                    maxEarlyData = finalWsMaxEarlyData
                )
            }
            "grpc" -> TransportConfig(
                type = "grpc",
                serviceName = grpcOpts?.get("grpc-service-name") as? String
            )
            "h2" -> TransportConfig(
                type = "http",
                path = h2Opts?.get("path") as? String,
                host = (h2Opts?.get("host") as? List<*>)?.mapNotNull { it?.toString() }
            )
            "http" -> {
                val httpHost = (httpOpts?.get("headers") as? Map<*, *>)?.get("Host") as? List<*>
                TransportConfig(
                    type = "http",
                    path = (httpOpts?.get("path") as? List<*>)?.firstOrNull()?.toString(),
                    host = httpHost?.mapNotNull { it?.toString() }
                )
            }
            else -> null
        }

        return when (type) {
            "vmess" -> Outbound(
                type = "vmess",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                alterId = (map["alterId"] as? Int) ?: 0,
                security = map["cipher"] as? String ?: "auto",
                tls = tlsConfig,
                transport = transportConfig,
                packetEncoding = packetEncoding
            )
            "vless" -> Outbound(
                type = "vless",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                flow = flow,
                tls = tlsConfig,
                transport = transportConfig,
                packetEncoding = packetEncoding
            )
            "trojan" -> Outbound(
                type = "trojan",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = tlsConfig,
                transport = transportConfig
            )
            "anytls" -> Outbound(
                type = "anytls",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = tlsConfig,
                idleSessionCheckInterval = map["idle_session_check_interval"] as? String,
                idleSessionTimeout = map["idle_session_timeout"] as? String,
                minIdleSession = (map["min_idle_session"] as? Number)?.toInt()
            )
            "hysteria2" -> Outbound(
                type = "hysteria2",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = tlsConfig,
                obfs = map["obfs"]?.let { obfs ->
                    ObfsConfig(
                        type = "salamander",
                        password = map["obfs-password"] as? String
                    )
                }
            )
            "tuic" -> Outbound(
                type = "tuic",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                password = password,
                congestionControl = map["congestion-control"] as? String ?: "bbr",
                udpRelayMode = map["udp-relay-mode"] as? String ?: "native",
                zeroRttHandshake = map["reduce-rtt"] as? Boolean ?: false,
                tls = tlsConfig
            )
            "ss", "shadowsocks" -> Outbound(
                type = "shadowsocks",
                tag = name,
                server = server,
                serverPort = port,
                method = map["cipher"] as? String,
                password = password
            )
            else -> null
        }
    }

    private fun parseProxyGroup(map: Map<String, Any>): Outbound? {
        val name = map["name"] as? String ?: return null
        val type = map["type"] as? String ?: "select"
        val proxies = map["proxies"] as? List<String> ?: emptyList()
        
        val singBoxType = when (type) {
            "select" -> "selector"
            "url-test" -> "urltest"
            "fallback" -> "urltest" // Sing-box doesn't strictly distinguish fallback, urltest covers it
            "load-balance" -> "urltest" // Approximate mapping
            else -> "selector"
        }

        val intervalValue = map["interval"]?.toString()
        val intervalWithUnit = if (intervalValue != null && !intervalValue.contains(Regex("[a-zA-Z]"))) {
            "${intervalValue}s"  // 添加秒单位
        } else {
            intervalValue
        }
        
        return Outbound(
            type = singBoxType,
            tag = name,
            outbounds = proxies,
            url = map["url"] as? String,
            interval = intervalWithUnit,
            tolerance = (map["tolerance"] as? Int) ?: (map["tolerance"] as? String)?.toIntOrNull()
        )
    }
}