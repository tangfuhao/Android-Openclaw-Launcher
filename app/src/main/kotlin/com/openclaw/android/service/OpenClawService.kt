package com.openclaw.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openclaw.android.MainActivity
import com.openclaw.android.R
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.gateway.GatewayClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the OpenClaw Gateway process alive.
 *
 * Responsibilities:
 * - Maintain a persistent notification so Android doesn't kill us
 * - Hold a partial wake lock to prevent CPU sleep
 * - Start/stop the gateway process
 * - Monitor health and trigger restarts on crash
 * - Connect the WebSocket operator client
 */
@AndroidEntryPoint
class OpenClawService : Service() {

    companion object {
        private const val TAG = "OpenClawService"
        private const val WAKE_LOCK_TAG = "openclaw::gateway"

        const val ACTION_START = "com.openclaw.android.action.START"
        const val ACTION_STOP = "com.openclaw.android.action.STOP"

        fun startIntent(context: Context): Intent =
            Intent(context, OpenClawService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, OpenClawService::class.java).apply { action = ACTION_STOP }
    }

    @Inject lateinit var processManager: ProcessManager
    @Inject lateinit var gatewayClient: GatewayClient
    @Inject lateinit var healthMonitor: HealthMonitor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(
                    OpenClawConstants.SERVICE_NOTIFICATION_ID,
                    buildNotification("Starting OpenClaw...")
                )
                acquireWakeLock()
                launchGateway()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun launchGateway() {
        serviceScope.launch {
            val started = processManager.startGateway()
            if (started) {
                updateNotification("OpenClaw is running")
                connectOperator()
                startHealthMonitoring()
                observeProcessState()
            } else {
                updateNotification("Failed to start gateway")
            }
        }
    }

    private fun connectOperator() {
        serviceScope.launch {
            gatewayClient.connect()
        }
    }

    private fun startHealthMonitoring() {
        healthMonitor.start(serviceScope) {
            Log.w(TAG, "Health check failed, attempting restart")
            gatewayClient.disconnect()
            val recovered = processManager.restartWithBackoff()
            if (recovered) {
                gatewayClient.connect()
            } else {
                updateNotification("Gateway crashed — tap to retry")
            }
        }
    }

    private fun observeProcessState() {
        serviceScope.launch {
            processManager.processState.collect { state ->
                when (state) {
                    is ProcessManager.ProcessState.Running -> {
                        updateNotification("OpenClaw is running")
                        healthMonitor.markHealthy()
                    }
                    is ProcessManager.ProcessState.Crashed -> {
                        updateNotification("Gateway crashed, restarting...")
                        gatewayClient.disconnect()
                        processManager.restartWithBackoff()
                    }
                    is ProcessManager.ProcessState.Error -> {
                        updateNotification("Error: ${state.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun shutdown() {
        Log.i(TAG, "Shutting down service")
        healthMonitor.stop()
        gatewayClient.disconnect()
        processManager.stopGateway()
        releaseWakeLock()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OpenClawConstants.SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OpenClaw")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(OpenClawConstants.SERVICE_NOTIFICATION_ID, notification)
    }
}
