package com.example.artemis.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

/**
 * Triple recorder (v2.3.3): one-button RECORD of the SCREEN (accessibility
 * takeScreenshot loop), the FRONT camera and the REAR camera, all at once,
 * into three MP4s under <filesDir>/data/record/{screen,front,rear}/.
 *
 * FGS-only: no Activity, no HTTP knowledge (house style). Frames come from:
 *  - screen: [RemoteControlService.capture] (the SAME accessibility
 *    takeScreenshot the preview uses — zero consent) at ~2 fps, downscaled
 *    to ≤960 px wide, drawn onto the encoder's input surface.
 *  - cameras: the [CameraController.recordSink] hook — the live preview's
 *    analyzer delivers NV21 frames per lens, plus a second-lens ImageAnalysis
 *    session when the device supports concurrent cameras. If the device
 *    rejects the second lens, that folder simply stays empty (handoff rule).
 *
 * Encoders: raw MediaCodec H.264 (no new deps). Camera sessions take
 * byte-buffer input (NV21 → the codec's preferred YUV420 format); the screen
 * session takes an input surface. PTS = System.nanoTime() (monotonic); the
 * muxer gets KEY_ROTATION from the encoder output format.
 */
class TripleRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraController: CameraController
) {
    companion object {
        const val MEDIA_SCREEN = "screen"
        const val MEDIA_FRONT = "front"
        const val MEDIA_REAR = "rear"

        const val LENS_BACK = CameraSelector.LENS_FACING_BACK
        const val LENS_FRONT = CameraSelector.LENS_FACING_FRONT

        private const val CAM_W = 640
        private const val CAM_H = 480
        private const val CAM_BITRATE = 4_000_000
        private const val FRAME_RATE = 15
        private const val IFRAME_INTERVAL = 1
        private const val SCREEN_MAX_WIDTH = 960
        private const val SCREEN_BITRATE = 3_500_000
        private const val SCREEN_FRAME_DELAY_MS = 450L

        private const val TAG = "ArtemisRec"
    }

    private data class RecSession(
        val media: String,
        val file: File,
        val encoder: MediaCodec,
        val muxer: MediaMuxer,
        var width: Int,
        var height: Int,
        var rotation: Int = 0,
        val colorFormat: Int = -1,   // camera (byte-buffer) sessions only
        val inputSurface: Surface? = null, // screen session only
        var trackIndex: Int = -1,
        var muxerStarted: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val sessions = mutableMapOf<String, RecSession>()

    @Volatile private var recording = false
    @Volatile private var startedAtMs = 0L

    /** Lens ids currently being captured (LENS_BACK/LENS_FRONT); empty when only screen. */
    @Volatile var activeLenses: List<Int> = emptyList()
        private set

    private var screenThread: Thread? = null

    private var screenW = 540
    private var screenH = 1200

    fun isRecording(): Boolean = recording

    /**
     * Start all three recorders. Returns immediately (handoff rule); the
     * camera bindings and screen loop run on worker coroutines. [preferredLens]
     * is the lens the live preview uses (or BACK for HTTP starts) — the
     * opposite lens is attempted as the second recording source.
     */
    fun start(preferredLens: Int): Map<String, Any?> {
        synchronized(lock) {
            if (recording) return mapOf("status" to "already_recording")
            recording = true
            startedAtMs = System.currentTimeMillis()
        }
        val epoch = startedAtMs
        val root = File(context.filesDir, "data/record")

        // Screen session (encoder + input surface).
        computeScreenSize()
        val screenSession = try {
            createScreenSession(File(root, MEDIA_SCREEN), epoch)
        } catch (e: Exception) {
            Log.e(TAG, "screen session init failed: ${e.message}")
            null
        }
        // Camera sessions (both are created; frames decide which fills).
        val rearSession = try {
            createCameraSession(File(root, MEDIA_REAR), epoch)
        } catch (e: Exception) {
            Log.e(TAG, "rear session init failed: ${e.message}")
            null
        }
        val frontSession = try {
            createCameraSession(File(root, MEDIA_FRONT), epoch)
        } catch (e: Exception) {
            Log.e(TAG, "front session init failed: ${e.message}")
            null
        }
        synchronized(lock) {
            if (screenSession != null) sessions[MEDIA_SCREEN] = screenSession
            if (rearSession != null) sessions[MEDIA_REAR] = rearSession
            if (frontSession != null) sessions[MEDIA_FRONT] = frontSession
        }

        val primaryLens = if (preferredLens == LENS_FRONT) LENS_FRONT else LENS_BACK
        val otherLens = if (primaryLens == LENS_BACK) LENS_FRONT else LENS_BACK

        scope.launch {
            // Wire the camera sources: reuse the live preview frames when the
            // WS is streaming; otherwise start our own preview (the WS can
            // then piggyback the same feed if it starts mid-recording).
            if (cameraController.isPreviewStreaming()) {
                cameraController.recordSink = ::onFrame
                activeLenses = listOf(primaryLens)
                if (cameraController.startSecondPreviewStream(otherLens)) {
                    activeLenses = listOf(primaryLens, otherLens)
                }
            } else {
                if (cameraController.startPreviewStream(primaryLens)) {
                    cameraController.recordSink = ::onFrame
                    activeLenses = listOf(primaryLens)
                    if (cameraController.startSecondPreviewStream(otherLens)) {
                        activeLenses = listOf(primaryLens, otherLens)
                    }
                } else {
                    activeLenses = emptyList() // camera unavailable — screen only
                }
            }
        }

        screenSession?.let { session ->
            screenThread = Thread({ screenLoop(session) }, "artemis-screen-record").also { it.start() }
        }

        Log.i(TAG, "start: lenses=$activeLenses screen=${screenSession != null} rear=${rearSession != null} front=${frontSession != null}")
        return mapOf("status" to "started", "recording" to true, "lenses" to activeLenses)
    }

    /** Stop everything, finalize the MP4s, return {"status":"saved","paths":{media:[names]}}. */
    fun stop(): Map<String, Any?> {
        synchronized(lock) {
            if (!recording) return mapOf("status" to "not_recording")
            recording = false
        }
        scope.launch {
            cameraController.recordSink = null
            cameraController.stopSecondPreviewStream()
        }

        val screenThread = screenThread
        screenThread?.join(3000)
        this.screenThread = null

        val saved = mutableMapOf<String, MutableList<String>>()
        synchronized(lock) {
            for (s in sessions.values.toList()) {
                if (s.media == MEDIA_SCREEN) continue // finalized by the capture loop
                synchronized(s) {
                    try { s.encoder.signalEndOfInputStream() } catch (_: Exception) {}
                    drainEncoder(s, eos = true)
                }
                finalizeSession(s)
                if (s.muxerStarted && s.file.exists() && s.file.length() > 0) {
                    saved.getOrPut(s.media) { mutableListOf() }.add(s.file.name)
                } else {
                    try { s.file.delete() } catch (_: Exception) {} // empty folder rule
                }
            }
            sessions.clear()
        }
        Log.i(TAG, "stop: saved=$saved")
        return mapOf("status" to "saved", "paths" to saved)
    }

    fun status(): Map<String, Any?> = mapOf(
        "recording" to recording,
        "startedAt" to startedAtMs,
        "lenses" to activeLenses,
        "secondLens" to cameraController.secondLensFacing()
    )

    fun list(): Map<String, List<Map<String, Any>>> {
        val root = File(context.filesDir, "data/record")
        return mapOf(
            MEDIA_SCREEN to listDir(File(root, MEDIA_SCREEN)),
            MEDIA_FRONT to listDir(File(root, MEDIA_FRONT)),
            MEDIA_REAR to listDir(File(root, MEDIA_REAR))
        )
    }

    /** Resolve a recording file by media folder + filename (path-traversal safe). */
    fun getFile(media: String, name: String): File? {
        if (media != MEDIA_SCREEN && media != MEDIA_FRONT && media != MEDIA_REAR) return null
        if (!name.matches(Regex("rec_\\d+\\.mp4"))) return null
        val f = File(File(context.filesDir, "data/record/$media"), name)
        return if (f.isFile) f else null
    }

    /**
     * Called by the server when the WS FLIPs the preview lens mid-recording:
     * the second binding must track the lens the preview is NOT holding, so
     * both MP4s keep receiving their own lens's frames.
     */
    fun onPreviewLensChanged(activeLens: Int) {
        if (!recording) return
        scope.launch {
            val other = if (activeLens == LENS_BACK) LENS_FRONT else LENS_BACK
            if (cameraController.secondLensFacing() == other) return@launch
            cameraController.stopSecondPreviewStream()
            if (cameraController.startSecondPreviewStream(other)) {
                activeLenses = listOf(activeLens, other)
            } else {
                activeLenses = listOf(activeLens)
            }
        }
    }

    fun release() {
        if (recording) stop()
    }

    // ---- session plumbing -------------------------------------------------

    private fun createCameraSession(dir: File, epoch: Long): RecSession {
        dir.mkdirs()
        val file = File(dir, "rec_$epoch.mp4")
        val codec = MediaCodec.createEncoderByType("video/avc")
        val caps = codec.codecInfo.getCapabilitiesForType("video/avc")
        val colorFormat = pickColorFormat(caps)
        check(colorFormat != -1) { "no YUV420 color format supported" }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, CAM_W, CAM_H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, CAM_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return RecSession(
            media = if (dir.name == MEDIA_FRONT) MEDIA_FRONT else MEDIA_REAR,
            file = file, encoder = codec, muxer = muxer,
            width = CAM_W, height = CAM_H, colorFormat = colorFormat
        )
    }

    private fun createScreenSession(dir: File, epoch: Long): RecSession {
        dir.mkdirs()
        val file = File(dir, "rec_$epoch.mp4")
        val codec = MediaCodec.createEncoderByType("video/avc")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenW, screenH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, SCREEN_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 5)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return RecSession(
            media = MEDIA_SCREEN, file = file, encoder = codec, muxer = muxer,
            width = screenW, height = screenH, inputSurface = inputSurface
        )
    }

    private fun computeScreenSize() {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) {
                wm.currentWindowMetrics.bounds
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Rect(0, 0, wm.defaultDisplay.width, wm.defaultDisplay.height)
            }
            val fullW = bounds.width()
            val fullH = bounds.height()
            val scale = if (fullW > SCREEN_MAX_WIDTH) SCREEN_MAX_WIDTH.toFloat() / fullW else 1f
            screenW = (fullW * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
            screenH = (fullH * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
            screenW = screenW.coerceAtLeast(2)
            screenH = screenH.coerceAtLeast(2)
        } catch (e: Exception) {
            Log.w(TAG, "screen size fallback: ${e.message}")
            screenW = 540
            screenH = 1200
        }
    }

    private fun pickColorFormat(caps: MediaCodecInfo.CodecCapabilities): Int {
        val preferred = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        for (p in preferred) {
            if (caps.colorFormats.contains(p)) return p
        }
        return -1
    }

    // ---- frame entry ------------------------------------------------------

    /** CameraController.recordSink callback (analyzer thread per session). */
    private var frameCount = 0L
    private var dropCount = 0L

    private fun onFrame(lens: Int, nv21: ByteArray, w: Int, h: Int, rotation: Int) {
        if (!recording) return
        val media = if (lens == LENS_BACK) MEDIA_REAR else MEDIA_FRONT
        val session = synchronized(lock) { sessions[media] } ?: return
        synchronized(session) {
            if (!recording) return
            if (++frameCount % 20 == 1L) {
                Log.i(TAG, "onFrame lens=$lens media=$media w=${nv21.size} drop=$dropCount")
            }
            feedCamera(session, nv21, w, h, rotation)
        }
    }

    private fun feedCamera(session: RecSession, nv21: ByteArray, w: Int, h: Int, rotation: Int) {
        // ImageAnalysis resolutions vary per device/lens (the M51 delivers
        // full-res YUV, e.g. ~1156x1024 — NOT the 640x480 target). Reconfigure
        // the encoder to the real frame size on the first frame.
        if (session.width != w || session.height != h || session.rotation != rotation) {
            reconfigureCamera(session, w, h, rotation)
        }
        val codec = session.encoder
        val inIdx = try { codec.dequeueInputBuffer(0) } catch (e: Exception) {
            Log.w(TAG, "dequeueInputBuffer threw: ${e.message}")
            return
        }
        if (inIdx < 0) { dropCount++; return } // encoder busy — drop frame
        val buf = try { codec.getInputBuffer(inIdx) } catch (_: Exception) { return } ?: return
        buf.clear()
        try {
            if (session.colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                nv21ToNv12(nv21, w, h, buf)
            } else {
                nv21ToI420(nv21, w, h, buf)
            }
        } catch (e: Exception) {
            Log.w(TAG, "convert threw: ${e.message}")
            return
        }
        val pts = System.nanoTime() / 1000
        try {
            codec.queueInputBuffer(inIdx, 0, w * h * 3 / 2, pts, 0)
            drainEncoder(session, eos = false)
        } catch (e: Exception) {
            Log.w(TAG, "queueInputBuffer threw: ${e.message}")
        }
    }

    /** Re-target the encoder to the actual ImageAnalysis resolution. Called
     * once per session on the first frame; MediaCodec supports stop() →
     * configure() → start() reuse. The muxer is untouched (no output yet). */
    private fun reconfigureCamera(session: RecSession, w: Int, h: Int, rotation: Int) {
        try { session.encoder.stop() } catch (_: Exception) {}
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, session.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, CAM_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // No KEY_LEVEL: full-res frames (e.g. 1156x1024@15) exceed fixed
            // levels — let the encoder pick the level for the size.
            setInteger(MediaFormat.KEY_ROTATION, rotation)
        }
        session.encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        session.encoder.start()
        session.width = w
        session.height = h
        session.rotation = rotation
        Log.i(TAG, "reconfigured ${session.media} to ${w}x${h} rot=$rotation fmt=${session.colorFormat}")
    }

    private fun nv21ToNv12(nv21: ByteArray, w: Int, h: Int, out: ByteBuffer) {
        val ySize = w * h
        out.put(nv21, 0, ySize)
        val chromaStart = ySize
        val uvCount = w * h / 4
        for (i in 0 until uvCount) {
            out.put(nv21[chromaStart + i * 2 + 1]) // U first (NV12 = Y + UV)
            out.put(nv21[chromaStart + i * 2])     // V second
        }
    }

    private fun nv21ToI420(nv21: ByteArray, w: Int, h: Int, out: ByteBuffer) {
        val ySize = w * h
        out.put(nv21, 0, ySize)
        val chromaStart = ySize
        val uvCount = w * h / 4
        for (i in 0 until uvCount) out.put(nv21[chromaStart + i * 2 + 1]) // U plane
        for (i in 0 until uvCount) out.put(nv21[chromaStart + i * 2])     // V plane
    }

    // ---- screen loop ------------------------------------------------------

    private fun screenLoop(session: RecSession) {
        val rcs = RemoteControlService.instance
        while (recording && synchronized(lock) { sessions.containsKey(MEDIA_SCREEN) }) {
            val jpeg = try { rcs?.capture() } catch (e: Exception) { null }
            if (jpeg != null) {
                val bmp = try { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) } catch (e: Exception) { null }
                if (bmp != null) {
                    val scaled = if (bmp.width != session.width || bmp.height != session.height) {
                        Bitmap.createScaledBitmap(bmp, session.width, session.height, true)
                    } else {
                        bmp
                    }
                    val surface = session.inputSurface
                    if (surface != null && recording) {
                        try {
                            val canvas = surface.lockCanvas(null)
                            canvas.drawBitmap(scaled, null, Rect(0, 0, session.width, session.height), null)
                            surface.unlockCanvasAndPost(canvas)
                            synchronized(session) { drainEncoder(session, eos = false) }
                        } catch (e: Exception) {
                            Log.w(TAG, "screen frame drop: ${e.message}")
                        }
                    }
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                }
            }
            try { Thread.sleep(SCREEN_FRAME_DELAY_MS) } catch (_: InterruptedException) { break }
        }
        // Finalize on the loop thread (it owns the surface encoder).
        synchronized(session) {
            try { session.encoder.signalEndOfInputStream() } catch (_: Exception) {}
            drainEncoder(session, eos = true)
        }
        finalizeSession(session)
        val ok = session.muxerStarted && session.file.exists() && session.file.length() > 0
        if (!ok) {
            try { session.file.delete() } catch (_: Exception) {}
        }
        Log.i(TAG, "screen loop done, file=${session.file.name} ok=$ok")
    }

    // ---- encode → mux -----------------------------------------------------

    private fun drainEncoder(session: RecSession, eos: Boolean) {
        val codec = session.encoder
        val info = MediaCodec.BufferInfo()
        var timeoutUs = if (eos) 10_000L else 0L
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!session.muxerStarted) {
                        session.trackIndex = session.muxer.addTrack(codec.outputFormat)
                        session.muxer.start()
                        session.muxerStarted = true
                    }
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* no-op */ }
                idx < 0 -> { Log.w(TAG, "codec event $idx (${session.media})") }
                idx >= 0 -> {
                    if (session.muxerStarted) {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            session.muxer.writeSampleData(session.trackIndex, buf, info)
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
                else -> { /* INFO_OUTPUT_BUFFERS_CHANGED etc. — no-op */ }
            }
            timeoutUs = 0L
        }
    }

    private fun finalizeSession(session: RecSession) {
        try { session.encoder.stop() } catch (_: Exception) {}
        try { session.encoder.release() } catch (_: Exception) {}
        try { session.inputSurface?.release() } catch (_: Exception) {}
        try {
            if (session.muxerStarted) session.muxer.stop()
        } catch (_: Exception) {}
        try { session.muxer.release() } catch (_: Exception) {}
    }

    private fun listDir(dir: File): List<Map<String, Any>> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { mapOf("name" to it.name, "size" to it.length(), "mtime" to it.lastModified()) }
            ?: emptyList())
}
