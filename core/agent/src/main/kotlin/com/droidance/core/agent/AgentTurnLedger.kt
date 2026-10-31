// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.TurnActivationKind

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionAttemptTransitions
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.event.TurnResult
import com.droidance.core.agent.event.UserAttachmentPayload
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.prompt.StablePrefix
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.agent.llm.session.FrozenTransportConfig
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.agent.llm.session.FrozenTurnModelSession
import com.droidance.core.agent.llm.session.ModelSessionKey
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun materializeUserAttachments(
    messageId: String,
    images: List<String>,
    blobStore: BlobStore
): List<UserAttachmentPayload> = images.mapIndexed { index, encoded ->
    val bytes = runCatching { java.util.Base64.getDecoder().decode(encoded) }
        .getOrElse { error -> throw IllegalArgumentException("User image is not valid base64", error) }
    require(bytes.isNotEmpty()) { "User image cannot be empty" }
    val mimeType = detectUserImageMimeType(bytes)
    val metadata = blobStore.put(bytes)
    UserAttachmentPayload(
        attachmentId = "$messageId:attachment:$index",
        contentRef = metadata.contentRef,
        contentHash = metadata.contentHash,
        sizeBytes = metadata.sizeBytes,
        mimeType = mimeType
    )
}

private fun detectUserImageMimeType(bytes: ByteArray): String = when {
    bytes.size >= 3 &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() ->
        "image/jpeg"
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    ) -> "image/png"
    bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
    bytes.size >= 6 && (
        bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
            bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())
        ) -> "image/gif"
    else -> throw IllegalArgumentException("User image has an unsupported binary format")
}

/**
 * Thread/Turn 的唯一运行时边界。
 *
 * AgentRuntime 仍然可以保留用于 UI 的 StateFlow，但所有可恢复的用户消息、模型会话
 * 身份和最终回执必须先进入 EventStore。这个类只负责建立和关闭 Turn，不参与模型决策。
 */
