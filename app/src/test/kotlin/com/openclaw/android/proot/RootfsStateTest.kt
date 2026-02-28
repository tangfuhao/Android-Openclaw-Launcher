package com.openclaw.android.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsStateTest {

    @Test
    fun `Installed is terminal`() {
        assertTrue(RootfsState.Installed.isTerminal)
    }

    @Test
    fun `Error is terminal`() {
        assertTrue(RootfsState.Error("failed").isTerminal)
    }

    @Test
    fun `Downloading is not terminal`() {
        assertFalse(RootfsState.Downloading(0.5f, 100, 200).isTerminal)
    }

    @Test
    fun `Extracting is not terminal`() {
        assertFalse(RootfsState.Extracting(0.3f).isTerminal)
    }

    @Test
    fun `NotInstalled is not terminal`() {
        assertFalse(RootfsState.NotInstalled.isTerminal)
    }

    @Test
    fun `Checking is not terminal`() {
        assertFalse(RootfsState.Checking.isTerminal)
    }

    @Test
    fun `Configuring is not terminal`() {
        assertFalse(RootfsState.Configuring.isTerminal)
    }

    @Test
    fun `Verifying is not terminal`() {
        assertFalse(RootfsState.Verifying.isTerminal)
    }
}
