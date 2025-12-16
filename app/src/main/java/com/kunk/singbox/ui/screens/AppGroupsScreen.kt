package com.kunk.singbox.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.theme.*
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGroupsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<AppGroup?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AppGroup?>(null) }

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
                .filter { app -> app.packageName != context.packageName }
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
        AppGroupEditorDialog(
            installedApps = installedApps,
            nodes = nodes,
            onDismiss = { showAddDialog = false },
            onConfirm = { group ->
                settingsViewModel.addAppGroup(group)
                showAddDialog = false
            }
        )
    }

    if (editingGroup != null) {
        AppGroupEditorDialog(
            initialGroup = editingGroup,
            installedApps = installedApps,
            nodes = nodes,
            onDismiss = { editingGroup = null },
            onConfirm = { group ->
                settingsViewModel.updateAppGroup(group)
                editingGroup = null
            }
        )
    }

    if (showDeleteConfirm != null) {
        ConfirmDialog(
            title = "删除分组",
            message = "确定要删除 \"${showDeleteConfirm?.name}\" 分组吗？包含 ${showDeleteConfirm?.apps?.size ?: 0} 个应用。",
            confirmText = "删除",
            onConfirm = {
                settingsViewModel.deleteAppGroup(showDeleteConfirm!!.id)
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
                        Icon(Icons.Rounded.Add, contentDescription = "添加分组", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 说明卡片
                item {
                    StandardCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "创建分组，将多个应用归类，统一设置代理规则",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                if (settings.appGroups.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = Neutral500,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("暂无分组", color = TextSecondary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("点击右上角 + 创建分组", color = Neutral500, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    items(settings.appGroups) { group ->
                        val nodeName = group.specificNodeId?.let { nodeId ->
                            nodes.find { it.id == nodeId }?.name
                        }
                        AppGroupCard(
                            group = group,
                            nodeName = nodeName,
                            onClick = { editingGroup = group },
                            onToggle = { settingsViewModel.toggleAppGroupEnabled(group.id) },
                            onDelete = { showDeleteConfirm = group }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppGroupCard(
    group: AppGroup,
    nodeName: String?,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val (outboundIcon, color) = when (group.outbound) {
        OutboundTag.PROXY -> Icons.Rounded.Shield to Color(0xFF4CAF50)
        OutboundTag.DIRECT -> Icons.Rounded.Public to Color(0xFF2196F3)
        OutboundTag.BLOCK -> Icons.Rounded.Block to Color(0xFFFF5252)
    }

    StandardCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp)
        ) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(outboundIcon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (group.enabled) TextPrimary else TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = buildString {
                            append(group.outbound.displayName)
                            if (group.outbound == OutboundTag.PROXY && nodeName != null) {
                                append(" → $nodeName")
                            }
                            append(" • ${group.apps.size} 个应用")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                }
                Switch(
                    checked = group.enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PureWhite,
                        checkedTrackColor = color,
                        uncheckedThumbColor = Neutral500,
                        uncheckedTrackColor = Neutral700
                    )
                )
            }

            // 应用图标预览
            if (group.apps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(group.apps.take(8)) { app ->
                        AppIconSmall(packageName = app.packageName)
                    }
                    if (group.apps.size > 8) {
                        item {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Neutral700),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+${group.apps.size - 8}", fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppIconSmall(packageName: String) {
    val context = LocalContext.current
    val appIcon = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName).toBitmap(128, 128).asImageBitmap()
        } catch (e: Exception) { null }
    }
    
    if (appIcon != null) {
        Image(
            bitmap = appIcon,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Neutral700),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Apps, contentDescription = null, tint = Neutral500, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGroupEditorDialog(
    initialGroup: AppGroup? = null,
    installedApps: List<InstalledApp>,
    nodes: List<NodeUi>,
    onDismiss: () -> Unit,
    onConfirm: (AppGroup) -> Unit
) {
    var groupName by remember { mutableStateOf(initialGroup?.name ?: "") }
    var outbound by remember { mutableStateOf(initialGroup?.outbound ?: OutboundTag.PROXY) }
    var selectedNodeId by remember { mutableStateOf(initialGroup?.specificNodeId) }
    var selectedApps by remember { mutableStateOf(initialGroup?.apps?.toSet() ?: emptySet()) }
    
    var showAppSelector by remember { mutableStateOf(false) }
    var showNodePicker by remember { mutableStateOf(false) }
    var showOutboundDialog by remember { mutableStateOf(false) }

    if (showAppSelector) {
        MultiAppSelectorDialog(
            installedApps = installedApps,
            selectedApps = selectedApps,
            onConfirm = { apps ->
                selectedApps = apps
                showAppSelector = false
            },
            onDismiss = { showAppSelector = false }
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

    if (showOutboundDialog) {
        OutboundSelectorDialog(
            currentOutbound = outbound,
            onSelect = { selected ->
                outbound = selected
                if (outbound != OutboundTag.PROXY) selectedNodeId = null
                showOutboundDialog = false
            },
            onDismiss = { showOutboundDialog = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = {
            Text(
                text = if (initialGroup == null) "创建分组" else "编辑分组",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StyledTextField(
                    label = "分组名称",
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = "例如：社交应用、游戏"
                )

                ClickableDropdownField(
                    label = "出站方式",
                    value = outbound.displayName,
                    onClick = { showOutboundDialog = true }
                )

                if (outbound == OutboundTag.PROXY && nodes.isNotEmpty()) {
                    val nodeName = selectedNodeId?.let { id ->
                        nodes.find { it.id == id }?.name
                    } ?: "默认节点（跟随全局）"
                    ClickableDropdownField(
                        label = "指定代理节点",
                        value = nodeName,
                        onClick = { showNodePicker = true }
                    )
                }

                // 选择应用
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("应用 (${selectedApps.size})", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { showAppSelector = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("选择应用")
                        }
                    }
                    
                    if (selectedApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Neutral700.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("点击上方按钮选择应用", color = Neutral500, fontSize = 13.sp)
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(selectedApps.toList()) { app ->
                                SelectedAppChip(app = app, onRemove = { selectedApps = selectedApps - app })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val group = initialGroup?.copy(
                        name = groupName,
                        apps = selectedApps.toList(),
                        outbound = outbound,
                        specificNodeId = if (outbound == OutboundTag.PROXY) selectedNodeId else null
                    ) ?: AppGroup(
                        name = groupName,
                        apps = selectedApps.toList(),
                        outbound = outbound,
                        specificNodeId = if (outbound == OutboundTag.PROXY) selectedNodeId else null
                    )
                    onConfirm(group)
                },
                enabled = groupName.isNotBlank() && selectedApps.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = PureWhite),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存", fontWeight = FontWeight.Bold)
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
fun SelectedAppChip(app: AppInfo, onRemove: () -> Unit) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
        } catch (e: Exception) { null }
    }
    
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Neutral700)
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(app.appName, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Rounded.Close,
            contentDescription = "移除",
            tint = Neutral500,
            modifier = Modifier.size(16.dp).clickable(onClick = onRemove)
        )
    }
}

@Composable
fun OutboundSelectorDialog(
    currentOutbound: OutboundTag,
    onSelect: (OutboundTag) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = { Text("选择出站方式", fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutboundTag.entries.forEach { tag ->
                    val (icon, color) = when (tag) {
                        OutboundTag.PROXY -> Icons.Rounded.Shield to Color(0xFF4CAF50)
                        OutboundTag.DIRECT -> Icons.Rounded.Public to Color(0xFF2196F3)
                        OutboundTag.BLOCK -> Icons.Rounded.Block to Color(0xFFFF5252)
                    }
                    val isSelected = tag == currentOutbound
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) color.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { onSelect(tag) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tag.displayName, color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(
                                when (tag) {
                                    OutboundTag.PROXY -> "通过代理节点访问"
                                    OutboundTag.DIRECT -> "直接连接，不使用代理"
                                    OutboundTag.BLOCK -> "阻止联网"
                                },
                                fontSize = 12.sp,
                                color = Neutral500
                            )
                        }
                        if (isSelected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAppSelectorDialog(
    installedApps: List<InstalledApp>,
    selectedApps: Set<AppInfo>,
    onConfirm: (Set<AppInfo>) -> Unit,
    onDismiss: () -> Unit
) {
    var tempSelected by remember { mutableStateOf(selectedApps) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val filteredApps = remember(installedApps, searchQuery, showSystemApps) {
        installedApps.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = showSystemApps || !app.isSystemApp
            matchesSearch && matchesFilter
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = {
            Column {
                Text("选择应用", fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("已选择 ${tempSelected.size} 个应用", fontSize = 12.sp, color = Neutral500)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用...", color = Neutral500) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Neutral500) },
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
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示系统应用", fontSize = 13.sp, color = TextSecondary)
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureWhite,
                            checkedTrackColor = Color(0xFF4CAF50),
                            uncheckedThumbColor = Neutral500,
                            uncheckedTrackColor = Neutral700
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filteredApps) { app ->
                        val appInfo = AppInfo(app.packageName, app.appName)
                        val isSelected = tempSelected.any { it.packageName == app.packageName }
                        SelectableAppItem(
                            app = app,
                            isSelected = isSelected,
                            onClick = {
                                tempSelected = if (isSelected) {
                                    tempSelected.filter { it.packageName != app.packageName }.toSet()
                                } else {
                                    tempSelected + appInfo
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(tempSelected) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = PureWhite),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("确定 (${tempSelected.size})", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

@Composable
fun SelectableAppItem(
    app: InstalledApp,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(144, 144).asImageBitmap()
        } catch (e: Exception) { null }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF4CAF50),
                uncheckedColor = Neutral500,
                checkmarkColor = PureWhite
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = app.appName,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Icon(Icons.Rounded.Apps, contentDescription = null, tint = Neutral500, modifier = Modifier.size(36.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            Text(app.packageName, fontSize = 11.sp, color = Neutral500, maxLines = 1)
        }
        if (app.isSystemApp) {
            Text("系统", fontSize = 10.sp, color = Neutral500)
        }
    }
}
