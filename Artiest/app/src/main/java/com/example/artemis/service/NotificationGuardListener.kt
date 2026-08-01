package com.example.artemis.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification listener that automatically dismisses the PackageInstaller's
 * "uninstall failed" notification.
 *
 * When a device-admin app blocks uninstallation, the package installer posts
 * a notification like "Uninstalling <app> unsuccessful." on its
 * "uninstall failure" channel. This listener sees it the moment it's posted
 * and cancels it, so the device user never sees the failure alert.
 *
 * Requires Notification Access (Settings → Special access → Notification
 * access); the system binds the service automatically once granted.
 */
class NotificationGuardListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("ArtemisGuard", "Notification access granted — uninstall-failure guard armed")
        // Sweep: dismiss any uninstall-failure notifications that were
        // posted before access was granted (they linger in the shade).
        try {
            for (sbn in activeNotifications) {
                if (isUninstallFailureNotification(sbn)) {
                    Log.i("ArtemisGuard", "Sweeping stale notification (key=${sbn.key})")
                    cancelNotification(sbn.key)
                }
            }
        } catch (e: Exception) {
            Log.w("ArtemisGuard", "Sweep failed: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Universal matching: any package, any OEM — the "uninstall failure"
        // channel name and failure wording are identical across AOSP and
        // vendor package installers. No package whitelist.
        val n = sbn.notification ?: return
        Log.d(
            "ArtemisGuard",
            "Posted: pkg=${sbn.packageName} channel=${n.channelId} " +
                    "title=${n.extras?.getCharSequence(Notification.EXTRA_TITLE)}"
        )
        if (isUninstallFailureNotification(sbn)) {
            Log.i(
                "ArtemisGuard",
                "Dismissing uninstall-failure notification from ${sbn.packageName} (key=${sbn.key})"
            )
            try {
                cancelNotification(sbn.key)
            } catch (e: Exception) {
                Log.w("ArtemisGuard", "cancelNotification failed: ${e.message}")
            }
        }
    }

    companion object {
        /**
         * True when [sbn] is the PackageInstaller uninstall-failure alert.
         * Matches on the "uninstall failure" channel first (precise,
         * universal), then falls back to title/text keywords. Works on any
         * device — no OEM-specific package names.
         */
        @JvmStatic
        fun isUninstallFailureNotification(sbn: StatusBarNotification): Boolean {
            val n = sbn.notification ?: return false

            // Channel check: package installers post this on a channel
            // containing "uninstall" and "fail" (AOSP: "uninstall failure").
            val channel = n.channelId?.lowercase() ?: ""
            if (channel.contains("uninstall") && channel.contains("fail")) return true

            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val blob = "$title $text".lowercase()
            if (!blob.contains("uninstall")) return false
            return FAILURE_KEYWORDS.any { blob.contains(it) }
        }

        private val FAILURE_KEYWORDS = listOf(
            "unsuccessful", "unable", "failed", "couldn't", "could not",
            "cannot", "can't", "not allowed", "blocked", "not uninstalled"
        )

        /** Whether notification access is granted for this app's component. */
        @JvmStatic
        fun isAccessGranted(context: android.content.Context): Boolean {
            val cn = android.content.ComponentName(context, NotificationGuardListener::class.java)
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.split(":").any { it.trim() == cn.flattenToString() }
        }
    }
}
