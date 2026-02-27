package com.openclaw.android.core

import java.io.File

/**
 * Central constants for the proot-based Linux environment and OpenClaw gateway.
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

    // --- Rootfs ---
    const val ROOTFS_FILE_NAME = "rootfs-aarch64.tar.xz"
    const val ROOTFS_VERSION = "0.1.0"

    // --- Inner paths (as seen by processes inside proot) ---
    const val INNER_NODE_BINARY = "/usr/bin/node"
    const val INNER_OPENCLAW_ENTRY = "/usr/lib/node_modules/openclaw/openclaw.mjs"
    const val INNER_SHELL_BINARY = "/usr/bin/bash"
    const val INNER_HOME = "/root"

    // --- Foreground Service ---
    const val SERVICE_NOTIFICATION_CHANNEL_ID = "openclaw_service"
    const val SERVICE_NOTIFICATION_CHANNEL_NAME = "OpenClaw Service"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val HEALTH_CHECK_INTERVAL_MS = 15_000L
    const val PROCESS_RESTART_MAX_RETRIES = 5
    const val PROCESS_RESTART_BASE_DELAY_MS = 2_000L

    // --- Preferences ---
    const val PREFS_NAME = "openclaw_prefs"
    const val PREF_ROOTFS_INSTALLED = "rootfs_installed"
    const val PREF_ROOTFS_VERSION = "rootfs_version"
    const val PREF_GATEWAY_AUTOSTART = "gateway_autostart"
    const val PREF_BACKGROUND_ENABLED = "background_enabled"
    const val PREF_API_KEY_ANTHROPIC = "api_key_anthropic"
    const val PREF_API_KEY_OPENAI = "api_key_openai"
    const val PREF_API_KEY_GOOGLE = "api_key_google"
    const val PREF_SELECTED_MODEL = "selected_model"

    /**
     * Resolves all filesystem paths for the proot-based Linux environment.
     * Paths prefixed with "host" are absolute Android paths used for file existence
     * checks and ProcessBuilder configuration. Paths inside proot use the INNER_*
     * constants above.
     */
    class Paths(filesDir: File) {
        /** App's files directory: /data/data/<pkg>/files */
        val root: File = filesDir

        /** Debian rootfs root — proot --rootfs points here */
        val rootfs: File = File(filesDir, "rootfs")

        /** Temporary directory for proot's internal use */
        val prootTmp: File = File(filesDir, "proot-tmp")

        /** Node.js binary — host path for existence checks */
        val hostNodeBinary: File = File(rootfs, "usr/bin/node")

        /** Bash shell — host path for existence checks */
        val hostShellBinary: File = File(rootfs, "usr/bin/bash")

        /** OpenClaw entry point — host path for existence checks */
        val hostOpenclawEntry: File = File(rootfs, "usr/lib/node_modules/openclaw/openclaw.mjs")

        /** Inner home directory — host path for file operations */
        val hostInnerHome: File = File(rootfs, "root")

        /** OpenClaw config directory — host path */
        val hostOpenclawConfig: File = File(rootfs, "root/.openclaw")

        /** OpenClaw data directory — host path */
        val hostOpenclawData: File = File(rootfs, "root/.openclaw/data")

        fun ensureDirectories() {
            arrayOf(rootfs, prootTmp, hostInnerHome, hostOpenclawConfig, hostOpenclawData).forEach {
                it.mkdirs()
            }
        }
    }
}
