package com.example.artemis.feature

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.NetworkInterface
import java.util.Locale

@Serializable
data class DeviceInfo(
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val buildId: String,
    val buildFingerprint: String,
    val screenSize: String,
    val screenDensity: Int,
    val displayWidth: Int,
    val displayHeight: Int
)

@Serializable
data class BatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val health: String,
    val technology: String,
    val temperature: Float,
    val voltage: Int,
    val capacity: Int = 0
)

@Serializable
data class NetworkInfo(
    val wifiSsid: String,
    val ipAddress: String,
    val signalStrength: Int,
    val linkSpeed: Int,
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isMobile: Boolean,
    val networkType: String,
    val macAddress: String
)

@Serializable
data class StorageInfo(
    val internalTotalMB: Long,
    val internalFreeMB: Long,
    val internalUsedMB: Long,
    val externalAvailable: Boolean,
    val externalTotalMB: Long? = null,
    val externalFreeMB: Long? = null
)

class DeviceInfoProvider(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun getFullDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        val metrics = context.resources.displayMetrics

        DeviceInfo(
            deviceName = getDeviceName(),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            apiLevel = Build.VERSION.SDK_INT,
            buildId = Build.DISPLAY,
            buildFingerprint = Build.FINGERPRINT,
            screenSize = "${metrics.widthPixels}x${metrics.heightPixels}",
            screenDensity = metrics.densityDpi,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels
        )
    }

    fun getBatteryInfo(): BatteryInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(null, intentFilter)

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = (level * 100) / scale

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val health = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            else -> "unknown"
        }

        val temperature = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val technology = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"

        return BatteryInfo(
            level = batteryPct,
            isCharging = isCharging,
            health = health,
            technology = technology,
            temperature = temperature,
            voltage = voltage
        )
    }

    fun getNetworkInfo(): NetworkInfo {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isMobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val networkType = when {
            isWifi -> "WiFi"
            isMobile -> "Mobile"
            else -> "Unknown"
        }

        var wifiSsid = ""
        var signalStrength = 0
        var linkSpeed = 0
        var ipAddress = ""
        var macAddress = ""

        if (isWifi) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val connectionInfo = wifiManager.connectionInfo
                wifiSsid = connectionInfo.ssid ?: "<unknown>"
                signalStrength = wifiManager.calculateSignalLevel(connectionInfo.rssi)
                linkSpeed = connectionInfo.linkSpeed
                ipAddress = intToIp(connectionInfo.ipAddress)
            } catch (_: Exception) { }
        }

        // Get MAC address from NetworkInterface
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name == "wlan0" || networkInterface.name.startsWith("eth")) {
                    val macBytes = networkInterface.hardwareAddress
                    if (macBytes != null) {
                        macAddress = macBytes.joinToString(":") { String.format("%02X", it) }
                    }
                    break
                }
            }
        } catch (_: Exception) { }

        return NetworkInfo(
            wifiSsid = wifiSsid,
            ipAddress = ipAddress,
            signalStrength = signalStrength,
            linkSpeed = linkSpeed,
            isConnected = isConnected,
            isWifi = isWifi,
            isMobile = isMobile,
            networkType = networkType,
            macAddress = macAddress
        )
    }

    fun getStorageInfo(): StorageInfo {
        val internalPath = Environment.getDataDirectory()
        val internalStat = StatFs(internalPath.path)
        val internalBlockSize = internalStat.blockSizeLong
        val internalTotalBlocks = internalStat.blockCountLong
        val internalFreeBlocks = internalStat.availableBlocksLong

        val internalTotalMB = (internalTotalBlocks * internalBlockSize) / (1024 * 1024)
        val internalFreeMB = (internalFreeBlocks * internalBlockSize) / (1024 * 1024)
        val internalUsedMB = internalTotalMB - internalFreeMB

        // Check external storage
        val externalAvailable = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        var externalTotalMB: Long? = null
        var externalFreeMB: Long? = null

        if (externalAvailable) {
            try {
                val externalPath = Environment.getExternalStorageDirectory()
                val externalStat = StatFs(externalPath.path)
                val externalBlockSize = externalStat.blockSizeLong
                externalTotalMB = (externalStat.blockCountLong * externalBlockSize) / (1024 * 1024)
                externalFreeMB = (externalStat.availableBlocksLong * externalBlockSize) / (1024 * 1024)
            } catch (_: Exception) { }
        }

        return StorageInfo(
            internalTotalMB = internalTotalMB,
            internalFreeMB = internalFreeMB,
            internalUsedMB = internalUsedMB,
            externalAvailable = externalAvailable,
            externalTotalMB = externalTotalMB,
            externalFreeMB = externalFreeMB
        )
    }

    /** Get a human-readable device name */
    private fun getDeviceName(): String {
        val bluetoothName = Settings.Secure.getString(
            context.contentResolver,
            "bluetooth_name"
        )
        if (!bluetoothName.isNullOrBlank()) return bluetoothName

        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun intToIp(ip: Int): String {
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            (ip shr 0) and 0xFF,
            (ip shr 8) and 0xFF,
            (ip shr 16) and 0xFF,
            (ip shr 24) and 0xFF
        )
    }
}
