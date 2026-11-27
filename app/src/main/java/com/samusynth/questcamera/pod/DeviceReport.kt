package com.samusynth.questcamera.pod

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What only the app can read of the headset, for `sx_rig_report_device`:
 * `{"battery_percent": 0-100, "charging": bool, "network_time_synchronized": bool,
 *   "battery": {"temperature_c", "current_ma", "status", "source"}}`.
 *
 * - Battery and charging come from the sticky `ACTION_BATTERY_CHANGED` broadcast
 *   (BatteryManager extras); charging is "plugged in" — a full battery on the charger counts.
 * - `battery` is what says whether the charger keeps up: the battery's temperature
 *   (EXTRA_TEMPERATURE, tenths of a degree), the current into it (BATTERY_PROPERTY_CURRENT_NOW,
 *   µA, as the OS signs it), what it is doing (EXTRA_STATUS: not charging while plugged in is the
 *   OS holding charge back, most often because it is too hot) and what it is plugged into
 *   (EXTRA_PLUGGED: a wall charger, a USB port or hub, wireless, a dock). Each is left out when
 *   the OS does not say it, never guessed.
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
        val pluggedAs = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return buildJsonObject {
            put("battery_percent", percent)
            put("charging", pluggedAs != 0)
            put("network_time_synchronized", networkTimeSynchronized())
            put("battery", readings(context, battery, pluggedAs))
        }.toString()
    }

    /** The device report's `battery`: each reading the OS gave, nothing it did not. */
    private fun readings(context: Context, battery: Intent, pluggedAs: Int): JsonObject {
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val status = when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            else -> null
        }
        val source = when (pluggedAs) {
            0 -> "none"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BATTERY_PLUGGED_DOCK -> "dock"
            else -> null
        }
        val microamps = runCatching {
            (context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()
        return buildJsonObject {
            // Android answers Int.MIN_VALUE for a property it does not keep.
            if (tenths != Int.MIN_VALUE && tenths in -400..1500) put("temperature_c", tenths / 10.0)
            if (microamps != null && microamps != Int.MIN_VALUE && microamps in -30_000_000..30_000_000) {
                put("current_ma", microamps / 1000)
            }
            if (status != null) put("status", status)
            if (source != null) put("source", source)
        }
    }

    /** BatteryManager.BATTERY_PLUGGED_DOCK, API 33. */
    private const val BATTERY_PLUGGED_DOCK = 8

    @JvmStatic
    fun networkTimeSynchronized(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching { SystemClock.currentNetworkTimeClock().millis() }.isSuccess
    }
}
