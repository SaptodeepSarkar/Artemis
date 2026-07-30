package com.example.artemis.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.artemis.ArtemisApp
import com.example.artemis.auth.AuthManager
import com.example.artemis.data.AppDatabase
import com.example.artemis.feature.CameraController
import com.example.artemis.feature.DeviceInfoProvider
import com.example.artemis.feature.LocationTracker
import com.example.artemis.feature.MicController
import com.example.artemis.server.SimpleHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class ArtemisSentinelService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var app: ArtemisApp
    private lateinit var authManager: AuthManager
    private lateinit var database: AppDatabase
    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var locationTracker: LocationTracker
    private lateinit var cameraController: CameraController
    private lateinit var micController: MicController
    private lateinit var artemisServer: SimpleHttpServer

    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.artemis.action.START"
        const val ACTION_STOP = "com.example.artemis.action.STOP"
        const val ACTION_RESTART = "com.example.artemis.action.RESTART"

        fun start(context: Context) {
            val intent = Intent(context, ArtemisSentinelService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ArtemisSentinelService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = ArtemisApp.instance
        authManager = AuthManager(app)
        database = AppDatabase.getInstance(this)
        deviceInfoProvider = DeviceInfoProvider(this)
        locationTracker = LocationTracker(this, database)
        cameraController = CameraController(this)
        micController = MicController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            ACTION_RESTART -> {
                stopServer()
                startServer()
            }
            else -> {
                if (!isRunning) startServer()
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (isRunning) return
        isRunning = true

        startForeground(NOTIFICATION_ID, createNotification(0))
        acquireWakeLock()

        artemisServer = SimpleHttpServer(
            app = app,
            authManager = authManager,
            deviceInfoProvider = deviceInfoProvider,
            locationTracker = locationTracker,
            cameraController = cameraController,
            micController = micController
        )

        serviceScope.launch {
            try {
                android.util.Log.i("ArtemisSvc", "Starting ArtemisServer...")
                artemisServer.start()
                android.util.Log.i("ArtemisSvc", "ArtemisServer started, starting location tracking...")
                startLocationTracking()
                android.util.Log.i("ArtemisSvc", "Scheduling health check...")
                scheduleHealthCheck()
                android.util.Log.i("ArtemisSvc", "Updating notification...")
                updateNotification(0)
                android.util.Log.i("ArtemisSvc", "Server startup complete.")
            } catch (e: Exception) {
                android.util.Log.e("ArtemisSvc", "Server start FAILED: ${e.message}", e)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        isRunning = false
        serviceScope.launch {
            try {
                artemisServer.stop()
            } catch (_: Exception) { }
            locationTracker.stopLocationUpdates()
            micController.release()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startLocationTracking() {
        locationTracker.startLocationUpdates()
    }

    private fun scheduleHealthCheck() {
        val healthCheckRequest = PeriodicWorkRequestBuilder<HealthCheckWorker>(
            15, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "artemis_health_check",
            ExistingPeriodicWorkPolicy.KEEP,
            healthCheckRequest
        )
    }

    private fun createNotification(activeClients: Int): Notification {
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val contentText = if (activeClients > 0) {
            "$deviceName — $activeClients connected"
        } else {
            "$deviceName — No clients connected"
        }
        return NotificationCompat.Builder(this, ArtemisApp.CHANNEL_SERVICE)
            .setContentTitle("Artemis Sentinel Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(activeClients: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(activeClients))
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ArtemisSentinel:ServerWakeLock"
            )
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) { }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }
}
