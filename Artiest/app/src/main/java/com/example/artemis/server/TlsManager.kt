package com.example.artemis.server

import android.content.Context
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * TLS support for the raw ServerSocket.
 *
 * A 2048-bit RSA keypair + self-signed X.509 certificate is generated once
 * (BouncyCastle) and persisted as a PKCS12 keystore in app-private storage.
 *
 * Why not AndroidKeyStore: Conscrypt's native TLS layer cannot route RSA
 * private-key operations to TEE-held AndroidKeyStore keys on several devices
 * (Samsung M51 observed: "error:04000044:RSA routines:OPENSSL_internal:
 * internal error" on every handshake). A software keypair sidesteps that
 * entirely and is still app-private. The pairing-token HMAC key REMAINS in
 * AndroidKeyStore (AuthManager) — that is the real trust anchor.
 *
 * Clients verify the server by SHA-256 fingerprint pinning (TOFU).
 *
 * --- Transport hardening (v1.5) ---
 *
 * TLS 1.3 is negotiated by default. Compatibility analysis:
 *  - minSdk is 29 (Android 10), whose Conscrypt ships TLS 1.3 enabled, so
 *    every in-scope device negotiates TLS 1.3 without any workaround.
 *  - TLS 1.2 remains enabled as a fallback ONLY for clients that cannot
 *    speak 1.3 (e.g. old Python/OpenSSL stacks). The 1.2 fallback is
 *    restricted to ECDHE key exchange with AEAD ciphers (AES-GCM / ChaCha20)
 *    — forward secrecy is guaranteed, CBC/RSA-key-exchange suites are gone.
 *  - Renegotiation: TLS 1.3 removed renegotiation entirely; on the 1.2
 *    fallback path, modern Conscrypt (Android 10+) rejects client-initiated
 *    renegotiation by default. The server never renegotiates (startHandshake
 *    is called exactly once per connection).
 */
object TlsManager {
    private const val KEYSTORE_PASSWORD = "artemis-tls"  // PKCS12 encryption pw
    private const val KEY_ALIAS = "artemis_tls"
    private const val RSA_KEY_SIZE = 2048
    private const val TAG = "TlsManager"

    /** TLS 1.3 ciphers — fixed by the spec, all AEAD. */
    private val TLS13_SUITES = setOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256"
    )

    /**
     * Allowed TLS 1.2 fallback suites: ECDHE (forward secrecy) + AEAD only.
     * No CBC, no RSA key exchange, no RC4/3DES/DES.
     */
    private val TLS12_ALLOWED = setOf(
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"
    )

    private var sslContext: SSLContext? = null
    private var fingerprint: String? = null

    @Synchronized
    fun getSslContext(context: Context): SSLContext {
        sslContext?.let { return it }

        val ksFile = File(context.filesDir, "tls_keystore.p12")
        val ks = KeyStore.getInstance("PKCS12")
        if (ksFile.exists()) {
            ks.load(ksFile.inputStream(), KEYSTORE_PASSWORD.toCharArray())
            Log.i(TAG, "Loaded existing TLS keystore")
        } else {
            ks.load(null, null)
            generateAndStore(ks, ksFile)
            Log.i(TAG, "Generated new TLS keystore")
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray())

        // "TLS" resolves to the platform's best available protocol (Conscrypt
        // on Android 10+ → TLS 1.3). The accept loop restricts the negotiated
        // protocol/cipher set via configureSocket().
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        sslContext = ctx

        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        fingerprint = sha256Hex(cert.encoded)
        return ctx
    }

    /**
     * Lock an accepted SSLSocket down to TLS 1.3 (preferred) with a restricted
     * TLS 1.2 fallback (ECDHE + AEAD only). Server-side cipher preference and
     * a 15s handshake timeout (set by the accept loop before startHandshake).
     */
    fun configureSocket(socket: SSLSocket) {
        val supportedProtocols = socket.supportedProtocols.toSet()
        val protocols = mutableListOf<String>()
        if ("TLSv1.3" in supportedProtocols) protocols.add("TLSv1.3")
        if ("TLSv1.2" in supportedProtocols) protocols.add("TLSv1.2")
        if (protocols.isNotEmpty()) {
            socket.enabledProtocols = protocols.toTypedArray()
        }

        val allowed = socket.supportedCipherSuites.filter { isAllowedSuite(it) }
        if (allowed.isNotEmpty()) {
            socket.enabledCipherSuites = allowed.toTypedArray()
        }

        socket.useClientMode = false
        try {
            socket.sslParameters = socket.sslParameters.apply { useCipherSuitesOrder = true }
        } catch (_: Exception) {
            // Some stacks expose read-only SSLParameters; server preference
            // ordering is a nice-to-have, not a requirement.
        }
    }

    /** True for TLS 1.3 mandatory suites or allowed TLS 1.2 ECDHE+AEAD suites. */
    fun isAllowedSuite(name: String): Boolean {
        if (name in TLS13_SUITES) return true
        return name in TLS12_ALLOWED
    }

    private fun generateAndStore(ks: KeyStore, ksFile: File) {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSA_KEY_SIZE)
        val keyPair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Artemis Sentinel")
        val serial = BigInteger.valueOf(now)
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 3600 * 1000)

        val certBuilder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        ks.setKeyEntry(
            KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(cert)
        )
        ksFile.outputStream().use { ks.store(it, KEYSTORE_PASSWORD.toCharArray()) }
        // App-private storage is already sandboxed; keep it tight anyway.
        ksFile.setReadable(false, false)
        ksFile.setWritable(false, false)
        ksFile.setReadable(true, true)
        ksFile.setWritable(true, true)
    }

    /** SHA-256 of the TLS cert DER, colon-separated uppercase hex. */
    fun getFingerprint(context: Context): String {
        if (fingerprint == null) getSslContext(context)
        return fingerprint ?: ""
    }

    /**
     * Rotate the TLS certificate: deletes the keystore and regenerates it on
     * next use. This changes the fingerprint — every paired dashboard's TOFU
     * pin will mismatch, forcing them to reject, clear the trust relationship
     * and re-pair. Use only when the key is suspected compromised.
     */
    @Synchronized
    fun rotateCertificate(context: Context) {
        val ksFile = File(context.filesDir, "tls_keystore.p12")
        if (ksFile.exists() && !ksFile.delete()) {
            Log.w(TAG, "Could not delete TLS keystore for rotation")
            return
        }
        sslContext = null
        fingerprint = null
        Log.w(TAG, "TLS keystore deleted — certificate will be regenerated (all dashboards must re-pair)")
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
