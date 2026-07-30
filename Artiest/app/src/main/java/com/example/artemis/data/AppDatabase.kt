package com.example.artemis.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Location history table entity
 */
data class LocationEntity(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val provider: String,
    val timestamp: Long
)

data class CapturedMediaEntity(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val filePath: String,
    val fileSize: Long = 0,
    val mimeType: String,
    val metadata: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)

data class ContactEntity(
    val id: Long = 0,
    val contactId: String,
    val name: String,
    val phoneNumbers: String = "[]",
    val emails: String = "[]",
    val photoUri: String = "",
    val rawJson: String = "{}",
    val capturedAt: Long = System.currentTimeMillis()
)

data class SmsEntity(
    val id: Long = 0,
    val threadId: String,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val capturedAt: Long = System.currentTimeMillis()
)

data class AuthClientEntity(
    val clientId: String,
    val clientName: String,
    val tokenHash: String = "",
    val tokenExpiry: Long = 0,
    val refreshTokenHash: String = "",
    val permissionScope: Int = 0,
    val lastSeen: Long? = null,
    val pairedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

data class PairingCodeEntity(
    val code: String,
    val expiresAt: Long,
    val used: Boolean = false
)

data class SystemEventEntity(
    val id: Long = 0,
    val eventType: String,
    val description: String,
    val severity: String = "info",
    val timestamp: Long = System.currentTimeMillis()
)

class LocationDao(private val db: AppDatabase) {

    fun insert(entity: LocationEntity): Long {
        val values = ContentValues().apply {
            put("latitude", entity.latitude)
            put("longitude", entity.longitude)
            put("accuracy", entity.accuracy)
            put("provider", entity.provider)
            put("timestamp", entity.timestamp)
        }
        return db.writableDatabase.insert(AppDatabase.TABLE_LOCATION, null, values)
    }

    fun getHistory(from: Long, to: Long, limit: Int = 1000, offset: Int = 0): List<LocationEntity> {
        val cursor = db.readableDatabase.rawQuery(
            """SELECT id, latitude, longitude, accuracy, provider, timestamp 
               FROM ${AppDatabase.TABLE_LOCATION} 
               WHERE timestamp >= ? AND timestamp <= ? 
               ORDER BY timestamp DESC 
               LIMIT ? OFFSET ?""",
            arrayOf(from.toString(), to.toString(), limit.toString(), offset.toString())
        )
        val results = mutableListOf<LocationEntity>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(LocationEntity(
                    id = it.getLong(0), latitude = it.getDouble(1),
                    longitude = it.getDouble(2), accuracy = it.getFloat(3),
                    provider = it.getString(4), timestamp = it.getLong(5)
                ))
            }
        }
        return results
    }

    fun getLatest(): LocationEntity? {
        val cursor = db.readableDatabase.rawQuery(
            """SELECT id, latitude, longitude, accuracy, provider, timestamp 
               FROM ${AppDatabase.TABLE_LOCATION} ORDER BY timestamp DESC LIMIT 1""", null
        )
        cursor.use {
            if (it.moveToFirst()) return LocationEntity(
                id = it.getLong(0), latitude = it.getDouble(1),
                longitude = it.getDouble(2), accuracy = it.getFloat(3),
                provider = it.getString(4), timestamp = it.getLong(5)
            )
        }
        return null
    }

    fun pruneOlderThan(timestamp: Long): Int {
        return db.writableDatabase.delete(AppDatabase.TABLE_LOCATION, "timestamp < ?", arrayOf(timestamp.toString()))
    }

    fun getCount(): Int {
        val cursor = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM ${AppDatabase.TABLE_LOCATION}", null)
        cursor.use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }
}

class AppDatabase private constructor(context: Context) {

    val writableDatabase: SQLiteDatabase
    val readableDatabase: SQLiteDatabase
    private val helper: AppSQLiteOpenHelper

    init {
        helper = AppSQLiteOpenHelper(context)
        writableDatabase = helper.writableDatabase
        readableDatabase = helper.readableDatabase
    }

