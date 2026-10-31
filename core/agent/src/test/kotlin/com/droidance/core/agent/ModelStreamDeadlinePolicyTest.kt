// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.model.llm.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ModelStreamDeadlinePolicyTest {
    @Test fun localRequestConfigurationDoesNotSatisfyTheFirstProviderEventDeadline() = runBlocking {
        val failure = runCatching {
            withTimeout(2000) {
                flow<LLMStreamEvent> {
                    emit(LLMStreamEvent.ReasoningConfiguration(ReasoningEffort.MEDIUM,
                        ReasoningEffort.DEFAULT, "unsupported_or_unknown_capability", listOf(ReasoningEffort.DEFAULT)))
                    awaitCancellation()
                }.collectWithDeadlines(ModelStreamDeadlinePolicy(30, 1000, 0), { false }) {}
            }
        }.exceptionOrNull()
        assertTrue(failure is ModelStreamTimeoutException)
        assertEquals("first_event", (failure as ModelStreamTimeoutException).phase)
    }

    @Test fun disabledDeadlinesAllowSilenceAndReasoningUntilACompleteDecision() = runBlocking {
        val policy = ModelStreamDeadlinePolicy(0, 0, 0)
        assertNull(policy.remaining(Long.MAX_VALUE, null, false))
        assertNull(policy.remaining(Long.MAX_VALUE, 1L, false))
        val observed = mutableListOf<LLMStreamEvent>()
        flow {
            delay(30)
            repeat(3) { emit(LLMStreamEvent.ReasoningChunk("reason")); delay(20) }
            emit(LLMStreamEvent.Content("complete"))
        }.collectWithDeadlines(policy, { false }) { observed += it }
        assertEquals(LLMStreamEvent.Content("complete"), observed.last())
    }

    @Test fun disabledDeadlinesStillPropagateParentCancellationAndStopProducer() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var stopped = false
        val consumer = launch {
            flow<LLMStreamEvent> {
                try { started.complete(Unit); awaitCancellation() } finally { stopped = true }
            }.collectWithDeadlines(ModelStreamDeadlinePolicy(0, 0, 0), { false }) {}
        }
        started.await()
        consumer.cancelAndJoin()
        assertTrue(stopped)
    }

    @Test fun reasoningActivityCannotRenewFirstOutputBudget() {
        val policy = ModelStreamDeadlinePolicy(30, 20, 60)
        assertEquals(ModelStreamDeadlinePolicy.Remaining("first_event", 25), policy.remaining(5, null, false))
        assertEquals(ModelStreamDeadlinePolicy.Remaining("first_output", 1), policy.remaining(59, 58, false))
        assertEquals(ModelStreamDeadlinePolicy.Remaining("stream_idle", 19), policy.remaining(59, 58, true))
    }

    @Test fun stalledStreamsCancelTheirProducerAndReportTheActualPhase() = runBlocking {
        for (expected in listOf("first_event", "stream_idle", "first_output")) {
            var producerCanceled = false
            val delegate = FakeModelClient()
            val client = object : ModelClient by delegate {
                override suspend fun streamChat(messages: List<ChatMessage>, tools: List<ToolDefinition>, systemPrompt: String?,
                    hasImage: Boolean, enableComputerTool: Boolean, computerFrameSize: Pair<Int, Int>?,
                    maxOutputTokens: Int?, requestTimeoutMs: Long?, requestCancellationGeneration: Long?,
                    requestPlan: ModelStreamPlan?, isolatedContext: Boolean, reasoningEffort: ReasoningEffort): Flow<LLMStreamEvent> = flow {
                    try {
                        if (expected == "stream_idle") emit(LLMStreamEvent.Content("partial"))
                        if (expected == "first_output") while (true) {
                            emit(LLMStreamEvent.ReasoningChunk("reasoning")); delay(5)
                        }
                        awaitCancellation()
                    } finally { producerCanceled = true }
                }
            }
            var metrics = emptyMap<String, String>()
            val exception = runCatching { OperatorStreamCollector(client) {}.collect(
                requestPlan = client.planStreamChat(emptyList(), false), messages = emptyList(), tools = emptyList(),
                systemPrompt = "fixture", hasImage = false, timeoutMs = 2000, cancellationGeneration = 0,
                maxOutputTokens = 100, enableComputerTool = false, computerFrameSize = null,
                startedAt = System.currentTimeMillis(), onMetrics = { metrics = it }, onContent = {},
                deadlines = ModelStreamDeadlinePolicy(100, 100, 250)) }.exceptionOrNull()
            assertTrue(exception.toString(), exception is ModelStreamTimeoutException)
            assertEquals(expected, (exception as ModelStreamTimeoutException).phase)
            assertEquals(expected, metrics["response_timeout_phase"])
            assertTrue(producerCanceled)
        }
    }
    @Test fun socketFailureDuringDeadlineCancellationCannotEscapeTheProducer() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        var stopped = false
        withContext(CoroutineExceptionHandler { _, error -> uncaught += error }) {
            val failure = runCatching {
                flow<LLMStreamEvent> {
                    try { awaitCancellation() }
                    finally { stopped = true; throw java.io.IOException("socket reset on cancel") }
                }.collectWithDeadlines(ModelStreamDeadlinePolicy(40, 40, 80), { false }) {}
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure is ModelStreamTimeoutException)
            assertEquals("first_event", (failure as ModelStreamTimeoutException).phase)
        }
        assertTrue(stopped)
        assertTrue(uncaught.toString(), uncaught.isEmpty())
    }

    @Test fun ordinaryProducerFailurePreservesBufferedContentAndReachesTheCaller() = runBlocking {
        val observed = mutableListOf<LLMStreamEvent>()
        val uncaught = mutableListOf<Throwable>()
        val error = java.io.IOException("active socket failed")
        withContext(CoroutineExceptionHandler { _, failure -> uncaught += failure }) {
            val failure = runCatching {
                flow<LLMStreamEvent> {
                    emit(LLMStreamEvent.Content("retained fragment")); throw error
                }.collectWithDeadlines(ModelStreamDeadlinePolicy(1000, 1000, 1000), { true }) { observed += it }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(error.message, failure?.message)
        }
        assertEquals(listOf(LLMStreamEvent.Content("retained fragment")), observed)
        assertTrue(uncaught.isEmpty())
    }

    @Test fun parentCancellationJoinsTheProducerEvenWhenSocketTeardownFails() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val uncaught = mutableListOf<Throwable>()
        var stopped = false
        val job = launch(CoroutineExceptionHandler { _, error -> uncaught += error }) {
            flow<LLMStreamEvent> {
                try { entered.complete(Unit); awaitCancellation() }
                finally { stopped = true; throw java.io.IOException("cancelled transport") }
            }.collectWithDeadlines(ModelStreamDeadlinePolicy(1000, 1000, 1000), { false }) {}
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(stopped); assertTrue(uncaught.isEmpty())
    }

}
