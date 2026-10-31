// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.device

import com.droidance.core.agent.device.*
import org.junit.Assert.*
import org.junit.Test

class DeviceModeResolverTest {
    @Test fun unavailableChannelNeverEnablesRoot() {
        val result = DeviceModeResolver.resolve(DeviceCapabilities())
        assertTrue(result.runnableModes.isEmpty())
        assertNull(result.recommendedMode)
        assertNull(DeviceModeResolver.selectMode(RuntimeMode.ULTRA, result))
    }
    @Test fun residentSidecarWorksWithoutLiveAdbOrRoot() {
        val result = DeviceModeResolver.resolve(DeviceCapabilities(virtualSpaceBridgeAvailable = true))
        assertEquals(setOf(RuntimeMode.STANDARD), result.runnableModes)
        assertEquals(RuntimeMode.STANDARD, result.recommendedMode)
        assertTrue(result.missingRequirements.getValue(RuntimeMode.STANDARD).isEmpty())
    }
    @Test fun liveAdbCanPrepareStandardButCannotEnableAnArchivedMode() {
        val result = DeviceModeResolver.resolve(DeviceCapabilities(adbBridgeAvailable = true))
        assertEquals(RuntimeMode.STANDARD, DeviceModeResolver.selectMode(null, result))
        assertNull(DeviceModeResolver.selectMode(RuntimeMode.ULTRA, result))
    }
    @Test fun observationRemainsRequired() {
        val result = DeviceModeResolver.resolve(DeviceCapabilities(adbBridgeAvailable = true, textObservationAvailable = false))
        assertTrue(result.runnableModes.isEmpty())
        assertTrue(DeviceRequirement.SCREEN_OBSERVATION in result.missingRequirements.getValue(RuntimeMode.STANDARD))
    }
}