    inner class AppSQLiteOpenHelper(context: Context) : SQLiteOpenHelper(
        context, DATABASE_NAME, null, DATABASE_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE_LOCATION)
            db.execSQL(CREATE_TABLE_CAPTURED_MEDIA)
            db.execSQL(CREATE_TABLE_CONTACTS)
            db.execSQL(CREATE_TABLE_SMS)
            db.execSQL(CREATE_TABLE_AUTH_CLIENTS)
            db.execSQL(CREATE_TABLE_PAIRING_CODES)
            db.execSQL(CREATE_TABLE_SYSTEM_EVENTS)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_location_timestamp ON $TABLE_LOCATION(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_type_created ON $TABLE_CAPTURED_MEDIA(type, created_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_thread ON $TABLE_SMS(thread_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_timestamp ON $TABLE_SYSTEM_EVENTS(timestamp)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LOCATION")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CAPTURED_MEDIA")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CONTACTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SMS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_AUTH_CLIENTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PAIRING_CODES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SYSTEM_EVENTS")
            onCreate(db)
        }
    }

    fun locationDao(): LocationDao = LocationDao(this)

    companion object {
        const val DATABASE_NAME = "artemis_sentinel.db"
        const val DATABASE_VERSION = 1
        const val TABLE_LOCATION = "location_history"
        const val TABLE_CAPTURED_MEDIA = "captured_media"
        const val TABLE_CONTACTS = "contacts_snapshot"
        const val TABLE_SMS = "sms_messages"
        const val TABLE_AUTH_CLIENTS = "auth_clients"
        const val TABLE_PAIRING_CODES = "auth_pairing_codes"
        const val TABLE_SYSTEM_EVENTS = "system_events"

        private const val CREATE_TABLE_LOCATION = """
            CREATE TABLE IF NOT EXISTS $TABLE_LOCATION (
                id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL NOT NULL,
                longitude REAL NOT NULL, accuracy REAL, provider TEXT, timestamp INTEGER NOT NULL
            )"""
        private const val CREATE_TABLE_CAPTURED_MEDIA = """
            CREATE TABLE IF NOT EXISTS $TABLE_CAPTURED_MEDIA (
                id TEXT PRIMARY KEY, type TEXT NOT NULL, file_path TEXT,
                file_size INTEGER, mime_type TEXT, metadata TEXT, created_at INTEGER
            )"""
        private const val CREATE_TABLE_CONTACTS = """
            CREATE TABLE IF NOT EXISTS $TABLE_CONTACTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT, contact_id TEXT, name TEXT,
                phone_numbers TEXT, emails TEXT, photo_uri TEXT, raw_json TEXT, captured_at INTEGER
            )"""
        private const val CREATE_TABLE_SMS = """
            CREATE TABLE IF NOT EXISTS $TABLE_SMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id TEXT, address TEXT,
                body TEXT, date INTEGER, type INTEGER, captured_at INTEGER
            )"""
        private const val CREATE_TABLE_AUTH_CLIENTS = """
            CREATE TABLE IF NOT EXISTS $TABLE_AUTH_CLIENTS (
                client_id TEXT PRIMARY KEY, client_name TEXT, token_hash TEXT,
                token_expiry INTEGER, refresh_token_hash TEXT, permission_scope INTEGER,
                last_seen INTEGER, paired_at INTEGER, is_active INTEGER DEFAULT 1
            )"""
        private const val CREATE_TABLE_PAIRING_CODES = """
            CREATE TABLE IF NOT EXISTS $TABLE_PAIRING_CODES (
                code TEXT PRIMARY KEY, expires_at INTEGER, used INTEGER DEFAULT 0
            )"""
        private const val CREATE_TABLE_SYSTEM_EVENTS = """
            CREATE TABLE IF NOT EXISTS $TABLE_SYSTEM_EVENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT, event_type TEXT,
                description TEXT, severity TEXT, timestamp INTEGER
            )"""

        private var instance: AppDatabase? = null

        @Synchronized
        fun getInstance(context: Context): AppDatabase {
            if (instance == null) {
                instance = AppDatabase(context.applicationContext)
            }
            return instance!!
        }
    }
}
