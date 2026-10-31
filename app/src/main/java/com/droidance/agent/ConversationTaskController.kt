// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Host operations needed by application-owned conversation submission. */
interface ConversationTaskRuntime {
    val currentConversationId: kotlinx.coroutines.flow.StateFlow<String?>
    val state: kotlinx.coroutines.flow.StateFlow<AgentState>
    fun registerActiveJob(taskId: String, job: Job)
    fun unregisterActiveJob(taskId: String, expectedJob: Job)
    fun cancelCurrentTask(taskId: String? = null, origin: String = "runtime")
    fun clearConversation()
    suspend fun steerConversationInput(conversationId: String, turnId: String, request: TurnRequest): Boolean
    suspend fun processConversationInput(
        conversationId: String,
        request: TurnRequest,
        onStreamContent: suspend (AssistantStreamChunk) -> Unit,
        onTurnStarted: () -> Unit
    ): AgentRuntime.RuntimeTurnOutcome
}

data class ActiveConversationTask(
    val conversationId: String,
    val turnId: String,
    val request: TurnRequest,
    val submittedAtMs: Long,
    val streamingByRound: Map<String?, String> = emptyMap(),
    val started: Boolean = false
)

enum class ConversationSubmissionMode { QUEUE, STEER }

/** Application-owned submission and Job lifetime; screens only observe presentation callbacks. */
@Singleton
class ConversationTaskController @Inject constructor(
    private val runtime: ConversationTaskRuntime,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _activeTasks = MutableStateFlow<Map<String, List<ActiveConversationTask>>>(emptyMap())
    val activeTasks: StateFlow<Map<String, List<ActiveConversationTask>>> = _activeTasks

    private class Submission(val conversationId: String, val turnId: String,
        val ready: kotlinx.coroutines.CompletableDeferred<Unit>, val request: TurnRequest) {
        lateinit var job: Job
        var enteringRuntime = false
        var removed = false
        var controlGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    }
    private val submissionLock = Any()
    private val executionMutex = Mutex()
    private val submissions = java.util.concurrent.ConcurrentHashMap<String, Submission>()
    private data class Admission(val conversationId: String, val turnId: String, val job: Job)
    private val admissions = java.util.concurrent.ConcurrentHashMap<Job, Admission>()
    private val admissionMutex = Mutex()
    private val historyMutationMutex = Mutex()
    private var deletingAllHistory = false
    private var deletingConversations: Set<String> = emptySet()

    private fun requireConversationWritable(conversationId: String) {
        if (deletingAllHistory || conversationId in deletingConversations) {
            throw CancellationException("Conversation history is being deleted")
        }
    }

    /** Block new admission before cancelling and durably settling the selected UI jobs. */
    suspend fun <T> withHistoryMutation(conversationIds: Set<String>?, block: suspend () -> T): T =
        historyMutationMutex.withLock {
            try {
                val jobs = synchronized(submissionLock) {
                    deletingAllHistory = conversationIds == null
                    deletingConversations = conversationIds.orEmpty()
                    val selected = (admissions.values.map { it.conversationId } + submissions.values.map { it.conversationId })
                        .filter { conversationIds == null || it in conversationIds }.toSet()
                    val jobs = (admissions.values.filter { it.conversationId in selected }.map { it.job } +
                        submissions.values.filter { it.conversationId in selected }.map { it.job }).distinct()
                    selected.forEach { stop(it, "conversation_deleted") }
                    jobs.forEach { it.cancel() }
                    jobs
                }
                jobs.forEach { it.join() }
                block()
            } finally {
                synchronized(submissionLock) {
                    deletingAllHistory = false
                    deletingConversations = emptySet()
                }
            }
        }

    suspend fun steer(conversationId: String, request: TurnRequest): Boolean {
        val tasks = _activeTasks.value[conversationId].orEmpty()
        val target = tasks.firstOrNull { it.started } ?: tasks.firstOrNull() ?: return false
        val active = submissions[target.turnId] ?: return false
        active.ready.await()
        if (submissions[active.turnId] !== active) return false
        return runtime.steerConversationInput(conversationId, active.turnId, request)
    }

    /** Admission also survives disposal of the screen while an existing Turn starts. */
    fun dispatch(conversationId: String, request: TurnRequest,
        mode: ConversationSubmissionMode = ConversationSubmissionMode.QUEUE,
        prepareRequest: suspend () -> TurnRequest = { request },
        submitNewTurn: (TurnRequest) -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val ownJob = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
            val turnId = "turn:${request.requestId}"
            val admission = Admission(conversationId, turnId, ownJob)
            synchronized(submissionLock) {
                requireConversationWritable(conversationId)
                admissions[ownJob] = admission
            }
            _activeTasks.update { tasks -> tasks + (conversationId to
                (tasks[conversationId].orEmpty() + ActiveConversationTask(
                    conversationId, turnId, request, System.currentTimeMillis()))) }
            try {
                // Attachment preparation is part of this visible queue slot, and a
                // later text message cannot overtake it or survive an explicit stop.
                admissionMutex.withLock {
                    val prepared = prepareRequest()
                    val enqueue = mode == ConversationSubmissionMode.QUEUE || !steer(conversationId, prepared)
                    synchronized(submissionLock) {
                        if (!ownJob.isActive) throw CancellationException("Input admission cancelled")
                        if (enqueue) submitNewTurn(prepared)
                    }
                }
            } finally {
                admissions.remove(ownJob, admission)
                if (!submissions.containsKey(turnId)) removeTask(conversationId, turnId)
            }
        }
        return job
    }

    fun submit(
        conversationId: String,
        request: TurnRequest,
        onStream: suspend (AssistantStreamChunk) -> Unit,
        onCompleted: (AgentRuntime.RuntimeTurnOutcome) -> Unit,
        onFailure: (Exception) -> Unit,
        onFinished: () -> Unit
    ): Job {
        synchronized(submissionLock) { requireConversationWritable(conversationId) }
        val turnId = "turn:${request.requestId}"
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val submission = Submission(conversationId, turnId, started, request)
        // Register before execution can finish or receive a stop request.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val wrappedStream: suspend (AssistantStreamChunk) -> Unit = { chunk ->
                    _activeTasks.update { tasks ->
                        val current = tasks[conversationId] ?: return@update tasks
                        tasks + (conversationId to current.map {
                            if (it.turnId == turnId) it.copy(streamingByRound = it.streamingByRound +
                                (chunk.roundId to (it.streamingByRound[chunk.roundId].orEmpty() + chunk.text))) else it
                        })
                    }
                    onStream(chunk)
                }
                onCompleted(executionMutex.withLock {
                    // Reserve each queue slot while an explicit steering action is being
                    // persisted. A failed steer leaves this same slot and its order intact.
                    while (true) {
                        val gate = synchronized(submissionLock) {
                            if (submission.removed) throw CancellationException("Queued message removed")
                            submission.controlGate?.takeUnless { it.isCompleted }.also { gate ->
                                if (gate == null) {
                                    submission.enteringRuntime = true
                                    _activeTasks.update { tasks ->
                                        val current = tasks[conversationId] ?: return@update tasks
                                        tasks + (conversationId to current.map {
                                            if (it.turnId == turnId) it.copy(started = true) else it
                                        })
                                    }
                                }
                            }
                        } ?: break
                        gate.await()
                    }
                    runtime.processConversationInput(conversationId, request, wrappedStream) {
                        synchronized(submissionLock) {
                            if (submission.removed) throw CancellationException("Queued message removed")
                            started.complete(Unit)
                        }
                    }
                })
            } catch (error: Exception) {
                // The UI shows only a generic notice; keep the cause for diagnosis.
                if (error !is CancellationException) android.util.Log.e("ConversationTask", "Turn $turnId failed", error)
                if (!submission.removed) onFailure(error)
                if (error is CancellationException) throw error
            }
        }
        submission.job = job
        synchronized(submissionLock) {
            try { requireConversationWritable(conversationId) } catch (cancelled: CancellationException) {
                job.cancel(cancelled)
                throw cancelled
            }
            submissions[turnId] = submission
        }
        _activeTasks.update { tasks ->
            val current = tasks[conversationId].orEmpty()
            val updated = if (current.any { it.turnId == turnId }) {
                current.map { if (it.turnId == turnId) it.copy(request = request) else it }
            } else current + ActiveConversationTask(conversationId, turnId, request, System.currentTimeMillis())
            tasks + (conversationId to updated)
        }
        runtime.registerActiveJob("ui:$turnId", job)
        job.invokeOnCompletion {
            submissions.remove(turnId, submission)
            started.complete(Unit)
            try {
                onFinished()
            } finally {
                removeTask(conversationId, turnId)
                runtime.unregisterActiveJob("ui:$turnId", job)
            }
        }
        job.start()
        return job
    }

    /** Withdraw only a queue slot that has not entered the runtime. */
    fun deleteQueued(conversationId: String, turnId: String): Boolean = synchronized(submissionLock) {
        val queued = submissions[turnId]
        if (queued == null) {
            val admission = admissions.values.firstOrNull {
                it.conversationId == conversationId && it.turnId == turnId
            } ?: return false
            admission.job.cancel()
            removeTask(conversationId, turnId)
            return true
        }
        if (queued.conversationId != conversationId || queued.enteringRuntime ||
            queued.removed || queued.controlGate != null) return false
        queued.removed = true
        queued.job.cancel()
        true
    }

    /** Explicitly use one queued message as guidance; keep its slot on rejection. */
    suspend fun steerQueued(conversationId: String, turnId: String, onResult: (Boolean) -> Unit = {}): Boolean =
        kotlinx.coroutines.withContext(scope.coroutineContext) {
            steerQueuedInput(conversationId, turnId).also(onResult)
        }

    private suspend fun steerQueuedInput(conversationId: String, turnId: String): Boolean = admissionMutex.withLock {
        val (queued, target) = synchronized(submissionLock) {
            val queued = submissions[turnId]?.takeIf {
                it.conversationId == conversationId && !it.enteringRuntime && !it.removed && it.controlGate == null
            } ?: return@withLock false
            val running = _activeTasks.value[conversationId].orEmpty().firstOrNull { it.started }
                ?: return@withLock false
            val target = submissions[running.turnId] ?: return@withLock false
            queued.controlGate = kotlinx.coroutines.CompletableDeferred()
            queued to target
        }
        try {
            target.ready.await()
            if (submissions[target.turnId] !== target ||
                !runtime.steerConversationInput(conversationId, target.turnId, queued.request)) return@withLock false
            synchronized(submissionLock) {
                queued.removed = true
                queued.job.cancel()
            }
            true
        } finally {
            queued.controlGate?.complete(Unit)
            synchronized(submissionLock) { queued.controlGate = null }
        }
    }

    private fun removeTask(conversationId: String, turnId: String) {
        _activeTasks.update { tasks ->
            val remaining = tasks[conversationId].orEmpty().filterNot { it.turnId == turnId }
            if (remaining.isEmpty()) tasks - conversationId else tasks + (conversationId to remaining)
        }
    }

    fun stop(conversationId: String, origin: String) {
        // Cancel suspended input admission first so stopping a Turn cannot resubmit that input.
        admissions.entries.filter { it.value.conversationId == conversationId }.forEach { it.key.cancel() }
        // A conversation stop covers its running Turn and admitted queued Turns, never another thread.
        _activeTasks.value[conversationId].orEmpty().asReversed().sortedBy { it.started }
            .forEach { runtime.cancelCurrentTask("ui:${it.turnId}", origin) }
    }

    /** Destructive history changes must wait for durable cancellation settlement, not UI idle. */
    suspend fun stopAndAwait(conversationId: String, origin: String) {
        val jobs = synchronized(submissionLock) {
            val targetJobs = admissions.values.filter { it.conversationId == conversationId }.map { it.job } +
                submissions.values.filter { it.conversationId == conversationId }.map { it.job }
            stop(conversationId, origin)
            targetJobs.distinct()
        }
        jobs.forEach { it.join() }
    }

    fun clear(conversationId: String, origin: String) {
        stop(conversationId, origin)
        if (runtime.currentConversationId.value == conversationId) {
            runtime.clearConversation()
        }
        _activeTasks.update { it - conversationId }
    }
}
