package com.example.artemis.server

import com.example.artemis.auth.AuthManager
import com.example.artemis.auth.AuthToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AuthErrorResponse(
    val error: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Custom Ktor AuthenticationProvider that validates Artemis HMAC-signed tokens.
 * Tokens can be provided via:
 *   - Authorization: Bearer *** header (REST endpoints)
 *   - token query parameter (WebSocket connections)
 */
class ArtemisAuthenticationProvider(
    private val authManager: AuthManager,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : AuthenticationProvider(Configuration("Artemis")) {

    class Configuration(name: String?) : AuthenticationProvider.Config(name)

    var extractor: (ApplicationCall) -> String? = { call ->
        // Try Authorization header first
        val authHeader = call.request.headers[HttpHeaders.Authorization]
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authHeader.removePrefix("Bearer ")
        } else {
            // Fall back to token query parameter (for WebSocket)
            call.request.queryParameters["token"]
        }
    }

    class ArtemisAuthResult(val token: AuthToken) : Principal

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val tokenString = extractor(call)
        if (tokenString == null) {
            context.challenge("Artemis", AuthenticationFailedCause.InvalidCredentials) { challenge, challengeCall ->
                challengeCall.respond(
                    HttpStatusCode.Unauthorized,
                    json.encodeToString(
                        AuthErrorResponse(
                            error = "unauthorized",
                            message = "Missing or invalid authentication token"
                        )
                    )
                )
            }
            return
        }

        val result = authManager.validateToken(tokenString)
        if (result.isFailure) {
            context.challenge("Artemis", AuthenticationFailedCause.InvalidCredentials) { challenge, challengeCall ->
                challengeCall.respond(
                    HttpStatusCode.Unauthorized,
                    json.encodeToString(
                        AuthErrorResponse(
                            error = "unauthorized",
                            message = "Missing or invalid authentication token"
                        )
                    )
                )
            }
            return
        }

        context.principal(ArtemisAuthResult(result.getOrThrow()))
    }
}

/** Convenience install function for Ktor */
fun AuthenticationConfig.configureArtemisAuth(
    authManager: AuthManager,
    json: Json = Json { ignoreUnknownKeys = true }
) {
    register(ArtemisAuthenticationProvider(authManager, json))
}

/** Extension to get the authenticated token from the call */
val ApplicationCall.artemisToken: AuthToken?
    get() = authentication.principal<ArtemisAuthenticationProvider.ArtemisAuthResult>()?.token

/** Scoped routing check */
suspend fun ApplicationCall.requireScope(authManager: AuthManager, scope: Int): Boolean {
    val token = artemisToken ?: return false
    return authManager.hasScope(token, scope)
}
