package com.example.artemis.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable

@Serializable
data class ContactEntry(
    val id: String,
    val name: String,
    val number: String?,
    val photoUri: String?
)

/**
 * Contacts helper (v2.3.0) — READ_CONTACTS-backed directory read.
 *
 * Dedupes per contact (a contact with several numbers appears once, first
 * number wins). Returns null when the permission is missing so the endpoint
 * can answer a clean `permission_denied` instead of crashing.
 *
 * FGS-only: no Activity reference.
 */
class ContactsProvider(private val context: Context) {

    /** @return contacts (first number per contact), or null if READ_CONTACTS
     *  is not granted. */
    fun getContacts(limit: Int = 500): List<ContactEntry>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val result = mutableListOf<ContactEntry>()
        val seenIds = HashSet<String>()

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
            )
            val sort = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                sort
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

                while (cursor.moveToNext() && result.size < limit) {
                    val id = cursor.getString(idIdx) ?: continue
                    if (!seenIds.add(id)) continue
                    result.add(
                        ContactEntry(
                            id = id,
                            name = cursor.getString(nameIdx) ?: "(unnamed)",
                            number = cursor.getString(numIdx),
                            photoUri = cursor.getString(photoIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ArtemisServer", "Contacts read failed: ${e.message}")
            return null
        }
        return result
    }
}
