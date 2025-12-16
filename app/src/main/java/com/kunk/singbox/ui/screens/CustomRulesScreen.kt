package com.kunk.singbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRulesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomRule?>(null) }

    if (showAddDialog) {
        CustomRuleEditorDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addCustomRule(rule)
                showAddDialog = false
            }
        )
    }

    if (editingRule != null) {
        CustomRuleEditorDialog(
            initialRule = editingRule,
            onDismiss = { editingRule = null },
            onConfirm = { rule ->
                settingsViewModel.updateCustomRule(rule)
                editingRule = null
            },
            onDelete = {
                settingsViewModel.deleteCustomRule(editingRule!!.id)
                editingRule = null
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("自定义规则", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加", tint = PureWhite)
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
            if (settings.customRules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无自定义规则",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                items(settings.customRules) { rule ->
                    CustomRuleItem(
                        rule = rule,
                        onClick = { editingRule = rule }
                    )
                }
            }
        }
    }
}

@Composable
fun CustomRuleItem(
    rule: CustomRule,
    onClick: () -> Unit
) {
    StandardCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${rule.type.displayName}: ${rule.value}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1
                )
                Text(
                    text = "-> ${rule.outbound.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "编辑",
                tint = TextSecondary
            )
        }
    }
}

@Composable
fun CustomRuleEditorDialog(
    initialRule: CustomRule? = null,
    onDismiss: () -> Unit,
    onConfirm: (CustomRule) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialRule?.name ?: "") }
    var type by remember { mutableStateOf(initialRule?.type ?: RuleType.DOMAIN_SUFFIX) }
    var value by remember { mutableStateOf(initialRule?.value ?: "") }
    var outbound by remember { mutableStateOf(initialRule?.outbound ?: OutboundTag.PROXY) }
    
    var showTypeDialog by remember { mutableStateOf(false) }
    var showOutboundDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showTypeDialog) {
        val options = RuleType.entries.map { it.displayName }
        SingleSelectDialog(
            title = "规则类型",
            options = options,
            selectedIndex = RuleType.entries.indexOf(type),
            onSelect = { index ->
                type = RuleType.entries[index]
                showTypeDialog = false
            },
            onDismiss = { showTypeDialog = false }
        )
    }

    if (showOutboundDialog) {
        val options = OutboundTag.entries.map { it.displayName }
        SingleSelectDialog(
            title = "出站",
            options = options,
            selectedIndex = OutboundTag.entries.indexOf(outbound),
            onSelect = { index ->
                outbound = OutboundTag.entries[index]
                showOutboundDialog = false
            },
            onDismiss = { showOutboundDialog = false }
        )
    }
    
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除规则",
            message = "确定要删除规则 \"$name\" 吗？",
            confirmText = "删除",
            onConfirm = {
                onDelete?.invoke()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = { 
            Text(
                text = if (initialRule == null) "添加规则" else "编辑规则",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            ) 
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                StyledTextField(
                    label = "名称",
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "我的规则"
                )

                ClickableDropdownField(
                    label = "类型",
                    value = type.displayName,
                    onClick = { showTypeDialog = true }
                )

                StyledTextField(
                    label = "规则内容",
                    value = value,
                    onValueChange = { value = it },
                    placeholder = when(type) {
                        RuleType.DOMAIN -> "example.com"
                        RuleType.DOMAIN_SUFFIX -> "example.com"
                        RuleType.IP_CIDR -> "192.168.0.0/16"
                        else -> "内容"
                    }
                )

                ClickableDropdownField(
                    label = "出站",
                    value = outbound.displayName,
                    onClick = { showOutboundDialog = true }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newRule = initialRule?.copy(
                        name = name,
                        type = type,
                        value = value,
                        outbound = outbound
                    ) ?: CustomRule(
                        name = name,
                        type = type,
                        value = value,
                        outbound = outbound
                    )
                    onConfirm(newRule)
                },
                enabled = name.isNotBlank() && value.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (initialRule != null && onDelete != null) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}