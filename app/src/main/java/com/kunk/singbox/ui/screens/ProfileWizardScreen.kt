package com.kunk.singbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Divider
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.viewmodel.ProfilesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileWizardScreen(
    navController: NavController,
    viewModel: com.kunk.singbox.viewmodel.ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    var step by remember { mutableStateOf(1) }
    var importType by remember { mutableStateOf<ImportType?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("添加配置", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step > 1) step-- else navController.popBackStack()
                    }) {
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
        ) {
            // Step Indicator
            Text(
                text = "第 $step 步 / 共 3 步",
                style = MaterialTheme.typography.labelLarge,
                color = Neutral500,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when (step) {
                1 -> Step1SourceSelection(
                    onSelect = { type ->
                        importType = type
                        step = 2
                    }
                )
                2 -> {
                    val type = importType
                    if (type != null) {
                        Step2Details(
                            type = type,
                            name = name,
                            url = url,
                            onNameChange = { name = it },
                            onUrlChange = { url = it },
                            onNext = { step = 3 }
                        )
                    } else {
                        // 安全回退到第一步
                        step = 1
                    }
                }
                3 -> {
                    val type = importType
                    if (type != null) {
                        Step3Import(
                            name = name,
                            url = url,
                            type = type,
                            viewModel = viewModel,
                            onComplete = {
                                navController.popBackStack()
                            },
                            onBack = {
                                step = 2
                            }
                        )
                    } else {
                        step = 1
                    }
                }
            }
        }
    }
}

enum class ImportType { Subscription, File, Clipboard, QRCode }

@Composable
fun Step1SourceSelection(onSelect: (ImportType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "选择来源",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        SourceOption(Icons.Rounded.Link, "订阅链接", "从 URL 导入") { onSelect(ImportType.Subscription) }
        SourceOption(Icons.Rounded.Description, "本地文件", "从 JSON/YAML 文件导入") { onSelect(ImportType.File) }
        SourceOption(Icons.Rounded.ContentPaste, "剪贴板", "从剪贴板内容导入") { onSelect(ImportType.Clipboard) }
        SourceOption(Icons.Rounded.QrCodeScanner, "扫描二维码", "使用相机扫描") { onSelect(ImportType.QRCode) }
    }
}

@Composable
fun SourceOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    StandardCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = PureWhite, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}

@Composable
fun Step2Details(
    type: ImportType,
    name: String,
    url: String,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "配置详情",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("名称") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = PureWhite,
                unfocusedBorderColor = Divider,
                focusedLabelColor = PureWhite,
                unfocusedLabelColor = Neutral500
            )
        )

        if (type == ImportType.Subscription) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text("订阅链接") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PureWhite,
                    unfocusedBorderColor = Divider,
                    focusedLabelColor = PureWhite,
                    unfocusedLabelColor = Neutral500
                )
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Neutral800, contentColor = PureWhite),
            shape = RoundedCornerShape(25.dp)
        ) {
            Text("下一步", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun Step3Import(
    name: String,
    url: String,
    type: ImportType,
    viewModel: ProfilesViewModel,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    val importState by viewModel.importState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    
    // 使用 key 来确保只执行一次导入
    // 只在 Idle 状态时触发导入，避免重复
    LaunchedEffect(type, url, name) {
        if (importState is ProfilesViewModel.ImportState.Idle) {
            when (type) {
                ImportType.Subscription -> {
                    if (url.isNotBlank()) {
                        viewModel.importSubscription(name, url)
                    }
                }
                ImportType.Clipboard -> {
                    val content = clipboardManager.getText()?.text ?: ""
                    viewModel.importFromContent(
                        name = if (name.isBlank()) "剪贴板导入" else name,
                        content = content
                    )
                }
                else -> {
                    // No-op
                }
            }
        }
    }
    
    // 导入成功后自动返回
    LaunchedEffect(importState) {
        if (importState is ProfilesViewModel.ImportState.Success) {
            kotlinx.coroutines.delay(1500)
            viewModel.resetImportState()
            onComplete()
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "导入配置",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        
        StandardCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val state = importState) {
                    is ProfilesViewModel.ImportState.Idle -> {
                        when (type) {
                            ImportType.Subscription -> {
                                if (url.isNotBlank()) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(48.dp),
                                        color = PureWhite,
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("准备导入...", color = TextSecondary)
                                } else {
                                    Icon(
                                        Icons.Rounded.Error,
                                        contentDescription = null,
                                        tint = Color(0xFFFF6B6B),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("请输入有效的订阅链接", color = TextSecondary)
                                }
                            }
                            ImportType.Clipboard -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = PureWhite,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("正在从剪贴板导入...", color = TextSecondary)
                            }
                            else -> {
                                Icon(
                                    Icons.Rounded.Error,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6B6B),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("暂不支持此导入方式", color = TextSecondary)
                            }
                        }
                    }
                    is ProfilesViewModel.ImportState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = PureWhite,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(state.message, color = TextSecondary)
                    }
                    is ProfilesViewModel.ImportState.Success -> {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("导入成功!", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("配置: ${state.profile.name}", color = TextSecondary)
                    }
                    is ProfilesViewModel.ImportState.Error -> {
                        Icon(
                            Icons.Rounded.Error,
                            contentDescription = null,
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("导入失败", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.message, color = TextSecondary)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 显示操作按钮
        when (importState) {
            is ProfilesViewModel.ImportState.Error -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.resetImportState()
                            onBack()
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Neutral500,
                            contentColor = PureWhite
                        ),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text("返回修改", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            viewModel.resetImportState()
                            when (type) {
                                ImportType.Subscription -> viewModel.importSubscription(name, url)
                                ImportType.Clipboard -> {
                                    val content = clipboardManager.getText()?.text ?: ""
                                    viewModel.importFromContent(
                                        name = if (name.isBlank()) "剪贴板导入" else name,
                                        content = content
                                    )
                                }
                                else -> {}
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Neutral800,
                            contentColor = PureWhite
                        ),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text("重试", fontWeight = FontWeight.Bold)
                    }
                }
            }
            is ProfilesViewModel.ImportState.Success -> {
                Button(
                    onClick = {
                        viewModel.resetImportState()
                        onComplete()
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Neutral800,
                        contentColor = PureWhite
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text("完成", fontWeight = FontWeight.Bold)
                }
            }
            else -> {
                // Loading 或 Idle 状态不显示按钮
            }
        }
    }
}