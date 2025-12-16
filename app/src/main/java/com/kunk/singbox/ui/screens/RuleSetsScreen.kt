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
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.Neutral700
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRuleSet by remember { mutableStateOf<RuleSet?>(null) }

    if (showAddDialog) {
        RuleSetEditorDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { ruleSet ->
                settingsViewModel.addRuleSet(ruleSet)
                showAddDialog = false
            }
        )
    }

    if (editingRuleSet != null) {
        RuleSetEditorDialog(
            initialRuleSet = editingRuleSet,
            onDismiss = { editingRuleSet = null },
            onConfirm = { ruleSet ->
                settingsViewModel.updateRuleSet(ruleSet)
                editingRuleSet = null
            },
            onDelete = {
                settingsViewModel.deleteRuleSet(editingRuleSet!!.id)
                editingRuleSet = null
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("规则集管理", color = TextPrimary) },
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
            if (settings.ruleSets.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无规则集",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                items(settings.ruleSets) { ruleSet ->
                    RuleSetItem(
                        ruleSet = ruleSet,
                        onClick = { editingRuleSet = ruleSet }
                    )
                }
            }
        }
    }
}

@Composable
fun RuleSetItem(
    ruleSet: RuleSet,
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
                    text = ruleSet.tag,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${ruleSet.type.displayName} • ${ruleSet.format}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                if (ruleSet.type == RuleSetType.REMOTE) {
                    Text(
                        text = ruleSet.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = ruleSet.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
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
fun RuleSetEditorDialog(
    initialRuleSet: RuleSet? = null,
    onDismiss: () -> Unit,
    onConfirm: (RuleSet) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var tag by remember { mutableStateOf(initialRuleSet?.tag ?: "") }
    var type by remember { mutableStateOf(initialRuleSet?.type ?: RuleSetType.REMOTE) }
    var format by remember { mutableStateOf(initialRuleSet?.format ?: "binary") }
    var url by remember { mutableStateOf(initialRuleSet?.url ?: "") }
    var path by remember { mutableStateOf(initialRuleSet?.path ?: "") }
    
    var showTypeDialog by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showTypeDialog) {
        val options = RuleSetType.entries.map { it.displayName }
        SingleSelectDialog(
            title = "规则集类型",
            options = options,
            selectedIndex = RuleSetType.entries.indexOf(type),
            onSelect = { index ->
                type = RuleSetType.entries[index]
                showTypeDialog = false
            },
            onDismiss = { showTypeDialog = false }
        )
    }

    if (showFormatDialog) {
        val options = listOf("binary", "source")
        SingleSelectDialog(
            title = "规则集格式",
            options = options,
            selectedIndex = options.indexOf(format).coerceAtLeast(0),
            onSelect = { index ->
                format = options[index]
                showFormatDialog = false
            },
            onDismiss = { showFormatDialog = false }
        )
    }
    
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除规则集",
            message = "确定要删除规则集 \"$tag\" 吗？",
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
                text = if (initialRuleSet == null) "添加规则集" else "编辑规则集",
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
                    label = "标识 (Tag)",
                    value = tag,
                    onValueChange = { tag = it },
                    placeholder = "geoip-cn"
                )

                ClickableDropdownField(
                    label = "类型",
                    value = type.displayName,
                    onClick = { showTypeDialog = true }
                )

                ClickableDropdownField(
                    label = "格式",
                    value = format,
                    onClick = { showFormatDialog = true }
                )

                if (type == RuleSetType.REMOTE) {
                    StyledTextField(
                        label = "URL",
                        value = url,
                        onValueChange = { url = it },
                        placeholder = "https://example.com/rules.srs"
                    )
                } else {
                    StyledTextField(
                        label = "本地路径",
                        value = path,
                        onValueChange = { path = it },
                        placeholder = "/path/to/rules.srs"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newRuleSet = initialRuleSet?.copy(
                        tag = tag,
                        type = type,
                        format = format,
                        url = url,
                        path = path
                    ) ?: RuleSet(
                        tag = tag,
                        type = type,
                        format = format,
                        url = url,
                        path = path
                    )
                    onConfirm(newRuleSet)
                },
                enabled = tag.isNotBlank() && (if (type == RuleSetType.REMOTE) url.isNotBlank() else path.isNotBlank())
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (initialRuleSet != null && onDelete != null) {
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