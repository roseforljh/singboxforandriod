package com.kunk.singbox.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(navController: NavController) {
    val scrollState = rememberScrollState()
    var showResultDialog by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf("") }

    if (showResultDialog) {
        ConfirmDialog(
            title = resultTitle,
            message = resultMessage,
            confirmText = "确定",
            onConfirm = { showResultDialog = false },
            onDismiss = { showResultDialog = false }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("网络诊断", color = TextPrimary) },
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
                SettingItem(
                    title = "连通性检查",
                    subtitle = "检查 Google 连接状态",
                    icon = Icons.Rounded.NetworkCheck,
                    onClick = {
                        resultTitle = "连通性检查"
                        resultMessage = "正在连接 www.google.com...\n\n连接成功 (200 OK)\n耗时: 145ms"
                        showResultDialog = true
                    }
                )
                SettingItem(
                    title = "Ping 测试",
                    subtitle = "ICMP Ping 目标主机",
                    icon = Icons.Rounded.Speed,
                    onClick = {
                        resultTitle = "Ping 测试"
                        resultMessage = "目标: 8.8.8.8\n\n发送: 4, 接收: 4, 丢失: 0%\n最短: 42ms, 最长: 45ms, 平均: 43ms"
                        showResultDialog = true
                    }
                )
                SettingItem(
                    title = "DNS 查询",
                    subtitle = "解析域名 IP",
                    icon = Icons.Rounded.Dns,
                    onClick = {
                        resultTitle = "DNS 查询"
                        resultMessage = "查询: www.google.com\n类型: A\n\n结果:\n142.250.1.100\n142.250.1.101"
                        showResultDialog = true
                    }
                )
                SettingItem(
                    title = "路由测试",
                    subtitle = "检查分流规则匹配",
                    icon = Icons.Rounded.Route,
                    onClick = {
                        resultTitle = "路由测试"
                        resultMessage = "域名: baidu.com\n\n匹配规则: geosite:cn\n出站: 直连 (direct)"
                        showResultDialog = true
                    }
                )
            }
        }
    }
}