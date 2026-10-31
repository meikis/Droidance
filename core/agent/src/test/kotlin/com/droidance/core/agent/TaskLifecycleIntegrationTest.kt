// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.TurnActivationKind
import com.droidance.core.agent.event.TurnCompletedEventPayload
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.ModelSession
import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.prompt.sha256Hex
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the production TaskLifecycle boundary around the real durable ledger and
 * TaskCoordinator. These tests deliberately keep the model fake and never create a device or
 * provider connection.
 */
class TaskLifecycleIntegrationTest {
    @Test fun userMessageWriteFailureSettlesTheAlreadyStartedTurnAndClosesTheLease() = runBlocking {
        val committed = InMemoryAgentEventStore()
        val writeFailure = IllegalStateException("injected user message write failure")
        val failing = object : AgentEventStore by committed {
            override suspend fun recordUserMessage(
                scope: TurnEventScope, messageId: String, text: String,
                attachments: List<UserAttachmentPayload>
            ): AgentEventEnvelope = throw writeFailure
        }
        val fixture = TaskCoordinatorTestFixture(eventStore = failing)
        val lifecycle = fixture.createLifecycle()
        var releaseCalls = 0
        val thrown = runCatching {
            lifecycle.run<Unit>(
                conversationId = "startup-thread",
                request = request("startup-write-failure", "Persist before executing"),
                initialPrefix = fixture::operatorPrefix,
                unfinishedNotice = { _, cause ->
                    assertSame(writeFailure, cause)
                    TaskLifecycle.FailureNotice("error", "Could not start the task", "STARTUP_WRITE_FAILED")
                },
                releaseResources = { releaseCalls++ }
            ) { error("Execution must not start without the user message") }
        }.exceptionOrNull()

        assertSame(writeFailure, thrown)
        assertTrue("A failed startup must not leave an open Turn", committed.unfinishedTurnScopesForRecovery().isEmpty())
        assertEquals(1, releaseCalls)
        assertEquals(0, fixture.model.turnDepth)
        val events = committed.eventsForTurn("turn:startup-write-failure")
        assertTrue(events.none { it.eventKind == AgentEventKind.USER_MESSAGE })
        assertEquals("STARTUP_WRITE_FAILED", completedPayload(events).diagnosticCode)
    }

    @Test fun cancellationWhileWritingTheInitialUserMessageSettlesWithoutAProcessRestart() = runBlocking {
        val committed = InMemoryAgentEventStore()
        val writing = CompletableDeferred<Unit>()
        val suspended = object : AgentEventStore by committed {
            override suspend fun recordUserMessage(
                scope: TurnEventScope, messageId: String, text: String,
                attachments: List<UserAttachmentPayload>
            ): AgentEventEnvelope {
                writing.complete(Unit)
                awaitCancellation()
            }
        }
        val fixture = TaskCoordinatorTestFixture(eventStore = suspended)
        val lifecycle = fixture.createLifecycle()
        var releaseCalls = 0
        val job = launch {
            lifecycle.run<Unit>(
                conversationId = "startup-thread",
                request = request("startup-cancel", "Cancel while saving the user message"),
                initialPrefix = fixture::operatorPrefix,
                unfinishedNotice = { _, _ -> TaskLifecycle.FailureNotice("cancelled", "Stopped", "TASK_CANCELLED") },
                releaseResources = { releaseCalls++ }
            ) { error("Execution must not start without the user message") }
        }
        withTimeout(1_000) { writing.await(); job.cancelAndJoin() }

        assertTrue("Cancellation before execute must settle its durable Turn", committed.unfinishedTurnScopesForRecovery().isEmpty())
        assertEquals(1, releaseCalls)
        assertEquals(0, fixture.model.turnDepth)
        assertEquals("cancelled", completedPayload(committed.eventsForTurn("turn:startup-cancel")).status)
    }

