package com.example.artemis.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.artemis.permissions.PermissionManager
import kotlinx.coroutines.launch

/**
 * Human-readable label for each permission constant shown in the UI list.
 */
private fun permissionDisplayName(permission: String): String = when (permission) {
    Manifest.permission.CAMERA -> "Camera"
    Manifest.permission.RECORD_AUDIO -> "Microphone"
    Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "Coarse Location"
    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Background Location"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    Manifest.permission.READ_CONTACTS -> "Contacts"
    Manifest.permission.READ_SMS -> "SMS"
    Manifest.permission.RECEIVE_SMS -> "Receive SMS"
    Manifest.permission.READ_PHONE_STATE -> "Phone State"
    Manifest.permission.READ_CALL_LOG -> "Call Log"
    Manifest.permission.SYSTEM_ALERT_WINDOW -> "Overlay"
    Manifest.permission.RECEIVE_BOOT_COMPLETED -> "Boot Receiver"
    Manifest.permission.FOREGROUND_SERVICE -> "Foreground Service"
    Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC -> "Data Sync Service"
    Manifest.permission.FOREGROUND_SERVICE_CAMERA -> "Camera Service"
    Manifest.permission.FOREGROUND_SERVICE_MICROPHONE -> "Microphone Service"
    Manifest.permission.FOREGROUND_SERVICE_LOCATION -> "Location Service"
    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK -> "Media Playback Service"
    Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED -> "System Exempted Service"
    Manifest.permission.INTERNET -> "Internet"
    Manifest.permission.ACCESS_NETWORK_STATE -> "Network State"
    Manifest.permission.ACCESS_WIFI_STATE -> "Wi-Fi State"
    Manifest.permission.CHANGE_WIFI_STATE -> "Change Wi-Fi"
    Manifest.permission.WAKE_LOCK -> "Wake Lock"
    else -> permission.substringAfterLast('.')
}

/**
 * Groups permissions by category for a cleaner UI layout.
 */
private fun permissionCategory(permission: String): String = when {
    permission.startsWith("android.permission.CAMERA") -> "Camera"
    permission.startsWith("android.permission.RECORD_AUDIO") -> "Microphone"
    permission.startsWith("android.permission.ACCESS_") -> "Location"
    permission.startsWith("android.permission.POST_") -> "Notifications"
    permission.startsWith("android.permission.READ_CONTACTS") -> "Contacts"
    permission.startsWith("android.permission.READ_SMS") ||
    permission.startsWith("android.permission.RECEIVE_SMS") -> "SMS"
    permission.startsWith("android.permission.READ_PHONE") ||
    permission.startsWith("android.permission.READ_CALL") -> "Phone"
    permission.startsWith("android.permission.SYSTEM_ALERT") -> "Display"
    permission.startsWith("android.permission.RECEIVE_BOOT") -> "System"
    permission.startsWith("android.permission.FOREGROUND_") -> "Service"
    permission.startsWith("android.permission.INTERNET") ||
    permission.startsWith("android.permission.ACCESS_NETWORK") ||
    permission.startsWith("android.permission.ACCESS_WIFI") ||
    permission.startsWith("android.permission.CHANGE_WIFI") -> "Network"
    permission.startsWith("android.permission.WAKE_LOCK") -> "System"
    else -> "Other"
}

