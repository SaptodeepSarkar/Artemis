package com.example.artemis.feature

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Screen capture helper (v2.3.0).
 *
 * Android's only consent-free screen capture path is the Accessibility
 * service (takeScreenshot, API 30+) — MediaProjection REQUIRES a
 * per-session user consent dialog that cannot be bypassed or re-granted
 * programmatically. The UX (documented in docs/handoff.md):
 *
 *   - PRIMARY (no prompts): the user enables "Artemis" under Settings →
 *     Accessibility ONCE. After that the server can capture whenever it
 *     wants, with the app closed — fully automatic.
 *   - FALLBACK: the old MediaProjection consent flow below, kept for
 *     devices/APIs where the accessibility path is unavailable.
 *
 * MediaProjection fallback flow:
 *
 *   1. The user opens the app ONCE and taps "Screen Capture -> Enable" on
 *      the admin dashboard (DashboardScreen). This launches the system
 *      createScreenCaptureIntent() consent dialog.
 *   2. On approval the Activity forwards resultCode + data to
 *      ScreenCaptureService (a foreground service with type
 *      mediaProjection — REQUIRED on Android 14+), which calls
 *      [start].
 *   3. From then on the helper captures on demand — the consent is cached
 *      for the lifetime of the projection; the dashboard's
 *      POST /api/v1/screen/capture just works, even with the app UI closed.
 *   4. POST /api/v1/screen/stop (or the notification action / system UI)
 *      ends the projection; the next capture needs a fresh consent.
 *
 * Capture backend selection:
 *   - PRIMARY: RemoteControlService (AccessibilityService.takeScreenshot,
 *     API 30+) — NO consent dialog, fully automatic, works with the app
 *     closed. Enabled once in Settings (or via adb for automation).
 *   - FALLBACK: MediaProjection (still requires a per-session consent
 *     dialog and dies when revoked; kept for devices/APIs where the
 *     accessibility path is unavailable).
 *
 * The MediaProjection path is FGS-only: it holds no Activity reference.
 * Frames are taken via a [VirtualDisplay] + [ImageReader] and returned as
 * JPEG bytes — no HTTP knowledge, no UI.
 */
class ScreenCaptureController(private val context: Context) {

    @Volatile private var mediaProjection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True when any capture backend is available. */
    val isEnabled: Boolean
        get() = RemoteControlService.instance != null || mediaProjection != null

    /** True when captures can succeed right now. */
    val isActive: Boolean
        get() = RemoteControlService.instance != null || virtualDisplay != null

    /** Active backend: "accessibility" | "media_projection" | "none". */
    val method: String
        get() = when {
            RemoteControlService.instance != null -> "accessibility"
            mediaProjection != null -> "media_projection"
            else -> "none"
        }

    /**
     * Adopt the user's consent (resultCode + data from the system consent
     * dialog). MUST be called from a running foreground service (Android 14+
     * throws SecurityException if called from an Activity context).
     * Returns false if the token is unusable.
     */
    fun start(resultCode: Int, data: Intent?): Boolean {
        if (mediaProjection != null) return true // already consented

        if (resultCode != Activity.RESULT_OK || data == null) return false

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }
            .getOrNull() ?: return false

        // Android 14+ requires a callback registered immediately after
        // creation; the system may stop an unregistered projection.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                android.util.Log.i("ArtemisServer", "MediaProjection stopped by system/user")
                releaseInternals()
            }
        }
        projection.registerCallback(callback, mainHandler)

        mediaProjection = projection
        android.util.Log.i("ArtemisServer", "Screen capture consent adopted — projection active")
        return true
    }

    /**
     * Capture the current screen as JPEG bytes, or null on failure / no
     * backend. PRIMARY: accessibility service (no consent, automatic).
     * FALLBACK: MediaProjection virtual display, created lazily on the
     * first capture and kept until [stop].
     */
    @Synchronized
    fun captureFrame(): ByteArray? {
        val rcs = RemoteControlService.instance
        if (rcs != null) {
            return try {
                rcs.capture()
            } catch (e: Exception) {
                android.util.Log.w("ArtemisServer", "Accessibility capture failed: ${e.message}")
                null
            }
        }
        return captureViaProjection()
    }

    private fun captureViaProjection(): ByteArray? {
        val projection = mediaProjection ?: return null
        var image: Image? = null
        try {
            ensureDisplay(projection)

            val reader = imageReader ?: return null

            // The first frame arrives a moment after the display is created;
            // poll briefly for a new image.
            image = reader.acquireLatestImage()
            val deadline = System.currentTimeMillis() + 2000L
            while (image == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                image = reader.acquireLatestImage()
            }
            if (image == null) return null

            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val bitmap = Bitmap.createBitmap(
                image.width, image.height, Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return out.toByteArray()
        } catch (e: Exception) {
            android.util.Log.w("ArtemisServer", "Screen capture failed: ${e.message}")
            return null
        } finally {
            try {
                image?.close()
            } catch (_: Exception) { }
        }
    }

    /** Tear down the projection and all capture surfaces (idempotent). */
    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) { }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) { }
        imageReader = null
        mediaProjection?.let { p ->
            try { p.stop() } catch (_: Exception) { }
        }
        mediaProjection = null
        android.util.Log.i("ArtemisServer", "Screen capture stopped — consent cleared")
    }

    private fun ensureDisplay(projection: MediaProjection) {
        if (virtualDisplay != null && imageReader != null) return

        val (w, h) = captureSize()
        val dpi = context.resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val display = projection.createVirtualDisplay(
            "ArtemisScreenCapture",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
        imageReader = reader
        virtualDisplay = display
        android.util.Log.i("ArtemisServer", "Virtual display created ${w}x$h @ $dpi dpi")
    }

    /** Real display size, capped at [MAX_WIDTH] to keep captures fast. */
    private fun captureSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        if (w > MAX_WIDTH) {
            val scale = MAX_WIDTH.toFloat() / w
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        // ImageReader sizes must be even.
        return (w and 0x7FFFFFFE) to (h and 0x7FFFFFFE)
    }

    private fun releaseInternals() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        mediaProjection = null
    }

    companion object {
        private const val MAX_WIDTH = 1280

        @Volatile
        private var instance: ScreenCaptureController? = null

        /**
         * Process-wide singleton. The controller must be created with an
         * application context so it outlives any single Activity/Service and
         * can be shared between the server, the consent service and the UI.
         */
        fun get(context: Context): ScreenCaptureController {
            return instance ?: synchronized(this) {
                instance ?: ScreenCaptureController(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
