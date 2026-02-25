package com.openclaw.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    CHAT("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
    TERMINAL("terminal", "Terminal", Icons.Default.Terminal),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}
