package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class SingBoxConfig(
    @SerializedName("log") val log: LogConfig? = null,
    @SerializedName("dns") val dns: DnsConfig? = null,
    @SerializedName("inbounds") val inbounds: List<Inbound>? = null,
    @SerializedName("outbounds") val outbounds: List<Outbound>? = null,
    @SerializedName("route") val route: RouteConfig? = null,
    @SerializedName("experimental") val experimental: ExperimentalConfig? = null
)

@Keep
data class LogConfig(
    @SerializedName("level") val level: String? = null,
    @SerializedName("timestamp") val timestamp: Boolean? = null,
    @SerializedName("output") val output: String? = null
)

@Keep
data class DnsConfig(
    @SerializedName("servers") val servers: List<DnsServer>? = null,
    @SerializedName("rules") val rules: List<DnsRule>? = null,
    @SerializedName("final") val finalServer: String? = null,
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("disable_cache") val disableCache: Boolean? = null,
    @SerializedName("disable_expire") val disableExpire: Boolean? = null,
    @SerializedName("independent_cache") val independentCache: Boolean? = null
)

@Keep
data class DnsServer(
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("server") val server: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("address_resolver") val addressResolver: String? = null,
    @SerializedName("detour") val detour: String? = null,
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("inet4_range") val inet4Range: String? = null,
    @SerializedName("inet6_range") val inet6Range: String? = null
)

@Keep
data class DnsRule(
    @SerializedName("domain") val domain: List<String>? = null,
    @SerializedName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerializedName("geosite") val geosite: List<String>? = null,
    @SerializedName("rule_set") val ruleSet: List<String>? = null,
    @SerializedName("query_type") val queryType: List<String>? = null,
    @SerializedName("server") val server: String? = null,
    @SerializedName("outbound") val outbound: String? = null
)

@Keep
data class Inbound(
    @SerializedName("type") val type: String? = null,
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("listen") val listen: String? = null,
    @SerializedName("listen_port") val listenPort: Int? = null,
    @SerializedName("interface_name") val interfaceName: String? = null,
    @SerializedName("inet4_address") val inet4Address: List<String>? = null,
    @SerializedName("inet6_address") val inet6Address: List<String>? = null,
    @SerializedName("mtu") val mtu: Int? = null,
    @SerializedName("auto_route") val autoRoute: Boolean? = null,
    @SerializedName("strict_route") val strictRoute: Boolean? = null,
    @SerializedName("stack") val stack: String? = null,
    @SerializedName("sniff") val sniff: Boolean? = null,
    @SerializedName("sniff_override_destination") val sniffOverrideDestination: Boolean? = null,
    @SerializedName("sniff_timeout") val sniffTimeout: String? = null,
    @SerializedName("tcp_fast_open") val tcpFastOpen: Boolean? = null
)

@Keep
data class Outbound(
    @SerializedName("type") val type: String = "",
    @SerializedName("tag") val tag: String = "",
    
    @SerializedName("server") val server: String? = null,
    @SerializedName("server_port") val serverPort: Int? = null,
    @SerializedName("tcp_fast_open") val tcpFastOpen: Boolean? = null,
    
    @SerializedName("outbounds") val outbounds: List<String>? = null,
    @SerializedName("default") val default: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("interval") val interval: String? = null,
    @SerializedName("tolerance") val tolerance: Int? = null,
    @SerializedName("interrupt_exist_connections") val interruptExistConnections: Boolean? = null,
    
    @SerializedName("method") val method: String? = null,
    @SerializedName("password") val password: String? = null,
    
    @SerializedName("uuid") val uuid: String? = null,
    @SerializedName("security") val security: String? = null,
    @SerializedName("alter_id") val alterId: Int? = null,
    @SerializedName("flow") val flow: String? = null,
    @SerializedName("packet_encoding") val packetEncoding: String? = null,
    
    @SerializedName("up_mbps") val upMbps: Int? = null,
    @SerializedName("down_mbps") val downMbps: Int? = null,
    @SerializedName("obfs") val obfs: ObfsConfig? = null,
    @SerializedName("auth_str") val authStr: String? = null,
    
    @SerializedName("idle_session_check_interval") val idleSessionCheckInterval: String? = null,
    @SerializedName("idle_session_timeout") val idleSessionTimeout: String? = null,
    @SerializedName("min_idle_session") val minIdleSession: Int? = null,
    
    @SerializedName("tls") val tls: TlsConfig? = null,
    
    @SerializedName("transport") val transport: TransportConfig? = null,
    
    @SerializedName("multiplex") val multiplex: MultiplexConfig? = null,
    
    // TUIC 特有字段
    @SerializedName("congestion_control") val congestionControl: String? = null,
    @SerializedName("udp_relay_mode") val udpRelayMode: String? = null,
    @SerializedName("zero_rtt_handshake") val zeroRttHandshake: Boolean? = null,
    @SerializedName("heartbeat") val heartbeat: String? = null
)

