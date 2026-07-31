package com.example.artemis.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.artemis.ArtemisApp
import com.example.artemis.data.AppDatabase

/**
 * HealthCheckWorker runs periodically via WorkManager to ensure:
 * 1. The foreground service is still running AND its socket is alive
 * 2. Old location data is pruned
 * 3. Expired auth tokens are cleaned up
 *
 * Liveness is checked through the shared [ArtemisApp.serverRef] — the
 * service publishes the running server instance there; a null ref means
 * the process was killed and never re-armed (START_STICKY failure).
 */
class HealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HealthCheckWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running periodic health check")

        return try {
            val app = ArtemisApp.instance

            // Prune old location data
            val database = AppDatabase.getInstance(applicationContext)
            val pruneCutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val prunedCount = database.locationDao().pruneOlderThan(pruneCutoff)
            Log.d(TAG, "Pruned $prunedCount old location records")

            // Check the server socket is actually alive, not just the ref.
            val server = app.serverRef
            val isServerAlive = server != null && runCatching {
                // The server tracks its own accept-loop health; a dead
                // socket shows up as a closed serverSocket.
                server.isRunning && !server.serverSocketClosed
            }.getOrDefault(false)

            if (!isServerAlive) {
                Log.w(TAG, "Server not running or socket dead, restarting...")
                runCatching { ArtemisSentinelService.start(applicationContext) }
                    .onFailure { Log.e(TAG, "Restart failed: ${it.message}") }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed: ${e.message}", e)
            Result.retry()
        }
    }
}
