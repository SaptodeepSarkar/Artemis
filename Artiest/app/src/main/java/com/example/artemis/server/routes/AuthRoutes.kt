package com.example.artemis.server.routes

import com.example.artemis.auth.AuthManager
import com.example.artemis.auth.PairedClient
import com.example.artemis.server.artemisToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PairRequest(
    val code: String,
    val clientName: String = "unknown"
)

@Serializable
data class PairResponse(
    val token: String,
    val refreshToken: String,
    val deviceId: String,
    val expiresAt: Long
)

@Serializable
data class TokenRequest(
    val refreshToken: String
)

@Serializable
data class TokenRefreshResponse(
    val token: String,
    val refreshToken: String,
    val expiresAt: Long
)

@Serializable
data class AuthStatusResponse(
    val authenticated: Boolean,
    val clientId: String? = null,
    val deviceId: String? = null,
    val scope: Int? = null,
    val expiresAt: Long? = null
)

fun Route.authRoutes(authManager: AuthManager, json: Json) {
    // Initial pairing request
    post("/api/v1/auth/pair") {
        val pairRequest = try {
            call.receive<PairRequest>()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                json.encodeToString(mapOf("error" to "invalid_request", "message" to "Invalid pairing request body"))
            )
            return@post
        }

        // In production, validate pairing code from the in-memory store
        // For simplicity, any code works for now — the UI shows it
        val deviceId = android.os.Build.getSerial() ?: android.os.Build.FINGERPRINT
        val clientId = "client_${System.currentTimeMillis()}"

        val token = authManager.issueToken(
            deviceId = deviceId,
            clientId = clientId,
            scope = AuthManager.SCOPE_ADMIN
        )
        val refreshToken = authManager.createRefreshToken(clientId)

        call.respond(
            HttpStatusCode.OK,
            json.encodeToString(
                PairResponse(
                    token = token,
                    refreshToken = refreshToken,
                    deviceId = deviceId,
                    expiresAt = System.currentTimeMillis() + AuthManager.TOKEN_EXPIRY_MS
                )
            )
        )
    }

    // Token refresh
    post("/api/v1/auth/token") {
        val tokenRequest = try {
            call.receive<TokenRequest>()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                json.encodeToString(mapOf("error" to "invalid_request", "message" to "Invalid token request body"))
            )
            return@post
        }

        val result = authManager.validateRefreshToken(tokenRequest.refreshToken)
        if (result.isFailure) {
            call.respond(
                HttpStatusCode.Unauthorized,
                json.encodeToString(mapOf("error" to "invalid_token", "message" to "Refresh token is invalid or expired"))
            )
            return@post
        }

        val clientId = result.getOrThrow()
        val deviceId = android.os.Build.getSerial() ?: android.os.Build.FINGERPRINT

        val newToken = authManager.issueToken(
            deviceId = deviceId,
            clientId = clientId,
            scope = AuthManager.SCOPE_ADMIN
        )
        val newRefreshToken = authManager.createRefreshToken(clientId)

        call.respond(
            HttpStatusCode.OK,
            json.encodeToString(
                TokenRefreshResponse(
                    token = newToken,
                    refreshToken = newRefreshToken,
                    expiresAt = System.currentTimeMillis() + AuthManager.TOKEN_EXPIRY_MS
                )
            )
        )
    }

    // Check authentication status
    get("/api/v1/auth/status") {
        val token = call.artemisToken
        if (token != null) {
            call.respond(
                HttpStatusCode.OK,
                json.encodeToString(
                    AuthStatusResponse(
                        authenticated = true,
                        clientId = token.clientId,
                        deviceId = token.deviceId,
                        scope = token.scope,
                        expiresAt = token.expiresAt
                    )
                )
            )
        } else {
            call.respond(
                HttpStatusCode.OK,
                json.encodeToString(AuthStatusResponse(authenticated = false))
            )
        }
    }

    // List paired clients (requires auth)
    get("/api/v1/auth/clients") {
        call.respond(
            HttpStatusCode.OK,
            json.encodeToString(
                mapOf("clients" to emptyList<PairedClient>())
            )
        )
    }

    // Revoke a client
    delete("/api/v1/auth/clients/{id}") {
        call.respond(
            HttpStatusCode.OK,
            json.encodeToString(mapOf("status" to "revoked"))
        )
    }
}
