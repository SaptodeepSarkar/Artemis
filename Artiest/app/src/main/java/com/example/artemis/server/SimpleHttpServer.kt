package com.example.artemis.server

import android.util.Log
import com.example.artemis.ArtemisApp
import com.example.artemis.auth.AuthManager
import com.example.artemis.auth.PairingCode
import com.example.artemis.feature.CameraController
import com.example.artemis.feature.DeviceInfoProvider
import com.example.artemis.feature.LocationTracker
import com.example.artemis.feature.MicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking

// ============================================================
// Data classes
// ============================================================

data class HttpRequest(
    val method: String,
    val path: String,
    val pathParams: Map<String, String>,
    val queryParams: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
    val remoteAddress: String
)

data class HttpResponse(
    val statusCode: Int,
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
    val body: String = ""
)

data class Route(
    val method: String,
    val pathPattern: String,
    val handler: (HttpRequest) -> HttpResponse
)

// ============================================================
// Router
// ============================================================

class HttpRouter {
    private val routes = mutableListOf<Route>()

    fun get(path: String, handler: (HttpRequest) -> HttpResponse) {
        routes.add(Route("GET", path, handler))
    }

    fun post(path: String, handler: (HttpRequest) -> HttpResponse) {
        routes.add(Route("POST", path, handler))
    }

    fun put(path: String, handler: (HttpRequest) -> HttpResponse) {
        routes.add(Route("PUT", path, handler))
    }

    fun delete(path: String, handler: (HttpRequest) -> HttpResponse) {
        routes.add(Route("DELETE", path, handler))
    }

    fun dispatch(method: String, path: String, baseRequest: HttpRequest): HttpResponse? {
        // Strip trailing slash for matching (but keep root "/")
        val normalizedPath = if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path

        for (route in routes) {
            if (route.method != method) continue
            val pathParams = matchPath(route.pathPattern, normalizedPath)
            if (pathParams != null) {
                return route.handler(
                    baseRequest.copy(pathParams = pathParams, path = normalizedPath)
                )
            }
        }
        return null
    }

    private fun matchPath(pattern: String, actual: String): Map<String, String>? {
        val patternSegments = pattern.trim('/').split('/')
        val actualSegments = actual.trim('/').split('/')

        if (patternSegments.size != actualSegments.size) return null

        val params = mutableMapOf<String, String>()
        for ((p, a) in patternSegments.zip(actualSegments)) {
            if (p.startsWith("{") && p.endsWith("}")) {
                val paramName = p.removeSurrounding("{", "}")
                params[paramName] = a
            } else if (p != a) {
                return null
            }
        }
        return params
    }
}

// ============================================================
// SimpleHttpServer — replaces Ktor/Netty with raw ServerSocket
// ============================================================

