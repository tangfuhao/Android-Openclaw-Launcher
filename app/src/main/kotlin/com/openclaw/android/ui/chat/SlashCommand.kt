package com.openclaw.android.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Registry of slash commands available in the Android app.
 *
 * Execution strategies:
 * - GATEWAY: sent as text via chat.send, Gateway handles natively
 * - SESSION_LIFECYCLE: calls WS method + clears local UI state
 * - CLIENT_ONLY: handled entirely on the client, never sent to Gateway
 */
data class SlashCommand(
    val name: String,
    val label: String,
    val icon: ImageVector,
    val description: String,
    val category: CommandCategory,
    val strategy: ExecutionStrategy,
    val hasArgs: Boolean = false,
    val argHint: String? = null,
)

enum class CommandCategory { QUICK_ACTION, SESSION, SETTINGS }

enum class ExecutionStrategy { GATEWAY, SESSION_LIFECYCLE, CLIENT_ONLY }

val SLASH_COMMANDS: List<SlashCommand> = listOf(
    // Quick Actions
    SlashCommand("/status", "Status", Icons.Default.Info, "View current session status", CommandCategory.QUICK_ACTION, ExecutionStrategy.GATEWAY),
    SlashCommand("/help", "Help", Icons.AutoMirrored.Filled.Help, "Show available commands", CommandCategory.QUICK_ACTION, ExecutionStrategy.GATEWAY),
    SlashCommand("/stop", "Stop", Icons.Default.Stop, "Interrupt the current run", CommandCategory.QUICK_ACTION, ExecutionStrategy.GATEWAY),

    // Session
    SlashCommand("/reset", "Reset", Icons.Default.Refresh, "Reset session (clear context)", CommandCategory.SESSION, ExecutionStrategy.SESSION_LIFECYCLE),
    SlashCommand("/new", "New Session", Icons.Default.Refresh, "Start a new conversation", CommandCategory.SESSION, ExecutionStrategy.SESSION_LIFECYCLE),
    SlashCommand("/compact", "Compact", Icons.Default.Compress, "Summarize and compress history", CommandCategory.SESSION, ExecutionStrategy.GATEWAY),
    SlashCommand("/clear", "Clear UI", Icons.Default.Delete, "Clear message list (local only)", CommandCategory.SESSION, ExecutionStrategy.CLIENT_ONLY),

    // Settings
    SlashCommand("/think", "Think Level", Icons.Default.Psychology, "Set thinking level", CommandCategory.SETTINGS, ExecutionStrategy.GATEWAY, hasArgs = true, argHint = "off|low|medium|high"),
    SlashCommand("/model", "Model", Icons.Default.Memory, "View or switch model", CommandCategory.SETTINGS, ExecutionStrategy.GATEWAY, hasArgs = true, argHint = "model name"),
    SlashCommand("/verbose", "Verbose", Icons.Default.Visibility, "Toggle verbose mode", CommandCategory.SETTINGS, ExecutionStrategy.GATEWAY, hasArgs = true, argHint = "on|off"),
    SlashCommand("/usage", "Usage", Icons.Default.Tune, "Show token usage", CommandCategory.SETTINGS, ExecutionStrategy.GATEWAY),
    SlashCommand("/queue", "Queue Mode", Icons.Default.Queue, "Change queue mode", CommandCategory.SETTINGS, ExecutionStrategy.GATEWAY, hasArgs = true, argHint = "collect|steer|interrupt"),
)

fun filterCommands(input: String): List<SlashCommand> {
    if (!input.startsWith("/")) return emptyList()
    val query = input.lowercase()
    return SLASH_COMMANDS.filter { it.name.startsWith(query) }
}
