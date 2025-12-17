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
import com.kunk.singbox.model.GhProxyMirror
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    private val gson = Gson()

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
        
        // DNS 设置
        val LOCAL_DNS = stringPreferencesKey("local_dns")
        val REMOTE_DNS = stringPreferencesKey("remote_dns")
        val FAKE_DNS_ENABLED = booleanPreferencesKey("fake_dns_enabled")
        val FAKE_IP_RANGE = stringPreferencesKey("fake_ip_range")
        val DNS_STRATEGY = stringPreferencesKey("dns_strategy")
        val DNS_CACHE_ENABLED = booleanPreferencesKey("dns_cache_enabled")
        
        // 路由设置
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val DEFAULT_RULE = stringPreferencesKey("default_rule")
        val BLOCK_ADS = booleanPreferencesKey("block_ads")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val GH_PROXY_MIRROR = stringPreferencesKey("gh_proxy_mirror")
        
        // 高级路由 (JSON)
        val CUSTOM_RULES = stringPreferencesKey("custom_rules")
        val RULE_SETS = stringPreferencesKey("rule_sets")
        val APP_RULES = stringPreferencesKey("app_rules")
        val APP_GROUPS = stringPreferencesKey("app_groups")
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
                Log.d("SettingsRepository", "Loading rule sets from JSON: $ruleSetsJson")
                val list = gson.fromJson<List<RuleSet>>(ruleSetsJson, object : TypeToken<List<RuleSet>>() {}.type) ?: emptyList()
                Log.d("SettingsRepository", "Parsed ${list.size} rule sets")
                
                // 自动修复并去重
                val migratedList = list.map { ruleSet ->
                    var updatedUrl = ruleSet.url
                    var updatedTag = ruleSet.tag
                    
                    // 1. 强制重命名旧的广告规则集标识
                    if (updatedTag.equals("geosite-ads", ignoreCase = true)) {
                        updatedTag = "geosite-category-ads-all"
                        Log.d("SettingsRepository", "Migrating tag: ${ruleSet.tag} -> $updatedTag")
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
                        Log.d("SettingsRepository", "RuleSet migrated: ${ruleSet.tag} -> $updatedTag, URL: ${ruleSet.url} -> $updatedUrl")
                        ruleSet.copy(tag = updatedTag, url = updatedUrl)
                    } else {
                        ruleSet
                    }
                }
                
                // 去重：如果存在相同 tag 的规则集，保留最后一个（通常是较新的或已迁移的）
                val result = migratedList.distinctBy { it.tag }
                Log.d("SettingsRepository", "Final rule sets tags: ${result.map { it.tag }}")
                result
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
            tunMtu = preferences[PreferencesKeys.TUN_MTU] ?: 1500,
            tunInterfaceName = preferences[PreferencesKeys.TUN_INTERFACE_NAME] ?: "tun0",
            autoRoute = preferences[PreferencesKeys.AUTO_ROUTE] ?: true,
            strictRoute = preferences[PreferencesKeys.STRICT_ROUTE] ?: true,
            
            // DNS 设置
            localDns = preferences[PreferencesKeys.LOCAL_DNS] ?: "8.8.8.8",
            remoteDns = preferences[PreferencesKeys.REMOTE_DNS] ?: "1.1.1.1",
            fakeDnsEnabled = preferences[PreferencesKeys.FAKE_DNS_ENABLED] ?: true,
            fakeIpRange = preferences[PreferencesKeys.FAKE_IP_RANGE] ?: "198.18.0.0/15",
            dnsStrategy = DnsStrategy.fromDisplayName(preferences[PreferencesKeys.DNS_STRATEGY] ?: "优先 IPv4"),
            dnsCacheEnabled = preferences[PreferencesKeys.DNS_CACHE_ENABLED] ?: true,
            
            // 路由设置
            routingMode = RoutingMode.fromDisplayName(preferences[PreferencesKeys.ROUTING_MODE] ?: "规则模式"),
            defaultRule = DefaultRule.fromDisplayName(preferences[PreferencesKeys.DEFAULT_RULE] ?: "直连"),
            blockAds = preferences[PreferencesKeys.BLOCK_ADS] ?: true,
            bypassLan = preferences[PreferencesKeys.BYPASS_LAN] ?: true,
            
            // 镜像设置
            ghProxyMirror = selectedMirror,
            
            // 高级路由
            customRules = customRules,
            ruleSets = ruleSets,
            appRules = appRules,
            appGroups = appGroups
        )
    }
    
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
    }
    
    suspend fun setTunStack(value: TunStack) {
        context.dataStore.edit { it[PreferencesKeys.TUN_STACK] = value.displayName }
    }
    
    suspend fun setTunMtu(value: Int) {
        context.dataStore.edit { it[PreferencesKeys.TUN_MTU] = value }
    }
    
    suspend fun setTunInterfaceName(value: String) {
        context.dataStore.edit { it[PreferencesKeys.TUN_INTERFACE_NAME] = value }
    }
    
    suspend fun setAutoRoute(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_ROUTE] = value }
    }
    
    suspend fun setStrictRoute(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.STRICT_ROUTE] = value }
    }
    
    // DNS 设置
    suspend fun setLocalDns(value: String) {
        context.dataStore.edit { it[PreferencesKeys.LOCAL_DNS] = value }
    }
    
    suspend fun setRemoteDns(value: String) {
        context.dataStore.edit { it[PreferencesKeys.REMOTE_DNS] = value }
    }
    
    suspend fun setFakeDnsEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.FAKE_DNS_ENABLED] = value }
    }
    
    suspend fun setFakeIpRange(value: String) {
        context.dataStore.edit { it[PreferencesKeys.FAKE_IP_RANGE] = value }
    }
    
    suspend fun setDnsStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.DNS_STRATEGY] = value.displayName }
    }
    
    suspend fun setDnsCacheEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DNS_CACHE_ENABLED] = value }
    }
    
    // 路由设置
    suspend fun setRoutingMode(value: RoutingMode) {
        context.dataStore.edit { it[PreferencesKeys.ROUTING_MODE] = value.displayName }
    }
    
    suspend fun setDefaultRule(value: DefaultRule) {
        context.dataStore.edit { it[PreferencesKeys.DEFAULT_RULE] = value.displayName }
    }
    
    suspend fun setBlockAds(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BLOCK_ADS] = value }
    }
    
    suspend fun setBypassLan(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BYPASS_LAN] = value }
    }
    
    suspend fun setGhProxyMirror(value: GhProxyMirror) {
        context.dataStore.edit { it[PreferencesKeys.GH_PROXY_MIRROR] = value.displayName }
    }
    
    suspend fun setCustomRules(value: List<CustomRule>) {
        context.dataStore.edit { it[PreferencesKeys.CUSTOM_RULES] = gson.toJson(value) }
    }

    suspend fun setRuleSets(value: List<RuleSet>) {
        context.dataStore.edit { it[PreferencesKeys.RULE_SETS] = gson.toJson(value) }
    }

    suspend fun getRuleSets(): List<RuleSet> {
        return settings.first().ruleSets
    }

    suspend fun setAppRules(value: List<AppRule>) {
        context.dataStore.edit { it[PreferencesKeys.APP_RULES] = gson.toJson(value) }
    }

    suspend fun setAppGroups(value: List<AppGroup>) {
        context.dataStore.edit { it[PreferencesKeys.APP_GROUPS] = gson.toJson(value) }
    }
    
    suspend fun checkAndMigrateRuleSets() {
        try {
            val currentSettings = settings.first()
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

    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
