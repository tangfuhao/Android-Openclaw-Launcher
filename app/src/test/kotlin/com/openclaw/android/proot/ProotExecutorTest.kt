package com.openclaw.android.proot

import android.content.Context
import android.content.pm.ApplicationInfo
import com.openclaw.android.core.OpenClawConstants
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotExecutorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var paths: OpenClawConstants.Paths
    private lateinit var configWriter: OpenClawConfigWriter
    private lateinit var executor: ProotExecutor

    @Before
    fun setUp() {
        context = mockk()
        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/lib"
        }
        every { context.applicationInfo } returns appInfo

        paths = OpenClawConstants.Paths(tempFolder.root)
        configWriter = mockk()
        every { configWriter.getApiKeyEnvVars() } returns mapOf(
            "ANTHROPIC_API_KEY" to "sk-ant-test",
        )

        executor = ProotExecutor(context, paths, configWriter)
    }

    // --- buildCommand ---

    @Test
    fun `buildCommand includes proot binary path`() {
        val cmd = executor.buildCommand(listOf("/usr/bin/node", "app.js"))
        assertEquals("/data/app/lib/libproot.so", cmd[0])
    }

    @Test
    fun `buildCommand includes rootfs flag`() {
        val cmd = executor.buildCommand(listOf("bash"))
        assertTrue(cmd.any { it.startsWith("--rootfs=") })
        assertTrue(cmd.any { it.contains(paths.rootfs.absolutePath) })
    }

    @Test
    fun `buildCommand includes all bind mounts`() {
        val cmd = executor.buildCommand(listOf("bash"))
        assertTrue(cmd.contains("--bind=/dev:/dev"))
        assertTrue(cmd.contains("--bind=/proc:/proc"))
        assertTrue(cmd.contains("--bind=/sys:/sys"))
        assertTrue(cmd.contains("--bind=/storage:/storage"))
    }

    @Test
    fun `buildCommand includes cwd`() {
        val cmd = executor.buildCommand(listOf("bash"))
        assertTrue(cmd.any { it.startsWith("--cwd=") })
        assertTrue(cmd.any { it.contains(OpenClawConstants.INNER_HOME) })
    }

    @Test
    fun `buildCommand includes link2symlink`() {
        val cmd = executor.buildCommand(listOf("bash"))
        assertTrue(cmd.contains("--link2symlink"))
    }

    @Test
    fun `buildCommand includes root mode`() {
        val cmd = executor.buildCommand(listOf("bash"))
        assertTrue(cmd.contains("-0"))
    }

    @Test
    fun `buildCommand appends inner command`() {
        val inner = listOf("/usr/bin/node", "gateway", "--port", "18789")
        val cmd = executor.buildCommand(inner)
        val innerStart = cmd.size - inner.size
        assertEquals(inner, cmd.subList(innerStart, cmd.size))
    }

    @Test
    fun `buildCommand uses custom cwd`() {
        val cmd = executor.buildCommand(listOf("bash"), cwd = "/tmp")
        assertTrue(cmd.contains("--cwd=/tmp"))
    }

    @Test
    fun `buildCommand with empty inner command`() {
        val cmd = executor.buildCommand(emptyList())
        assertTrue(cmd.contains("-0"))
        assertEquals("/data/app/lib/libproot.so", cmd[0])
    }

    // --- buildEnvironment ---

    @Test
    fun `buildEnvironment includes HOME`() {
        val env = executor.buildEnvironment()
        assertEquals(OpenClawConstants.INNER_HOME, env["HOME"])
    }

    @Test
    fun `buildEnvironment includes LANG`() {
        val env = executor.buildEnvironment()
        assertEquals("en_US.UTF-8", env["LANG"])
    }

    @Test
    fun `buildEnvironment includes TERM`() {
        val env = executor.buildEnvironment()
        assertEquals("xterm-256color", env["TERM"])
    }

    @Test
    fun `buildEnvironment includes PATH`() {
        val env = executor.buildEnvironment()
        assertTrue(env["PATH"]!!.contains("/usr/bin"))
        assertTrue(env["PATH"]!!.contains("/usr/local/bin"))
    }

    @Test
    fun `buildEnvironment includes TMPDIR`() {
        val env = executor.buildEnvironment()
        assertEquals(paths.prootTmp.absolutePath, env["TMPDIR"])
        assertEquals(paths.prootTmp.absolutePath, env["PROOT_TMP_DIR"])
    }

    @Test
    fun `buildEnvironment includes OPENCLAW vars`() {
        val env = executor.buildEnvironment()
        assertEquals("/root", env["OPENCLAW_HOME"])
        assertEquals("/root/.openclaw/data", env["OPENCLAW_DATA"])
        assertEquals(OpenClawConstants.GATEWAY_PORT.toString(), env["OPENCLAW_GATEWAY_PORT"])
    }

    @Test
    fun `buildEnvironment includes API key env vars`() {
        val env = executor.buildEnvironment()
        assertEquals("sk-ant-test", env["ANTHROPIC_API_KEY"])
    }

    @Test
    fun `buildEnvironment merges API keys`() {
        every { configWriter.getApiKeyEnvVars() } returns mapOf(
            "ANTHROPIC_API_KEY" to "sk-ant",
            "OPENAI_API_KEY" to "sk-oai",
        )
        val env = executor.buildEnvironment()
        assertEquals("sk-ant", env["ANTHROPIC_API_KEY"])
        assertEquals("sk-oai", env["OPENAI_API_KEY"])
    }
}
