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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
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
                    title = "绕过局域网",
                    subtitle = "局域网流量不经过代理",
                    checked = settings.bypassLan,
                    onCheckedChange = { settingsViewModel.setBypassLan(it) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StandardCard {
                SettingItem(title = "应用分流", value = "${settings.appRules.size} 条规则", onClick = { navController.navigate(Screen.AppRules.route) })
                SettingItem(title = "管理规则集", onClick = { navController.navigate(Screen.RuleSets.route) })
                SettingItem(title = "自定义规则", onClick = { navController.navigate(Screen.CustomRules.route) })
            }
        }
    }
}