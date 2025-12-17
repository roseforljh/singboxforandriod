package com.kunk.singbox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kunk.singbox.ui.navigation.NAV_ANIMATION_DURATION
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.PureWhite

private val settingsSubRoutes = setOf(
    Screen.Settings.route,
    Screen.RoutingSettings.route,
    Screen.DnsSettings.route,
    Screen.TunSettings.route,
    Screen.Diagnostics.route,
    Screen.Logs.route,
    Screen.ConnectionSettings.route,
    Screen.RuleSets.route,
    Screen.CustomRules.route,
    Screen.AppRules.route,
    Screen.RuleSetHub.route,
    Screen.RuleSetRouting.route
)

private val nodesSubRoutes = setOf(
    Screen.Nodes.route,
    Screen.NodeDetail.route
)

private val profilesSubRoutes = setOf(
    Screen.Profiles.route,
    Screen.ProfileEditor.route
)

private fun isRouteInTab(currentRoute: String?, screen: Screen): Boolean {
    return when (screen) {
        Screen.Settings -> currentRoute in settingsSubRoutes
        Screen.Nodes -> currentRoute in nodesSubRoutes || currentRoute?.startsWith("node_detail/") == true
        Screen.Profiles -> currentRoute in profilesSubRoutes
        Screen.Dashboard -> currentRoute == Screen.Dashboard.route
        else -> currentRoute == screen.route
    }
}

@Composable
fun AppNavBar(
    navController: NavController,
    onNavigationStart: () -> Unit = {}
) {
    val items = listOf(
        Screen.Dashboard,
        Screen.Nodes,
        Screen.Profiles,
        Screen.Settings
    )
    
    NavigationBar(
        containerColor = AppBackground,
        contentColor = PureWhite,
        modifier = Modifier.height(64.dp)
    ) {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        items.forEach { screen ->
            val isSelected = isRouteInTab(currentRoute, screen)
            
            val targetScale = if (screen == Screen.Profiles) {
                if (isSelected) 1.5f else 1.2f
            } else {
                if (isSelected) 1.2f else 0.9f
            }

            val scale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = tween(NAV_ANIMATION_DURATION),
                label = "iconScale"
            )

            NavigationBarItem(
                icon = { 
                    Icon(
                        imageVector = if (isSelected) {
                            when(screen) {
                                Screen.Dashboard -> Icons.Filled.Dashboard
                                Screen.Nodes -> Icons.Filled.Dns
                                Screen.Profiles -> Icons.Filled.List
                                Screen.Settings -> Icons.Filled.Settings
                                else -> Icons.Filled.Dashboard
                            }
                        } else {
                            when(screen) {
                                Screen.Dashboard -> Icons.Outlined.Dashboard
                                Screen.Nodes -> Icons.Outlined.Dns
                                Screen.Profiles -> Icons.Outlined.List
                                Screen.Settings -> Icons.Outlined.Settings
                                else -> Icons.Outlined.Dashboard
                            }
                        },
                        contentDescription = screen.route,
                        modifier = Modifier
                            .size(24.dp)
                            .scale(scale)
                    ) 
                },
                label = null,
                selected = isSelected,
                onClick = {
                    onNavigationStart()
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = false
                            inclusive = false
                        }
                        launchSingleTop = true
                        restoreState = false
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PureWhite,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = Neutral500
                )
            )
        }
    }
}
