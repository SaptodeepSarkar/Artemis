package com.example.artemis.feature

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.Serializable

@Serializable
data class BatterySnapshot(
    val levelPercent: Int,
    val isCharging: Boolean,
    val chargeSource: String,
    val status: String,
    val health: String,
    val temperatureC: Float,
    val voltageMv: Int,
    val technology: String,
    val plugged: String
)

/**
 * Battery helper (v2.3.0) — plain data read for the dashboard's live view
 * header. No HTTP, no Activity; takes only a Context (application or
 * service). Values come from the sticky battery broadcast, so this works
 * FGS-only with the app UI closed.
 */
class BatteryHelper(private val context: Context) {

    fun read(): BatterySnapshot {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""

        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        return BatterySnapshot(
            levelPercent = percent,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            chargeSource = when {
                plugged == BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                plugged == BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                plugged == BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
                else -> "none"
            },
            status = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            },
            health = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            },
            temperatureC = temperature / 10.0f,
            voltageMv = voltage,
            technology = technology,
            plugged = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
                else -> "none"
            }
        )
    }
}
