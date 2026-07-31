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
 */
object TlsManager {
    private const val KEYSTORE_PASSWORD = "artemis-tls"  // PKCS12 encryption pw
    private const val KEY_ALIAS = "artemis_tls"
    private const val RSA_KEY_SIZE = 2048
    private const val TAG = "TlsManager"

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

        // TLS 1.2: plenty for our own clients; TLS 1.3 adds no security
        // for a pinned self-signed server and was flaky with software keys.
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(kmf.keyManagers, null, null)
        sslContext = ctx

        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        fingerprint = sha256Hex(cert.encoded)
        return ctx
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

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
