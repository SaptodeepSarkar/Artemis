package com.example.artemis.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Administrator receiver for Artemis Sentinel.
 *
 * While the app is an ACTIVE device administrator, Android blocks normal
 * uninstallation ("You can't uninstall this app while device admin is
 * active" / "Not allowed to uninstall") — the user must first deactivate
 * device admin from Security settings or via the in-app Settings toggle.
 *
 * Activation is requested through the system screen
 * ([DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN]); the only policy declared
 * is force-lock, which lets the dashboard remotely lock the screen via
 * `POST /api/v1/admin/lock`.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        android.util.Log.i("ArtemisAdmin", "Device admin ENABLED — app is now uninstall-protected")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        android.util.Log.i("ArtemisAdmin", "Device admin DISABLED — app can be uninstalled normally")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Shown by the system when the user tries to deactivate admin.
        return "Deactivating device admin removes uninstall protection from Artemis Sentinel."
    }

    companion object {
        /** Whether this app is currently an active device administrator. */
        @JvmStatic
        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
            val component = android.content.ComponentName(context, AdminReceiver::class.java)
            return dpm.isAdminActive(component)
        }

        /**
         * Build the system "activate device admin" intent. Launching it shows
         * the device-admin activation screen; the user must accept once.
         *
         * CRITICAL: MUST be launched from an Activity context and MUST NOT
         * carry FLAG_ACTIVITY_NEW_TASK — DeviceAdminAdd refuses to start as a
         * new task (getCallingActivity() == null → "Cannot start
         * ADD_DEVICE_ADMIN as a new task" → instant finish / blank flash).
         */
        @JvmStatic
        fun activationIntent(context: Context): Intent {
            val component = android.content.ComponentName(context, AdminReceiver::class.java)
            return Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Artemis Sentinel uses device admin privileges to prevent uninstallation " +
                            "and to allow the dashboard to lock this device remotely."
                )
                // No FLAG_ACTIVITY_NEW_TASK here — see comment above.
            }
        }

        /** Deactivate device admin in-place (no system dialog needed). */
        @JvmStatic
        fun deactivate(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
            val component = android.content.ComponentName(context, AdminReceiver::class.java)
            dpm.removeActiveAdmin(component)
        }

        /** Lock the screen immediately. Returns true if the lock was issued. */
        @JvmStatic
        fun lockNow(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
            val component = android.content.ComponentName(context, AdminReceiver::class.java)
            if (!dpm.isAdminActive(component)) return false
            try {
                dpm.lockNow()
                return true
            } catch (e: Exception) {
                android.util.Log.w("ArtemisAdmin", "lockNow failed: ${e.message}")
                return false
            }
        }
    }
}
