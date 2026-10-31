// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.content.Context
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.ui.overlay.RuntimeIslandPresentation
import com.droidance.ui.overlay.RuntimeSurfaceBitmapRenderer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileUseReadInstrumentedTest {
    @Test fun nativeReadWithoutExecutionDoesNotInventDisplayOwnership() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = Json.parseToJsonElement(DeviceInfoReader(context, ExecutionContextStore()).read().substringAfter('\n')).jsonObject
        assertFalse(payload.getValue("execution_binding_present").jsonPrimitive.boolean)
        assertEquals(JsonNull, payload["display_id"])
        assertEquals(JsonNull, payload["execution_space"])
        assertEquals(JsonNull, payload["backend_session_id"])
        assertEquals(android.os.Build.MODEL, payload.getValue("model").jsonPrimitive.content)
    }

    @Test fun standardNativeMediaAndStorageReadNeedsNoLiveDisplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ExecutionContextStore()
        store.start(ExecutionContext.fromStrategy("standard-native-read", "unavailable-display",
            DeviceExecutionStrategy(DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
                ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE)), 999999))
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val beforeVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val beforeAvailable = context.filesDir.usableSpace
        val payload = Json.parseToJsonElement(DeviceInfoReader(context, store).read().substringAfter('\n')).jsonObject
        assertEquals("STANDARD", payload.getValue("runtime_mode").jsonPrimitive.content)
        assertEquals("platform_stream_index", payload.getValue("media_volume_unit").jsonPrimitive.content)
        assertEquals(beforeVolume, payload.getValue("media_volume_index").jsonPrimitive.int)
        assertEquals(audio.getStreamMinVolume(android.media.AudioManager.STREAM_MUSIC),
            payload.getValue("media_volume_min_index").jsonPrimitive.int)
        assertEquals(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
            payload.getValue("media_volume_max_index").jsonPrimitive.int)
        assertEquals(audio.isStreamMute(android.media.AudioManager.STREAM_MUSIC),
            payload.getValue("media_muted").jsonPrimitive.boolean)
        assertEquals("app_internal_data_volume", payload.getValue("storage_scope").jsonPrimitive.content)
        assertEquals(context.filesDir.totalSpace, payload.getValue("storage_total_bytes").jsonPrimitive.long)
        assertTrue(kotlin.math.abs(beforeAvailable - payload.getValue("storage_available_bytes").jsonPrimitive.long) < 64L * 1024 * 1024)
        java.io.File(context.filesDir, "standard-native-read.json").writeText(payload.toString())
    }

    @Test fun nativeDeviceDataDoesNotNeedDisplayOrBackend() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ExecutionContextStore()
        val strategy = DeviceExecutionStrategy(DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE))
        store.start(ExecutionContext.fromStrategy("read-probe", "probe", strategy, 999999))
        val before = System.currentTimeMillis()
        val payload = Json.parseToJsonElement(DeviceInfoReader(context, store).read().substringAfter('\n')).jsonObject
        assertEquals(android.os.Build.MODEL, payload.getValue("model").jsonPrimitive.content)
        assertEquals("android_platform_api", payload.getValue("source_kind").jsonPrimitive.content)
        assertEquals(999999, payload.getValue("display_id").jsonPrimitive.int)
        assertTrue(payload.getValue("captured_at_ms").jsonPrimitive.long >= before)
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))!!
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        assertEquals(status, payload.getValue("battery_status_code").jsonPrimitive.int)
        assertEquals(status == android.os.BatteryManager.BATTERY_STATUS_CHARGING,
            payload.getValue("battery_charging").jsonPrimitive.boolean)
        assertEquals(intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
            payload.getValue("battery_temperature_celsius").jsonPrimitive.double, 0.1)
        assertEquals(intent.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 1),
            payload.getValue("battery_health_code").jsonPrimitive.int)
        assertEquals(intent.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0),
            payload.getValue("battery_voltage_mv").jsonPrimitive.int)
        assertNotNull(java.time.ZonedDateTime.parse(payload.getValue("device_time").jsonPrimitive.content))
        assertTrue(payload.containsKey("network_addresses"))
        assertTrue(payload.containsKey("network_gateways"))
        assertNotNull(payload.getValue("active_network_present").jsonPrimitive.booleanOrNull)
        val capacity = payload.getValue("battery_percent")
        assertTrue(capacity == JsonNull || capacity.jsonPrimitive.int in 0..100)
    }

    @Test fun platformAcceptsArgumentFreeSystemQuery() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(SystemStateQuery("battery").command)
        ).bufferedReader().use { it.readText() }
        assertTrue(output.isNotBlank())
        assertTrue(output.contains("level:"))
    }

    @Test fun islandPngDimensionsMatchMetadataAfterRepeatedRendering() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val renderer = RuntimeSurfaceBitmapRenderer(context)
        repeat(10) { frame ->
            val result = renderer.renderIsland("读取设备状态",
                RuntimeSurfaceBitmapRenderer.IslandAction.OPEN, false,
                RuntimeIslandPresentation.State.EXECUTING, frame / 10f)
            val bitmap = BitmapFactory.decodeByteArray(result.png, 0, result.png.size)
            try {
                assertEquals(bitmap.width, result.widthPx)
                assertEquals(bitmap.height, result.heightPx)
            } finally { bitmap.recycle() }
        }
    }
}
