package com.kunk.singbox.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.InstalledApp
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.Neutral700
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AppRule?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AppRule?>(null) }

    // 获取节点列表
    val configRepository = remember { ConfigRepository.getInstance(context) }
    val nodes by configRepository.nodes.collectAsState()

    // 获取已安装应用列表
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    // 过滤掉系统应用（可选）和自己
                    app.packageName != context.packageName
                }
                .map { app ->
                    InstalledApp(
                        packageName = app.packageName,
                        appName = app.loadLabel(pm).toString(),
                        isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedBy { it.appName.lowercase() }
            installedApps = apps
            isLoading = false
        }
    }

    if (showAddDialog) {
        AppRuleEditorDialog(
            installedApps = installedApps,
            existingPackages = settings.appRules.map { it.packageName }.toSet(),
            nodes = nodes,
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addAppRule(rule)
                showAddDialog = false
            }
        )
    }

    if (editingRule != null) {
        AppRuleEditorDialog(
            initialRule = editingRule,
            installedApps = installedApps,
            existingPackages = settings.appRules.filter { it.id != editingRule?.id }.map { it.packageName }.toSet(),
            nodes = nodes,
            onDismiss = { editingRule = null },
            onConfirm = { rule ->
                settingsViewModel.updateAppRule(rule)
                editingRule = null
            }
        )
    }

    if (showDeleteConfirm != null) {
        ConfirmDialog(
            title = "删除规则",
            message = "确定要删除 \"${showDeleteConfirm?.appName}\" 的分流规则吗？",
            confirmText = "删除",
            onConfirm = {
                settingsViewModel.deleteAppRule(showDeleteConfirm!!.id)
                showDeleteConfirm = null
            },
            onDismiss = { showDeleteConfirm = null }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("应用分流", color = TextPrimary) },
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
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PureWhite)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 说明卡片
                item {
                    StandardCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "为不同应用设置不同的代理规则",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                OutboundChip(OutboundTag.PROXY, "代理")
                                OutboundChip(OutboundTag.DIRECT, "直连")
                                OutboundChip(OutboundTag.BLOCK, "拦截")
                            }
                        }
                    }
                }

                if (settings.appRules.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Apps,
                                    contentDescription = null,
                                    tint = Neutral500,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "暂无应用分流规则",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "点击右上角 + 添加规则",
                                    color = Neutral500,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else {
                    items(settings.appRules) { rule ->
                        val nodeName = rule.specificNodeId?.let { nodeId ->
                            nodes.find { it.id == nodeId }?.name
                        }
                        AppRuleItem(
                            rule = rule,
                            nodeName = nodeName,
                            onClick = { editingRule = rule },
                            onToggle = { settingsViewModel.toggleAppRuleEnabled(rule.id) },
                            onDelete = { showDeleteConfirm = rule }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OutboundChip(tag: OutboundTag, label: String) {
    val (icon, color) = when (tag) {
        OutboundTag.PROXY -> Icons.Rounded.Shield to Color(0xFF4CAF50)
        OutboundTag.DIRECT -> Icons.Rounded.Public to Color(0xFF2196F3)
        OutboundTag.BLOCK -> Icons.Rounded.Block to Color(0xFFFF5252)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = color
        )
    }
}

@Composable
fun AppRuleItem(
    rule: AppRule,
    nodeName: String?,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val (outboundIcon, color) = when (rule.outbound) {
        OutboundTag.PROXY -> Icons.Rounded.Shield to Color(0xFF4CAF50)
        OutboundTag.DIRECT -> Icons.Rounded.Public to Color(0xFF2196F3)
        OutboundTag.BLOCK -> Icons.Rounded.Block to Color(0xFFFF5252)
    }
    
    // 加载应用图标
    val appIcon = remember(rule.packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(rule.packageName)
            drawable.toBitmap(160, 160).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    StandardCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = rule.appName,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Neutral700),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Apps,
                            contentDescription = null,
                            tint = Neutral500,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                // 出站类型小图标
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        outboundIcon,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (rule.enabled) TextPrimary else TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(rule.outbound.displayName)
                        if (rule.outbound == OutboundTag.PROXY && nodeName != null) {
                            append(" → $nodeName")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rule.outbound == OutboundTag.PROXY) Color(0xFF4CAF50) else Neutral500,
                    maxLines = 1
                )
            }
            
            // 删除按钮
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PureWhite,
                    checkedTrackColor = color,
                    uncheckedThumbColor = Neutral500,
                    uncheckedTrackColor = Neutral700
                )
            )
        }
    }
}

