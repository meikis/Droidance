// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecutionContextStoreTest {
    @Test
    fun contextCannotChangeUntilMatchingExecutionFinishes() {
        val store = ExecutionContextStore()
        val first = context("task-1", "sidecar-7", RuntimeMode.STANDARD, 96)

        store.start(first)

        assertThrows(IllegalStateException::class.java) {
            store.start(context("task-2", "sidecar-8", RuntimeMode.STANDARD, 96))
        }
        assertEquals(first, store.requireCurrent())

        store.finish("task-1")
        assertNull(store.current())
    }

    @Test
    fun wrongExecutionCannotClearActiveContext() {
        val store = ExecutionContextStore()
        val first = context("task-1", "sidecar-7", RuntimeMode.STANDARD, 96)
        store.start(first)

        assertThrows(IllegalStateException::class.java) { store.finish("task-2") }
        assertEquals(first, store.requireCurrent())
    }

    @Test fun returnTargetBelongsToTheExactExecutionAndSurvivesAnUnavailableObservation() {
        val store = ExecutionContextStore()
        val first = context("task-1", "sidecar-7", RuntimeMode.STANDARD, 96)
        store.start(first)
        store.recordForeground(first, "example.app/.Editor")
        store.recordForeground(first, null)
        store.recordForeground(first.copy(backendSessionId = "stale"), "other.app/.Main")
        assertEquals("example.app", store.foregroundPackage(first))
        assertNull(store.foregroundPackage(first.copy(executionId = "other")))
        store.finish(first.executionId)
        val second = context("task-2", "sidecar-7", RuntimeMode.STANDARD, 96)
        store.start(second)
        assertNull(store.foregroundPackage(second))
    }

    @Test fun retiredRoutesCannotBecomeActive() {
        for (retired in listOf(context("root", "legacy", RuntimeMode.ULTRA, 96),
            context("main", "legacy", RuntimeMode.STANDARD, 0))) {
            val store = ExecutionContextStore()
            assertThrows(IllegalArgumentException::class.java) { store.start(retired) }
            assertNull(store.current())
        }
    }

    private fun context(
        executionId: String,
        backendSessionId: String,
        mode: RuntimeMode,
        displayId: Int
    ): ExecutionContext {
        val strategy = DeviceExecutionStrategy(
            selection = DeviceModeSelection(mode, mode),
            spaceSelection = ExecutionSpaceSelection(
                requestedSpace = if (displayId == 0) ExecutionSpace.MAIN_SCREEN else ExecutionSpace.VIRTUAL_SPACE,
                effectiveSpace = if (displayId == 0) ExecutionSpace.MAIN_SCREEN else ExecutionSpace.VIRTUAL_SPACE
            )
        )
        return ExecutionContext(
            executionId = executionId,
            backendSessionId = backendSessionId,
            strategy = strategy,
            space = strategy.spaceSelection.effectiveSpace,
            displayId = displayId,
            mode = mode
        )
    }
}
