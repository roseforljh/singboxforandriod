package com.kunk.singbox.model

import com.google.gson.annotations.SerializedName

/**
 * 所有应用设置的数据模型
 */
data class AppSettings(
    // 通用设置
    @SerializedName("autoConnect") val autoConnect: Boolean = false,
    @SerializedName("autoReconnect") val autoReconnect: Boolean = true,
    @SerializedName("excludeFromRecent") val excludeFromRecent: Boolean = false,
    
    // TUN/VPN 设置
    @SerializedName("tunEnabled") val tunEnabled: Boolean = true,
    @SerializedName("tunStack") val tunStack: TunStack = TunStack.GVISOR,
    @SerializedName("tunMtu") val tunMtu: Int = 1280,
    @SerializedName("tunInterfaceName") val tunInterfaceName: String = "tun0",
    @SerializedName("autoRoute") val autoRoute: Boolean = true,
    @SerializedName("strictRoute") val strictRoute: Boolean = true,
    @SerializedName("vpnRouteMode") val vpnRouteMode: VpnRouteMode = VpnRouteMode.GLOBAL,
    @SerializedName("vpnRouteIncludeCidrs") val vpnRouteIncludeCidrs: String = "",
    @SerializedName("vpnAppMode") val vpnAppMode: VpnAppMode = VpnAppMode.ALL,
    @SerializedName("vpnAllowlist") val vpnAllowlist: String = "",
    @SerializedName("vpnBlocklist") val vpnBlocklist: String = "",
    
    // 代理端口设置
    @SerializedName("proxyPort") val proxyPort: Int = 2080,
    @SerializedName("allowLan") val allowLan: Boolean = false,
    @SerializedName("appendHttpProxy") val appendHttpProxy: Boolean = false,
    
    // DNS 设置
    @SerializedName("localDns") val localDns: String = "223.5.5.5",
    @SerializedName("remoteDns") val remoteDns: String = "1.1.1.1",
    @SerializedName("fakeDnsEnabled") val fakeDnsEnabled: Boolean = true,
    @SerializedName("fakeIpRange") val fakeIpRange: String = "198.18.0.0/15",
    @SerializedName("dnsStrategy") val dnsStrategy: DnsStrategy = DnsStrategy.PREFER_IPV4,
    @SerializedName("remoteDnsStrategy") val remoteDnsStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("directDnsStrategy") val directDnsStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("serverAddressStrategy") val serverAddressStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("dnsCacheEnabled") val dnsCacheEnabled: Boolean = true,
    
    // 路由设置
    @SerializedName("routingMode") val routingMode: RoutingMode = RoutingMode.RULE,
    @SerializedName("defaultRule") val defaultRule: DefaultRule = DefaultRule.DIRECT,
    @SerializedName("blockAds") val blockAds: Boolean = true,
    @SerializedName("bypassLan") val bypassLan: Boolean = true,
    @SerializedName("blockQuic") val blockQuic: Boolean = true,
    
    // 延迟测试设置
    @SerializedName("latencyTestMethod") val latencyTestMethod: LatencyTestMethod = LatencyTestMethod.REAL_RTT,
    @SerializedName("latencyTestUrl") val latencyTestUrl: String = "https://cp.cloudflare.com/generate_204",
    
    // 镜像设置
    @SerializedName("ghProxyMirror") val ghProxyMirror: GhProxyMirror = GhProxyMirror.GHFAST_TOP,
    
    // 高级路由
    @SerializedName("customRules") val customRules: List<CustomRule> = emptyList(),
    @SerializedName("ruleSets") val ruleSets: List<RuleSet> = emptyList(),
    @SerializedName("appRules") val appRules: List<AppRule> = emptyList(),
    @SerializedName("appGroups") val appGroups: List<AppGroup> = emptyList()
)

enum class LatencyTestMethod(val displayName: String) {
    @SerializedName("TCP") TCP("TCP 延迟"),
    @SerializedName("REAL_RTT") REAL_RTT("真实延迟 (RTT)"),
    @SerializedName("HANDSHAKE") HANDSHAKE("HTTP 握手延迟");
    
    companion object {
        fun fromDisplayName(name: String): LatencyTestMethod {
            return entries.find { it.displayName == name } ?: REAL_RTT
        }
    }
}

enum class TunStack(val displayName: String) {
    @SerializedName("SYSTEM") SYSTEM("System"),
    @SerializedName("GVISOR") GVISOR("gVisor"),
    @SerializedName("MIXED") MIXED("Mixed");
    
    companion object {
        fun fromDisplayName(name: String): TunStack {
            return entries.find { it.displayName == name } ?: GVISOR
        }
    }
}

enum class VpnRouteMode(val displayName: String) {
    @SerializedName("GLOBAL") GLOBAL("全局接管"),
    @SerializedName("CUSTOM") CUSTOM("自定义接管");

    companion object {
        fun fromDisplayName(name: String): VpnRouteMode {
            return entries.find { it.displayName == name } ?: GLOBAL
        }
    }
}

enum class VpnAppMode(val displayName: String) {
    @SerializedName("ALL") ALL("全部应用"),
    @SerializedName("ALLOWLIST") ALLOWLIST("仅允许列表"),
    @SerializedName("BLOCKLIST") BLOCKLIST("排除列表");

    companion object {
        fun fromDisplayName(name: String): VpnAppMode {
            return entries.find { it.displayName == name } ?: ALL
        }
    }
}

enum class DnsStrategy(val displayName: String) {
    @SerializedName("AUTO") AUTO("Auto"),
    @SerializedName("PREFER_IPV4") PREFER_IPV4("优先 IPv4"),
    @SerializedName("PREFER_IPV6") PREFER_IPV6("优先 IPv6"),
    @SerializedName("ONLY_IPV4") ONLY_IPV4("仅 IPv4"),
    @SerializedName("ONLY_IPV6") ONLY_IPV6("仅 IPv6");
    
    companion object {
        fun fromDisplayName(name: String): DnsStrategy {
            return entries.find { it.displayName == name } ?: AUTO
        }
    }
}

enum class RoutingMode(val displayName: String) {
    @SerializedName("RULE") RULE("规则模式"),
    @SerializedName("GLOBAL_PROXY") GLOBAL_PROXY("全局代理"),
    @SerializedName("GLOBAL_DIRECT") GLOBAL_DIRECT("全局直连");
    
    companion object {
        fun fromDisplayName(name: String): RoutingMode {
            return entries.find { it.displayName == name } ?: RULE
        }
    }
}

enum class DefaultRule(val displayName: String) {
    @SerializedName("DIRECT") DIRECT("直连"),
    @SerializedName("PROXY") PROXY("代理"),
    @SerializedName("BLOCK") BLOCK("拦截");
    
    companion object {
        fun fromDisplayName(name: String): DefaultRule {
            return entries.find { it.displayName == name } ?: DIRECT
        }
    }
}

enum class GhProxyMirror(val url: String, val displayName: String) {
    @SerializedName("GHFAST_TOP") GHFAST_TOP("https://ghfast.top/", "ghfast.top"),
    @SerializedName("GH_PROXY_COM") GH_PROXY_COM("https://gh-proxy.com/", "gh-proxy.com"),
    @SerializedName("GHPROXY_LINK") GHPROXY_LINK("https://ghproxy.link/", "ghproxy.link");
    
    companion object {
        fun fromUrl(url: String): GhProxyMirror {
            return entries.find { url.startsWith(it.url) } ?: GHFAST_TOP
        }
        
        fun fromDisplayName(name: String): GhProxyMirror {
            return entries.find { it.displayName == name } ?: GHFAST_TOP
        }
    }
}

