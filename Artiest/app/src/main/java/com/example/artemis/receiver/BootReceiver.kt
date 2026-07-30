package com.example.artemis.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.artemis.service.ArtemisSentinelService

/**
 * BroadcastReceiver for BOOT_COMPLETED.
 * Starts the Artemis Sentinel foreground service when the device boots up.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start the foreground service on boot
            ArtemisSentinelService.start(context)
        }
    }
}
