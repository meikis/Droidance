// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryContextRefreshTest {
    @Test fun eachAcceptedInputOwnsOneIdempotentSnapshotAndOldQueriesCannotBeRelabelled() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("memory-input-owner", "Original goal", profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        val payload = MemoryContextLoadedEventPayload(1, "a".repeat(64), emptyList())
        val first = fixture.eventStore.recordMemoryContextLoaded(started.active.scope, payload)
        assertEquals(first.eventId, fixture.eventStore.recordMemoryContextLoaded(started.active.scope, payload).eventId)
        val update = fixture.eventStore.recordUserSteer(started.active.scope, "update", "Additional restriction")
        try {
            fixture.eventStore.recordMemoryContextLoaded(started.active.scope, payload)
            fail("An initial query must not be saved as the updated input's snapshot")
        } catch (_: IllegalArgumentException) { }
        val refreshed = fixture.eventStore.recordMemoryContextLoaded(started.active.scope,
            payload.copy(queryHash = "b".repeat(64)), update.sequence)
        assertNotEquals(first.eventId, refreshed.eventId)
        assertEquals(refreshed.eventId, fixture.eventStore.recordMemoryContextLoaded(started.active.scope,
            payload.copy(queryHash = "b".repeat(64)), update.sequence).eventId)
        val packageRefresh = fixture.eventStore.recordMemoryContextLoaded(started.active.scope,
            payload.copy(queryHash = "c".repeat(64), contextKey = "package:com.example.shop"), update.sequence)
        assertNotEquals(refreshed.eventId, packageRefresh.eventId)
        assertEquals(packageRefresh.eventId, fixture.eventStore.recordMemoryContextLoaded(started.active.scope,
            payload.copy(queryHash = "c".repeat(64), contextKey = "package:com.example.shop"), update.sequence).eventId)
        assertNotNull(fixture.eventStore.threadReadModel(started.active.scope.threadId))
    }
}
