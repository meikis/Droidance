// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskExecutionTimingIntegrationTest {
    @Test fun guidancePersistsActiveTimeWithoutResumingManualPause() = runBlocking {
        var now = 0L
        val fixture = TaskCoordinatorTestFixture()
        val original = fixture.startTurn("guide-clock", "Inspect records", activate = false)
        val active = original.active.copy(executionClock = TaskExecutionClock { now })
        now = 400L
        active.executionClock.pause()
        now += 60_000L
        fixture.turnLedger.recordUserSteer(active, "guide-1", "Inspect only the newest records")
        val entries = fixture.eventStore.threadReadModel(active.scope.threadId)!!.conversation.entries
        val guidance = entries.single { it.kind == com.droidance.core.agent.event.ConversationEntryKind.USER_STEER }
        assertEquals(400L, guidance.activeDurationMillis)
        assertEquals("Inspect only the newest records", guidance.text)
        assertNull(active.executionClock.state.value.runningSinceMs)
        active.executionClock.resume()
        now += 500L
        fixture.turnLedger.recordUserSteer(active, "guide-1", "Inspect only the newest records")
        val reloaded = fixture.eventStore.threadReadModel(active.scope.threadId)!!.conversation.entries
            .filter { it.kind == com.droidance.core.agent.event.ConversationEntryKind.USER_STEER }
        assertEquals(1, reloaded.size)
        assertEquals(400L, reloaded.single().activeDurationMillis)
    }

    @Test fun longManualTakeoverDoesNotConsumeTheBudgetAndResumedWorkStillReachesItsLimit() = runBlocking {
        for (resumedWork in listOf(500L, 1_800L)) {
            var now = 0L
            var paused = false
            var reads = 0
            val fixture = TaskCoordinatorTestFixture(taskWallMs = 2_000L)
            val original = fixture.startTurn("pause-$resumedWork", "Inspect records",
                profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
            val started = original.copy(active = original.active.copy(executionClock = TaskExecutionClock { now }))
            fixture.toolExecutor.beforeLocalExecute = { call ->
                if (call.name == "session_read") {
                    reads++
                    if (reads == 1) {
                        now += 500L
                        started.active.executionClock.pause()
                        paused = true
                        // Pause starts before the coroutine reaches its waiting boundary.
                        now += 30_000L
                    } else now += resumedWork
                }
            }
            repeat(2) { index ->
                fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(
                    0, "read-$index", "session_read", "{}"))
            }
            fixture.model.roundResponses += listOf(fixture.completionCall("finish", """
                {"status":"done","deliverables":[],"results":[{"text":"Records inspected.","evidence_refs":[]}],"unresolved_requirements":[],"requirement_checks":[]}
            """.trimIndent()))

            val result = fixture.coordinator.coordinateTask(
taskGoal = "Inspect records",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = null,
executionStrategy = null,
toolProfile = started.profile,
mode = null,
host = object : com.droidance.core.agent.TurnHost {
    override val isPaused: () -> Boolean = { paused }
    override val waitIfPaused: suspend () -> Unit = {
                    if (paused) {
                        now += 30_000L
                        assertEquals(500L, started.active.executionClock.elapsedMs())
                        paused = false
                        started.active.executionClock.resume()
                    }
                }
}
)
            fixture.complete(started, result)

            assertEquals(2, reads)
            assertEquals(500L + resumedWork, started.active.executionClock.elapsedMs())
            if (resumedWork == 500L) {
                assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
                assertTrue(result.taskDoneByModel)
                assertEquals(TurnFailureReason.NONE, result.failureReason)
            } else {
                assertEquals(TurnFailureReason.TASK_TIME_LIMIT, result.failureReason)
                assertFalse(result.taskDoneByModel)
            }
            val activity = fixture.eventStore.threadReadModel(started.active.scope.threadId)!!
                .activityByTurn.getValue(started.active.scope.turnId)
            assertEquals(500L + resumedWork, activity.activeDurationMillis)
        }
    }

    @Test fun permissionWaitInsideAToolIsExcludedWithoutAnOuterPauseBoundary() = runBlocking {
        var now = 0L
        val fixture = TaskCoordinatorTestFixture(taskWallMs = 2_000L)
        val original = fixture.startTurn("permission-clock", "Inspect a record",
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        val started = original.copy(active = original.active.copy(executionClock = TaskExecutionClock { now }))
        fixture.toolExecutor.beforeLocalExecute = { call ->
            if (call.name == "session_read") {
                now += 500L
                fixture.turnLedger.pauseForUser(started.active, "permission")
                started.active.executionClock.pause()
                now += 60_000L
                assertEquals(500L, started.active.executionClock.elapsedMs())
                fixture.turnLedger.resumeFromUser(started.active, "permission")
                started.active.executionClock.resume()
                now += 500L
            }
        }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "read", "session_read", "{}"))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", """
            {"status":"done","deliverables":[],"results":[{"text":"Record inspected.","evidence_refs":[]}],"unresolved_requirements":[],"requirement_checks":[]}
        """.trimIndent()))
        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Inspect a record", additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = null, executionStrategy = null,
            toolProfile = started.profile, mode = null)
        fixture.complete(started, result)
        assertTrue(result.taskDoneByModel)
        assertEquals(TurnFailureReason.NONE, result.failureReason)
        assertEquals(1_000L, started.active.executionClock.elapsedMs())
    }

    @Test fun cancelWhilePausedPersistsActiveTimeSeparatelyFromWallTime() = runBlocking {
        var now = 0L
        val fixture = TaskCoordinatorTestFixture()
        val original = fixture.startTurn("cancel-clock", "Inspect records",
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        val active = original.active.copy(executionClock = TaskExecutionClock { now })
        now += 300L
        fixture.turnLedger.pauseForUser(active, "manual-control")
        active.executionClock.pause()
        now += 3_600_000L
        fixture.turnLedger.completeWithNotice(active, "cancelled", "Cancelled while waiting.",
            completedAtMs = active.startedAtMs + now)
        val activity = fixture.eventStore.threadReadModel(active.scope.threadId)!!
            .activityByTurn.getValue(active.scope.turnId)
        assertEquals(300L, activity.activeDurationMillis)
        assertEquals(3_600_300L, activity.totalDurationMillis)
        active.executionClock.resume()
        now += 500L
        assertEquals(300L, active.executionClock.elapsedMs())
    }
}
