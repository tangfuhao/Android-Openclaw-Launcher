package com.openclaw.android.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStateTest {

    @Test
    fun `Idle is not connected and not active`() {
        assertFalse(GatewayState.Idle.isConnected)
        assertFalse(GatewayState.Idle.isActive)
    }

    @Test
    fun `Connecting is not connected but is active`() {
        assertFalse(GatewayState.Connecting.isConnected)
        assertTrue(GatewayState.Connecting.isActive)
    }

    @Test
    fun `Handshaking is not connected but is active`() {
        assertFalse(GatewayState.Handshaking.isConnected)
        assertTrue(GatewayState.Handshaking.isActive)
    }

    @Test
    fun `Connected is connected and active`() {
        assertTrue(GatewayState.Connected(3).isConnected)
        assertTrue(GatewayState.Connected(3).isActive)
    }

    @Test
    fun `Reconnecting is not connected and not active`() {
        assertFalse(GatewayState.Reconnecting.isConnected)
        assertFalse(GatewayState.Reconnecting.isActive)
    }

    @Test
    fun `Disconnected is not connected and not active`() {
        assertFalse(GatewayState.Disconnected("test").isConnected)
        assertFalse(GatewayState.Disconnected("test").isActive)
    }

    @Test
    fun `Error is not connected and not active`() {
        assertFalse(GatewayState.Error("fail").isConnected)
        assertFalse(GatewayState.Error("fail").isActive)
    }
}
