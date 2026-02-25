package com.openclaw.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.ui.chat.ChatScreen
import com.openclaw.android.ui.navigation.Screen
import com.openclaw.android.ui.settings.SettingsScreen
import com.openclaw.android.ui.setup.SetupWizardScreen
import com.openclaw.android.ui.terminal.TerminalScreen

@Composable
fun MainScreen(
    preferencesManager: PreferencesManager = hiltViewModel<MainViewModel>().preferencesManager,
) {
    val setupCompleted by preferencesManager.isSetupCompleted.collectAsStateWithLifecycle(initialValue = null)

    when (setupCompleted) {
        null -> { /* Loading */ }
        false -> SetupWizardScreen(onSetupComplete = { /* navigates internally */ })
        true -> MainContent()
    }
}

@Composable
private fun MainContent() {
    val navController = rememberNavController()
    val screens = listOf(Screen.CHAT, Screen.TERMINAL, Screen.SETTINGS)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.CHAT.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.CHAT.route) { ChatScreen() }
            composable(Screen.TERMINAL.route) { TerminalScreen() }
            composable(Screen.SETTINGS.route) { SettingsScreen() }
        }
    }
}
