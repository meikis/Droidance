// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.device

import com.droidance.core.agent.device.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class RuntimeMode(val displayName: String, val eventIdentifier: String) {
    // EventStore wire identifiers stay stable; product names are independent of stored evidence.
    @SerialName("AIR") STANDARD("Standard", "AIR"),
    // Historical event identifier only; no Ultra runtime is shipped.
    @SerialName("PRO") ULTRA("Ultra", "PRO")
}

@Serializable
enum class ExecutionSpace {
    // Historical event identifier only; new execution is virtual-space only.
    MAIN_SCREEN,
    VIRTUAL_SPACE
}

data class DeviceCapabilities(
    val adbBridgeAvailable: Boolean = false,
    val inputInjectionReady: Boolean = false,
    val visionProviderAvailable: Boolean = false,
    val textObservationAvailable: Boolean = true,
    val virtualSpaceBridgeAvailable: Boolean = false
) {

    // 实时 ADB 连接(adbBridgeAvailable)断开(如 WiFi 关闭)后，只要配对期间建立的常驻 sidecar
    // binder(virtualSpaceBridgeAvailable)还活着，AIR 仍应可运行——这条线路本机 Binder IPC 不
    // 经 ADB。之前只看 adbBridgeAvailable，导致 WiFi 一断即使 sidecar 完好也判定"无可用通道"，
    // 让常驻通道机制形同虚设。
    val standardReady: Boolean
        get() = (adbBridgeAvailable || virtualSpaceBridgeAvailable) && textObservationAvailable
}

enum class DeviceRequirement(val diagnostic: String) {
    WIRELESS_DEBUGGING("Wireless debugging is unavailable"),
    SCREEN_OBSERVATION("Screen observation is unavailable"),
}

enum class ExecutionSpaceUnavailableCause(val diagnostic: String) {
    STANDARD_SIDECAR("Standard virtual-space sidecar is unavailable"),
    NO_RUNTIME_MODE("No runtime mode is available")
}

data class DeviceModeResolution(
    val runnableModes: Set<RuntimeMode>,
    val recommendedMode: RuntimeMode?,
    val missingRequirements: Map<RuntimeMode, List<DeviceRequirement>>
)

data class ExecutionSpaceSelection(
    val requestedSpace: ExecutionSpace,
    val effectiveSpace: ExecutionSpace,
    val unavailableCause: ExecutionSpaceUnavailableCause? = null
) {
    val unavailableReason: String?
        get() = unavailableCause?.let { "[BACKGROUND_DISPLAY_UNAVAILABLE] ${it.diagnostic}" }
}
