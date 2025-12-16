package com.kunk.singbox.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.kunk.singbox.ui.screens.DashboardScreen
import com.kunk.singbox.ui.screens.NodesScreen
import com.kunk.singbox.ui.screens.ProfilesScreen
import com.kunk.singbox.ui.screens.SettingsScreen
// New Screens
import com.kunk.singbox.ui.screens.ProfileWizardScreen
import com.kunk.singbox.ui.screens.ProfileEditorScreen
import com.kunk.singbox.ui.screens.NodeDetailScreen
import com.kunk.singbox.ui.screens.RoutingSettingsScreen
import com.kunk.singbox.ui.screens.DnsSettingsScreen
import com.kunk.singbox.ui.screens.TunSettingsScreen
import com.kunk.singbox.ui.screens.DiagnosticsScreen
import com.kunk.singbox.ui.screens.ConnectionSettingsScreen
import com.kunk.singbox.ui.screens.LogsScreen
import com.kunk.singbox.ui.screens.RuleSetsScreen
import com.kunk.singbox.ui.screens.CustomRulesScreen
import com.kunk.singbox.ui.screens.AppRulesScreen
import com.kunk.singbox.ui.screens.AppGroupsScreen
import com.kunk.singbox.ui.screens.SplashScreen

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Dashboard : Screen("dashboard")
    object Nodes : Screen("nodes")
    object Profiles : Screen("profiles")
    object Settings : Screen("settings")
    
    // Details & Wizards
    object ProfileWizard : Screen("profile_wizard")
    object ProfileEditor : Screen("profile_editor")
    object NodeDetail : Screen("node_detail")
    object RoutingSettings : Screen("routing_settings")
    object DnsSettings : Screen("dns_settings")
    object TunSettings : Screen("tun_settings")
    object Diagnostics : Screen("diagnostics")
    object Logs : Screen("logs")
    object ConnectionSettings : Screen("connection_settings")
    object RuleSets : Screen("rule_sets")
    object CustomRules : Screen("custom_rules")
    object AppRules : Screen("app_rules")
}

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route
    ) {
        // Main Tabs
        composable(Screen.Dashboard.route) { DashboardScreen(navController) }
        composable(Screen.Nodes.route) { NodesScreen(navController) }
        composable(Screen.Profiles.route) { ProfilesScreen(navController) }
        composable(Screen.Settings.route) { SettingsScreen(navController) }
        
        // Sub Screens
        composable(
            route = Screen.ProfileWizard.route,
            enterTransition = { androidx.compose.animation.slideInHorizontally(initialOffsetX = { 1000 }, animationSpec = androidx.compose.animation.core.tween(300)) },
            exitTransition = { androidx.compose.animation.slideOutHorizontally(targetOffsetX = { -1000 }, animationSpec = androidx.compose.animation.core.tween(300)) },
            popEnterTransition = { androidx.compose.animation.slideInHorizontally(initialOffsetX = { -1000 }, animationSpec = androidx.compose.animation.core.tween(300)) },
            popExitTransition = { androidx.compose.animation.slideOutHorizontally(targetOffsetX = { 1000 }, animationSpec = androidx.compose.animation.core.tween(300)) }
        ) { ProfileWizardScreen(navController) }
        
        composable(
            route = Screen.ProfileEditor.route,
            enterTransition = { androidx.compose.animation.slideInHorizontally(initialOffsetX = { 1000 }, animationSpec = androidx.compose.animation.core.tween(300)) },
            exitTransition = { androidx.compose.animation.slideOutHorizontally(targetOffsetX = { -1000 }, animationSpec = androidx.compose.animation.core.tween(300)) },
            popEnterTransition = { androidx.compose.animation.slideInHorizontally(initialOffsetX = { -1000 }, animationSpec = androidx.compose.animation.core.tween(300)) },
            popExitTransition = { androidx.compose.animation.slideOutHorizontally(targetOffsetX = { 1000 }, animationSpec = androidx.compose.animation.core.tween(300)) }
        ) { ProfileEditorScreen(navController) }
        
        composable(Screen.NodeDetail.route) { NodeDetailScreen(navController) }
        composable(Screen.RoutingSettings.route) { RoutingSettingsScreen(navController) }
        composable(Screen.DnsSettings.route) { DnsSettingsScreen(navController) }
        composable(Screen.TunSettings.route) { TunSettingsScreen(navController) }
        composable(Screen.Diagnostics.route) { DiagnosticsScreen(navController) }
        composable(Screen.Logs.route) { LogsScreen(navController) }
        composable(Screen.ConnectionSettings.route) { ConnectionSettingsScreen(navController) }
        composable(Screen.RuleSets.route) { RuleSetsScreen(navController) }
        composable(Screen.CustomRules.route) { CustomRulesScreen(navController) }
        composable(Screen.AppRules.route) { AppGroupsScreen(navController) }
    }
}