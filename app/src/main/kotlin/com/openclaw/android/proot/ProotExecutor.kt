package com.openclaw.android.proot

import android.content.Context
import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import java.io.File

/**
 * Constructs proot command lines and launches processes inside the Debian rootfs.
 *
 * Every process that needs to run inside the Linux environment (gateway, shell sessions,
 * package installs) goes through this class. It handles:
 * - Locating the proot binary shipped as a native library
 * - Building the correct --rootfs, --bind, and --cwd arguments
 * - Setting up environment variables for both proot and the inner process
 */
class ProotExecutor(
    private val context: Context,
    private val paths: OpenClawConstants.Paths,
) {
    companion object {
        private const val TAG = "ProotExecutor"
        private const val PROOT_LIB_NAME = "libproot.so"
    }

    /** Absolute path to the proot binary extracted by Android from the APK's jniLibs. */
    val prootBinaryPath: String
        get() = context.applicationInfo.nativeLibraryDir + "/" + PROOT_LIB_NAME

    /** Whether the proot binary is available for execution. */
    fun isAvailable(): Boolean = File(prootBinaryPath).exists()

    /**
     * Builds the full command-line array for running [innerCommand] inside proot.
     * The returned list can be passed directly to [ProcessBuilder] or [TerminalSession].
     */
    fun buildCommand(
        innerCommand: List<String>,
        cwd: String = OpenClawConstants.INNER_HOME,
    ): List<String> = buildList {
        add(prootBinaryPath)
        add("--rootfs=${paths.rootfs.absolutePath}")
        add("--bind=/dev:/dev")
        add("--bind=/proc:/proc")
        add("--bind=/sys:/sys")
        add("--bind=/storage:/storage")
        add("--cwd=$cwd")
        add("--link2symlink")
        add("-0") // pretend to be root inside proot
        addAll(innerCommand)
    }

    /**
     * Environment variables for the proot host process.
     * These are set on the ProcessBuilder; proot forwards relevant ones into the guest.
     */
    fun buildEnvironment(): Map<String, String> = buildMap {
        put("HOME", OpenClawConstants.INNER_HOME)
        put("LANG", "en_US.UTF-8")
        put("TERM", "xterm-256color")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("TMPDIR", paths.prootTmp.absolutePath)
        put("PROOT_TMP_DIR", paths.prootTmp.absolutePath)

        put("OPENCLAW_HOME", "/root/.openclaw")
        put("OPENCLAW_DATA", "/root/.openclaw/data")
        put("OPENCLAW_GATEWAY_PORT", OpenClawConstants.GATEWAY_PORT.toString())
    }

    /**
     * Starts a proot-wrapped process running [innerCommand] and returns the [Process] handle.
     */
    fun execute(
        innerCommand: List<String>,
        cwd: String = OpenClawConstants.INNER_HOME,
    ): Process {
        val command = buildCommand(innerCommand, cwd)
        val env = buildEnvironment()

        Log.i(TAG, "Executing: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command)
            .directory(paths.root)
            .redirectErrorStream(true)

        processBuilder.environment().apply {
            clear()
            putAll(env)
        }

        return processBuilder.start()
    }

    /**
     * Starts an interactive login shell inside proot for the terminal tab.
     */
    fun executeShell(): Process {
        return execute(
            innerCommand = listOf(OpenClawConstants.INNER_SHELL_BINARY, "--login"),
            cwd = OpenClawConstants.INNER_HOME,
        )
    }
}
