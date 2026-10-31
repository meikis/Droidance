// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.device.DeviceExecutionStrategy
import com.droidance.core.agent.device.DeviceModeSelection
import com.droidance.core.agent.device.ExecutionContext
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.ExecutionSpaceSelection
import com.droidance.core.agent.device.RuntimeMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InputActionLifecycleTest {
    @Test
    fun actionLifecycleWrapsTheRealBackendCall() = runBlocking {
        val store = ExecutionContextStore().apply { start(context(displayId = 96)) }
        val lifecycle = InputActionLifecycle(store)
        val order = mutableListOf<String>()
        val events = mutableListOf<InputActionLifecycleEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.events.take(2).toList(events)
        }

        val result = lifecycle.execute(
            action = InputAction.Tap(20, 30),
            succeeded = { it }
        ) {
            order += "BACKEND"
            true
        }
        collector.join()
        events.forEachIndexed { index, event ->
            order.add(index * 2, event.phase.name)
        }

        assertTrue(result)
        assertEquals(listOf("STARTED", "BACKEND", "FINISHED"), order)
        assertTrue(events.first() is InputActionLifecycleEvent.Started)
        assertTrue(events.last() is InputActionLifecycleEvent.Finished)
        assertTrue((events.last() as InputActionLifecycleEvent.Finished).success)
    }

    @Test
    fun failedBackendStillEmitsFinishedAgainstTheFrozenContext() = runBlocking {
        val frozen = context(displayId = 96)
        val store = ExecutionContextStore().apply { start(frozen) }
        val lifecycle = InputActionLifecycle(store)
        val events = mutableListOf<InputActionLifecycleEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.events.take(2).toList(events)
        }
        val failure = IllegalStateException("backend failed")

        val thrown = runCatching {
            lifecycle.execute(
                action = InputAction.Swipe(10, 20, 30, 40, 275),
                succeeded = { _: Unit -> true }
            ) { throw failure }
        }.exceptionOrNull()
        collector.join()

        assertSame(failure, thrown)
        assertEquals(events.first().actionId, events.last().actionId)
        assertTrue(events.all { it.executionId == frozen.executionId })
        assertTrue(events.all { it.backendSessionId == frozen.backendSessionId })
        assertTrue(events.all { it.displayId == frozen.displayId })
        assertFalse((events.last() as InputActionLifecycleEvent.Finished).success)
    }

    @Test
    fun consecutiveActionsTrackPositionAndEmitTravelMetadata() = runBlocking {
        val store = ExecutionContextStore().apply { start(context(displayId = 96)) }
        val lifecycle = InputActionLifecycle(store)
        val events = mutableListOf<InputActionLifecycleEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.events.take(4).toList(events)
        }

        lifecycle.execute(action = InputAction.Tap(50, 60), succeeded = { it }) { true }
        lifecycle.execute(action = InputAction.Tap(400, 800), succeeded = { it }) { true }
        collector.join()

        assertEquals(4, events.size)
        val firstStarted = events[0] as InputActionLifecycleEvent.Started
        val secondStarted = events[2] as InputActionLifecycleEvent.Started
        assertEquals(50, firstStarted.fromX)
        assertEquals(60, firstStarted.fromY)
        assertEquals(50, secondStarted.fromX)
        assertEquals(60, secondStarted.fromY)
    }

    @Test
    fun recordDisplayPositionInitializesTravelOrigin() = runBlocking {
        val store = ExecutionContextStore().apply { start(context(displayId = 5)) }
        val lifecycle = InputActionLifecycle(store)
        lifecycle.recordDisplayPosition(5, 120, 340)

        val events = mutableListOf<InputActionLifecycleEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.events.take(2).toList(events)
        }

        lifecycle.execute(action = InputAction.Swipe(200, 400, 200, 800, 500), succeeded = { it }) { true }
        collector.join()

        val started = events.first() as InputActionLifecycleEvent.Started
        assertEquals(120, started.fromX)
        assertEquals(340, started.fromY)
    }

    private fun context(displayId: Int): ExecutionContext {
        val space = if (displayId == 0) ExecutionSpace.MAIN_SCREEN else ExecutionSpace.VIRTUAL_SPACE
        val strategy = DeviceExecutionStrategy(
            selection = DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            spaceSelection = ExecutionSpaceSelection(space, space)
        )
        return ExecutionContext(
            executionId = "run-1",
            backendSessionId = "backend-7",
            strategy = strategy,
            space = space,
            displayId = displayId,
            mode = RuntimeMode.STANDARD
        )
    }
}