@Keep
data class ObfsConfig(
    @SerializedName("type") val type: String? = null,
    @SerializedName("password") val password: String? = null
)

@Keep
data class TlsConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("server_name") val serverName: String? = null,
    @SerializedName("insecure") val insecure: Boolean? = null,
    @SerializedName("alpn") val alpn: List<String>? = null,
    @SerializedName("utls") val utls: UtlsConfig? = null,
    @SerializedName("reality") val reality: RealityConfig? = null,
    @SerializedName("ech") val ech: EchConfig? = null
)

@Keep
data class EchConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("pq_signature_schemes_enabled") val pqSignatureSchemesEnabled: Boolean? = null,
    @SerializedName("dynamic_record_sizing_disabled") val dynamicRecordSizingDisabled: Boolean? = null,
    @SerializedName("config") val config: List<String>? = null
)

@Keep
data class UtlsConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("fingerprint") val fingerprint: String? = null
)

@Keep
data class RealityConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("public_key") val publicKey: String? = null,
    @SerializedName("short_id") val shortId: String? = null
)

@Keep
data class TransportConfig(
    @SerializedName("type") val type: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("headers") val headers: Map<String, String>? = null,
    @SerializedName("service_name") val serviceName: String? = null,
    @SerializedName("host") val host: List<String>? = null,
    @SerializedName("early_data_header_name") val earlyDataHeaderName: String? = null,
    @SerializedName("max_early_data") val maxEarlyData: Int? = null
)

@Keep
data class MultiplexConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("protocol") val protocol: String? = null,
    @SerializedName("max_connections") val maxConnections: Int? = null,
    @SerializedName("min_streams") val minStreams: Int? = null,
    @SerializedName("max_streams") val maxStreams: Int? = null,
    @SerializedName("padding") val padding: Boolean? = null
)

@Keep
data class RouteConfig(
    @SerializedName("rules") val rules: List<RouteRule>? = null,
    @SerializedName("rule_set") val ruleSet: List<RuleSetConfig>? = null,
    @SerializedName("final") val finalOutbound: String? = null,
    @SerializedName("auto_detect_interface") val autoDetectInterface: Boolean? = null,
    @SerializedName("default_interface") val defaultInterface: String? = null
)

@Keep
data class RouteRule(
    @SerializedName("protocol") val protocol: List<String>? = null,
    @SerializedName("domain") val domain: List<String>? = null,
    @SerializedName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerializedName("domain_keyword") val domainKeyword: List<String>? = null,
    @SerializedName("geosite") val geosite: List<String>? = null,
    @SerializedName("geoip") val geoip: List<String>? = null,
    @SerializedName("ip_cidr") val ipCidr: List<String>? = null,
    @SerializedName("port") val port: List<Int>? = null,
    @SerializedName("port_range") val portRange: List<String>? = null,
    @SerializedName("rule_set") val ruleSet: List<String>? = null,
    @SerializedName("inbound") val inbound: List<String>? = null,
    @SerializedName("package_name") val packageName: List<String>? = null,
    @SerializedName("process_name") val processName: List<String>? = null,
    @SerializedName("user_id") val userId: List<Int>? = null,
    @SerializedName("outbound") val outbound: String? = null
)

@Keep
data class RuleSetConfig(
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("format") val format: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("download_detour") val downloadDetour: String? = null,
    @SerializedName("update_interval") val updateInterval: String? = null
)

@Keep
data class ExperimentalConfig(
    @SerializedName("cache_file") val cacheFile: CacheFileConfig? = null
)

@Keep
data class CacheFileConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("store_fakeip") val storeFakeip: Boolean? = null
)
