package com.openclaw.android.proot

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.openclaw.android.core.OpenClawConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Bridges files between Android content URIs and the proot filesystem.
 * Copies user-picked files into proot's shared media directory so the agent
 * can access them, and reads agent-created files from proot for display.
 */
class FileBridge(
    private val context: Context,
    private val paths: OpenClawConstants.Paths,
) {

    private val mediaDir: File
        get() = File(paths.hostOpenclawData, "media").also { it.mkdirs() }

    /**
     * Copy an Android content URI into proot. Returns metadata about the copied file.
     */
    suspend fun importToProotFromUri(uri: Uri): BridgedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryDisplayName(uri) ?: "file_${UUID.randomUUID().toString().take(8)}"

        val safeFileName = sanitizeFileName(displayName)
        val destFile = uniqueFile(mediaDir, safeFileName)

        resolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Cannot open input stream for URI: $uri")

        val prootPath = hostPathToProotPath(destFile)
        BridgedFile(
            hostPath = destFile.absolutePath,
            prootPath = prootPath,
            mimeType = mimeType,
            fileName = safeFileName,
            size = destFile.length(),
        )
    }

    /**
     * Copy raw bytes into proot. Useful for programmatic content (e.g. voice recordings).
     */
    suspend fun importToProotFromBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): BridgedFile = withContext(Dispatchers.IO) {
        val safeFileName = sanitizeFileName(fileName)
        val destFile = uniqueFile(mediaDir, safeFileName)
        destFile.writeBytes(bytes)

        BridgedFile(
            hostPath = destFile.absolutePath,
            prootPath = hostPathToProotPath(destFile),
            mimeType = mimeType,
            fileName = safeFileName,
            size = bytes.size.toLong(),
        )
    }

    /**
     * Resolve a proot-internal path to the host filesystem.
     * E.g. "/root/.openclaw/data/media/img.png" -> "<rootfs>/root/.openclaw/data/media/img.png"
     */
    fun resolveProotPath(prootPath: String): File {
        val relativePath = prootPath.removePrefix("/")
        return File(paths.rootfs, relativePath)
    }

    /**
     * Check if a proot path exists on the host filesystem.
     */
    fun prootFileExists(prootPath: String): Boolean {
        return resolveProotPath(prootPath).exists()
    }

    /**
     * Read bytes from a proot path.
     */
    suspend fun readProotFile(prootPath: String): ByteArray = withContext(Dispatchers.IO) {
        val file = resolveProotPath(prootPath)
        if (!file.exists()) throw IOException("File not found in proot: $prootPath")
        file.readBytes()
    }

    /**
     * Get a File handle for a proot path (for use with file-based APIs like ExoPlayer).
     */
    fun getHostFile(prootPath: String): File = resolveProotPath(prootPath)

    /**
     * Guess MIME type from a file path.
     */
    fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "md", "txt", "log" -> "text/plain"
                "json" -> "application/json"
                "py" -> "text/x-python"
                "js", "mjs" -> "text/javascript"
                "ts" -> "text/typescript"
                "sh" -> "text/x-shellscript"
                "ogg" -> "audio/ogg"
                "opus" -> "audio/opus"
                "webm" -> "video/webm"
                else -> "application/octet-stream"
            }
    }

    fun isImage(mimeType: String) = mimeType.startsWith("image/")
    fun isAudio(mimeType: String) = mimeType.startsWith("audio/")
    fun isVideo(mimeType: String) = mimeType.startsWith("video/")

    private fun hostPathToProotPath(hostFile: File): String {
        val relative = hostFile.relativeTo(paths.rootfs).path
        return "/$relative"
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").take(200)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file

        val baseName = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${baseName}_${counter}${suffix}")
            counter++
        }
        return file
    }

    data class BridgedFile(
        val hostPath: String,
        val prootPath: String,
        val mimeType: String,
        val fileName: String,
        val size: Long,
    )
}
