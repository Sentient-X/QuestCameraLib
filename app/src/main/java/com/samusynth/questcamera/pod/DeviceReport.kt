package com.samusynth.questcamera.pod

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What only the app can read of the headset, for `sx_rig_report_device`:
 * `{"battery_percent": 0-100, "charging": bool, "network_time_synchronized": bool}`.
 *
 * - Battery and charging come from the sticky `ACTION_BATTERY_CHANGED` broadcast
 *   (BatteryManager extras); charging is "plugged in" — a full battery on the charger counts.
 * - `network_time_synchronized` is true only when `SystemClock.currentNetworkTimeClock()`
 *   (API 33+) answers, i.e. the platform holds a network time fix. Below API 33 it cannot be
 *   known and is false, as is an unavailable fix. AUTO_TIME alone says the device would
 *   sync, not that it has, so it never makes this true.
 * Returns null when the battery state is unavailable, so nothing false is reported as true.
 */
object DeviceReport {
    @JvmStatic
    fun json(context: Context): String? {
        val battery = context.applicationContext
            .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = (level * 100 / scale).coerceIn(0, 100)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return buildJsonObject {
            put("battery_percent", percent)
            put("charging", plugged)
            put("network_time_synchronized", networkTimeSynchronized())
        }.toString()
    }

    @JvmStatic
    fun networkTimeSynchronized(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching { SystemClock.currentNetworkTimeClock().millis() }.isSuccess
    }
}
