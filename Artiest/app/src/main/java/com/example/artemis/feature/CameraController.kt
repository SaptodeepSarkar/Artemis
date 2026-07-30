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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

class CameraController(private val context: Context) {

    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()
    private val capturesDir: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val captures = mutableListOf<MediaCapture>()

    init {
        capturesDir = File(context.filesDir, "captures").also { it.mkdirs() }
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
     * @param cameraId "front", "back", or "0"
     */
    suspend fun capturePhoto(cameraId: String): Result<MediaCapture> = withContext(Dispatchers.IO) {
        if (!hasCameraPermission()) {
            return@withContext Result.failure(SecurityException("Camera permission not granted"))
        }

        try {
            val lensFacing = when (cameraId) {
                "front" -> CameraSelector.LENS_FACING_FRONT
                else -> CameraSelector.LENS_FACING_BACK
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val cameraProvider = ProcessCameraProvider.getInstance(context).get()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()

            // Bind to lifecycle
            val preview = Preview.Builder().build()
            val camera = cameraProvider.bindToLifecycle(
                ProcessLifecycleOwner.get(),
                cameraSelector,
                imageCapture
            )

            // Capture
            val photoFile = File(capturesDir, "photo_${UUID.randomUUID()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            val captureResult = suspendCancellableCoroutine<Result<MediaCapture>> { cont ->
                imageCapture.takePicture(
                    outputOptions,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val mediaCapture = MediaCapture(
                                id = UUID.randomUUID().toString(),
                                cameraId = cameraId,
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

            // Unbind camera
            cameraProvider.unbindAll()

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

                return@withContext Result.success(sizedCapture)
            }

            return@withContext captureResult
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /**
     * Capture a single frame as JPEG bytes for WebSocket streaming.
     * Uses ImageCapture in a simplified mode.
     */
    suspend fun captureFrame(): ByteArray? = withContext(Dispatchers.IO) {
        if (!hasCameraPermission()) return@withContext null

        try {
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val cameraProvider = ProcessCameraProvider.getInstance(context).get()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            cameraProvider.bindToLifecycle(
                ProcessLifecycleOwner.get(),
                cameraSelector,
                imageCapture
            )

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

            cameraProvider.unbindAll()
            return@withContext result
        } catch (_: Exception) {
            return@withContext null
        }
    }

    fun getCaptures(): List<MediaCapture> {
        synchronized(captures) {
            return captures.toList()
        }
    }

    fun getCapture(id: String): MediaCapture? {
        synchronized(captures) {
            return captures.find { it.id == id }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
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
