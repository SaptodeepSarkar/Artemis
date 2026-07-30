package com.example.artemis.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Serializable
data class AudioRecording(
    val id: String,
    val durationMs: Long,
    val fileSize: Long,
    val mimeType: String,
    val createdAt: Long,
    val filePath: String
)

class MicController(private val context: Context) {

    private val recordingsDir: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val recordings = mutableListOf<AudioRecording>()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var currentRecordingFile: File? = null
    private var currentRecordingStartTime: Long = 0L

    // Ring buffer for live streaming
    private val streamBuffer = ArrayDeque<ByteArray>(10)

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    init {
        recordingsDir = File(context.filesDir, "recordings").also { it.mkdirs() }
    }

    /**
     * Start recording audio.
     * @param durationMs Maximum recording duration in milliseconds
     */
    suspend fun startRecording(durationMs: Long): Result<AudioRecording> = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            return@withContext Result.failure(SecurityException("Microphone permission not granted"))
        }

        if (isRecording) {
            return@withContext Result.failure(IllegalStateException("Already recording"))
        }

        try {
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                4096
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            val recordFile = File(recordingsDir, "recording_${UUID.randomUUID()}.pcm")
            currentRecordingFile = recordFile
            currentRecordingStartTime = System.currentTimeMillis()
            isRecording = true

            audioRecord?.startRecording()

            recordingJob = GlobalScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                val fileOutputStream = FileOutputStream(recordFile)
                val startTime = System.currentTimeMillis()

                try {
                    while (isActive && isRecording) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (bytesRead > 0) {
                            fileOutputStream.write(buffer, 0, bytesRead)

                            // Add to stream buffer
                            synchronized(streamBuffer) {
                                val chunk = buffer.copyOf(bytesRead)
                                streamBuffer.addLast(chunk)
                                if (streamBuffer.size > 10) {
                                    streamBuffer.removeFirst()
                                }
                            }
                        }

                        // Check duration limit
                        if (durationMs > 0 && System.currentTimeMillis() - startTime > durationMs) {
                            break
                        }

                        // Small delay to prevent busy-loop
                        if (bytesRead <= 0) delay(10)
                    }
                } catch (e: Exception) {
                    // Recording error
                } finally {
                    try {
                        fileOutputStream.close()
                    } catch (_: Exception) { }
                    stopRecordingInternal()
                }
            }

            // Wait a moment for recording to start
            delay(200)

            val recording = AudioRecording(
                id = UUID.randomUUID().toString(),
                durationMs = 0, // updated on stop
                fileSize = 0,
                mimeType = "audio/pcm",
                createdAt = currentRecordingStartTime,
                filePath = recordFile.absolutePath
            )

            synchronized(recordings) {
                recordings.add(0, recording)
            }

            Result.success(recording)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Stop the current recording.
     */
    suspend fun stopRecording(): Result<AudioRecording> = withContext(Dispatchers.IO) {
        if (!isRecording) {
            return@withContext Result.failure(IllegalStateException("No active recording"))
        }

        recordingJob?.cancel()
        stopRecordingInternal()

        val file = currentRecordingFile
        if (file != null && file.exists()) {
            val duration = System.currentTimeMillis() - currentRecordingStartTime

            // Update the recording entry
            synchronized(recordings) {
                val index = recordings.indexOfFirst { it.filePath == file.absolutePath }
                if (index >= 0) {
                    val updated = recordings[index].copy(
                        durationMs = duration,
                        fileSize = file.length(),
                        mimeType = "audio/pcm"
                    )
                    recordings[index] = updated
                    return@withContext Result.success(updated)
                }
            }
        }

        Result.failure(Exception("Failed to stop recording"))
    }

    /**
     * Read a chunk of audio data for WebSocket streaming.
     * Returns null if no data available.
     */
    fun readAudioChunk(): ByteArray? {
        synchronized(streamBuffer) {
            return streamBuffer.removeFirstOrNull()
        }
    }

    fun getRecordings(): List<AudioRecording> {
        synchronized(recordings) {
            return recordings.toList()
        }
    }

    fun getRecording(id: String): AudioRecording? {
        synchronized(recordings) {
            return recordings.find { it.id == id }
        }
    }

    private fun stopRecordingInternal() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) { }
        try {
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
        isRecording = false
        recordingJob = null
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Clean up resources */
    fun release() {
        if (isRecording) {
            recordingJob?.cancel()
            stopRecordingInternal()
        }
        synchronized(streamBuffer) {
            streamBuffer.clear()
        }
    }
}
