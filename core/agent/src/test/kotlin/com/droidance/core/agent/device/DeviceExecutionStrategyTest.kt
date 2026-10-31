// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.device

import com.droidance.core.agent.device.*
import org.junit.Assert.*
import org.junit.Test

class DeviceExecutionStrategyTest {
    private val ready = DeviceCapabilities(adbBridgeAvailable = true, virtualSpaceBridgeAvailable = true)
    private fun resolve(mode: RuntimeMode?, space: ExecutionSpace, capabilities: DeviceCapabilities = ready) =
        DeviceExecutionStrategyResolver.resolve(mode, space, DeviceModeResolver.resolve(capabilities), capabilities)
    @Test fun defaultRouteIsStandardVirtualSpace() {
        val result = resolve(null, ExecutionSpace.VIRTUAL_SPACE)
        assertEquals(RuntimeMode.STANDARD, result.selection.effectiveMode)
        assertEquals(ExecutionSpace.VIRTUAL_SPACE, result.spaceSelection.effectiveSpace)
        assertNull(result.spaceSelection.unavailableReason)
    }
    @Test fun archivedMainScreenRequestCannotExecute() {
        assertNotNull(resolve(RuntimeMode.STANDARD, ExecutionSpace.MAIN_SCREEN).spaceSelection.unavailableReason)
    }
    @Test fun archivedUltraRequestCannotExecute() {
        assertNull(resolve(RuntimeMode.ULTRA, ExecutionSpace.VIRTUAL_SPACE).selection.effectiveMode)
    }
    @Test fun missingVirtualChannelNeverFallsBackToPhysicalScreen() {
        val result = resolve(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE, DeviceCapabilities(adbBridgeAvailable = true))
        assertEquals(ExecutionSpace.VIRTUAL_SPACE, result.spaceSelection.effectiveSpace)
        assertEquals(ExecutionSpaceUnavailableCause.STANDARD_SIDECAR, result.spaceSelection.unavailableCause)
    }
}
