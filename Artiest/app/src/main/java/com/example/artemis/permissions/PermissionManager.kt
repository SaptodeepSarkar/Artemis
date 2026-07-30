package com.example.artemis.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "permissions")

class PermissionManager(private val context: Context) {

    companion object {
        /** All permissions requested once at first launch, then remembered. */
        @JvmStatic
        val ALL_PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.RECEIVE_BOOT_COMPLETED)
        }.toTypedArray()
    }

    private val permissionKeys = ALL_PERMISSIONS.associateWith {
        booleanPreferencesKey("granted_${it.substringAfterLast('.')}")
    }

    /** Check whether ALL permissions in [ALL_PERMISSIONS] are currently granted. */
    suspend fun checkAllPermissions(): Boolean {
        return ALL_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Check whether ALL permissions are granted (blocking variant for LaunchedEffect). */
    fun checkAllPermissionsSync(): Boolean = runBlocking { checkAllPermissions() }

    /** Check if a specific permission was granted and persisted. */
    suspend fun isPermissionGranted(permission: String): Boolean {
        val prefs = context.dataStore.data.first()
        val key = permissionKeys[permission] ?: return false
        return prefs[key] ?: false
    }

    /** Mark a permission as granted in DataStore (called after user grants it). */
    suspend fun markPermissionGranted(permission: String) {
        context.dataStore.edit { prefs ->
            permissionKeys[permission]?.let { key ->
                prefs[key] = true
            }
        }
    }

    /** Mark a permission as denied. */
    suspend fun markPermissionDenied(permission: String) {
        context.dataStore.edit { prefs ->
            permissionKeys[permission]?.let { key ->
                prefs[key] = false
            }
        }
    }

    /** Reset all stored permission flags (for re-onboarding). */
    suspend fun resetAll() {
        context.dataStore.edit { prefs ->
            permissionKeys.values.forEach { key ->
                prefs.remove(key)
            }
        }
    }
}
