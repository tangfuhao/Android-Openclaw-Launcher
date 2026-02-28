package com.openclaw.android.service

import android.util.Log
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.gateway.GatewayState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthMonitorTest {

    private lateinit var gatewayClient: GatewayClient
    private lateinit var connectionStateFlow: MutableStateFlow<GatewayState>
    private lateinit var monitor: HealthMonitor

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        gatewayClient = mockk()
        connectionStateFlow = MutableStateFlow(GatewayState.Idle)
        every { gatewayClient.connectionState } returns connectionStateFlow

        monitor = HealthMonitor(gatewayClient)
    }

    @After
    fun tearDown() {
        monitor.stop()
        unmockkStatic(Log::class)
    }

    private fun setLastHealthyTimestamp(value: Long) {
        val field = HealthMonitor::class.java.getDeclaredField("lastHealthyTimestamp")
        field.isAccessible = true
        field.setLong(monitor, value)
    }

    @Test
    fun `start initializes lastHealthyTimestamp`() = runTest {
        val before = System.currentTimeMillis()
        monitor.start(this) {}
        val field = HealthMonitor::class.java.getDeclaredField("lastHealthyTimestamp")
        field.isAccessible = true
        val timestamp = field.getLong(monitor)
        assert(timestamp >= before)
        monitor.stop()
    }

    @Test
    fun `markHealthy updates timestamp`() = runTest {
        monitor.start(this) {}
        val before = System.currentTimeMillis()
        Thread.sleep(10)
        monitor.markHealthy()
        val field = HealthMonitor::class.java.getDeclaredField("lastHealthyTimestamp")
        field.isAccessible = true
        val timestamp = field.getLong(monitor)
        assert(timestamp >= before)
        monitor.stop()
    }

    @Test
    fun `stop cancels monitor job`() = runTest {
        var callbackCount = 0
        monitor.start(this) { callbackCount++ }
        monitor.stop()

        connectionStateFlow.value = GatewayState.Error("bad")
        setLastHealthyTimestamp(System.currentTimeMillis() - 120_000L)
        advanceTimeBy(30_000)

        assertEquals(0, callbackCount)
    }

    @Test
    fun `stop is safe to call multiple times`() {
        monitor.stop()
        monitor.stop()
        monitor.stop()
    }

    @Test
    fun `connected state marks healthy`() = runTest {
        var callbackCalled = false
        monitor.start(this) { callbackCalled = true }

        connectionStateFlow.value = GatewayState.Connected(3)
        advanceTimeBy(16_000)

        assertEquals(false, callbackCalled)
        monitor.stop()
    }

    @Test
    fun `disconnected within threshold does not trigger callback`() = runTest {
        var callbackCalled = false
        monitor.start(this) { callbackCalled = true }

        connectionStateFlow.value = GatewayState.Disconnected("test")
        // lastHealthyTimestamp was just set by start(), so elapsed < 60s
        advanceTimeBy(16_000)

        assertEquals(false, callbackCalled)
        monitor.stop()
    }

    @Test
    fun `disconnected beyond threshold triggers onUnhealthy`() = runTest {
        var callbackCalled = false
        monitor.start(this) { callbackCalled = true }

        connectionStateFlow.value = GatewayState.Disconnected("test")
        setLastHealthyTimestamp(System.currentTimeMillis() - 70_000L)
        advanceTimeBy(16_000)

        assertEquals(true, callbackCalled)
        monitor.stop()
    }

    @Test
    fun `error state beyond threshold triggers onUnhealthy`() = runTest {
        var callbackCalled = false
        monitor.start(this) { callbackCalled = true }

        connectionStateFlow.value = GatewayState.Error("connection refused")
        setLastHealthyTimestamp(System.currentTimeMillis() - 70_000L)
        advanceTimeBy(16_000)

        assertEquals(true, callbackCalled)
        monitor.stop()
    }

    @Test
    fun `connecting state does not trigger callback`() = runTest {
        var callbackCalled = false
        monitor.start(this) { callbackCalled = true }

        connectionStateFlow.value = GatewayState.Connecting
        setLastHealthyTimestamp(System.currentTimeMillis() - 120_000L)
        advanceTimeBy(16_000)

        assertEquals(false, callbackCalled)
        monitor.stop()
    }

    @Test
    fun `reconnecting state does not trigger callback`() = runTest {
        var callbackCalled = false
        monitor.start(this) { callbackCalled = true }

        connectionStateFlow.value = GatewayState.Reconnecting
        setLastHealthyTimestamp(System.currentTimeMillis() - 120_000L)
        advanceTimeBy(16_000)

        assertEquals(false, callbackCalled)
        monitor.stop()
    }

    @Test
    fun `start replaces previous monitor job`() = runTest {
        var count = 0
        monitor.start(this) { count++ }
        monitor.start(this) { count += 10 }

        connectionStateFlow.value = GatewayState.Error("bad")
        setLastHealthyTimestamp(System.currentTimeMillis() - 70_000L)
        advanceTimeBy(16_000)

        assertEquals(10, count)
        monitor.stop()
    }
}
