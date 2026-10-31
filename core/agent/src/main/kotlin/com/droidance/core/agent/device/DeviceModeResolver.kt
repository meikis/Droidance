// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.device

import com.droidance.core.agent.device.*

object DeviceModeResolver {
    fun resolve(capabilities: DeviceCapabilities): DeviceModeResolution {
        val modes = if (capabilities.standardReady) setOf(RuntimeMode.STANDARD) else emptySet()
        val missing = buildList {
            if (!capabilities.adbBridgeAvailable && !capabilities.virtualSpaceBridgeAvailable) add(DeviceRequirement.WIRELESS_DEBUGGING)
            if (!capabilities.textObservationAvailable) add(DeviceRequirement.SCREEN_OBSERVATION)
        }
        return DeviceModeResolution(modes, modes.firstOrNull(),
            mapOf(RuntimeMode.STANDARD to missing))
    }
    fun selectMode(preferredMode: RuntimeMode?, resolution: DeviceModeResolution): RuntimeMode? =
        RuntimeMode.STANDARD.takeIf { (preferredMode == null || preferredMode == it) && it in resolution.runnableModes }
}
