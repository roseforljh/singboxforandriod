package com.kunk.singbox.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(navController: NavController) {
    var showTestDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }

    if (showTestDialog) {
        ConfirmDialog(
            title = "测速结果",
            message = "TCP 握手: 45ms\nHTTP 延迟: 120ms\n抖动: 5ms",
            confirmText = "确定",
            onConfirm = { showTestDialog = false },
            onDismiss = { showTestDialog = false }
        )
    }
    
    if (showCopyDialog) {
        ConfirmDialog(
            title = "已复制",
            message = "节点链接已复制到剪贴板。",
            confirmText = "确定",
            onConfirm = { showCopyDialog = false },
            onDismiss = { showCopyDialog = false }
        )
    }
    
    if (showShareDialog) {
        ConfirmDialog(
            title = "分享节点",
            message = "生成二维码或分享链接。\n(功能开发中)",
            confirmText = "确定",
            onConfirm = { showShareDialog = false },
            onDismiss = { showShareDialog = false }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("节点详情", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { showTestDialog = true }) {
                        Icon(Icons.Rounded.Bolt, contentDescription = "测速", tint = PureWhite)
                    }
                    IconButton(onClick = { showCopyDialog = true }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制", tint = PureWhite)
                    }
                    IconButton(onClick = { showShareDialog = true }) {
                        Icon(Icons.Rounded.Share, contentDescription = "分享", tint = PureWhite)
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
        ) {
            StandardCard {
                SettingItem(title = "名称", value = "HK-01 Premium Route")
                SettingItem(title = "协议", value = "VLESS")
                SettingItem(title = "地址", value = "hk.example.com")
                SettingItem(title = "端口", value = "443")
                SettingItem(title = "UUID", value = "********-****-****-****-************")
                SettingItem(title = "流控", value = "xtls-rprx-vision")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StandardCard {
                SettingItem(title = "传输方式", value = "TCP")
                SettingItem(title = "TLS", value = "已启用")
                SettingItem(title = "SNI", value = "hk.example.com")
                SettingItem(title = "指纹", value = "chrome")
            }
        }
    }
}