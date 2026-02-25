package com.openclaw.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.openclaw.android.core.OpenClawConstants
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenClawApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OpenClawConstants.SERVICE_NOTIFICATION_CHANNEL_ID,
                OpenClawConstants.SERVICE_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the OpenClaw gateway running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
