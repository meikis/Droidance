// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.model.llm.ProviderHttpException
import com.droidance.core.model.llm.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ModelRequestResilienceIntegrationTest {
    @Test fun entryRetriesTransientTransportAndHttpFailuresBeforePublication() = runBlocking {
        for (failure in listOf(java.net.SocketException("connection reset")) +
            listOf(408, 409, 429, 500, 503).map { ProviderHttpException.fromResponse(it, "fixture", "{}") }) {
            val client = ScriptedClient { request ->
                if (request == 1) throw failure
                emit(LLMStreamEvent.Content("Recovered"))
            }
            val fixture = TaskCoordinatorTestFixture(modelClientOverride = client)
            assertEquals("Recovered", runDirect(fixture, client))
            assertEquals(2, client.requests)
            assertEquals(0, client.delegate.turnDepth)
            assertTrue(fixture.eventStore.unfinishedTurnScopesForRecovery().isEmpty())
            assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        }
    }

    @Test fun entryRejectsPermanentFailuresAndNeverPublishesAnIncompleteResponse() = runBlocking {
        for (status in listOf(400, 401, 402, 403)) {
            val client = ScriptedClient { throw ProviderHttpException.fromResponse(status, "fixture", "{}") }
            val fixture = TaskCoordinatorTestFixture(modelClientOverride = client)
            assertTrue(runCatching { runDirect(fixture, client) }.isFailure)
            assertEquals(1, client.requests)
            assertEquals(0, client.delegate.turnDepth)
            assertTrue(fixture.eventStore.unfinishedTurnScopesForRecovery().isEmpty())
        }
        val streamed = StringBuilder()
        val partial = ScriptedClient {
            emit(LLMStreamEvent.Content("Incomplete response"))
            throw java.net.SocketException("connection reset")
        }
        assertTrue(runCatching { runDirect(TaskCoordinatorTestFixture(modelClientOverride = partial), partial, streamed = streamed) }.isFailure)
        assertEquals("", streamed.toString())
        assertTrue(partial.requests in 1..3)
    }

    @Test fun continuingReasoningRenewsIdleBudgetWithoutAFirstOutputDeadline() = runBlocking {
        val client = ScriptedClient {
            val state = com.droidance.core.model.llm.OpenAIResponsesTurnState()
            repeat(12) {
                val events = com.droidance.core.model.llm.OpenAIResponsesProtocol.parseEvent(
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        """{"type":"response.reasoning_summary_text.delta","delta":"private reasoning"}""")
                        .let { it as kotlinx.serialization.json.JsonObject }, state)
                assertTrue(events.isNotEmpty())
                assertFalse(events.any { it is LLMStreamEvent.Content || it is LLMStreamEvent.ReasoningChunk })
                events.forEach { emit(it) }
                delay(20)
            }
            emit(LLMStreamEvent.Content("Finished reasoning"))
        }
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = client, runtimeThresholdsOverride = {
            it.copy(llmFirstEventTimeoutMs = 100, llmStreamIdleTimeoutMs = 100,
                llmFirstOutputTimeoutMs = 0, llmRecoveryTimeoutMs = 1000)
        })
        assertEquals("Finished reasoning", runDirect(fixture, client))
        assertEquals(1, client.requests)
    }

    @Test fun silentEntryRequestsShareOneBudgetAndSettleBeforeAnotherTask() = runBlocking {
        var cancelledProducers = 0
        val client = ScriptedClient {
            try { awaitCancellation() } finally { cancelledProducers++ }
        }
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = client, runtimeThresholdsOverride = {
            it.copy(llmFirstEventTimeoutMs = 50, llmRecoveryTimeoutMs = 120,
                llmTransientRetryBackoffMs = 0)
        })
        withTimeout(1500) { assertTrue(runCatching { runDirect(fixture, client) }.isFailure) }
        assertTrue(client.requests in 1..3)
        assertEquals(client.requests, cancelledProducers)
        assertEquals(0, client.delegate.turnDepth)
        assertTrue(fixture.eventStore.unfinishedTurnScopesForRecovery().isEmpty())
        val next = ScriptedClient { emit(LLMStreamEvent.Content("Ready again")) }
        assertEquals("Ready again", runDirect(TaskCoordinatorTestFixture(modelClientOverride = next), next, "second"))
    }

    @Test fun retryAfterBeyondTheRemainingBudgetDoesNotSendAnEarlyRetry() = runBlocking {
        val client = ScriptedClient { throw ProviderHttpException.fromResponse(429, "fixture", "{}", "1") }
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = client, runtimeThresholdsOverride = { it.copy(llmRecoveryTimeoutMs = 100) })
        withTimeout(1000) { assertTrue(runCatching { runDirect(fixture, client) }.isFailure) }
        assertEquals(1, client.requests)
        assertEquals(0, client.delegate.turnDepth)
        assertTrue(fixture.eventStore.unfinishedTurnScopesForRecovery().isEmpty())
    }

    @Test fun idleReasoningInTheOperatorCanRecoverWithoutExecutingPartialCalls() = runBlocking {
        var firstProducerCancelled = false
        val client = ScriptedClient { request ->
            if (request == 1) {
                emit(LLMStreamEvent.ReasoningChunk("private unfinished reasoning"))
                try { awaitCancellation() } finally { firstProducerCancelled = true }
            } else emit(LLMStreamEvent.Content("Recovered decision"))
        }
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = client, runtimeThresholdsOverride = {
            it.copy(llmStreamIdleTimeoutMs = 50, llmFirstOutputTimeoutMs = 0,
                llmRecoveryTimeoutMs = 1000)
        })
        val started = fixture.startTurn("operator-idle", "Inspect the requested state", activate = false)
        val result = withTimeout(1500) {
            fixture.coordinator.coordinateTask(taskGoal = "Inspect the requested state", additionalContext = "",
                durableTurn = started.active, turnContext = "", expectedBackend = null,
                executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        }
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals("Recovered decision", result.operatorOutput)
        assertEquals(2, client.requests)
        assertTrue(firstProducerCancelled)
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }

    @Test fun cancellingDuringEntryBackoffStopsRetriesAndReleasesTheSession() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val client = ScriptedClient { failed.complete(Unit); throw java.net.SocketException("reset") }
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = client, runtimeThresholdsOverride = {
            it.copy(llmTransientRetryBackoffMs = 5000)
        })
        val job = launch { runDirect(fixture, client) }
        failed.await()
        withTimeout(1000) { job.cancelAndJoin() }
        assertEquals(1, client.requests)
        assertEquals(0, client.delegate.turnDepth)
        assertTrue(fixture.eventStore.unfinishedTurnScopesForRecovery().isEmpty())
    }

    @Test fun stalledStoppedReplyFallsBackAndSettlesItsRound() = runBlocking {
        var stoppedReplyCancelled = false
        val client = ScriptedClient { request ->
            if (request == 1) throw ModelStreamTimeoutException("first_output")
            try { awaitCancellation() } finally { stoppedReplyCancelled = true }
        }
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = client,
            stoppedReplyEnabled = true, stoppedReplyTimeoutMs = 50)
        val started = fixture.startTurn("stopped-reply", "Inspect the requested state", activate = false)
        val result = withTimeout(1500) {
            fixture.coordinator.coordinateTask(taskGoal = "Inspect the requested state", additionalContext = "",
                durableTurn = started.active, turnContext = "", expectedBackend = null,
                executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        }
        assertEquals(TurnFailureReason.MODEL_TIMEOUT, result.failureReason)
        assertEquals(2, client.requests)
        assertTrue(stoppedReplyCancelled)
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertTrue(fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .any { "STOPPED_REPLY_UNAVAILABLE" in it.payloadJson })
    }

    @Test fun runtimeFailureExplanationCannotBlockTaskCleanup() = runBlocking {
        var producerCancelled = false
        val client = ScriptedClient {
            try { awaitCancellation() } finally { producerCancelled = true }
        }
        val fixture = TaskCoordinatorTestFixture(stoppedReplyTimeoutMs = 50)
        val lifecycle = fixture.createLifecycle(client)
        var released = false
        val failure = withTimeout(1500) { runCatching {
            lifecycle.run<Unit>("thread", request("runtime-failure"), fixture::operatorPrefix,
                unfinishedNotice = { _, _ -> TaskLifecycle.FailureNotice("error", "Execution failed", "RUNTIME_EXCEPTION") },
                releaseResources = { released = true }) { session ->
                session.activateTools(fixture.operatorPrefix(), com.droidance.core.agent.event.TurnActivationKind.LOCAL, preparedAtStart = true)
                throw IllegalStateException("Preparation failed")
            }
        }.exceptionOrNull() }
        assertTrue(failure is IllegalStateException)
        assertTrue(released)
        assertTrue(producerCancelled)
        assertEquals(0, client.delegate.turnDepth)
        assertTrue(fixture.eventStore.unfinishedTurnScopesForRecovery().isEmpty())
    }

    private suspend fun runDirect(fixture: TaskCoordinatorTestFixture, client: ModelClient,
        id: String = "entry", streamed: StringBuilder = StringBuilder()): String {
        val lifecycle = fixture.createLifecycle(client)
        return lifecycle.run("thread", request(id), fixture::operatorPrefix,
            unfinishedNotice = { _, failure -> TaskLifecycle.FailureNotice(
                if (failure is CancellationException) "cancelled" else "error", "Request stopped", "TEST_FAILURE") },
            releaseResources = {}) { session ->
            val result = fixture.runOperator(session, "Say hello") { streamed.append(it.text) }
            session.complete(if (result.terminal == TurnTerminal.DIRECT_REPLY) "done" else "failed", result.summary)
            check(result.terminal == TurnTerminal.DIRECT_REPLY) { "Request failed: ${result.failureReason}" }
            result.summary
        }
    }

    private fun request(id: String) = TurnRequest.create(MessageSource.DIRECT, "Say hello").copy(requestId = id)

    private class ScriptedClient(
        val delegate: FakeModelClient = FakeModelClient(),
        private val script: suspend FlowCollector<LLMStreamEvent>.(Int) -> Unit
    ) : ModelClient by delegate {
        var requests = 0
        override suspend fun streamChat(messages: List<ChatMessage>, tools: List<ToolDefinition>, systemPrompt: String?,
            hasImage: Boolean, enableComputerTool: Boolean, computerFrameSize: Pair<Int, Int>?, maxOutputTokens: Int?,
            requestTimeoutMs: Long?, requestCancellationGeneration: Long?, requestPlan: ModelStreamPlan?,
            isolatedContext: Boolean, reasoningEffort: ReasoningEffort): Flow<LLMStreamEvent> {
            val index = ++requests
            return flow { script(index) }
        }
    }
}
