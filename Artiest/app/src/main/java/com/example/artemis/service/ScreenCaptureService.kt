package com.example.artemis.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.artemis.ArtemisApp
import com.example.artemis.feature.ScreenCaptureController

/**
 * Foreground service that adopts the MediaProjection consent token.
 *
 * Android 14+ (API 34) REQUIRES `MediaProjectionManager.getMediaProjection()`
 * to be called from a foreground service whose declared type includes
 * `mediaProjection` — an Activity context throws SecurityException. This
 * service is that FGS: the DashboardScreen activity forwards the consent
 * result here, the projection is created, and [ScreenCaptureController]
 * (process-wide singleton) serves frames to the HTTP server on demand.
 *
 * Lifecycle:
 *  - ACTION_START (extras: resultCode + data from the consent dialog):
 *    startForeground with the mediaProjection type, then
 *    ScreenCaptureController.start(resultCode, data).
 *  - ACTION_STOP: release the projection, stopForeground, stopSelf.
 */
class ScreenCaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        controller = ScreenCaptureController.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                startWithProjectionType()
                val ok = controller?.start(resultCode, data) ?: false
                android.util.Log.i("ArtemisSvc",
                    "Screen capture consent ${if (ok) "adopted" else "FAILED to adopt"}")
                if (!ok) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                controller?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Restarted by the system after death: nothing to project
                // (the token dies with the process) — shut down quietly.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * API 34+: the service must run with the `mediaProjection` type BEFORE
     * getMediaProjection() is called. The mask passed here MUST be a subset
     * of the manifest `foregroundServiceType` attribute — this service
     * declares only `mediaProjection`, so only that bit is passed (passing
     * the main service's full mask throws IllegalArgumentException).
     */
    private fun startWithProjectionType() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ArtemisApp.CHANNEL_SERVICE)
            .setContentTitle("Artemis Sentinel — Screen Capture Active")
            .setContentText("Consent granted — the dashboard can capture the screen. Tap to stop.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.example.artemis.action.SCREEN_CAPTURE_START"
        private const val ACTION_STOP = "com.example.artemis.action.SCREEN_CAPTURE_STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        @Volatile
        private var controller: ScreenCaptureController? = null

        /**
         * Forward the consent result (from the system dialog) to the
         * mediaProjection foreground service. Call from the Activity's
         * onActivityResult. [data] is the Intent returned by
         * `createScreenCaptureIntent()`.
         */
        fun startConsent(context: Context, resultCode: Int, data: Intent?) {
            if (data == null) return
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Release the projection (used by the server / UI stop button). */
        fun stopConsent() {
            controller?.stop()
        }
    }
}
