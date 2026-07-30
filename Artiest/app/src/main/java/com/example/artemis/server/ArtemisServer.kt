package com.example.artemis.server

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.artemis.ArtemisApp
import com.example.artemis.auth.AuthManager
import com.example.artemis.feature.CameraController
import com.example.artemis.feature.DeviceInfoProvider
import com.example.artemis.feature.LocationTracker
import com.example.artemis.feature.MicController
import com.example.artemis.server.routes.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.*
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

data class ErrorResponse(
    val error: String,
    val message: String,
    val statusCode: Int,
    val timestamp: Long = System.currentTimeMillis()
)

class ArtemisServer(
    private val app: ArtemisApp,
    private val authManager: AuthManager,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val locationTracker: LocationTracker,
    private val cameraController: CameraController,
    private val micController: MicController,
    private val port: Int = DEFAULT_PORT,
    private val useTls: Boolean = true
) {
    private var server: ApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        // Tell Netty to avoid broken NIO selector reflection on Android
        System.setProperty("io.netty.noKeySetOptimization", "true")
        System.setProperty("io.netty.noUnsafe", "true")
        android.util.Log.i("ArtemisServer", "Netty properties set: noKeySetOptimization=true, noUnsafe=true")
    }

    @Volatile
    var activeConnections: Int = 0
    var startTime: Long = 0L

    companion object {
        const val DEFAULT_PORT = 8443
        private const val KS_PASSWORD = "artemis_ks"
        private const val KEY_ALIAS = "artemis_tls"

        fun createSslContext(app: ArtemisApp): SSLContext {
            val ksFile = File(app.filesDir, "artemis_tls.bks")

            val keyStore: KeyStore
            if (!ksFile.exists()) {
                // Generate a new self-signed certificate
                keyStore = generateSelfSignedCert(app)
                keyStore.store(FileOutputStream(ksFile), KS_PASSWORD.toCharArray())
            } else {
                keyStore = KeyStore.getInstance("BKS")
                keyStore.load(FileInputStream(ksFile), KS_PASSWORD.toCharArray())
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, KS_PASSWORD.toCharArray())

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore as? KeyStore ?: KeyStore.getInstance("BKS").apply { load(null, null) })

            val sslContext = SSLContext.getInstance("TLSv1.3")
            sslContext.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
            return sslContext
        }

        private fun generateSelfSignedCert(app: ArtemisApp): KeyStore {
            val keyPair = generateKeyPair(app)
            val cert = generateCertificate(keyPair)

            val keyStore = KeyStore.getInstance("BKS")
            keyStore.load(null, KS_PASSWORD.toCharArray())
            keyStore.setKeyEntry(
                KEY_ALIAS,
                keyPair.private,
                KS_PASSWORD.toCharArray(),
                arrayOf(cert)
            )
            return keyStore
        }

        private fun generateKeyPair(app: ArtemisApp): KeyPair {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(
                    java.security.spec.ECGenParameterSpec("secp256r1")
                )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            keyPairGenerator.initialize(spec)
            return keyPairGenerator.generateKeyPair()
        }

        private fun generateCertificate(keyPair: KeyPair): X509Certificate {
            val issuerName = "CN=Artemis Sentinel, O=Artemis, C=US"
            val serial = BigInteger.valueOf(System.currentTimeMillis())
            val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
            val notAfter = Date(System.currentTimeMillis() + 157_680_000_000L) // ~5 years

            try {
                // Using BouncyCastle (bundled with Android)
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                val certGenClass = Class.forName("org.bouncycastle.x509.X509V1CertificateGenerator")
                val certGen = certGenClass.getDeclaredConstructor().newInstance()
                val x500NameClass = Class.forName("org.bouncycastle.jce.X509Principal")
                val x500Name = x500NameClass.getDeclaredConstructor(String::class.java).newInstance(issuerName)

                certGenClass.getMethod("setSerialNumber", BigInteger::class.java).invoke(certGen, serial)
                certGenClass.getMethod("setIssuerDN", x500NameClass).invoke(certGen, x500Name)
                certGenClass.getMethod("setNotBefore", Date::class.java).invoke(certGen, notBefore)
                certGenClass.getMethod("setNotAfter", Date::class.java).invoke(certGen, notAfter)
                certGenClass.getMethod("setSubjectDN", x500NameClass).invoke(certGen, x500Name)
                certGenClass.getMethod("setPublicKey", PublicKey::class.java).invoke(certGen, keyPair.public)
                certGenClass.getMethod("setSignatureAlgorithm", String::class.java).invoke(certGen, "SHA256withECDSA")

                val cert = certGenClass.getMethod("generate", PrivateKey::class.java, String::class.java)
                    .invoke(certGen, keyPair.private, "BC") as X509Certificate
                return cert
            } catch (e: Exception) {
                throw RuntimeException("Failed to generate self-signed certificate: ${e.message}", e)
            }
        }
    }
    suspend fun start() {
        if (server != null) {
            android.util.Log.i("ArtemisServer", "start() called but server already running")
            return
        }
        android.util.Log.i("ArtemisServer", "Starting Ktor server on port $port...")

        startTime = System.currentTimeMillis()

        try {
            server = embeddedServer(
                factory = Netty,
                port = port,
                host = "0.0.0.0",
                module = {
                    android.util.Log.i("ArtemisServer", "Module configuration starting...")
                    install(ContentNegotiation) {
                        json(json)
                    }

                    android.util.Log.i("ArtemisServer", "Configuring CORS...")
                    install(CORS) {
                        allowMethod(HttpMethod.Options)
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Put)
                        allowMethod(HttpMethod.Delete)
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                        allowHeader("X-Requested-With")
                        anyHost()
                    }

                    android.util.Log.i("ArtemisServer", "Configuring StatusPages...")
                    install(StatusPages) {
                        exception<Throwable> { call, cause ->
                            android.util.Log.e("ArtemisServer", "Unhandled exception", cause)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                json.encodeToString(
                                    ErrorResponse(
                                        error = "internal_error",
                                        message = cause.message ?: "An unexpected error occurred",
                                        statusCode = HttpStatusCode.InternalServerError.value
                                    )
                                )
                            )
                        }
                        status(HttpStatusCode.NotFound) { call, _ ->
                            call.respond(
                                HttpStatusCode.NotFound,
                                json.encodeToString(
                                    ErrorResponse(
                                        error = "not_found",
                                        message = "The requested resource was not found",
                                        statusCode = HttpStatusCode.NotFound.value
                                    )
                                )
                            )
                        }
                        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
                            call.respond(
                                HttpStatusCode.MethodNotAllowed,
                                json.encodeToString(
                                    ErrorResponse(
                                        error = "method_not_allowed",
                                        message = "This HTTP method is not supported for this endpoint",
                                        statusCode = HttpStatusCode.MethodNotAllowed.value
                                    )
                                )
                            )
                        }
                    }

                    android.util.Log.i("ArtemisServer", "Configuring WebSockets...")
                    install(WebSockets)

                    android.util.Log.i("ArtemisServer", "Configuring Authentication...")
                    install(Authentication) {
                        configureArtemisAuth(authManager, json)
                    }

                    android.util.Log.i("ArtemisServer", "Configuring routes...")
                    routing {
                        android.util.Log.i("ArtemisServer", "Setting up health routes...")
                        healthRoutes(this@ArtemisServer)

                        android.util.Log.i("ArtemisServer", "Setting up authenticated routes...")
                        authenticate("Artemis") {
                            authRoutes(authManager, json)
                            deviceRoutes(deviceInfoProvider, json)
                            locationRoutes(locationTracker, json)
                            cameraRoutes(cameraController, json)
                            micRoutes(micController, json)
                        }

                        android.util.Log.i("ArtemisServer", "Setting up WebSocket routes...")
                        webSocketRoutes(
                            authManager = authManager,
                            cameraController = cameraController,
                            micController = micController,
                            serverScope = serverScope,
                            json = json
                        )
                    }
                    android.util.Log.i("ArtemisServer", "Module configuration complete.")
                }
            ).apply {
                android.util.Log.i("ArtemisServer", "Calling start(wait=false)...")
                start(wait = false)
                android.util.Log.i("ArtemisServer", "start(wait=false) returned")
            }
            android.util.Log.i("ArtemisServer", "Server started successfully on port $port")
        } catch (e: Exception) {
            android.util.Log.e("ArtemisServer", "FAILED to start server: ${e.message}", e)
            throw e
        }
    }

    suspend fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        server = null
        serverScope.cancel()
    }

    val isRunning: Boolean get() = server != null
}
