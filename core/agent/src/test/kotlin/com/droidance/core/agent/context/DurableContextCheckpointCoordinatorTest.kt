// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.event.*
import com.droidance.core.agent.prompt.StablePrefixBuilder
import com.droidance.core.agent.llm.session.*
import com.droidance.core.model.llm.session.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DurableContextCheckpointCoordinatorTest {
    private val policy = ContextCompactionExecutionPolicy(90000, 240000, 1, 2048, 8192, 24000, 1, 4096)

    @Test
    fun `early transient failure leaves raw history checkpoint and epoch unchanged`() = runBlocking {
        val fixture = Fixture()
        val initialSession = fixture.active.session
        val recovery = ContextCompactionRecovery(policy)
        val decision = fixture.evaluate(100000, recovery) { throw ContextCompactionUnavailable("timeout") }
        assertEquals(ContextUnchangedReason.COMPACTION_DEFERRED, (decision as ContextCheckpointDecision.Unchanged).reason)
        assertEquals(0, fixture.commitAttempts)
        assertEquals(initialSession, fixture.active.session)
        assertThrows(ContextCompactionUnavailable::class.java) {
            runBlocking { fixture.evaluate(100000, recovery) { throw ContextCompactionUnavailable("timeout") } }
        }
        assertEquals(0, fixture.commitAttempts)
    }

    @Test
    fun `hard limit does not permit a normal request after summary timeout`() {
        val fixture = Fixture()
        assertThrows(ContextCompactionUnavailable::class.java) {
            runBlocking { fixture.evaluate(1000, ContextCompactionRecovery(policy)) { throw ContextCompactionUnavailable("timeout") } }
        }
        assertEquals(0, fixture.commitAttempts)
    }

    @Test
    fun `empty summary cancellation and evidence failures do not defer or write`() {
        for (failure in listOf<Throwable?>(null, CancellationException("cancel"), IllegalStateException("missing image"))) {
            val fixture = Fixture()
            val error = assertThrows(Exception::class.java) {
                runBlocking { fixture.evaluate(100000, ContextCompactionRecovery(policy)) {
                    if (failure != null) throw failure
                    " "
                } }
            }
            if (failure != null) assertSame(failure, error)
            assertEquals(0, fixture.commitAttempts)
        }
    }

    @Test
    fun `failed checkpoint persistence does not advance epoch or silently defer`() {
        val fixture = Fixture()
        val initialSession = fixture.active.session
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.evaluate(100000, ContextCompactionRecovery(policy)) { "continuation summary" } }
        }
        assertEquals("simulated persistence failure", error.message)
        assertEquals(1, fixture.commitAttempts)
        assertEquals(initialSession, fixture.active.session)
    }

    private class Fixture {
        var commitAttempts = 0
        private val prefix = StablePrefixBuilder.build("You are Droidance.", emptyList(), emptyList())
        val active = object : ContextCheckpointSession {
            override val scope = TurnEventScope("thread-1", "turn-1")
            override val stablePrefix = prefix
            override var session = FrozenTurnModelSession(
                "turn-1", "config", ModelSessionKey(ModelSessionIdentity.create(
                    threadId = "thread-1", providerId = "provider", transportKind = ModelTransport.OPENAI_RESPONSES,
                    transportConfig = FrozenTransportConfig.create("https://example.com/v1", "test"),
                    authScopeHash = "a".repeat(64), modelId = "test", stablePrefix = prefix
                ), 0)
            )
        }
        private val history = buildList {
            add(event(1, AgentEventKind.USER_MESSAGE, "round-1", Json.encodeToString(
                UserTextEventPayload(1, "message-1", "task")
            )))
            for (round in 1..3) {
                val start = (round - 1) * 3L + 2
                add(event(start, AgentEventKind.MODEL_ROUND_STARTED, "round-$round"))
                add(event(start + 1, AgentEventKind.PUBLIC_PROGRESS, "round-$round", Json.encodeToString(
                    PublicProgressEventPayload(1, listOf("source data ".repeat(2000)), "detail-$round")
                )))
                add(event(start + 2, AgentEventKind.MODEL_ROUND_COMPLETED, "round-$round"))
            }
        }
        private val store = Proxy.newProxyInstance(AgentEventStore::class.java.classLoader, arrayOf(AgentEventStore::class.java)) { _, method, _ ->
            when (method.name) {
                "latestContextCheckpointForTurn" -> null
                "eventsForTurn" -> history
                "recordContextCheckpoint" -> { commitAttempts++; error("simulated persistence failure") }
                else -> error("Unexpected write or read: ${method.name}")
            }
        } as AgentEventStore

        suspend fun evaluate(window: Int, recovery: ContextCompactionRecovery, summarize: suspend () -> String) =
            DurableContextCheckpointCoordinator(store).evaluateAndCommit(
                activeTurn = active, taskAnchorPrompt = "task", taskGoal = "task", taskWorkingMemory = "",
                freshScreenState = null, hasAttachedImage = false, providerContextWindowTokens = window,
                compressionTriggerPercent = 1, recentScreenshotLimit = 0, maxOutputTokens = 100,
                additionalRequestContext = "", compactionRecovery = recovery, onCapacity = {},
                compact = { _, _ -> summarize() }
            )

        private fun event(sequence: Long, kind: AgentEventKind, round: String, payload: String = "{}") = AgentEventEnvelope(
            schemaVersion = 2, eventId = "event-$sequence", eventKind = kind, sequence = sequence,
            occurredAt = sequence, threadId = "thread-1", turnId = "turn-1", roundId = round,
            attemptId = null, executionId = null, callId = null, payloadHash = "0".repeat(64),
            payloadJson = payload, idempotencyKey = "key-$sequence"
        )
    }
}
