package com.openclaw.android.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.service.OpenClawService
import com.openclaw.android.ui.chat.ChatScreen
import com.openclaw.android.ui.navigation.Routes
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
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val intent = OpenClawService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
    ) {
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToTerminal = { navController.navigate(Routes.TERMINAL) },
            )
        }
        composable(Routes.TERMINAL) {
            TerminalScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
