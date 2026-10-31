// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.device.ExecutionContextStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface InputAction {
    val durationMs: Int

    data class Tap(
        val x: Int,
        val y: Int
    ) : InputAction {
        override val durationMs: Int = 0
    }

    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        override val durationMs: Int
    ) : InputAction
}

sealed interface InputActionLifecycleEvent {
    enum class Phase { STARTED, FINISHED }

    val phase: Phase
    val actionId: String
    val executionId: String
    val backendSessionId: String
    val displayId: Int
    val elapsedMs: Long
    val action: InputAction

    data class Started(
        override val actionId: String,
        override val executionId: String,
        override val backendSessionId: String,
        override val displayId: Int,
        override val elapsedMs: Long,
        override val action: InputAction,
        val fromX: Int = 0,
        val fromY: Int = 0,
        val travelDurationMs: Long = 0L
    ) : InputActionLifecycleEvent {
        override val phase: Phase = Phase.STARTED
    }

    data class Finished(
        override val actionId: String,
        override val executionId: String,
        override val backendSessionId: String,
        override val displayId: Int,
        override val elapsedMs: Long,
        override val action: InputAction,
        val success: Boolean,
        val durationMs: Long
    ) : InputActionLifecycleEvent {
        override val phase: Phase = Phase.FINISHED
    }
}

@Singleton
class InputActionLifecycle @Inject constructor(
    private val executionContextStore: ExecutionContextStore
) {
    private val _events = MutableSharedFlow<InputActionLifecycleEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<InputActionLifecycleEvent> = _events.asSharedFlow()
    /** App-owned overlay input is disabled only during injected gestures on its display. */
    private val lastPositions = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Int>>()

    fun recordDisplayPosition(displayId: Int, x: Int, y: Int) {
        lastPositions[displayId] = x to y
    }

    suspend fun <T> execute(
        action: InputAction,
        succeeded: (T) -> Boolean,
        block: suspend () -> T
    ): T {
        val executionContext = executionContextStore.requireCurrent()
        val actionId = UUID.randomUUID().toString()
        val startedAtMs = elapsedRealtimeMs()
        val displayId = executionContext.displayId
        val targetPoint = when (action) {
            is InputAction.Tap -> action.x to action.y
            is InputAction.Swipe -> action.startX to action.startY
        }
        val prevPoint = lastPositions[displayId]
        val fromX = prevPoint?.first ?: targetPoint.first
        val fromY = prevPoint?.second ?: targetPoint.second
        val distance = if (prevPoint != null) {
            Math.hypot(
                (targetPoint.first - prevPoint.first).toDouble(),
                (targetPoint.second - prevPoint.second).toDouble()
            ).toFloat()
        } else {
            0f
        }
        val animatorsEnabled = runCatching {
            android.animation.ValueAnimator.areAnimatorsEnabled()
        }.getOrDefault(false)
        val travelDurationMs = if (distance > 15f && animatorsEnabled) {
            com.droidance.core.device.agent.device.CursorMotion.calculateTravelDuration(distance)
        } else {
            0L
        }

        _events.emit(
            InputActionLifecycleEvent.Started(
                actionId = actionId,
                executionId = executionContext.executionId,
                backendSessionId = executionContext.backendSessionId,
                displayId = displayId,
                elapsedMs = startedAtMs,
                action = action,
                fromX = fromX,
                fromY = fromY,
                travelDurationMs = travelDurationMs
            )
        )

        if (travelDurationMs > 0L) {
            kotlinx.coroutines.delay(travelDurationMs)
        }

        var success = false
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return block().also { success = succeeded(it) }
        } finally {
            val endPoint = when (action) {
                is InputAction.Tap -> action.x to action.y
                is InputAction.Swipe -> action.endX to action.endY
            }
            lastPositions[displayId] = endPoint
            val finishedAtMs = elapsedRealtimeMs()
            _events.emit(
                InputActionLifecycleEvent.Finished(
                    actionId = actionId,
                    executionId = executionContext.executionId,
                    backendSessionId = executionContext.backendSessionId,
                    displayId = displayId,
                    elapsedMs = finishedAtMs,
                    action = action,
                    success = success,
                    durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L)
                )
            )
        }
    }

    private fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L
}
