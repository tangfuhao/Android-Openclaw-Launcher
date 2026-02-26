package com.openclaw.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.openclaw.android.data.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives BOOT_COMPLETED broadcast to auto-start the OpenClaw service
 * when the device boots up (if the user has enabled background mode).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    @Inject lateinit var preferencesManager: PreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!preferencesManager.isRootfsInstalledSync()) {
            Log.i(TAG, "Rootfs not installed, skipping auto-start")
            return
        }

        if (!preferencesManager.isBackgroundEnabledSync()) {
            Log.i(TAG, "Background mode disabled, skipping auto-start")
            return
        }

        Log.i(TAG, "Boot completed, starting OpenClaw service")
        val serviceIntent = OpenClawService.startIntent(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
