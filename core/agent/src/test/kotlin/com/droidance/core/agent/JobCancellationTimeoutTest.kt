// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.TurnCompletedEventPayload
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/** Cancellation closes the real durable round and settles its active tool exactly once. */
class JobCancellationTimeoutTest {
    @Test
    fun cancelingRunningTaskCompletesWithinBoundedTimeoutAndWritesCancelledState() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn(
            requestId = "cancelled-turn",
            goal = "Run a long local operation",
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE
        )
        val toolStarted = CompletableDeferred<Unit>()
        fixture.toolExecutor.beforeLocalExecute = { call ->
            if (call.name == "update_memory") {
                toolStarted.complete(Unit)
                delay(10_000L)
            }
        }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(
            index = 0, id = "scope", name = "task_memo",
            arguments = """{"record_path":"work.md","state":"working","requirements":["Run a long local operation"],"output_constraints":[],"requested_deliverables":[],"protected_values":[],"steps":[]}"""
        ))
        fixture.model.roundResponses += listOf(
            LLMStreamEvent.ToolCallChunk(
                index = 0,
                id = "long-local-call",
                name = "update_memory",
                arguments = "{\"fact\":\"long operation\"}"
            )
        )

        val job = launch(Dispatchers.Default) {
            fixture.coordinator.coordinateTask(
                taskGoal = "Run a long local operation", additionalContext = "",
                durableTurn = started.active, turnContext = "",
                expectedBackend = null, executionStrategy = null,
                toolProfile = OperatorToolProfile.LOCAL_KNOWLEDGE,
                mode = null
            )
        }
        withTimeout(1_000L) {
            toolStarted.await()
        }

        val elapsed = measureTimeMillis {
            job.cancel()
            job.join()
        }
        assertTrue("Cancellation must finish promptly, elapsed=${elapsed}ms", elapsed < 2_500L)

        fixture.turnLedger.completeWithNotice(
            activeTurn = started.active,
            status = "cancelled",
            notice = "The active task was cancelled.",
            diagnosticCode = "TASK_CANCELLED"
        )
        val events = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
        val callId = events.single { it.eventKind == AgentEventKind.TOOL_CALL &&
            Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).name == "update_memory" }.callId
        val finished = events.filter { it.eventKind == AgentEventKind.TOOL_FINISHED && it.callId == callId }
        assertEquals(1, finished.size)
        assertEquals(ToolExecutionStatus.CANCELED,
            Json.decodeFromString<ToolFinishedEventPayload>(finished.single().payloadJson).result.executionStatus)
        val completed = events.filter { it.eventKind == AgentEventKind.TURN_COMPLETED }
        assertEquals(1, completed.size)
        val payload = Json.decodeFromString<TurnCompletedEventPayload>(completed.single().payloadJson)
        assertEquals("cancelled", payload.status)
        assertEquals("TASK_CANCELLED", payload.diagnosticCode)
    }
}