@Composable
fun AppRuleEditorDialog(
    initialRule: AppRule? = null,
    installedApps: List<InstalledApp>,
    existingPackages: Set<String>,
    nodes: List<NodeUi>,
    onDismiss: () -> Unit,
    onConfirm: (AppRule) -> Unit
) {
    var selectedApp by remember { mutableStateOf<InstalledApp?>(
        initialRule?.let { rule -> 
            InstalledApp(rule.packageName, rule.appName) 
        }
    ) }
    var outbound by remember { mutableStateOf(initialRule?.outbound ?: OutboundTag.PROXY) }
    var selectedNodeId by remember { mutableStateOf(initialRule?.specificNodeId) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showOutboundDialog by remember { mutableStateOf(false) }
    var showNodePicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            existingPackages = existingPackages,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            showSystemApps = showSystemApps,
            onShowSystemAppsChange = { showSystemApps = it },
            onSelect = { app ->
                selectedApp = app
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }

    if (showOutboundDialog) {
        val options = OutboundTag.entries.map { it.displayName }
        SingleSelectDialog(
            title = "选择出站方式",
            options = options,
            selectedIndex = OutboundTag.entries.indexOf(outbound),
            onSelect = { index ->
                outbound = OutboundTag.entries[index]
                // 如果不是代理模式，清除节点选择
                if (outbound != OutboundTag.PROXY) {
                    selectedNodeId = null
                }
                showOutboundDialog = false
            },
            onDismiss = { showOutboundDialog = false }
        )
    }

    if (showNodePicker && nodes.isNotEmpty()) {
        NodePickerDialog(
            nodes = nodes,
            selectedNodeId = selectedNodeId,
            onSelect = { nodeId ->
                selectedNodeId = nodeId
                showNodePicker = false
            },
            onDismiss = { showNodePicker = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = {
            Text(
                text = if (initialRule == null) "添加应用规则" else "编辑应用规则",
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
                // 选择应用
                ClickableDropdownField(
                    label = "选择应用",
                    value = selectedApp?.appName ?: "点击选择...",
                    onClick = { showAppPicker = true }
                )

                // 选择出站方式
                ClickableDropdownField(
                    label = "出站方式",
                    value = outbound.displayName,
                    onClick = { showOutboundDialog = true }
                )

                // 如果是代理模式，显示节点选择
                if (outbound == OutboundTag.PROXY && nodes.isNotEmpty()) {
                    val nodeName = selectedNodeId?.let { id ->
                        nodes.find { it.id == id }?.let { "${it.regionFlag ?: ""} ${it.name}".trim() }
                    } ?: "默认节点（跟随全局）"
                    
                    ClickableDropdownField(
                        label = "指定代理节点",
                        value = nodeName,
                        onClick = { showNodePicker = true }
                    )
                }

                // 出站说明
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Neutral700.copy(alpha = 0.3f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutboundExplanation(OutboundTag.PROXY, "通过代理节点访问")
                    OutboundExplanation(OutboundTag.DIRECT, "直接连接，不使用代理")
                    OutboundExplanation(OutboundTag.BLOCK, "阻止该应用联网")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedApp?.let { app ->
                        val rule = initialRule?.copy(
                            packageName = app.packageName,
                            appName = app.appName,
                            outbound = outbound,
                            specificNodeId = if (outbound == OutboundTag.PROXY) selectedNodeId else null
                        ) ?: AppRule(
                            packageName = app.packageName,
                            appName = app.appName,
                            outbound = outbound,
                            specificNodeId = if (outbound == OutboundTag.PROXY) selectedNodeId else null
                        )
                        onConfirm(rule)
                    }
                },
                enabled = selectedApp != null
            ) {
                Text("保存", color = if (selectedApp != null) PureWhite else Neutral500)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@Composable
fun OutboundExplanation(tag: OutboundTag, description: String) {
    val (icon, color) = when (tag) {
        OutboundTag.PROXY -> Icons.Rounded.Shield to Color(0xFF4CAF50)
        OutboundTag.DIRECT -> Icons.Rounded.Public to Color(0xFF2196F3)
        OutboundTag.BLOCK -> Icons.Rounded.Block to Color(0xFFFF5252)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(
            text = "${tag.displayName}: $description",
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}

@Composable
fun NodePickerDialog(
    nodes: List<NodeUi>,
    selectedNodeId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var tempSelectedId by remember { mutableStateOf(selectedNodeId) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = {
            Text(
                text = "选择代理节点",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 默认节点选项
                item {
                    val isSelected = tempSelectedId == null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { tempSelectedId = null }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Public,
                            contentDescription = null,
                            tint = if (isSelected) Color(0xFF4CAF50) else Neutral500,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "默认节点",
                                fontSize = 14.sp,
                                color = if (isSelected) TextPrimary else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "跟随全局设置",
                                fontSize = 11.sp,
                                color = Neutral500
                            )
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                
                // 节点列表
                items(nodes) { node ->
                    val isSelected = tempSelectedId == node.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { tempSelectedId = node.id }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = if (isSelected) Color(0xFF4CAF50) else Neutral500,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${node.regionFlag ?: ""} ${node.name}".trim(),
                                fontSize = 14.sp,
                                color = if (isSelected) TextPrimary else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                            Text(
                                text = node.protocol,
                                fontSize = 11.sp,
                                color = Neutral500
                            )
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelect(tempSelectedId) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50),
                    contentColor = PureWhite
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("确定", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerDialog(
    apps: List<InstalledApp>,
    existingPackages: Set<String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    showSystemApps: Boolean,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onSelect: (InstalledApp) -> Unit,
    onDismiss: () -> Unit
) {
    val filteredApps = remember(apps, searchQuery, showSystemApps, existingPackages) {
        apps.filter { app ->
            val matchesSearch = searchQuery.isBlank() || 
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = showSystemApps || !app.isSystemApp
            val notExisting = app.packageName !in existingPackages
            matchesSearch && matchesFilter && notExisting
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = {
            Text(
                text = "选择应用",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用...", color = Neutral500) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = Neutral500)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PureWhite.copy(alpha = 0.6f),
                        unfocusedBorderColor = Neutral700,
                        cursorColor = PureWhite
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 显示系统应用开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "显示系统应用",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = onShowSystemAppsChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureWhite,
                            checkedTrackColor = Color(0xFF4CAF50),
                            uncheckedThumbColor = Neutral500,
                            uncheckedTrackColor = Neutral700
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 应用列表
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredApps) { app ->
                        AppListItem(
                            app = app,
                            onClick = { onSelect(app) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@Composable
fun AppListItem(
    app: InstalledApp,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(app.packageName)
            drawable.toBitmap(144, 144).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = app.appName,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Icon(
                Icons.Rounded.Apps,
                contentDescription = null,
                tint = Neutral500,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                fontSize = 14.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = app.packageName,
                fontSize = 11.sp,
                color = Neutral500,
                maxLines = 1
            )
        }
        if (app.isSystemApp) {
            Text(
                text = "系统",
                fontSize = 10.sp,
                color = Neutral500
            )
        }
    }
}
