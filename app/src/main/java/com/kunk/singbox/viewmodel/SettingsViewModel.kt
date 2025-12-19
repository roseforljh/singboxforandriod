package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = SettingsRepository.getInstance(application)
    
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )
    
    // 通用设置
    fun setAutoConnect(value: Boolean) {
        viewModelScope.launch { repository.setAutoConnect(value) }
    }
    
    fun setAutoReconnect(value: Boolean) {
        viewModelScope.launch { repository.setAutoReconnect(value) }
    }
    
    fun setExcludeFromRecent(value: Boolean) {
        viewModelScope.launch { repository.setExcludeFromRecent(value) }
    }
    
    // TUN/VPN 设置
    fun setTunEnabled(value: Boolean) {
        viewModelScope.launch { repository.setTunEnabled(value) }
    }
    
    fun setTunStack(value: TunStack) {
        viewModelScope.launch { repository.setTunStack(value) }
    }
    
    fun setTunMtu(value: Int) {
        viewModelScope.launch { repository.setTunMtu(value) }
    }
    
    fun setTunInterfaceName(value: String) {
        viewModelScope.launch { repository.setTunInterfaceName(value) }
    }
    
    fun setAutoRoute(value: Boolean) {
        viewModelScope.launch { repository.setAutoRoute(value) }
    }
    
    fun setStrictRoute(value: Boolean) {
        viewModelScope.launch { repository.setStrictRoute(value) }
    }

    fun setVpnRouteMode(value: VpnRouteMode) {
        viewModelScope.launch { repository.setVpnRouteMode(value) }
    }

    fun setVpnRouteIncludeCidrs(value: String) {
        viewModelScope.launch { repository.setVpnRouteIncludeCidrs(value) }
    }

    fun setVpnAppMode(value: VpnAppMode) {
        viewModelScope.launch { repository.setVpnAppMode(value) }
    }

    fun setVpnAllowlist(value: String) {
        viewModelScope.launch { repository.setVpnAllowlist(value) }
    }

    fun setVpnBlocklist(value: String) {
        viewModelScope.launch { repository.setVpnBlocklist(value) }
    }
    
    // DNS 设置
    fun setLocalDns(value: String) {
        viewModelScope.launch { repository.setLocalDns(value) }
    }
    
    fun setRemoteDns(value: String) {
        viewModelScope.launch { repository.setRemoteDns(value) }
    }
    
    fun setFakeDnsEnabled(value: Boolean) {
        viewModelScope.launch { repository.setFakeDnsEnabled(value) }
    }
    
    fun setFakeIpRange(value: String) {
        viewModelScope.launch { repository.setFakeIpRange(value) }
    }
    
    fun setDnsStrategy(value: DnsStrategy) {
        viewModelScope.launch { repository.setDnsStrategy(value) }
    }

    fun setRemoteDnsStrategy(value: DnsStrategy) {
        viewModelScope.launch { repository.setRemoteDnsStrategy(value) }
    }

    fun setDirectDnsStrategy(value: DnsStrategy) {
        viewModelScope.launch { repository.setDirectDnsStrategy(value) }
    }

    fun setServerAddressStrategy(value: DnsStrategy) {
        viewModelScope.launch { repository.setServerAddressStrategy(value) }
    }
    
    fun setDnsCacheEnabled(value: Boolean) {
        viewModelScope.launch { repository.setDnsCacheEnabled(value) }
    }
    
    // 路由设置
    fun setRoutingMode(value: RoutingMode) {
        viewModelScope.launch { repository.setRoutingMode(value) }
    }
    
    fun setDefaultRule(value: DefaultRule) {
        viewModelScope.launch { repository.setDefaultRule(value) }
    }
    
    fun setBlockAds(value: Boolean) {
        viewModelScope.launch { repository.setBlockAds(value) }
    }
    
    fun setBlockQuic(value: Boolean) {
        viewModelScope.launch { repository.setBlockQuic(value) }
    }
    
    fun setLatencyTestMethod(value: LatencyTestMethod) {
        viewModelScope.launch { repository.setLatencyTestMethod(value) }
    }
    
    fun setLatencyTestUrl(value: String) {
        viewModelScope.launch { repository.setLatencyTestUrl(value) }
    }
    
    fun setBypassLan(value: Boolean) {
        viewModelScope.launch { repository.setBypassLan(value) }
    }

    fun setGhProxyMirror(value: GhProxyMirror) {
        viewModelScope.launch { repository.setGhProxyMirror(value) }
    }

    // 代理设置
    fun updateProxyPort(value: Int) {
        viewModelScope.launch { repository.setProxyPort(value) }
    }

    fun updateAllowLan(value: Boolean) {
        viewModelScope.launch { repository.setAllowLan(value) }
    }

    fun updateAppendHttpProxy(value: Boolean) {
        viewModelScope.launch { repository.setAppendHttpProxy(value) }
    }

    // 高级路由
    fun addCustomRule(rule: CustomRule) {
        viewModelScope.launch {
            val currentRules = settings.value.customRules.toMutableList()
            currentRules.add(rule)
            repository.setCustomRules(currentRules)
        }
    }

    fun updateCustomRule(rule: CustomRule) {
        viewModelScope.launch {
            val currentRules = settings.value.customRules.toMutableList()
            val index = currentRules.indexOfFirst { it.id == rule.id }
            if (index != -1) {
                currentRules[index] = rule
                repository.setCustomRules(currentRules)
            }
        }
    }

    fun deleteCustomRule(ruleId: String) {
        viewModelScope.launch {
            val currentRules = settings.value.customRules.toMutableList()
            currentRules.removeAll { it.id == ruleId }
            repository.setCustomRules(currentRules)
        }
    }

    fun addRuleSet(ruleSet: RuleSet, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val currentSets = repository.getRuleSets().toMutableList()
            val exists = currentSets.any { it.tag == ruleSet.tag && it.url == ruleSet.url }
            if (exists) {
                onResult(false, "规则集 \"${ruleSet.tag}\" 已存在")
            } else {
                currentSets.add(ruleSet)
                repository.setRuleSets(currentSets)
                onResult(true, "已添加规则集 \"${ruleSet.tag}\"")
            }
        }
    }

    fun addRuleSets(ruleSets: List<RuleSet>, onResult: (Int) -> Unit = { _ -> }) {
        viewModelScope.launch {
            val currentSets = repository.getRuleSets().toMutableList()
            var addedCount = 0
            ruleSets.forEach { ruleSet ->
                val exists = currentSets.any { it.tag == ruleSet.tag && it.url == ruleSet.url }
                if (!exists) {
                    currentSets.add(ruleSet)
                    addedCount++
                }
            }
            repository.setRuleSets(currentSets)
            onResult(addedCount)
        }
    }

    fun updateRuleSet(ruleSet: RuleSet) {
        viewModelScope.launch {
            val currentSets = settings.value.ruleSets.toMutableList()
            val index = currentSets.indexOfFirst { it.id == ruleSet.id }
            if (index != -1) {
                currentSets[index] = ruleSet
                repository.setRuleSets(currentSets)
            }
        }
    }

    fun deleteRuleSet(ruleSetId: String) {
        viewModelScope.launch {
            val currentSets = settings.value.ruleSets.toMutableList()
            currentSets.removeAll { it.id == ruleSetId }
            repository.setRuleSets(currentSets)
        }
    }
    
    fun deleteRuleSets(ruleSetIds: List<String>) {
        viewModelScope.launch {
            val idsToDelete = ruleSetIds.toSet()
            val currentSets = settings.value.ruleSets.toMutableList()
            currentSets.removeAll { it.id in idsToDelete }
            repository.setRuleSets(currentSets)
        }
    }

    // App 分流规则
    fun addAppRule(rule: AppRule) {
        viewModelScope.launch {
            val currentRules = settings.value.appRules.toMutableList()
            // 避免重复添加同一个应用
            currentRules.removeAll { it.packageName == rule.packageName }
            currentRules.add(rule)
            repository.setAppRules(currentRules)
        }
    }

    fun updateAppRule(rule: AppRule) {
        viewModelScope.launch {
            val currentRules = settings.value.appRules.toMutableList()
            val index = currentRules.indexOfFirst { it.id == rule.id }
            if (index != -1) {
                currentRules[index] = rule
                repository.setAppRules(currentRules)
            }
        }
    }

    fun deleteAppRule(ruleId: String) {
        viewModelScope.launch {
            val currentRules = settings.value.appRules.toMutableList()
            currentRules.removeAll { it.id == ruleId }
            repository.setAppRules(currentRules)
        }
    }

    fun toggleAppRuleEnabled(ruleId: String) {
        viewModelScope.launch {
            val currentRules = settings.value.appRules.toMutableList()
            val index = currentRules.indexOfFirst { it.id == ruleId }
            if (index != -1) {
                val rule = currentRules[index]
                currentRules[index] = rule.copy(enabled = !rule.enabled)
                repository.setAppRules(currentRules)
            }
        }
    }

    // App 分组
    fun addAppGroup(group: AppGroup) {
        viewModelScope.launch {
            val currentGroups = settings.value.appGroups.toMutableList()
            currentGroups.add(group)
            repository.setAppGroups(currentGroups)
        }
    }

    fun updateAppGroup(group: AppGroup) {
        viewModelScope.launch {
            val currentGroups = settings.value.appGroups.toMutableList()
            val index = currentGroups.indexOfFirst { it.id == group.id }
            if (index != -1) {
                currentGroups[index] = group
                repository.setAppGroups(currentGroups)
            }
        }
    }

    fun deleteAppGroup(groupId: String) {
        viewModelScope.launch {
            val currentGroups = settings.value.appGroups.toMutableList()
            currentGroups.removeAll { it.id == groupId }
            repository.setAppGroups(currentGroups)
        }
    }

    fun toggleAppGroupEnabled(groupId: String) {
        viewModelScope.launch {
            val currentGroups = settings.value.appGroups.toMutableList()
            val index = currentGroups.indexOfFirst { it.id == groupId }
            if (index != -1) {
                val group = currentGroups[index]
                currentGroups[index] = group.copy(enabled = !group.enabled)
                repository.setAppGroups(currentGroups)
            }
        }
    }
}
