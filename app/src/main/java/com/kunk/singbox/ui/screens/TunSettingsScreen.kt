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
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val settings by settingsViewModel.settings.collectAsState()
    
    // Dialog States
    var showStackDialog by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showInterfaceDialog by remember { mutableStateOf(false) }

    if (showStackDialog) {
        val options = TunStack.entries.map { it.displayName }
        SingleSelectDialog(
            title = "堆栈",
            options = options,
            selectedIndex = options.indexOf(settings.tunStack.displayName).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setTunStack(TunStack.entries[index])
                showStackDialog = false
            },
            onDismiss = { showStackDialog = false }
        )
    }
    
    if (showMtuDialog) {
        InputDialog(
            title = "MTU",
            initialValue = settings.tunMtu.toString(),
            onConfirm = { 
                it.toIntOrNull()?.let { mtu -> settingsViewModel.setTunMtu(mtu) }
                showMtuDialog = false 
            },
            onDismiss = { showMtuDialog = false }
        )
    }
    
    if (showInterfaceDialog) {
        InputDialog(
            title = "接口名称",
            initialValue = settings.tunInterfaceName,
            onConfirm = { 
                settingsViewModel.setTunInterfaceName(it)
                showInterfaceDialog = false 
            },
            onDismiss = { showInterfaceDialog = false }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("TUN / VPN 设置", color = TextPrimary) },
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
                SettingSwitchItem(
                    title = "启用 TUN",
                    subtitle = "创建 VPN 接口以接管系统流量",
                    checked = settings.tunEnabled,
                    onCheckedChange = { settingsViewModel.setTunEnabled(it) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StandardCard {
                SettingItem(title = "堆栈", value = settings.tunStack.displayName, onClick = { showStackDialog = true })
                SettingItem(title = "MTU", value = settings.tunMtu.toString(), onClick = { showMtuDialog = true })
                SettingItem(title = "接口名称", value = settings.tunInterfaceName, onClick = { showInterfaceDialog = true })
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StandardCard {
                SettingSwitchItem(
                    title = "自动路由",
                    subtitle = "自动配置系统路由表",
                    checked = settings.autoRoute,
                    onCheckedChange = { settingsViewModel.setAutoRoute(it) }
                )
                SettingSwitchItem(
                    title = "严格路由",
                    subtitle = "防止 DNS 泄露",
                    checked = settings.strictRoute,
                    onCheckedChange = { settingsViewModel.setStrictRoute(it) }
                )
            }
        }
    }
}