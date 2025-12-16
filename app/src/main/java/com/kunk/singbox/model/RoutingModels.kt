package com.kunk.singbox.model

import java.util.UUID

enum class RuleType(val displayName: String) {
    DOMAIN("域名"),
    DOMAIN_SUFFIX("域名后缀"),
    DOMAIN_KEYWORD("域名关键字"),
    IP_CIDR("IP CIDR"),
    GEOIP("GeoIP"),
    GEOSITE("GeoSite"),
    PORT("端口"),
    PROCESS_NAME("进程名")
}

enum class OutboundTag(val displayName: String) {
    DIRECT("直连"),
    PROXY("代理"),
    BLOCK("拦截")
}

data class CustomRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: RuleType,
    val value: String, // e.g., "google.com", "cn"
    val outbound: OutboundTag,
    val enabled: Boolean = true
)

enum class RuleSetType(val displayName: String) {
    REMOTE("远程"),
    LOCAL("本地")
}

data class RuleSet(
    val id: String = UUID.randomUUID().toString(),
    val tag: String, // Unique identifier in config
    val type: RuleSetType,
    val format: String = "binary", // "binary" or "source"
    val url: String = "", // For remote
    val path: String = "", // For local
    val enabled: Boolean = true
)

/**
 * App-based routing rule - allows per-app proxy settings
 */
data class AppRule(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String, // e.g., "com.google.android.youtube"
    val appName: String, // Display name, e.g., "YouTube"
    val outbound: OutboundTag, // DIRECT, PROXY, or BLOCK
    val specificNodeId: String? = null, // If set, use this specific node instead of default proxy
    val enabled: Boolean = true
)

/**
 * App group - groups multiple apps to use the same routing rule
 */
data class AppGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String, // Group name, e.g., "社交应用", "游戏"
    val apps: List<AppInfo> = emptyList(), // Apps in this group
    val outbound: OutboundTag, // DIRECT, PROXY, or BLOCK
    val specificNodeId: String? = null, // If set, use this specific node
    val enabled: Boolean = true
)

/**
 * Basic app info for group membership
 */
data class AppInfo(
    val packageName: String,
    val appName: String
)

/**
 * Represents an installed app on the device
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false
)