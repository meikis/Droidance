// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TakeoverLifecycleIntegrationTest {
    @Test fun acceptedHandoffWaitsWithoutCompletingOrRequestingAnotherModelRound() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("persistent-handoff", "Read the screen, then let me take over")
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "handoff", "ask_human",
            """{"boundary_type":"user_judgment","reason":"Ready for your input"}"""))
        var paused = false
        val waiting = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val task = async {
            fixture.coordinator.coordinateTask(
taskGoal = "Read the screen, then let me take over",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onManualTakeoverNeeded: (reason: String) -> Unit = { paused = true }
    override val isPaused: () -> Boolean = { paused }
    override val waitIfPaused: suspend () -> Unit = { if (paused) { waiting.complete(Unit); resume.await() } }
}
)
        }
        try {
            withTimeout(5_000L) { waiting.await() }
            assertFalse(task.isCompleted)
            assertEquals(1, fixture.model.recordedRequests.size)
            val activity = fixture.eventStore.threadReadModel(started.active.scope.threadId)!!
                .activityByTurn.getValue(started.active.scope.turnId)
            assertEquals(TurnReadModelState.WAITING_FOR_USER, activity.state)
            assertFalse(fixture.eventStore.eventsForTurn(started.active.scope.turnId)
                .any { it.eventKind == AgentEventKind.TURN_COMPLETED })
        } finally {
            task.cancelAndJoin()
        }
    }

    @Test fun lateCompletionAndTimeoutWaitForExplicitResumeBeforeDiscardingTheOldRound() = runBlocking {
        for (lateTimeout in listOf(false, true)) {
            val model = FakeModelClient()
            var paused = false
            var requestCount = 0
            val client = object : ModelClient by model {
                override suspend fun streamChat(
                    messages: List<ChatMessage>, tools: List<ToolDefinition>, systemPrompt: String?,
                    hasImage: Boolean, enableComputerTool: Boolean, computerFrameSize: Pair<Int, Int>?,
                    maxOutputTokens: Int?, requestTimeoutMs: Long?, requestCancellationGeneration: Long?,
                    requestPlan: ModelStreamPlan?, isolatedContext: Boolean, reasoningEffort: ReasoningEffort
                ): Flow<LLMStreamEvent> {
                    val first = requestCount++ == 0
                    val response = model.streamChat(messages, tools, systemPrompt, hasImage, enableComputerTool,
                        computerFrameSize, maxOutputTokens, requestTimeoutMs, requestCancellationGeneration,
                        requestPlan, isolatedContext, reasoningEffort)
                    val lateResponse = if (first && lateTimeout) flow<LLMStreamEvent> {
                        withTimeout(1L) { awaitCancellation() }
                    } else response
                    return lateResponse.onCompletion { if (first) paused = true }
                }
            }
            val fixture = TaskCoordinatorTestFixture(modelClientOverride = client)
            val started = fixture.startTurn("late-response-$lateTimeout", "Inspect records", profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
            suspend fun completion(id: String, text: String) = fixture.completionCall(id,
                """{"status":"done","deliverables":[],"results":[{"text":"$text","evidence_refs":[]}],"unresolved_requirements":[],"requirement_checks":[]}""")
            model.roundResponses += listOf(completion("stale", "Before user control"))
            model.roundResponses += listOf(completion("fresh", "After explicit resume"))
            val waiting = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var resumeInstruction: String? = null
            val task = async {
                fixture.coordinator.coordinateTask(
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
    override val waitIfPaused: suspend () -> Unit = { if (paused) { waiting.complete(Unit); resume.await() } }
    override val takeManualResumeInstruction: () -> String? = { resumeInstruction.also { resumeInstruction = null } }
}
)
            }
            try {
                withTimeout(5_000L) { waiting.await() }
                assertFalse(task.isCompleted)
                assertTrue(fixture.toolExecutor.localCalls.isEmpty())
                assertFalse(fixture.eventStore.eventsForTurn(started.active.scope.turnId).any {
                    it.eventKind == AgentEventKind.TOOL_CALL || it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED
                })
                paused = false
                resumeInstruction = "The user explicitly resumed; observe the current state."
                resume.complete(Unit)
                val result = withTimeout(5_000L) { task.await() }
                assertTrue(result.taskDoneByModel)
                assertEquals("After explicit resume", result.operatorOutput)
                assertEquals(1, fixture.toolExecutor.localCalls.size)
                assertTrue(fixture.toolExecutor.localCalls.single().arguments.contains("After explicit resume"))
            } finally {
                task.cancelAndJoin()
            }
        }
    }
}
