package com.openclaw.android.core

import java.io.File

/**
 * Central constants for the embedded Linux environment and OpenClaw gateway.
 * All filesystem paths are derived from the app's private data directory at runtime.
 */
object OpenClawConstants {

    const val PACKAGE_NAME = "com.openclaw.android"

    // --- Gateway ---
    const val GATEWAY_HOST = "127.0.0.1"
    const val GATEWAY_PORT = 18789
    const val GATEWAY_WS_PATH = "/"
    val GATEWAY_WS_URL get() = "ws://$GATEWAY_HOST:$GATEWAY_PORT$GATEWAY_WS_PATH"
    const val GATEWAY_PROTOCOL_VERSION = 3

    // --- Bootstrap ---
    const val BOOTSTRAP_FILE_NAME = "bootstrap-aarch64.tar.gz"
    const val BOOTSTRAP_VERSION = "0.1.0"

    // --- Process ---
    const val NODE_BINARY = "bin/node"
    const val OPENCLAW_ENTRY = "lib/node_modules/openclaw/bin/openclaw.js"
    const val SHELL_BINARY = "bin/bash"

    // --- Foreground Service ---
    const val SERVICE_NOTIFICATION_CHANNEL_ID = "openclaw_service"
    const val SERVICE_NOTIFICATION_CHANNEL_NAME = "OpenClaw Service"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val HEALTH_CHECK_INTERVAL_MS = 15_000L
    const val PROCESS_RESTART_MAX_RETRIES = 5
    const val PROCESS_RESTART_BASE_DELAY_MS = 2_000L

    // --- Preferences ---
    const val PREFS_NAME = "openclaw_prefs"
    const val PREF_BOOTSTRAP_INSTALLED = "bootstrap_installed"
    const val PREF_BOOTSTRAP_VERSION = "bootstrap_version"
    const val PREF_GATEWAY_AUTOSTART = "gateway_autostart"
    const val PREF_BACKGROUND_ENABLED = "background_enabled"
    const val PREF_API_KEY_ANTHROPIC = "api_key_anthropic"
    const val PREF_API_KEY_OPENAI = "api_key_openai"
    const val PREF_API_KEY_GOOGLE = "api_key_google"
    const val PREF_SELECTED_MODEL = "selected_model"

    /**
     * Resolves all filesystem paths based on the app's actual data directory.
     * Must be called with a valid Context.filesDir.
     */
    class Paths(filesDir: File) {
        /** Root of the Linux filesystem: /data/data/<pkg>/files */
        val root: File = filesDir

        /** $PREFIX — installed packages live here */
        val prefix: File = File(filesDir, "usr")

        /** $HOME — user home directory */
        val home: File = File(filesDir, "home")

        /** Node.js binary */
        val nodeBinary: File = File(prefix, NODE_BINARY)

        /** OpenClaw entry point */
        val openclawEntry: File = File(prefix, OPENCLAW_ENTRY)

        /** Bash shell */
        val shellBinary: File = File(prefix, SHELL_BINARY)

        /** OpenClaw config directory */
        val openclawConfig: File = File(home, ".openclaw")

        /** OpenClaw data directory */
        val openclawData: File = File(openclawConfig, "data")

        /** Temporary directory */
        val tmp: File = File(prefix, "tmp")

        /** Standard bin directory */
        val bin: File = File(prefix, "bin")

        /** Standard lib directory */
        val lib: File = File(prefix, "lib")

        fun ensureDirectories() {
            arrayOf(prefix, home, openclawConfig, openclawData, tmp, bin, lib).forEach {
                it.mkdirs()
            }
        }
    }
}
