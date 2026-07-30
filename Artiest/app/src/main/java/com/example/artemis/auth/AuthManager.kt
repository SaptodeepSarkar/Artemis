package com.example.artemis.auth

import android.util.Base64
import com.example.artemis.ArtemisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey

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

class AuthManager(private val app: ArtemisApp) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val secureRandom = SecureRandom()
    private val mutex = Mutex()

    private val activeTokens = mutableMapOf<String, AuthToken>()
    private val revokedTokens = mutableSetOf<String>()

    companion object {
        const val TOKEN_EXPIRY_MS = 24 * 60 * 60 * 1000L
        const val REFRESH_EXPIRY_MS = 30 * 24 * 60 * 60 * 1000L
        const val PAIRING_CODE_EXPIRY_MS = 5 * 60 * 1000L
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
    }

    fun generatePairingCode(): PairingCode {
        val code = String.format("%06d", secureRandom.nextInt(1_000_000))
        val expiresAt = currentTimeMillis() + PAIRING_CODE_EXPIRY_MS
        return PairingCode(code, expiresAt)
    }

    suspend fun createToken(token: AuthToken): String = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(token)
        val payloadB64 = Base64.encodeToString(
            payload.toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val signature = sign(payloadB64)
        "$TOKEN_VERSION.$payloadB64.$signature"
    }

    suspend fun validateToken(tokenString: String): Result<AuthToken> = withContext(Dispatchers.IO) {
        try {
            val parts = tokenString.split(".")
            if (parts.size != 3 || parts[0] != TOKEN_VERSION) {
                return@withContext Result.failure(AuthException("Invalid token format"))
            }

            if (parts[1] in revokedTokens) {
                return@withContext Result.failure(AuthException("Token has been revoked"))
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

            activeTokens[token.clientId] = token
            Result.success(token)
        } catch (e: Exception) {
            Result.failure(AuthException("Token validation failed: ${e.message}"))
        }
    }

    suspend fun createRefreshToken(clientId: String): String = withContext(Dispatchers.IO) {
        val data = "$clientId:${currentTimeMillis()}:${secureRandom.nextLong()}"
        val raw = data.toByteArray()
        val b64 = Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        "$TOKEN_VERSION.RF.$b64.${sign(b64)}"
    }

    suspend fun validateRefreshToken(tokenString: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val parts = tokenString.split(".")
            if (parts.size != 4 || parts[0] != TOKEN_VERSION || parts[1] != "RF") {
                return@withContext Result.failure(AuthException("Invalid refresh token format"))
            }

            val expectedSig = sign(parts[2])
            if (!constantTimeEquals(expectedSig, parts[3])) {
                return@withContext Result.failure(AuthException("Invalid refresh token signature"))
            }

            val raw = Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING)
            val decoded = String(raw)
            val clientId = decoded.substringBefore(":")
            Result.success(clientId)
        } catch (e: Exception) {
            Result.failure(AuthException("Refresh token validation failed: ${e.message}"))
        }
    }

    suspend fun revokeToken(tokenString: String) {
        mutex.withLock {
            val parts = tokenString.split(".")
            if (parts.size == 3) {
                revokedTokens.add(parts[1])
                try {
                    val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING)
                    val token = json.decodeFromString<AuthToken>(String(payloadBytes))
                    activeTokens.remove(token.clientId)
                } catch (_: Exception) { }
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

    private fun sign(data: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(app.secretKey as SecretKey)
        val sigBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sigBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    suspend fun purgeExpiredTokens() {
        mutex.withLock {
            val now = currentTimeMillis()
            activeTokens.entries.removeAll { it.value.expiresAt < now }
            if (revokedTokens.size > 1000) {
                revokedTokens.clear()
            }
        }
    }

    private fun currentTimeMillis(): Long = System.currentTimeMillis()

    class AuthException(message: String) : Exception(message)
}
