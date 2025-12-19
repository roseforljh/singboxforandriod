package com.kunk.singbox

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.viewmodel.DashboardViewModel
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.ui.components.AppNavBar
import com.kunk.singbox.ui.navigation.AppNavigation
import com.kunk.singbox.ui.navigation.NAV_ANIMATION_DURATION
import com.kunk.singbox.ui.theme.OLEDBlack
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.SingBoxTheme
import android.content.ComponentName
import android.service.quicksettings.TileService
import androidx.work.WorkManager
import com.kunk.singbox.worker.RuleSetUpdateWorker
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前启用边到边显示
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            SingBoxApp()
        }

        cancelRuleSetUpdateWork()
    }

    private fun cancelRuleSetUpdateWork() {
        WorkManager.getInstance(this).cancelUniqueWork(RuleSetUpdateWorker.WORK_NAME)
    }
}

@Composable
fun SingBoxApp() {
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            Log.d("SingBoxActivity", "POST_NOTIFICATIONS permission result: $isGranted")
        }
    )

    LaunchedEffect(Unit) {
        // Best-effort: ask system to refresh QS tile state after app process restarts/force-stops.
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            Log.d("SingBoxActivity", "POST_NOTIFICATIONS permission status: $permission (Granted: ${permission == PackageManager.PERMISSION_GRANTED})")
            
            if (permission != PackageManager.PERMISSION_GRANTED) {
                Log.d("SingBoxActivity", "Requesting POST_NOTIFICATIONS permission")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val dashboardViewModel: DashboardViewModel = viewModel()
    val connectionState by dashboardViewModel.connectionState.collectAsState()

    // 自动连接逻辑
    LaunchedEffect(settings?.autoConnect, connectionState) {
        if (settings?.autoConnect == true && 
            connectionState == ConnectionState.Idle && 
            !SingBoxService.isRunning &&
            !SingBoxService.isStarting &&
            !SingBoxService.isManuallyStopped
        ) {
            // Delay a bit to ensure everything is initialized
            delay(1000)
            if (connectionState == ConnectionState.Idle && !SingBoxService.isRunning) {
                dashboardViewModel.toggleConnection()
            }
        }
    }

    // 在最近任务中隐藏逻辑
    LaunchedEffect(settings?.excludeFromRecent) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.appTasks?.forEach { 
            it.setExcludeFromRecents(settings?.excludeFromRecent == true)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        SettingsRepository.restartRequiredEvents.collect {
            if (snackbarHostState.currentSnackbarData != null) return@collect

            snackbarHostState.showSnackbar(
                message = "设置已修改，重启后生效",
                duration = SnackbarDuration.Indefinite
            )
        }
    }

        SingBoxTheme {
            val navController = rememberNavController()
            var isNavigating by remember { mutableStateOf(false) }
            var navigationStartTime by remember { mutableStateOf(0L) }
            
            // Get current destination
            val navBackStackEntry = navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry.value?.destination?.route
            val showBottomBar = currentRoute != null

            // Reset isNavigating after animation completes
        LaunchedEffect(navigationStartTime) {
            if (navigationStartTime > 0) {
                delay(NAV_ANIMATION_DURATION.toLong() + 50)
                isNavigating = false
            }
        }
        
        fun startNavigation() {
            isNavigating = true
            navigationStartTime = System.currentTimeMillis()
        }
        
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        snackbar = { data ->
                            Surface(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .heightIn(min = 52.dp)
                                    .shadow(6.dp, RoundedCornerShape(12.dp)),
                                color = PureWhite,
                                contentColor = Color.Black,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = data.visuals.message,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = Color.Black,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Text(
                                        text = "重启",
                                        modifier = Modifier
                                            .heightIn(min = 24.dp)
                                            .clickable {
                                                data.dismiss()
                                                if (SingBoxService.isRunning || SingBoxService.isStarting) {
                                                    dashboardViewModel.restartVpn()
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF00C853)
                                    )
                                }
                            }
                        }
                    )
                },
                bottomBar = { 
                    if (showBottomBar) {
                        AppNavBar(
                            navController = navController,
                            onNavigationStart = { startNavigation() }
                        )
                    }
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0) // 不自动添加系统栏 insets
            ) { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()) // 只应用底部 padding
                ) {
                    AppNavigation(navController)
                }
            }

            // Global loading overlay during navigation
            AnimatedVisibility(
                visible = isNavigating,
                enter = EnterTransition.None,
                exit = ExitTransition.None
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OLEDBlack.copy(alpha = 0.3f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter),
                        color = PureWhite,
                        trackColor = Color.Transparent,
                        strokeCap = StrokeCap.Round
                    )
                }
            }
        }
    }
}