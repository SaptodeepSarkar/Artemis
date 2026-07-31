package com.example.artemis.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.example.artemis.ArtemisApp
import com.example.artemis.service.ArtemisSentinelService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Color constants for status indicators.
private val StatusGreen = Color(0xFF4CAF50)
private val StatusGray = Color(0xFFBDBDBD)

/**
 * Main dashboard shown after all permissions are granted.
 *
 * Displays the pairing code, connected clients count, service on/off
 * controls, and a summary of monitored features.
 *
 * @param onNavigateToSettings Called when the user taps the settings icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- service running state ----
    var isServiceRunning by remember { mutableStateOf(false) }

    // ---- pairing code state ----
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var certFingerprint by remember { mutableStateOf<String?>(null) }
    var connectedClients by remember { mutableIntStateOf(0) }
    var isRefreshingCode by remember { mutableStateOf(false) }
    var serverIpAddress by remember { mutableStateOf<String?>(null) }

    // Port used by the Artemis server
    val SERVER_PORT = 8443

    // ---- fetch pairing code from server ----
    fun refreshPairingCode() {
        // Read from shared in-process state (server exposes code via ArtemisApp)
        val sharedCode = ArtemisApp.instance.currentPairingCode
        pairingCode = sharedCode?.code
        isRefreshingCode = false
    }

    fun regeneratePairingCode() {
        scope.launch {
            isRefreshingCode = true
            try {
                // Rotate via the local server (loopback-only endpoint).
                // The response never contains the code — read it from
                // in-process shared state, which the server just updated.
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/auth/pair/regenerate")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.write("{}".toByteArray())
                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    conn.inputStream.bufferedReader().readText()
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("Dashboard", "Could not regenerate pairing code: ${e.message}")
            }
            // Always read the fresh code from shared state (server may have
            // rotated it on its own; shared state is the single source of truth).
            pairingCode = ArtemisApp.instance.currentPairingCode?.code
            isRefreshingCode = false
        }
    }

    // ---- fetch pairing code from server on launch ----
    LaunchedEffect(Unit) {
        // Get local IP for display
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            serverIpAddress = String.format("%d.%d.%d.%d", ipInt and 0xFF, (ipInt shr 8) and 0xFF, (ipInt shr 16) and 0xFF, (ipInt shr 24) and 0xFF)
        } catch (_: Exception) {
            serverIpAddress = "192.168.x.x"
        }
        // AUTO-START service FIRST, then read pairing code from shared state
        if (!isServiceRunning) {
            val intent = Intent(context, ArtemisSentinelService::class.java)
            try {
                context.startForegroundService(intent)
                isServiceRunning = true
            } catch (e: Exception) {
                android.util.Log.e("Dashboard", "Auto-start failed: ${e.message}")
            }
        }
        // Wait for server to be ready
        delay(3000)
        // Read code from shared in-process state — no network call
        pairingCode = ArtemisApp.instance.currentPairingCode?.code
        certFingerprint = ArtemisApp.instance.currentCertFingerprint
        android.util.Log.i("Dashboard", if (pairingCode != null) "Pairing code available on screen" else "No pairing code in shared state yet")
    }

    // ---- periodic poll of connected clients while service is running ----
    LaunchedEffect(isServiceRunning) {
        while (isServiceRunning) {
            // Follow the 5-minute server-side rotation: the code shown on
            // screen must always match the code the server will accept.
            pairingCode = ArtemisApp.instance.currentPairingCode?.code
            // TLS cert fingerprint (stable across rotations — set at server start)
            certFingerprint = ArtemisApp.instance.currentCertFingerprint
            // connectedClients would be updated by server events
            delay(5_000L)
        }
    }

    // ---- start / stop helpers ----
    fun startService() {
        val intent = Intent(context, ArtemisSentinelService::class.java)
        try {
            android.util.Log.i("Dashboard", "Starting foreground service...")
            context.startForegroundService(intent)
            isServiceRunning = true
            android.util.Log.i("Dashboard", "Foreground service started successfully")
        } catch (e: Exception) {
            android.util.Log.e("Dashboard", "Failed to start service: ${e.message}", e)
            isServiceRunning = false
        }
    }

    fun stopService() {
        val intent = Intent(context, ArtemisSentinelService::class.java)
        context.stopService(intent)
        isServiceRunning = false
    }

    // ---- UI ----
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Artemis Sentinel") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
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
            // ---- status card ----
            item(key = "status") {
                Spacer(modifier = Modifier.height(4.dp))
                StatusCard(isRunning = isServiceRunning)
            }

            // ---- pairing code card ----
            item(key = "pairing") {
                PairingCodeCard(
                    pairingCode = pairingCode,
                    certFingerprint = certFingerprint,
                    serverIp = serverIpAddress,
                    onRefresh = { regeneratePairingCode() },
                    isRefreshing = isRefreshingCode
                )
            }

            // ---- connected clients card ----
            item(key = "clients") {
                ConnectedClientsCard(clientCount = connectedClients)
            }

            // ---- service control button ----
            item(key = "control") {
                ServiceControlButton(
                    isRunning = isServiceRunning,
                    onStart = { startService() },
                    onStop = { stopService() }
                )
            }

            // ---- monitored features summary ----
            item(key = "features") {
                FeatureSummaryCard()
            }

            // bottom spacer
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

@Composable
private fun StatusCard(isRunning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot
            Canvas(modifier = Modifier.size(12.dp)) {
                drawCircle(color = if (isRunning) StatusGreen else StatusGray)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) "Service Running" else "Service Stopped",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isRunning)
                        "Monitoring device sensors and connectivity"
                    else
                        "Tap Start to begin monitoring",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.Sensors,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isRunning) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun PairingCodeCard(
    pairingCode: String?,
    certFingerprint: String?,
    serverIp: String?,
    onRefresh: () -> Unit,
    isRefreshing: Boolean
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Pairing Code",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh pairing code",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pairing code display box
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = pairingCode ?: "------",
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = TextUnit(4f, TextUnitType.Sp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Share this code with the Artemis desktop app to pair. Code rotates every 5 minutes.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (certFingerprint != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "TLS PIN  ${certFingerprint.replace(":", "").take(16).chunked(4).joinToString(":")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Encrypted connection — verify this PIN in the dashboard",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (serverIp != null) {
                Text(
                    text = "Server: https://$serverIp:8443",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ConnectedClientsCard(clientCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DevicesOther,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Connected Clients",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Devices paired with this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "$clientCount",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (clientCount > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ServiceControlButton(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    if (isRunning) {
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Stop Monitoring", style = MaterialTheme.typography.labelLarge)
        }
    } else {
        FilledTonalButton(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Monitoring", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FeatureSummaryCard() {
    val features = listOf(
        "Camera" to "Monitor and stream camera feed",
        "Microphone" to "Audio monitoring and recording",
        "Location" to "GPS and network location tracking",
        "Device Info" to "Battery, sensor, and system data"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Monitored Features",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            val primaryColor = MaterialTheme.colorScheme.primary
            features.forEach { (name, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(modifier = Modifier.size(6.dp)) {
                        drawCircle(color = primaryColor)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
