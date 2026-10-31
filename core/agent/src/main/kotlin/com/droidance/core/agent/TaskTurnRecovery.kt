// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import kotlinx.serialization.encodeToString

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.workspace.TaskWorkspace

import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal suspend fun TaskTurnSession.explainStoppedTask(result: TurnResult): TurnResult {
    val policy = Json.decodeFromString<StoppedTaskReplyPolicy>(
        ruleConfigManager.readMarkdownSection("stopped_task_reply.md", "policy")
    ).also { it.validate() }
    if (!stoppedReplyContextAvailable || !policy.eligible(result)) return result.copy(
        runtimeFailureSummary = result.runtimeFailureSummary ?: fallbackText("termination_internal_error")
            .takeIf { result.terminal == TurnTerminal.SYSTEM_ERROR })
    val stoppedReplyCancellationGeneration = llmRouter.currentCancellationGeneration()
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    if (llmRouter.wasCancellationRequestedSince(stoppedReplyCancellationGeneration) || isPaused()) return result
    settlePendingDurableRound("EXECUTION_STOPPED_BEFORE_REPLY")
    val events = eventStore.eventsForTurn(durableTurn.scope.turnId)
    val retainedRequirements = com.droidance.core.agent.event.TaskRequirementPlan.current(
        events, durableTurn.scope.threadId, durableTurn.scope.turnId)
    val memo = com.droidance.core.agent.event.TaskContinuationContext.latestMemo(
        events,
        durableTurn.scope.threadId, durableTurn.scope.turnId
    )
    val workspaceExcerpt = kotlinx.coroutines.withTimeoutOrNull(500L) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            workspaceContextReader.stoppedReplyExcerpt(events, durableTurn.scope, policy.maxContextChars / 3)
        }
    }.orEmpty()
    val failureDiagnostic = result.stopDiagnostic ?: when (result.failureReason) {
        TurnFailureReason.GUARD_STOP -> "GUARD_STOP"
        TurnFailureReason.TASK_TIME_LIMIT -> "MAX_DEVICE_TASK_WALL_STOP"
        TurnFailureReason.CHANNEL_DOWN -> "DEVICE_CHANNEL_DISCONNECTED"
        TurnFailureReason.MODEL_PROTOCOL_ERROR -> "COMPLETION_PROTOCOL_UNCONFIRMED"
        else -> result.failureReason.name
    }
    val lastResultAttachment = events.asReversed().firstNotNullOfOrNull { event ->
        if (event.eventKind != com.droidance.core.agent.event.AgentEventKind.DETAIL_ATTACHMENT_ADDED) null
        else Json.decodeFromString<com.droidance.core.agent.event.DetailAttachmentAddedEventPayload>(event.payloadJson)
            .takeIf { it.role in setOf(DetailAttachmentRole.RESULT, DetailAttachmentRole.CURRENT_OBSERVATION) && it.mimeType.startsWith("image/") }
    }
    val lastResultImage = lastResultAttachment?.let { attachment ->
        try {
            java.util.Base64.getEncoder().encodeToString(blobStore.read(attachment.contentRef, 8 * 1024 * 1024))
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }
    val corePreferences = events.lastOrNull {
        it.eventKind == com.droidance.core.agent.event.AgentEventKind.MEMORY_CONTEXT_LOADED
    }?.let { Json.decodeFromString<com.droidance.core.agent.event.MemoryContextLoadedEventPayload>(it.payloadJson) }
        ?.items?.filter { it.scope == com.droidance.core.agent.event.MemoryContextScope.CORE_PROFILE }
        ?.joinToString("\n") { "${it.ref}: ${it.summary}" }.orEmpty()
    val payload = kotlinx.serialization.json.buildJsonObject {
        put("model_stream_facts", buildJsonObject {
            listOf("response_timeout_phase", "request_duration_ms", "first_provider_event_ms",
                "first_reasoning_event_ms", "first_content_event_ms", "first_tool_event_ms",
                "last_stream_event_ms").forEach { key -> requestMetrics[key]?.let { put(key, JsonPrimitive(it)) } }
        })
        retainedRequirements?.let { put("retained_task_contract", Json.parseToJsonElement(it.render())) }
        put("core_user_preferences", kotlinx.serialization.json.JsonPrimitive(corePreferences.take(policy.maxContextChars / 10)))
        put("last_result_image_ref", kotlinx.serialization.json.JsonPrimitive(lastResultAttachment?.contentRef.orEmpty()))
        put("last_result_image_available", kotlinx.serialization.json.JsonPrimitive(lastResultImage != null))
        put("workspace_files", kotlinx.serialization.json.JsonPrimitive(
            com.droidance.core.agent.workspace.TaskWorkspace.overview(events, durableTurn.scope, policy.maxContextChars / 12)))
        put("unverified_workspace_excerpt", kotlinx.serialization.json.JsonPrimitive(workspaceExcerpt))
        put("goal", kotlinx.serialization.json.JsonPrimitive(taskGoal.take(policy.maxContextChars / 12)))
        put("user_supplements", kotlinx.serialization.json.JsonPrimitive(events.filter {
            it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER
        }.joinToString("\n") { Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(it.payloadJson).text }
            .takeLast(policy.maxContextChars / 8)))
        put("failure_reason", kotlinx.serialization.json.JsonPrimitive(result.failureReason.name))
        put("terminal", kotlinx.serialization.json.JsonPrimitive(result.terminal.name))
        put("user_input_required", kotlinx.serialization.json.JsonPrimitive(result.terminal == TurnTerminal.NEED_MORE_INFO))
        put("execution_rounds", kotlinx.serialization.json.JsonPrimitive(result.rounds))
        put("latest_tool_receipt_excerpt", kotlinx.serialization.json.JsonPrimitive(
            events.lastOrNull { it.eventKind == com.droidance.core.agent.event.AgentEventKind.TOOL_FINISHED }
                ?.payloadJson.orEmpty().take(policy.maxContextChars / 12)))
        put("recent_tool_results", StoppedTaskToolEvidence.project(events, policy.maxContextChars / 6))
        put("retained_tool_receipts", StoppedTaskToolEvidence.retainedReceipts(events, policy.maxContextChars / 5))
        put("runtime_diagnostic", kotlinx.serialization.json.JsonPrimitive(failureDiagnostic))
        put("recorded_evidence_excerpt", kotlinx.serialization.json.JsonPrimitive(
            result.evidenceText.take(policy.maxContextChars / 12)))
        put("unverified_model_memo_excerpt", kotlinx.serialization.json.JsonPrimitive(
            memo?.state.orEmpty().take(policy.maxContextChars / 12)))
    }
    val replyMessages = listOf(ChatMessage(role = Role.USER, content = payload.toString(),
        imageBase64 = lastResultImage, historicalImage = lastResultImage != null))
    val prompt = ruleConfigManager.readMarkdownSection("stopped_task_reply.md", "instruction")
        .replace("{{language}}", ruleConfigManager.outputLanguage())
    val plan = llmRouter.planStreamChat(replyMessages, hasImage = lastResultImage != null)
    var metrics = emptyMap<String, String>()
    beginDurableRound(RepairPurpose.NONE)
    return try {
        val capture = operatorStreamCollector.collect(
            diagnosticScope = checkNotNull(roundSession.pending).scope.let {
                com.droidance.core.model.llm.ModelDiagnosticScope(it.threadId, it.turnId, it.roundId)
            },
            requestPlan = plan, messages = replyMessages, tools = emptyList(),
            systemPrompt = prompt, hasImage = lastResultImage != null, timeoutMs = policy.timeoutMs,
            cancellationGeneration = stoppedReplyCancellationGeneration,
            maxOutputTokens = policy.maxOutputTokens, reasoningEffort = policy.reasoningEffort, enableComputerTool = false,
            computerFrameSize = null, isolatedContext = true,
            startedAt = System.currentTimeMillis(), onMetrics = { metrics = it }, onContent = {}
        )
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (llmRouter.wasCancellationRequestedSince(stoppedReplyCancellationGeneration) || isPaused()) {
            settlePendingDurableRound("STOPPED_REPLY_INTERRUPTED")
            return result
        }
        val reply = PublicAssistantText.sanitize(capture.content).trim()
        val publicationValid = runCatching {
            retainedRequirements?.constraints?.validatePublicText(reply)
            CompletionLinks.destinations(reply).all { link ->
                !WorkspaceDeliverable.isLink(link) && toolExecutor.canOpenPublicLink(link)
            }
        }.getOrDefault(false)
        if (capture.toolCalls.isNotEmpty() || reply.isBlank() || !publicationValid) {
            roundSession.settle("STOPPED_REPLY_INVALID", metrics)
            return result
        }
        roundSession.commit(
            handle = checkNotNull(roundSession.pending), presentations = emptyMap(),
            presentedToolNames = emptySet(), publicBlocks = listOf(reply), calls = emptyList(),
            finishMetadata = metrics + mapOf("phase" to "stopped_task_reply",
                "execution_failure_reason" to result.failureReason.name,
                "execution_terminal" to result.terminal.name,
                "stopped_reply_context" to payload.toString())
        )
        result.withStoppedTaskReply(reply)
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException && error !is TimeoutCancellationException) throw error
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        roundSession.settle("STOPPED_REPLY_UNAVAILABLE", metrics)
        result
    }
}
