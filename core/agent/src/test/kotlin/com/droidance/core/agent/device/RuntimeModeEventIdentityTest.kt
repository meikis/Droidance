// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.device

import com.droidance.core.agent.device.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.droidance.core.agent.attempt.CapabilitySnapshot
import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.attempt.ExecutionBackendKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeModeEventIdentityTest {
    @Test
    fun productRenameKeepsImmutableEventIdentifiers() {
        assertEquals("\"AIR\"", Json.encodeToString(RuntimeMode.STANDARD))
        assertEquals("\"PRO\"", Json.encodeToString(RuntimeMode.ULTRA))
        assertEquals(RuntimeMode.STANDARD, Json.decodeFromString<RuntimeMode>("\"AIR\""))
        assertEquals(RuntimeMode.ULTRA, Json.decodeFromString<RuntimeMode>("\"PRO\""))
        assertEquals("AIR", RuntimeMode.STANDARD.eventIdentifier)
        assertEquals("PRO", RuntimeMode.ULTRA.eventIdentifier)
    }

    @Test
    fun historicalCapabilityReceiptHashSurvivesProductRename() {
        val receipt = CapabilitySnapshot.create(
            capabilitySnapshotRef = "history-snapshot",
            runtimeMode = RuntimeMode.ULTRA,
            executionSpace = ExecutionSpace.MAIN_SCREEN,
            displayId = 0,
            backendKind = ExecutionBackendKind.ROOT_BACKEND,
            backendSessionId = "history-root",
            backendAvailable = true,
            capabilities = setOf(DeviceCapability.OBSERVE_SCREEN)
        )
        assertEquals("c89c5df750dcc7a074dda79dd6b4598c8ffbc759116faff14eb571fda1790616", receipt.capabilitySnapshotHash)
    }
}
