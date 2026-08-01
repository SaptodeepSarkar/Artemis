package com.example.artemis.feature

import android.content.Context
import androidx.camera.core.CameraSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class CameraFeedStatus(
    val active: Boolean,
    val intervalMs: Long,
    val durationMs: Long,
    val startedAt: Long,
    val framesCaptured: Int,
    val lastFrameAt: Long?,
    val cameraId: String
)

/**
 * Repeating camera feed (v2.3.0 helper).
 *
 * Runs a capture loop on its own coroutine: every [intervalMs] it takes a
 * single frame (via [CameraController.captureFrame]) and keeps only the
 * LATEST JPEG in memory — no disk writes, so the loop can run for minutes
 * without filling storage. The dashboard pulls `GET /api/v1/camera/feed/latest`
 * to render a live-ish view.
 *
 * Camera exclusivity: every frame is taken under [CameraController.cameraMutex],
 * the same process-wide gate used by photo capture and video recording, so a
 * feed never fights a photo or a video for the camera.
 *
 * FGS-only by design: this class holds no Activity reference and works with
 * the app UI closed (the foreground service keeps the server alive).
 */
class CameraFeedController(
    private val context: Context,
    private val cameraController: CameraController
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    private val feedJob = AtomicReference<Job?>(null)

    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var lastFrameAt: Long = 0L
    @Volatile private var framesCaptured = 0
    @Volatile private var intervalMs = 0L
    @Volatile private var durationMs = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var cameraId = "back"

    val isActive: Boolean get() = feedJob.get()?.isActive == true

    /**
     * Start the repeating feed.
     * @return true if started, false if a feed is already running.
     */
    fun start(intervalMs: Long, durationMs: Long, cameraId: String): Boolean {
        synchronized(this) {
            if (isActive) return false

            this.intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
            // duration 0 = run until stopped.
            this.durationMs = durationMs.coerceIn(0L, MAX_DURATION_MS)
            this.cameraId = if (cameraId == "front" || cameraId == "1") "front" else "back"
            framesCaptured = 0
            latestFrame = null
            lastFrameAt = 0L
            startedAt = System.currentTimeMillis()

            val lensFacing = if (this.cameraId == "front") {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

            feedJob.set(scope.launch {
                val endAt = if (durationMs > 0) startedAt + durationMs else Long.MAX_VALUE
                while (isActive && System.currentTimeMillis() < endAt) {
                    CameraController.cameraMutex.withLock {
                        cameraController.captureFrame(lensFacing)?.let { frame ->
                            latestFrame = frame
                            lastFrameAt = System.currentTimeMillis()
                            framesCaptured++
                        }
                    }
                    if (System.currentTimeMillis() >= endAt) break
                    delay(this@CameraFeedController.intervalMs)
                }
                // Loop exits on its own when the duration elapses (or on stop).
                android.util.Log.i("ArtemisServer", "Camera feed finished " +
                        "(${framesCaptured} frames)" +
                        if (durationMs > 0) " — duration reached" else " — stopped")
            })
            android.util.Log.i("ArtemisServer", "Camera feed started " +
                    "(${this.intervalMs}ms interval, ${this.durationMs}ms duration, ${this.cameraId})")
            return true
        }
    }

    /** Stop the feed (idempotent). */
    fun stop() {
        synchronized(this) {
            feedJob.getAndSet(null)?.cancel()
        }
    }

    /** Latest JPEG frame, or null if the feed never produced one. */
    fun latestFrame(): ByteArray? = latestFrame

    fun status(): CameraFeedStatus {
        val job = feedJob.get()
        return CameraFeedStatus(
            active = job?.isActive == true,
            intervalMs = intervalMs,
            durationMs = durationMs,
            startedAt = startedAt,
            framesCaptured = framesCaptured,
            lastFrameAt = lastFrameAt.takeIf { it > 0 },
            cameraId = cameraId
        )
    }

    /** Stop the feed and cancel the scope (service shutdown). */
    fun release() {
        stop()
        scopeJob.cancel()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 500L
        private const val MAX_INTERVAL_MS = 60_000L
        private const val MAX_DURATION_MS = 3_600_000L // 1 h cap
    }
}
