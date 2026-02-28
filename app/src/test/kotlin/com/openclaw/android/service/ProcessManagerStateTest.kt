package com.openclaw.android.service

import com.openclaw.android.service.ProcessManager.ProcessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessManagerStateTest {

    @Test
    fun `Stopped is not operational`() {
        assertFalse(ProcessState.Stopped.isOperational)
    }

    @Test
    fun `Starting is not operational`() {
        assertFalse(ProcessState.Starting.isOperational)
    }

    @Test
    fun `Running is operational`() {
        assertTrue(ProcessState.Running.isOperational)
    }

    @Test
    fun `Restarting is not operational`() {
        assertFalse(ProcessState.Restarting(1).isOperational)
    }

    @Test
    fun `Crashed is not operational`() {
        assertFalse(ProcessState.Crashed(1).isOperational)
    }

    @Test
    fun `Error is not operational`() {
        assertFalse(ProcessState.Error("test").isOperational)
    }

    @Test
    fun `Stopped display text`() {
        assertEquals("Stopped", ProcessState.Stopped.displayText)
    }

    @Test
    fun `Running display text`() {
        assertEquals("Running", ProcessState.Running.displayText)
    }

    @Test
    fun `Restarting display text includes attempt`() {
        assertEquals("Restarting (attempt 3)...", ProcessState.Restarting(3).displayText)
    }

    @Test
    fun `Crashed display text includes exit code`() {
        assertEquals("Crashed (exit code 137)", ProcessState.Crashed(137).displayText)
    }

    @Test
    fun `Error display text includes message`() {
        assertEquals("Error: proot not found", ProcessState.Error("proot not found").displayText)
    }
}
