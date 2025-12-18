package com.kunk.singbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.navigation.NavController
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.kunk.singbox.repository.FakeRepository
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.model.UpdateStatus
import com.kunk.singbox.ui.components.ProfileCard
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Divider
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.SurfaceCard
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary

@Composable
fun ProfilesScreen(
    navController: NavController,
    viewModel: com.kunk.singbox.viewmodel.ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    
    var showSearchDialog by remember { mutableStateOf(false) }
    var showImportSelection by remember { mutableStateOf(false) }
    var showSubscriptionInput by remember { mutableStateOf(false) }
    var showClipboardInput by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Handle update state feedback
    androidx.compose.runtime.LaunchedEffect(updateStatus) {
        updateStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    // Handle import state feedback
    androidx.compose.runtime.LaunchedEffect(importState) {
        when (val state = importState) {
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Success -> {
                Toast.makeText(context, "导入成功: ${state.profile.name}", Toast.LENGTH_SHORT).show()
                viewModel.resetImportState()
            }
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Error -> {
                Toast.makeText(context, "导入失败: ${state.message}", Toast.LENGTH_LONG).show()
                viewModel.resetImportState()
            }
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Loading -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
    }

    if (showImportSelection) {
        ImportSelectionDialog(
            onDismiss = { showImportSelection = false },
            onTypeSelected = { type ->
                showImportSelection = false
                when (type) {
                    ProfileImportType.Subscription -> showSubscriptionInput = true
                    ProfileImportType.Clipboard -> showClipboardInput = true
                    ProfileImportType.File -> {
                        Toast.makeText(context, "暂不支持文件导入", Toast.LENGTH_SHORT).show()
                    }
                    ProfileImportType.QRCode -> {
                        Toast.makeText(context, "暂不支持二维码扫描", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showSubscriptionInput) {
        SubscriptionInputDialog(
            onDismiss = { showSubscriptionInput = false },
            onConfirm = { name, url ->
                viewModel.importSubscription(name, url)
                showSubscriptionInput = false
            }
        )
    }

    if (showClipboardInput) {
        InputDialog(
            title = "导入剪贴板",
            placeholder = "配置名称",
            initialValue = "",
            confirmText = "导入",
            onConfirm = { name ->
                val content = clipboardManager.getText()?.text ?: ""
                if (content.isNotBlank()) {
                    viewModel.importFromContent(if (name.isBlank()) "剪贴板导入" else name, content)
                } else {
                    Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                }
                showClipboardInput = false
            },
            onDismiss = { showClipboardInput = false }
        )
    }

    if (showSearchDialog) {
        InputDialog(
            title = "搜索配置",
            placeholder = "输入关键词...",
            confirmText = "搜索",
            onConfirm = { showSearchDialog = false },
            onDismiss = { showSearchDialog = false }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showImportSelection = true },
                containerColor = PureWhite,
                contentColor = Color.Black
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Profile")
            }
        }
    ) { padding ->
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "配置管理",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                IconButton(onClick = { showSearchDialog = true }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = PureWhite)
                }
            }

            // List
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        name = profile.name,
                        type = profile.type.name,
                        isSelected = profile.id == activeProfileId,
                        isEnabled = profile.enabled,
                        isUpdating = profile.updateStatus == UpdateStatus.Updating,
                        onClick = { viewModel.setActiveProfile(profile.id) },
                        onUpdate = {
                            viewModel.updateProfile(profile.id)
                        },
                        onToggle = {
                            viewModel.toggleProfileEnabled(profile.id)
                        },
                        onEdit = {
                            navController.navigate(Screen.ProfileEditor.route)
                        },
                        onDelete = {
                            viewModel.deleteProfile(profile.id)
                        }
                    )
                }
            }
        }
    }
}

private enum class ProfileImportType { Subscription, File, Clipboard, QRCode }

@Composable
private fun ImportSelectionDialog(
    onDismiss: () -> Unit,
    onTypeSelected: (ProfileImportType) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImportOptionCard(
                icon = Icons.Rounded.Link,
                title = "订阅链接",
                subtitle = "从 URL 导入",
                onClick = { onTypeSelected(ProfileImportType.Subscription) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.Description,
                title = "本地文件",
                subtitle = "从 JSON/YAML 文件导入",
                onClick = { onTypeSelected(ProfileImportType.File) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.ContentPaste,
                title = "剪贴板",
                subtitle = "从剪贴板内容导入",
                onClick = { onTypeSelected(ProfileImportType.Clipboard) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.QrCodeScanner,
                title = "扫描二维码",
                subtitle = "使用相机扫描",
                onClick = { onTypeSelected(ProfileImportType.QRCode) }
            )
        }
    }
}

@Composable
private fun ImportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    StandardCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SubscriptionInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "添加订阅",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PureWhite,
                    unfocusedBorderColor = Divider,
                    focusedLabelColor = PureWhite,
                    unfocusedLabelColor = Neutral500
                )
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("订阅链接") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PureWhite,
                    unfocusedBorderColor = Divider,
                    focusedLabelColor = PureWhite,
                    unfocusedLabelColor = Neutral500
                )
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { onConfirm(name, url) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Color.Black),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text("确定", fontWeight = FontWeight.Bold, color = Color.Black)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Neutral500)
            ) {
                Text("取消")
            }
        }
    }
}