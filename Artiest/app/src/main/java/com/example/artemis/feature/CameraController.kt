package com.example.artemis.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class CameraDeviceInfo(
    val id: String,
    val name: String,
    val facing: String,
    val supportedResolutions: List<String> = emptyList()
)

@Serializable
data class MediaCapture(
    val id: String,
    val cameraId: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val createdAt: Long
)

class CameraController(
    private val context: Context,
    /** Lifecycle the camera binds to. MUST stay >= STARTED as long as the
     *  app is closed — the foreground service's lifecycle (LifecycleService)
     *  satisfies that; ProcessLifecycleOwner drops below STARTED when no
     *  Activity is open and the camera would die. */
    private val lifecycleOwner: LifecycleOwner
) {

    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()
    private val capturesDir: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val captures = mutableListOf<MediaCapture>()

    init {
        capturesDir = File(context.filesDir, "captures").also { it.mkdirs() }
        // Rebuild the in-memory catalogue from disk so captures survive
        // service restarts (the file list is the source of truth).
        try {
            capturesDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f ->
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, options)
                    captures.add(
                        MediaCapture(
                            id = f.name.removeSuffix(".jpg").removePrefix("photo_"),
                            cameraId = if (f.name.startsWith("photo_front_")) "front" else "back",
                            filePath = f.absolutePath,
                            fileSize = f.length(),
                            mimeType = "image/jpeg",
                            width = options.outWidth,
                            height = options.outHeight,
                            createdAt = f.lastModified()
                        )
                    )
                }
        } catch (_: Exception) { }
    }

    /** List available cameras */
    fun getCameraList(): List<CameraDeviceInfo> {
        if (!hasCameraPermission()) return emptyList()
        return listOf(
            CameraDeviceInfo(
                id = "front",
                name = "Front Camera",
                facing = "front"
            ),
            CameraDeviceInfo(
                id = "back",
                name = "Rear Camera",
                facing = "back"
            )
        )
    }

    /**
     * Capture a photo from the specified camera.
     * @param cameraId "front", "back", "0" (back) or "1" (front)
     */
    suspend fun capturePhoto(cameraId: String): Result<MediaCapture> = withContext(Dispatchers.IO) {
        if (!hasCameraPermission()) {
            return@withContext Result.failure(SecurityException("Camera permission not granted"))
        }

        val lensFacing = when (cameraId) {
            "front", "1" -> CameraSelector.LENS_FACING_FRONT
            else -> CameraSelector.LENS_FACING_BACK
        }

        // The camera is exclusive — serialize captures so a photo and a
        // video recording never bind the same camera simultaneously.
        cameraMutex.withLock {
            capturePhotoLocked(lensFacing)
        }
    }

    private suspend fun capturePhotoLocked(lensFacing: Int): Result<MediaCapture> {
        try {
                // A photo capture rebinds the camera — release the live
                // preview stream first so the bind is exclusive.
                stopPreviewStream()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                val cameraProvider = ProcessCameraProvider.getInstance(context).get()

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setTargetRotation(android.view.Surface.ROTATION_0)
                    .build()

                // Bind to lifecycle — CameraX REQUIRES the main thread
                // (checkMainThread in bindToLifecycle) and a lifecycle that
                // stays STARTED with the app closed (the service's).
                val preview = Preview.Builder().build()
                withContext(Dispatchers.Main.immediate) {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        imageCapture
                    )
                }

                // Capture
                val facingTag = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
                val photoFile = File(capturesDir, "photo_${facingTag}_${UUID.randomUUID()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                val captureResult = suspendCancellableCoroutine<Result<MediaCapture>> { cont ->
                    imageCapture.takePicture(
                        outputOptions,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val mediaCapture = MediaCapture(
                                    id = UUID.randomUUID().toString(),
                                    cameraId = facingTag,
                                    filePath = output.savedUri?.path ?: photoFile.absolutePath,
                                    fileSize = photoFile.length(),
                                    mimeType = "image/jpeg",
                                    width = 0, // would need BitmapFactory for actual dimensions
                                    height = 0,
                                    createdAt = System.currentTimeMillis()
                                )
                                cont.resume(Result.success(mediaCapture))
                            }

                            override fun onError(exception: ImageCaptureException) {
                                cont.resume(Result.failure(exception))
                            }
                        }
                    )
                }

                // Unbind camera — always, even on failure (main thread req.)
                withContext(Dispatchers.Main.immediate) {
                    try { cameraProvider.unbindAll() } catch (_: Exception) { }
                }

                if (captureResult.isSuccess) {
                    val capture = captureResult.getOrThrow()

                    // Get actual dimensions
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(photoFile.absolutePath, options)

                    val sizedCapture = capture.copy(
                        width = options.outWidth,
                        height = options.outHeight
                    )

                    synchronized(captures) {
                        captures.add(0, sizedCapture)
                    }

                    return Result.success(sizedCapture)
                }

                return captureResult
            } catch (e: Exception) {
                android.util.Log.e("ArtemisCam", "capturePhotoLocked failed", e)
                return Result.failure(e)
            }
    }

    /**
     * Capture a single frame as JPEG bytes for streaming / the camera feed.
     * Uses ImageCapture in a simplified mode.
     * @param lensFacing [CameraSelector.LENS_FACING_BACK] or
     * [CameraSelector.LENS_FACING_FRONT].
     */
    suspend fun captureFrame(
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        maxDimension: Int = 0,
        quality: Int = 85
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!hasCameraPermission()) return@withContext null

        try {
            // Release any active live preview before rebinding the camera.
            stopPreviewStream()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val cameraProvider = ProcessCameraProvider.getInstance(context).get()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            withContext(Dispatchers.Main.immediate) {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )
            }

            // Capture to byte array using OnImageCapturedCallback
            val result = suspendCancellableCoroutine<ByteArray?> { cont ->
                imageCapture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bytes = imageProxyToJpeg(image)
                            image.close()
                            cont.resume(bytes)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            cont.resume(null)
                        }
                    }
                )
            }

            withContext(Dispatchers.Main.immediate) {
                cameraProvider.unbindAll()
            }
            return@withContext downscaleIfNeeded(result, maxDimension, quality)
        } catch (e: Exception) {
            android.util.Log.e("ArtemisCam", "captureFrame failed", e)
            return@withContext null
        }
    }

    /**
     * Downscale + recompress a full-res JPEG when the caller asked for a
     * smaller frame (live streaming). Returns the original bytes when
     * maxDimension <= 0 or the image is already small enough.
     */
    private fun downscaleIfNeeded(jpeg: ByteArray?, maxDimension: Int, quality: Int): ByteArray? {
        if (jpeg == null || maxDimension <= 0) return jpeg
        return try {
            val src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val scale = maxDimension.toFloat() / maxOf(src.width, src.height)
            if (scale >= 1f) {
                src.recycle()
                return jpeg
            }
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
            src.recycle()
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            scaled.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            android.util.Log.w("ArtemisCam", "downscale failed: ${e.message}")
            jpeg
        }
    }

    // ============================================================
    // Live preview stream (v2.3.1 — smooth WebSocket video)
    // A persistent ImageAnalysis binding (640x480) converts every frame
    // to a small JPEG on its own executor; the WS session polls the
    // latest frame instead of doing a slow bind→capture→unbind per
    // frame (that was ~0.5 fps on the M51 — this runs 8–12 fps).
    // ============================================================

    private val previewMutex = Any()
    private var previewActive = false
    private var previewLens = CameraSelector.LENS_FACING_BACK
    private var latestPreviewJpeg: ByteArray? = null
    private var latestPreviewSeq = 0L
    private var previewProvider: ProcessCameraProvider? = null
    private var previewExecutor: java.util.concurrent.ExecutorService? = null

    fun isPreviewStreaming(): Boolean = synchronized(previewMutex) { previewActive }

    /** Sequence of the newest analyser frame (monotonic; -1 when idle). */
    fun previewSeq(): Long = synchronized(previewMutex) {
        if (previewActive) latestPreviewSeq else -1L
    }

    /** Latest JPEG from the analyser, or null. */
    fun readLatestPreviewFrame(): ByteArray? = synchronized(previewMutex) { latestPreviewJpeg }

    /** Switch the live preview to another lens (front/back). */
    suspend fun switchPreviewLens(lensFacing: Int) {
        synchronized(previewMutex) {
            if (!previewActive || previewLens == lensFacing) return
        }
        startPreviewStream(lensFacing)
    }

    /**
     * Bind ImageAnalysis and stream JPEG frames into the latest-frame
     * buffer. Returns false when the camera permission is missing or the
     * bind fails (caller should surface a clear error, not crash).
     */
    suspend fun startPreviewStream(lensFacing: Int): Boolean {
        if (!hasCameraPermission()) return false
        synchronized(previewMutex) {
            if (previewActive && previewLens == lensFacing) return true
            if (previewActive) stopPreviewStreamLocked()
        }
        return try {
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()

            val executor = Executors.newSingleThreadExecutor()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val jpeg = yuvToJpeg(image, 55)
                    synchronized(previewMutex) {
                        if (previewActive && previewLens == lensFacing) {
                            latestPreviewJpeg = jpeg
                            latestPreviewSeq++
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }

            var bound = false
            withContext(Dispatchers.Main.immediate) {
                try {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        analysis
                    )
                    bound = true
                } catch (_: Exception) {
                    bound = false
                }
            }
            if (!bound) {
                executor.shutdown()
                return false
            }
            synchronized(previewMutex) {
                previewActive = true
                previewLens = lensFacing
                previewProvider = cameraProvider
                previewExecutor = executor
                latestPreviewJpeg = null
                latestPreviewSeq = 0L
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("ArtemisCam", "startPreviewStream failed: ${e.message}")
            false
        }
    }

    /** Stop the live preview and release the camera binding. */
    suspend fun stopPreviewStream() {
        synchronized(previewMutex) {
            stopPreviewStreamLocked()
        }
    }

    private fun stopPreviewStreamLocked() {
        val provider = previewProvider
        val executor = previewExecutor
        previewProvider = null
        previewExecutor = null
        previewActive = false
        latestPreviewJpeg = null
        latestPreviewSeq = 0L
        if (executor != null) {
            try { executor.shutdown() } catch (_: Exception) {}
        }
        if (provider != null) {
            // unbindAll must run on the main thread; post and forget —
            // the next bind on the main dispatcher serializes after it.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { provider.unbindAll() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Convert a YUV_420_888 ImageProxy to a JPEG ByteArray (NV21 → YuvImage).
     * Applies the image's rotation metadata via decode→rotate→re-encode;
     * at 640x480 that is ~10 ms — cheap enough at 10 fps.
     */
    private fun yuvToJpeg(image: ImageProxy, quality: Int): ByteArray {
        val width = image.width
        val height = image.height
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                nv21[pos++] = yBuffer.get(rowStart + col * yPixStride)
            }
        }
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride
        val uvWidth = width / 2
        val uvHeight = height / 2
        for (row in 0 until uvHeight) {
            val rowStart = row * uvRowStride
            for (col in 0 until uvWidth) {
                val uvIdx = rowStart + col * uvPixStride
                nv21[pos++] = vBuffer.get(uvIdx)
                nv21[pos++] = uBuffer.get(uvIdx)
            }
        }

        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
        val raw = out.toByteArray()

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        return try {
            val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            src.recycle()
            val out2 = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, quality, out2)
            rotated.recycle()
            out2.toByteArray()
        } catch (_: Exception) {
            raw
        }
    }

    fun getCaptures(): List<MediaCapture> {
        rescanDisk()
        synchronized(captures) {
            return captures.toList()
        }
    }

    fun getCapture(id: String): MediaCapture? {
        rescanDisk()
        synchronized(captures) {
            return captures.find { it.id == id }
        }
    }

    /** Resolve the on-disk JPEG for a capture id, or null. */
    fun getCaptureFile(id: String): File? {
        val capture = getCapture(id) ?: return null
        val f = File(capture.filePath)
        return if (f.exists()) f else null
    }

    /**
     * Re-scan the captures dir so photos taken by any process (or restored
     * after a service restart) appear in the catalogue.
     */
    private fun rescanDisk() {
        try {
            val known = synchronized(captures) { captures.map { it.filePath }.toSet() }
            capturesDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f ->
                    if (f.absolutePath !in known) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(f.absolutePath, options)
                        synchronized(captures) {
                            captures.add(
                                MediaCapture(
                                    id = f.name.removeSuffix(".jpg").removePrefix("photo_"),
                                    cameraId = if (f.name.startsWith("photo_front_")) "front" else "back",
                                    filePath = f.absolutePath,
                                    fileSize = f.length(),
                                    mimeType = "image/jpeg",
                                    width = options.outWidth,
                                    height = options.outHeight,
                                    createdAt = f.lastModified()
                                )
                            )
                        }
                    }
                }
        } catch (_: Exception) { }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /**
         * Process-wide camera gate: photo capture and video recording both
         * bind the camera — never concurrently. The server wraps both
         * handlers in this lock as well.
         */
        val cameraMutex = kotlinx.coroutines.sync.Mutex()
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val yuvImage = YuvImage(
            bytes,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, outputStream)
        return outputStream.toByteArray()
    }
}
