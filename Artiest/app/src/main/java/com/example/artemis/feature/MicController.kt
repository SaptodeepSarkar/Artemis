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

    // Live PCM streaming (v2.3.0 video-call mic): one AudioRecord feeding
    // any number of server stream listeners. Chunks are 16-bit mono PCM at
    // SAMPLE_RATE; the dashboard plays them via WebAudio.
    private val liveListeners = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.channels.Channel<ByteArray>>()
    private var liveJob: Job? = null
    private var liveAudioRecord: AudioRecord? = null
    val isStreaming: Boolean get() = liveJob?.isActive == true

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
        if (isStreaming) {
            return@withContext Result.failure(IllegalStateException("Mic is busy streaming to the dashboard"))
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

            // Convert raw PCM to a playable WAV (RIFF header + PCM data).
            val wavFile = convertPcmToWav(file)
            if (wavFile != null && wavFile.exists()) {
                // Update the recording entry to point at the WAV file.
                synchronized(recordings) {
                    val index = recordings.indexOfFirst { it.filePath == file.absolutePath }
                    if (index >= 0) {
                        val updated = recordings[index].copy(
                            durationMs = duration,
                            fileSize = wavFile.length(),
                            mimeType = "audio/wav",
                            filePath = wavFile.absolutePath
                        )
                        recordings[index] = updated
                        return@withContext Result.success(updated)
                    }
                }
            } else {
                // Conversion failed — keep the raw PCM entry.
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
        }

        Result.failure(Exception("Failed to stop recording"))
    }

    /**
     * Wrap raw PCM16 mono data in a WAV container so the file is playable
     * by any player. Returns the new file, or null on failure.
     */
    private fun convertPcmToWav(pcmFile: File): File? {
        return try {
            val pcmBytes = pcmFile.readBytes()
            if (pcmBytes.isEmpty()) return null

            val wavFile = File(recordingsDir, pcmFile.nameWithoutExtension + ".wav")
            val byteRate = SAMPLE_RATE * 2 // 16-bit mono
            val dataSize = pcmBytes.size

            val header = java.io.ByteArrayOutputStream().use { out ->
                out.write("RIFF".toByteArray())
                out.write(intToLeBytes(36 + dataSize))
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(intToLeBytes(16))          // fmt chunk size
                out.write(shortToLeBytes(1))         // PCM
                out.write(shortToLeBytes(1))         // mono
                out.write(intToLeBytes(SAMPLE_RATE))
                out.write(intToLeBytes(byteRate))
                out.write(shortToLeBytes(2))         // block align
                out.write(shortToLeBytes(16))        // bits per sample
                out.write("data".toByteArray())
                out.write(intToLeBytes(dataSize))
                out.toByteArray()
            }

            wavFile.writeBytes(header + pcmBytes)
            pcmFile.delete()
            wavFile
        } catch (e: Exception) {
            android.util.Log.e("MicController", "WAV conversion failed: ${e.message}")
            null
        }
    }

    private fun intToLeBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToLeBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
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

    // ============================================================
    // Live PCM streaming (v2.3.0) — the video-call mic path.
    // A single AudioRecord loop fans out 16-bit mono PCM chunks to every
    // subscribed Channel. No file I/O; stops when the last listener leaves.
    // ============================================================

    /**
     * Subscribe to the live mic stream. Returns a channel of raw PCM chunks
     * (16-bit mono, 44100 Hz) or null when the mic is busy / not permitted.
     * The caller MUST [stopLiveStream] when done (client disconnect).
     */
    fun startLiveStream(): kotlinx.coroutines.channels.Channel<ByteArray>? {
        synchronized(this) {
            if (isRecording) return null // file recording owns the mic
            if (!hasMicPermission()) return null
        }
        val channel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 64)
        liveListeners.add(channel)
        ensureLiveCaptureRunning()
        return channel
    }

    /** Unsubscribe one listener; stops the shared AudioRecord when empty. */
    fun stopLiveStream(channel: kotlinx.coroutines.channels.Channel<ByteArray>) {
        liveListeners.remove(channel)
        channel.close()
        if (liveListeners.isEmpty()) stopLiveCaptureInternal()
    }

    /** Kill every live stream (service shutdown). */
    fun stopAllLiveStreams() {
        liveListeners.forEach { it.close() }
        liveListeners.clear()
        stopLiveCaptureInternal()
    }

    private fun ensureLiveCaptureRunning() {
        synchronized(this) {
            if (liveJob?.isActive == true) return
            if (liveListeners.isEmpty()) return
            if (!hasMicPermission()) return
            try {
                val bufferSize = maxOf(
                    AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                    4096
                )
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
                )
                record.startRecording()
                liveAudioRecord = record
                liveJob = GlobalScope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(bufferSize)
                    try {
                        while (isActive) {
                            val n = record.read(buffer, 0, buffer.size)
                            if (n > 0) {
                                val chunk = buffer.copyOf(n)
                                liveListeners.forEach { ch ->
                                    // Drop the new chunk when a listener is
                                    // slow — live audio prefers fresh over
                                    // complete, and the browser recovers.
                                    ch.trySend(chunk)
                                }
                            } else {
                                delay(10)
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        stopLiveCaptureInternal()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MicController", "Live stream start failed: ${e.message}")
            }
        }
    }

    private fun stopLiveCaptureInternal() {
        synchronized(this) {
            liveJob?.cancel()
            liveJob = null
            try { liveAudioRecord?.stop() } catch (_: Exception) { }
            try { liveAudioRecord?.release() } catch (_: Exception) { }
            liveAudioRecord = null
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

    /** Resolve the on-disk audio file for a recording id, or null. */
    fun getRecordingFile(id: String): File? {
        val rec = getRecording(id) ?: return null
        val f = File(rec.filePath)
        return if (f.exists()) f else null
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
        stopAllLiveStreams()
        if (isRecording) {
            recordingJob?.cancel()
            stopRecordingInternal()
        }
        synchronized(streamBuffer) {
            streamBuffer.clear()
        }
    }
}
