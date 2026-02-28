package com.openclaw.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PathsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createPaths(): OpenClawConstants.Paths {
        return OpenClawConstants.Paths(tempFolder.root)
    }

    @Test
    fun `root points to filesDir`() {
        val paths = createPaths()
        assertEquals(tempFolder.root, paths.root)
    }

    @Test
    fun `rootfs is filesDir rootfs`() {
        val paths = createPaths()
        assertEquals(File(tempFolder.root, "rootfs"), paths.rootfs)
    }

    @Test
    fun `prootTmp is filesDir proot-tmp`() {
        val paths = createPaths()
        assertEquals(File(tempFolder.root, "proot-tmp"), paths.prootTmp)
    }

    @Test
    fun `hostNodeBinary is rootfs usr bin node`() {
        val paths = createPaths()
        assertEquals(File(paths.rootfs, "usr/bin/node"), paths.hostNodeBinary)
    }

    @Test
    fun `hostShellBinary is rootfs usr bin bash`() {
        val paths = createPaths()
        assertEquals(File(paths.rootfs, "usr/bin/bash"), paths.hostShellBinary)
    }

    @Test
    fun `hostOpenclawEntry path is correct`() {
        val paths = createPaths()
        assertEquals(
            File(paths.rootfs, "usr/lib/node_modules/openclaw/openclaw.mjs"),
            paths.hostOpenclawEntry,
        )
    }

    @Test
    fun `hostOpenclawConfig is rootfs root openclaw`() {
        val paths = createPaths()
        assertEquals(File(paths.rootfs, "root/.openclaw"), paths.hostOpenclawConfig)
    }

    @Test
    fun `hostOpenclawData is rootfs root openclaw data`() {
        val paths = createPaths()
        assertEquals(File(paths.rootfs, "root/.openclaw/data"), paths.hostOpenclawData)
    }

    @Test
    fun `ensureDirectories creates all required dirs`() {
        val paths = createPaths()
        paths.ensureDirectories()

        assertTrue(paths.rootfs.isDirectory)
        assertTrue(paths.prootTmp.isDirectory)
        assertTrue(paths.hostInnerHome.isDirectory)
        assertTrue(paths.hostOpenclawConfig.isDirectory)
        assertTrue(paths.hostOpenclawData.isDirectory)
    }

    @Test
    fun `ensureDirectories is idempotent`() {
        val paths = createPaths()
        paths.ensureDirectories()
        paths.ensureDirectories()

        assertTrue(paths.rootfs.isDirectory)
        assertTrue(paths.hostOpenclawData.isDirectory)
    }

    @Test
    fun `GATEWAY_WS_URL format is correct`() {
        val expected = "ws://${OpenClawConstants.GATEWAY_HOST}:${OpenClawConstants.GATEWAY_PORT}${OpenClawConstants.GATEWAY_WS_PATH}"
        assertEquals(expected, OpenClawConstants.GATEWAY_WS_URL)
    }

    @Test
    fun `constants have expected values`() {
        assertEquals("127.0.0.1", OpenClawConstants.GATEWAY_HOST)
        assertEquals(18789, OpenClawConstants.GATEWAY_PORT)
        assertEquals("/", OpenClawConstants.GATEWAY_WS_PATH)
        assertEquals(3, OpenClawConstants.GATEWAY_PROTOCOL_VERSION)
        assertEquals("/usr/bin/node", OpenClawConstants.INNER_NODE_BINARY)
        assertEquals("/usr/bin/bash", OpenClawConstants.INNER_SHELL_BINARY)
        assertEquals("/root", OpenClawConstants.INNER_HOME)
    }
}
