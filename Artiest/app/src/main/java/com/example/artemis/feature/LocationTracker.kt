package com.example.artemis.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.example.artemis.data.AppDatabase
import com.example.artemis.data.LocationEntity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

@Serializable
data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val provider: String?,
    val timestamp: Long
)

class LocationTracker(
    private val context: Context,
    private val database: AppDatabase
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    @Volatile
    private var lastLocation: LocationPoint? = null

    @Volatile
    private var callback: LocationCallback? = null

    companion object {
        private const val UPDATE_INTERVAL_MS = 600_000L       // 10 minutes
        private const val FASTEST_INTERVAL_MS = 300_000L      // 5 minutes
        private const val MAX_WAIT_MS = 900_000L               // 15 minutes
        private const val MAX_ACCURACY_METERS = 100.0f
        private const val HISTORY_RETENTION_DAYS = 30
    }

    /** Start periodic location updates */
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            setMaxUpdateDelayMillis(MAX_WAIT_MS)
            setWaitForAccurateLocation(true)
        }.build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val point = toLocationPoint(location)
                    lastLocation = point
                    scope.launch {
                        saveLocation(point)
                    }
                }
            }
        }

        this.callback = locationCallback

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                context.mainLooper
            )
        } catch (e: SecurityException) {
            // Permissions revoked
        }
    }

    /** Stop periodic location updates */
    fun stopLocationUpdates() {
        callback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (_: Exception) { }
        }
        callback = null
    }

    /**
     * Get the current location.
     * Returns cached value if fresh (< 30s) unless [forceFresh] is set
     * (dashboard live-tracking poll).
     */
    suspend fun getCurrentLocation(forceFresh: Boolean = false): LocationPoint? = withContext(Dispatchers.IO) {
        // Return cached if fresh enough
        if (!forceFresh) {
            lastLocation?.let { loc ->
                if (System.currentTimeMillis() - loc.timestamp < 30_000L) {
                    return@withContext loc
                }
            }
        }

        if (!hasLocationPermission()) return@withContext lastLocation

        try {
            val location = fusedLocationClient.lastLocation.await()
            if (location != null) {
                val point = toLocationPoint(location)
                lastLocation = point
                return@withContext point
            }
        } catch (_: Exception) { }

        lastLocation
    }

    /** Get location history with optional date range */
    suspend fun getLocationHistory(
        from: Long? = null,
        to: Long? = null,
        limit: Int = 1000,
        offset: Int = 0
    ): List<LocationPoint> = withContext(Dispatchers.IO) {
        val entities = database.locationDao().getHistory(
            from = from ?: (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(HISTORY_RETENTION_DAYS.toLong())),
            to = to ?: System.currentTimeMillis(),
            limit = limit,
            offset = offset
        )
        entities.map { it.toPoint() }
    }

    /** Save a location point to the database */
    private suspend fun saveLocation(point: LocationPoint) {
        if (point.accuracy > MAX_ACCURACY_METERS) return

        database.locationDao().insert(
            LocationEntity(
                latitude = point.latitude,
                longitude = point.longitude,
                accuracy = point.accuracy,
                provider = point.provider ?: "unknown",
                timestamp = point.timestamp
            )
        )

        // Prune old records
        database.locationDao().pruneOlderThan(
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(HISTORY_RETENTION_DAYS.toLong())
        )
    }

    /** Trigger an immediate location capture (used by WorkManager) */
    fun captureLocation() {
        if (!hasLocationPermission()) return

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val point = toLocationPoint(location)
                    lastLocation = point
                    scope.launch {
                        saveLocation(point)
                    }
                }
            }
        } catch (_: SecurityException) { }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun toLocationPoint(location: Location): LocationPoint {
        return LocationPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            provider = location.provider,
            timestamp = location.time
        )
    }

    private fun LocationEntity.toPoint(): LocationPoint {
        return LocationPoint(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            provider = provider,
            timestamp = timestamp
        )
    }
}

/**
 * Suspend extension to await a Task result
 */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? {
    return withContext(Dispatchers.IO) {
        try {
            com.google.android.gms.tasks.Tasks.await(this@await, 10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }
}
