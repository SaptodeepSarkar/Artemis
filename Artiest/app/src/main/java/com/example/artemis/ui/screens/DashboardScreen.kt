package com.example.artemis.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.example.artemis.ArtemisApp
import com.example.artemis.service.ArtemisSentinelService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Artemis Labs admin palette — dark, consistent with the web dashboard.
// ---------------------------------------------------------------------------
private val AdminBg = Color(0xFF0B0F14)
private val AdminCard = Color(0xFF121821)
private val AdminCardBorder = Color(0xFF1E2937)
private val AdminText = Color(0xFFE2E8F0)
private val AdminMuted = Color(0xFF94A3B8)
private val AdminGreen = Color(0xFF4CAF50)
private val AdminRed = Color(0xFFF87171)
private val AdminCyan = Color(0xFF22D3EE)
private val AdminAmber = Color(0xFFFBBF24)

private val SERVER_PORT = 8443

/**
 * Admin dashboard. The pairing flow is FROZEN (open → code → enter once);
 * this screen only restyles it. Feature controls talk to the loopback
 * server (loopback is auth-exempt) so the UI exercises the exact same
 * endpoints the web dashboard uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- live state ----
    var isServiceRunning by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var certFingerprint by remember { mutableStateOf<String?>(null) }
    var serverIpAddress by remember { mutableStateOf<String?>(null) }
    var uptimeSec by remember { mutableLongStateOf(0L) }
    var cameraCount by remember { mutableIntStateOf(0) }
    var callCount by remember { mutableIntStateOf(0) }
    var smsCount by remember { mutableIntStateOf(0) }
    var videoCount by remember { mutableIntStateOf(0) }
    var callRecordingEnabled by remember { mutableStateOf(false) }

    // ---- actions ----
    fun startService() {
        try {
            context.startForegroundService(Intent(context, ArtemisSentinelService::class.java))
            isServiceRunning = true
        } catch (e: Exception) {
            android.util.Log.e("Dashboard", "Start failed: ${e.message}")
        }
    }

    fun stopService() {
        context.stopService(Intent(context, ArtemisSentinelService::class.java))
        isServiceRunning = false
    }

    fun refreshPairingCode() {
        scope.launch {
            try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/auth/pair/regenerate")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.write("{}\n".toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) { }
            pairingCode = ArtemisApp.instance.currentPairingCode?.code
        }
    }

    fun capturePhoto(cameraId: String) {
        scope.launch {
            try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/camera/capture")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 20_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.write("{\"cameraId\":\"$cameraId\"}\n".toByteArray())
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    if (json.optString("status") == "ok") {
                        cameraCount += 1
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("Dashboard", "Capture failed: ${e.message}")
            }
        }
    }

    fun recordVideo(durationMs: Long) {
        scope.launch {
            try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/video/record")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 120_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.write("{\"cameraId\":\"back\",\"durationMs\":$durationMs}\n".toByteArray())
                if (conn.responseCode == 200) videoCount += 1
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("Dashboard", "Video failed: ${e.message}")
            }
        }
    }

    fun toggleCallRecording() {
        scope.launch {
            try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/callrecorder/toggle")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.write("{\"enabled\":\"${!callRecordingEnabled}\"}\n".toByteArray())
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    callRecordingEnabled = json.optBoolean("enabled", callRecordingEnabled)
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("Dashboard", "Call recorder toggle failed: ${e.message}")
            }
        }
    }

    // ---- startup: auto-start service, fetch IP ----
    LaunchedEffect(Unit) {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            serverIpAddress = String.format(
                "%d.%d.%d.%d",
                ipInt and 0xFF, (ipInt shr 8) and 0xFF, (ipInt shr 16) and 0xFF, (ipInt shr 24) and 0xFF
            )
        } catch (_: Exception) {
            serverIpAddress = "192.168.x.x"
        }
        if (!isServiceRunning) {
            try {
                context.startForegroundService(Intent(context, ArtemisSentinelService::class.java))
                isServiceRunning = true
            } catch (e: Exception) {
                android.util.Log.e("Dashboard", "Auto-start failed: ${e.message}")
            }
        }
        delay(3000)
        pairingCode = ArtemisApp.instance.currentPairingCode?.code
        certFingerprint = ArtemisApp.instance.currentCertFingerprint
    }

    // ---- periodic status poll ----
    LaunchedEffect(isServiceRunning) {
        while (true) {
            pairingCode = ArtemisApp.instance.currentPairingCode?.code
            certFingerprint = ArtemisApp.instance.currentCertFingerprint
            val server = ArtemisApp.instance.serverRef
            val running = server?.isRunning == true && !server.serverSocketClosed
            isServiceRunning = running
            if (ArtemisApp.instance.serverStartedAt > 0) {
                uptimeSec = (System.currentTimeMillis() - ArtemisApp.instance.serverStartedAt) / 1000
            }
            // Pull counts through the loopback API (auth-exempt).
            fun getInt(path: String, key: String): Int {
                return try {
                    val url = java.net.URL("http://127.0.0.1:$SERVER_PORT$path")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    val code = conn.responseCode
                    if (code == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().readText())
                        conn.disconnect()
                        json.optInt(key, 0)
                    } else {
                        conn.disconnect()
                        0
                    }
                } catch (_: Exception) { 0 }
            }
            cameraCount = getInt("/api/v1/camera/captures", "count")
            videoCount = getInt("/api/v1/video/list", "count")
            callCount = getInt("/api/v1/logs/calls", "count")
            smsCount = getInt("/api/v1/sms", "count")
            val crStatus = try {
                val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/callrecorder/status")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                val code = conn.responseCode
                if (code == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()
                    json.optBoolean("enabled", false)
                } else {
                    conn.disconnect()
                    false
                }
            } catch (_: Exception) { false }
            callRecordingEnabled = crStatus
            delay(5_000L)
        }
    }

    // ---- UI ----
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        background = AdminBg,
        surface = AdminCard,
        surfaceVariant = Color(0xFF1A2230),
        primary = AdminCyan,
        onBackground = AdminText,
        onSurface = AdminText,
        onSurfaceVariant = AdminMuted,
        onPrimary = AdminBg
    )) {
        Scaffold(
            containerColor = AdminBg,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "ARTEMIS SENTINEL",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = AdminText,
                                letterSpacing = TextUnit(2f, TextUnitType.Sp)
                            )
                            Text(
                                "ADMIN CONSOLE · v2.0.0",
                                style = MaterialTheme.typography.labelSmall,
                                color = AdminMuted,
                                letterSpacing = TextUnit(1f, TextUnitType.Sp)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = AdminMuted
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AdminBg,
                        titleContentColor = AdminText
                    )
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "status") { Spacer(modifier = Modifier.height(2.dp)) }

                // ---- status header ----
                item(key = "status-header") {
                    AdminStatusHeader(
                        running = isServiceRunning,
                        uptimeSec = uptimeSec,
                        serverIp = serverIpAddress
                    )
                }

                // ---- pairing card (frozen flow, restyled) ----
                item(key = "pairing") {
                    AdminPairingCard(
                        pairingCode = pairingCode,
                        certFingerprint = certFingerprint,
                        onRefresh = { refreshPairingCode() }
                    )
                }

                // ---- feature grid ----
                item(key = "features-title") {
                    SectionLabel("CAPTURE & FEATURES")
                }
                item(key = "features-grid") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AdminFeatureCard(
                                modifier = Modifier.weight(1f),
                                title = "Camera",
                                value = "$cameraCount photos",
                                accent = AdminCyan,
                                icon = { Icons.Default.CameraAlt },
                                actions = listOf(
                                    AdminAction("BACK") { capturePhoto("back") },
                                    AdminAction("FRONT") { capturePhoto("front") }
                                )
                            )
                            AdminFeatureCard(
                                modifier = Modifier.weight(1f),
                                title = "Video",
                                value = "$videoCount clips",
                                accent = AdminAmber,
                                icon = { Icons.Default.Videocam },
                                actions = listOf(
                                    AdminAction("REC 5s") { recordVideo(5_000L) },
                                    AdminAction("REC 15s") { recordVideo(15_000L) }
                                )
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AdminFeatureCard(
                                modifier = Modifier.weight(1f),
                                title = "Microphone",
                                value = "Audio monitoring",
                                accent = AdminGreen,
                                icon = { Icons.Default.Mic },
                                actions = listOf(
                                    AdminAction("START") { micStart() },
                                    AdminAction("STOP") { micStop() }
                                )
                            )
                            AdminFeatureCard(
                                modifier = Modifier.weight(1f),
                                title = "Call Recording",
                                value = if (callRecordingEnabled) "AUTO ON" else "OFF",
                                accent = if (callRecordingEnabled) AdminGreen else AdminMuted,
                                icon = { Icons.Default.Smartphone },
                                actions = listOf(
                                    AdminAction(if (callRecordingEnabled) "DISABLE" else "ENABLE") {
                                        toggleCallRecording()
                                    }
                                )
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AdminFeatureCard(
                                modifier = Modifier.weight(1f),
                                title = "Call Logs",
                                value = "$callCount entries",
                                accent = AdminCyan,
                                icon = { Icons.Default.Smartphone },
                                actions = emptyList()
                            )
                            AdminFeatureCard(
                                modifier = Modifier.weight(1f),
                                title = "SMS",
                                value = "$smsCount messages",
                                accent = AdminGreen,
                                icon = { Icons.Default.Smartphone },
                                actions = emptyList()
                            )
                        }
                    }
                }

                // ---- service control ----
                item(key = "control") {
                    if (isServiceRunning) {
                        Button(
                            onClick = { stopService() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AdminRed),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("STOP MONITORING", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { startService() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AdminGreen.copy(alpha = 0.15f),
                                contentColor = AdminGreen
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("START MONITORING", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

fun micStart() {
    kotlinx.coroutines.GlobalScope.launch {
        try {
            val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/mic/record/start")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write("{}\n".toByteArray())
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) { }
    }
}

fun micStop() {
    kotlinx.coroutines.GlobalScope.launch {
        try {
            val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/api/v1/mic/record/stop")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write("{}\n".toByteArray())
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) { }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AdminMuted,
        fontWeight = FontWeight.Bold,
        letterSpacing = TextUnit(2f, TextUnitType.Sp),
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun AdminStatusHeader(running: Boolean, uptimeSec: Long, serverIp: String?) {
    val uptime = if (uptimeSec > 0) {
        val h = uptimeSec / 3600
        val m = (uptimeSec % 3600) / 60
        val s = uptimeSec % 60
        "${h}h ${m}m ${s}s"
    } else {
        "—"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AdminCard),
        border = BorderStroke(1.dp, AdminCardBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // pulsing-style status dot
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCircle(color = if (running) AdminGreen else AdminRed)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) "SERVER ACTIVE" else "SERVER DOWN",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (running) AdminGreen else AdminRed,
                    letterSpacing = TextUnit(1f, TextUnitType.Sp)
                )
                Text(
                    text = if (running) "Serving on :$SERVER_PORT · background persistence armed"
                    else "Foreground service not running",
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminMuted
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "UPTIME",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminMuted
                )
                Text(
                    text = uptime,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AdminText,
                    fontFamily = FontFamily.Monospace
                )
                if (serverIp != null) {
                    Text(
                        text = "https://$serverIp:$SERVER_PORT",
                        style = MaterialTheme.typography.labelSmall,
                        color = AdminCyan,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminPairingCard(
    pairingCode: String?,
    certFingerprint: String?,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AdminCard),
        border = BorderStroke(1.dp, AdminCardBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "PAIRING CODE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AdminMuted,
                    letterSpacing = TextUnit(2f, TextUnitType.Sp)
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Rotate pairing code",
                        tint = AdminCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D141D), RoundedCornerShape(8.dp))
                    .border(1.dp, AdminCardBorder, RoundedCornerShape(8.dp))
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = pairingCode ?: "------",
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = TextUnit(6f, TextUnitType.Sp),
                    color = AdminCyan
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Enter this code in the Artemis dashboard to pair. Rotates every 5 minutes — never sent over the network.",
                style = MaterialTheme.typography.labelSmall,
                color = AdminMuted
            )
            if (certFingerprint != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "TLS PIN  ${certFingerprint.replace(":", "").take(16).chunked(4).joinToString(":")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminGreen,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

class AdminAction(val label: String, val onClick: () -> Unit)

@Composable
private fun AdminFeatureCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accent: Color,
    icon: () -> androidx.compose.ui.graphics.vector.ImageVector,
    actions: List<AdminAction>
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AdminCard),
        border = BorderStroke(1.dp, AdminCardBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon(),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AdminText
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = AdminMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { action ->
                        Button(
                            onClick = action.onClick,
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent.copy(alpha = 0.14f),
                                contentColor = accent
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = ButtonDefaults.ContentPadding
                        ) {
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
