package com.example.artemis.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class CallRecording(
    val id: String,
    val durationMs: Long,
    val fileSize: Long,
    val mimeType: String,
    val createdAt: Long,
    val filePath: String,
    val number: String = "",
    val name: String = ""
)

/**
 * Automatic call recorder. Listens for telephony call-state changes and
 * records the call with MediaRecorder (MIC source — VOICE_CALL upstream is
 * routinely silenced/blocked by OEMs, Samsung included, so MIC is the
 * reliable fallback). MUST run inside the foreground service — recording
 * while the process is in the background without a FGS is not permitted
 * on modern Android.
 *
 * Files land in filesDir/recordings/ as AAC-in-MP4 (.m4a).
 */
class CallRecorder(private val context: Context) {

    private val recordingsDir: File = File(context.filesDir, "recordings").also { it.mkdirs() }
    private val recordings = mutableListOf<CallRecording>()

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var mediaRecorder: MediaRecorder? = null

    private var activeFile: File? = null
    private var activeStartMs: Long = 0L
    private var activeNumber: String = ""
    private var activeName: String = ""
    private var isRecording = false

    @Volatile
    var autoRecordEnabled: Boolean = true

    /** Whether a call is being captured right now. */
    @Volatile
    var isRecordingCall: Boolean = false
        private set

    fun startListening() {
        if (!hasPhonePermission()) return
        try {
            telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber)
                }
            }
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            android.util.Log.e("CallRecorder", "Failed to start listening: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) { }
        telephonyManager = null
        phoneStateListener = null
        stopRecordingInternal(commit = true)
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (autoRecordEnabled && !isRecording) {
                    startRecording(phoneNumber)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isRecording) {
                    stopRecordingInternal(commit = true)
                }
            }
            else -> { /* RINGING: start on answer (OFFHOOK), not on ring */ }
        }
    }

    private fun startRecording(number: String?) {
        try {
            if (!hasMicPermission()) return

            val file = File(recordingsDir, "call_${System.currentTimeMillis()}.m4a")
            val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Prefer VOICE_CALL (in-call audio) but fall back to MIC — on
            // Samsung the upstream is silenced and VOICE_CALL produces a
            // silent file; MIC records the room.
            var source = MediaRecorder.AudioSource.VOICE_CALL
            var output = file
            try {
                recorder.setAudioSource(source)
            } catch (e: Exception) {
                source = MediaRecorder.AudioSource.MIC
                recorder.reset()
                recorder.setAudioSource(source)
            }
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            activeFile = file
            activeStartMs = System.currentTimeMillis()
            activeNumber = number ?: ""
            activeName = lookupContactName(number)
            isRecording = true
            isRecordingCall = true
            android.util.Log.i("CallRecorder", "Recording call (source=$source) to ${file.name}")
        } catch (e: Exception) {
            android.util.Log.e("CallRecorder", "Start failed: ${e.message}")
            try { mediaRecorder?.release() } catch (_: Exception) { }
            mediaRecorder = null
            isRecording = false
            isRecordingCall = false
        }
    }

    private fun stopRecordingInternal(commit: Boolean) {
        val recorder = mediaRecorder ?: return
        val file = activeFile
        val startMs = activeStartMs
        val number = activeNumber
        val name = activeName
        mediaRecorder = null
        activeFile = null
        isRecording = false
        isRecordingCall = false
        if (file == null) return

        var durationMs = 0L
        try {
            recorder.stop()
            durationMs = System.currentTimeMillis() - startMs
        } catch (e: Exception) {
            android.util.Log.w("CallRecorder", "Stop failed (file likely empty): ${e.message}")
            file.delete()
            return
        } finally {
            try { recorder.release() } catch (_: Exception) { }
        }

        if (!commit) return

        val recording = CallRecording(
            id = UUID.randomUUID().toString(),
            durationMs = durationMs,
            fileSize = file.length(),
            mimeType = "audio/mp4",
            createdAt = startMs,
            filePath = file.absolutePath,
            number = number,
            name = name
        )
        synchronized(recordings) {
            recordings.add(0, recording)
        }
        android.util.Log.i("CallRecorder", "Call recording saved: ${file.name} (${durationMs}ms)")
    }

    /** Manually stop an in-progress call recording (dashboard-triggered). */
    suspend fun stopNow(): Result<CallRecording> = withContext(Dispatchers.IO) {
        if (!isRecording) {
            return@withContext Result.failure(IllegalStateException("No active call recording"))
        }
        stopRecordingInternal(commit = true)
        val last = synchronized(recordings) { recordings.firstOrNull() }
        if (last != null) Result.success(last)
        else Result.failure(Exception("Recording failed"))
    }

    suspend fun recordFor(durationMs: Long): Result<CallRecording> = withContext(Dispatchers.IO) {
        startRecording(null)
        if (!isRecording) return@withContext Result.failure(Exception("Could not start recorder"))
        try {
            kotlinx.coroutines.delay(durationMs.coerceIn(1000, 300_000))
        } finally {
            stopRecordingInternal(commit = true)
        }
        val last = synchronized(recordings) { recordings.firstOrNull() }
        if (last != null) Result.success(last)
        else Result.failure(Exception("Recording failed"))
    }

    fun getRecordings(): List<CallRecording> = synchronized(recordings) { recordings.toList() }

    fun getRecording(id: String): CallRecording? =
        synchronized(recordings) { recordings.find { it.id == id } }

    fun getRecordingFile(id: String): File? {
        val rec = getRecording(id) ?: return null
        val f = File(rec.filePath)
        return if (f.exists()) f else null
    }

    private fun lookupContactName(number: String?): String {
        if (number.isNullOrBlank()) return ""
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun release() {
        stopListening()
        try { mediaRecorder?.release() } catch (_: Exception) { }
        mediaRecorder = null
    }
}
