// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the application-owned submission boundary with a real coroutine Job.
 * The runtime is fake, but the controller's registration, cancellation, and cleanup
 * paths are exercised through the production CoroutineScope and suspend callback.
 */
class ConversationTaskControllerTest {
    @Test
    fun submitRegistersBeforeExecutionAndClearsTheExpectedJobAfterCompletion() = runBlocking {
        val runtime = FakeRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val completed = mutableListOf<AgentRuntime.RuntimeTurnOutcome>()
            val failures = mutableListOf<Exception>()
            var finished = 0
            val expected = outcome("completed")

            val job = controller.submit(
                conversationId = "conversation-a",
                request = request("request-a"),
                onStream = {},
                onCompleted = { completed += it },
                onFailure = { failures += it },
                onFinished = { finished++; runtime.events += "finished" }
            )

            assertEquals(listOf("register", "process:conversation-a"), runtime.events)
            assertSame(job, runtime.registeredJob)
            assertSame(job, runtime.activeJob)

            runtime.processResult.complete(expected)
            withTimeout(1_000L) { job.join() }

            assertEquals(listOf(expected), completed)
            assertTrue(failures.isEmpty())
            assertEquals(1, finished)
            assertSame(job, runtime.unregisteredJob)
            assertNull(runtime.activeJob)
            assertEquals(
                listOf("register", "process:conversation-a", "finished", "unregister"),
                runtime.events
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationCallsFailureAndFinishedCallbacksBeforeIdentityCleanup() = runBlocking {
        val runtime = FakeRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val failures = mutableListOf<Exception>()
            var finished = 0

            val job = controller.submit(
                conversationId = "conversation-a",
                request = request("request-cancel"),
                onStream = {},
                onCompleted = { error("cancelled task must not complete") },
                onFailure = { failures += it; runtime.events += "failure" },
                onFinished = { finished++; runtime.events += "finished" }
            )

            assertTrue(runtime.processStarted)
            job.cancelAndJoin()

            assertEquals(1, failures.size)
            assertTrue(failures.single() is CancellationException)
            assertEquals(1, finished)
            assertSame(job, runtime.unregisteredJob)
            assertNull(runtime.activeJob)
            assertEquals(
                listOf("register", "process:conversation-a", "failure", "finished", "unregister"),
                runtime.events
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun submittingAnotherConversationKeepsTheOldTaskAliveBeforeStartingTheNewOne() = runBlocking {
        val runtime = FakeRuntime().apply {
            conversation.value = "conversation-old"
            stateFlow.value = AgentState.THINKING
        }
        val oldJob = Job()
        runtime.registerActiveJob("ui", oldJob)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val newJob = controller.submit(
                conversationId = "conversation-new",
                request = request("request-new"),
                onStream = {},
                onCompleted = {},
                onFailure = { error("new task should complete") },
                onFinished = { runtime.events += "finished" }
            )

            assertFalse(oldJob.isCancelled)
            assertEquals(
                listOf(
                    "register",
                    "register",
                    "process:conversation-new"
                ),
                runtime.events
            )
            runtime.processResult.complete(outcome("new"))
            withTimeout(1_000L) { newJob.join() }
            assertEquals(
                listOf(
                    "register",
                    "register",
                    "process:conversation-new",
                    "finished",
                    "unregister"
                ),
                runtime.events
            )
            assertNull(runtime.activeJob)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun preCancelledScopeStillFinishesAndUnregistersTheLazySubmission() = runBlocking {
        val runtime = FakeRuntime()
        val parent = SupervisorJob().also { it.cancel() }
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            var finished = 0

            val job = controller.submit(
                conversationId = "conversation-a",
                request = request("request-pre-cancelled"),
                onStream = {},
                onCompleted = { error("a cancelled scope must not execute the request") },
                onFailure = { error("a lazy job cancelled before start has no failure callback") },
                onFinished = { finished++; runtime.events += "finished" }
            )

            withTimeout(1_000L) { job.join() }

            assertTrue(job.isCancelled)
            assertFalse(runtime.processStarted)
            assertEquals(1, finished)
            assertSame(job, runtime.unregisteredJob)
            assertNull(runtime.activeJob)
            assertEquals(listOf("register", "finished", "unregister"), runtime.events)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun activeTaskPublishesStreamingTextAndClearsOnCompletion() = runBlocking {
        val runtime = FakeRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val streamChunks = mutableListOf<String>()
            val job = controller.submit(
                conversationId = "conversation-a",
                request = request("request-a"),
                onStream = { streamChunks += it.text },
                onCompleted = {},
                onFailure = {},
                onFinished = {}
            )

            assertEquals("conversation-a", controller.activeTasks.value["conversation-a"]?.single()?.conversationId)
            assertEquals("turn:request-a", controller.activeTasks.value["conversation-a"]?.single()?.turnId)
            assertEquals("", controller.activeTasks.value["conversation-a"]?.single()?.streamingByRound?.values?.joinToString(""))

            runtime.onStreamCapture?.invoke(AssistantStreamChunk("Hello ", null))
            runtime.onStreamCapture?.invoke(AssistantStreamChunk("world", null))

            assertEquals("Hello world", controller.activeTasks.value["conversation-a"]?.single()?.streamingByRound?.values?.joinToString(""))
            assertEquals(listOf("Hello ", "world"), streamChunks)

            runtime.processResult.complete(outcome("done"))
            withTimeout(1_000L) { job.join() }

            assertTrue(controller.activeTasks.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun queuedConversationKeepsRunningConversationSnapshotAndStreamsSeparate() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            val b = submit(controller, "conversation-b", "b")
            assertEquals(setOf("conversation-a", "conversation-b"), controller.activeTasks.value.keys)
            assertFalse(runtime.started("b").isCompleted)
            runtime.streams.getValue("a").invoke(AssistantStreamChunk("A is still running", null))
            assertEquals("A is still running", controller.activeTasks.value.getValue("conversation-a").single().streamingByRound.values.joinToString(""))
            assertEquals("", controller.activeTasks.value.getValue("conversation-b").single().streamingByRound.values.joinToString(""))

            runtime.result("a").complete(outcome("A done"))
            withTimeout(1_000) { a.join(); runtime.started("b").await() }
            assertEquals(setOf("conversation-b"), controller.activeTasks.value.keys)
            assertEquals(1, runtime.maxConcurrentTurns)
            runtime.result("b").complete(outcome("B done"))
            withTimeout(1_000) { b.join() }
            assertTrue(controller.activeTasks.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun stoppingQueuedConversationDoesNotCancelRunningTurnOrItsProvider() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            val b = submit(controller, "conversation-b", "b")
            controller.stop("conversation-b", "chat_stop")
            withTimeout(1_000) { b.join() }
            assertTrue(b.isCancelled)
            assertFalse(a.isCancelled)
            assertEquals(0, runtime.providerCancellations)
            assertEquals(setOf("conversation-a"), controller.activeTasks.value.keys)
            runtime.streams.getValue("a").invoke(AssistantStreamChunk("Still live", null))
            assertEquals("Still live", controller.activeTasks.value.getValue("conversation-a").single().streamingByRound.values.joinToString(""))

            controller.stop("conversation-a", "chat_stop")
            withTimeout(1_000) { a.join() }
            assertTrue(a.isCancelled)
            assertEquals(1, runtime.providerCancellations)
            assertTrue(controller.activeTasks.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun disposingScreenDoesNotCancelAdmissionWaitingForExistingTurnToStart() = runBlocking {
        val runtime = SerializedRuntime()
        val startGate = CompletableDeferred<Unit>()
        runtime.beforeStart["a"] = startGate
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, appScope)
            val a = submit(controller, "conversation-a", "a")
            lateinit var admission: Job
            var resubmissions = 0
            screenScope.launch {
                admission = controller.dispatch("conversation-a", request("steer"), ConversationSubmissionMode.STEER) { resubmissions++ }
            }.join()
            assertTrue(admission.isActive)
            screenScope.cancel()
            assertFalse(admission.isCancelled)
            startGate.complete(Unit)
            withTimeout(1_000) { admission.join() }
            assertEquals(listOf("steer"), runtime.steeredRequests)
            assertEquals(0, resubmissions)
            assertFalse(a.isCancelled)
            runtime.result("a").complete(outcome("done"))
            withTimeout(1_000) { a.join() }
        } finally { screenScope.cancel(); appScope.cancel() }
    }

    @Test fun sameConversationUnsteerableTurnCanQueueWithoutLosingRunningSnapshotAndStopCancelsBoth() = runBlocking {
        val runtime = SerializedRuntime().apply { acceptsSteering = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            lateinit var b: Job
            controller.dispatch("conversation-a", request("b")) { b = submit(controller, "conversation-a", "b") }.join()
            assertEquals(listOf("turn:a", "turn:b"), controller.activeTasks.value.getValue("conversation-a").map { it.turnId })
            assertEquals(listOf(true, false), controller.activeTasks.value.getValue("conversation-a").map { it.started })
            runtime.streams.getValue("a").invoke(AssistantStreamChunk("A is finishing", null))
            assertEquals("A is finishing", controller.activeTasks.value.getValue("conversation-a").first().streamingByRound.values.joinToString(""))
            assertFalse(runtime.started("b").isCompleted)
            controller.stop("conversation-a", "chat_stop")
            withTimeout(1_000) { a.join(); b.join() }
            assertTrue(a.isCancelled)
            assertTrue(b.isCancelled)
            assertFalse(runtime.started("b").isCompleted)
            assertEquals(1, runtime.providerCancellations)
            assertTrue(controller.activeTasks.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun clearingOneConversationCancelsItsRunningAndQueuedTurnsAndPreservesAnotherQueue() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            val b = submit(controller, "conversation-a", "b")
            val c = submit(controller, "conversation-c", "c")
            controller.clear("conversation-a", "clear_messages")
            withTimeout(1_000) { a.join(); b.join(); runtime.started("c").await() }
            assertTrue(a.isCancelled)
            assertTrue(b.isCancelled)
            assertFalse(c.isCancelled)
            assertEquals(setOf("conversation-c"), controller.activeTasks.value.keys)
            runtime.result("c").complete(outcome("done"))
            withTimeout(1_000) { c.join() }
        } finally { scope.cancel() }
    }

    @Test fun stoppingTurnDuringAdmissionDoesNotResubmitWaitingInputAndLaterInputCanRun() = runBlocking {
        val runtime = SerializedRuntime()
        runtime.beforeStart["a"] = CompletableDeferred()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            var resubmissions = 0
            val pending = controller.dispatch("conversation-a", request("waiting-input"), ConversationSubmissionMode.STEER) { resubmissions++ }
            assertTrue(pending.isActive)
            controller.stop("conversation-a", "chat_stop")
            withTimeout(1_000) { a.join(); pending.join() }
            assertTrue(pending.isCancelled)
            assertEquals(0, resubmissions)
            assertTrue(controller.activeTasks.value.isEmpty())
            lateinit var later: Job
            controller.dispatch("conversation-a", request("later")) { later = submit(controller, "conversation-a", "later") }.join()
            assertFalse(later.isCancelled)
            runtime.result("later").complete(outcome("Later done"))
            withTimeout(1_000) { later.join() }
        } finally { scope.cancel() }
    }

    @Test fun historyMutationBlocksAdmissionUntilDurableSettlementAndCleanupFinish() = runBlocking {
        val runtime = SerializedRuntime().apply { settlementGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cleanup = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        try {
            val controller = ConversationTaskController(runtime, scope)
            val active = submit(controller, "conversation-a", "active-delete")
            val deleting = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                controller.withHistoryMutation(setOf("conversation-a")) {
                    entered.complete(Unit)
                    cleanup.await()
                }
            }
            runtime.settlementStarted.await()
            assertFalse(entered.isCompleted)
            var prepared = false
            val rejected = controller.dispatch("conversation-a", request("late"), prepareRequest = {
                prepared = true
                request("late")
            }) { error("Deleted conversation must not be submitted") }
            rejected.join()
            assertTrue(rejected.isCancelled)
            assertFalse(prepared)
            runtime.settlementGate!!.complete(Unit)
            withTimeout(1_000) { entered.await() }
            assertTrue(active.isCompleted)
            assertTrue(runCatching { submit(controller, "conversation-a", "late-submit") }.exceptionOrNull() is CancellationException)
            cleanup.complete(Unit)
            withTimeout(1_000) { deleting.join() }
            val later = submit(controller, "conversation-a", "after-cleanup")
            runtime.result("after-cleanup").complete(outcome("New conversation"))
            withTimeout(1_000) { later.join() }
        } finally {
            cleanup.complete(Unit)
            runtime.settlementGate?.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun bulkHistoryMutationBlocksNewThreadsAndReleasesAdmissionAfterFailure() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val failure = runCatching {
                controller.withHistoryMutation(null) {
                    val late = controller.dispatch("brand-new-thread", request("new")) { error("Blocked") }
                    late.join()
                    assertTrue(late.isCancelled)
                    throw java.io.IOException("Cleanup failed")
                }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            val later = submit(controller, "brand-new-thread", "retry")
            runtime.result("retry").complete(outcome("Available after failure"))
            withTimeout(1_000) { later.join() }
        } finally { scope.cancel() }
    }

    private fun submit(controller: ConversationTaskController, conversation: String, id: String): Job =
        controller.submit(conversation, request(id), {}, {}, {}, {})

    @Test fun destructiveStopWaitsForSettlementAndCancelsOnlyTheTargetConversation() = runBlocking {
        val runtime = SerializedRuntime().apply { settlementGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val active = submit(controller, "conversation-a", "active")
            val queued = submit(controller, "conversation-a", "queued")
            val unrelated = submit(controller, "conversation-b", "unrelated")
            val stopping = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                controller.stopAndAwait("conversation-a", "conversation_deleted")
            }

            runtime.settlementStarted.await()
            assertEquals(AgentState.IDLE, runtime.state.value)
            assertTrue(active.isCancelled)
            assertTrue(queued.isCancelled)
            assertFalse(stopping.isCompleted)
            assertFalse(unrelated.isCancelled)
            assertFalse(runtime.started("queued").isCompleted)

            runtime.settlementGate!!.complete(Unit)
            withTimeout(1_000) { stopping.join(); runtime.started("unrelated").await() }
            assertTrue(active.isCompleted)
            assertTrue(queued.isCompleted)
            assertFalse(controller.activeTasks.value.containsKey("conversation-a"))
            runtime.result("unrelated").complete(outcome("Still runs"))
            withTimeout(1_000) { unrelated.join() }
        } finally {
            runtime.settlementGate?.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun destructiveStopAlsoWaitsForAttachmentAdmissionCleanup() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cleanup = CompletableDeferred<Unit>()
        try {
            val controller = ConversationTaskController(runtime, scope)
            var submitted = false
            val admission = controller.dispatch("conversation-a", request("image"), prepareRequest = {
                try {
                    CompletableDeferred<Unit>().await()
                    request("image")
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cleanup.await() }
                }
            }) { submitted = true }
            val stopping = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                controller.stopAndAwait("conversation-a", "conversation_deleted")
            }
            assertTrue(admission.isCancelled)
            assertFalse(stopping.isCompleted)
            cleanup.complete(Unit)
            withTimeout(1_000) { stopping.join() }
            assertTrue(admission.isCompleted)
            assertFalse(submitted)
            assertTrue(controller.activeTasks.value.isEmpty())
        } finally {
            cleanup.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun destructiveStopStillWaitsAfterCancellationHasClearedTheVisibleConversation() = runBlocking {
        val runtime = SerializedRuntime().apply { settlementGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val active = submit(controller, "conversation-a", "already-cancelled")
            controller.clear("conversation-a", "chat_cleared")
            runtime.settlementStarted.await()
            assertTrue(active.isCancelled)
            assertFalse(active.isCompleted)
            assertTrue(controller.activeTasks.value.isEmpty())

            val stopping = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                controller.stopAndAwait("conversation-a", "conversation_deleted")
            }
            assertFalse(stopping.isCompleted)
            runtime.settlementGate!!.complete(Unit)
            withTimeout(1_000) { stopping.join() }
            assertTrue(active.isCompleted)
        } finally {
            runtime.settlementGate?.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun defaultSubmissionQueuesInOrderWithoutSteeringOrCancellingTheRunningTurn() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            lateinit var b: Job
            lateinit var c: Job
            controller.dispatch("conversation-a", request("b")) { b = submit(controller, "conversation-a", "b") }.join()
            controller.dispatch("conversation-a", request("c")) { c = submit(controller, "conversation-a", "c") }.join()
            assertTrue(runtime.steeredRequests.isEmpty())
            assertFalse(a.isCancelled)
            assertFalse(runtime.started("b").isCompleted)
            assertFalse(runtime.started("c").isCompleted)
            assertEquals(listOf(true, false, false), controller.activeTasks.value.getValue("conversation-a").map { it.started })
            runtime.result("a").complete(outcome("A done"))
            withTimeout(1_000) { a.join(); runtime.started("b").await() }
            assertFalse(runtime.started("c").isCompleted)
            runtime.result("b").complete(outcome("B done"))
            withTimeout(1_000) { b.join(); runtime.started("c").await() }
            runtime.result("c").complete(outcome("C done"))
            withTimeout(1_000) { c.join() }
            assertEquals(1, runtime.maxConcurrentTurns)
            assertEquals(0, runtime.providerCancellations)
            assertTrue(controller.activeTasks.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun explicitSteeringTargetsTheRunningTurnEvenWhenAnotherMessageIsQueued() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            val b = submit(controller, "conversation-a", "b")
            var queuedInstead = false
            controller.dispatch("conversation-a", request("guidance"), ConversationSubmissionMode.STEER) { queuedInstead = true }.join()
            assertEquals(listOf("guidance"), runtime.steeredRequests)
            assertFalse(queuedInstead)
            assertFalse(a.isCancelled)
            assertFalse(b.isCancelled)
            assertEquals(0, runtime.providerCancellations)
            runtime.result("a").complete(outcome("A done"))
            withTimeout(1_000) { a.join(); runtime.started("b").await() }
            runtime.result("b").complete(outcome("B done"))
            withTimeout(1_000) { b.join() }
        } finally { scope.cancel() }
    }

    @Test fun attachmentPreparationKeepsSubmissionOrderAndStopCannotResubmitIt() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val ready = CompletableDeferred<Unit>()
            val admitted = mutableListOf<TurnRequest>()
            val image = controller.dispatch("conversation-a", request("image"), prepareRequest = {
                ready.await()
                request("image").copy(images = listOf("pixels"))
            }) { admitted += it }
            val text = controller.dispatch("conversation-a", request("text")) { admitted += it }
            assertTrue(admitted.isEmpty())
            ready.complete(Unit)
            withTimeout(1_000) { image.join(); text.join() }
            assertEquals(listOf("image", "text"), admitted.map { it.requestId })
            assertEquals(listOf("pixels"), admitted.first().images)
            val blocked = controller.dispatch("conversation-a", request("blocked"), prepareRequest = {
                CompletableDeferred<Unit>().await()
                request("blocked")
            }) { admitted += it }
            controller.stop("conversation-a", "chat_stop")
            withTimeout(1_000) { blocked.join() }
            assertTrue(blocked.isCancelled)
            assertEquals(2, admitted.size)
        } finally { scope.cancel() }
    }

    @Test fun deletingOneQueueCardCannotCancelTheProviderOrAnotherQueuedMessage() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            val b = submit(controller, "conversation-a", "b")
            val c = submit(controller, "conversation-a", "c")
            assertFalse(controller.deleteQueued("conversation-other", "turn:b"))
            assertFalse(controller.deleteQueued("conversation-a", "turn:a"))
            assertTrue(controller.deleteQueued("conversation-a", "turn:b"))
            b.join()
            assertFalse(a.isCancelled)
            assertFalse(c.isCancelled)
            assertEquals(0, runtime.providerCancellations)
            runtime.result("a").complete(outcome("A done"))
            withTimeout(1_000) { a.join(); runtime.started("c").await() }
            assertFalse(runtime.started("b").isCompleted)
            runtime.result("c").complete(outcome("C done"))
            c.join()
        } finally { scope.cancel() }
    }

    @Test fun queuedGuidanceIsDeliveredOnceAndNeverStartsItsOwnTurn() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            val b = submit(controller, "conversation-a", "b")
            assertTrue(controller.steerQueued("conversation-a", "turn:b"))
            b.join()
            assertFalse(controller.steerQueued("conversation-a", "turn:b"))
            assertEquals(listOf("b"), runtime.steeredRequests)
            assertFalse(a.isCancelled)
            assertEquals(0, runtime.providerCancellations)
            runtime.result("a").complete(outcome("A done"))
            a.join()
            assertFalse(runtime.started("b").isCompleted)
            assertTrue(controller.activeTasks.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun finishingDuringSteerKeepsTheOriginalQueueSlotAndOrder() = runBlocking {
        val runtime = SerializedRuntime().apply { steeringGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val a = submit(controller, "conversation-a", "a")
            val b = submit(controller, "conversation-a", "b")
            val c = submit(controller, "conversation-a", "c")
            var accepted = true
            val action = launch { accepted = controller.steerQueued("conversation-a", "turn:b") }
            kotlinx.coroutines.yield()
            runtime.result("a").complete(outcome("A done"))
            a.join()
            assertFalse(runtime.started("b").isCompleted)
            assertFalse(controller.deleteQueued("conversation-a", "turn:b"))
            runtime.steeringGate!!.complete(Unit)
            action.join()
            assertFalse(accepted)
            withTimeout(1_000) { runtime.started("b").await() }
            assertFalse(runtime.started("c").isCompleted)
            runtime.result("b").complete(outcome("B done"))
            withTimeout(1_000) { b.join(); runtime.started("c").await() }
            runtime.result("c").complete(outcome("C done"))
            c.join()
            assertEquals(0, runtime.providerCancellations)
        } finally { scope.cancel() }
    }

    @Test fun imagePreparationIsAVisibleDeletableSlotAndCannotSubmitAfterDeletion() = runBlocking {
        val runtime = SerializedRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ConversationTaskController(runtime, scope)
            val gate = CompletableDeferred<Unit>()
            var submitted = false
            val preparation = controller.dispatch("conversation-a", request("image"), prepareRequest = {
                gate.await()
                request("image").copy(images = listOf("pixels"))
            }) { submitted = true }
            assertEquals("turn:image", controller.activeTasks.value.getValue("conversation-a").single().turnId)
            assertTrue(controller.deleteQueued("conversation-a", "turn:image"))
            preparation.join()
            gate.complete(Unit)
            assertFalse(submitted)
            assertTrue(controller.activeTasks.value.isEmpty())
            val synchronous = controller.dispatch("conversation-a", request("text"), prepareRequest = {
                controller.stop("conversation-a", "stop_during_prepare")
                request("text")
            }) { submitted = true }
            synchronous.join()
            assertTrue(synchronous.isCancelled)
            assertFalse(submitted)
            assertTrue(controller.activeTasks.value.isEmpty())
        } finally { scope.cancel() }
    }

    /** Models the production single-runtime mutex and the actual TurnJobs cancellation identity. */
    private class SerializedRuntime : ConversationTaskRuntime {
        private val mutex = Mutex()
        private val jobs = TurnJobs()
        private val conversation = MutableStateFlow<String?>(null)
        private val stateFlow = MutableStateFlow(AgentState.IDLE)
        private val results = mutableMapOf<String, CompletableDeferred<AgentRuntime.RuntimeTurnOutcome>>()
        private val starts = mutableMapOf<String, CompletableDeferred<Unit>>()
        val beforeStart = mutableMapOf<String, CompletableDeferred<Unit>>()
        val streams = mutableMapOf<String, suspend (AssistantStreamChunk) -> Unit>()
        val steeredRequests = mutableListOf<String>()
        private var running = 0
        var maxConcurrentTurns = 0
        var providerCancellations = 0
        var acceptsSteering = true
        var steeringGate: CompletableDeferred<Unit>? = null
        var settlementGate: CompletableDeferred<Unit>? = null
        val settlementStarted = CompletableDeferred<Unit>()
        override val currentConversationId: StateFlow<String?> = conversation
        override val state: StateFlow<AgentState> = stateFlow
        fun result(id: String) = results.getOrPut(id) { CompletableDeferred() }
        fun started(id: String) = starts.getOrPut(id) { CompletableDeferred() }
        override fun registerActiveJob(taskId: String, job: Job) { jobs.register(taskId, job) }
        override fun unregisterActiveJob(taskId: String, expectedJob: Job) { jobs.release(taskId, expectedJob) }
        override fun cancelCurrentTask(taskId: String?, origin: String) {
            val targets = jobs.cancellationTargets(taskId)
            if (targets.any { it === jobs.runningJob }) {
                providerCancellations++
                stateFlow.value = AgentState.IDLE
            }
            targets.forEach { it.cancel() }
        }
        override fun clearConversation() { conversation.value = null }
        override suspend fun steerConversationInput(conversationId: String, turnId: String, request: TurnRequest): Boolean {
            steeringGate?.await()
            if (!acceptsSteering || conversation.value != conversationId || stateFlow.value == AgentState.IDLE) return false
            steeredRequests += request.requestId
            return true
        }
        override suspend fun processConversationInput(
            conversationId: String, request: TurnRequest, onStreamContent: suspend (AssistantStreamChunk) -> Unit, onTurnStarted: () -> Unit
        ): AgentRuntime.RuntimeTurnOutcome = mutex.withLock {
            val job = currentCoroutineContext()[Job]!!
            jobs.begin("ui", job)
            conversation.value = conversationId
            stateFlow.value = AgentState.THINKING
            running++
            maxConcurrentTurns = maxOf(maxConcurrentTurns, running)
            streams[request.requestId] = onStreamContent
            try {
                beforeStart[request.requestId]?.await()
                onTurnStarted()
                started(request.requestId).complete(Unit)
                result(request.requestId).await()
            } finally {
                settlementStarted.complete(Unit)
                settlementGate?.let { gate ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                }
                running--
                stateFlow.value = AgentState.IDLE
                jobs.release("ui", job)
            }
        }
    }

    private fun request(id: String): TurnRequest = TurnRequest(
        requestId = id,
        source = MessageSource.DIRECT,
        rawText = "request $id",
        images = emptyList(),
        rawHash = id,
        conversationId = "test"
    )

    private fun outcome(response: String) = AgentRuntime.RuntimeTurnOutcome(
        response = response,
        status = "done",
        completion = null
    )

    private class FakeRuntime : ConversationTaskRuntime {
        val conversation = MutableStateFlow<String?>("conversation-a")
        val stateFlow = MutableStateFlow(AgentState.IDLE)
        val events = mutableListOf<String>()
        val processResult = CompletableDeferred<AgentRuntime.RuntimeTurnOutcome>()
        var processStarted = false
        var registeredJob: Job? = null
        var activeJob: Job? = null
        var unregisteredJob: Job? = null
        var onStreamCapture: (suspend (AssistantStreamChunk) -> Unit)? = null

        override val currentConversationId: StateFlow<String?> = conversation
        override val state: StateFlow<AgentState> = stateFlow

        override fun registerActiveJob(taskId: String, job: Job) {
            assertTrue(taskId == "ui" || taskId.startsWith("ui:turn:"))
            events += "register"
            registeredJob = job
            activeJob = job
        }

        override fun unregisterActiveJob(taskId: String, expectedJob: Job) {
            assertTrue(taskId == "ui" || taskId.startsWith("ui:turn:"))
            events += "unregister"
            unregisteredJob = expectedJob
            if (activeJob === expectedJob) activeJob = null
        }

        override suspend fun steerConversationInput(
            conversationId: String,
            turnId: String,
            request: TurnRequest
        ): Boolean = false

        override fun cancelCurrentTask(taskId: String?, origin: String) {
            events += "cancel:$origin"
            activeJob?.cancel()
            stateFlow.value = AgentState.IDLE
        }

        override fun clearConversation() {
            conversation.value = null
        }

        override suspend fun processConversationInput(
            conversationId: String,
            request: TurnRequest,
            onStreamContent: suspend (AssistantStreamChunk) -> Unit,
            onTurnStarted: () -> Unit
        ): AgentRuntime.RuntimeTurnOutcome {
            onTurnStarted()
            processStarted = true
            events += "process:$conversationId"
            onStreamCapture = onStreamContent
            return processResult.await()
        }
    }
}
