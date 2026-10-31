// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextEventContractTest {
    @Test
    fun memoryLoadEventCarriesOnlyBoundedReferencesAndSourceEvidence() {
        val payload = MemoryContextLoadedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            queryHash = "b".repeat(64),
            items = listOf(
                MemoryContextItemPayload(
                    ref = "profile:preferred_address",
                    scope = MemoryContextScope.CORE_PROFILE,
                    summary = "Address the user as Alex",
                    sourceThroughSequence = 42L
                )
            )
        )

        assertTrue(payload.items.single().sourceThroughSequence == 42L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun memoryLoadEventRejectsAnUnboundedSummary() {
        MemoryContextItemPayload(
            ref = "profile:oversized",
            scope = MemoryContextScope.RELATED_PROFILE,
            summary = "x".repeat(501)
        )
    }
}
