package com.openclaw.android.bootstrap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads the bootstrap archive from a remote URL (typically GitHub Releases).
 * Reports granular progress for the UI progress bar.
 */
class BootstrapDownloader(private val httpClient: OkHttpClient) {

    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }

    /**
     * Downloads [url] to [destination], calling [onProgress] periodically.
     * @throws IOException on network or filesystem errors
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("Download failed: HTTP ${response.code} ${response.message}")
        }

        val body = response.body ?: throw IOException("Empty response body")
        val totalBytes = body.contentLength()

        destination.parentFile?.mkdirs()
        val tempFile = File(destination.parent, "${destination.name}.tmp")

        try {
            tempFile.outputStream().buffered().use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0

                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(Progress(bytesRead, totalBytes))
                    }
                }
            }
            tempFile.renameTo(destination)
            destination
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }
}
