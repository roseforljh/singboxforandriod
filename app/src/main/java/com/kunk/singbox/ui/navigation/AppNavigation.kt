package com.kunk.singbox.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.kunk.singbox.ui.screens.DashboardScreen
import com.kunk.singbox.ui.screens.NodesScreen
import com.kunk.singbox.ui.screens.ProfilesScreen
import com.kunk.singbox.ui.screens.SettingsScreen
// New Screens
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
import com.kunk.singbox.ui.screens.RuleSetHubScreen
import com.kunk.singbox.ui.screens.RuleSetRoutingScreen

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Dashboard : Screen("dashboard")
    object Nodes : Screen("nodes")
    object Profiles : Screen("profiles")
    object Settings : Screen("settings")
    
    // Details & Wizards
    object ProfileEditor : Screen("profile_editor")
    object NodeDetail : Screen("node_detail/{nodeId}") {
        fun createRoute(nodeId: String) = "node_detail/$nodeId"
    }
    object RoutingSettings : Screen("routing_settings")
    object DnsSettings : Screen("dns_settings")
    object TunSettings : Screen("tun_settings")
    object Diagnostics : Screen("diagnostics")
    object Logs : Screen("logs")
    object ConnectionSettings : Screen("connection_settings")
    object RuleSets : Screen("rule_sets")
    object CustomRules : Screen("custom_rules")
    object AppRules : Screen("app_rules")
    object RuleSetHub : Screen("rule_set_hub")
    object RuleSetRouting : Screen("rule_set_routing")
}

const val NAV_ANIMATION_DURATION = 250

@Composable
fun AppNavigation(navController: NavHostController) {
    val slideSpec = tween<IntOffset>(
        durationMillis = NAV_ANIMATION_DURATION,
        easing = FastOutSlowInEasing
    )

    val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(initialOffsetX = { it }, animationSpec = slideSpec)
    }
    val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = slideSpec)
    }
    val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = slideSpec)
    }
    val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(targetOffsetX = { it }, animationSpec = slideSpec)
    }

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
            route = Screen.ProfileEditor.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { ProfileEditorScreen(navController) }

        composable(
            route = Screen.NodeDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("nodeId") { type = androidx.navigation.NavType.StringType }
            ),
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { backStackEntry ->
            val nodeId = backStackEntry.arguments?.getString("nodeId") ?: ""
            NodeDetailScreen(navController = navController, nodeId = nodeId)
        }
        composable(
            route = Screen.RoutingSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RoutingSettingsScreen(navController) }
        composable(
            route = Screen.DnsSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { DnsSettingsScreen(navController) }
        composable(
            route = Screen.TunSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { TunSettingsScreen(navController) }
        composable(
            route = Screen.Diagnostics.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { DiagnosticsScreen(navController) }
        composable(
            route = Screen.Logs.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { LogsScreen(navController) }
        composable(
            route = Screen.ConnectionSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { ConnectionSettingsScreen(navController) }
        composable(
            route = Screen.RuleSets.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RuleSetsScreen(navController) }
        composable(
            route = Screen.CustomRules.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { CustomRulesScreen(navController) }
        composable(
            route = Screen.AppRules.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { AppGroupsScreen(navController) }
        composable(
            route = Screen.RuleSetHub.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RuleSetHubScreen(navController) }
        composable(
            route = Screen.RuleSetRouting.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RuleSetRoutingScreen(navController) }
    }
}