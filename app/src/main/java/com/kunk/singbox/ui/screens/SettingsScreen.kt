package com.kunk.singbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.TextPrimary

@Composable
fun SettingsScreen(navController: NavController) {
    val scrollState = rememberScrollState()
    var showAboutDialog by remember { mutableStateOf(false) }

    if (showAboutDialog) {
        ConfirmDialog(
            title = "关于 SingBox",
            message = "SingBox for Android\n版本: 1.0.0 (Alpha)\n\n基于 Sing-box 内核构建。\n\nDesigned by KunK.",
            confirmText = "确定",
            onConfirm = { showAboutDialog = false },
            onDismiss = { showAboutDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 1. Connection & Startup
        SettingsGroupTitle("通用")
        StandardCard {
            SettingItem(
                title = "连接与启动",
                subtitle = "自动连接、断线重连",
                icon = Icons.Rounded.PowerSettingsNew,
                onClick = { navController.navigate(Screen.ConnectionSettings.route) }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // 2. Network
        SettingsGroupTitle("网络")
        StandardCard {
            SettingItem(
                title = "路由设置",
                subtitle = "模式、规则集、默认规则",
                icon = Icons.Rounded.Route,
                onClick = { navController.navigate(Screen.RoutingSettings.route) }
            )
            SettingItem(
                title = "DNS 设置",
                value = "自动",
                icon = Icons.Rounded.Dns,
                onClick = { navController.navigate(Screen.DnsSettings.route) }
            )
            SettingItem(
                title = "TUN / VPN",
                subtitle = "堆栈、MTU、分应用代理",
                icon = Icons.Rounded.VpnKey,
                onClick = { navController.navigate(Screen.TunSettings.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Tools
        SettingsGroupTitle("工具")
        StandardCard {
            SettingItem(
                title = "运行日志",
                icon = Icons.Rounded.History,
                onClick = { navController.navigate(Screen.Logs.route) }
            )
            SettingItem(
                title = "网络诊断",
                icon = Icons.Rounded.BugReport,
                onClick = { navController.navigate(Screen.Diagnostics.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. About
        SettingsGroupTitle("关于")
        StandardCard {
            SettingItem(
                title = "关于应用",
                value = "v1.0.0",
                icon = Icons.Rounded.Info,
                onClick = { showAboutDialog = true }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = TextPrimary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}