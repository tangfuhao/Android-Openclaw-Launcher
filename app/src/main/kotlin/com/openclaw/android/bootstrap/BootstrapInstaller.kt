package com.openclaw.android.bootstrap

import android.content.Context
import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Orchestrates the full bootstrap installation:
 * download archive -> extract -> set permissions -> verify.
 */
class BootstrapInstaller(
    private val context: Context,
    private val paths: OpenClawConstants.Paths,
    private val downloader: BootstrapDownloader,
    private val prefs: PreferencesManager,
) {
    companion object {
        private const val TAG = "BootstrapInstaller"
    }

    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Checking)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    fun isInstalled(): Boolean {
        return paths.nodeBinary.exists() && paths.shellBinary.exists()
    }

    /**
     * Runs the full installation pipeline. Safe to call multiple times;
     * will skip if already installed (unless [force] is true).
     */
    suspend fun install(bootstrapUrl: String, force: Boolean = false) {
        if (isInstalled() && !force) {
            _state.value = BootstrapState.Installed
            return
        }

        try {
            _state.value = BootstrapState.Downloading(0f, 0, 0)

            val archiveFile = File(context.cacheDir, OpenClawConstants.BOOTSTRAP_FILE_NAME)
            downloader.download(bootstrapUrl, archiveFile) { progress ->
                _state.value = BootstrapState.Downloading(
                    progress.fraction,
                    progress.bytesDownloaded,
                    progress.totalBytes,
                )
            }

            _state.value = BootstrapState.Extracting(0f)
            extractTarGz(archiveFile, paths.prefix)

            _state.value = BootstrapState.Configuring
            setExecutablePermissions(paths.prefix)
            paths.ensureDirectories()

            archiveFile.delete()

            prefs.setBootstrapInstalled(true)
            prefs.setBootstrapVersion(OpenClawConstants.BOOTSTRAP_VERSION)

            _state.value = BootstrapState.Installed
            Log.i(TAG, "Bootstrap installation completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap installation failed", e)
            _state.value = BootstrapState.Error(
                message = e.message ?: "Unknown error during installation",
                cause = e,
            )
        }
    }

    private suspend fun extractTarGz(archive: File, destination: File) = withContext(Dispatchers.IO) {
        destination.mkdirs()

        // Use the system tar command if available (faster), fall back to Java extraction
        val tarBinary = "/system/bin/tar"
        if (File(tarBinary).exists()) {
            extractWithSystemTar(archive, destination)
        } else {
            extractWithProcessBuilder(archive, destination)
        }
    }

    private fun extractWithSystemTar(archive: File, destination: File) {
        val process = ProcessBuilder(
            "/system/bin/tar",
            "xzf", archive.absolutePath,
            "-C", destination.absolutePath,
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val stderr = process.inputStream.bufferedReader().readText()
            throw RuntimeException("tar extraction failed (exit $exitCode): $stderr")
        }
    }

    private fun extractWithProcessBuilder(archive: File, destination: File) {
        // Fallback: use GZIPInputStream + simple tar parsing
        // For production, consider using Apache Commons Compress
        BufferedInputStream(GZIPInputStream(FileInputStream(archive))).use { gzipStream ->
            extractTarStream(gzipStream, destination)
        }
    }

    /**
     * Minimal tar extractor for POSIX tar format.
     * Handles regular files, directories, and symlinks.
     */
    private fun extractTarStream(input: BufferedInputStream, destDir: File) {
        val header = ByteArray(512)
        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead < 512 || header.all { it == 0.toByte() }) break

            val name = readTarString(header, 0, 100)
            if (name.isBlank()) break

            val sizeOctal = readTarString(header, 124, 12).trim()
            val size = if (sizeOctal.isNotEmpty()) sizeOctal.toLong(8) else 0L
            val typeFlag = header[156].toInt().toChar()
            val linkName = readTarString(header, 157, 100)

            // Handle USTAR prefix
            val prefix = readTarString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

            val outFile = File(destDir, fullName)

            when (typeFlag) {
                '5', 'D' -> outFile.mkdirs()
                '2' -> {
                    outFile.parentFile?.mkdirs()
                    // Symlinks: create them as-is
                    try {
                        Runtime.getRuntime().exec(arrayOf("ln", "-sf", linkName, outFile.absolutePath)).waitFor()
                    } catch (_: Exception) {
                        // Symlink creation may fail on some Android versions; skip gracefully
                    }
                }
                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        val buf = ByteArray(8192)
                        var remaining = size
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                }
                else -> {
                    // Skip unknown types
                    val toSkip = size
                    input.skip(toSkip)
                }
            }

            // Tar records are padded to 512-byte boundaries
            val remainder = (512 - (size % 512)) % 512
            if (remainder > 0) input.skip(remainder)
        }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n == -1) return offset
            offset += n
        }
        return offset
    }

    private fun readTarString(header: ByteArray, offset: Int, length: Int): String {
        val end = header.indexOf(0.toByte(), offset).let { if (it in offset until offset + length) it else offset + length }
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun ByteArray.indexOf(byte: Byte, startIndex: Int): Int {
        for (i in startIndex until size) {
            if (this[i] == byte) return i
        }
        return -1
    }

    /** Recursively make all files in bin/ and lib/ executable. */
    private fun setExecutablePermissions(prefix: File) {
        val execDirs = listOf("bin", "lib", "libexec").map { File(prefix, it) }
        execDirs.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }
    }
}
