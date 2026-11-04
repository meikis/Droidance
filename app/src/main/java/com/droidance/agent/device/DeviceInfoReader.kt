// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject

@Singleton
class DeviceInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executionContextStore: ExecutionContextStore
) {
    fun read(): String {
        val execution = executionContextStore.current()
        val native = captureNativeDeviceState(context)
        val payload = buildJsonObject {
            native.forEach { (key, value) -> put(key, value) }
            put("runtime_mode", JsonPrimitive(execution?.mode?.name ?: "NATIVE"))
            put("execution_binding_present", JsonPrimitive(execution != null))
            put("execution_space", execution?.space?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("display_id", execution?.displayId?.let(::JsonPrimitive) ?: JsonNull)
            put("backend_session_id", execution?.backendSessionId?.let(::JsonPrimitive) ?: JsonNull)
        }
        return "[ANDROID_DEVICE_RESULT]\n$payload"
    }
}

/** Device-wide platform data; it does not acquire a display or an execution backend. */
internal fun captureNativeDeviceState(context: Context): kotlinx.serialization.json.JsonObject {
    val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val capacity = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val statusName = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        else -> null
    }
    val temperature = batteryIntent?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
    val plugged = batteryIntent?.takeIf { it.hasExtra(BatteryManager.EXTRA_PLUGGED) }
        ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val health = batteryIntent?.takeIf { it.hasExtra(BatteryManager.EXTRA_HEALTH) }
        ?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
    val healthName = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> null
    }
    val voltage = batteryIntent?.takeIf { it.hasExtra(BatteryManager.EXTRA_VOLTAGE) }
        ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork
    val networkCapabilities = network?.let(connectivity::getNetworkCapabilities)
    val linkProperties = network?.let(connectivity::getLinkProperties)
    val audio = context.getSystemService(AudioManager::class.java)
    val mediaVolume = runCatching {
        listOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMinVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }.getOrNull()
    val mediaMuted = runCatching { audio.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull()
    val storage = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
    val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    @Suppress("DEPRECATION")
    val displayMetrics = display?.let { current ->
        android.util.DisplayMetrics().also { current.getRealMetrics(it) }
    }
    val displayMode = display?.mode
    return buildJsonObject {
        put("source_kind", JsonPrimitive("android_platform_api"))
        put("captured_at_ms", JsonPrimitive(System.currentTimeMillis()))
        put("scope", JsonPrimitive("device"))
        put("manufacturer", JsonPrimitive(Build.MANUFACTURER))
        put("model", JsonPrimitive(Build.MODEL))
        put("android_version", JsonPrimitive(Build.VERSION.RELEASE))
        put("api_level", JsonPrimitive(Build.VERSION.SDK_INT))
        put("build_id", JsonPrimitive(Build.ID))
        put("screen_scope", JsonPrimitive("device_default_display"))
        put("screen_display_id", display?.displayId?.let(::JsonPrimitive) ?: JsonNull)
        put("screen_effective_source", displayMetrics?.let { JsonPrimitive("Display.getRealMetrics") } ?: JsonNull)
        put("screen_effective_scope", displayMetrics?.let { JsonPrimitive("largest_app_accessible_display_region") } ?: JsonNull)
        put("screen_effective_width_px", displayMetrics?.widthPixels?.let(::JsonPrimitive) ?: JsonNull)
        put("screen_effective_height_px", displayMetrics?.heightPixels?.let(::JsonPrimitive) ?: JsonNull)
        put("screen_density_dpi", displayMetrics?.densityDpi?.let(::JsonPrimitive) ?: JsonNull)
        put("screen_density_scale", displayMetrics?.density?.let(::JsonPrimitive) ?: JsonNull)
        put("screen_density_qualifier", displayMetrics?.densityDpi?.let(::standardDensityQualifier)?.let(::JsonPrimitive) ?: JsonNull)
        put("screen_mode_source", displayMode?.let { JsonPrimitive("Display.getMode") } ?: JsonNull)
        put("screen_mode_scope", displayMode?.let { JsonPrimitive("reported_current_display_mode") } ?: JsonNull)
        put("screen_mode_width_px", displayMode?.physicalWidth?.let(::JsonPrimitive) ?: JsonNull)
        put("screen_mode_height_px", displayMode?.physicalHeight?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "battery_percent",
            capacity.takeIf { it in 0..100 }?.let(::JsonPrimitive) ?: JsonNull
        )
        put("battery_status_code", status?.takeIf { it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
        put("battery_status", statusName?.let(::JsonPrimitive) ?: JsonNull)
        put("battery_charging", statusName?.let {
            JsonPrimitive(status == BatteryManager.BATTERY_STATUS_CHARGING)
        } ?: JsonNull)
        put("battery_power_connected", plugged?.let { JsonPrimitive(it != 0) } ?: JsonNull)
        put("battery_temperature_celsius", temperature?.let { JsonPrimitive(it / 10.0) } ?: JsonNull)
        put("battery_health_code", health?.let(::JsonPrimitive) ?: JsonNull)
        put("battery_health", healthName?.let(::JsonPrimitive) ?: JsonNull)
        put("battery_voltage_mv", voltage?.let(::JsonPrimitive) ?: JsonNull)
        put("media_volume_index", mediaVolume?.get(0)?.let(::JsonPrimitive) ?: JsonNull)
        put("media_volume_min_index", mediaVolume?.get(1)?.let(::JsonPrimitive) ?: JsonNull)
        put("media_volume_max_index", mediaVolume?.get(2)?.let(::JsonPrimitive) ?: JsonNull)
        put("media_volume_unit", JsonPrimitive("platform_stream_index"))
        put("media_muted", mediaMuted?.let(::JsonPrimitive) ?: JsonNull)
        put("storage_scope", JsonPrimitive("app_internal_data_volume"))
        put("storage_total_bytes", storage?.totalBytes?.let(::JsonPrimitive) ?: JsonNull)
        put("storage_available_bytes", storage?.availableBytes?.let(::JsonPrimitive) ?: JsonNull)
        put("device_time", JsonPrimitive(java.time.ZonedDateTime.now().toString()))
        put("next_alarm_clock", captureNextAlarmClock(context))
        put("network_interface", linkProperties?.interfaceName?.let(::JsonPrimitive) ?: JsonNull)
        put("network_addresses", linkProperties?.let { links -> kotlinx.serialization.json.JsonArray(
            links.linkAddresses.map { JsonPrimitive(it.toString()) }) } ?: JsonNull)
        put("network_gateways", linkProperties?.let { links -> kotlinx.serialization.json.JsonArray(
            links.routes.mapNotNull { it.gateway?.hostAddress }.distinct().map(::JsonPrimitive)) } ?: JsonNull)
        put("active_network_present", JsonPrimitive(network != null))
        put("network_capabilities_available", JsonPrimitive(networkCapabilities != null))
        put("network_validated", networkCapabilities?.let {
            JsonPrimitive(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        } ?: JsonNull)
        put("network_wifi", networkCapabilities?.let {
            JsonPrimitive(it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        } ?: JsonNull)
        put("network_cellular", networkCapabilities?.let {
            JsonPrimitive(it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        } ?: JsonNull)
        put("network_vpn", networkCapabilities?.let {
            JsonPrimitive(it.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        } ?: JsonNull)
    }
}

/** Public API since level 21; no exact-alarm scheduling grant is needed for this read. */
internal fun captureNextAlarmClock(context: Context): kotlinx.serialization.json.JsonObject {
    val result = runCatching {
        requireNotNull(context.getSystemService(android.app.AlarmManager::class.java)).nextAlarmClock
    }
    return buildJsonObject {
        put("source", JsonPrimitive("AlarmManager.getNextAlarmClock"))
        put("scope", JsonPrimitive("next_scheduled_alarm_clock_for_current_user"))
        put("status", JsonPrimitive(when {
            result.isFailure -> "unavailable"
            result.getOrNull() == null -> "none"
            else -> "scheduled"
        }))
        put("trigger_at_ms", result.getOrNull()?.triggerTime?.let(::JsonPrimitive) ?: JsonNull)
        result.exceptionOrNull()?.let { put("error_type", JsonPrimitive(it.javaClass.simpleName)) }
    }
}

/** Exact Android resource qualifiers only; non-standard logical densities remain unclassified. */
internal fun standardDensityQualifier(dpi: Int): String? = when (dpi) {
    android.util.DisplayMetrics.DENSITY_LOW -> "ldpi"
    android.util.DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
    android.util.DisplayMetrics.DENSITY_TV -> "tvdpi"
    android.util.DisplayMetrics.DENSITY_HIGH -> "hdpi"
    android.util.DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
    android.util.DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
    android.util.DisplayMetrics.DENSITY_XXXHIGH -> "xxxhdpi"
    else -> null
}
