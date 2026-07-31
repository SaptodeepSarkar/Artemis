package com.example.artemis.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A paired dashboard as reported by the phone's local server. */
data class PairedDashboard(
    val clientId: String,
    val clientName: String,
    val pairedAt: Long,
    val lastSeen: Long,
    val isActive: Boolean
)

/**
 * Application settings screen.
 *
 * Provides configuration for the embedded server port, notification
 * preferences, and displays app version / about information.
 *
 * @param onNavigateBack Called when the user presses the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    // ---- local state (not persisted in v1) ----
    var serverPort by remember { mutableStateOf("8443") }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var batteryOptimizationIgnored by remember { mutableStateOf(false) }

    // ---- paired dashboards state (session management) ----
    val scope = rememberCoroutineScope()
    var dashboards by remember { mutableStateOf<List<PairedDashboard>>(emptyList()) }
    var dashboardsLoading by remember { mutableStateOf(false) }
    var dashboardsError by remember { mutableStateOf<String?>(null) }
    var revokeAllArmed by remember { mutableStateOf(false) }

    // Port used by the Artemis server (matches DashboardScreen).
    val SERVER_PORT = 8443

    fun fetchDashboards() {
        scope.launch {
            dashboardsLoading = true
            dashboardsError = null
            try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/auth/clients")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().readText()
                    val arr = org.json.JSONObject(text).getJSONArray("clients")
                    dashboards = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        PairedDashboard(
                            clientId = o.getString("clientId"),
                            clientName = o.optString("clientName", "Dashboard"),
                            pairedAt = o.getLong("pairedAt"),
                            lastSeen = o.optLong("lastSeen", 0L),
                            isActive = o.optBoolean("isActive", true)
                        )
                    }
                } else {
                    dashboardsError = "Server returned HTTP ${conn.responseCode}"
                }
                conn.disconnect()
            } catch (e: Exception) {
                dashboardsError = "Server not reachable — is it running?"
            }
            dashboardsLoading = false
        }
    }

    fun revokeDashboard(clientId: String) {
        scope.launch {
            try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/auth/clients/$clientId")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "DELETE"
                conn.responseCode // trigger the request
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("Settings", "Revoke failed: ${e.message}")
            }
            fetchDashboards()
        }
    }

    fun reviveDashboard(clientId: String) {
        scope.launch {
            try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/auth/clients/$clientId/unrevoke")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.write("{}".toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("Settings", "Revive failed: ${e.message}")
            }
            fetchDashboards()
        }
    }

    fun revokeAllDashboards() {
        scope.launch {
            try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/auth/clients")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "DELETE"
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("Settings", "Revoke-all failed: ${e.message}")
            }
            fetchDashboards()
        }
    }

    // Load the dashboard list when the screen opens.
    LaunchedEffect(Unit) { fetchDashboards() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to dashboard"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- server section ----
            item(key = "server_section") {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(title = "Server", icon = Icons.Default.Storage)
            }

            item(key = "server_port") {
                SettingsCard {
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { newValue ->
                            // Only allow digits and clamp to valid port range.
                            if (newValue.all { it.isDigit() } && newValue.length <= 5) {
                                serverPort = newValue
                            }
                        },
                        label = { Text("Server Port") },
                        supportingText = { Text("Default: 8443 (1024–65535)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ---- notifications section ----
            item(key = "notifications_section") {
                SectionHeader(title = "Notifications", icon = Icons.Default.Notifications)
            }

            item(key = "notifications_toggle") {
                SettingsCard {
                    SwitchSettingRow(
                        title = "Push Notifications",
                        subtitle = "Receive alerts when clients connect or disconnect",
                        checked = notificationsEnabled,
                        onCheckedChange = { notificationsEnabled = it }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SwitchSettingRow(
                        title = "Ignore Battery Optimizations",
                        subtitle = "Prevent system from killing the background service",
                        checked = batteryOptimizationIgnored,
                        onCheckedChange = { batteryOptimizationIgnored = it }
                    )
                }
            }

            // ---- security section ----
            item(key = "security_section") {
                SectionHeader(title = "Security", icon = Icons.Default.Security)
            }

            item(key = "security_info") {
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "All communication between Artemis Sentinel and paired " +
                                    "clients is encrypted using TLS. The pairing code acts as a " +
                                    "one-time authentication token.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- paired dashboards section ----
            item(key = "dashboards_section") {
                SectionHeader(title = "Paired Dashboards", icon = Icons.Default.Person)
            }

            item(key = "dashboards_list") {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (dashboardsLoading) "Loading…" else "${dashboards.size} paired",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { fetchDashboards() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh dashboards"
                            )
                        }
                    }
                    when {
                        dashboardsError != null -> {
                            Text(
                                text = dashboardsError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { fetchDashboards() }) {
                                Text("Retry")
                            }
                        }
                        dashboards.isEmpty() && !dashboardsLoading -> {
                            Text(
                                text = "No paired dashboards yet. Pair one by entering the " +
                                        "6-digit code shown on the dashboard screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            dashboards.forEachIndexed { index, dashboard ->
                                PairedDashboardRow(
                                    dashboard = dashboard,
                                    onRevoke = { revokeDashboard(dashboard.clientId) },
                                    onRevive = { reviveDashboard(dashboard.clientId) }
                                )
                                if (index < dashboards.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (revokeAllArmed) {
                                        revokeAllArmed = false
                                        revokeAllDashboards()
                                    } else {
                                        revokeAllArmed = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (revokeAllArmed) "Tap again to confirm" else "Revoke All Dashboards")
                            }
                        }
                    }
                }
            }

            // ---- about section ----
            item(key = "about_section") {
                SectionHeader(title = "About", icon = Icons.Default.Storage)
            }

            item(key = "about_info") {
                SettingsCard {
                    AboutRow(label = "App Name", value = "Artemis Sentinel")
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    AboutRow(label = "Version", value = "1.0.0")
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    AboutRow(label = "Package", value = "com.example.artemis")
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    AboutRow(label = "Developer", value = "Nous Research")
                }
            }

            // bottom spacer
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable sub-components
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SettingsCard(
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun AboutRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PairedDashboardRow(
    dashboard: PairedDashboard,
    onRevoke: () -> Unit,
    onRevive: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dashboard.clientName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (dashboard.isActive) "Active" else "Revoked",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dashboard.isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            Text(
                text = "Paired ${formatTimestamp(dashboard.pairedAt)} · " +
                        "Last seen ${formatTimestamp(dashboard.lastSeen)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (dashboard.isActive) {
            IconButton(onClick = onRevoke) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Revoke ${dashboard.clientName}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            OutlinedButton(onClick = onRevive) {
                Text("Revive")
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "never"
    return try {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(
            java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
        )
    } catch (_: Exception) {
        timestamp.toString()
    }
}
