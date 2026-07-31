package com.example.artemis.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class SmsEntry(
    val id: Long,
    val address: String,
    val person: String?,
    val date: Long,
    val read: Boolean,
    val body: String
)

/**
 * Reads SMS from the Telephony SMS content provider.
 * Requires READ_SMS (requested at first launch with all other
 * permissions). Message bodies are REDACTED by default — the dashboard
 * must explicitly ask with ?includeBody=1 to receive message text.
 */
class SmsProvider(private val context: Context) {

    private val boxes = mapOf(
        "inbox" to Telephony.Sms.Inbox.CONTENT_URI,
        "sent" to Telephony.Sms.Sent.CONTENT_URI,
        "draft" to Telephony.Sms.Draft.CONTENT_URI,
        "outbox" to Telephony.Sms.Outbox.CONTENT_URI,
        "failed" to Telephony.Sms.Outbox.CONTENT_URI,
        "all" to Telephony.Sms.CONTENT_URI
    )

    suspend fun getSms(
        box: String = "inbox",
        limit: Int = 100,
        includeBody: Boolean = false
    ): List<SmsEntry> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext emptyList()
        }

        val uri = boxes[box.lowercase()] ?: Telephony.Sms.Inbox.CONTENT_URI
        val entries = mutableListOf<SmsEntry>()
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.PERSON,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.BODY
            )
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addrCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val personCol = cursor.getColumnIndexOrThrow(Telephony.Sms.PERSON)
                val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val readCol = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)

                var count = 0
                while (cursor.moveToNext() && count < limit.coerceIn(1, 1000)) {
                    val body = cursor.getString(bodyCol) ?: ""
                    entries.add(
                        SmsEntry(
                            id = cursor.getLong(idCol),
                            address = cursor.getString(addrCol) ?: "",
                            person = cursor.getString(personCol),
                            date = cursor.getLong(dateCol),
                            read = cursor.getInt(readCol) != 0,
                            // Privacy: bodies stay on the phone unless the
                            // dashboard explicitly requests them.
                            body = if (includeBody) body else "[redacted]"
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Sms", "Query failed: ${e.message}")
        }
        entries
    }
}
