package com.openclaw.android.proot

import android.content.Context
import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import java.io.File
import java.nio.file.Files

/**
 * Constructs proot command lines and launches processes inside the Debian rootfs.
 *
 * Every process that needs to run inside the Linux environment (gateway, shell sessions,
 * package installs) goes through this class. It handles:
 * - Locating the proot binary shipped as a native library
 * - Building the correct --rootfs, --bind, and --cwd arguments
 * - Setting up environment variables for both proot and the inner process
 * - Injecting API keys read from [OpenClawConfigWriter]
 */
class ProotExecutor(
    private val context: Context,
    private val paths: OpenClawConstants.Paths,
    private val configWriter: OpenClawConfigWriter,
) {
    companion object {
        private const val TAG = "ProotExecutor"
        private const val PROOT_LIB_NAME = "libproot.so"
        private const val PROOT_LOADER_LIB_NAME = "libproot_loader.so"
    }

    private val nativeLibDir: String
        get() = context.applicationInfo.nativeLibraryDir

    /** Absolute path to the proot binary extracted by Android from the APK's jniLibs. */
    val prootBinaryPath: String
        get() = "$nativeLibDir/$PROOT_LIB_NAME"

    /** Whether the proot binary is available for execution. */
    fun isAvailable(): Boolean = File(prootBinaryPath).exists()

    /**
     * Writes a Node.js preload script that patches APIs broken inside proot
     * (e.g. os.networkInterfaces which fails with EACCES on Android).
     */
    private fun ensureNodePreload() {
        val preloadDir = File(paths.rootfs, "root/.openclaw")
        preloadDir.mkdirs()
        val preloadFile = File(preloadDir, "node-preload.cjs")
        if (preloadFile.exists()) return

        preloadFile.writeText(
            """
            'use strict';
            const os = require('os');
            const origNI = os.networkInterfaces;
            os.networkInterfaces = function() {
              try { return origNI.call(os); }
              catch (_) {
                return { lo: [{ address: '127.0.0.1', netmask: '255.0.0.0',
                  family: 'IPv4', mac: '00:00:00:00:00:00', internal: true,
                  cidr: '127.0.0.1/8' }] };
              }
            };
            """.trimIndent() + "\n"
        )
        Log.i(TAG, "Node preload script written to ${preloadFile.absolutePath}")
    }

    /**
     * The Termux-built proot links against libtalloc.so.2, but Android only extracts
     * libtalloc.so. Create a versioned symlink so the dynamic linker can find it.
     */
    private fun ensureTallocSymlink() {
        val tallocSo = File(nativeLibDir, "libtalloc.so")
        if (!tallocSo.exists()) return

        val linkDir = File(paths.root, "lib")
        linkDir.mkdirs()
        val link = File(linkDir, "libtalloc.so.2")
        val linkPath = link.toPath()

        val needsUpdate = if (Files.isSymbolicLink(linkPath)) {
            Files.readSymbolicLink(linkPath) != tallocSo.toPath()
        } else {
            !link.exists()
        }

        if (needsUpdate) {
            link.delete()
            try {
                Files.createSymbolicLink(linkPath, tallocSo.toPath())
                Log.i(TAG, "Created libtalloc.so.2 symlink -> ${tallocSo.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create libtalloc symlink, copying instead", e)
                tallocSo.copyTo(link, overwrite = true)
            }
        }
    }

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
     * Includes API keys from user preferences for the OpenClaw gateway.
     */
    fun buildEnvironment(): Map<String, String> = buildMap {
        put("HOME", OpenClawConstants.INNER_HOME)
        put("LANG", "en_US.UTF-8")
        put("TERM", "xterm-256color")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("TMPDIR", paths.prootTmp.absolutePath)
        put("PROOT_TMP_DIR", paths.prootTmp.absolutePath)
        put("PROOT_LOADER", "$nativeLibDir/$PROOT_LOADER_LIB_NAME")
        put("NODE_OPTIONS", "--require=/root/.openclaw/node-preload.cjs")

        val tallocLibDir = File(paths.root, "lib").absolutePath
        put("LD_LIBRARY_PATH", "$nativeLibDir:$tallocLibDir")

        put("OPENCLAW_HOME", "/root")
        put("OPENCLAW_DATA", "/root/.openclaw/data")
        put("OPENCLAW_GATEWAY_PORT", OpenClawConstants.GATEWAY_PORT.toString())

        putAll(configWriter.getApiKeyEnvVars())
    }

    /**
     * Starts a proot-wrapped process running [innerCommand] and returns the [Process] handle.
     */
    fun execute(
        innerCommand: List<String>,
        cwd: String = OpenClawConstants.INNER_HOME,
    ): Process {
        ensureNodePreload()
        ensureTallocSymlink()

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
