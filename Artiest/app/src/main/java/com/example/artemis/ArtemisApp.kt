package com.example.artemis

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class ArtemisApp : Application() {

    lateinit var masterKey: MasterKey
        private set
    lateinit var secretKey: SecretKey
        private set
    lateinit var encryptedPreferences: EncryptedSharedPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        initSecurity()
        createNotificationChannels()
    }

    private fun initSecurity() {
        masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        encryptedPreferences = EncryptedSharedPreferences.create(
            this,
            "artemis_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences

        secretKey = getOrCreateHmacKey()
    }

    private fun getOrCreateHmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (keyStore.containsAlias(KEYSTORE_HMAC_ALIAS)) {
            return (keyStore.getEntry(KEYSTORE_HMAC_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_HMAC_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        NotificationChannel(
            CHANNEL_SERVICE,
            "Artemis Sentinel Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for Artemis Sentinel foreground service"
            manager.createNotificationChannel(this)
        }

        NotificationChannel(
            CHANNEL_EVENTS,
            "Artemis Events",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for camera, mic, and location events"
            manager.createNotificationChannel(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            NotificationChannel(
                CHANNEL_MEDIA,
                "Artemis Media",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback and recording notifications"
                manager.createNotificationChannel(this)
            }
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "artemis_service"
        const val CHANNEL_EVENTS = "artemis_events"
        const val CHANNEL_MEDIA = "artemis_media"
        const val KEYSTORE_HMAC_ALIAS = "artemis_hmac_key"

        lateinit var instance: ArtemisApp
            private set
    }
}
