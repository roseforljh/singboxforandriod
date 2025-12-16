package com.kunk.singbox.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.PureWhite

@Composable
fun AppNavBar(navController: NavController) {
    val items = listOf(
        Screen.Dashboard,
        Screen.Nodes,
        Screen.Profiles,
        Screen.Settings
    )
    
    NavigationBar(
        containerColor = AppBackground,
        contentColor = PureWhite,
        modifier = Modifier.height(64.dp) // Reduced height
    ) {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        items.forEach { screen ->
            val isSelected = currentRoute == screen.route
            
            // Animation for icon scale
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.2f else 0.9f,
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
                label = null, // Removed text label
                selected = isSelected,
                onClick = {
                    navController.navigate(screen.route) {
                        // Pop up to the start destination of the graph to
                        // avoid building up a large stack of destinations
                        // on the back stack as users select items
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        // Avoid multiple copies of the same destination when
                        // reselecting the same item
                        launchSingleTop = true
                        // Restore state when reselecting a previously selected item
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PureWhite,
                    indicatorColor = Color.Transparent, // No pill indicator
                    unselectedIconColor = Neutral500
                )
            )
        }
    }
}