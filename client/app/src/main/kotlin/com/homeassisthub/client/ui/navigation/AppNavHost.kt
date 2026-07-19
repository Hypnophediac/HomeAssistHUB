package com.homeassisthub.client.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.homeassisthub.client.R
import com.homeassisthub.client.ui.dashboard.DashboardScreen
import com.homeassisthub.client.ui.settings.SettingsScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { BottomNavBar(navController) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.DASHBOARD,
            modifier = Modifier.padding(padding)
        ) {
            composable(NavRoutes.DASHBOARD) { DashboardScreen() }
            composable(NavRoutes.SETTINGS) { SettingsScreen() }
        }
    }
}

@Composable
private fun BottomNavBar(navController: androidx.navigation.NavHostController) {
    val items = listOf(
        NavRoutes.DASHBOARD to Icons.Filled.Home,
        NavRoutes.SETTINGS to Icons.Filled.Settings
    )
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        items.forEach { (route, icon) ->
            val selected = currentDestination?.hierarchy?.any { it.route == route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(icon, contentDescription = route) },
                label = { Text(labelFor(route)) }
            )
        }
    }
}

@Composable
private fun labelFor(route: String): String = when (route) {
    NavRoutes.DASHBOARD -> androidx.compose.ui.res.stringResource(R.string.nav_dashboard)
    NavRoutes.SETTINGS -> androidx.compose.ui.res.stringResource(R.string.nav_settings)
    else -> route
}
