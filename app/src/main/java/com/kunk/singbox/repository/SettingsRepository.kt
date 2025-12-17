package com.kunk.singbox.repository

import android.content.Context
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
        
        // 高级路由 (JSON)
        val CUSTOM_RULES = stringPreferencesKey("custom_rules")
        val RULE_SETS = stringPreferencesKey("rule_sets")
        val APP_RULES = stringPreferencesKey("app_rules")
        val APP_GROUPS = stringPreferencesKey("app_groups")
    }
    
    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val customRulesJson = preferences[PreferencesKeys.CUSTOM_RULES]
        val customRules = if (customRulesJson != null) {
            try {
                gson.fromJson<List<CustomRule>>(customRulesJson, object : TypeToken<List<CustomRule>>() {}.type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val ruleSetsJson = preferences[PreferencesKeys.RULE_SETS]
        val ruleSets = if (ruleSetsJson != null) {
            try {
                gson.fromJson<List<RuleSet>>(ruleSetsJson, object : TypeToken<List<RuleSet>>() {}.type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val appRulesJson = preferences[PreferencesKeys.APP_RULES]
        val appRules = if (appRulesJson != null) {
            try {
                gson.fromJson<List<AppRule>>(appRulesJson, object : TypeToken<List<AppRule>>() {}.type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val appGroupsJson = preferences[PreferencesKeys.APP_GROUPS]
        val appGroups = if (appGroupsJson != null) {
            try {
                gson.fromJson<List<AppGroup>>(appGroupsJson, object : TypeToken<List<AppGroup>>() {}.type)
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
