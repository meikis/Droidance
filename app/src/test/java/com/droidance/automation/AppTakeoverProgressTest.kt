// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppTakeoverProgressTest {
    private fun watch() = AppTakeoverWatch(
        packageName = "example.app", instruction = "Observe the authorized goal",
        pollIntervalMs = 60_000L, createdAtMs = 1_000L, updatedAtMs = 2_000L,
        nextPollAtMs = 3_000L, nextCheckAtMs = null
    )

    @Test fun liveFactsDistinguishAnUnsavedCheckpointFromAPlannedRecheck() {
        val first = takeoverProgressFacts(watch())
        assertFalse(first.getValue("checkpoint_saved").jsonPrimitive.boolean)
        assertEquals(0, first.getValue("checkpoint_chars").jsonPrimitive.int)
        assertEquals(JsonNull, first["next_check_at_ms"])
        val saved = takeoverProgressFacts(watch().copy(
            checkpoint = "Object state and exact evidence refs",
            lastSummary = "A pending; C observed", nextCheckAtMs = 90_000L
        ))
        assertTrue(saved.getValue("checkpoint_saved").jsonPrimitive.boolean)
        assertEquals("Object state and exact evidence refs".length,
            saved.getValue("checkpoint_chars").jsonPrimitive.int)
        assertEquals("A pending; C observed", saved.getValue("last_saved_summary").jsonPrimitive.content)
        assertEquals(90_000L, saved.getValue("next_check_at_ms").jsonPrimitive.long)
    }

    @Test fun expiryUsesTheFullSavedCheckpointRatherThanAHealthCheckSummary() {
        val checkpoint = "Retained state;".repeat(1_000)
        assertEquals(checkpoint, savedTakeoverProgress(watch().copy(
            checkpoint = checkpoint, lastSummary = "Screen unchanged"
        )))
        assertEquals("Last known progress", savedTakeoverProgress(watch().copy(lastSummary = "Last known progress")))
        assertEquals("", savedTakeoverProgress(watch()))
    }
}
