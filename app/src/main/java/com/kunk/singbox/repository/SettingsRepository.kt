package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.model.GhProxyMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    private val gson = Gson()

    private fun parseVpnAppMode(raw: String?): VpnAppMode {
        if (raw.isNullOrBlank()) return VpnAppMode.ALL
        VpnAppMode.entries.firstOrNull { it.name == raw }?.let { return it }
        return VpnAppMode.fromDisplayName(raw)
    }

    private fun migrateVpnAppModeIfNeeded(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (VpnAppMode.entries.any { it.name == raw }) return null
        val migrated = VpnAppMode.entries.firstOrNull { it.displayName == raw } ?: return null
        return migrated.name
    }

    private object PreferencesKeys {
        // 通用设置
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val EXCLUDE_FROM_RECENT = booleanPreferencesKey("exclude_from_recent")
        
        // TUN/VPN 设置
        val TUN_ENABLED = booleanPreferencesKey("tun_enabled")
        val TUN_STACK = stringPreferencesKey("tun_stack")
        val TUN_MTU = intPreferencesKey("tun_mtu")
        val TUN_INTERFACE_NAME = stringPreferencesKey("tun_interface_name")
        val AUTO_ROUTE = booleanPreferencesKey("auto_route")
        val STRICT_ROUTE = booleanPreferencesKey("strict_route")
        val VPN_ROUTE_MODE = stringPreferencesKey("vpn_route_mode")
        val VPN_ROUTE_INCLUDE_CIDRS = stringPreferencesKey("vpn_route_include_cidrs")
        val VPN_APP_MODE = stringPreferencesKey("vpn_app_mode")
        val VPN_ALLOWLIST = stringPreferencesKey("vpn_allowlist")
        val VPN_BLOCKLIST = stringPreferencesKey("vpn_blocklist")
        
        // DNS 设置
        val LOCAL_DNS = stringPreferencesKey("local_dns")
        val REMOTE_DNS = stringPreferencesKey("remote_dns")
        val FAKE_DNS_ENABLED = booleanPreferencesKey("fake_dns_enabled")
        val FAKE_IP_RANGE = stringPreferencesKey("fake_ip_range")
        val DNS_STRATEGY = stringPreferencesKey("dns_strategy")
        val REMOTE_DNS_STRATEGY = stringPreferencesKey("remote_dns_strategy")
        val DIRECT_DNS_STRATEGY = stringPreferencesKey("direct_dns_strategy")
        val SERVER_ADDRESS_STRATEGY = stringPreferencesKey("server_address_strategy")
        val DNS_CACHE_ENABLED = booleanPreferencesKey("dns_cache_enabled")
        
        // 路由设置
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val DEFAULT_RULE = stringPreferencesKey("default_rule")
        val BLOCK_ADS = booleanPreferencesKey("block_ads")
        val BLOCK_QUIC = booleanPreferencesKey("block_quic")
        val LATENCY_TEST_METHOD = stringPreferencesKey("latency_test_method")
        val LATENCY_TEST_URL = stringPreferencesKey("latency_test_url")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val GH_PROXY_MIRROR = stringPreferencesKey("gh_proxy_mirror")
        
        // 代理设置
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val ALLOW_LAN = booleanPreferencesKey("allow_lan")
        val APPEND_HTTP_PROXY = booleanPreferencesKey("append_http_proxy")
        
        // 高级路由 (JSON)
        val CUSTOM_RULES = stringPreferencesKey("custom_rules")
        val RULE_SETS = stringPreferencesKey("rule_sets")
        val APP_RULES = stringPreferencesKey("app_rules")
        val APP_GROUPS = stringPreferencesKey("app_groups")
        val DNS_MIGRATED = booleanPreferencesKey("dns_migrated")
    }
    
    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val selectedMirror = GhProxyMirror.fromDisplayName(preferences[PreferencesKeys.GH_PROXY_MIRROR] ?: "ghfast.top")
        val currentMirrorUrl = selectedMirror.url

        val customRulesJson = preferences[PreferencesKeys.CUSTOM_RULES]
        val customRules = if (customRulesJson != null) {
            try {
                gson.fromJson<List<CustomRule>>(customRulesJson, object : TypeToken<List<CustomRule>>() {}.type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

        } else {
            emptyList()
        }

        val ruleSetsJson = preferences[PreferencesKeys.RULE_SETS]
        val ruleSets = if (ruleSetsJson != null) {
            try {
                // Log only if it's a significant read or in verbose mode
                // Log.v(TAG, "Loading rule sets from JSON (length=${ruleSetsJson.length})")
                val list = gson.fromJson<List<RuleSet>>(ruleSetsJson, object : TypeToken<List<RuleSet>>() {}.type) ?: emptyList()
                
                // 自动修复并去重
                val migratedList = list.map { ruleSet ->
                    var updatedUrl = ruleSet.url
                    var updatedTag = ruleSet.tag
                    
                    // 1. 强制重命名旧的广告规则集标识
                    if (updatedTag.equals("geosite-ads", ignoreCase = true)) {
                        updatedTag = "geosite-category-ads-all"
                    }
                    
                    // 2. 修复错误的广告规则集 URL
                    if (updatedUrl.contains("geosite-ads.srs")) {
                        updatedUrl = updatedUrl.replace("geosite-ads.srs", "geosite-category-ads-all.srs")
                    }
                    
                    // 3. 统一使用镜像加速
                    if (updatedUrl.contains("raw.githubusercontent.com") && !updatedUrl.contains(currentMirrorUrl)) {
                        // 移除旧镜像
                        val rawUrl = if (updatedUrl.contains("https://")) {
                            "https://raw.githubusercontent.com/" + updatedUrl.substringAfter("raw.githubusercontent.com/")
                        } else {
                            updatedUrl
                        }
                        updatedUrl = currentMirrorUrl + rawUrl
                    }
                    
                    // 修复之前注入的失效或不稳定的镜像
                    val oldMirrors = listOf(
                        "https://ghp.ci/", 
                        "https://mirror.ghproxy.com/", 
                        "https://ghproxy.com/", 
                        "https://ghproxy.net/",
                        "https://ghfast.top/",
                        "https://gh-proxy.com/",
                        "https://ghproxy.link/"
                    )
                    
                    for (mirror in oldMirrors) {
                        if (updatedUrl.startsWith(mirror) && mirror != currentMirrorUrl) {
                            updatedUrl = updatedUrl.replace(mirror, currentMirrorUrl)
                        }
                    }

                    if (updatedUrl != ruleSet.url || updatedTag != ruleSet.tag) {
                        ruleSet.copy(tag = updatedTag, url = updatedUrl)
                    } else {
                        ruleSet
                    }
                }
                
                // 去重：如果存在相同 tag 的规则集，保留最后一个（通常是较新的或已迁移的）
                migratedList.distinctBy { it.tag }
            } catch (e: Exception) {
                Log.e("SettingsRepository", "Failed to parse rule sets JSON", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        val appRulesJson = preferences[PreferencesKeys.APP_RULES]
        val appRules = if (appRulesJson != null) {
            try {
                gson.fromJson<List<AppRule>>(appRulesJson, object : TypeToken<List<AppRule>>() {}.type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val appGroupsJson = preferences[PreferencesKeys.APP_GROUPS]
        val appGroups = if (appGroupsJson != null) {
            try {
                gson.fromJson<List<AppGroup>>(appGroupsJson, object : TypeToken<List<AppGroup>>() {}.type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        AppSettings(
            // 通用设置
            autoConnect = preferences[PreferencesKeys.AUTO_CONNECT] ?: false,
            autoReconnect = preferences[PreferencesKeys.AUTO_RECONNECT] ?: true,
            excludeFromRecent = preferences[PreferencesKeys.EXCLUDE_FROM_RECENT] ?: false,
            
            // TUN/VPN 设置
            tunEnabled = preferences[PreferencesKeys.TUN_ENABLED] ?: true,
            tunStack = TunStack.fromDisplayName(preferences[PreferencesKeys.TUN_STACK] ?: "gVisor"),
            tunMtu = preferences[PreferencesKeys.TUN_MTU] ?: 1280,
            tunInterfaceName = preferences[PreferencesKeys.TUN_INTERFACE_NAME] ?: "tun0",
            autoRoute = preferences[PreferencesKeys.AUTO_ROUTE] ?: true,
            strictRoute = preferences[PreferencesKeys.STRICT_ROUTE] ?: true,
            vpnRouteMode = VpnRouteMode.fromDisplayName(preferences[PreferencesKeys.VPN_ROUTE_MODE] ?: VpnRouteMode.GLOBAL.displayName),
            vpnRouteIncludeCidrs = preferences[PreferencesKeys.VPN_ROUTE_INCLUDE_CIDRS] ?: "",
            vpnAppMode = parseVpnAppMode(preferences[PreferencesKeys.VPN_APP_MODE]),
            vpnAllowlist = preferences[PreferencesKeys.VPN_ALLOWLIST] ?: "",
            vpnBlocklist = preferences[PreferencesKeys.VPN_BLOCKLIST] ?: "",
            
            // DNS 设置
            localDns = preferences[PreferencesKeys.LOCAL_DNS] ?: "https://dns.alidns.com/dns-query",
            remoteDns = preferences[PreferencesKeys.REMOTE_DNS] ?: "https://dns.google/dns-query",
            fakeDnsEnabled = preferences[PreferencesKeys.FAKE_DNS_ENABLED] ?: true,
            fakeIpRange = preferences[PreferencesKeys.FAKE_IP_RANGE] ?: "198.18.0.0/15",
            dnsStrategy = DnsStrategy.fromDisplayName(preferences[PreferencesKeys.DNS_STRATEGY] ?: "优先 IPv4"),
            remoteDnsStrategy = DnsStrategy.fromDisplayName(preferences[PreferencesKeys.REMOTE_DNS_STRATEGY] ?: "Auto"),
            directDnsStrategy = DnsStrategy.fromDisplayName(preferences[PreferencesKeys.DIRECT_DNS_STRATEGY] ?: "Auto"),
            serverAddressStrategy = DnsStrategy.fromDisplayName(preferences[PreferencesKeys.SERVER_ADDRESS_STRATEGY] ?: "Auto"),
            dnsCacheEnabled = preferences[PreferencesKeys.DNS_CACHE_ENABLED] ?: true,
            
            // 路由设置
            routingMode = RoutingMode.fromDisplayName(preferences[PreferencesKeys.ROUTING_MODE] ?: "规则模式"),
            defaultRule = DefaultRule.fromDisplayName(preferences[PreferencesKeys.DEFAULT_RULE] ?: "直连"),
            blockAds = preferences[PreferencesKeys.BLOCK_ADS] ?: true,
            blockQuic = preferences[PreferencesKeys.BLOCK_QUIC] ?: true,
            latencyTestMethod = LatencyTestMethod.valueOf(preferences[PreferencesKeys.LATENCY_TEST_METHOD] ?: LatencyTestMethod.REAL_RTT.name),
            latencyTestUrl = preferences[PreferencesKeys.LATENCY_TEST_URL] ?: "https://cp.cloudflare.com/generate_204",
            bypassLan = preferences[PreferencesKeys.BYPASS_LAN] ?: true,
            
            // 镜像设置
            ghProxyMirror = selectedMirror,
            
            // 代理设置
            proxyPort = preferences[PreferencesKeys.PROXY_PORT] ?: 20808,
            allowLan = preferences[PreferencesKeys.ALLOW_LAN] ?: false,
            appendHttpProxy = preferences[PreferencesKeys.APPEND_HTTP_PROXY] ?: false,
            
            // 高级路由
            customRules = customRules,
            ruleSets = ruleSets,
            appRules = appRules,
            appGroups = appGroups
        )
    }.flowOn(Dispatchers.Default)
    
    // 通用设置
    suspend fun setAutoConnect(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_CONNECT] = value }
    }
    
    suspend fun setAutoReconnect(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_RECONNECT] = value }
    }
    
    suspend fun setExcludeFromRecent(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.EXCLUDE_FROM_RECENT] = value }
    }
    
    // TUN/VPN 设置
    suspend fun setTunEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.TUN_ENABLED] = value }
        notifyRestartRequired()
    }
    
    suspend fun setTunStack(value: TunStack) {
        context.dataStore.edit { it[PreferencesKeys.TUN_STACK] = value.displayName }
        notifyRestartRequired()
    }
    
    suspend fun setTunMtu(value: Int) {
        context.dataStore.edit { it[PreferencesKeys.TUN_MTU] = value }
        notifyRestartRequired()
    }
    
    suspend fun setTunInterfaceName(value: String) {
        context.dataStore.edit { it[PreferencesKeys.TUN_INTERFACE_NAME] = value }
        notifyRestartRequired()
    }
    
    suspend fun setAutoRoute(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_ROUTE] = value }
        notifyRestartRequired()
    }
    
    suspend fun setStrictRoute(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.STRICT_ROUTE] = value }
        notifyRestartRequired()
    }

    suspend fun setVpnRouteMode(value: VpnRouteMode) {
        context.dataStore.edit { it[PreferencesKeys.VPN_ROUTE_MODE] = value.displayName }
        notifyRestartRequired()
    }

    suspend fun setVpnRouteIncludeCidrs(value: String) {
        context.dataStore.edit { it[PreferencesKeys.VPN_ROUTE_INCLUDE_CIDRS] = value }
        notifyRestartRequired()
    }

    suspend fun setVpnAppMode(value: VpnAppMode) {
        context.dataStore.edit { it[PreferencesKeys.VPN_APP_MODE] = value.name }
        notifyRestartRequired()
    }

    suspend fun setVpnAllowlist(value: String) {
        context.dataStore.edit { it[PreferencesKeys.VPN_ALLOWLIST] = value }
        notifyRestartRequired()
    }

    suspend fun setVpnBlocklist(value: String) {
        context.dataStore.edit { it[PreferencesKeys.VPN_BLOCKLIST] = value }
        notifyRestartRequired()
    }
    
    // DNS 设置
    suspend fun setLocalDns(value: String) {
        context.dataStore.edit { it[PreferencesKeys.LOCAL_DNS] = value }
        notifyRestartRequired()
    }
    
    suspend fun setRemoteDns(value: String) {
        context.dataStore.edit { it[PreferencesKeys.REMOTE_DNS] = value }
        notifyRestartRequired()
    }
    
    suspend fun setFakeDnsEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.FAKE_DNS_ENABLED] = value }
        notifyRestartRequired()
    }
    
    suspend fun setFakeIpRange(value: String) {
        context.dataStore.edit { it[PreferencesKeys.FAKE_IP_RANGE] = value }
        notifyRestartRequired()
    }
    
    suspend fun setDnsStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.DNS_STRATEGY] = value.displayName }
        notifyRestartRequired()
    }

    suspend fun setRemoteDnsStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.REMOTE_DNS_STRATEGY] = value.displayName }
        notifyRestartRequired()
    }

    suspend fun setDirectDnsStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.DIRECT_DNS_STRATEGY] = value.displayName }
        notifyRestartRequired()
    }

    suspend fun setServerAddressStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.SERVER_ADDRESS_STRATEGY] = value.displayName }
        notifyRestartRequired()
    }
    
    suspend fun setDnsCacheEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DNS_CACHE_ENABLED] = value }
        notifyRestartRequired()
    }
    
    // 路由设置
    suspend fun setRoutingMode(value: RoutingMode) {
        context.dataStore.edit { it[PreferencesKeys.ROUTING_MODE] = value.displayName }
        notifyRestartRequired()
    }
    
    suspend fun setDefaultRule(value: DefaultRule) {
        context.dataStore.edit { it[PreferencesKeys.DEFAULT_RULE] = value.displayName }
        notifyRestartRequired()
    }
    
    suspend fun setBlockAds(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BLOCK_ADS] = value }
        notifyRestartRequired()
    }
    
    suspend fun setBlockQuic(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BLOCK_QUIC] = value }
        notifyRestartRequired()
    }
    
    suspend fun setLatencyTestMethod(value: LatencyTestMethod) {
        context.dataStore.edit { it[PreferencesKeys.LATENCY_TEST_METHOD] = value.name }
    }
    
    suspend fun setLatencyTestUrl(value: String) {
        context.dataStore.edit { it[PreferencesKeys.LATENCY_TEST_URL] = value }
    }
    
    suspend fun setBypassLan(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BYPASS_LAN] = value }
        notifyRestartRequired()
    }
    
    suspend fun setGhProxyMirror(value: GhProxyMirror) {
        context.dataStore.edit { it[PreferencesKeys.GH_PROXY_MIRROR] = value.displayName }
        notifyRestartRequired()
    }
    
    suspend fun setProxyPort(value: Int) {
        context.dataStore.edit { it[PreferencesKeys.PROXY_PORT] = value }
        notifyRestartRequired()
    }

    suspend fun setAllowLan(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ALLOW_LAN] = value }
        notifyRestartRequired()
    }

    suspend fun setAppendHttpProxy(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.APPEND_HTTP_PROXY] = value }
        notifyRestartRequired()
    }
    
    suspend fun setCustomRules(value: List<CustomRule>) {
        context.dataStore.edit { it[PreferencesKeys.CUSTOM_RULES] = gson.toJson(value) }
        notifyRestartRequired()
    }

    suspend fun setRuleSets(value: List<RuleSet>) {
        context.dataStore.edit { it[PreferencesKeys.RULE_SETS] = gson.toJson(value) }
        notifyRestartRequired()
    }

    suspend fun getRuleSets(): List<RuleSet> {
        return settings.first().ruleSets
    }

    suspend fun setAppRules(value: List<AppRule>) {
        context.dataStore.edit { it[PreferencesKeys.APP_RULES] = gson.toJson(value) }
        notifyRestartRequired()
    }

    suspend fun setAppGroups(value: List<AppGroup>) {
        context.dataStore.edit { it[PreferencesKeys.APP_GROUPS] = gson.toJson(value) }
        notifyRestartRequired()
    }
    
    suspend fun checkAndMigrateRuleSets() {
        try {
            // Also migrate legacy vpnAppMode persisted as displayName to stable enum.name
            val preferences = context.dataStore.data.first()
            val rawVpnAppMode = preferences[PreferencesKeys.VPN_APP_MODE]
            val migratedVpnAppMode = migrateVpnAppModeIfNeeded(rawVpnAppMode)
            if (migratedVpnAppMode != null) {
                context.dataStore.edit { it[PreferencesKeys.VPN_APP_MODE] = migratedVpnAppMode }
            }

            val currentSettings = settings.first()
            val isDnsMigrated = preferences[PreferencesKeys.DNS_MIGRATED] ?: false
            
            // 自动迁移: 优化 TUN MTU (1500 -> 1280)
            if (currentSettings.tunMtu == 1500) {
                Log.i("SettingsRepository", "Migrating TUN MTU from 1500 to 1280")
                setTunMtu(1280)
            }
            
            if (!isDnsMigrated) {
                // 自动迁移: 优化本地 DNS (8.8.8.8/223.5.5.5 -> AliDNS DoH)
                if (currentSettings.localDns == "8.8.8.8" || currentSettings.localDns == "223.5.5.5") {
                    Log.i("SettingsRepository", "Migrating Local DNS to AliDNS DoH")
                    setLocalDns("https://dns.alidns.com/dns-query")
                }
                
                // 自动迁移: 优化远程 DNS (1.1.1.1 -> Google DoH)
                if (currentSettings.remoteDns == "1.1.1.1") {
                    Log.i("SettingsRepository", "Migrating Remote DNS to Google DoH")
                    setRemoteDns("https://dns.google/dns-query")
                }
                
                // 标记已迁移，防止后续用户手动改回 IP 后再次被覆盖
                context.dataStore.edit { it[PreferencesKeys.DNS_MIGRATED] = true }
            }

            val originalRuleSets = currentSettings.ruleSets
            val currentMirrorUrl = currentSettings.ghProxyMirror.url
            val migratedRuleSets = originalRuleSets.map { ruleSet ->
                var updatedUrl = ruleSet.url
                var updatedTag = ruleSet.tag
                
                if (updatedTag.equals("geosite-ads", ignoreCase = true)) {
                    updatedTag = "geosite-category-ads-all"
                }
                
                if (updatedUrl.contains("geosite-ads.srs")) {
                    updatedUrl = updatedUrl.replace("geosite-ads.srs", "geosite-category-ads-all.srs")
                }
                
                if (updatedUrl.contains("raw.githubusercontent.com") && !updatedUrl.contains(currentMirrorUrl)) {
                    val rawUrl = if (updatedUrl.contains("https://")) {
                        "https://raw.githubusercontent.com/" + updatedUrl.substringAfter("raw.githubusercontent.com/")
                    } else {
                        updatedUrl
                    }
                    updatedUrl = currentMirrorUrl + rawUrl
                }
                
                val oldMirrors = listOf(
                    "https://ghp.ci/", 
                    "https://mirror.ghproxy.com/", 
                    "https://ghproxy.com/", 
                    "https://ghproxy.net/",
                    "https://ghfast.top/",
                    "https://gh-proxy.com/",
                    "https://ghproxy.link/"
                )
                
                for (mirror in oldMirrors) {
                    if (updatedUrl.startsWith(mirror) && mirror != currentMirrorUrl) {
                        updatedUrl = updatedUrl.replace(mirror, currentMirrorUrl)
                    }
                }

                if (updatedUrl != ruleSet.url || updatedTag != ruleSet.tag) {
                    ruleSet.copy(tag = updatedTag, url = updatedUrl)
                } else {
                    ruleSet
                }
            }.distinctBy { it.tag }

            if (migratedRuleSets != originalRuleSets) {
                Log.i("SettingsRepository", "Force saving migrated rule sets to DataStore")
                setRuleSets(migratedRuleSets)
            }
        } catch (e: Exception) {
            Log.e("SettingsRepository", "Error during force migration", e)
        }
    }

    private fun notifyRestartRequired() {
        _restartRequiredEvents.tryEmit(Unit)
    }

    companion object {
        private val _restartRequiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val restartRequiredEvents: SharedFlow<Unit> = _restartRequiredEvents.asSharedFlow()

        @Volatile
        private var INSTANCE: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
