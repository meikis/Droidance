// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PauseAwareToolExecutionTest {
    @Test fun nativePermissionWaitCanSettleWithoutBeingMistakenForToolInterruption() = runBlocking {
        val paused = MutableStateFlow(false)
        val result = PauseAwareToolExecution.run(paused, shouldInterrupt = { false }) {
            paused.value = true
            yield()
            paused.value = false
            7
        }
        assertEquals(7, result)
    }

    @Test fun pausedTaskDoesNotStartAnOperation() = runBlocking {
        var calls = 0
        try {
            PauseAwareToolExecution.run(MutableStateFlow(true)) { calls++ }
            fail("paused operation ran")
        } catch (_: ManualToolPauseException) { }
        assertEquals(0, calls)
    }

    @Test fun pauseCancelsChildAndLeavesOwnerWaitingWithoutReplay() = runBlocking {
        val paused = MutableStateFlow(false)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var effects = 0
        var replans = 0
        val owner = launch {
            try {
                PauseAwareToolExecution.run(paused) {
                    effects++
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
            } catch (_: ManualToolPauseException) { }
            paused.first { !it }
            replans++
        }
        started.await()
        paused.value = true
        stopped.await()
        yield()
        assertTrue(owner.isActive)
        assertEquals(1, effects)
        assertEquals(0, replans)
        paused.value = false
        owner.join()
        assertEquals(1, effects)
        assertEquals(1, replans)
    }

    @Test fun taskCancellationIsNeverConvertedIntoManualPause() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var converted = false
        val owner = launch {
            try {
                PauseAwareToolExecution.run(MutableStateFlow(false)) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (_: ManualToolPauseException) { converted = true }
        }
        started.await()
        owner.cancelAndJoin()
        assertFalse(converted)
    }
}
