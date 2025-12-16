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
        val port = (map["port"] as? Int) ?: 443
        val uuid = map["uuid"] as? String
        val password = map["password"] as? String
        
        // TLS Config
        val tlsEnabled = map["tls"] as? Boolean == true
        val skipCertVerify = map["skip-cert-verify"] as? Boolean == true
        val serverName = map["servername"] as? String ?: map["sni"] as? String
        val alpn = map["alpn"] as? List<String>
        val fingerprint = map["client-fingerprint"] as? String
        
        val tlsConfig = if (tlsEnabled || type == "hysteria2" || type == "trojan") {
            TlsConfig(
                enabled = true,
                serverName = serverName,
                insecure = skipCertVerify,
                alpn = alpn,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        } else null

        // Transport Config
        val network = map["network"] as? String
        val wsOpts = map["ws-opts"] as? Map<String, Any>
        val grpcOpts = map["grpc-opts"] as? Map<String, Any>
        val h2Opts = map["h2-opts"] as? Map<String, Any>
        val httpOpts = map["http-opts"] as? Map<String, Any>

        val transportConfig = when (network) {
            "ws" -> TransportConfig(
                type = "ws",
                path = wsOpts?.get("path") as? String,
                headers = (wsOpts?.get("headers") as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
            )
            "grpc" -> TransportConfig(
                type = "grpc",
                serviceName = grpcOpts?.get("grpc-service-name") as? String
            )
            "h2" -> TransportConfig(
                type = "http",
                path = h2Opts?.get("path") as? String,
                host = (h2Opts?.get("host") as? List<*>)?.mapNotNull { it?.toString() }?.firstOrNull() // TransportConfig host is String?
            )
             "http" -> TransportConfig(
                type = "http",
                path = httpOpts?.get("path") as? String,
                host = ((httpOpts?.get("headers") as? Map<*, *>)?.get("Host") as? List<*>)?.mapNotNull { it?.toString() }?.firstOrNull() // TransportConfig host is String?
            )
            else -> null
        }

        return when (type) {
            "ss" -> Outbound(
                type = "shadowsocks",
                tag = name,
                server = server,
                serverPort = port,
                method = map["cipher"] as? String,
                password = password
            )
            "vmess" -> Outbound(
                type = "vmess",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                alterId = (map["alterId"] as? Int) ?: 0,
                security = map["cipher"] as? String ?: "auto",
                tls = tlsConfig,
                transport = transportConfig
            )
            "vless" -> Outbound(
                type = "vless",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                flow = map["flow"] as? String,
                tls = tlsConfig,
                transport = transportConfig
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
             "hysteria" -> Outbound(
                type = "hysteria",
                tag = name,
                server = server,
                serverPort = port,
                authStr = map["auth_str"] as? String,
                upMbps = (map["up"] as? String)?.replace(" Mbps", "")?.toIntOrNull(),
                downMbps = (map["down"] as? String)?.replace(" Mbps", "")?.toIntOrNull(),
                tls = tlsConfig,
                obfs = map["obfs"]?.let { ObfsConfig(type = "salamander", password = map["obfs-password"] as? String) }
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