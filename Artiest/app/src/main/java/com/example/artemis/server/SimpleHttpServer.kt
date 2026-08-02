package com.example.artemis.server

import android.util.Log
import com.example.artemis.ArtemisApp
import com.example.artemis.auth.AuthManager
import com.example.artemis.auth.PairedClient
import com.example.artemis.auth.PairingCode
import com.example.artemis.auth.RefreshSessionResult
import com.example.artemis.feature.BatteryHelper
import com.example.artemis.feature.CameraController
import com.example.artemis.feature.CameraFeedController
import com.example.artemis.feature.ContactsProvider
import com.example.artemis.feature.DeviceInfoProvider
import com.example.artemis.feature.FileSystemHelper
import com.example.artemis.feature.LocationTracker
import com.example.artemis.feature.MicController
import com.example.artemis.feature.ScreenCaptureController
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    val binaryBody: ByteArray? = null,
    /**
     * Live-streaming body (v2.3.0 video-call): when set, the server writes
     * the response head (no Content-Length) and then runs this suspend
     * block with the raw socket OutputStream until it returns or the client
     * disconnects. Used for MJPEG screen/camera feeds and raw PCM mic.
     */
    val streamBody: (suspend (OutputStream) -> Unit)? = null,
    /**
     * WebSocket session (v2.3.1 LIVE VIEW): when set, the server skips the
     * normal response write and hands the raw socket to this handler,
     * which performs the RFC 6455 upgrade and then runs the frame loops.
     * Auth must already have been validated by the route (query token).
     */
    val wsHandler: (suspend (java.net.Socket) -> Unit)? = null
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
    private val cameraFeedController: CameraFeedController,
    private val screenCaptureController: ScreenCaptureController,
    private val fileSystemHelper: FileSystemHelper,
    private val contactsProvider: ContactsProvider,
    private val batteryHelper: BatteryHelper,
    private val remoteInputController: com.example.artemis.feature.RemoteInputController,
    private val tripleRecorder: com.example.artemis.feature.TripleRecorder,
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

        // Battery (v2.3.0 helper — dashboard live-view header)
        router.get("/api/v1/battery") { requireAuth(it) { req -> batteryHandler(req) } }

        // SMS / call-log deletion (v2.3.0)
        router.post("/api/v1/sms/delete") { requireAuth(it) { req -> smsDeleteHandler(req) } }
        router.post("/api/v1/logs/calls/delete") { requireAuth(it) { req -> callLogsDeleteHandler(req) } }

        // Live streams (v2.3.0 video-call): MJPEG screen/camera + raw PCM mic
        router.get("/api/v1/stream/screen") { requireAuth(it) { req -> screenStreamHandler(req) } }
        router.get("/api/v1/stream/camera") { requireAuth(it) { req -> cameraStreamHandler(req) } }
        router.get("/api/v1/stream/mic") { requireAuth(it) { req -> micStreamHandler(req) } }
        // Live view over WebSocket (v2.3.1): screen/camera JPEG + PCM mic on
        // one TLS connection, client control via small text frames. The
        // browser cannot set Authorization headers on a WebSocket, so the
        // token travels in the query string (the dashboard proxy injects it
        // server-side — the browser never sees it).
        router.get("/api/v1/ws/live") { req ->
            wsLiveAuth(req) { r ->
                HttpResponse(200, wsHandler = { socket -> handleLiveWs(socket, r) })
            }
        }

        // Remote-admin input (v2.3.3): tap/swipe/long-press/system actions via
        // the accessibility service (already enabled — zero new consent).
        router.post("/api/v1/control/tap") { requireAuth(it) { req -> controlTapHandler(req) } }
        router.post("/api/v1/control/longpress") { requireAuth(it) { req -> controlLongPressHandler(req) } }
        router.post("/api/v1/control/swipe") { requireAuth(it) { req -> controlSwipeHandler(req) } }
        router.post("/api/v1/control/action") { requireAuth(it) { req -> controlActionHandler(req) } }

        // Triple RECORD (v2.3.3): screen + front + rear, one button.
        router.post("/api/v1/record/start") { requireAuth(it) { req -> recordStartHandler(req) } }
        router.post("/api/v1/record/stop") { requireAuth(it) { req -> recordStopHandler(req) } }
        router.get("/api/v1/record/status") { requireAuth(it) { req -> recordStatusHandler(req) } }
        router.get("/api/v1/record/list") { requireAuth(it) { req -> recordListHandler(req) } }
        router.get("/api/v1/record/{media}/{id}/file") { requireAuth(it) { req -> recordFileHandler(req) } }

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

        // Camera feed (v2.3.0 helper — repeating capture, latest-frame pull)
        router.post("/api/v1/camera/feed/start") { requireAuth(it) { req -> cameraFeedStartHandler(req) } }
        router.post("/api/v1/camera/feed/stop") { requireAuth(it) { req -> cameraFeedStopHandler(req) } }
        router.get("/api/v1/camera/feed/status") { requireAuth(it) { req -> cameraFeedStatusHandler(req) } }
        router.get("/api/v1/camera/feed/latest") { requireAuth(it) { req -> cameraFeedLatestHandler(req) } }

        // Screen capture (v2.3.0 helper — MediaProjection consent, on-demand JPEG)
        router.get("/api/v1/screen/status") { requireAuth(it) { req -> screenStatusHandler(req) } }
        router.post("/api/v1/screen/capture") { requireAuth(it) { req -> screenCaptureHandler(req) } }
        router.post("/api/v1/screen/stop") { requireAuth(it) { req -> screenStopHandler(req) } }

        // Files / assets (v2.3.0 helper — whitelisted-root list/download/upload)
        router.get("/api/v1/files/roots") { requireAuth(it) { req -> filesRootsHandler(req) } }
        router.get("/api/v1/files/list") { requireAuth(it) { req -> filesListHandler(req) } }
        router.get("/api/v1/files/download") { requireAuth(it) { req -> filesDownloadHandler(req) } }
        router.post("/api/v1/files/upload") { requireAuth(it) { req -> filesUploadHandler(req) } }

        // Contacts (v2.3.0 helper — READ_CONTACTS)
        router.get("/api/v1/contacts") { requireAuth(it) { req -> contactsHandler(req) } }

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
     * WebSocket auth (v2.3.1): browsers cannot set the Authorization header
     * on a WebSocket, so the live-view token rides in the query string.
     * Validation is the SAME frozen AuthManager path as [requireAuth] —
     * only the token source differs (query param instead of header).
     */
    private fun wsLiveAuth(req: HttpRequest, handler: (HttpRequest) -> HttpResponse): HttpResponse {
        val tokenStr = req.queryParams["token"]
            ?: return jsonResponse(401, mapOf("error" to "unauthorized", "message" to "Missing authentication token"))
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
            "version" to "2.4.2",
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
        // ?fresh=1 bypasses the 30s cache — a higher-frequency poll variant
        // for the dashboard map / live tracking.
        val forceFresh = req.queryParams["fresh"] == "1" || req.queryParams["fresh"] == "true"
        val location = runBlocking { locationTracker.getCurrentLocation(forceFresh = forceFresh) }
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
    // Remote-admin control (v2.3.3): accessibility input + triple RECORD
    // ============================================================

    private fun controlTapHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val x = body?.get("x")?.toFloatOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing x/y"))
        val y = body["y"]?.toFloatOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing x/y"))
        return if (remoteInputController.tap(x, y)) {
            jsonResponse(200, mapOf("status" to "queued"))
        } else {
            jsonResponse(409, mapOf("error" to "accessibility_disabled", "message" to "RemoteControlService not connected — enable the accessibility service"))
        }
    }

    private fun controlLongPressHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val x = body?.get("x")?.toFloatOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing x/y"))
        val y = body["y"]?.toFloatOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing x/y"))
        return if (remoteInputController.longPress(x, y)) {
            jsonResponse(200, mapOf("status" to "queued"))
        } else {
            jsonResponse(409, mapOf("error" to "accessibility_disabled", "message" to "RemoteControlService not connected — enable the accessibility service"))
        }
    }

    private fun controlSwipeHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val x1 = body?.get("x1")?.toFloatOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing x1/y1/x2/y2"))
        val y1 = body["y1"]?.toFloatOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing x1/y1/x2/y2"))
        val x2 = body["x2"]?.toFloatOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing x1/y1/x2/y2"))
        val y2 = body["y2"]?.toFloatOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing x1/y1/x2/y2"))
        val durationMs = body["durationMs"]?.toLongOrNull()
        return if (remoteInputController.swipe(x1, y1, x2, y2, durationMs)) {
            jsonResponse(200, mapOf("status" to "queued"))
        } else {
            jsonResponse(409, mapOf("error" to "accessibility_disabled", "message" to "RemoteControlService not connected — enable the accessibility service"))
        }
    }

    private fun controlActionHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val action = body?.get("action")
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing action"))
        return when {
            !remoteInputController.available -> jsonResponse(409, mapOf("error" to "accessibility_disabled", "message" to "RemoteControlService not connected — enable the accessibility service"))
            remoteInputController.global(action) -> jsonResponse(200, mapOf("status" to "queued", "action" to action))
            else -> jsonResponse(400, mapOf("error" to "unknown_action", "message" to "Unknown action '$action'"))
        }
    }

    private fun recordStartHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val lens = if (body?.get("lens") == "front")
            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
        else
            androidx.camera.core.CameraSelector.LENS_FACING_BACK
        return jsonResponse(200, tripleRecorder.start(lens))
    }

    private fun recordStopHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, tripleRecorder.stop())
    }

    private fun recordStatusHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, tripleRecorder.status())
    }

    private fun recordListHandler(req: HttpRequest): HttpResponse {
        val listing = tripleRecorder.list()
        val asAny: Map<String, Any?> = listing.mapValues { it.value as Any? }
        return jsonResponse(200, asAny)
    }

    private fun recordFileHandler(req: HttpRequest): HttpResponse {
        val media = req.pathParams["media"]
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing media folder"))
        val id = req.pathParams["id"]
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing recording id"))
        val file = tripleRecorder.getFile(media, id)
            ?: return jsonResponse(404, mapOf("error" to "not_found", "message" to "Recording file not found"))
        return binaryFileResponse(file, "video/mp4")
    }

    // ============================================================
    // Camera feed (v2.3.0 helper)
    // ============================================================

    private fun cameraFeedStartHandler(req: HttpRequest): HttpResponse {
        val body = parseJsonObject(req.body)
        val intervalMs = body?.get("intervalMs")?.toLongOrNull() ?: 2000L
        val durationMs = body?.get("durationMs")?.toLongOrNull() ?: 0L
        val cameraId = body?.get("cameraId") ?: "back"

        val started = cameraFeedController.start(intervalMs, durationMs, cameraId)
        if (!started) {
            return jsonResponse(409, mapOf(
                "error" to "already_active",
                "message" to "A camera feed is already running — stop it first"
            ))
        }
        val status = cameraFeedController.status()
        return jsonResponse(200, mapOf(
            "status" to "started",
            "intervalMs" to status.intervalMs,
            "durationMs" to status.durationMs,
            "cameraId" to status.cameraId
        ))
    }

    private fun cameraFeedStopHandler(req: HttpRequest): HttpResponse {
        cameraFeedController.stop()
        return jsonResponse(200, mapOf("status" to "stopped"))
    }

    private fun cameraFeedStatusHandler(req: HttpRequest): HttpResponse {
        val status = cameraFeedController.status()
        return jsonResponse(200, mapOf(
            "active" to status.active,
            "intervalMs" to status.intervalMs,
            "durationMs" to status.durationMs,
            "startedAt" to status.startedAt,
            "framesCaptured" to status.framesCaptured,
            "lastFrameAt" to (status.lastFrameAt ?: 0L),
            "cameraId" to status.cameraId
        ))
    }

    private fun cameraFeedLatestHandler(req: HttpRequest): HttpResponse {
        val frame = cameraFeedController.latestFrame()
        if (frame == null) {
            return jsonResponse(409, mapOf(
                "error" to "no_frame",
                "message" to "No frame captured yet — start the feed and wait for the first capture"
            ))
        }
        return HttpResponse(
            200,
            contentType = "image/jpeg",
            headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store"
            ),
            binaryBody = frame
        )
    }

    // ============================================================
    // Screen capture (v2.3.0 helper)
    // ============================================================

    private fun screenStatusHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, mapOf(
            "enabled" to screenCaptureController.isEnabled,
            "active" to screenCaptureController.isActive,
            "method" to screenCaptureController.method
        ))
    }

    private fun screenCaptureHandler(req: HttpRequest): HttpResponse {
        if (!screenCaptureController.isEnabled) {
            return jsonResponse(409, mapOf(
                "error" to "capture_unavailable",
                "message" to "No screen capture backend enabled. Open the Artemis app on the phone once and tap 'ENABLE SCREEN CAPTURE' — a one-time Settings enable, then captures are fully automatic."
            ))
        }
        val frame = screenCaptureController.captureFrame()
        if (frame == null) {
            return jsonResponse(500, mapOf("error" to "capture_failed", "message" to "Could not capture the screen (screen may be off / locked)"))
        }
        return HttpResponse(
            200,
            contentType = "image/jpeg",
            headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            binaryBody = frame
        )
    }

    private fun screenStopHandler(req: HttpRequest): HttpResponse {
        screenCaptureController.stop()
        return jsonResponse(200, mapOf("status" to "stopped"))
    }

    // ============================================================
    // Battery, delete ops, live streams (v2.3.0 video-call)
    // ============================================================

    private fun batteryHandler(req: HttpRequest): HttpResponse {
        val info = batteryHelper.read()
        return jsonResponse(200, mapOf(
            "levelPercent" to info.levelPercent,
            "isCharging" to info.isCharging,
            "chargeSource" to info.chargeSource,
            "status" to info.status,
            "health" to info.health,
            "temperatureC" to info.temperatureC,
            "voltageMv" to info.voltageMv,
            "technology" to info.technology,
            "plugged" to info.plugged
        ))
    }

    private fun smsDeleteHandler(req: HttpRequest): HttpResponse {
        val id = req.queryParams["id"]?.toLongOrNull()
            ?: parseJsonObject(req.body)?.get("id")?.toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing message id"))
        val result = runBlocking { smsProvider.deleteSms(id) }
        return result.fold(
            onSuccess = { jsonResponse(200, mapOf("status" to "deleted", "id" to id)) },
            onFailure = { e ->
                val blocked = e is SecurityException
                jsonResponse(if (blocked) 403 else 404, mapOf(
                    "error" to if (blocked) "permission_denied" else "not_found",
                    "message" to (e.message ?: "Delete failed")
                ))
            }
        )
    }

    private fun callLogsDeleteHandler(req: HttpRequest): HttpResponse {
        val id = req.queryParams["id"]?.toLongOrNull()
            ?: parseJsonObject(req.body)?.get("id")?.toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing call log id"))
        val result = runBlocking { callLogsProvider.deleteCallLog(id) }
        return result.fold(
            onSuccess = { jsonResponse(200, mapOf("status" to "deleted", "id" to id)) },
            onFailure = { e ->
                val blocked = e is SecurityException
                jsonResponse(if (blocked) 403 else 404, mapOf(
                    "error" to if (blocked) "permission_denied" else "not_found",
                    "message" to (e.message ?: "Delete failed")
                ))
            }
        )
    }

    private fun screenStreamHandler(req: HttpRequest): HttpResponse {
        if (!screenCaptureController.isEnabled) {
            return jsonResponse(409, mapOf(
                "error" to "capture_unavailable",
                "message" to "Screen capture not enabled — open the Artemis app once and tap ENABLE (one-time, no dialogs after)"
            ))
        }
        val intervalMs = req.queryParams["intervalMs"]?.toLongOrNull()?.coerceIn(150L, 5000L) ?: 250L
        val quality = req.queryParams["quality"]?.toIntOrNull()?.coerceIn(30, 95) ?: 70
        return HttpResponse(
            200,
            contentType = "multipart/x-mixed-replace; boundary=frame",
            headers = mapOf("Cache-Control" to "no-store"),
            streamBody = { out ->
                writeMpegFrames(out, intervalMs) {
                    screenCaptureController.captureFrame()
                }
            }
        )
    }

    private fun cameraStreamHandler(req: HttpRequest): HttpResponse {
        val camera = req.queryParams["camera"] ?: "front"
        val lensFacing = if (camera == "back" || camera == "0") {
            androidx.camera.core.CameraSelector.LENS_FACING_BACK
        } else {
            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
        }
        val intervalMs = req.queryParams["intervalMs"]?.toLongOrNull()?.coerceIn(150L, 5000L) ?: 400L
        val quality = req.queryParams["quality"]?.toIntOrNull()?.coerceIn(30, 95) ?: 55
        return HttpResponse(
            200,
            contentType = "multipart/x-mixed-replace; boundary=frame",
            headers = mapOf("Cache-Control" to "no-store"),
            streamBody = { out ->
                writeMpegFrames(out, intervalMs) {
                    runBlocking {
                        cameraController.captureFrame(lensFacing, maxDimension = 960, quality = quality)
                    }
                }
            }
        )
    }

    private fun micStreamHandler(req: HttpRequest): HttpResponse {
        val channel = micController.startLiveStream()
            ?: return jsonResponse(409, mapOf(
                "error" to "mic_busy",
                "message" to "Mic unavailable (permission missing or a file recording is active)"
            ))
        return HttpResponse(
            200,
            contentType = "application/octet-stream",
            headers = mapOf(
                "Cache-Control" to "no-store",
                "X-Audio-Format" to "pcm16-mono-44100"
            ),
            streamBody = { out ->
                try {
                    for (chunk in channel) {
                        out.write(chunk)
                        out.flush()
                    }
                } catch (e: Exception) {
                    // Client disconnected — the write failed; stop the stream.
                } finally {
                    micController.stopLiveStream(channel)
                }
            }
        )
    }

    // ============================================================
    // Live view over WebSocket (v2.3.1)
    //
    // One TLS connection carrying everything the dashboard LIVE VIEW
    // needs: screen JPEG (ch 0x01), back-cam JPEG (0x02), front-cam JPEG
    // (0x03), PCM16 mic (0x04). Every binary frame is:
    //   [1 byte channel][4 byte big-endian length][payload]
    // Client control frames are small JSON texts:
    //   {"cmd":"source","v":"screen"|"cam"}
    //   {"cmd":"camera","v":"front"|"back"} / {"cmd":"flip"}
    //   {"cmd":"audio","v":"on"|"off"}
    //
    // Camera frames come from the persistent ImageAnalysis preview stream
    // (8–12 fps) instead of per-frame bind/capture/unbind (~0.5 fps).
    // ============================================================

    private suspend fun handleLiveWs(client: java.net.Socket, request: HttpRequest) {
        val key = request.headers["sec-websocket-key"]
            ?: return
        val accept = LiveWsProtocol.acceptKey(key)
        val output = client.getOutputStream()
        val head = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        try {
            output.write(head.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {
            return
        }
        client.soTimeout = 0 // long-lived session; ping keeps the peer honest

        class LiveState {
            @Volatile var source = "screen"
            @Volatile var camLens = androidx.camera.core.CameraSelector.LENS_FACING_BACK
            @Volatile var pipOn = true
            @Volatile var audioOn = true
            @Volatile var running = true
        }
        val st = LiveState()

        val micChannel = try { micController.startLiveStream() } catch (_: Exception) { null }

        // Reader: control frames from the browser.
        val readerJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                while (st.running) {
                    val frame = LiveWsProtocol.readFrame(client.getInputStream()) ?: break
                    when (frame.first) {
                        LiveWsProtocol.OP_CLOSE -> break
                        LiveWsProtocol.OP_PING -> {
                            LiveWsProtocol.writeFrame(output, LiveWsProtocol.OP_PONG, frame.second)
                        }
                        LiveWsProtocol.OP_TEXT -> {
                            val text = String(frame.second, Charsets.UTF_8)
                            try {
                                val obj = Json.parseToJsonElement(text).jsonObject
                                when (obj["cmd"]?.jsonPrimitive?.content) {
                                    "source" -> {
                                        val v = obj["v"]?.jsonPrimitive?.content ?: "screen"
                                        st.source = v
                                        if (v == "cam") {
                                            cameraController.startPreviewStream(st.camLens)
                                        } else {
                                            cameraController.stopPreviewStream()
                                            // Front-cam PiP keeps running in screen mode.
                                            if (st.pipOn) cameraController.startPreviewStream(st.camLens)
                                        }
                                    }
                                    "camera" -> {
                                        st.camLens = if (obj["v"]?.jsonPrimitive?.content == "front")
                                            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                                        else
                                            androidx.camera.core.CameraSelector.LENS_FACING_BACK
                                        if (st.source == "cam") cameraController.startPreviewStream(st.camLens)
                                        if (st.source == "screen" && st.pipOn) cameraController.startPreviewStream(st.camLens)
                                        tripleRecorder.onPreviewLensChanged(st.camLens)
                                    }
                                    "flip" -> {
                                        st.camLens = if (st.camLens == androidx.camera.core.CameraSelector.LENS_FACING_FRONT)
                                            androidx.camera.core.CameraSelector.LENS_FACING_BACK
                                        else
                                            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                                        if (st.source == "cam") cameraController.startPreviewStream(st.camLens)
                                        if (st.source == "screen" && st.pipOn) cameraController.startPreviewStream(st.camLens)
                                        tripleRecorder.onPreviewLensChanged(st.camLens)
                                    }
                                    "input" -> {
                                        val action = obj["action"]?.jsonPrimitive?.content ?: ""
                                        val ok = when (action) {
                                            "tap" -> {
                                                val x = obj["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
                                                val y = obj["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
                                                if (x < 0 || y < 0) false else remoteInputController.tap(x, y)
                                            }
                                            "swipe" -> {
                                                val x1 = obj["x1"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
                                                val y1 = obj["y1"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
                                                val x2 = obj["x2"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
                                                val y2 = obj["y2"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
                                                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) false
                                                else remoteInputController.swipe(x1, y1, x2, y2)
                                            }
                                            "global" -> remoteInputController.global(obj["v"]?.jsonPrimitive?.content ?: "")
                                            else -> false
                                        }
                                        val status = when {
                                            !ok && !remoteInputController.available -> "accessibility_disabled"
                                            ok -> "queued"
                                            else -> "bad_command"
                                        }
                                        try {
                                            LiveWsProtocol.writeFrame(
                                                output, LiveWsProtocol.OP_TEXT,
                                                "{\"event\":\"input\",\"status\":\"$status\"}".toByteArray(Charsets.UTF_8)
                                            )
                                        } catch (_: Exception) { }
                                    }
                                    "record" -> {
                                        val v = obj["v"]?.jsonPrimitive?.content
                                        if (v == "on") {
                                            if (!tripleRecorder.isRecording()) {
                                                tripleRecorder.start(st.camLens)
                                            }
                                            try {
                                                LiveWsProtocol.writeFrame(
                                                    output, LiveWsProtocol.OP_TEXT,
                                                    buildJsonObject(
                                                        mapOf(
                                                            "event" to "record_status",
                                                            "recording" to true,
                                                            "lenses" to tripleRecorder.activeLenses
                                                        )
                                                    ).toByteArray(Charsets.UTF_8)
                                                )
                                            } catch (_: Exception) { }
                                        } else if (v == "off") {
                                            val result = tripleRecorder.stop()
                                            try {
                                                LiveWsProtocol.writeFrame(
                                                    output, LiveWsProtocol.OP_TEXT,
                                                    buildJsonObject(
                                                        mapOf(
                                                            "event" to "record_status",
                                                            "recording" to false,
                                                            "paths" to (result["paths"] ?: emptyMap<String, Any>())
                                                        )
                                                    ).toByteArray(Charsets.UTF_8)
                                                )
                                            } catch (_: Exception) { }
                                        }
                                    }
                                    "pip" -> {
                                        st.pipOn = obj["v"]?.jsonPrimitive?.content == "on"
                                        if (st.source == "screen") {
                                            if (st.pipOn) cameraController.startPreviewStream(
                                                androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                                            )
                                            else cameraController.stopPreviewStream()
                                        }
                                    }
                                    "audio" -> st.audioOn = obj["v"]?.jsonPrimitive?.content == "on"
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                st.running = false
            }
        }

        try {
            if (st.source == "cam") {
                cameraController.startPreviewStream(st.camLens)
            } else if (st.pipOn) {
                cameraController.startPreviewStream(st.camLens)
            }
            var lastCamSeq = -1L
            var lastPipSeq = -1L
            var lastFrameAt = 0L
            val minFrameGap = 60L // ~16 fps cap
            while (st.running) {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt >= minFrameGap) {
                    var sent = false
                    if (st.source == "cam") {
                        val seq = cameraController.previewSeq()
                        if (seq >= 0 && seq != lastCamSeq) {
                            val jpeg = cameraController.readLatestPreviewFrame()
                            if (jpeg != null) {
                                val channel = if (st.camLens == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) 0x03 else 0x02
                                if (LiveWsProtocol.writeFrame(output, LiveWsProtocol.OP_BINARY, framePayload(channel, jpeg))) {
                                    lastCamSeq = seq
                                    sent = true
                                } else break
                            }
                        }
                    } else {
                        val jpeg = screenCaptureController.captureFrame()
                        if (jpeg != null) {
                            if (!LiveWsProtocol.writeFrame(output, LiveWsProtocol.OP_BINARY, framePayload(0x01, jpeg))) break
                            sent = true
                        }
                        // Camera PiP while the main view is the screen.
                        if (st.pipOn) {
                            val seq = cameraController.previewSeq()
                            if (seq >= 0 && seq != lastPipSeq) {
                                val pipJpeg = cameraController.readLatestPreviewFrame()
                                if (pipJpeg != null) {
                                    val pipChannel = if (st.camLens == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) 0x03 else 0x02
                                    if (!LiveWsProtocol.writeFrame(output, LiveWsProtocol.OP_BINARY, framePayload(pipChannel, pipJpeg))) break
                                    lastPipSeq = seq
                                }
                            }
                        }
                    }
                    if (sent) lastFrameAt = System.currentTimeMillis()
                }
                if (st.audioOn && micChannel != null) {
                    // Drain every buffered chunk so audio never lags behind
                    // the video (the loop blocks during screen captures).
                    var chunk = micChannel.tryReceive().getOrNull()
                    while (chunk != null) {
                        if (!LiveWsProtocol.writeFrame(output, LiveWsProtocol.OP_BINARY, framePayload(0x04, chunk))) {
                            st.running = false
                            break
                        }
                        chunk = micChannel.tryReceive().getOrNull()
                    }
                }
                kotlinx.coroutines.delay(30)
            }
        } finally {
            st.running = false
            readerJob.cancel()
            cameraController.stopPreviewStream()
            if (micChannel != null) {
                try { micController.stopLiveStream(micChannel) } catch (_: Exception) { }
            }
        }
    }

    /** [1 byte channel][4 byte big-endian length][payload] */
    private fun framePayload(channel: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = channel.toByte()
        out[1] = ((payload.size shr 24) and 0xFF).toByte()
        out[2] = ((payload.size shr 16) and 0xFF).toByte()
        out[3] = ((payload.size shr 8) and 0xFF).toByte()
        out[4] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 5, payload.size)
        return out
    }

    /**
     * Shared MJPEG writer: pushes JPEG frames as multipart parts until the
     * client disconnects (write throws), the frame source fails too many
     * times in a row, or the coroutine is cancelled. Frames are captured
     * under the process camera mutex where applicable (single camera).
     */
    private suspend fun writeMpegFrames(
        out: OutputStream,
        intervalMs: Long,
        frameSource: () -> ByteArray?
    ) {
        val boundary = "--frame"
        var consecutiveFailures = 0
        try {
            while (true) {
                val frame = try { frameSource() } catch (e: Exception) {
                    Log.w("ArtemisServer", "Stream frame error: ${e.message}")
                    null
                }
                if (frame == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 20) break // ~10s of dead source
                    delay(500)
                    continue
                }
                consecutiveFailures = 0
                out.write("$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(frame)
                out.write("\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
                delay(intervalMs)
            }
        } catch (e: Exception) {
            // Client disconnect or socket error — normal stream end.
            Log.i("ArtemisServer", "MJPEG stream ended: ${e.message}")
        }
    }

    // ============================================================
    // Files / assets (v2.3.0 helper)
    // ============================================================

    private fun filesRootsHandler(req: HttpRequest): HttpResponse {
        return jsonResponse(200, mapOf("roots" to fileSystemHelper.allowedRoots()))
    }

    private fun filesListHandler(req: HttpRequest): HttpResponse {
        val path = req.queryParams["path"]
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing path query parameter"))
        val entries = fileSystemHelper.listDirectory(path)
        if (entries == null) {
            return jsonResponse(403, mapOf(
                "error" to "forbidden",
                "message" to "Path is outside the allowed roots or not a readable directory"
            ))
        }
        val entriesJson = json.encodeToString(entries)
        val rootsJson = json.encodeToString(fileSystemHelper.allowedRoots())
        val body = """{"path":${toJsonValue(path)},"entries":$entriesJson,"count":${entries.size},"roots":$rootsJson}"""
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
    }

    private fun filesDownloadHandler(req: HttpRequest): HttpResponse {
        val path = req.queryParams["path"]
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing path query parameter"))
        val file = fileSystemHelper.resolveFile(path)
        if (file == null) {
            return jsonResponse(404, mapOf(
                "error" to "not_found",
                "message" to "File not found or outside the allowed roots"
            ))
        }
        return binaryFileResponse(file, mimeTypeFor(file.name))
    }

    private fun filesUploadHandler(req: HttpRequest): HttpResponse {
        val path = req.queryParams["path"]
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing path query parameter"))
        val body = parseJsonObject(req.body)
        val dataB64 = body?.get("data")
            ?: return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Missing base64 'data' field in JSON body"))
        val data = try {
            android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("error" to "bad_request", "message" to "Invalid base64 data"))
        }
        val result = fileSystemHelper.writeFile(path, data)
        if (result.isFailure) {
            return jsonResponse(403, mapOf(
                "error" to "forbidden",
                "message" to (result.exceptionOrNull()?.message ?: "Write failed")
            ))
        }
        val written = result.getOrThrow()
        return jsonResponse(200, mapOf(
            "status" to "ok",
            "path" to written.absolutePath,
            "size" to written.length()
        ))
    }

    /** Simple content-type guess for file downloads. */
    private fun mimeTypeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "amr" -> "audio/amr"
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "db" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }

    // ============================================================
    // Contacts (v2.3.0 helper)
    // ============================================================

    private fun contactsHandler(req: HttpRequest): HttpResponse {
        val limit = req.queryParams["limit"]?.toIntOrNull() ?: 500
        val contacts = contactsProvider.getContacts(limit.coerceIn(1, 2000))
        if (contacts == null) {
            return jsonResponse(403, mapOf(
                "error" to "permission_denied",
                "permission" to "READ_CONTACTS"
            ))
        }
        val contactsJson = json.encodeToString(contacts)
        val body = """{"contacts":$contactsJson,"count":${contacts.size}}"""
        return HttpResponse(200, "application/json", mapOf("Access-Control-Allow-Origin" to "*"), body)
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

    private suspend fun handleConnection(client: Socket) {
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

            // Live-stream response: write the head without a Content-Length,
            // then let the handler push frames until the client disconnects.
            if (response.streamBody != null) {
                sendStreamHead(output, response)
                response.streamBody!!.invoke(output)
                Log.i("ArtemisServer", "<- stream ended ${request.method} ${request.path}")
                return
            }

            // WebSocket session: the handler performs the RFC 6455 upgrade
            // on the raw socket and runs its own frame loops.
            if (response.wsHandler != null) {
                response.wsHandler!!.invoke(client)
                Log.i("ArtemisServer", "<- ws ended ${request.method} ${request.path}")
                return
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

    /** Response head for a live stream — no Content-Length, Connection: close. */
    private fun sendStreamHead(output: OutputStream, response: HttpResponse) {
        try {
            val statusText = when (response.statusCode) {
                200 -> "OK"
                401 -> "Unauthorized"
                404 -> "Not Found"
                else -> "Unknown"
            }
            val headerLines = StringBuilder()
            headerLines.append("HTTP/1.1 ${response.statusCode} $statusText\r\n")
            headerLines.append("Content-Type: ${response.contentType}\r\n")
            headerLines.append("Cache-Control: no-store\r\n")
            headerLines.append("Connection: close\r\n")
            headerLines.append("Access-Control-Allow-Origin: *\r\n")
            for ((key, value) in response.headers) {
                headerLines.append("$key: $value\r\n")
            }
            headerLines.append("Server: Artemis/2.4.2\r\n")
            headerLines.append("\r\n")
            output.write(headerLines.toString().toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {
            Log.e("ArtemisServer", "Error sending stream head: ${e.message}")
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
            headerLines.append("Server: Artemis/2.4.2\r\n")
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
