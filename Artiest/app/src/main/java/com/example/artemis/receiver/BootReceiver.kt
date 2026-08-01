package com.example.artemis.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.artemis.service.ArtemisSentinelService

/**
 * BroadcastReceiver for BOOT_COMPLETED.
 * Starts the Artemis Sentinel foreground service when the device boots up.
 *
 * NOTE: On Samsung One UI, BOOT_COMPLETED is only delivered if the app is
 * NOT in the "Sleeping apps" / "Deep sleeping apps" list and has
 * "Allow auto-launching" enabled (Settings → Apps → Artemis → Battery).
 * The dashboard's battery banner deep-links there. If the service does not
 * start after a reboot, check logcat for "BootReceiver: fired" first.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "fired — starting ArtemisSentinelService")
            try {
                ArtemisSentinelService.start(context)
                Log.i(TAG, "start() returned OK")
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException on some OEMs
                Log.e(TAG, "start() FAILED: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
