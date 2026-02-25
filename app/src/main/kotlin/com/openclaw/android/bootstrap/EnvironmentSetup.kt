package com.openclaw.android.bootstrap

import com.openclaw.android.core.OpenClawConstants
import java.io.File

/**
 * Configures the environment variables and filesystem layout
 * needed to run Linux binaries inside the embedded Termux environment.
 */
class EnvironmentSetup(private val paths: OpenClawConstants.Paths) {

    /** Environment variables passed to every spawned Linux process. */
    fun buildEnvironment(): Map<String, String> = buildMap {
        put("HOME", paths.home.absolutePath)
        put("PREFIX", paths.prefix.absolutePath)
        put("TMPDIR", paths.tmp.absolutePath)
        put("LANG", "en_US.UTF-8")
        put("TERM", "xterm-256color")

        val pathDirs = listOf(
            paths.bin.absolutePath,
            "/system/bin",
            "/system/xbin",
        )
        put("PATH", pathDirs.joinToString(":"))
        put("LD_LIBRARY_PATH", paths.lib.absolutePath)

        put("OPENCLAW_HOME", paths.openclawConfig.absolutePath)
        put("OPENCLAW_DATA", paths.openclawData.absolutePath)
        put("OPENCLAW_GATEWAY_PORT", OpenClawConstants.GATEWAY_PORT.toString())
        put("NODE_PATH", File(paths.prefix, "lib/node_modules").absolutePath)
    }

    /** Ensures all required directories exist and have correct permissions. */
    fun ensureEnvironment() {
        paths.ensureDirectories()
        createProfileScript()
    }

    /** Verify that the bootstrap is properly installed and all critical binaries exist. */
    fun verifyInstallation(): VerificationResult {
        val missing = mutableListOf<String>()
        if (!paths.nodeBinary.exists()) missing += "node"
        if (!paths.shellBinary.exists()) missing += "bash"
        if (!paths.openclawEntry.exists()) missing += "openclaw"

        return if (missing.isEmpty()) {
            VerificationResult.Ok
        } else {
            VerificationResult.MissingBinaries(missing)
        }
    }

    private fun createProfileScript() {
        val profileFile = File(paths.home, ".profile")
        if (profileFile.exists()) return

        profileFile.writeText(
            buildString {
                appendLine("export HOME=${paths.home.absolutePath}")
                appendLine("export PREFIX=${paths.prefix.absolutePath}")
                appendLine("export PATH=${paths.bin.absolutePath}:\$PATH")
                appendLine("export TMPDIR=${paths.tmp.absolutePath}")
                appendLine("export LANG=en_US.UTF-8")
            }
        )
    }

    sealed interface VerificationResult {
        data object Ok : VerificationResult
        data class MissingBinaries(val names: List<String>) : VerificationResult
    }
}
