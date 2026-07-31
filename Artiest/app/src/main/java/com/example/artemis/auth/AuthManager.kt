package com.example.artemis.auth

import android.util.Base64
import android.util.Log
import com.example.artemis.ArtemisApp
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AuthToken(
    val deviceId: String,
    val clientId: String,
    val scope: Int,
    val issuedAt: Long,
    val expiresAt: Long
)

@Serializable
data class PairedClient(
    val clientId: String,
    val clientName: String,
    val permissionScope: Int,
    val pairedAt: Long,
    val lastSeen: Long? = null,
    val isActive: Boolean = true
)

@Serializable
data class PairingCode(
    val code: String,
    val expiresAt: Long
)

/**
 * Persisted record for a refresh token. Only the SHA-256 hash of the token
 * secret is stored — never the token itself. Records form a chain via
 * [replacedBy] when rotated, which is what makes replay detection possible.
 */
@Serializable
data class RefreshTokenRecord(
    val tokenHash: String,
    val clientId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val replacedBy: String? = null,
    val replacedAt: Long? = null,
    val revoked: Boolean = false
)

/**
 * Outcome of validating a refresh token.
 */
sealed class RefreshOutcome {
    /** Token is current and usable. [isLegacy] marks a pre-v1.5 stateless token. */
    data class Valid(val clientId: String, val isLegacy: Boolean = false) : RefreshOutcome()

    /**
     * Token was rotated recently (within the grace window). Treated as a
     * network-retry of the previous refresh response — caller may issue a
     * fresh pair again.
     */
    data class Superseded(val clientId: String) : RefreshOutcome()

    /**
     * A rotated token was reused after the grace window, or a revoked token
     * was presented. This is a replay / theft signal: the client family must
     * be revoked and re-pairing required.
     */
    object Replay : RefreshOutcome()

    /** Known token that has passed its 30-day lifetime. Registration ends. */
    object Expired : RefreshOutcome()

    /** Bad signature, malformed, or unknown token. */
    object Invalid : RefreshOutcome()
}

/**
 * Result of a full refresh attempt (validate + rotate atomically).
 */
sealed class RefreshSessionResult {
    /** Issued a fresh refresh token; caller must also issue a fresh access token. */
    data class Ok(val clientId: String, val newRefreshToken: String) : RefreshSessionResult()

    /** Refresh token reuse detected — the client family was revoked. */
    data class ReplayDetected(val clientId: String) : RefreshSessionResult()

    /** Refresh token lifetime ended — the client was unregistered. */
    data class Expired(val clientId: String) : RefreshSessionResult()

    /** Unknown/tampered/revoked token. */
    object Invalid : RefreshSessionResult()
}

