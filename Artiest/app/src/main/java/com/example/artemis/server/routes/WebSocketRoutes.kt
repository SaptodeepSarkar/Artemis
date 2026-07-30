package com.example.artemis.server.routes

import com.example.artemis.auth.AuthManager
import com.example.artemis.feature.CameraController
import com.example.artemis.feature.MicController
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class WsAuthMessage(
    val type: String = "auth",
    val token: String
)

@Serializable
data class WsCommand(
    val type: String,
    val action: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class WsEvent(
    val type: String,
    val data: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

fun Route.webSocketRoutes(
    authManager: AuthManager,
    cameraController: CameraController,
    micController: MicController,
    serverScope: CoroutineScope,
    json: Json
) {
    val activeStreams = ConcurrentHashMap<String, MutableSet<WebSocketServerSession>>()
    val connectionCounter = AtomicInteger(0)

    // Camera stream
    webSocket("/ws/v1/stream/camera") {
        connectionCounter.incrementAndGet()
        try {
            // Authenticate via query parameter token
            val tokenStr = call.request.queryParameters["token"]
            if (tokenStr == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing auth token"))
                return@webSocket
            }

            val authResult = authManager.validateToken(tokenStr)
            if (authResult.isFailure) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                return@webSocket
            }

            val token = authResult.getOrThrow()
            if (!authManager.hasScope(token, AuthManager.SCOPE_CAMERA)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Insufficient permissions"))
                return@webSocket
            }

            // Register for camera stream
            val streamId = "camera_${token.clientId}"
            activeStreams.getOrPut("/ws/v1/stream/camera") { mutableSetOf() }.add(this)

            // Start sending camera frames
            val streamJob: Job = serverScope.launch {
                while (isActive) {
                    try {
                        val frame = cameraController.captureFrame()
                        if (frame != null) {
                            send(Frame.Binary(true, frame))
                        }
                        delay(200) // ~5 FPS
                    } catch (e: Exception) {
                        if (isActive) {
                            delay(1000)
                        }
                    }
                }
            }

            try {
                for (frame in incoming) {
                    when (frame.frameType) {
                        FrameType.PING -> {
                            send(Frame.Pong(frame.data))
                        }
                        FrameType.TEXT -> {
                            val text = (frame as Frame.Text).readText()
                            // Process commands from client
                        }
                        FrameType.CLOSE -> break
                        else -> {}
                    }
                }
            } finally {
                streamJob.cancel()
            }
        } finally {
            connectionCounter.decrementAndGet()
            activeStreams["/ws/v1/stream/camera"]?.remove(this)
        }
    }

    // Microphone stream
    webSocket("/ws/v1/stream/mic") {
        connectionCounter.incrementAndGet()
        try {
            val tokenStr = call.request.queryParameters["token"]
            if (tokenStr == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing auth token"))
                return@webSocket
            }

            val authResult = authManager.validateToken(tokenStr)
            if (authResult.isFailure) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                return@webSocket
            }

            val token = authResult.getOrThrow()
            if (!authManager.hasScope(token, AuthManager.SCOPE_MICROPHONE)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Insufficient permissions"))
                return@webSocket
            }

            activeStreams.getOrPut("/ws/v1/stream/mic") { mutableSetOf() }.add(this)

            val streamJob: Job = serverScope.launch {
                while (isActive) {
                    try {
                        val audioChunk = micController.readAudioChunk()
                        if (audioChunk != null) {
                            send(Frame.Binary(true, audioChunk))
                        }
                        delay(100)
                    } catch (e: Exception) {
                        if (isActive) {
                            delay(500)
                        }
                    }
                }
            }

            try {
                for (frame in incoming) {
                    when (frame.frameType) {
                        FrameType.PING -> send(Frame.Pong(frame.data))
                        FrameType.TEXT -> {
                            val text = (frame as Frame.Text).readText()
                            // Process commands
                        }
                        FrameType.CLOSE -> break
                        else -> {}
                    }
                }
            } finally {
                streamJob.cancel()
            }
        } finally {
            connectionCounter.decrementAndGet()
            activeStreams["/ws/v1/stream/mic"]?.remove(this)
        }
    }

    // Events stream
    webSocket("/ws/v1/events") {
        connectionCounter.incrementAndGet()
        try {
            val tokenStr = call.request.queryParameters["token"]
            if (tokenStr == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing auth token"))
                return@webSocket
            }

            val authResult = authManager.validateToken(tokenStr)
            if (authResult.isFailure) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                return@webSocket
            }

            // Heartbeat loop
            val heartbeatJob: Job = serverScope.launch {
                while (isActive) {
                    try {
                        val heartbeat = WsEvent(
                            type = "heartbeat",
                            data = mapOf("timestamp" to System.currentTimeMillis().toString())
                        )
                        send(Frame.Text(json.encodeToString(heartbeat)))
                        delay(15_000)
                    } catch (e: Exception) {
                        if (isActive) delay(5000)
                    }
                }
            }

            try {
                for (frame in incoming) {
                    when (frame.frameType) {
                        FrameType.PING -> send(Frame.Pong(frame.data))
                        FrameType.CLOSE -> break
                        else -> {}
                    }
                }
            } finally {
                heartbeatJob.cancel()
            }
        } finally {
            connectionCounter.decrementAndGet()
        }
    }

    // Remote control channel
    webSocket("/ws/v1/control") {
        connectionCounter.incrementAndGet()
        try {
            val tokenStr = call.request.queryParameters["token"]
            if (tokenStr == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing auth token"))
                return@webSocket
            }

            val authResult = authManager.validateToken(tokenStr)
            if (authResult.isFailure) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                return@webSocket
            }

            for (frame in incoming) {
                when (frame.frameType) {
                    FrameType.PING -> send(Frame.Pong(frame.data))
                    FrameType.TEXT -> {
                        val text = (frame as Frame.Text).readText()
                        // Relay commands to RemoteControlService
                        val response = WsEvent(
                            type = "command_ack",
                            data = mapOf("status" to "received", "command" to text.substringBefore("\n"))
                        )
                        send(Frame.Text(json.encodeToString(response)))
                    }
                    FrameType.CLOSE -> break
                    else -> {}
                }
            }
        } finally {
            connectionCounter.decrementAndGet()
        }
    }
}
