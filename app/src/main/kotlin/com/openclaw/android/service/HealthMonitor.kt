package com.openclaw.android.service

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.gateway.GatewayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodically checks the health of the OpenClaw Gateway by monitoring
 * the WebSocket connection state. Triggers restart when unhealthy.
 */
class HealthMonitor(private val gatewayClient: GatewayClient) {

    companion object {
        private const val TAG = "HealthMonitor"
        private const val HEALTHY_THRESHOLD_MS = 60_000L
    }

    private var monitorJob: Job? = null
    private var lastHealthyTimestamp: Long = 0L
    private var onUnhealthy: (suspend () -> Unit)? = null

    /**
     * Starts periodic health checks.
     * @param scope Coroutine scope tied to the service lifecycle
     * @param onUnhealthy Callback invoked when the gateway is detected as unhealthy
     */
    fun start(scope: CoroutineScope, onUnhealthy: suspend () -> Unit) {
        this.onUnhealthy = onUnhealthy
        stop()

        monitorJob = scope.launch {
            Log.i(TAG, "Health monitor started")
            while (isActive) {
                delay(OpenClawConstants.HEALTH_CHECK_INTERVAL_MS)
                checkHealth()
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun markHealthy() {
        lastHealthyTimestamp = System.currentTimeMillis()
    }

    private suspend fun checkHealth() {
        val state = gatewayClient.connectionState.value

        when (state) {
            is GatewayState.Connected -> {
                markHealthy()
            }
            is GatewayState.Disconnected, is GatewayState.Error -> {
                val elapsed = System.currentTimeMillis() - lastHealthyTimestamp
                if (lastHealthyTimestamp > 0 && elapsed > HEALTHY_THRESHOLD_MS) {
                    Log.w(TAG, "Gateway unhealthy for ${elapsed}ms, triggering recovery")
                    onUnhealthy?.invoke()
                }
            }
            else -> { /* connecting/reconnecting — give it time */ }
        }
    }
}