/**
 * Screen that displays all required permissions and guides the user
 * through granting them.
 *
 * @param permissionManager Instance of [PermissionManager] used to track state.
 * @param onAllPermissionsGranted Called once every permission in [PermissionManager.ALL_PERMISSIONS]
 *   has been granted at the OS level and recorded via [PermissionManager.markPermissionGranted].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    permissionManager: PermissionManager,
    onAllPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- state ----
    var allGranted by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var grantedMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isRequesting by remember { mutableStateOf(false) }

    val allPermissions = remember { PermissionManager.ALL_PERMISSIONS.toList() }

    // Derive missing permissions (those not yet granted at the OS level).
    val missingPermissions by remember(allPermissions, grantedMap) {
        derivedStateOf {
            allPermissions.filter { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
                        || grantedMap[perm] != true
            }
        }
    }

    // ---- permission request launcher (requests ALL missing permissions at once) ----
    val multiRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults: Map<String, Boolean> ->
        isRequesting = false
        var nowAllGranted = true
        val updated = grantedMap.toMutableMap()
        for ((permission, granted) in grantResults) {
            updated[permission] = granted
            if (granted) {
                scope.launch {
                    permissionManager.markPermissionGranted(permission)
                }
            } else {
                nowAllGranted = false
            }
        }
        grantedMap = updated
        // Check if everything is now granted.
        if (nowAllGranted) {
            scope.launch {
                if (permissionManager.checkAllPermissions()) {
                    allGranted = true
                    onAllPermissionsGranted()
                }
            }
        }
    }

    // ---- initial check on first composition ----
    LaunchedEffect(Unit) {
        val granted = permissionManager.checkAllPermissions()
        allGranted = granted
        isLoading = false
        if (granted) {
            onAllPermissionsGranted()
        }
    }

    // ---- grouped permissions for categorized display ----
    val groupedPermissions by remember(allPermissions, grantedMap) {
        derivedStateOf {
            allPermissions.groupBy { permissionCategory(it) }
                .mapValues { (_, perms) ->
                    perms.map { perm ->
                        PermissionItem(
                            permission = perm,
                            granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                                    || grantedMap[perm] == true
                        )
                    }
                }
        }
    }

    // Count of currently-granted permissions.
    val grantedCount by remember(allPermissions, grantedMap) {
        derivedStateOf {
            allPermissions.count { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                        || grantedMap[perm] == true
            }
        }
    }

    // ---- UI ----
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Required Permissions") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Checking permissions…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // ---- header explanation card ----
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Artemis Sentinel needs the following permissions to monitor " +
                                "device sensors, camera, microphone, and location. " +
                                "All data stays on-device until you explicitly share it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- progress indicator ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Progress",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$grantedCount / ${allPermissions.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (grantedCount == allPermissions.size)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = {
                    if (allPermissions.isEmpty()) 1f
                    else grantedCount.toFloat() / allPermissions.size
                },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ---- grouped permission list ----
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedPermissions.forEach { (category, items) ->
                    item(key = "category_$category") {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    items(items = items, key = { it.permission }) { permItem ->
                        PermissionRow(
                            permission = permItem.permission,
                            displayName = permissionDisplayName(permItem.permission),
                            isGranted = permItem.granted,
                            onGranted = { granted ->
                                if (granted) {
                                    scope.launch {
                                        permissionManager.markPermissionGranted(permItem.permission)
                                    }
                                    grantedMap = grantedMap + (permItem.permission to true)
                                }
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // ---- bottom "Grant All" button ----
            AnimatedVisibility(
                visible = missingPermissions.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn()
            ) {
                Button(
                    onClick = {
                        isRequesting = true
                        multiRequestLauncher.launch(missingPermissions.toTypedArray())
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    enabled = !isRequesting && missingPermissions.isNotEmpty()
                ) {
                    if (isRequesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant All Permissions")
                }
            }
        }
    }
}

// ---- Data class ----

private data class PermissionItem(
    val permission: String,
    val granted: Boolean
)

// ---- Individual permission row ----

@Composable
private fun PermissionRow(
    permission: String,
    displayName: String,
    isGranted: Boolean,
    onGranted: (Boolean) -> Unit
) {
    // Launcher must be at composable scope, not inside a callback.
    val singleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onGranted(granted)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Security,
                contentDescription = if (isGranted) "Granted" else "Not granted",
                tint = if (isGranted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Permission name and constant
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isGranted) FontWeight.Medium else FontWeight.Normal
                )
                Text(
                    text = permission.substringAfterLast('.'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Grant indicator or button
            if (isGranted) {
                Text(
                    text = "Granted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                TextButton(onClick = { singleLauncher.launch(permission) }) {
                    Text("Grant", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