    @Test fun ingressModelPlanIsLeasedForTheTurnAndReleasedAfterCompletion() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val lifecycle = fixture.createLifecycle()
        val plan = com.droidance.core.model.llm.ModelStreamPlan.fromProvider(fixture.model.dummyConfig, "chosen-model", false)
        lifecycle.run(
            conversationId = "composer-thread",
            request = request("composer", "Say hello").copy(modelPlan = plan, executionSpace = com.droidance.core.agent.device.ExecutionSpace.MAIN_SCREEN),
            initialPrefix = fixture::operatorPrefix,
            unfinishedNotice = ::unfinishedNotice,
            releaseResources = {}
        ) { session ->
            assertSame(plan, fixture.model.sessionInitialPlan)
            assertEquals("chosen-model", fixture.model.planStreamChat(emptyList(), false).initialModel)
            session.complete("done", "Hello")
        }
        assertEquals(0, fixture.model.turnDepth)
        assertEquals(null, fixture.model.sessionInitialPlan)
    }

    @Test
    fun operatorDirectReplySettlesWithoutDeviceExecution() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val lifecycle = fixture.createLifecycle()
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Hello from Operator"))
        val streamed = mutableListOf<String>()

        val decision = lifecycle.run(
            conversationId = "direct-thread",
            request = request("direct", "Say hello"),
            initialPrefix = fixture::operatorPrefix,
            unfinishedNotice = ::unfinishedNotice,
            releaseResources = {}
        ) { session ->
            val result = fixture.runOperator(session, "Say hello") { streamed.add(it.text) }
            assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
            session.complete("done", result.summary)
            result
        }

        assertEquals("Hello from Operator", decision.summary)
        assertEquals(listOf("Hello from Operator"), streamed)
        assertEquals(0, fixture.model.turnDepth)
        val events = fixture.eventStore.eventsForTurn("turn:direct")
        assertTrue(events.any { it.eventKind == AgentEventKind.TURN_STARTED })
        assertTrue(events.any { it.eventKind == AgentEventKind.USER_MESSAGE })
        assertTrue(events.any { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED })
        assertTrue(events.any { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED })
        assertTrue(events.any { it.eventKind == AgentEventKind.TURN_COMPLETED })
        assertTrue(events.none { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
        assertTrue(events.none { it.eventKind == AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED })
    }

    @Test
    fun localActivationUsesTheRealTaskCoordinatorAndSettlesItsTerminalResult() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val lifecycle = fixture.createLifecycle()
        fixture.model.roundResponses += listOf(
            call("scope", "task_memo", """{"record_path":"work.md","state":"record requested fact","requirements":["Record a task fact"],"output_constraints":[],"requested_deliverables":[],"protected_values":[],"steps":[{"id":"record","objective":"Record requested fact","acceptance":"Persistence receipt","status":"active","evidence_refs":[]}]}"""),
            call("local-memo", "update_memory", "{\"fact\":\"fixture fact\"}")
        )

        val result = lifecycle.run(
            conversationId = "local-thread",
            request = request("local", "Record a task fact"),
            initialPrefix = fixture::operatorPrefix,
            unfinishedNotice = ::unfinishedNotice,
            releaseResources = {}
        ) { session ->
            session.activateTools(
                prefix = fixture.coordinator.stablePrefix(
                    mode = null,
                    executionSpace = null,
                    toolProfile = OperatorToolProfile.LOCAL_KNOWLEDGE
                ),
                kind = TurnActivationKind.LOCAL, preparedAtStart = true
            )
            fixture.model.roundResponses += listOf(
                fixture.completionCall("local-stop", needMoreInfoPayload("Confirm the next step"), session.durableTurn.scope)
            )
            fixture.bindLocalToolScope(session.durableTurn.scope)
            val coordinated = fixture.coordinator.coordinateTask(
                taskGoal = "Record a task fact",
                additionalContext = "",
                durableTurn = session.durableTurn,
                turnContext = "",
                expectedBackend = null,
                executionStrategy = null,
                toolProfile = OperatorToolProfile.LOCAL_KNOWLEDGE,
                mode = null
            )
            assertEquals(
                "coordinated result: $coordinated localCalls=${fixture.toolExecutor.localCalls} " +
                    "requests=${fixture.model.recordedRequests}",
                TurnTerminal.NEED_MORE_INFO,
                coordinated.terminal
            )
            session.complete("need_more_info", coordinated.summary)
            coordinated
        }

        assertEquals(TurnTerminal.NEED_MORE_INFO, result.terminal)
        assertTrue(fixture.toolExecutor.localCalls.any { it.name == "update_memory" })
        assertTrue(fixture.toolExecutor.localCalls.any { it.name == "done" })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertEquals(0, fixture.model.turnDepth)
        val events = fixture.eventStore.eventsForTurn("turn:local")
        assertTrue(events.none { it.eventKind == AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED })
        assertTrue(events.any { it.eventKind == AgentEventKind.TURN_COMPLETED })
    }

    @Test
    fun twoConsecutiveTasksUseFreshSessionsAndIndependentDurableTurns() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val lifecycle = fixture.createLifecycle()
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("First"))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Second"))

        suspend fun runDirect(requestId: String): String {
            return lifecycle.run(
                conversationId = "same-thread",
                request = request(requestId, "Reply $requestId"),
                initialPrefix = fixture::operatorPrefix,
                unfinishedNotice = ::unfinishedNotice,
                releaseResources = {}
            ) { session ->
                val reply = fixture.runOperator(session, "Reply $requestId").summary
                session.complete("done", reply)
                reply
            }
        }

        assertEquals("First", runDirect("first"))
        assertEquals(0, fixture.model.turnDepth)
        assertEquals("Second", runDirect("second"))
        assertEquals(0, fixture.model.turnDepth)
        val events = fixture.eventStore.eventsForThread("same-thread")
        assertEquals(2, events.count { it.eventKind == AgentEventKind.TURN_STARTED })
        assertEquals(2, events.count { it.eventKind == AgentEventKind.TURN_COMPLETED })
        assertEquals(setOf("turn:first", "turn:second"), events.mapNotNull { it.turnId }.toSet())
    }

    @Test
    fun cancellationSettlesAndClearsTheTurnBeforeASecondTaskCanStart() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val lifecycle = fixture.createLifecycle()
        val sessionReady = CompletableDeferred<TaskLifecycle.Session>()
        var releaseCalls = 0

        val job = launch {
            lifecycle.run<Unit>(
                conversationId = "cancel-thread",
                request = request("cancel", "Cancel this task"),
                initialPrefix = fixture::operatorPrefix,
                unfinishedNotice = { _, _ ->
                    TaskLifecycle.FailureNotice("cancelled", "Task cancelled", "TASK_CANCELLED")
                },
                releaseResources = { releaseCalls++ }
            ) { session ->
                session.context.getOrPutTyped { ContextMarker }
                sessionReady.complete(session)
                awaitCancellation()
            }
        }

        val cancelledSession = withTimeout(1_000L) { sessionReady.await() }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, releaseCalls)
        assertEquals(0, fixture.model.turnDepth)
        assertTrue(cancelledSession.context.getTyped<ContextMarker>() == null)
        val cancelledEvents = fixture.eventStore.eventsForTurn("turn:cancel")
        val cancelled = completedPayload(cancelledEvents)
        assertEquals("cancelled", cancelled.status)
        assertEquals("TASK_CANCELLED", cancelled.diagnosticCode)

        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Restarted"))
        val restarted = lifecycle.run(
            conversationId = "restart-thread",
            request = request("restart", "Start again"),
            initialPrefix = fixture::operatorPrefix,
            unfinishedNotice = ::unfinishedNotice,
            releaseResources = {}
        ) { session ->
            val reply = fixture.runOperator(session, "Start again").summary
            session.complete("done", reply)
            reply
        }
        assertEquals("Restarted", restarted)
        assertEquals(0, fixture.model.turnDepth)
    }

    @Test
    fun cancellationAfterLocalToolStartClosesUnknownReceiptAndTerminalExactlyOnce() = runBlocking {
        val backing = InMemoryAgentEventStore()
        val started = CompletableDeferred<Unit>()
        val store = object : AgentEventStore by backing {
            override suspend fun recordModelRoundPlan(
                plan: PreparedModelRoundCommit
            ): ModelRoundPlanRecordResult {
                val result = backing.recordModelRoundPlan(plan)
                if (backing.eventsForTurn("turn:local-cancel").any {
                        it.eventKind == AgentEventKind.TOOL_STARTED
                    }) {
                    started.complete(Unit)
                    awaitCancellation()
                }
                return result
            }
        }
        val fixture = TaskCoordinatorTestFixture(store)
        val lifecycle = fixture.createLifecycle()
        lateinit var active: AgentTurnLedger.ActiveTurn
        val job = launch {
            lifecycle.run<Unit>(
                conversationId = "local-cancel-thread",
                request = request("local-cancel", "Finish this local task"),
                initialPrefix = fixture::operatorPrefix,
                unfinishedNotice = { _, _ ->
                    TaskLifecycle.FailureNotice("cancelled", "Task cancelled", "TASK_CANCELLED")
                },
                releaseResources = {}
            ) { session ->
                active = session.durableTurn
                session.activateTools(
                    prefix = fixture.coordinator.stablePrefix(
                        mode = null, executionSpace = null,
                        toolProfile = OperatorToolProfile.LOCAL_KNOWLEDGE
                    ),
                    kind = TurnActivationKind.LOCAL, preparedAtStart = true
                )
                fixture.model.roundResponses += listOf(
                    fixture.completionCall("cancel-done", needMoreInfoPayload("Confirm the next step"), session.durableTurn.scope)
                )
                fixture.coordinator.coordinateTask(
                    taskGoal = "Finish this local task", additionalContext = "",
                    durableTurn = session.durableTurn, turnContext = "",
                    expectedBackend = null, executionStrategy = null,
                    toolProfile = OperatorToolProfile.LOCAL_KNOWLEDGE, mode = null
                )
            }
        }
        withTimeout(2_000L) { started.await() }
        job.cancelAndJoin()
        val events = backing.eventsForTurn("turn:local-cancel")
        assertEquals(1, events.count { it.eventKind == AgentEventKind.TOOL_STARTED })
        val finished = events.single { it.eventKind == AgentEventKind.TOOL_FINISHED }
        val receipt = Json.decodeFromString<ToolFinishedEventPayload>(finished.payloadJson).result
        assertEquals(ToolExecutionStatus.INTERRUPTED, receipt.executionStatus)
        assertEquals(ToolEvidenceStatus.UNKNOWN, receipt.evidenceStatus)
        assertEquals("TASK_CANCELLED", receipt.diagnosticCode)
        assertEquals("cancelled", completedPayload(events).status)
        assertEquals(0, fixture.model.turnDepth)
        fixture.turnLedger.completeWithNotice(active, "failed", "Late cleanup", "LATE_FAILURE")
        assertEquals(events, backing.eventsForTurn("turn:local-cancel"))
    }

    @Test
    fun hostCleanupFailureDoesNotReplaceTheOriginalTaskFailureAndStillClosesEverything() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val original = IllegalStateException("execution failed")
        val releaseFailure = IllegalStateException("host cleanup failed")
        val closeFailure = IllegalStateException("model close failed")
        val tracking = TrackingModelClient(fixture.model, closeFailure = closeFailure)
        val lifecycle = fixture.createLifecycle(tracking)
        var captured: TaskLifecycle.Session? = null
        var releaseSawContext = false

        val thrown = runCatching {
            lifecycle.run<Unit>(
                conversationId = "cleanup-thread",
                request = request("cleanup", "Fail during execution"),
                initialPrefix = fixture::operatorPrefix,
                unfinishedNotice = { _, _ ->
                    TaskLifecycle.FailureNotice("failed", "Task failed", "TASK_FAILED")
                },
                releaseResources = { session ->
                    releaseSawContext = session.context.getTyped<ContextMarker>() != null
                    throw releaseFailure
                }
            ) { session ->
                captured = session
                session.context.getOrPutTyped { ContextMarker }
                throw original
            }
        }.exceptionOrNull()

        assertSame(original, thrown)
        assertTrue(thrown!!.suppressed.any { it === releaseFailure })
        assertTrue(releaseFailure.suppressed.any { it === closeFailure })
        assertTrue(releaseSawContext)
        assertNotNull(captured)
        assertTrue(captured!!.context.getTyped<ContextMarker>() == null)
        assertEquals(1, tracking.createCount)
        assertEquals(1, tracking.closeCount)
        assertEquals(0, fixture.model.turnDepth)
        val completed = completedPayload(fixture.eventStore.eventsForTurn("turn:cleanup"))
        assertEquals("failed", completed.status)
        assertEquals("TASK_FAILED", completed.diagnosticCode)
    }

    @Test
    fun normalReturnWithoutSessionCompletionIsRejectedAndSettled() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val lifecycle = fixture.createLifecycle()
        var captured: TaskLifecycle.Session? = null
        var releaseCalls = 0

        val thrown = runCatching {
            lifecycle.run<String>(
                conversationId = "unfinished-thread",
                request = request("unfinished", "Return too early"),
                initialPrefix = fixture::operatorPrefix,
                unfinishedNotice = { _, error ->
                    assertTrue(error is IllegalStateException)
                    TaskLifecycle.FailureNotice("failed", "Task did not settle", "UNFINISHED_TASK")
                },
                releaseResources = { releaseCalls++ }
            ) { session ->
                captured = session
                session.context.getOrPutTyped { ContextMarker }
                "returned before complete"
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("Task returned without terminal settlement", thrown?.message)
        assertEquals(1, releaseCalls)
        assertNotNull(captured)
        assertTrue(captured!!.context.getTyped<ContextMarker>() == null)
        assertEquals(0, fixture.model.turnDepth)
        val completed = completedPayload(fixture.eventStore.eventsForTurn("turn:unfinished"))
        assertEquals("failed", completed.status)
        assertEquals("UNFINISHED_TASK", completed.diagnosticCode)
    }

    @Test
    fun modelSessionAcquireFailureIsPrimaryAndDoesNotPretendALeaseWasAcquired() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val acquireFailure = IllegalStateException("model acquire failed")
        val tracking = TrackingModelClient(fixture.model, acquireFailure = acquireFailure)
        val lifecycle = fixture.createLifecycle(tracking)
        var prefixCalled = false
        var noticeCalled = false
        var releaseCalled = false

        val thrown = runCatching {
            lifecycle.run<Unit>(
                conversationId = "acquire-thread",
                request = request("acquire", "Cannot acquire"),
                initialPrefix = {
                    prefixCalled = true
                    fixture.operatorPrefix()
                },
                unfinishedNotice = { _, _ ->
                    noticeCalled = true
                    TaskLifecycle.FailureNotice("failed", "unexpected", "UNEXPECTED")
                },
                releaseResources = { releaseCalled = true }
            ) {
                error("execution must not run")
            }
        }.exceptionOrNull()

        assertSame(acquireFailure, thrown)
        assertEquals(1, tracking.createCount)
        assertEquals(0, tracking.closeCount)
        assertFalse(prefixCalled)
        assertFalse(noticeCalled)
        assertFalse(releaseCalled)
        assertEquals(0, fixture.model.turnDepth)
        assertTrue(fixture.eventStore.eventsForThread("acquire-thread").isEmpty())
    }

    private fun request(requestId: String, text: String): TurnRequest = TurnRequest(
        requestId = requestId,
        source = MessageSource.DIRECT,
        rawText = text,
        images = emptyList(),
        rawHash = sha256Hex(text),
        conversationId = "test"
    )

    private fun unfinishedNotice(
        session: TaskLifecycle.Session,
        cause: Throwable
    ): TaskLifecycle.FailureNotice =
        TaskLifecycle.FailureNotice("failed", "Task did not finish", "TASK_UNFINISHED")

    private suspend fun completedPayload(
        events: List<com.droidance.core.agent.event.AgentEventEnvelope>
    ): TurnCompletedEventPayload {
        val completed = events.filter { it.eventKind == AgentEventKind.TURN_COMPLETED }
        assertEquals(1, completed.size)
        return Json.decodeFromString(completed.single().payloadJson)
    }

    private data object ContextMarker

    private class TrackingModelClient(
        private val delegate: ModelClient,
        private val acquireFailure: Throwable? = null,
        private val closeFailure: Throwable? = null
    ) : ModelClient by delegate {
        var createCount = 0
        var closeCount = 0

        override fun createSession(initialPlan: com.droidance.core.model.llm.ModelStreamPlan?): ModelSession {
            createCount++
            acquireFailure?.let { throw it }
            val delegatedSession = delegate.createSession(initialPlan)
            return object : ModelSession by delegatedSession {
                override fun close() {
                    closeCount++
                    try {
                        delegatedSession.close()
                    } finally {
                        closeFailure?.let { throw it }
                    }
                }
            }
        }
    }

    private companion object {
        fun call(id: String, name: String, arguments: String): LLMStreamEvent.ToolCallChunk =
            LLMStreamEvent.ToolCallChunk(
                index = id.hashCode().and(Int.MAX_VALUE),
                id = id,
                name = name,
                arguments = arguments
            )

        fun needMoreInfoPayload(unresolved: String): String =
            """{"status":"need_more_info","deliverables":[],"results":[{"text":"Need user input","evidence_refs":[]}],"unresolved_requirements":["$unresolved"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}] }"""
    }
}
