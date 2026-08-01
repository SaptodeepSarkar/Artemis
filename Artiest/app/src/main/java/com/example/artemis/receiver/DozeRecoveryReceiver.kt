package com.example.artemis.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.artemis.ArtemisApp
import com.example.artemis.service.ArtemisSentinelService

/**
 * Re-arms the Artemis server after Doze / Samsung-freezer events.
 *
 * Samsung's aggressive battery management (and standard Doze for
 * non-whitelisted apps) can freeze the server socket while the screen is
 * off. When the user comes back (screen on / unlock / power event /
 * connectivity change), this receiver checks whether the server actually
 * answers on the loopback port and restarts the foreground service if it
 * doesn't.
 *
 * Registered DYNAMICALLY by [ArtemisSentinelService] (a manifest receiver
 * for USER_PRESENT / SCREEN_ON cannot execute on Android 12+ for
 * backgrounded apps — "Background execution not allowed").
 *
 * This is a belt-and-braces complement to:
 * - battery-optimization exemption (the real fix — see the dashboard's
 *   "BATTERY OPTIMIZATION OFF" banner / auto-prompt), and
 * - the WakeLock held by [ArtemisSentinelService].
 */
class DozeRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in REARM_ACTIONS) return
        Log.d(TAG, "Event: $action")

        // Startup grace: broadcasts that arrive while the server is still
        // binding (e.g. CONNECTIVITY_CHANGE right after BOOT_COMPLETED)
        // would fail the probe and cause a pointless restart loop.
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastRegistration < STARTUP_GRACE_MS) {
            Log.d(TAG, "Startup grace — skipping probe")
            return
        }
        // Restart cooldown: don't hammer startForegroundService if the
        // server is down for a while (e.g. socket frozen, recovery in
        // progress); one restart per window is enough.
        if (elapsed - lastRestartAttempt < RESTART_COOLDOWN_MS) {
            Log.d(TAG, "Restart cooldown — skipping probe")
            return
        }

        // Probe the socket (not just the object state) — a frozen socket
        // can still report "open" while refusing to answer.
        val answers = probeLoopback()
        if (answers) {
            Log.d(TAG, "Server answers on loopback — no action needed")
            return
        }

        Log.w(TAG, "Server not answering after $action — restarting service")
        lastRestartAttempt = elapsed
        try {
            ArtemisSentinelService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "Restart blocked (${e.message}) — START_STICKY will re-arm")
        }
    }

    /** Called by [ArtemisSentinelService] right after dynamic registration. */
    fun onRegistered() {
        lastRegistration = SystemClock.elapsedRealtime()
        Log.d(TAG, "Registered — startup grace armed for ${STARTUP_GRACE_MS / 1000}s")
    }

    /** True if something answers on the loopback server port. */
    private fun probeLoopback(): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 8443), 800)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "DozeRecovery"

        /** Skip probing for this long after dynamic registration (startup). */
        private const val STARTUP_GRACE_MS = 10_000L

        /** Minimum gap between restart attempts for a persistently-dead server. */
        private const val RESTART_COOLDOWN_MS = 30_000L

        /** When the service last registered us (elapsedRealtime, 0 = never). */
        @Volatile private var lastRegistration = 0L

        /** When we last attempted a restart (elapsedRealtime, 0 = never). */
        @Volatile private var lastRestartAttempt = 0L

        /** Doze-exit / wake-up signals worth re-arming on. */
        private val REARM_ACTIONS = setOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            android.net.ConnectivityManager.CONNECTIVITY_ACTION
        )
    }
}
