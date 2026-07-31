package com.example.artemis.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class CallLogEntry(
    val id: Long,
    val number: String,
    val cachedName: String?,
    val type: String,
    val durationSec: Long,
    val date: Long
)

/**
 * Reads call history from the CallLog.Calls content provider.
 * Requires READ_CALL_LOG (requested at first launch with all other
 * permissions). Served over the authenticated TLS endpoint only.
 */
class CallLogsProvider(private val context: Context) {

    suspend fun getCallLogs(limit: Int = 100): List<CallLogEntry> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext emptyList()
        }

        val entries = mutableListOf<CallLogEntry>()
        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val durCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

                var count = 0
                while (cursor.moveToNext() && count < limit.coerceIn(1, 1000)) {
                    val typeInt = cursor.getInt(typeCol)
                    entries.add(
                        CallLogEntry(
                            id = cursor.getLong(idCol),
                            number = cursor.getString(numCol) ?: "",
                            cachedName = cursor.getString(nameCol),
                            type = typeName(typeInt),
                            durationSec = cursor.getLong(durCol),
                            date = cursor.getLong(dateCol)
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CallLogs", "Query failed: ${e.message}")
        }
        entries
    }

    private fun typeName(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "unknown"
    }
}
