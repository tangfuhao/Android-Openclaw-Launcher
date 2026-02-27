package com.openclaw.android.proot

import android.content.Context
import android.system.Os
import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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

        val rawInput = BufferedInputStream(FileInputStream(archive), 65536)
        val decompressed = if (archive.name.endsWith(".tar.xz")) {
            XZCompressorInputStream(rawInput)
        } else {
            java.util.zip.GZIPInputStream(rawInput)
        }

        var entryCount = 0
        TarArchiveInputStream(decompressed).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val outFile = File(destination, entry.name)

                when {
                    entry.isDirectory -> outFile.mkdirs()

                    entry.isSymbolicLink -> {
                        outFile.parentFile?.mkdirs()
                        outFile.delete()
                        Os.symlink(entry.linkName, outFile.absolutePath)
                    }

                    entry.isLink -> {
                        outFile.parentFile?.mkdirs()
                        val linkTarget = File(destination, entry.linkName)
                        outFile.delete()
                        Os.symlink(linkTarget.absolutePath, outFile.absolutePath)
                    }

                    else -> {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> tar.copyTo(fos) }
                        applyPermissions(outFile, entry.mode)
                    }
                }

                entryCount++
                if (entryCount % 500 == 0) {
                    Log.d(TAG, "Extracted $entryCount entries...")
                }

                entry = tar.nextEntry
            }
        }
        Log.i(TAG, "Extraction complete: $entryCount entries")
    }

    /** Map tar entry mode bits to Java file permissions. */
    private fun applyPermissions(file: File, mode: Int) {
        file.setReadable(mode and 0b100_000_000 != 0, false)
        file.setWritable(mode and 0b010_000_000 != 0, false)
        file.setExecutable(mode and 0b001_000_000 != 0, false)
    }

    private fun configureRootfs() {
        writeResolvConf()
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
