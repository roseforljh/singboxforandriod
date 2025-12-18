package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import java.util.UUID

@Keep
enum class RuleType(val displayName: String) {
    @SerializedName("DOMAIN") DOMAIN("域名"),
    @SerializedName("DOMAIN_SUFFIX") DOMAIN_SUFFIX("域名后缀"),
    @SerializedName("DOMAIN_KEYWORD") DOMAIN_KEYWORD("域名关键字"),
    @SerializedName("IP_CIDR") IP_CIDR("IP CIDR"),
    @SerializedName("GEOIP") GEOIP("GeoIP"),
    @SerializedName("GEOSITE") GEOSITE("GeoSite"),
    @SerializedName("PORT") PORT("端口"),
    @SerializedName("PROCESS_NAME") PROCESS_NAME("进程名")
}

@Keep
enum class OutboundTag(val displayName: String) {
    @SerializedName("DIRECT") DIRECT("直连"),
    @SerializedName("PROXY") PROXY("代理"),
    @SerializedName("BLOCK") BLOCK("拦截")
}

@Keep
data class CustomRule(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: RuleType,
    @SerializedName("value") val value: String, // e.g., "google.com", "cn"
    @SerializedName("outbound") val outbound: OutboundTag,
    @SerializedName("enabled") val enabled: Boolean = true
)

@Keep
enum class RuleSetType(val displayName: String) {
    @SerializedName("REMOTE") REMOTE("远程"),
    @SerializedName("LOCAL") LOCAL("本地")
}

@Keep
enum class RuleSetOutboundMode(val displayName: String) {
    @SerializedName("DIRECT") DIRECT("直连"),
    @SerializedName("BLOCK") BLOCK("拦截"),
    @SerializedName("PROXY") PROXY("代理"),
    @SerializedName("NODE") NODE("单节点"),
    @SerializedName("PROFILE") PROFILE("配置"),
    @SerializedName("GROUP") GROUP("节点组")
}

@Keep
data class RuleSet(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("tag") val tag: String, // Unique identifier in config
    @SerializedName("type") val type: RuleSetType,
    @SerializedName("format") val format: String = "binary", // "binary" or "source"
    @SerializedName("url") val url: String = "", // For remote
    @SerializedName("path") val path: String = "", // For local
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("outboundMode") val outboundMode: RuleSetOutboundMode? = RuleSetOutboundMode.DIRECT,
    @SerializedName("outboundValue") val outboundValue: String? = null, // ID for Node/Profile, or Name for Group
    @SerializedName("inbounds") val inbounds: List<String>? = emptyList() // List of inbound tags
)

@Keep
data class AppRule(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("packageName") val packageName: String, // e.g., "com.google.android.youtube"
    @SerializedName("appName") val appName: String, // Display name, e.g., "YouTube"
    @SerializedName("outboundMode") val outboundMode: RuleSetOutboundMode? = RuleSetOutboundMode.DIRECT,
    @SerializedName("outboundValue") val outboundValue: String? = null, // ID for Node/Profile, or Name for Group
    @SerializedName("enabled") val enabled: Boolean = true
)

@Keep
data class AppGroup(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String, // Group name, e.g., "社交应用", "游戏"
    @SerializedName("apps") val apps: List<AppInfo> = emptyList(), // Apps in this group
    @SerializedName("outboundMode") val outboundMode: RuleSetOutboundMode? = RuleSetOutboundMode.DIRECT,
    @SerializedName("outboundValue") val outboundValue: String? = null, // ID for Node/Profile, or Name for Group
    @SerializedName("enabled") val enabled: Boolean = true
)

@Keep
data class AppInfo(
    @SerializedName("packageName") val packageName: String,
    @SerializedName("appName") val appName: String
)

@Keep
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false
)