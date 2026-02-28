package com.openclaw.android.proot

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: FileDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = FileDownloader(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `download saves file to destination`() = runTest {
        val body = "hello world content"
        server.enqueue(MockResponse().setBody(body))

        val dest = File(tempFolder.root, "output.txt")
        downloader.download(server.url("/file").toString(), dest)

        assertTrue(dest.exists())
        assertEquals(body, dest.readText())
    }

    @Test
    fun `download reports progress`() = runTest {
        val body = "x".repeat(16384) // 16KB, enough for at least 2 progress callbacks
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Length", body.length))

        val progressValues = mutableListOf<FileDownloader.Progress>()
        val dest = File(tempFolder.root, "big.bin")

        downloader.download(server.url("/big").toString(), dest) { progress ->
            progressValues.add(progress)
        }

        assertTrue(progressValues.isNotEmpty())
        assertEquals(body.length.toLong(), progressValues.last().bytesDownloaded)
    }

    @Test(expected = IOException::class)
    fun `download throws on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val dest = File(tempFolder.root, "fail.bin")
        downloader.download(server.url("/missing").toString(), dest)
    }

    @Test
    fun `download creates parent directories`() = runTest {
        server.enqueue(MockResponse().setBody("data"))

        val dest = File(tempFolder.root, "a/b/c/output.txt")
        downloader.download(server.url("/file").toString(), dest)

        assertTrue(dest.exists())
        assertTrue(dest.parentFile!!.isDirectory)
    }

    @Test
    fun `download cleans up temp file on failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val dest = File(tempFolder.root, "fail.bin")
        val tempFile = File(tempFolder.root, "fail.bin.tmp")

        try {
            downloader.download(server.url("/fail").toString(), dest)
        } catch (_: IOException) {
            // expected
        }

        assertFalse(tempFile.exists())
        assertFalse(dest.exists())
    }

    @Test
    fun `download renames temp to destination atomically`() = runTest {
        server.enqueue(MockResponse().setBody("content"))

        val dest = File(tempFolder.root, "final.txt")
        val tempFile = File(tempFolder.root, "final.txt.tmp")

        downloader.download(server.url("/file").toString(), dest)

        assertTrue(dest.exists())
        assertFalse(tempFile.exists())
    }

    @Test
    fun `download throws on rename failure`() = runTest {
        server.enqueue(MockResponse().setBody("content"))

        // Destination in a non-writable / non-existent parent after download
        val dest = File(tempFolder.root, "output.txt")
        // Pre-create dest as a directory to cause rename failure
        dest.mkdirs()
        File(dest, "blocker").createNewFile()

        try {
            downloader.download(server.url("/file").toString(), dest)
            assertTrue("Should have thrown", false)
        } catch (e: IOException) {
            assertTrue(e.message?.contains("rename") == true || e.message?.contains("Failed") == true)
        }
    }

    @Test
    fun `Progress fraction is 0 when totalBytes is 0`() {
        val progress = FileDownloader.Progress(bytesDownloaded = 100, totalBytes = 0)
        assertEquals(0f, progress.fraction, 0.001f)
    }

    @Test
    fun `Progress fraction calculates correctly`() {
        val progress = FileDownloader.Progress(bytesDownloaded = 50, totalBytes = 100)
        assertEquals(0.5f, progress.fraction, 0.001f)
    }
}
