package com.kunk.singbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.kunk.singbox.ui.navigation.AppNavigation
import com.kunk.singbox.ui.navigation.NAV_ANIMATION_DURATION
import com.kunk.singbox.ui.theme.SingBoxTheme
import com.kunk.singbox.ui.components.AppNavBar
import com.kunk.singbox.ui.theme.OLEDBlack
import com.kunk.singbox.ui.theme.PureWhite
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
    }
}

@Composable
fun SingBoxApp() {
    SingBoxTheme {
        val navController = rememberNavController()
        var isNavigating by remember { mutableStateOf(false) }
        var navigationStartTime by remember { mutableStateOf(0L) }

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
                bottomBar = { 
                    AppNavBar(
                        navController = navController,
                        onNavigationStart = { startNavigation() }
                    ) 
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
                enter = fadeIn(animationSpec = tween(50)),
                exit = fadeOut(animationSpec = tween(50))
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