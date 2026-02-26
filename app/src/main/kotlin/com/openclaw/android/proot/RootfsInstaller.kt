package com.openclaw.android.proot

import android.content.Context
import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates the full Debian rootfs installation:
 * download archive -> extract -> configure networking -> set permissions -> verify.
 *
 * After installation, the rootfs at [OpenClawConstants.Paths.rootfs] contains a complete
 * Debian aarch64 filesystem with Node.js and OpenClaw pre-installed, ready for proot.
 */
class RootfsInstaller(
    private val context: Context,
    private val paths: OpenClawConstants.Paths,
    private val downloader: FileDownloader,
    private val prefs: PreferencesManager,
) {
    companion object {
        private const val TAG = "RootfsInstaller"
    }

    private val _state = MutableStateFlow<RootfsState>(RootfsState.Checking)
    val state: StateFlow<RootfsState> = _state.asStateFlow()

    fun isInstalled(): Boolean {
        return paths.hostNodeBinary.exists() && paths.hostShellBinary.exists()
    }

    /**
     * Runs the full rootfs installation pipeline. Safe to call multiple times;
     * will skip if already installed (unless [force] is true).
     */
    suspend fun install(rootfsUrl: String, force: Boolean = false) {
        if (isInstalled() && !force) {
            _state.value = RootfsState.Installed
            return
        }

        try {
            _state.value = RootfsState.Downloading(0f, 0, 0)

            val archiveFile = File(context.cacheDir, OpenClawConstants.ROOTFS_FILE_NAME)
            downloader.download(rootfsUrl, archiveFile) { progress ->
                _state.value = RootfsState.Downloading(
                    progress.fraction,
                    progress.bytesDownloaded,
                    progress.totalBytes,
                )
            }

            _state.value = RootfsState.Extracting(0f)
            extractRootfs(archiveFile, paths.rootfs)

            _state.value = RootfsState.Configuring
            configureRootfs()
            paths.ensureDirectories()

            _state.value = RootfsState.Verifying
            verifyRootfs()

            archiveFile.delete()

            prefs.setRootfsInstalled(true)
            prefs.setRootfsVersion(OpenClawConstants.ROOTFS_VERSION)

            _state.value = RootfsState.Installed
            Log.i(TAG, "Rootfs installation completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs installation failed", e)
            _state.value = RootfsState.Error(
                message = e.message ?: "Unknown error during installation",
                cause = e,
            )
        }
    }

    private suspend fun extractRootfs(archive: File, destination: File) = withContext(Dispatchers.IO) {
        destination.mkdirs()

        val isXz = archive.name.endsWith(".tar.xz")
        val tarFlag = if (isXz) "xJf" else "xzf"

        val tarBinary = "/system/bin/tar"
        if (File(tarBinary).exists()) {
            val process = ProcessBuilder(
                tarBinary, tarFlag, archive.absolutePath,
                "-C", destination.absolutePath,
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val stderr = process.inputStream.bufferedReader().readText()
                throw RuntimeException("tar extraction failed (exit $exitCode): $stderr")
            }
        } else {
            throw RuntimeException(
                "System tar not available. Cannot extract rootfs archive."
            )
        }
    }

    private fun configureRootfs() {
        writeResolvConf()
        setExecutablePermissions()
        createProotTmpDir()
    }

    /** Write DNS configuration so apt and network tools work inside proot. */
    private fun writeResolvConf() {
        val resolvConf = File(paths.rootfs, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText(
            buildString {
                appendLine("nameserver 8.8.8.8")
                appendLine("nameserver 8.8.4.4")
                appendLine("nameserver 1.1.1.1")
            }
        )
    }

    /** Recursively make all files in bin/, sbin/, lib/ executable. */
    private fun setExecutablePermissions() {
        val execDirs = listOf("usr/bin", "usr/sbin", "usr/lib", "usr/libexec", "bin", "sbin")
            .map { File(paths.rootfs, it) }
        execDirs.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }
    }

    private fun createProotTmpDir() {
        paths.prootTmp.mkdirs()
    }

    private fun verifyRootfs() {
        val missing = mutableListOf<String>()
        if (!paths.hostNodeBinary.exists()) missing += "node"
        if (!paths.hostShellBinary.exists()) missing += "bash"
        if (!paths.hostOpenclawEntry.exists()) missing += "openclaw"

        if (missing.isNotEmpty()) {
            throw RuntimeException("Rootfs verification failed: missing binaries: $missing")
        }
    }
}
