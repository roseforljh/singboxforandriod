package com.kunk.singbox.ui.screens

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.viewmodel.DashboardViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.ui.components.BigToggle
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InfoCard
import com.kunk.singbox.ui.components.ModeChip
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StatusChip
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import android.widget.Toast
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = viewModel()
) {
    val context = LocalContext.current
    
    val connectionState by viewModel.connectionState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val activeNodeId by viewModel.activeNodeId.collectAsState()
    
    // 获取活跃配置和节点的名称
    val activeProfileName = profiles.find { it.id == activeProfileId }?.name
    val activeNodeName = viewModel.getActiveNodeName()
    
    var showModeDialog by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf("规则模式") }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showTestDialog by remember { mutableStateOf(false) }
    
    val updateStatus by viewModel.updateStatus.collectAsState()
    val testStatus by viewModel.testStatus.collectAsState()
    val vpnPermissionNeeded by viewModel.vpnPermissionNeeded.collectAsState()
    
    // VPN 权限请求处理
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    
    // 当需要 VPN 权限时启动请求
    LaunchedEffect(vpnPermissionNeeded) {
        if (vpnPermissionNeeded) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                // 已有权限
                viewModel.onVpnPermissionResult(true)
            }
        }
    }

    // Monitor connection errors
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.Error) {
            Toast.makeText(context, "连接失败，请检查配置", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Monitor update status
    LaunchedEffect(updateStatus) {
        updateStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }
    
    // Monitor test status
    LaunchedEffect(testStatus) {
        testStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    if (showModeDialog) {
        val options = listOf("规则模式", "全局代理", "全局直连")
        SingleSelectDialog(
            title = "路由模式",
            options = options,
            selectedIndex = options.indexOf(currentMode).coerceAtLeast(0),
            onSelect = { index ->
                currentMode = options[index]
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }
    
    if (showUpdateDialog) {
        ConfirmDialog(
            title = "更新订阅",
            message = "确定要更新所有订阅吗？",
            confirmText = "更新",
            onConfirm = {
                viewModel.updateAllSubscriptions()
                showUpdateDialog = false
            },
            onDismiss = { showUpdateDialog = false }
        )
    }
    
    if (showTestDialog) {
        ConfirmDialog(
            title = "延迟测试",
            message = "确定要测试所有节点延迟吗？",
            confirmText = "开始测试",
            onConfirm = {
                viewModel.testAllNodesLatency()
                showTestDialog = false
            },
            onDismiss = { showTestDialog = false }
        )
    }
    
    // Helper to format bytes
    fun formatBytes(bytes: Long): String = Formatter.formatFileSize(context, bytes)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Status Bar (Chips)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(
                    label = when (connectionState) {
                        ConnectionState.Idle -> "未连接"
                        ConnectionState.Connecting -> "连接中..."
                        ConnectionState.Connected -> "已连接"
                        ConnectionState.Disconnecting -> "断开中..."
                        ConnectionState.Error -> "错误"
                    },
                    isActive = connectionState == ConnectionState.Connected
                )
                
                ModeChip(
                    mode = currentMode,
                    isRunning = connectionState == ConnectionState.Connected
                ) { showModeDialog = true }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                StatusChip(
                    label = activeProfileName ?: "未选择配置",
                    onClick = {
                        navController.navigate(Screen.Profiles.route) {
                            popUpTo(Screen.Dashboard.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(
                    label = activeNodeName ?: "未选择节点",
                    onClick = {
                        navController.navigate(Screen.Nodes.route) {
                            popUpTo(Screen.Dashboard.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }

        // 2. Main Toggle - 使用 TopCenter 对齐让偏移动画生效
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.weight(1f)
        ) {
            BigToggle(
                isRunning = connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting || connectionState == ConnectionState.Disconnecting,
                onClick = {
                    viewModel.toggleConnection()
                }
            )
        }

        // 3. Stats & Quick Actions
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                val isConnected = connectionState == ConnectionState.Connected
                InfoCard(
                    uploadSpeed = if (isConnected) "${formatBytes(stats.uploadSpeed)}/s" else "-/s",
                    downloadSpeed = if (isConnected) "${formatBytes(stats.downloadSpeed)}/s" else "-/s",
                    ping = if (isConnected) "45 ms" else "-" // Mock ping
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickActionButton(Icons.Rounded.Refresh, "更新订阅") { showUpdateDialog = true }
                QuickActionButton(Icons.Rounded.Bolt, "延迟测试") { showTestDialog = true }
                QuickActionButton(Icons.Rounded.History, "运行日志") { navController.navigate(Screen.Logs.route) }
                QuickActionButton(Icons.Rounded.BugReport, "网络诊断") { navController.navigate(Screen.Diagnostics.route) }
            }
        }
    }
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = TextPrimary
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}