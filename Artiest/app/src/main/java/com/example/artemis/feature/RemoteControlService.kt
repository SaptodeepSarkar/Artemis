package com.example.artemis.feature

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Accessibility service used for remote control input injection.
 * Provides swipe, tap, type, and gesture capabilities for remote clients.
 */
class RemoteControlService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureQueue = ConcurrentLinkedQueue<GestureDescription>()
    private var isProcessingGestures = false

    /** Command queue for processing remote control actions */
    private val commandQueue = ConcurrentLinkedQueue<RemoteCommand>()

    data class RemoteCommand(
        val action: String,
        val params: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        serviceScope.launch {
            processCommandQueue()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Monitor for system dialogs, unlock state, etc.
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Track which app is in foreground
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Monitor notifications
            }
        }
    }

    override fun onInterrupt() {
        // Accessibility service was interrupted
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle commands from the main app
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_TAP -> {
                val x = intent.getFloatExtra("x", 0f)
                val y = intent.getFloatExtra("y", 0f)
                queueTap(x, y)
            }
            ACTION_SWIPE -> {
                val fromX = intent.getFloatExtra("fromX", 0f)
                val fromY = intent.getFloatExtra("fromY", 0f)
                val toX = intent.getFloatExtra("toX", 0f)
                val toY = intent.getFloatExtra("toY", 0f)
                queueSwipe(fromX, fromY, toX, toY)
            }
            ACTION_TYPE -> {
                val text = intent.getStringExtra("text") ?: ""
                queueType(text)
            }
            ACTION_BACK -> queueGlobalAction(GLOBAL_ACTION_BACK)
            ACTION_HOME -> queueGlobalAction(GLOBAL_ACTION_HOME)
            ACTION_RECENTS -> queueGlobalAction(GLOBAL_ACTION_RECENTS)
            ACTION_LOCK_SCREEN -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            ACTION_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ACTION_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            ACTION_POWER_DIALOG -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                }
            }
        }
    }

    // ---- Public API for external routing ----

    fun queueTap(x: Float, y: Float) {
        commandQueue.add(RemoteCommand("tap", mapOf("x" to x, "y" to y)))
    }

    fun queueSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        commandQueue.add(
            RemoteCommand(
                "swipe",
                mapOf("fromX" to fromX, "fromY" to fromY, "toX" to toX, "toY" to toY)
            )
        )
    }

    fun queueType(text: String) {
        commandQueue.add(RemoteCommand("type", mapOf("text" to text)))
    }

    fun queueGlobalAction(action: Int) {
        commandQueue.add(RemoteCommand("global_action", mapOf("action" to action)))
    }

    /** Execute a gesture command directly */
    fun executeCommand(command: RemoteCommand): Boolean {
        return when (command.action) {
            "tap" -> {
                val x = (command.params["x"] as? Number)?.toFloat() ?: return false
                val y = (command.params["y"] as? Number)?.toFloat() ?: return false
                performTap(x, y)
            }
            "swipe" -> {
                val fromX = (command.params["fromX"] as? Number)?.toFloat() ?: return false
                val fromY = (command.params["fromY"] as? Number)?.toFloat() ?: return false
                val toX = (command.params["toX"] as? Number)?.toFloat() ?: return false
                val toY = (command.params["toY"] as? Number)?.toFloat() ?: return false
                performSwipe(fromX, fromY, toX, toY)
            }
            "global_action" -> {
                val action = (command.params["action"] as? Number)?.toInt() ?: return false
                performGlobalAction(action)
            }
            "type" -> {
                val text = command.params["text"] as? String ?: return false
                performType(text)
            }
            else -> false
        }
    }

    // ---- Internal gesture execution ----

    private fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performType(text: String): Boolean {
        // Type text by finding a focused input field and injecting clipboard + paste
        // or by dispatching individual key events
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("artemis_type", text)
            clipboard.setPrimaryClip(clip)

            // Paste
            focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } else {
            // For older APIs, type character by character
            text.forEach { char ->
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, char.toString())
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
        return true
    }

    /** Get the current node hierarchy as JSON for remote viewing */
    fun getWindowHierarchy(): String? {
        val root = rootInActiveWindow ?: return null
        return buildNodeInfo(root, 0)
    }

    private fun buildNodeInfo(node: AccessibilityNodeInfo, depth: Int): String {
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        sb.append("$indent{\n")
        sb.append("$indent  \"className\": \"${node.className}\",\n")
        sb.append("$indent  \"text\": \"${node.text}\",\n")
        sb.append("$indent  \"contentDescription\": \"${node.contentDescription}\",\n")
        sb.append("$indent  \"bounds\": [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}],\n")
        sb.append("$indent  \"clickable\": ${node.isClickable},\n")
        sb.append("$indent  \"focusable\": ${node.isFocusable},\n")
        sb.append("$indent  \"enabled\": ${node.isEnabled},\n")
        sb.append("$indent  \"visible\": ${node.isVisibleToUser},\n")

        if (node.childCount > 0) {
            sb.append("$indent  \"children\": [\n")
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child != null) {
                    sb.append(buildNodeInfo(child, depth + 2))
                    if (i < node.childCount - 1) sb.append(",\n")
                }
            }
            sb.append("\n$indent  ]\n")
        }

        sb.append("$indent}")
        return sb.toString()
    }

    /** Process queued commands sequentially */
    private suspend fun processCommandQueue() {
        while (true) {
            val command = commandQueue.poll() ?: run {
                delay(100)
                continue
            }

            executeCommand(command)
            delay(50)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    companion object {
        const val ACTION_TAP = "com.example.artemis.action.TAP"
        const val ACTION_SWIPE = "com.example.artemis.action.SWIPE"
        const val ACTION_TYPE = "com.example.artemis.action.TYPE"
        const val ACTION_BACK = "com.example.artemis.action.BACK"
        const val ACTION_HOME = "com.example.artemis.action.HOME"
        const val ACTION_RECENTS = "com.example.artemis.action.RECENTS"
        const val ACTION_LOCK_SCREEN = "com.example.artemis.action.LOCK_SCREEN"
        const val ACTION_NOTIFICATIONS = "com.example.artemis.action.NOTIFICATIONS"
        const val ACTION_QUICK_SETTINGS = "com.example.artemis.action.QUICK_SETTINGS"
        const val ACTION_POWER_DIALOG = "com.example.artemis.action.POWER_DIALOG"

        private const val GESTURE_DURATION_MS = 200L
        private const val TAP_DURATION_MS = 50L
        private const val SWIPE_STEPS = 10

        @Volatile
        var instance: RemoteControlService? = null
            private set
    }
}
