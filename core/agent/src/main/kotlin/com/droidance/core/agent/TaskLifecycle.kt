// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.event.TurnActivationKind
import com.droidance.core.agent.harness.DurableModelRoundCoordinator
import com.droidance.core.agent.harness.ZeroToolRoundDecision
import com.droidance.core.agent.ports.AgentConfiguration
import com.droidance.core.agent.prompt.StablePrefix
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

class TaskExecutionFailure(val publicReply: String, cause: Throwable) : IllegalStateException(publicReply, cause)

/** Production request lifetime shared by Android hosts and JVM behavioral tests. */
@Singleton
class TaskLifecycle @Inject constructor(
    private val model: ModelClient,
    private val ledger: AgentTurnLedger,
    private val durableModelRoundCoordinator: DurableModelRoundCoordinator,
    private val configuration: AgentConfiguration,
    private val evidenceLedger: TaskEvidenceLedger
) {
    data class FailureNotice(
        val status: String, val text: String, val diagnosticCode: String?,
        val cancellationOrigin: String? = null
    )

    /**
     * A model lease encloses start, activation, settlement and host cleanup. Even acquisition,
     * cancellation and cleanup failures close the lease. A caller cannot return an unsettled turn.
     * Host resources are released before task state, and task state before the model lease.
     */
    suspend fun <T> run(
        conversationId: String?,
        request: TurnRequest,
        initialPrefix: suspend () -> StablePrefix,
        unfinishedNotice: suspend (Session, Throwable) -> FailureNotice,
        releaseResources: suspend (Session) -> Unit,
        execute: suspend (Session) -> T
    ): T {
        val modelSession = model.createSession(request.modelPlan)
        var session: Session? = null
        var failure: Throwable? = null
        try {
            ledger.start(conversationId, request.source, request.rawText,
                request.images, request.requestId, initialPrefix()) { turn ->
                session = Session(turn, request)
            }
            val active = checkNotNull(session)
            return execute(active).also { check(active.finished) { "Task returned without terminal settlement" } }
        } catch (error: Throwable) {
            failure = error
            val explanation = if (error is Exception && error !is kotlinx.coroutines.CancellationException &&
                session?.toolsActivated == true) {
                session.explainRuntimeFailure(error)
            } else null
            val reported = explanation?.let { TaskExecutionFailure(it, error) } ?: error
            failure = reported
            throw reported
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
                }
            }
            withContext(NonCancellable) {
                session?.let { active ->
                    if (!active.finished) cleanup {
                        withTimeout(3_000L) {
                            val notice = unfinishedNotice(active, checkNotNull(failure))
                            ledger.completeWithNotice(active.durableTurn, notice.status, notice.text,
                                diagnosticCode = notice.diagnosticCode,
                                cancellationOrigin = notice.cancellationOrigin)
                            active.finished = true
                        }
                    }
                    cleanup { releaseResources(active) }
                    cleanup { active.context.clear() }
                }
                cleanup { modelSession.close() }
            }
            cleanupFailure?.let { error ->
                val original = failure
                if (original == null) throw error else original.addSuppressed(error)
            }
        }
    }

    inner class Session internal constructor(
        initialTurn: AgentTurnLedger.ActiveTurn,
        private val request: TurnRequest
    ) {
        val durableTurn: AgentTurnLedger.ActiveTurn = initialTurn
        val context = TurnSessionContext(initialTurn.scope.turnId, initialTurn.scope.threadId)
        internal var finished = false
        var toolsActivated = false
            private set

        /** One no-tool reply by the same model when host preparation failed outside the loop. */
        internal suspend fun explainRuntimeFailure(error: Exception): String? {
            var handle: DurableModelRoundCoordinator.RoundHandle? = null
            return try {
                val policy = kotlinx.serialization.json.Json.decodeFromString<StoppedTaskReplyPolicy>(
                    configuration.readMarkdownSection("stopped_task_reply.md", "policy")).also { it.validate() }
                val cancellationGeneration = model.currentCancellationGeneration()
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("goal", kotlinx.serialization.json.JsonPrimitive(request.rawText.take(policy.maxContextChars / 3)))
                    put("user_supplements", kotlinx.serialization.json.JsonPrimitive(ledger.userSteerMessages(durableTurn)
                        .joinToString("\n") { it.content }.takeLast(policy.maxContextChars / 3)))
                    put("failure_reason", kotlinx.serialization.json.JsonPrimitive("RUNTIME_EXCEPTION"))
                    put("runtime_diagnostic", kotlinx.serialization.json.JsonPrimitive("${error.javaClass.simpleName}: ${error.message.orEmpty().take(500)}"))
                    put("user_input_required", kotlinx.serialization.json.JsonPrimitive(false))
                    put("execution_stopped", kotlinx.serialization.json.JsonPrimitive(true))
                }
                val messages = listOf(ChatMessage(Role.USER, payload.toString()))
                val prompt = configuration.readMarkdownSection("stopped_task_reply.md", "instruction")
                    .replace("{{language}}", configuration.outputLanguage())
                handle = durableModelRoundCoordinator.begin(durableTurn.scope,
                    durableModelRoundCoordinator.newRoundId(), startCommitted = durableTurn.startCommitted)
                val output = StringBuilder()
                var hasTools = false
                com.droidance.core.model.llm.withModelRequestTimeout(policy.timeoutMs) {
                    model.streamChat(messages, tools = emptyList(), systemPrompt = prompt,
                        maxOutputTokens = policy.maxOutputTokens, reasoningEffort = policy.reasoningEffort, requestTimeoutMs = policy.timeoutMs,
                        requestCancellationGeneration = cancellationGeneration,
                        requestPlan = model.planStreamChat(messages, false), isolatedContext = true).collect { event ->
                        when (event) {
                            is com.droidance.core.model.llm.models.LLMStreamEvent.Content -> output.append(event.text)
                            is com.droidance.core.model.llm.models.LLMStreamEvent.ToolCallChunk -> hasTools = true
                            else -> Unit
                        }
                    }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                check(!model.wasCancellationRequestedSince(cancellationGeneration)) { "Failure reply interrupted" }
                val reply = PublicAssistantText.sanitize(output.toString()).trim()
                require(!hasTools && reply.isNotBlank()) { "Invalid failure reply" }
                durableModelRoundCoordinator.commitZeroToolResponse(checkNotNull(handle), reply,
                    ZeroToolRoundDecision.DIRECT_REPLY, requestMetadata = mapOf("purpose" to "runtime_failure_reply"))
                reply
            } catch (failure: Exception) {
                handle?.let { pending ->
                    withContext(NonCancellable) {
                        kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                            runCatching { durableModelRoundCoordinator.commitZeroToolResponse(pending, null,
                                ZeroToolRoundDecision.FAILED, "FAILURE_REPLY_UNAVAILABLE", failure) }
                        }
                    }
                }
                if (failure is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
                null
            }
        }

        suspend fun activateTools(prefix: StablePrefix, kind: TurnActivationKind, preparedAtStart: Boolean = false) {
            check(!finished && !toolsActivated && kind != TurnActivationKind.DIRECT)
            if (!preparedAtStart) ledger.activateTools(durableTurn, prefix, kind)
            evidenceLedger.startTask(request.rawText)
            toolsActivated = true
        }

        suspend fun complete(status: String, reply: String, diagnosticCode: String? = null) {
            check(!finished) { "Task is already settled" }
            ledger.complete(durableTurn, status, reply, diagnosticCode)
            finished = true
        }
    }
}
