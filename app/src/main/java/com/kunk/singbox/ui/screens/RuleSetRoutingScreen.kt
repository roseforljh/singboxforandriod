package com.kunk.singbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetRoutingScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    val nodes by nodesViewModel.nodes.collectAsState()
    val groups by nodesViewModel.nodeGroups.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()

    var editingRuleSet by remember { mutableStateOf<RuleSet?>(null) }
    var showOutboundModeDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showInboundDialog by remember { mutableStateOf(false) }

    // Temporary state for target selection
    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // Name, ID/Value

    // Available Inbounds (Hardcoded for now as they are system defaults usually)
    val availableInbounds = listOf("tun", "mixed")

    if (showOutboundModeDialog && editingRuleSet != null) {
        val options = RuleSetOutboundMode.entries.map { it.displayName }
        val currentMode = editingRuleSet!!.outboundMode ?: RuleSetOutboundMode.DIRECT
        SingleSelectDialog(
            title = "选择出站模式",
            options = options,
            selectedIndex = RuleSetOutboundMode.entries.indexOf(currentMode),
            onSelect = { index ->
                val selectedMode = RuleSetOutboundMode.entries[index]
                val updatedRuleSet = editingRuleSet!!.copy(outboundMode = selectedMode, outboundValue = null)
                
                // If mode requires further selection, trigger it
                if (selectedMode == RuleSetOutboundMode.NODE || 
                    selectedMode == RuleSetOutboundMode.PROFILE || 
                    selectedMode == RuleSetOutboundMode.GROUP) {
                    
                    editingRuleSet = updatedRuleSet // Update local state to remember mode
                    showOutboundModeDialog = false
                    
                    // Prepare target selection
                    when (selectedMode) {
                        RuleSetOutboundMode.NODE -> {
                            targetSelectionTitle = "选择节点"
                            targetOptions = nodes.map { it.name to it.id }
                        }
                        RuleSetOutboundMode.PROFILE -> {
                            targetSelectionTitle = "选择配置"
                            targetOptions = profiles.map { it.name to it.id }
                        }
                        RuleSetOutboundMode.GROUP -> {
                            targetSelectionTitle = "选择节点组"
                            targetOptions = groups.map { it to it }
                        }
                        else -> {}
                    }
                    showTargetSelectionDialog = true
                } else {
                    // Direct or Block, save immediately
                    settingsViewModel.updateRuleSet(updatedRuleSet)
                    editingRuleSet = null
                    showOutboundModeDialog = false
                }
            },
            onDismiss = { 
                showOutboundModeDialog = false 
                editingRuleSet = null
            }
        )
    }

    if (showTargetSelectionDialog && editingRuleSet != null) {
        SingleSelectDialog(
            title = targetSelectionTitle,
            options = targetOptions.map { it.first },
            selectedIndex = targetOptions.indexOfFirst { it.second == editingRuleSet!!.outboundValue }.coerceAtLeast(-1),
            onSelect = { index ->
                val selectedValue = targetOptions[index].second
                val updatedRuleSet = editingRuleSet!!.copy(outboundValue = selectedValue)
                settingsViewModel.updateRuleSet(updatedRuleSet)
                showTargetSelectionDialog = false
                editingRuleSet = null
            },
            onDismiss = {
                showTargetSelectionDialog = false
                editingRuleSet = null
            }
        )
    }

    if (showInboundDialog && editingRuleSet != null) {
        AlertDialog(
            onDismissRequest = { 
                showInboundDialog = false
                editingRuleSet = null
            },
            containerColor = Neutral800,
            title = { Text("选择入站", color = TextPrimary) },
            text = {
                Column {
                    availableInbounds.forEach { inbound ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val currentInbounds = (editingRuleSet!!.inbounds ?: emptyList()).toMutableList()
                                    if (currentInbounds.contains(inbound)) {
                                        currentInbounds.remove(inbound)
                                    } else {
                                        currentInbounds.add(inbound)
                                    }
                                    editingRuleSet = editingRuleSet!!.copy(inbounds = currentInbounds)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = (editingRuleSet!!.inbounds ?: emptyList()).contains(inbound),
                                onCheckedChange = null // Handled by Row click
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = inbound, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.updateRuleSet(editingRuleSet!!)
                        showInboundDialog = false
                        editingRuleSet = null
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showInboundDialog = false
                        editingRuleSet = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("规则集路由", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(settings.ruleSets) { ruleSet ->
                StandardCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = ruleSet.tag,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Outbound Setting
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    editingRuleSet = ruleSet
                                    showOutboundModeDialog = true
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("出站", color = TextSecondary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val currentMode = ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT
                                val outboundText = when (currentMode) {
                                    RuleSetOutboundMode.DIRECT -> "直连"
                                    RuleSetOutboundMode.BLOCK -> "拦截"
                                    RuleSetOutboundMode.NODE -> {
                                        val nodeName = nodes.find { it.id == ruleSet.outboundValue }?.name ?: "未知节点"
                                        "节点: $nodeName"
                                    }
                                    RuleSetOutboundMode.PROFILE -> {
                                        val profileName = profiles.find { it.id == ruleSet.outboundValue }?.name ?: "未知配置"
                                        "配置: $profileName"
                                    }
                                    RuleSetOutboundMode.GROUP -> "节点组: ${ruleSet.outboundValue ?: "未知"}"
                                }
                                Text(outboundText, color = TextPrimary)
                            }
                        }
                        
                        Divider(color = Color.Gray.copy(alpha = 0.2f))
                        
                        // Inbound Setting
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    editingRuleSet = ruleSet
                                    showInboundDialog = true
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("入站", color = TextSecondary)
                            val currentInbounds = ruleSet.inbounds ?: emptyList()
                            val inboundsText = if (currentInbounds.isEmpty()) "所有" else currentInbounds.joinToString(", ")
                            Text(inboundsText, color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}