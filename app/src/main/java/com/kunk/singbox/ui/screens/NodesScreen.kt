package com.kunk.singbox.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.NodeCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodesScreen(
    navController: NavController,
    viewModel: NodesViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val nodes by viewModel.nodes.collectAsState()
    val activeNodeId by viewModel.activeNodeId.collectAsState()
    val groups by viewModel.nodeGroups.collectAsState()
    
    var selectedGroupIndex by remember { mutableStateOf(0) }
    val isTesting by viewModel.isTesting.collectAsState()
    
    // 当groups变化时重置索引，避免越界
    LaunchedEffect(groups) {
        if (selectedGroupIndex >= groups.size) {
            selectedGroupIndex = 0
        }
    }
    
    // Filter nodes based on selected group
    val filteredNodes = remember(nodes, groups, selectedGroupIndex) {
        if (selectedGroupIndex == 0 || groups.isEmpty()) {
            nodes
        } else {
            val selectedGroup = groups.getOrNull(selectedGroupIndex) ?: return@remember nodes
            nodes.filter { it.group == selectedGroup }
        }
    }
    
    var showSearchDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showMenuDialog by remember { mutableStateOf(false) }
    var selectedNodeName by remember { mutableStateOf("") }

    if (showSearchDialog) {
        InputDialog(
            title = "搜索节点",
            placeholder = "输入关键词...",
            confirmText = "搜索",
            onConfirm = { showSearchDialog = false },
            onDismiss = { showSearchDialog = false }
        )
    }
    
    if (showSortDialog) {
        ConfirmDialog(
            title = "排序节点",
            message = "选择排序方式:\n\n• 延迟 (低 -> 高)\n• 名称 (A -> Z)\n• 地区",
            confirmText = "确定",
            onConfirm = { showSortDialog = false },
            onDismiss = { showSortDialog = false }
        )
    }
    
    if (showMenuDialog) {
        ConfirmDialog(
            title = selectedNodeName,
            message = "选择操作:\n\n• 查看详情\n• 测速\n• 分享节点",
            confirmText = "查看详情",
            onConfirm = {
                showMenuDialog = false
                navController.navigate(Screen.NodeDetail.route)
            },
            onDismiss = { showMenuDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.background(AppBackground),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!isTesting) {
                        viewModel.testAllLatency()
                    }
                },
                containerColor = PureWhite,
                contentColor = Color.Black
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = "测试延迟"
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 1. Top Bar (Search & Filter)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "节点列表",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                Row {
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search", tint = PureWhite)
                    }
                    IconButton(onClick = { showSortDialog = true }) {
                        Icon(Icons.Rounded.Sort, contentDescription = "Sort", tint = PureWhite)
                    }
                }
            }

            // 2. Group Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedGroupIndex,
                contentColor = PureWhite,
                modifier = Modifier.background(AppBackground),
                edgePadding = 16.dp,
                divider = {},
                indicator = {} // Custom indicator or none for minimalist look
            ) {
                groups.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedGroupIndex == index,
                        onClick = { selectedGroupIndex = index },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedGroupIndex == index) PureWhite else Neutral500,
                                fontWeight = if (selectedGroupIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // 3. Node List
            LazyColumn(
                contentPadding = PaddingValues(bottom = 88.dp, top = 16.dp, start = 16.dp, end = 16.dp), // Add bottom padding for FAB
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredNodes, key = { it.id }) { node ->
                    NodeCard(
                        name = "${node.regionFlag ?: ""} ${node.name}",
                        type = node.protocol,
                        latency = node.latencyMs,
                        isSelected = node.id == activeNodeId,
                        onClick = { viewModel.setActiveNode(node.id) },
                        onMenuClick = {
                            selectedNodeName = node.name
                            showMenuDialog = true
                        }
                    )
                }
            }
        }
    }
}