@Singleton
class AgentTurnLedger @Inject constructor(
    private val eventStore: AgentEventStore,
    private val modelClient: ModelClient,
    private val blobStore: BlobStore
) {
    data class ActiveTurn(
        override val scope: TurnEventScope,
        override var stablePrefix: StablePrefix,
        override var session: FrozenTurnModelSession,
        val startedAtMs: Long,
        val startCommitted: Boolean = false,
        var activationKind: TurnActivationKind = TurnActivationKind.DIRECT,
        val executionClock: TaskExecutionClock = TaskExecutionClock()
    ) : com.droidance.core.agent.context.ContextCheckpointSession

    fun conversationTitles(activeTurn: ActiveTurn): ConversationTitleSession =
        ConversationTitleSession(eventStore, activeTurn.scope)

    suspend fun activateTools(
        activeTurn: ActiveTurn,
        stablePrefix: StablePrefix,
        activationKind: TurnActivationKind
    ) {
        val plan = modelClient.planStreamChat(messages = emptyList(), hasImage = false)
        val identity = createIdentity(activeTurn.scope.threadId, plan, stablePrefix)
        val session = FrozenTurnModelSession(
            turnId = activeTurn.scope.turnId,
            providerConfigSnapshotRef = plan.providerSnapshotRef,
            key = ModelSessionKey(identity = identity, epoch = activeTurn.session.key.epoch + 1L)
        )
        eventStore.activateModelSession(
            scope = activeTurn.scope,
            modelSessionIdentity = identity,
            providerConfigSnapshotRef = session.providerConfigSnapshotRef,
            previousEpoch = activeTurn.session.key.epoch,
            currentEpoch = session.key.epoch,
            activationKind = activationKind
        )
        modelClient.resetResponsesSessionForAndroidActivation()
        activeTurn.activationKind = activationKind
        activeTurn.stablePrefix = stablePrefix
        activeTurn.session = session
    }

    suspend fun start(
        conversationId: String?,
        source: MessageSource,
        userText: String,
        images: List<String>,
        requestId: String,
        stablePrefix: StablePrefix,
        onStarted: (ActiveTurn) -> Unit
    ): ActiveTurn {
        val threadId = conversationId?.trim()?.takeIf(String::isNotBlank)
            ?: "droidance-thread:${source.wireName}"
        val turnId = "turn:$requestId"
        val messageId = "message:$requestId"
        val attachments = materializeUserAttachments(messageId, images, blobStore)
        if (eventStore.threadReadModel(threadId) == null) {
            eventStore.createThread(threadId, userText.trim().take(120).ifBlank { "Droidance" })
        }

        // beginTurn 已经冻结了 Provider 选择；先取一次同一冻结配置，后续所有恢复轮次都复用它。
        val plan = modelClient.planStreamChat(messages = emptyList(), hasImage = false)
        val identity = createIdentity(threadId, plan, stablePrefix)
        val session = FrozenTurnModelSession(
            turnId = turnId,
            providerConfigSnapshotRef = plan.providerSnapshotRef,
            key = ModelSessionKey(identity = identity, epoch = 0L)
        )
        val scope = TurnEventScope(threadId = threadId, turnId = turnId)
        val active = ActiveTurn(
            scope = scope,
            stablePrefix = stablePrefix,
            session = session,
            startedAtMs = System.currentTimeMillis()
        )
        // Transfer settlement ownership before cancellation can resume between durable writes.
        withContext(NonCancellable) {
            eventStore.startTurn(
                scope = scope,
                modelSessionIdentity = identity,
                providerConfigSnapshotRef = session.providerConfigSnapshotRef
            )
            onStarted(active)
        }
        eventStore.recordUserMessage(
            scope = scope,
            messageId = messageId,
            text = userText,
            attachments = attachments
        )
        return active
    }

    private fun createIdentity(
        threadId: String,
        plan: ModelStreamPlan,
        stablePrefix: StablePrefix
    ): ModelSessionIdentity = ModelSessionIdentity(
        threadId = threadId,
        providerId = plan.providerId,
        transportKind = plan.transportKind,
        transportConfigHash = plan.transportConfigHash,
        authScopeHash = plan.authScopeHash,
        modelId = plan.initialModel,
        stablePrefixHash = stablePrefix.hash
    )

    suspend fun complete(
        activeTurn: ActiveTurn,
        status: String,
        assistantReply: String,
        diagnosticCode: String? = null,
        completedAtMs: Long = System.currentTimeMillis()
    ) {
        val activeDurationMs = activeTurn.executionClock.finish()
        settleExecutionAttempt(activeTurn, status, diagnosticCode)
        eventStore.completeTurn(
            threadId = activeTurn.scope.threadId,
            turnId = activeTurn.scope.turnId,
            result = TurnResult(
                status = status,
                assistantReply = assistantReply,
                diagnosticCode = diagnosticCode,
                totalDurationMillis = (completedAtMs - activeTurn.startedAtMs).coerceAtLeast(0L),
                activeDurationMillis = activeDurationMs
            )
        )
    }

    suspend fun userSteerMessages(activeTurn: ActiveTurn): List<com.droidance.core.model.llm.models.ChatMessage> =
        eventStore.eventsForTurn(activeTurn.scope.turnId)
            .filter { it.eventKind == AgentEventKind.USER_STEER }
            .map { event ->
                val payload = kotlinx.serialization.json.Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(event.payloadJson)
                com.droidance.core.model.llm.models.ChatMessage(com.droidance.core.model.llm.models.Role.USER, payload.text, timestamp = event.occurredAt,
                    imageBase64List = payload.attachments.map { java.util.Base64.getEncoder().encodeToString(blobStore.read(it.contentRef, 8 * 1024 * 1024)) })
            }

    suspend fun recordUserSteer(
        activeTurn: ActiveTurn,
        messageId: String,
        text: String,
        images: List<String> = emptyList()
    ) {
        val attachments = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            materializeUserAttachments(messageId, images, blobStore)
        }
        // Delivery retries must reuse the original timing fact and idempotency payload.
        val existing = eventStore.eventsForTurn(activeTurn.scope.turnId).asSequence()
            .filter { it.eventKind == AgentEventKind.USER_STEER }
            .map { kotlinx.serialization.json.Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(it.payloadJson) }
            .firstOrNull { it.messageId == messageId }
        val activeDuration = if (existing != null) existing.activeDurationMillis else activeTurn.executionClock.elapsedMs()
        eventStore.recordUserSteer(activeTurn.scope, messageId, text, attachments, activeDuration)
    }

    /** Persist the exact ExecutionAttempt pause before a system permission dialog is launched. */
    suspend fun pauseForUser(activeTurn: ActiveTurn, interactionId: String) {
        var attempt = eventStore.latestExecutionAttemptForTurn(activeTurn.scope) ?: return
        if (attempt.state == ExecutionAttemptState.CREATED) {
            eventStore.recordExecutionAttemptState(
                scope = attempt.scope,
                transitionId = "permission:$interactionId:running-before-pause",
                state = ExecutionAttemptState.RUNNING
            )
            attempt = eventStore.requireExecutionAttemptSnapshot(attempt.scope)
        }
        if (attempt.state == ExecutionAttemptState.RUNNING) {
            eventStore.recordExecutionAttemptState(
                scope = attempt.scope,
                transitionId = "permission:$interactionId:paused",
                state = ExecutionAttemptState.PAUSED_USER
            )
        }
    }

    /** Resume only the same persisted ExecutionAttempt after the permission result is known. */
    suspend fun resumeFromUser(activeTurn: ActiveTurn, interactionId: String) {
        val attempt = eventStore.latestExecutionAttemptForTurn(activeTurn.scope) ?: return
        if (attempt.state == ExecutionAttemptState.PAUSED_USER) {
            eventStore.recordExecutionAttemptState(
                scope = attempt.scope,
                transitionId = "permission:$interactionId:resumed",
                state = ExecutionAttemptState.RUNNING
            )
        }
    }

    suspend fun completeWithNotice(
        activeTurn: ActiveTurn,
        status: String,
        notice: String,
        diagnosticCode: String? = null,
        completedAtMs: Long = System.currentTimeMillis(),
        assistantReply: String? = null,
        cancellationOrigin: String? = null
    ) {
        val activeDurationMs = activeTurn.executionClock.finish()
        // Failure cleanup may interrupt a turn-local handler outside the device executor.
        // Preserve an already committed terminal; never replace it with a cancellation race.
        if (eventStore.eventsForTurn(activeTurn.scope.turnId).any {
                it.eventKind == AgentEventKind.TURN_COMPLETED
            }) return
        // A started tool without its receipt has an unknown outcome, not a successful one.
        for (pending in eventStore.requireTurnRecoverySnapshot(activeTurn.scope).pendingTools) {
            eventStore.finishTool(
                scope = pending.scope,
                result = ToolExecutionResult(
                    outputText = "",
                    executionStatus = ToolExecutionStatus.INTERRUPTED,
                    evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                    diagnosticCode = diagnosticCode
                ),
                roundDetailRef = pending.roundDetailRef
            )
        }
        settleExecutionAttempt(activeTurn, status, diagnosticCode)
        eventStore.completeTurn(
            threadId = activeTurn.scope.threadId,
            turnId = activeTurn.scope.turnId,
            result = TurnResult(
                status = status,
                assistantReply = assistantReply,
                systemNotice = if (assistantReply == null) notice else null,
                diagnosticCode = diagnosticCode,
                cancellationOrigin = cancellationOrigin,
                totalDurationMillis = (completedAtMs - activeTurn.startedAtMs).coerceAtLeast(0L),
                activeDurationMillis = activeDurationMs
            )
        )
    }

    private suspend fun settleExecutionAttempt(
        activeTurn: ActiveTurn,
        status: String,
        diagnosticCode: String?
    ) {
        var snapshot = eventStore.latestExecutionAttemptForTurn(activeTurn.scope) ?: return
        if (snapshot.state in ExecutionAttemptTransitions.terminalStates) return

        val terminalState = when (status.trim().lowercase()) {
            "done" -> ExecutionAttemptState.COMPLETED
            "cancelled", "canceled" -> ExecutionAttemptState.CANCELED
            "need_more_info" -> ExecutionAttemptState.INTERRUPTED
            else -> ExecutionAttemptState.FAILED
        }
        if (terminalState == ExecutionAttemptState.COMPLETED &&
            snapshot.state != ExecutionAttemptState.RUNNING
        ) {
            eventStore.recordExecutionAttemptState(
                scope = snapshot.scope,
                transitionId = "turn:${activeTurn.scope.turnId}:finalize-running",
                state = ExecutionAttemptState.RUNNING
            )
            snapshot = eventStore.requireExecutionAttemptSnapshot(snapshot.scope)
        }
        eventStore.recordExecutionAttemptState(
            scope = snapshot.scope,
            transitionId = "turn:${activeTurn.scope.turnId}:terminal",
            state = terminalState,
            diagnosticCode = diagnosticCode
        )
    }
}
