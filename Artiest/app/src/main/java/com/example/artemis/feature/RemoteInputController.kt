package com.example.artemis.feature

import android.content.Context
import android.util.Log

/**
 * Remote-admin input helper (v2.3.3).
 *
 * Thin, plain-Kotlin facade over [RemoteControlService] for the HTTP/WS
 * layer: tap / swipe / long-press / global system actions. Holds NO HTTP
 * knowledge (house style — helpers are plain classes taking Context) and
 * no Activity reference — FGS-only.
 *
 * Every method returns true when the command was ACCEPTED (queued). The
 * accessibility service must be enabled AND currently connected
 * ([RemoteControlService.instance] != null); otherwise the caller gets
 * false and the server answers "accessibility_disabled".
 */
class RemoteInputController(private val context: Context) {

    private val rcs get() = RemoteControlService.instance

    /** True when the accessibility service is connected (gestures work). */
    val available: Boolean get() = rcs != null

    fun tap(x: Float, y: Float): Boolean {
        val svc = rcs ?: return false
        svc.queueTap(x, y)
        return true
    }

    fun longPress(x: Float, y: Float): Boolean {
        val svc = rcs ?: return false
        svc.queueLongPress(x, y)
        return true
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long? = null): Boolean {
        val svc = rcs ?: return false
        if (durationMs != null) svc.queueSwipe(x1, y1, x2, y2, durationMs)
        else svc.queueSwipe(x1, y1, x2, y2)
        return true
    }

    /**
     * System global action by name. Names: home, back, recents, lock,
     * notifications, quick_settings, power. Unknown names return false.
     */
    fun global(action: String): Boolean {
        val svc = rcs ?: return false
        val id = when (action.lowercase()) {
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "lock" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                } else {
                    Log.w("ArtemisInput", "power dialog requires API 33+ — ignoring")
                    return false
                }
            }
            else -> return false
        }
        svc.queueGlobalAction(id)
        return true
    }
}
