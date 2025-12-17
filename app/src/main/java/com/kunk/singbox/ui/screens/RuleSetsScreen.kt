package com.kunk.singbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
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
import com.kunk.singbox.ui.navigation.Screen
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.Neutral700
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.model.RuleSetOutboundMode
import kotlinx.coroutines.launch

data class DefaultRuleSetConfig(
    val tag: String,
    val description: String,
    val url: String,
    val outboundMode: RuleSetOutboundMode,
    val format: String = "binary"
)

val CHINA_DEFAULT_RULE_SETS = listOf(
    DefaultRuleSetConfig(
        tag = "geosite-cn",
        description = "中国大陆域名直连",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
        outboundMode = RuleSetOutboundMode.DIRECT
    ),
    DefaultRuleSetConfig(
        tag = "geoip-cn",
        description = "中国大陆IP直连",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
        outboundMode = RuleSetOutboundMode.DIRECT
    ),
    DefaultRuleSetConfig(
        tag = "geosite-geolocation-!cn",
        description = "非中国域名走代理",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-geolocation-!cn.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-category-ads-all",
        description = "广告域名拦截",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
        outboundMode = RuleSetOutboundMode.BLOCK
    ),
    DefaultRuleSetConfig(
        tag = "geosite-private",
        description = "私有网络直连",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-private.srs",
        outboundMode = RuleSetOutboundMode.DIRECT
    ),
    DefaultRuleSetConfig(
        tag = "geosite-apple",
        description = "苹果服务走代理",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-apple.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-youtube",
        description = "YouTube走代理",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-youtube.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-google",
        description = "Google走代理",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-telegram",
        description = "Telegram走代理",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-telegram.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-facebook",
        description = "Facebook走代理",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-facebook.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-openai",
        description = "OpenAI/ChatGPT走代理",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-openai.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-github",
        description = "GitHub走代理",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-github.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRuleSet by remember { mutableStateOf<RuleSet?>(null) }
    var showDefaultRuleSetsDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
    
    if (showDefaultRuleSetsDialog) {
        DefaultRuleSetsDialog(
            existingTags = settings.ruleSets.map { it.tag },
            onDismiss = { showDefaultRuleSetsDialog = false },
            onAdd = { configs ->
                val ruleSets = configs.map { config ->
                    RuleSet(
                        tag = config.tag,
                        type = RuleSetType.REMOTE,
                        format = config.format,
                        url = config.url,
                        outboundMode = config.outboundMode
                    )
                }
                settingsViewModel.addRuleSets(ruleSets) { addedCount ->
                    scope.launch {
                        snackbarHostState.showSnackbar("已添加 $addedCount 个规则集")
                    }
                }
                showDefaultRuleSetsDialog = false
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    IconButton(onClick = { navController.navigate(Screen.RuleSetHub.route) }) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = "导入", tint = PureWhite)
                    }
                    Box {
                        var showAddMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = "添加", tint = PureWhite)
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                            modifier = Modifier.background(Neutral700)
                        ) {
                            DropdownMenuItem(
                                text = { Text("添加规则集", color = TextPrimary) },
                                onClick = {
                                    showAddMenu = false
                                    showAddDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("默认规则", color = TextPrimary) },
                                onClick = {
                                    showAddMenu = false
                                    showDefaultRuleSetsDialog = true
                                }
                            )
                        }
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

@Composable
fun DefaultRuleSetsDialog(
    existingTags: List<String>,
    onDismiss: () -> Unit,
    onAdd: (List<DefaultRuleSetConfig>) -> Unit
) {
    val selectedItems = remember { 
        mutableStateMapOf<String, Boolean>().apply {
            CHINA_DEFAULT_RULE_SETS.forEach { config ->
                put(config.tag, !existingTags.contains(config.tag))
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = {
            Column {
                Text(
                    text = "添加默认规则集",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "适合中国大陆用户的常用规则",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(CHINA_DEFAULT_RULE_SETS) { config ->
                    val isExisting = existingTags.contains(config.tag)
                    val isSelected = selectedItems[config.tag] ?: false
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isExisting) {
                                selectedItems[config.tag] = !isSelected
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = if (isExisting) null else { isChecked -> selectedItems[config.tag] = isChecked },
                            enabled = !isExisting
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config.tag,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isExisting) TextSecondary.copy(alpha = 0.5f) else TextPrimary
                            )
                            Text(
                                text = config.description + if (isExisting) " (已添加)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary.copy(alpha = if (isExisting) 0.5f else 1f)
                            )
                        }
                        val modeText = when (config.outboundMode) {
                            RuleSetOutboundMode.DIRECT -> "直连"
                            RuleSetOutboundMode.BLOCK -> "拦截"
                            RuleSetOutboundMode.PROXY -> "代理"
                            RuleSetOutboundMode.PROFILE -> "代理"
                            else -> ""
                        }
                        Surface(
                            color = when (config.outboundMode) {
                                RuleSetOutboundMode.DIRECT -> Color(0xFF2E7D32)
                                RuleSetOutboundMode.BLOCK -> Color(0xFFC62828)
                                RuleSetOutboundMode.PROXY -> Color(0xFF1565C0)
                                RuleSetOutboundMode.PROFILE -> Color(0xFF1565C0)
                                else -> Color.Gray
                            }.copy(alpha = if (isExisting) 0.3f else 0.8f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = modeText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val selectedCount = selectedItems.count { it.value }
            TextButton(
                onClick = {
                    val selected = CHINA_DEFAULT_RULE_SETS.filter { selectedItems[it.tag] == true }
                    onAdd(selected)
                },
                enabled = selectedCount > 0
            ) {
                Text("添加 ($selectedCount)")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        CHINA_DEFAULT_RULE_SETS.forEach { config ->
                            if (!existingTags.contains(config.tag)) {
                                selectedItems[config.tag] = true
                            }
                        }
                    }
                ) {
                    Text("全选")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}