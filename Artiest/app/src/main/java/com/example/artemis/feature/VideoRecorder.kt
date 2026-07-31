package com.example.artemis.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume

@Serializable
data class VideoRecording(
    val id: String,
    val cameraId: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val createdAt: Long
)

/**
 * Dashboard-triggered video recording via CameraX VideoCapture.
 * Records MP4 (H.264 + AAC) to filesDir/videos/. The camera is exclusive —
 * photo capture and video recording serialize through the server's camera
 * mutex and unbind after each use.
 */
class VideoRecorder(private val context: Context) {

    private val videosDir: File = File(context.filesDir, "videos").also { it.mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val videos = mutableListOf<VideoRecording>()

    @Volatile
    var isRecording = false
        private set

    private var currentRecording: Recording? = null
    private var currentFile: File? = null
    private var currentStartMs: Long = 0L

    /** Record video for [durationMs] (default 15s, max 5 min). */
    suspend fun record(cameraId: String, durationMs: Long): Result<VideoRecording> =
        withContext(Dispatchers.IO) {
            if (isRecording) {
                return@withContext Result.failure(IllegalStateException("Already recording"))
            }
            if (!hasCameraPermission()) {
                return@withContext Result.failure(SecurityException("Camera permission not granted"))
            }

            val file = File(videosDir, "video_${System.currentTimeMillis()}.mp4")
            currentFile = file
            currentStartMs = System.currentTimeMillis()

            try {
                val lensFacing = if (cameraId == "front" || cameraId == "1") {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                val qualitySelector = try {
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                } catch (e: Exception) {
                    null
                }

                val recorder = if (qualitySelector != null) {
                    Recorder.Builder().setQualitySelector(qualitySelector).build()
                } else {
                    Recorder.Builder().build()
                }
                val videoCapture = VideoCapture.withOutput(recorder)

                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.bindToLifecycle(
                    ProcessLifecycleOwner.get(),
                    cameraSelector,
                    videoCapture
                )

                val outputOptions = FileOutputOptions.Builder(file).build()
                val recording = recorder
                    .prepareRecording(context, outputOptions)
                    .withAudioEnabled()
                    .start(executor) { event -> onVideoEvent(event) }

                currentRecording = recording
                isRecording = true

                // Auto-stop after the requested duration.
                if (durationMs > 0) {
                    delay(durationMs.coerceIn(1000, 300_000))
                    if (isRecording) {
                        try { recording.stop() } catch (_: Exception) { }
                    }
                }

                // Wait for Finalize event to set the final size.
                var finalized = false
                var attempts = 0
                while (!finalized && attempts < 100) {
                    attempts++
                    delay(100)
                    synchronized(videos) {
                        finalized = videos.firstOrNull()?.filePath == file.absolutePath
                    }
                }

                isRecording = false
                try { cameraProvider.unbindAll() } catch (_: Exception) { }

                val entry = synchronized(videos) {
                    videos.find { it.filePath == file.absolutePath }
                }
                if (entry != null) {
                    Result.success(entry)
                } else if (file.exists() && file.length() > 0) {
                    // Finalize may have raced — build the entry from disk.
                    val created = VideoRecording(
                        id = UUID.randomUUID().toString(),
                        cameraId = cameraId,
                        filePath = file.absolutePath,
                        fileSize = file.length(),
                        mimeType = "video/mp4",
                        width = 0,
                        height = 0,
                        durationMs = System.currentTimeMillis() - currentStartMs,
                        createdAt = currentStartMs
                    )
                    synchronized(videos) { videos.add(0, created) }
                    Result.success(created)
                } else {
                    Result.failure(Exception("Video recording produced no output"))
                }
            } catch (e: Exception) {
                isRecording = false
                try {
                    val provider = ProcessCameraProvider.getInstance(context).get()
                    provider.unbindAll()
                } catch (_: Exception) { }
                Result.failure(e)
            }
        }

    private fun onVideoEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Finalize -> {
                val file = currentFile
                if (file != null && file.exists()) {
                    val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    val entry = VideoRecording(
                        id = UUID.randomUUID().toString(),
                        cameraId = "back",
                        filePath = file.absolutePath,
                        fileSize = file.length(),
                        mimeType = "video/mp4",
                        width = 0,
                        height = 0,
                        durationMs = durationMs,
                        createdAt = currentStartMs
                    )
                    synchronized(videos) {
                        if (videos.none { it.filePath == file.absolutePath }) {
                            videos.add(0, entry)
                        }
                    }
                }
                currentRecording = null
                isRecording = false
            }
            else -> { /* Start / status events — no-op */ }
        }
    }

    suspend fun stopNow(): Result<VideoRecording> = withContext(Dispatchers.IO) {
        val rec = currentRecording
        if (rec == null || !isRecording) {
            return@withContext Result.failure(IllegalStateException("No active video recording"))
        }
        try { rec.stop() } catch (_: Exception) { }
        var entry: VideoRecording? = null
        var attempts = 0
        while (attempts < 100) {
            attempts++
            delay(100)
            entry = synchronized(videos) { videos.firstOrNull() }
            if (entry != null) break
        }
        isRecording = false
        if (entry != null) Result.success(entry)
        else Result.failure(Exception("Recording failed"))
    }

    fun getVideos(): List<VideoRecording> = synchronized(videos) { videos.toList() }

    fun getVideo(id: String): VideoRecording? =
        synchronized(videos) { videos.find { it.id == id } }

    fun getVideoFile(id: String): File? {
        val v = getVideo(id) ?: return null
        val f = File(v.filePath)
        return if (f.exists()) f else null
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun release() {
        try { currentRecording?.stop() } catch (_: Exception) { }
        currentRecording = null
        isRecording = false
    }
}
