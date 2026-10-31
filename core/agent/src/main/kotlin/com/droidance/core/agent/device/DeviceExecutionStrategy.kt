// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.device

import com.droidance.core.agent.device.*

data class DeviceModeSelection(
    val requestedMode: RuntimeMode?,
    val effectiveMode: RuntimeMode?,
    val unavailableReason: String? = null
)

data class DeviceExecutionStrategy(
    val selection: DeviceModeSelection,
    val spaceSelection: ExecutionSpaceSelection
)

object DeviceExecutionStrategyResolver {
    fun resolve(
        requestedMode: RuntimeMode?,
        requestedSpace: ExecutionSpace = ExecutionSpace.VIRTUAL_SPACE,
        resolution: DeviceModeResolution,
        capabilities: DeviceCapabilities
    ): DeviceExecutionStrategy {
        val mode = DeviceModeResolver.selectMode(requestedMode, resolution)
        val unavailable = if (requestedSpace != ExecutionSpace.VIRTUAL_SPACE || mode == null) {
            ExecutionSpaceUnavailableCause.NO_RUNTIME_MODE
        } else if (!capabilities.virtualSpaceBridgeAvailable) ExecutionSpaceUnavailableCause.STANDARD_SIDECAR else null
        return DeviceExecutionStrategy(
            DeviceModeSelection(requestedMode, mode, if (mode == null) "Standard execution is unavailable" else null),
            ExecutionSpaceSelection(requestedSpace, ExecutionSpace.VIRTUAL_SPACE, unavailable)
        )
    }
}
