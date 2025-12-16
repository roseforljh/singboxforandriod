package com.kunk.singbox.model

/**
 * 所有应用设置的数据模型
 */
data class AppSettings(
    // 通用设置
    val autoConnect: Boolean = false,
    val autoReconnect: Boolean = true,
    
    // TUN/VPN 设置
    val tunEnabled: Boolean = true,
    val tunStack: TunStack = TunStack.GVISOR,
    val tunMtu: Int = 9000,
    val tunInterfaceName: String = "tun0",
    val autoRoute: Boolean = true,
    val strictRoute: Boolean = true,
    
    // DNS 设置
    val localDns: String = "8.8.8.8",
    val remoteDns: String = "1.1.1.1",
    val fakeDnsEnabled: Boolean = true,
    val fakeIpRange: String = "198.18.0.0/15",
    val dnsStrategy: DnsStrategy = DnsStrategy.PREFER_IPV4,
    val dnsCacheEnabled: Boolean = true,
    
    // 路由设置
    val routingMode: RoutingMode = RoutingMode.RULE,
    val defaultRule: DefaultRule = DefaultRule.DIRECT,
    val blockAds: Boolean = true,
    val bypassLan: Boolean = true,
    
    // 高级路由
    val customRules: List<CustomRule> = emptyList(),
    val ruleSets: List<RuleSet> = emptyList(),
    val appRules: List<AppRule> = emptyList(),
    val appGroups: List<AppGroup> = emptyList()
)

enum class TunStack(val displayName: String) {
    SYSTEM("System"),
    GVISOR("gVisor"),
    MIXED("Mixed");
    
    companion object {
        fun fromDisplayName(name: String): TunStack {
            return entries.find { it.displayName == name } ?: GVISOR
        }
    }
}

enum class DnsStrategy(val displayName: String) {
    PREFER_IPV4("优先 IPv4"),
    PREFER_IPV6("优先 IPv6"),
    ONLY_IPV4("仅 IPv4"),
    ONLY_IPV6("仅 IPv6");
    
    companion object {
        fun fromDisplayName(name: String): DnsStrategy {
            return entries.find { it.displayName == name } ?: PREFER_IPV4
        }
    }
}

enum class RoutingMode(val displayName: String) {
    RULE("规则模式"),
    GLOBAL_PROXY("全局代理"),
    GLOBAL_DIRECT("全局直连");
    
    companion object {
        fun fromDisplayName(name: String): RoutingMode {
            return entries.find { it.displayName == name } ?: RULE
        }
    }
}

enum class DefaultRule(val displayName: String) {
    DIRECT("直连"),
    PROXY("代理"),
    BLOCK("拦截");
    
    companion object {
        fun fromDisplayName(name: String): DefaultRule {
            return entries.find { it.displayName == name } ?: DIRECT
        }
    }
}
