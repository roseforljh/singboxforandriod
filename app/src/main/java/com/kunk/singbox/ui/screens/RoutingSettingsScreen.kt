package com.kunk.singbox.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.GhProxyMirror
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val settings by settingsViewModel.settings.collectAsState()
    
    // Dialog States
    var showModeDialog by remember { mutableStateOf(false) }
    var showDefaultRuleDialog by remember { mutableStateOf(false) }
    var showMirrorDialog by remember { mutableStateOf(false) }
    var showLatencyMethodDialog by remember { mutableStateOf(false) }
    var showLatencyUrlDialog by remember { mutableStateOf(false) }

    if (showLatencyMethodDialog) {
        val options = LatencyTestMethod.entries.map { it.displayName }
        SingleSelectDialog(
            title = "延迟测试方式",
            options = options,
            selectedIndex = LatencyTestMethod.entries.indexOf(settings.latencyTestMethod).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setLatencyTestMethod(LatencyTestMethod.entries[index])
                showLatencyMethodDialog = false
            },
            onDismiss = { showLatencyMethodDialog = false }
        )
    }

    if (showLatencyUrlDialog) {
        InputDialog(
            title = "延迟测试地址",
            initialValue = settings.latencyTestUrl,
            placeholder = "例如：https://cp.cloudflare.com/generate_204",
            onConfirm = { url ->
                settingsViewModel.setLatencyTestUrl(url.trim())
                showLatencyUrlDialog = false
            },
            onDismiss = { showLatencyUrlDialog = false }
        )
    }

    if (showModeDialog) {
        val options = RoutingMode.entries.map { it.displayName }
        SingleSelectDialog(
            title = "路由模式",
            options = options,
            selectedIndex = options.indexOf(settings.routingMode.displayName).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setRoutingMode(RoutingMode.entries[index])
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }
    
    if (showDefaultRuleDialog) {
        val options = DefaultRule.entries.map { it.displayName }
        SingleSelectDialog(
            title = "默认规则",
            options = options,
            selectedIndex = options.indexOf(settings.defaultRule.displayName).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setDefaultRule(DefaultRule.entries[index])
                showDefaultRuleDialog = false
            },
            onDismiss = { showDefaultRuleDialog = false }
        )
    }

    if (showMirrorDialog) {
        val options = GhProxyMirror.entries.map { it.displayName }
        SingleSelectDialog(
            title = "GitHub 镜像加速",
            options = options,
            selectedIndex = options.indexOf(settings.ghProxyMirror.displayName).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setGhProxyMirror(GhProxyMirror.entries[index])
                showMirrorDialog = false
            },
            onDismiss = { showMirrorDialog = false }
        )
    }
    
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("路由设置", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            StandardCard {
                SettingItem(title = "路由模式", value = settings.routingMode.displayName, onClick = { showModeDialog = true })
                SettingItem(title = "默认规则", value = settings.defaultRule.displayName, onClick = { showDefaultRuleDialog = true })
                SettingItem(title = "延迟测试方式", value = settings.latencyTestMethod.displayName, onClick = { showLatencyMethodDialog = true })
                SettingItem(
                    title = "延迟测试地址",
                    onClick = { showLatencyUrlDialog = true },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = settings.latencyTestUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = Neutral500
                            )
                        }
                    }
                )
                SettingItem(title = "GitHub 镜像", value = settings.ghProxyMirror.displayName, onClick = { showMirrorDialog = true })
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StandardCard {
                SettingSwitchItem(
                    title = "拦截广告",
                    subtitle = "拦截常见广告域名",
                    checked = settings.blockAds,
                    onCheckedChange = { settingsViewModel.setBlockAds(it) }
                )
                SettingSwitchItem(
                    title = "屏蔽 QUIC",
                    subtitle = "屏蔽 UDP 443 以减少初始加载延迟",
                    checked = settings.blockQuic,
                    onCheckedChange = { settingsViewModel.setBlockQuic(it) }
                )
                SettingSwitchItem(
                    title = "绕过局域网",
                    subtitle = "局域网流量不经过代理",
                    checked = settings.bypassLan,
                    onCheckedChange = { settingsViewModel.setBypassLan(it) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StandardCard {
                SettingItem(
                    title = "应用分流",
                    value = "${settings.appRules.size + settings.appGroups.size} 条规则",
                    onClick = { navController.navigate(Screen.AppRules.route) }
                )
                SettingItem(title = "管理规则集", onClick = { navController.navigate(Screen.RuleSets.route) })
                SettingItem(title = "规则集路由", onClick = { navController.navigate(Screen.RuleSetRouting.route) })
            }
        }
    }
}