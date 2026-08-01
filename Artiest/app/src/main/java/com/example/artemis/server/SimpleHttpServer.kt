package com.example.artemis.server

import android.util.Log
import com.example.artemis.ArtemisApp
import com.example.artemis.auth.AuthManager
import com.example.artemis.auth.PairedClient
import com.example.artemis.auth.PairingCode
import com.example.artemis.auth.RefreshSessionResult
import com.example.artemis.feature.CameraController
import com.example.artemis.feature.DeviceInfoProvider
import com.example.artemis.feature.LocationTracker
import com.example.artemis.feature.MicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
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
    val body: String = "",
    val binaryBody: ByteArray? = null
)

data class Route(
    val method: String,
    val pathPattern: String,
    val handler: (HttpRequest) -> HttpResponse
)

/**
 * Per-IP pairing attempt state. Lockout grows exponentially with each
 * failure past the threshold and resets on code rotation.
 */
data class PairingAttempt(
    val fails: Int,
    val firstFailAt: Long,
    val lockedUntil: Long
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
    private val callLogsProvider: com.example.artemis.feature.CallLogsProvider,
    private val smsProvider: com.example.artemis.feature.SmsProvider,
    private val callRecorder: com.example.artemis.feature.CallRecorder,
    private val videoRecorder: com.example.artemis.feature.VideoRecorder,
    private val port: Int = 8443,
    private val useTls: Boolean = true
) {
    private var serverSocket: ServerSocket? = null
    private var sslSocketFactory: SSLSocketFactory? = null
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

    // Rate limiting — per-IP fixed window. Loopback (phone UI) is exempt.
    private val requestLimiter = RateLimiter(maxRequests = 120, windowMs = 60_000L)
    // Pairing brute-force defense: per-IP attempt tracking with exponential
    // lockout. Counters reset on code rotation (max 5 minutes) and after a
    // successful pairing.
    private val pairingAttempts = mutableMapOf<String, PairingAttempt>()
    private val maxPairingFailures = 5
    // Lockout ladder: 5th failure locks 1 min, then 2, 4 ... capped at the
    // 5-minute rotation window (rotation clears everything anyway).
    private val pairingBackoffBaseMs = 60_000L

    @Volatile
    var activeConnections: Int = 0
        private set
    var startTime: Long = 0L
        private set

    val isRunning: Boolean get() = serverSocket != null

    /** True when the socket has been closed (accept-loop will exit). */
    val serverSocketClosed: Boolean get() = serverSocket?.isClosed ?: true

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

        // Clients — management is available to authenticated dashboards AND to
        // the phone UI itself (loopback, no token): the phone must be able to
        // view/revoke its own pairings without holding a dashboard token.
        router.get("/api/v1/auth/clients") { managementHandler(it) { req -> clientsListHandler(req) } }
        router.delete("/api/v1/auth/clients/{id}") { managementHandler(it) { req -> clientRevokeHandler(req) } }
        router.post("/api/v1/auth/clients/{id}/unrevoke") { managementHandler(it) { req -> clientUnrevokeHandler(req) } }
        router.delete("/api/v1/auth/clients") { managementHandler(it) { req -> revokeAllHandler(req) } }

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
        router.get("/api/v1/camera/captures/{id}/file") { requireAuth(it) { req -> cameraCaptureFileHandler(req) } }

        // Microphone
        router.post("/api/v1/mic/record/start") { requireAuth(it) { req -> micRecordStartHandler(req) } }
        router.post("/api/v1/mic/record/stop") { requireAuth(it) { req -> micRecordStopHandler(req) } }
        router.get("/api/v1/mic/recordings") { requireAuth(it) { req -> micRecordingsHandler(req) } }
        router.get("/api/v1/mic/recordings/{id}") { requireAuth(it) { req -> micRecordingGetHandler(req) } }
        router.get("/api/v1/mic/recordings/{id}/file") { requireAuth(it) { req -> micRecordingFileHandler(req) } }

        // Call logs (READ_CALL_LOG)
        router.get("/api/v1/logs/calls") { requireAuth(it) { req -> callLogsHandler(req) } }

        // SMS (READ_SMS) — bodies redacted unless ?includeBody=1
        router.get("/api/v1/sms") { requireAuth(it) { req -> smsHandler(req) } }

        // Call recorder (TelephonyManager-triggered, runs in the FGS)
        router.get("/api/v1/callrecorder/status") { requireAuth(it) { req -> callRecorderStatusHandler(req) } }
        router.post("/api/v1/callrecorder/toggle") { requireAuth(it) { req -> callRecorderToggleHandler(req) } }
        router.get("/api/v1/callrecordings") { requireAuth(it) { req -> callRecordingsHandler(req) } }
        router.get("/api/v1/callrecordings/{id}") { requireAuth(it) { req -> callRecordingGetHandler(req) } }
        router.get("/api/v1/callrecordings/{id}/file") { requireAuth(it) { req -> callRecordingFileHandler(req) } }

        // Video recording (CameraX VideoCapture)
        router.post("/api/v1/video/record") { requireAuth(it) { req -> videoRecordHandler(req) } }
        router.get("/api/v1/video/list") { requireAuth(it) { req -> videoListHandler(req) } }
        router.get("/api/v1/video/{id}") { requireAuth(it) { req -> videoGetHandler(req) } }
        router.get("/api/v1/video/{id}/file") { requireAuth(it) { req -> videoFileHandler(req) } }

        // Device admin — remote lock (requires active device admin)
        router.post("/api/v1/admin/lock") { requireAuth(it) { req -> adminLockHandler(req) } }
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

    /**
     * Client-management endpoints: authenticated dashboards pass a token; the
     * phone itself (loopback) is trusted without one — the same trust model
     * as pairing-code regeneration. Code running on the device already has
     * full access to the device.
     */
    private fun managementHandler(req: HttpRequest, handler: (HttpRequest) -> HttpResponse): HttpResponse {
        val remote = req.remoteAddress
        val isLoopback = remote == "127.0.0.1" || remote == "::1" || remote.startsWith("127.")
        if (isLoopback) return handler(req)
        return requireAuth(req, handler)
    }

    // ============================================================
    // Route handlers
    // ============================================================

    private fun healthHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, mapOf(
            "status" to "ok",
            "version" to "2.2.0",
            "deviceName" to android.os.Build.MODEL,
            "uptimeSeconds" to ((System.currentTimeMillis() - startTime) / 1000),
            "activeConnections" to activeConnections,
            "tls" to (sslSocketFactory != null),
            "certFp" to (app.currentCertFingerprint ?: ""),
            "timestamp" to System.currentTimeMillis()
        ))
    }

    /**
     * Remote lock via device-admin privileges. Returns 200 with a status
     * field; requires the app to be an ACTIVE device administrator
     * (otherwise the phone reports "not_active" — the dashboard can show
     * the user how to activate it).
     */
    private fun adminLockHandler(req: HttpRequest): HttpResponse {
        return try {
            val dpm = app.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
            val component = android.content.ComponentName(app, com.example.artemis.receiver.AdminReceiver::class.java)
            if (!dpm.isAdminActive(component)) {
                return jsonResponse(200, mapOf(
                    "status" to "not_active",
                    "message" to "Device admin is not active — activate it from the app Settings to enable remote lock"
                ))
            }
            dpm.lockNow()
            jsonResponse(200, mapOf("status" to "locked"))
        } catch (e: Exception) {
            Log.w("ArtemisServer", "Remote lock failed: ${e.message}")
            jsonResponse(500, mapOf("error" to "lock_failed", "message" to (e.message ?: "unknown")))
        }
    }

    private fun regenerateCodeHandler(req: HttpRequest): HttpResponse {
        // Security: only the phone itself may rotate the code. Any other
        // host could otherwise force a rotation loop / pairing DoS.
        val remote = req.remoteAddress
        if (remote != "127.0.0.1" && remote != "localhost" && remote != "::1" && !remote.startsWith("127.")) {
            Log.w("ArtemisServer", "Rejected pairing-code regeneration from non-loopback address $remote")
            return jsonResponse(403, mapOf("error" to "forbidden", "message" to "Regeneration is only allowed from the device itself"))
        }

        // Rotate without ever exposing the code value in logs or the response.
        // The phone UI reads the fresh code from in-process shared state.
        val fresh = authManager.generatePairingCode()
        pairingCode = fresh
        app.currentPairingCode = fresh
        // A fresh code resets all pairing lockouts.
        synchronized(pairingAttempts) { pairingAttempts.clear() }
        return jsonResponse(200, mapOf("status" to "rotated"))
    }

    private fun pairHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body) ?: return jsonResponse(400, mapOf("error" to "invalid_request", "message" to "Invalid JSON body"))
        val code = body["code"] ?: return jsonResponse(400, mapOf("error" to "invalid_request", "message" to "Missing pairing code"))

        // Brute-force guard: per-IP attempt tracking with exponential lockout.
        // Threshold: 5 failed attempts; each subsequent failure extends the
        // lockout (1 min, 2, 4 ... capped at the 5-minute rotation window).
        // Code rotation clears all counters, so lockouts never persist past
        // the current code's lifetime.
        val remote = req.remoteAddress
        val isLoopback = remote == "127.0.0.1" || remote == "::1" || remote.startsWith("127.")
        if (!isLoopback) {
            val attempt = synchronized(pairingAttempts) { pairingAttempts[remote] }
            if (attempt != null) {
                val now = System.currentTimeMillis()
                if (now < attempt.lockedUntil) {
                    val retryAfterSec = ((attempt.lockedUntil - now) / 1000).coerceAtLeast(1)
                    Log.w("ArtemisServer", "Pairing locked for $remote until ${attempt.lockedUntil} ($retryAfterSec s)")
                    return HttpResponse(
                        429, contentType = "application/json",
                        headers = mapOf("Retry-After" to retryAfterSec.toString()),
                        body = """{"error":"pairing_locked","message":"Too many failed attempts — pairing locked, retry later"}"""
                    )
                }
            }
        }

        // Check the pairing code (constant-time compare — no timing side channel)
        val currentCode = pairingCode
        val codeMatches = currentCode != null &&
            MessageDigest.isEqual(currentCode.code.toByteArray(), code.toByteArray())
        if (!codeMatches) {
            if (!isLoopback) synchronized(pairingAttempts) {
                val prev = pairingAttempts[remote]
                val fails = (prev?.fails ?: 0) + 1
                val firstFailAt = prev?.firstFailAt ?: System.currentTimeMillis()
                var lockedUntil = 0L
                if (fails >= maxPairingFailures) {
                    // Exponential: 1 min after the 5th failure, doubling, capped
                    // at the rotation window.
                    val backoffMs = pairingBackoffBaseMs shl (fails - maxPairingFailures).coerceAtMost(4)
                    lockedUntil = firstFailAt + backoffMs.coerceAtMost(AuthManager.PAIRING_CODE_EXPIRY_MS)
                    Log.w("ArtemisServer", "Pairing failure #$fails for $remote — locked until $lockedUntil")
                }
                pairingAttempts[remote] = PairingAttempt(fails, firstFailAt, lockedUntil)
            }
            return jsonResponse(401, mapOf("error" to "pairing_failed", "message" to "Invalid pairing code"))
        }
        if (System.currentTimeMillis() > currentCode!!.expiresAt) {
            return jsonResponse(401, mapOf("error" to "pairing_failed", "message" to "Pairing code expired"))
        }

        // Success — clear this IP's attempt tracking
        synchronized(pairingAttempts) { pairingAttempts.remove(remote) }

        val deviceId = try { android.os.Build.getSerial() } catch (_: Exception) { android.os.Build.FINGERPRINT }
        // Unpredictable client ID: 12 random bytes, base64url. The previous
        // millisecond-based ID was guessable and could collide.
        val clientIdBytes = ByteArray(12).also { secureRandom.nextBytes(it) }
        val clientId = "client_" + android.util.Base64.encodeToString(
            clientIdBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        // Optional human-friendly device name sent by the dashboard on pairing.
        val clientName = body["name"]?.takeIf { it.isNotBlank() }?.take(64) ?: "Dashboard"

        val token = runBlocking {
            authManager.issueToken(
                deviceId = deviceId,
                clientId = clientId,
                scope = AuthManager.SCOPE_ADMIN
            )
        }
        val refreshToken = runBlocking { authManager.createRefreshToken(clientId) }

        // Remember the paired client persistently — survives server restarts.
        authManager.rememberPairedClient(
            PairedClient(
                clientId = clientId,
                clientName = clientName,
                permissionScope = AuthManager.SCOPE_ADMIN,
                pairedAt = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                isActive = true
            )
        )

        connectedClients++

        // Rotate the code immediately after a successful pairing so a code
        // that has been observed once (on screen, by anyone nearby) can never
        // pair a second dashboard. The dashboard remains paired via tokens.
        val fresh = authManager.generatePairingCode()
        pairingCode = fresh
        app.currentPairingCode = fresh
        synchronized(pairingAttempts) { pairingAttempts.clear() }
        Log.i("ArtemisServer", "Pairing succeeded for $clientName ($clientId) — code rotated")

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

        val deviceId = try { android.os.Build.getSerial() } catch (_: Exception) { android.os.Build.FINGERPRINT }

        when (val result = runBlocking { authManager.refreshSession(refreshToken) }) {
            is RefreshSessionResult.Ok -> {
                // Rotation happened atomically: the presented refresh token is
                // now superseded and the client has a fresh one.
                val newToken = runBlocking {
                    authManager.issueToken(
                        deviceId = deviceId,
                        clientId = result.clientId,
                        scope = AuthManager.SCOPE_ADMIN
                    )
                }
                return jsonResponse(200, mapOf(
                    "token" to newToken,
                    "refreshToken" to result.newRefreshToken,
                    "expiresAt" to (System.currentTimeMillis() + AuthManager.TOKEN_EXPIRY_MS)
                ))
            }
            is RefreshSessionResult.ReplayDetected -> {
                // Reuse of a rotated refresh token after the grace window, or
                // reuse of a revoked token. The client was revoked — full
                // re-pairing required. (error code lets the dashboard
                // distinguish this from a benign expiry)
                return jsonResponse(401, mapOf(
                    "error" to "replay_detected",
                    "message" to "Refresh token reuse detected — device revoked, re-pair required"
                ))
            }
            is RefreshSessionResult.Expired -> {
                return jsonResponse(401, mapOf(
                    "error" to "invalid_token",
                    "message" to "Refresh token expired — re-pair required"
                ))
            }
            RefreshSessionResult.Invalid -> {
                return jsonResponse(401, mapOf(
                    "error" to "invalid_token",
                    "message" to "Refresh token is invalid"
                ))
            }
        }
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
        val clients = authManager.getPairedClients()
        val list = clients.map { c ->
            mapOf(
                "clientId" to c.clientId,
                "clientName" to c.clientName,
                "scope" to c.permissionScope,
                "pairedAt" to c.pairedAt,
                "lastSeen" to (c.lastSeen ?: 0L),
                "isActive" to c.isActive
            )
        }
        return jsonResponse(200, mapOf("clients" to list))
    }

    private fun clientRevokeHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"]
        if (id == null) {
            return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing client ID"))
        }
        val revoked = authManager.revokeClient(id)
        if (!revoked) {
            return jsonResponse(404, mapOf("error" to "not_found", "message" to "Client not found"))
        }
        return jsonResponse(200, mapOf("status" to "revoked", "clientId" to id))
    }

    /** Recovery path for an accidental revoke — reactivates a client. */
    private fun clientUnrevokeHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"]
        if (id == null) {
            return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing client ID"))
        }
        val revived = authManager.unrevokeClient(id)
        if (!revived) {
            return jsonResponse(404, mapOf("error" to "not_found", "message" to "Client not found"))
        }
        return jsonResponse(200, mapOf("status" to "revived", "clientId" to id))
    }

    /** Revoke every paired dashboard (lost/stolen phone, "revoke all"). */
    private fun revokeAllHandler(req: HttpRequest): HttpResponse {
        authManager.revokeAllClients()
        Log.w("ArtemisServer", "All paired dashboards revoked")
        return jsonResponse(200, mapOf("status" to "revoked", "count" to 0))
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

        // CameraController serializes photo capture itself. Do not take its
        // mutex here as well: kotlinx Mutex is non-reentrant and nesting it
        // would make every photo request wait forever. Video is guarded in
        // its own handler because VideoRecorder does not own that lock.
        val result = runBlocking { cameraController.capturePhoto(cameraId) }
        if (result.isSuccess) {
            val capture = result.getOrThrow()
            val captureJson = json.encodeToString(capture)
            // Include status so dashboard clients can distinguish
            // CAPTURE_OK from CAPTURE_FAILED without parsing fields.
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"),
                """{"status":"ok","capture":$captureJson}""")
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

    private fun cameraCaptureFileHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"] ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing capture ID"))
        val file = cameraController.getCaptureFile(id)
            ?: return jsonResponse(404, mapOf("error" to "not_found", "message" to "Capture file not found"))
        return binaryFileResponse(file, "image/jpeg")
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

    private fun micRecordingFileHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"] ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing recording ID"))
        val file = micController.getRecordingFile(id)
            ?: return jsonResponse(404, mapOf("error" to "not_found", "message" to "Recording file not found"))
        return binaryFileResponse(file, if (file.name.endsWith(".wav")) "audio/wav" else "audio/pcm")
    }

    // ============================================================
    // Call logs
    // ============================================================

    private fun callLogsHandler(req: HttpRequest): HttpResponse {
        val limit = req.queryParams["limit"]?.toIntOrNull() ?: 100
        val logs = runBlocking { callLogsProvider.getCallLogs(limit) }
        val logsJson = json.encodeToString(logs)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"),
            """{"calls":$logsJson,"count":${logs.size}}""")
    }

    // ============================================================
    // SMS
    // ============================================================

    private fun smsHandler(req: HttpRequest): HttpResponse {
        val box = req.queryParams["box"] ?: "inbox"
        val limit = req.queryParams["limit"]?.toIntOrNull() ?: 100
        val includeBody = req.queryParams["includeBody"] == "1" || req.queryParams["includeBody"] == "true"
        val messages = runBlocking { smsProvider.getSms(box, limit, includeBody) }
        val smsJson = json.encodeToString(messages)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"),
            """{"messages":$smsJson,"count":${messages.size},"box":"${box.lowercase()}","bodiesRedacted":${!includeBody}}""")
    }

    // ============================================================
    // Call recorder
    // ============================================================

    private fun callRecorderStatusHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, mapOf(
            "enabled" to callRecorder.autoRecordEnabled,
            "recording" to callRecorder.isRecordingCall,
            "count" to callRecorder.getRecordings().size
        ))
    }

    private fun callRecorderToggleHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val enabled = body?.get("enabled")
        when (enabled) {
            "true", "1" -> callRecorder.autoRecordEnabled = true
            "false", "0" -> callRecorder.autoRecordEnabled = false
            else -> callRecorder.autoRecordEnabled = !callRecorder.autoRecordEnabled
        }
        Log.i("ArtemisServer", "Call auto-recording ${if (callRecorder.autoRecordEnabled) "enabled" else "disabled"} by dashboard")
        return jsonResponse(200, mapOf("enabled" to callRecorder.autoRecordEnabled))
    }

    private fun callRecordingsHandler(req: HttpRequest): HttpResponse {
        val recordings = callRecorder.getRecordings()
        val recJson = json.encodeToString(recordings)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"),
            """{"recordings":$recJson,"count":${recordings.size}}""")
    }

    private fun callRecordingGetHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"] ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing recording ID"))
        val recording = callRecorder.getRecording(id)
        if (recording != null) {
            val recJson = json.encodeToString(recording)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), recJson)
        }
        return jsonResponse(404, mapOf("error" to "not_found", "message" to "Recording not found"))
    }

    private fun callRecordingFileHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"] ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing recording ID"))
        val file = callRecorder.getRecordingFile(id)
            ?: return jsonResponse(404, mapOf("error" to "not_found", "message" to "Recording file not found"))
        return binaryFileResponse(file, "audio/mp4")
    }

    // ============================================================
    // Video recording
    // ============================================================

    private fun videoRecordHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val cameraId = body?.get("cameraId") ?: "back"
        val durationMs = body?.get("durationMs")?.toLongOrNull() ?: 15_000L

        val result = runBlocking {
            CameraController.cameraMutex.withLock {
                videoRecorder.record(cameraId, durationMs)
            }
        }
        if (result.isSuccess) {
            val video = result.getOrThrow()
            val videoJson = json.encodeToString(video)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), videoJson)
        }
        return jsonResponse(500, mapOf("error" to "record_failed", "message" to (result.exceptionOrNull()?.message ?: "Unknown error")))
    }

    private fun videoListHandler(req: HttpRequest): HttpResponse {
        val videos = videoRecorder.getVideos()
        val videosJson = json.encodeToString(videos)
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"),
            """{"videos":$videosJson,"count":${videos.size}}""")
    }

    private fun videoGetHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"] ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing video ID"))
        val video = videoRecorder.getVideo(id)
        if (video != null) {
            val videoJson = json.encodeToString(video)
            return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), videoJson)
        }
        return jsonResponse(404, mapOf("error" to "not_found", "message" to "Video not found"))
    }

    private fun videoFileHandler(req: HttpRequest): HttpResponse {
        val id = req.pathParams["id"] ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing video ID"))
        val file = videoRecorder.getVideoFile(id)
            ?: return jsonResponse(404, mapOf("error" to "not_found", "message" to "Video file not found"))
        return binaryFileResponse(file, "video/mp4")
    }

    /**
     * Stream a file's bytes with correct Content-Type and Content-Length.
     * Used for photos, recordings and videos so the dashboard can pull
     * captured media over the same authenticated TLS channel.
     */
    private fun binaryFileResponse(file: java.io.File, mimeType: String): HttpResponse {
        return try {
            val bytes = file.readBytes()
            HttpResponse(
                200,
                contentType = mimeType,
                headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Disposition" to "attachment; filename=\"${file.name}\""
                ),
                binaryBody = bytes
            )
        } catch (e: Exception) {
            Log.e("ArtemisServer", "File read failed: ${e.message}")
            jsonResponse(500, mapOf("error" to "read_failed", "message" to "Could not read file"))
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
     * Rotates to a new 6-digit code; the value is only ever shown
     * on the phone screen — never logged and never in network responses.
     */
    fun regeneratePairingCode(): String {
        val code = authManager.generatePairingCode()
        pairingCode = code
        app.currentPairingCode = code
        Log.i("ArtemisServer", "Pairing code regenerated (screen-only)")
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

            // TLS: generate/load the AndroidKeyStore self-signed cert. Loopback
            // connections (phone UI) stay plaintext; every network connection
            // is upgraded to TLS by the accept loop.
            if (useTls) {
                val ctx = TlsManager.getSslContext(app)
                sslSocketFactory = ctx.socketFactory
                app.currentCertFingerprint = TlsManager.getFingerprint(app)
                Log.i("ArtemisServer", "TLS enabled — cert SHA-256 fingerprint ${TlsManager.getFingerprint(app).take(23)}…")
            } else {
                sslSocketFactory = null
                app.currentCertFingerprint = null
                Log.w("ArtemisServer", "TLS DISABLED — plaintext server (dev mode)")
            }

            // Generate initial pairing code and publish to shared state
            pairingCode = authManager.generatePairingCode()
            app.currentPairingCode = pairingCode

            serverScope.launch {
                acceptLoop()
            }

            // Rotate the pairing code every 5 minutes so a stale code
            // can never be used for pairing later. Rotation also clears
            // per-IP pairing lockouts — the new code resets the game.
            serverScope.launch {
                while (serverScope.isActive) {
                    delay(AuthManager.PAIRING_CODE_EXPIRY_MS)
                    if (!serverScope.isActive) break
                    val fresh = authManager.generatePairingCode()
                    pairingCode = fresh
                    app.currentPairingCode = fresh
                    synchronized(pairingAttempts) { pairingAttempts.clear() }
                    Log.i("ArtemisServer", "Pairing code rotated (new code shown on screen only)")
                }
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
                    val loopback = client.inetAddress?.isLoopbackAddress == true
                    Log.i("ArtemisServer", "Connection accepted from ${client.inetAddress.hostAddress}" +
                            if (loopback) " (loopback)" else " (TLS)")
                    serverScope.launch {
                        try {
                            if (!loopback && sslSocketFactory != null) {
                                // Upgrade to TLS. Plaintext talkers on the network
                                // fail the handshake and get nothing but a closed
                                // connection — exactly the "garbage to others" goal.
                                client.soTimeout = 15000 // fail slow handshakes fast
                                val ssl = sslSocketFactory!!.createSocket(
                                    client, client.inetAddress.hostAddress, port, true
                                ) as SSLSocket
                                // TLS 1.3 preferred; restricted TLS 1.2 fallback
                                // (ECDHE + AEAD only); server cipher preference.
                                TlsManager.configureSocket(ssl)
                                ssl.startHandshake()
                                handleConnection(ssl)
                            } else {
                                handleConnection(client)
                            }
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

            // Standard rate limiting — per-IP fixed window (loopback exempt,
            // the phone UI polls every few seconds).
            val remote = request.remoteAddress
            val isLoopback = remote == "127.0.0.1" || remote == "::1" || remote.startsWith("127.")
            if (!isLoopback && !requestLimiter.allow(remote)) {
                sendHttpResponse(output, HttpResponse(
                    429, contentType = "application/json",
                    body = """{"error":"rate_limited","message":"Too many requests — try again shortly"}"""
                ))
                return
            }

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
                // Never echo exception details to the client — they can leak
                // stack traces, file paths and internals to network peers.
                sendHttpResponse(client.getOutputStream(),
                    HttpResponse(500, contentType = "application/json",
                        body = """{"error":"internal_error","message":"Internal server error"}"""))
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
            val bodyBytes = response.binaryBody ?: response.body.toByteArray(Charsets.UTF_8)
            val statusText = when (response.statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                409 -> "Conflict"
                429 -> "Too Many Requests"
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
            headerLines.append("Server: Artemis/2.2.0\r\n")
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

// ============================================================
// RateLimiter — per-IP fixed-window counter
// ============================================================

private class RateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long
) {
    // ip -> [windowStartMs, count]
    private val buckets = mutableMapOf<String, LongArray>()
    private val lock = Any()

    /** Returns true if the request is allowed, false if over the limit. */
    fun allow(ip: String): Boolean {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val b = buckets.getOrPut(ip) { longArrayOf(now, 0) }
            if (now - b[0] >= windowMs) {
                b[0] = now
                b[1] = 0
            }
            if (b[1] >= maxRequests) return false
            b[1]++
            return true
        }
    }
}
