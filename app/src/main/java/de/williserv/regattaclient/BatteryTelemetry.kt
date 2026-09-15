package de.williserv.regattaclient

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

internal data class BatteryTelemetrySnapshot(
    val percent: Int?,
    val charging: Boolean?
)

internal object BatteryTelemetry {
    fun read(context: Context): BatteryTelemetrySnapshot {
        val intent = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return BatteryTelemetrySnapshot(percent = null, charging = null)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        return BatteryTelemetrySnapshot(
            percent = normalizePercent(level, scale),
            charging = chargingFromStatus(status)
        )
    }

    internal fun normalizePercent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        return ((level.toDouble() / scale.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    internal fun chargingFromStatus(status: Int): Boolean? {
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true

            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false

            else -> null
        }
    }
}
