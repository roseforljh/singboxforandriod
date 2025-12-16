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
import com.kunk.singbox.model.DnsStrategy
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
fun DnsSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val settings by settingsViewModel.settings.collectAsState()
    
    // State for Dialogs
    var showLocalDnsDialog by remember { mutableStateOf(false) }
    var showRemoteDnsDialog by remember { mutableStateOf(false) }
    var showFakeIpDialog by remember { mutableStateOf(false) }
    var showStrategyDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }

    if (showLocalDnsDialog) {
        InputDialog(
            title = "本地 DNS",
            initialValue = settings.localDns,
            onConfirm = { 
                settingsViewModel.setLocalDns(it)
                showLocalDnsDialog = false 
            },
            onDismiss = { showLocalDnsDialog = false }
        )
    }
    
    if (showRemoteDnsDialog) {
        InputDialog(
            title = "远程 DNS",
            initialValue = settings.remoteDns,
            onConfirm = { 
                settingsViewModel.setRemoteDns(it)
                showRemoteDnsDialog = false 
            },
            onDismiss = { showRemoteDnsDialog = false }
        )
    }
    
    if (showFakeIpDialog) {
        InputDialog(
            title = "虚假 IP 段",
            initialValue = settings.fakeIpRange,
            onConfirm = { 
                settingsViewModel.setFakeIpRange(it)
                showFakeIpDialog = false 
            },
            onDismiss = { showFakeIpDialog = false }
        )
    }
    
    if (showStrategyDialog) {
        val options = DnsStrategy.entries.map { it.displayName }
        SingleSelectDialog(
            title = "解析策略",
            options = options,
            selectedIndex = options.indexOf(settings.dnsStrategy.displayName).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setDnsStrategy(DnsStrategy.entries[index])
                showStrategyDialog = false
            },
            onDismiss = { showStrategyDialog = false }
        )
    }
    
    if (showCacheDialog) {
        val options = listOf("已启用", "已禁用")
        val currentIndex = if (settings.dnsCacheEnabled) 0 else 1
        SingleSelectDialog(
            title = "DNS 缓存",
            options = options,
            selectedIndex = currentIndex,
            onSelect = { index ->
                settingsViewModel.setDnsCacheEnabled(index == 0)
                showCacheDialog = false
            },
            onDismiss = { showCacheDialog = false }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("DNS 设置", color = TextPrimary) },
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
                SettingItem(title = "本地 DNS", value = settings.localDns, onClick = { showLocalDnsDialog = true })
                SettingItem(title = "远程 DNS", value = settings.remoteDns, onClick = { showRemoteDnsDialog = true })
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StandardCard {
                SettingSwitchItem(
                    title = "Fake DNS",
                    subtitle = "返回虚假 IP 以加速域名解析",
                    checked = settings.fakeDnsEnabled,
                    onCheckedChange = { settingsViewModel.setFakeDnsEnabled(it) }
                )
                SettingItem(title = "虚假 IP 段", value = settings.fakeIpRange, onClick = { showFakeIpDialog = true })
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StandardCard {
                SettingItem(title = "解析策略", value = settings.dnsStrategy.displayName, onClick = { showStrategyDialog = true })
                SettingItem(title = "缓存", value = if (settings.dnsCacheEnabled) "已启用" else "已禁用", onClick = { showCacheDialog = true })
            }
        }
    }
}