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
    val date: Long,
    val count: Int
)

/**
 * Reads call history from the CallLog.Calls content provider and GROUPS it
 * by (normalised number, type): calling the same person 7 times yields ONE
 * row with count=7 instead of 7 rows. Incoming/outgoing/missed stay in
 * separate rows so direction is always visible.
 * Requires READ_CALL_LOG. Served over the authenticated TLS endpoint only.
 */
class CallLogsProvider(private val context: Context) {

    suspend fun getCallLogs(limit: Int = 100): List<CallLogEntry> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext emptyList()
        }

        // number -> type -> running aggregate
        val groups = LinkedHashMap<String, MutableList<CallLogEntry>>()
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

                while (cursor.moveToNext()) {
                    val typeInt = cursor.getInt(typeCol)
                    val number = cursor.getString(numCol) ?: ""
                    val key = "${normaliseNumber(number)}|$typeInt"
                    val entry = CallLogEntry(
                        id = cursor.getLong(idCol),
                        number = number,
                        cachedName = cursor.getString(nameCol),
                        type = typeName(typeInt),
                        durationSec = cursor.getLong(durCol),
                        date = cursor.getLong(dateCol),
                        count = 1
                    )
                    groups.getOrPut(key) { mutableListOf() }.add(entry)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CallLogs", "Query failed: ${e.message}")
        }

        groups.values
            .map { rows ->
                val first = rows.first() // newest row in the group (DATE DESC)
                CallLogEntry(
                    id = rows.maxOf { it.id },
                    number = first.number,
                    cachedName = rows.mapNotNull { it.cachedName?.takeIf { n -> n.isNotBlank() } }.firstOrNull(),
                    type = first.type,
                    durationSec = rows.sumOf { it.durationSec },
                    date = first.date,
                    count = rows.size
                )
            }
            .sortedByDescending { it.date }
            .take(limit.coerceIn(1, 1000))
    }

    /**
     * Delete one call-log row by its provider id.
     * Requires WRITE_CALL_LOG. NOTE: Android (API 22+) only grants
     * WRITE_CALL_LOG to the default dialer app — if Artemis is not the
     * default, the delete throws SecurityException. The phone dashboard
     * offers a "set as default dialer" action for that.
     */
    suspend fun deleteCallLog(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.failure(SecurityException("WRITE_CALL_LOG not granted"))
        }
        try {
            val deleted = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID}=?", arrayOf(id.toString())
            )
            if (deleted > 0) Result.success(Unit)
            else Result.failure(IllegalStateException(
                "Entry not found or blocked — make Artemis the default dialer to delete call logs"))
        } catch (e: SecurityException) {
            Result.failure(SecurityException(
                "Call log deletion blocked by Android — make Artemis the default dialer first", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normaliseNumber(raw: String): String =
        raw.replace(Regex("[^\\d+]"), "")

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