class SimpleHttpServer(
    private val app: ArtemisApp,
    private val authManager: AuthManager,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val locationTracker: LocationTracker,
    private val cameraController: CameraController,
    private val micController: MicController,
    private val port: Int = 8443,
    private val useTls: Boolean = true
) {
    private var serverSocket: ServerSocket? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val router = HttpRouter()
    private val secureRandom = SecureRandom()
    private var pairingCode: PairingCode? = null
    private var connectedClients = 0

    @Volatile
    var activeConnections: Int = 0
        private set
    var startTime: Long = 0L
        private set

    val isRunning: Boolean get() = serverSocket != null

    init {
        Log.i("ArtemisServer", "SimpleHttpServer initializing on port $port, TLS=$useTls")
        registerRoutes()
    }

    private fun registerRoutes() {
        // Health
        router.get("/api/v1/health") { healthHandler(it) }
        router.get("/health") { healthHandler(it) }

        // NOTE: No public pairing-code endpoint. Code is generated internally
        // and shown on the phone screen via ArtemisApp.instance.currentPairingCode.
        // The user reads it from the screen and enters it on the dashboard.

        // Pair
        router.post("/api/v1/auth/pair") { pairHandler(it) }

        // Regenerate pairing code (called by phone UI, no auth — localhost only)
        router.post("/api/v1/auth/pair/regenerate") { regenerateCodeHandler(it) }

        // Token refresh
        router.post("/api/v1/auth/token") { tokenRefreshHandler(it) }

        // Auth status
        router.get("/api/v1/auth/status") { authStatusHandler(it) }

        // Clients
        router.get("/api/v1/auth/clients") { clientsListHandler(it) }
        router.delete("/api/v1/auth/clients/{id}") { clientRevokeHandler(it) }

        // Device info
        router.get("/api/v1/device/info") { requireAuth(it) { req -> deviceInfoHandler(req) } }
        router.get("/api/v1/device/info/battery") { requireAuth(it) { req -> batteryInfoHandler(req) } }
        router.get("/api/v1/device/info/network") { requireAuth(it) { req -> networkInfoHandler(req) } }
        router.get("/api/v1/device/info/storage") { requireAuth(it) { req -> storageInfoHandler(req) } }

        // Location
        router.get("/api/v1/location/current") { requireAuth(it) { req -> locationCurrentHandler(req) } }
        router.get("/api/v1/location/history") { requireAuth(it) { req -> locationHistoryHandler(req) } }

        // Camera
        router.get("/api/v1/camera/list") { requireAuth(it) { req -> cameraListHandler(req) } }
        router.post("/api/v1/camera/capture") { requireAuth(it) { req -> cameraCaptureHandler(req) } }
        router.get("/api/v1/camera/captures") { requireAuth(it) { req -> cameraCapturesHandler(req) } }
        router.get("/api/v1/camera/captures/{id}") { requireAuth(it) { req -> cameraCaptureGetHandler(req) } }

        // Microphone
        router.post("/api/v1/mic/record/start") { requireAuth(it) { req -> micRecordStartHandler(req) } }
        router.post("/api/v1/mic/record/stop") { requireAuth(it) { req -> micRecordStopHandler(req) } }
        router.get("/api/v1/mic/recordings") { requireAuth(it) { req -> micRecordingsHandler(req) } }
        router.get("/api/v1/mic/recordings/{id}") { requireAuth(it) { req -> micRecordingGetHandler(req) } }
    }

    // ============================================================
    // Auth helpers
    // ============================================================

    private fun extractToken(req: HttpRequest): String? {
        val authHeader = req.headers["authorization"] ?: req.headers["Authorization"]
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.removePrefix("Bearer ")
        }
        return req.queryParams["token"]
    }

    private fun requireAuth(req: HttpRequest, handler: (HttpRequest) -> HttpResponse): HttpResponse {
        val tokenStr = extractToken(req) ?: return jsonResponse(401, mapOf("error" to "unauthorized", "message" to "Missing authentication token"))
        val result = runBlocking { authManager.validateToken(tokenStr) }
        if (result.isFailure) {
            return jsonResponse(401, mapOf("error" to "unauthorized", "message" to "Invalid authentication token"))
        }
        return handler(req)
    }

    // ============================================================
    // Route handlers
    // ============================================================

    private fun healthHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, mapOf(
            "status" to "ok",
            "version" to "1.0.0",
            "deviceName" to android.os.Build.MODEL,
            "uptimeSeconds" to ((System.currentTimeMillis() - startTime) / 1000),
            "activeConnections" to activeConnections,
            "timestamp" to System.currentTimeMillis()
        ))
    }

    private fun regenerateCodeHandler(req: HttpRequest): HttpResponse {
        val newCode = regeneratePairingCode()
        Log.i("ArtemisServer", "Pairing code regenerated via API: $newCode")
        return jsonResponse(200, mapOf(
            "code" to newCode,
            "expiresAt" to (app.currentPairingCode?.expiresAt ?: System.currentTimeMillis() + 300_000)
        ))
    }

    private fun pairHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body) ?: return jsonResponse(400, mapOf("error" to "invalid_request", "message" to "Invalid JSON body"))
        val code = body["code"] ?: return jsonResponse(400, mapOf("error" to "invalid_request", "message" to "Missing pairing code"))

        // Check the pairing code
        val currentCode = pairingCode
        if (currentCode == null || currentCode.code != code) {
            return jsonResponse(401, mapOf("error" to "pairing_failed", "message" to "Invalid pairing code"))
        }
        if (System.currentTimeMillis() > currentCode.expiresAt) {
            return jsonResponse(401, mapOf("error" to "pairing_failed", "message" to "Pairing code expired"))
        }

        val deviceId = try { android.os.Build.getSerial() } catch (_: Exception) { android.os.Build.FINGERPRINT }
        val clientId = "client_${System.currentTimeMillis()}"

        val token = runBlocking {
            authManager.issueToken(
                deviceId = deviceId,
                clientId = clientId,
                scope = AuthManager.SCOPE_ADMIN
            )
        }
        val refreshToken = runBlocking { authManager.createRefreshToken(clientId) }

        connectedClients++

        return jsonResponse(200, mapOf(
            "token" to token,
            "refreshToken" to refreshToken,
            "deviceId" to deviceId,
            "expiresAt" to (System.currentTimeMillis() + AuthManager.TOKEN_EXPIRY_MS)
        ))
    }

    private fun tokenRefreshHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body) ?: return jsonResponse(400, mapOf("error" to "invalid_request", "message" to "Invalid JSON body"))
        val refreshToken = body["refreshToken"] ?: return jsonResponse(400, mapOf("error" to "invalid_request", "message" to "Missing refreshToken"))

        val result = runBlocking { authManager.validateRefreshToken(refreshToken) }
        if (result.isFailure) {
            return jsonResponse(401, mapOf("error" to "invalid_token", "message" to "Refresh token is invalid or expired"))
        }

        val clientId = result.getOrThrow()
        val deviceId = try { android.os.Build.getSerial() } catch (_: Exception) { android.os.Build.FINGERPRINT }

        val newToken = runBlocking {
            authManager.issueToken(
                deviceId = deviceId,
                clientId = clientId,
                scope = AuthManager.SCOPE_ADMIN
            )
        }
        val newRefreshToken = runBlocking { authManager.createRefreshToken(clientId) }

        return jsonResponse(200, mapOf(
            "token" to newToken,
            "refreshToken" to newRefreshToken,
            "expiresAt" to (System.currentTimeMillis() + AuthManager.TOKEN_EXPIRY_MS)
        ))
    }

    private fun authStatusHandler(req: HttpRequest): HttpResponse {
        val tokenStr = extractToken(req)
        if (tokenStr != null) {
            val result = runBlocking { authManager.validateToken(tokenStr) }
            if (result.isSuccess) {
                val token = result.getOrThrow()
                return jsonResponse(200, mapOf(
                    "authenticated" to true,
                    "clientId" to token.clientId,
                    "deviceId" to token.deviceId,
                    "scope" to token.scope,
                    "expiresAt" to token.expiresAt
                ))
            }
        }
        return jsonResponse(200, mapOf("authenticated" to false))
    }

    private fun clientsListHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, mapOf("clients" to emptyList<Any>()))
    }

    private fun clientRevokeHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, mapOf("status" to "revoked"))
    }

    private fun deviceInfoHandler(req: HttpRequest): HttpResponse {
        val info = runBlocking { deviceInfoProvider.getFullDeviceInfo() }
        val body = json.encodeToString(info)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun batteryInfoHandler(req: HttpRequest): HttpResponse {
        val info = deviceInfoProvider.getBatteryInfo()
        val body = json.encodeToString(info)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun networkInfoHandler(req: HttpRequest): HttpResponse {
        val info = deviceInfoProvider.getNetworkInfo()
        val body = json.encodeToString(info)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun storageInfoHandler(req: HttpRequest): HttpResponse {
        val info = deviceInfoProvider.getStorageInfo()
        val body = json.encodeToString(info)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun locationCurrentHandler(req: HttpRequest): HttpResponse {
        val location = runBlocking { locationTracker.getCurrentLocation() }
        if (location != null) {
            val body = json.encodeToString(location)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
        }
        return jsonResponse(200, mapOf("error" to "no_location", "message" to "No location data available yet"))
    }

    private fun locationHistoryHandler(req: HttpRequest): HttpResponse {
        val from = req.queryParams["from"]?.toLongOrNull()
        val to = req.queryParams["to"]?.toLongOrNull()
        val limit = req.queryParams["limit"]?.toIntOrNull() ?: 1000
        val offset = req.queryParams["offset"]?.toIntOrNull() ?: 0

        val history = runBlocking { locationTracker.getLocationHistory(from, to, limit, offset) }
        val pointsJson = json.encodeToString(history)
        val body = """{"points":$pointsJson,"count":${history.size},"limit":$limit,"offset":$offset}"""
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun cameraListHandler(req: HttpRequest): HttpResponse {
        val cameras = cameraController.getCameraList()
        val body = json.encodeToString(cameras)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun cameraCaptureHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val cameraId = body?.get("cameraId") ?: "0"

        val result = runBlocking { cameraController.capturePhoto(cameraId) }
        if (result.isSuccess) {
            val capture = result.getOrThrow()
            val captureJson = json.encodeToString(capture)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), captureJson)
        } else {
            return jsonResponse(500, mapOf("error" to "capture_failed", "message" to (result.exceptionOrNull()?.message ?: "Unknown error")))
        }
    }

    private fun cameraCapturesHandler(req: HttpRequest): HttpResponse {
        val captures = cameraController.getCaptures()
        val body = json.encodeToString(captures)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun cameraCaptureGetHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"] ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing capture ID"))
        val capture = cameraController.getCapture(id)
        if (capture != null) {
            val capJson = json.encodeToString(capture!!)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), capJson)
        } else {
            return jsonResponse(404, mapOf("error" to "not_found", "message" to "Capture not found"))
        }
    }

    private fun micRecordStartHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val durationMs = body?.get("durationMs")?.toLongOrNull() ?: 30_000L

        val result = runBlocking { micController.startRecording(durationMs) }
        if (result.isSuccess) {
            val recording = result.getOrThrow()
            val recJson = json.encodeToString(recording)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), recJson)
        } else {
            return jsonResponse(500, mapOf("error" to "record_failed", "message" to (result.exceptionOrNull()?.message ?: "Unknown error")))
        }
    }

    private fun micRecordStopHandler(req: HttpRequest): HttpResponse {
        val result = runBlocking { micController.stopRecording() }
        if (result.isSuccess) {
            val recording = result.getOrThrow()
            val recJson = json.encodeToString(recording)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), recJson)
        } else {
            return jsonResponse(409, mapOf("error" to "not_recording", "message" to "No active recording to stop"))
        }
    }

    private fun micRecordingsHandler(req: HttpRequest): HttpResponse {
        val recordings = micController.getRecordings()
        val body = json.encodeToString(recordings)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun micRecordingGetHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"] ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing recording ID"))
        val recording = micController.getRecording(id)
        if (recording != null) {
            val recJson = json.encodeToString(recording!!)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), recJson)
        } else {
            return jsonResponse(404, mapOf("error" to "not_found", "message" to "Recording not found"))
        }
    }

    // ============================================================
    // Response builders
    // ============================================================

    private fun jsonResponse(statusCode: Int, data: Map<String, Any?>): HttpResponse {
        val body = buildJsonObject(data)
        return HttpResponse(
            statusCode = statusCode,
            contentType = "application/json",
            headers = mapOf("Access-Control-Allow-Origin" to "*"),
            body = body
        )
    }

    private fun jsonResponseArray(statusCode: Int, data: List<Any?>): HttpResponse {
        val body = buildJsonArray(data)
        return HttpResponse(
            statusCode = statusCode,
            contentType = "application/json",
            headers = mapOf("Access-Control-Allow-Origin" to "*"),
            body = body
        )
    }

    private fun buildJsonObject(data: Map<String, Any?>): String {
        val entries = data.map { (key, value) ->
            "\"$key\":${toJsonValue(value)}"
        }
        return "{${entries.joinToString(",")}}"
    }

    private fun buildJsonArray(data: List<Any?>): String {
        val items = data.map { toJsonValue(it) }
        return "[${items.joinToString(",")}]"
    }

    @Suppress("UNCHECKED_CAST")
    private fun toJsonValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> buildJsonObject(value as Map<String, Any?>)
            is List<*> -> buildJsonArray(value as List<Any?>)
            else -> "\"$value\""
        }
    }

    // ============================================================
    // Server lifecycle
    // ============================================================

    /**
     * Called by the UI (via service) to get a fresh pairing code.
     * Returns the new 6-digit code string.
     */
    fun regeneratePairingCode(): String {
        val code = authManager.generatePairingCode()
        pairingCode = code
        app.currentPairingCode = code
        Log.i("ArtemisServer", "Pairing code regenerated: ${code.code}")
        return code.code
    }

    suspend fun start() {
        if (serverSocket != null) {
            Log.i("ArtemisServer", "start() called but server already running")
            return
        }

        Log.i("ArtemisServer", "Starting raw ServerSocket server on port $port...")
        startTime = System.currentTimeMillis()

        try {
            serverSocket = ServerSocket(port)
            Log.i("ArtemisServer", "ServerSocket bound to 0.0.0.0:$port")

            // Generate initial pairing code and publish to shared state
            pairingCode = authManager.generatePairingCode()
            app.currentPairingCode = pairingCode
            Log.i("ArtemisServer", "Initial pairing code: ${pairingCode?.code}")

            serverScope.launch {
                acceptLoop()
            }

            Log.i("ArtemisServer", "Server started successfully on port $port")
        } catch (e: Exception) {
            Log.e("ArtemisServer", "FAILED to start server: ${e.message}", e)
            serverSocket = null
            throw e
        }
    }

    private suspend fun acceptLoop() {
        val socket = serverSocket ?: return
        try {
            while (serverScope.isActive) {
                try {
                    val client = socket.accept()
                    activeConnections++
                    Log.i("ArtemisServer", "Connection accepted from ${client.inetAddress.hostAddress}")
                    serverScope.launch {
                        try {
                            handleConnection(client)
                        } catch (e: Exception) {
                            Log.e("ArtemisServer", "Error handling connection: ${e.message}")
                        } finally {
                            activeConnections--
                        }
                    }
                } catch (e: Exception) {
                    if (serverScope.isActive) {
                        Log.e("ArtemisServer", "Error accepting connection: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (serverScope.isActive) {
                Log.e("ArtemisServer", "Accept loop error: ${e.message}")
            }
        }
    }

    private fun handleConnection(client: Socket) {
        try {
            client.soTimeout = 15000 // 15s timeout for reading
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val output = client.getOutputStream()

            // Parse the HTTP request
            val request = parseHttpRequest(input, client)
            if (request == null) {
                sendHttpResponse(output, HttpResponse(400, body = "Bad Request"))
                return
            }

            Log.i("ArtemisServer", "-> ${request.method} ${request.path}")

            // Route the request
            val response = router.dispatch(request.method, request.path, request)
                ?: HttpResponse(404, contentType = "application/json",
                    body = """{"error":"not_found","message":"The requested resource was not found"}""")

            if (response.statusCode == 404) {
                Log.i("ArtemisServer", "<- 404 ${request.method} ${request.path}")
            }

            // Send the response
            sendHttpResponse(output, response)
        } catch (e: Exception) {
            Log.e("ArtemisServer", "Connection handler error: ${e.message}")
            try {
                sendHttpResponse(client.getOutputStream(),
                    HttpResponse(500, contentType = "application/json",
                        body = """{"error":"internal_error","message":"${e.message?.replace("\"", "\\\"") ?: "Unknown error"}"""))
            } catch (_: Exception) {}
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private fun parseHttpRequest(reader: BufferedReader, client: Socket): HttpRequest? {
        try {
            // Read the request line
            val requestLine = reader.readLine() ?: return null
            val parts = requestLine.split(" ")
            if (parts.size < 2) return null

            val method = parts[0]
            val rawPath = parts[1]

            // Split path and query string
            val pathAndQuery = rawPath.split("?", limit = 2)
            val path = URLDecoder.decode(pathAndQuery[0], "UTF-8")
            val queryString = pathAndQuery.getOrElse(1) { "" }

            // Parse query parameters
            val queryParams = mutableMapOf<String, String>()
            if (queryString.isNotBlank()) {
                queryString.split("&").forEach { pair ->
                    val eq = pair.split("=", limit = 2)
                    val key = URLDecoder.decode(eq[0], "UTF-8")
                    val value = if (eq.size > 1) URLDecoder.decode(eq[1], "UTF-8") else ""
                    queryParams[key] = value
                }
            }

            // Read headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty() || headerLine.isBlank()) break
                val colonIdx = headerLine.indexOf(":")
                if (colonIdx > 0) {
                    val key = headerLine.substring(0, colonIdx).trim().lowercase()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // Read body if present
            val body = if (contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                String(bodyChars, 0, totalRead)
            } else {
                ""
            }

            return HttpRequest(
                method = method,
                path = path,
                pathParams = emptyMap(),
                queryParams = queryParams,
                headers = headers,
                body = body,
                remoteAddress = client.inetAddress?.hostAddress ?: "unknown"
            )
        } catch (e: Exception) {
            Log.e("ArtemisServer", "Error parsing HTTP request: ${e.message}")
            return null
        }
    }

    private fun sendHttpResponse(output: OutputStream, response: HttpResponse) {
        try {
            val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
            val statusText = when (response.statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                409 -> "Conflict"
                500 -> "Internal Server Error"
                else -> "Unknown"
            }

            val headerLines = StringBuilder()
            headerLines.append("HTTP/1.1 ${response.statusCode} $statusText\r\n")
            headerLines.append("Content-Type: ${response.contentType}; charset=utf-8\r\n")
            headerLines.append("Content-Length: ${bodyBytes.size}\r\n")
            headerLines.append("Connection: close\r\n")
            headerLines.append("Access-Control-Allow-Origin: *\r\n")
            headerLines.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
            headerLines.append("Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With\r\n")
            for ((key, value) in response.headers) {
                headerLines.append("$key: $value\r\n")
            }
            headerLines.append("Server: Artemis/1.0.0\r\n")
            headerLines.append("\r\n")

            output.write(headerLines.toString().toByteArray(Charsets.UTF_8))
            output.write(bodyBytes)
            output.flush()

            Log.i("ArtemisServer", "<- ${response.statusCode} (${bodyBytes.size} bytes)")
        } catch (e: Exception) {
            Log.e("ArtemisServer", "Error sending response: ${e.message}")
        }
    }

    // ============================================================
    // Utility
    // ============================================================

    /**
     * Simple manual JSON object parser — avoids kotlinx.serialization issues with Map<String, Any?>
     */
    private fun parseJsonObject(text: String): Map<String, String>? {
        if (text.isBlank()) return null
        try {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return emptyMap()

            val result = mutableMapOf<String, String>()
            var i = 0
            while (i < inner.length) {
                // Skip whitespace
                while (i < inner.length && inner[i].isWhitespace()) i++
                if (i >= inner.length) break

                // Key
                if (inner[i] != '"') return null
                i++
                val keyStart = i
                while (i < inner.length && inner[i] != '"') {
                    if (inner[i] == '\\') i++ // skip escaped char
                    i++
                }
                if (i >= inner.length) return null
                val key = inner.substring(keyStart, i)
                i++ // skip closing quote

                // Colon
                while (i < inner.length && inner[i].isWhitespace()) i++
                if (i >= inner.length || inner[i] != ':') return null
                i++
                while (i < inner.length && inner[i].isWhitespace()) i++

                // Value — only parse string values (quoted) or simple values
                if (i < inner.length && inner[i] == '"') {
                    i++ // skip opening quote
                    val valStart = i
                    while (i < inner.length && inner[i] != '"') {
                        if (inner[i] == '\\') i++
                        i++
                    }
                    val value = inner.substring(valStart, i)
                    i++ // skip closing quote
                    result[key] = value
                } else {
                    // Number, boolean, or null — read until comma or end
                    val valStart = i
                    while (i < inner.length && inner[i] != ',' && inner[i] != '}') i++
                    val raw = inner.substring(valStart, i).trim()
                    result[key] = raw
                }

                // Skip comma
                while (i < inner.length && inner[i].isWhitespace()) i++
                if (i < inner.length && inner[i] == ',') i++
            }

            return result
        } catch (_: Exception) {
            return null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryParseJson(text: String): Map<String, Any?>? {
        if (text.isBlank()) return null
        try {
            return json.decodeFromString<Map<String, Any?>>(text)
        } catch (_: Exception) {
            return null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryParseJsonArray(text: String): List<Any?>? {
        if (text.isBlank()) return null
        try {
            return json.decodeFromString<List<Any?>>(text)
        } catch (_: Exception) {
            return null
        }
    }

    suspend fun stop() {
        Log.i("ArtemisServer", "Stopping server...")
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverScope.cancel()
        Log.i("ArtemisServer", "Server stopped")
    }
}