class AuthManager(private val app: ArtemisApp) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val secureRandom = SecureRandom()
    private val mutex = Mutex()

    private val activeTokens = mutableMapOf<String, AuthToken>()
    // payload-b64 -> revokedAt (epoch ms); pruned to a bounded window
    private val revokedTokens = mutableMapOf<String, Long>()
    private val pairedClients = mutableMapOf<String, PairedClient>()
    // token-secret hash -> record
    private val refreshRecords = mutableMapOf<String, RefreshTokenRecord>()
    // Legacy (pre-v1.5) refresh secrets already migrated: hash -> migratedAt
    private val migratedRefreshTokens = mutableMapOf<String, Long>()
    private val stateLock = Any()

    init {
        loadPersistedState()
    }

    // ------------------------------------------------------------------
    // Persistence — paired clients, revocations and refresh-token records
    // survive server restarts. The HMAC key lives in AndroidKeyStore
    // (persists across restarts), so previously-issued access tokens keep
    // validating automatically.
    // ------------------------------------------------------------------

    private fun prefs() = app.encryptedPreferences

    private fun loadPersistedState() {
        try {
            val clientsJson = prefs().getString(KEY_PAIRED_CLIENTS, null)
            if (clientsJson != null) {
                val list = json.decodeFromString<List<PairedClient>>(clientsJson)
                list.forEach { pairedClients[it.clientId] = it }
            }
            val revokedJson = prefs().getString(KEY_REVOKED_TOKENS, null)
            if (revokedJson != null) {
                val now = currentTimeMillis()
                val parsed = json.parseToJsonElement(revokedJson)
                if (parsed is kotlinx.serialization.json.JsonArray) {
                    // Legacy format: bare list of revoked payloads
                    parsed.forEach { revokedTokens[(it as kotlinx.serialization.json.JsonPrimitive).content] = now }
                } else {
                    val map = json.decodeFromString<Map<String, Long>>(revokedJson)
                    revokedTokens.putAll(map)
                }
            }
            val refreshJson = prefs().getString(KEY_REFRESH_TOKENS, null)
            if (refreshJson != null) {
                val list = json.decodeFromString<List<RefreshTokenRecord>>(refreshJson)
                list.forEach { refreshRecords[it.tokenHash] = it }
            }
            val migratedJson = prefs().getString(KEY_MIGRATED_REFRESH, null)
            if (migratedJson != null) {
                migratedRefreshTokens.putAll(json.decodeFromString<Map<String, Long>>(migratedJson))
            }
        } catch (e: Exception) {
            // Corrupt store — start fresh rather than crash
            Log.w("AuthManager", "Persisted state unreadable, starting fresh: ${e.message}")
        }
    }

    private fun persistPairedClients() {
        try {
            val list = pairedClients.values.toList()
            prefs().edit().putString(KEY_PAIRED_CLIENTS, json.encodeToString(list)).apply()
        } catch (_: Exception) { }
    }

    private fun persistRevokedTokens() {
        try {
            prefs().edit().putString(KEY_REVOKED_TOKENS, json.encodeToString(revokedTokens)).apply()
        } catch (_: Exception) { }
    }

    private fun persistRefreshRecords() {
        try {
            val list = refreshRecords.values.toList()
            prefs().edit().putString(KEY_REFRESH_TOKENS, json.encodeToString(list)).apply()
        } catch (_: Exception) { }
    }

    private fun persistMigrated() {
        try {
            prefs().edit().putString(KEY_MIGRATED_REFRESH, json.encodeToString(migratedRefreshTokens)).apply()
        } catch (_: Exception) { }
    }

    fun rememberPairedClient(client: PairedClient) {
        synchronized(stateLock) {
            pairedClients[client.clientId] = client
            persistPairedClients()
        }
    }

    fun getPairedClients(): List<PairedClient> {
        synchronized(stateLock) {
            return pairedClients.values.sortedByDescending { it.pairedAt }
        }
    }

    /** Update lastSeen in memory immediately; persist at most once per minute. */
    private var lastLastSeenPersist = 0L

    fun touchClient(clientId: String) {
        val now = currentTimeMillis()
        synchronized(stateLock) {
            val client = pairedClients[clientId] ?: return
            pairedClients[clientId] = client.copy(lastSeen = now)
            if (now - lastLastSeenPersist > LAST_SEEN_PERSIST_INTERVAL_MS) {
                lastLastSeenPersist = now
                persistPairedClients()
            }
        }
    }

    /**
     * Revoke a client: mark inactive, revoke every refresh token it holds,
     * and drop any cached access token. Stateless access tokens already
     * issued are rejected at validation time via the isActive check.
     */
    fun revokeClient(clientId: String): Boolean {
        synchronized(stateLock) {
            val client = pairedClients[clientId] ?: return false
            pairedClients[clientId] = client.copy(isActive = false)
            revokeRefreshTokensOf(clientId)
            activeTokens.remove(clientId)
            persistPairedClients()
            return true
        }
    }

    fun unrevokeClient(clientId: String): Boolean {
        synchronized(stateLock) {
            val client = pairedClients[clientId] ?: return false
            pairedClients[clientId] = client.copy(isActive = true)
            persistPairedClients()
            return true
        }
    }

    /**
     * Permanently remove a client (used when a refresh token expires or a
     * security violation is detected). The dashboard must re-pair.
     */
    fun unregisterClient(clientId: String): Boolean {
        synchronized(stateLock) {
            val existed = pairedClients.remove(clientId) != null
            refreshRecords.entries.removeAll { (_, rec) -> rec.clientId == clientId }
            activeTokens.remove(clientId)
            if (existed) {
                persistPairedClients()
                persistRefreshRecords()
            }
            return existed
        }
    }

    /** Revoke every paired dashboard (phone-side "revoke all"). */
    fun revokeAllClients() {
        synchronized(stateLock) {
            pairedClients.replaceAll { _, c -> c.copy(isActive = false) }
            revokeRefreshTokensOf(null)
            activeTokens.clear()
            persistPairedClients()
        }
    }

    /** Mark every (optionally client-scoped) refresh record revoked. */
    private fun revokeRefreshTokensOf(clientId: String?) {
        val targets = refreshRecords.values
            .filter { clientId == null || it.clientId == clientId }
            .map { it.tokenHash }
        targets.forEach { hash ->
            val rec = refreshRecords[hash] ?: return@forEach
            refreshRecords[hash] = rec.copy(revoked = true)
        }
        persistRefreshRecords()
    }

    companion object {
        /** Access token lifetime: 1 hour. */
        const val TOKEN_EXPIRY_MS = 60 * 60 * 1000L
        /** Refresh token lifetime: 30 days. */
        const val REFRESH_EXPIRY_MS = 30 * 24 * 60 * 60 * 1000L
        /** Pairing code rotation window: 5 minutes. */
        const val PAIRING_CODE_EXPIRY_MS = 5 * 60 * 1000L
        /**
         * Grace window for a rotated refresh token. A token re-presented
         * within this window is assumed to be a network retry of the last
         * refresh response (the response was lost), not theft. Reuse after
         * the window is treated as replay and revokes the client family.
         */
        const val REFRESH_GRACE_MS = 60 * 1000L

        const val SCOPE_ADMIN = 0xFFFF
        const val SCOPE_LOCATION = 1 shl 0
        const val SCOPE_CAMERA = 1 shl 1
        const val SCOPE_MICROPHONE = 1 shl 2
        const val SCOPE_CONTACTS = 1 shl 3
        const val SCOPE_SMS = 1 shl 4
        const val SCOPE_DEVICE_INFO = 1 shl 5
        const val SCOPE_SCREEN = 1 shl 6
        const val SCOPE_FILE = 1 shl 7

        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val TOKEN_VERSION = "AT1"
        private const val KEY_PAIRED_CLIENTS = "paired_clients"
        private const val KEY_REVOKED_TOKENS = "revoked_tokens"
        private const val KEY_REFRESH_TOKENS = "refresh_tokens"
        private const val KEY_MIGRATED_REFRESH = "migrated_refresh_tokens"
        private const val LAST_SEEN_PERSIST_INTERVAL_MS = 60 * 1000L
        private const val REVOKED_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
    }

    // ------------------------------------------------------------------
    // Pairing codes
    // ------------------------------------------------------------------

    /**
     * Generate a fresh 6-digit code using SecureRandom (unbiased). The value
     * must never be logged or returned by the API — only shown on the phone
     * screen.
     */
    fun generatePairingCode(): PairingCode {
        val code = String.format("%06d", secureRandom.nextInt(1_000_000))
        val expiresAt = currentTimeMillis() + PAIRING_CODE_EXPIRY_MS
        return PairingCode(code, expiresAt)
    }

    // ------------------------------------------------------------------
    // Access tokens (stateless, HMAC-signed)
    // ------------------------------------------------------------------

    suspend fun createToken(token: AuthToken): String = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(token)
        val payloadB64 = b64url(payload.toByteArray())
        val signature = sign(payloadB64)
        "$TOKEN_VERSION.$payloadB64.$signature"
    }

    suspend fun validateToken(tokenString: String): Result<AuthToken> = withContext(Dispatchers.IO) {
        try {
            val parts = tokenString.split(".")
            if (parts.size != 3 || parts[0] != TOKEN_VERSION) {
                return@withContext Result.failure(AuthException("Invalid token format"))
            }

            synchronized(stateLock) {
                revokedTokens[parts[1]]?.let {
                    return@withContext Result.failure(AuthException("Token has been revoked"))
                }
            }

            val expectedSig = sign(parts[1])
            if (!constantTimeEquals(expectedSig, parts[2])) {
                return@withContext Result.failure(AuthException("Invalid token signature"))
            }

            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING)
            val token = json.decodeFromString<AuthToken>(String(payloadBytes))

            if (token.expiresAt < currentTimeMillis()) {
                return@withContext Result.failure(AuthException("Token has expired"))
            }

            // The client must still be registered and active — revocation is
            // enforced here, not just at issue time.
            val client = synchronized(stateLock) { pairedClients[token.clientId] }
            if (client == null) {
                return@withContext Result.failure(AuthException("Client is not registered"))
            }
            if (!client.isActive) {
                return@withContext Result.failure(AuthException("Client has been revoked"))
            }

            synchronized(stateLock) { activeTokens[token.clientId] = token }
            touchClient(token.clientId)
            Result.success(token)
        } catch (e: AuthException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(AuthException("Token validation failed"))
        }
    }

    suspend fun revokeToken(tokenString: String) {
        mutex.withLock {
            val parts = tokenString.split(".")
            if (parts.size == 3) {
                synchronized(stateLock) {
                    revokedTokens[parts[1]] = currentTimeMillis()
                    persistRevokedTokens()
                    try {
                        val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING)
                        val token = json.decodeFromString<AuthToken>(String(payloadBytes))
                        activeTokens.remove(token.clientId)
                    } catch (_: Exception) { }
                }
            }
        }
    }

    fun hasScope(token: AuthToken, scope: Int): Boolean {
        return (token.scope and scope) == scope
    }

    suspend fun createAdminToken(deviceId: String): String {
        val now = currentTimeMillis()
        val token = AuthToken(
            deviceId = deviceId,
            clientId = "admin",
            scope = SCOPE_ADMIN,
            issuedAt = now,
            expiresAt = now + TOKEN_EXPIRY_MS
        )
        return createToken(token)
    }

    suspend fun issueToken(deviceId: String, clientId: String, scope: Int): String {
        val now = currentTimeMillis()
        val token = AuthToken(
            deviceId = deviceId,
            clientId = clientId,
            scope = scope,
            issuedAt = now,
            expiresAt = now + TOKEN_EXPIRY_MS
        )
        return createToken(token)
    }

    // ------------------------------------------------------------------
    // Refresh tokens (stateful: rotation + replay detection)
    // ------------------------------------------------------------------

    /**
     * Full refresh flow, atomic under the auth mutex so two concurrent
     * refreshes with the same token cannot fork the token family.
     */
    suspend fun refreshSession(oldToken: String): RefreshSessionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val outcome = validateRefreshTokenLocked(oldToken)
            when (outcome) {
                is RefreshOutcome.Valid -> {
                    val newRefresh = createRefreshTokenLocked(outcome.clientId)
                    markSupersededLocked(oldToken, newRefresh, outcome.isLegacy)
                    RefreshSessionResult.Ok(outcome.clientId, newRefresh)
                }
                is RefreshOutcome.Superseded -> {
                    // Grace-window retry: rotate again from the presented token.
                    val newRefresh = createRefreshTokenLocked(outcome.clientId)
                    markSupersededLocked(oldToken, newRefresh, isLegacy = false)
                    RefreshSessionResult.Ok(outcome.clientId, newRefresh)
                }
                is RefreshOutcome.Replay -> {
                    val clientId = clientIdForToken(oldToken)
                    if (clientId != null) {
                        revokeClient(clientId)
                        Log.w("AuthManager", "REFRESH REPLAY DETECTED for client $clientId — revoked, re-pair required")
                    }
                    RefreshSessionResult.ReplayDetected(clientId ?: "")
                }
                is RefreshOutcome.Expired -> {
                    val clientId = clientIdForToken(oldToken)
                    if (clientId != null) {
                        unregisterClient(clientId)
                        Log.i("AuthManager", "Refresh token expired for client $clientId — unregistered")
                    }
                    RefreshSessionResult.Expired(clientId ?: "")
                }
                RefreshOutcome.Invalid -> RefreshSessionResult.Invalid
            }
        }
    }

    /** Read-only validation (no rotation side effects). */
    suspend fun validateRefreshToken(tokenString: String): RefreshOutcome = withContext(Dispatchers.IO) {
        validateRefreshTokenLocked(tokenString)
    }

    private fun validateRefreshTokenLocked(tokenString: String): RefreshOutcome {
        val parts = tokenString.split(".")
        if (parts.size != 4 || parts[0] != TOKEN_VERSION || parts[1] != "RF") {
            return RefreshOutcome.Invalid
        }
        val secret = parts[2]
        val expectedSig = sign(secret)
        if (!constantTimeEquals(expectedSig, parts[3])) {
            return RefreshOutcome.Invalid
        }

        val hash = sha256(secret)
        return synchronized(stateLock) {
            refreshRecords[hash]?.let { record ->
                if (record.revoked) return@synchronized RefreshOutcome.Invalid
                if (currentTimeMillis() > record.expiresAt) return@synchronized RefreshOutcome.Expired
                if (record.replacedBy != null) {
                    val replacedAt = record.replacedAt ?: record.issuedAt
                    if (currentTimeMillis() - replacedAt <= REFRESH_GRACE_MS) {
                        return@synchronized RefreshOutcome.Superseded(record.clientId)
                    }
                    return@synchronized RefreshOutcome.Replay
                }
                return@synchronized RefreshOutcome.Valid(record.clientId)
            }
            // Not in the stateful store.
            migratedRefreshTokens[hash]?.let { migratedAt ->
                // Legacy token that already migrated to the stateful scheme.
                if (currentTimeMillis() - migratedAt <= REFRESH_GRACE_MS) {
                    val cid = legacyClientIdOf(parts[2])
                    if (cid != null) return@synchronized RefreshOutcome.Superseded(cid)
                }
                return@synchronized RefreshOutcome.Replay
            }
            // Unknown or legacy-not-yet-migrated.
            validateLegacyRefreshToken(parts, hash)
        }
    }

    /**
     * Legacy pre-v1.5 refresh tokens: AT1.RF.<b64("clientId:issuedAt:rand")>.sig
     * First use migrates them: records the hash so reuse becomes detectable.
     */
    private fun validateLegacyRefreshToken(parts: List<String>, hash: String): RefreshOutcome {
        return try {
            val raw = String(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING))
            val clientId = raw.substringBefore(":")
            val issuedAt = raw.substringAfter(":").substringBefore(":").toLongOrNull() ?: 0L
            val client = synchronized(stateLock) { pairedClients[clientId] }
            if (client == null || !client.isActive) return RefreshOutcome.Invalid
            val age = currentTimeMillis() - issuedAt
            if (age < 0 || age > REFRESH_EXPIRY_MS) return RefreshOutcome.Expired
            // First successful use migrates the legacy token into the
            // replay-detection set.
            synchronized(stateLock) {
                migratedRefreshTokens[hash] = currentTimeMillis()
                persistMigrated()
            }
            RefreshOutcome.Valid(clientId, isLegacy = true)
        } catch (_: Exception) {
            RefreshOutcome.Invalid
        }
    }

    private fun legacyClientIdOf(secretB64: String): String? {
        return try {
            val raw = String(Base64.decode(secretB64, Base64.URL_SAFE or Base64.NO_PADDING))
            raw.substringBefore(":")
        } catch (_: Exception) {
            null
        }
    }

    private fun clientIdForToken(tokenString: String): String? {
        val parts = tokenString.split(".")
        if (parts.size != 4) return null
        val hash = sha256(parts[2])
        synchronized(stateLock) {
            refreshRecords[hash]?.let { return it.clientId }
            migratedRefreshTokens[hash]?.let {
                return legacyClientIdOf(parts[2])
            }
        }
        return legacyClientIdOf(parts[2])
    }

    /**
     * Issue a new refresh token for a client. The token is a 256-bit random
     * secret (base64url) wrapped in the signed envelope; the phone stores
     * only its SHA-256 hash.
     */
    suspend fun createRefreshToken(clientId: String): String = withContext(Dispatchers.IO) {
        createRefreshTokenLocked(clientId)
    }

    private fun createRefreshTokenLocked(clientId: String): String {
        val secretBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val secret = b64url(secretBytes)
        val token = "$TOKEN_VERSION.RF.$secret.${sign(secret)}"
        val now = currentTimeMillis()
        val record = RefreshTokenRecord(
            tokenHash = sha256(secret),
            clientId = clientId,
            issuedAt = now,
            expiresAt = now + REFRESH_EXPIRY_MS
        )
        synchronized(stateLock) {
            refreshRecords[record.tokenHash] = record
            persistRefreshRecords()
        }
        return token
    }

    private fun markSupersededLocked(oldToken: String, newToken: String, isLegacy: Boolean) {
        val parts = oldToken.split(".")
        if (parts.size != 4) return
        val oldHash = sha256(parts[2])
        val newHash = sha256(newToken.split(".")[2])
        synchronized(stateLock) {
            if (isLegacy) {
                // Legacy token: no record to mark; its hash in the migrated
                // set now points at the new family head.
                migratedRefreshTokens[oldHash] = currentTimeMillis()
                persistMigrated()
                return
            }
            val record = refreshRecords[oldHash] ?: return
            refreshRecords[oldHash] = record.copy(
                replacedBy = newHash,
                replacedAt = currentTimeMillis()
            )
            persistRefreshRecords()
        }
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    suspend fun purgeExpiredTokens() {
        mutex.withLock {
            val now = currentTimeMillis()
            synchronized(stateLock) {
                activeTokens.entries.removeAll { it.value.expiresAt < now }
                // Prune revocation entries older than the retention window
                // (keeps the set bounded without dropping recent revocations).
                val before = revokedTokens.size
                revokedTokens.entries.removeAll { now - it.value > REVOKED_RETENTION_MS }
                if (revokedTokens.size != before) persistRevokedTokens()
                // Drop records that are expired (they can no longer grant
                // anything) or revoked and past retention.
                val beforeR = refreshRecords.size
                refreshRecords.entries.removeAll { (_, rec) ->
                    (now > rec.expiresAt) ||
                        (rec.revoked && now - rec.issuedAt > REVOKED_RETENTION_MS)
                }
                if (refreshRecords.size != beforeR) persistRefreshRecords()
                val beforeM = migratedRefreshTokens.size
                migratedRefreshTokens.entries.removeAll { now - it.value > REVOKED_RETENTION_MS }
                if (migratedRefreshTokens.size != beforeM) persistMigrated()
            }
        }
    }

    private fun sign(data: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(app.secretKey as SecretKey)
        val sigBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return b64url(sigBytes)
    }

    private fun b64url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun sha256(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
        return b64url(digest)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun currentTimeMillis(): Long = System.currentTimeMillis()

    class AuthException(message: String) : Exception(message)
}
