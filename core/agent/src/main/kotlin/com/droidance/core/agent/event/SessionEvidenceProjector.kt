// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.json.*

/** Exact, turn-scoped reads of settled calls; model prose is never treated as an attachment. */
object SessionEvidenceProjector {
    data class HistoricalSource(
        val event: AgentEventEnvelope,
        val attachment: DetailAttachmentAddedEventPayload,
        val executionContext: ExecutionContextSnapshotPayload?
    )

    data class Step(val event: AgentEventEnvelope, val call: ToolCallEventPayload,
        val result: ToolExecutionResult, val attachments: List<DetailAttachmentAddedEventPayload>,
        val executionContext: ExecutionContextSnapshotPayload? = null,
        val historicalSource: HistoricalSource? = null)

    fun steps(events: List<AgentEventEnvelope>, threadId: String, turnId: String): List<Step> {
        val scoped = events.filter { it.threadId == threadId && it.turnId == turnId }
            .distinctBy { it.eventId }.sortedBy { it.sequence }
        val calls = scoped.filter { it.eventKind == AgentEventKind.TOOL_CALL && !it.callId.isNullOrBlank() }
            .associateBy { it.callId }
        val attemptContexts = scoped.filter { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED }
            .mapNotNull { created ->
                val key = created.attemptId to created.executionId
                val payload = runCatching {
                    Json.decodeFromString<ExecutionAttemptCreatedEventPayload>(created.payloadJson)
                }.getOrNull() ?: return@mapNotNull null
                key to payload.context
            }.toMap()
        val projected = scoped.filter {
            it.eventKind == AgentEventKind.TOOL_FINISHED ||
                it.eventKind == AgentEventKind.TOOL_CALL_REJECTED
        }.filter { !it.callId.isNullOrBlank() }.distinctBy { it.callId }.mapNotNull { settled ->
            val declaration = calls[settled.callId]?.takeIf { it.sequence < settled.sequence } ?: return@mapNotNull null
            val call = Json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
            val result = when (settled.eventKind) {
                AgentEventKind.TOOL_FINISHED -> Json.decodeFromString<ToolFinishedEventPayload>(settled.payloadJson).result
                AgentEventKind.TOOL_CALL_REJECTED -> Json.decodeFromString<ToolCallRejectedEventPayload>(settled.payloadJson).result
                else -> return@mapNotNull null
            }
            val attachments = scoped.filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED &&
                it.callId == settled.callId && it.sequence > settled.sequence }.map {
                Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson)
            }.filter { it.role != DetailAttachmentRole.DIAGNOSTIC }.distinctBy { it.attachmentId }
            Step(settled, call, result, attachments, attemptContexts[settled.attemptId to settled.executionId])
        }
        val attachmentOwners = projected.asSequence()
            .flatMap { step -> step.attachments.asSequence().map { it.attachmentId to step } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (attachmentId, owners) ->
                owners.singleOrNull()?.let { attachmentId to it }
            }
            .toMap()

        fun historicalSourceFor(step: Step): HistoricalSource? {
            var attachmentId = step.result.historicalAttachment?.attachmentId ?: return null
            val visited = mutableSetOf<String>()
            while (visited.add(attachmentId)) {
                val owner = attachmentOwners[attachmentId] ?: return null
                if (owner.event.sequence >= step.event.sequence) return null
                val attachment = owner.attachments.firstOrNull { it.attachmentId == attachmentId }
                    ?: return null
                val nextAttachmentId = owner.result.historicalAttachment?.attachmentId
                if (nextAttachmentId.isNullOrBlank()) {
                    return HistoricalSource(owner.event, attachment, owner.executionContext)
                }
                val nextOwner = attachmentOwners[nextAttachmentId] ?: return null
                if (nextOwner.event.sequence >= owner.event.sequence) return null
                attachmentId = nextAttachmentId
            }
            return null
        }

        return projected.map { step ->
            step.copy(historicalSource = historicalSourceFor(step))
        }
    }

    fun find(steps: List<Step>, ref: String): Step? = steps.firstOrNull { step ->
        step.event.eventId == ref || step.event.callId == ref || step.attachments.any {
            it.observationId == ref || it.attachmentId == ref
        } || step.result.observationId == ref || step.result.sourceRef == ref || step.result.dataRef == ref
    }

    /** A bounded retrieval index, independent of candidate citations and model-authored summaries. */
    fun sourceIndex(events: List<AgentEventEnvelope>, threadId: String, turnId: String, limit: Int,
                    includeTextContent: Boolean = false, preferredRefs: Set<String> = emptySet(),
                    requester: TurnEventScope? = null): JsonObject {
        require(limit in 1..32)
        val allSteps = steps(events, threadId, turnId)
        val effects = allSteps.filter { it.call.effect == ToolEffect.STATE_CHANGING && it.result.isVerifiedSuccess }
        val sources = allSteps.filter { step ->
            step.result.historicalAttachment == null && (step.attachments.isNotEmpty() ||
                (step.call.binding != ToolBinding.TURN_LOCAL && (step.call.effect == ToolEffect.READ_ONLY || step.call.name == "shell") &&
                    step.result.isVerifiedSuccess))
        }
        val preferred = preferredRefs.mapNotNull { find(sources, it) }.toSet()
        val selected = (sources.filter { it in preferred } + sources).distinct().take(limit).toSet()
        val page = sources.filter { it in selected }
        var remainingTextChars = if (preferred.isEmpty()) 16_000 else 32_000
        val textByEvent = linkedMapOf<String, String>()
        if (includeTextContent) {
            (page.filter { it in preferred } + page.filter { it !in preferred }).forEach { step ->
                if (step.call.binding != ToolBinding.TURN_LOCAL && (step.call.effect == ToolEffect.READ_ONLY || step.call.name == "shell") &&
                    step.result.isVerifiedSuccess && step.attachments.none { it.mimeType.startsWith("image/") } &&
                    (!hasSensitiveArguments(step.call) || canReplayOwnReadReceipt(step, requester))) {
                    val text = step.result.asReceiptText()
                    // A cited receipt is delivered whole or retrieved explicitly, never mistaken for its prefix.
                    val content = if (step in preferred) text.takeIf { it.length <= remainingTextChars }.orEmpty()
                        else text.take(minOf(8_000, remainingTextChars))
                    textByEvent[step.event.eventId] = content
                    remainingTextChars -= content.length
                }
            }
        }
        var remainingEffectChars = 16_000
        return buildJsonObject {
            // Receipts establish a settled effect, not the truth of model-authored findings.
            put("settled_effects", JsonArray(effects.takeLast(limit).asReversed().map { step -> buildJsonObject {
                put("event_ref", step.event.eventId); put("call_ref", step.event.callId)
                put("sequence", step.event.sequence); put("tool", step.call.name)
                put("binding", step.call.binding.name); put("execution_status", step.result.executionStatus.name)
                put("evidence_status", step.result.evidenceStatus.name)
                if (includeTextContent && !hasSensitiveArguments(step.call) &&
                    runCatching { Json.parseToJsonElement(step.result.asReceiptText()) is JsonObject }.getOrDefault(false)) {
                    val original = step.result.asReceiptText()
                    val text = original.take(minOf(8_000, remainingEffectChars))
                    remainingEffectChars -= text.length
                    put("original_receipt", text)
                    put("receipt_truncated", text.length < original.length)
                    put("read_arguments", buildJsonObject { put("mode", "artifact"); put("ref", step.event.eventId); put("offset", 0) })
                } else put("content_redacted", true)
            } }))
            put("omitted_effect_receipts", (effects.size - limit).coerceAtLeast(0))
            put("sources", JsonArray(page.map { step -> buildJsonObject {
                put("event_ref", step.event.eventId)
                put("sequence", step.event.sequence)
                put("tool", step.call.name)
                put("execution_status", step.result.executionStatus.name)
                put("observation_ref", step.result.observationId)
                put("source_ref", step.result.sourceRef)
                if (step.call.name == "shell") put("source_role", "execution_receipt")
                textByEvent[step.event.eventId]?.let { content ->
                    put("original_text", content)
                    val originalTextChars = step.result.asReceiptText().length
                    put("original_text_chars", originalTextChars)
                    put("original_text_truncated", content.length < originalTextChars)
                    if (content.length < originalTextChars) put("read_arguments", buildJsonObject {
                        put("mode", "artifact"); put("ref", step.event.eventId); put("offset", 0)
                    })
                }
                put("attachments", JsonArray(step.attachments.map { attachment -> buildJsonObject {
                    put("ref", attachment.attachmentId)
                    put("observation_ref", attachment.observationId)
                    put("mime_type", attachment.mimeType)
                    put("sha256", attachment.contentHash)
                } }))
            } }))
            // Only advance over the contiguous prefix; prioritizing a later cited source must not skip earlier ones.
            put("next_after", if (sources.size > page.size)
                JsonPrimitive(sources.takeWhile { it in selected }.lastOrNull()?.event?.sequence ?: 0L) else JsonNull)
        }
    }

    fun describe(step: Step, includeContent: Boolean, requester: TurnEventScope? = null): JsonObject = buildJsonObject {
        put("event_ref", step.event.eventId)
        put("call_ref", step.event.callId)
        put("sequence", step.event.sequence)
        put("occurred_at", step.event.occurredAt)
        put("source_thread_ref", step.event.threadId)
        put("source_turn_ref", step.event.turnId)
        put("source_round_ref", step.event.roundId)
        put("settlement_kind", step.event.eventKind.name)
        put("attempt_ref", step.event.attemptId)
        put("execution_ref", step.event.executionId)
        put("observation_ref", step.result.observationId)
        put("runtime_mode", step.executionContext?.runtimeMode?.name)
        put("execution_space", step.executionContext?.executionSpace?.name)
        put("display_id", step.executionContext?.displayId)
        put("backend_session_id", step.executionContext?.backendSessionId)
        put("tool", step.call.name)
        put("execution_status", step.result.executionStatus.name)
        put("evidence_status", step.result.evidenceStatus.name)
        put("diagnostic_code", step.result.diagnosticCode?.takeIf { SAFE_DIAGNOSTIC_CODE.matches(it) })
        step.historicalSource?.let { source ->
            put("historical_source", buildJsonObject {
                put("event_ref", source.event.eventId)
                put("call_ref", source.event.callId)
                put("sequence", source.event.sequence)
                put("occurred_at", source.event.occurredAt)
                put("source_thread_ref", source.event.threadId)
                put("source_turn_ref", source.event.turnId)
                put("source_round_ref", source.event.roundId)
                put("attempt_ref", source.event.attemptId)
                put("execution_ref", source.event.executionId)
                put("display_id", source.executionContext?.displayId)
                put("attachment_ref", source.attachment.attachmentId)
                put("observation_ref", source.attachment.observationId)
                put("content_ref", source.attachment.contentRef)
                put("sha256", source.attachment.contentHash)
            })
        }
        if (includeContent) {
            // Sensitive tool arguments and potentially echoed receipts stay out of replay text.
            if (!hasSensitiveArguments(step.call)) {
                put("arguments", step.call.argumentsJson)
                put("result", step.result.asReceiptText())
            } else if (canReplayOwnReadReceipt(step, requester)) {
                put("arguments_redacted", true)
                put("result", step.result.asReceiptText())
            } else put("content_redacted", true)
        }
        put("attachments", JsonArray(step.attachments.map { attachment -> buildJsonObject {
            put("ref", attachment.attachmentId)
            put("observation_ref", attachment.observationId)
            put("role", attachment.role.name)
            put("mime_type", attachment.mimeType)
            put("content_ref", attachment.contentRef)
            put("sha256", attachment.contentHash)
            put("size_bytes", attachment.sizeBytes)
            attachment.modelContentRef?.let { put("model_content_ref", it) }
            attachment.modelSizeBytes?.let { put("model_size_bytes", it) }
            put("source_event_ref", step.event.eventId)
            put("source_call_ref", step.event.callId)
            put("source_thread_ref", step.event.threadId)
            put("source_turn_ref", step.event.turnId)
            put("source_round_ref", step.event.roundId)
            put("source_execution_ref", step.event.executionId)
            put("source_display_id", step.executionContext?.displayId)
        } }))
    }

    private val SAFE_DIAGNOSTIC_CODE = Regex("[A-Z][A-Z0-9_]{0,95}")

    // An active Agent can revisit a device read it already received in this exact Turn.
    // This does not expose arguments or grant another Turn access to a sensitive receipt.
    private fun canReplayOwnReadReceipt(step: Step, requester: TurnEventScope?): Boolean =
        requester != null && requester.threadId == step.event.threadId && requester.turnId == step.event.turnId &&
            step.call.binding == ToolBinding.DEVICE_BOUND && (step.call.effect == ToolEffect.READ_ONLY || step.call.name == "shell") &&
            step.result.isVerifiedSuccess

    /** Optional sensitive fields do not redact unrelated operations of a shared tool. */
    private fun hasSensitiveArguments(call: ToolCallEventPayload): Boolean {
        if (call.presentation.sensitiveFieldPaths.isEmpty()) return false
        return runCatching {
            val root = Json.parseToJsonElement(call.argumentsJson).jsonObject
            call.presentation.sensitiveFieldPaths.any { path ->
                val tokens = path.split('.').filter(String::isNotBlank).let {
                    if (it.firstOrNull() == "arguments") it.drop(1) else it
                }
                require(tokens.isNotEmpty())
                var value: JsonElement? = root
                for (token in tokens) {
                    value = when (val current = value) {
                        is JsonObject -> current[token]
                        is JsonArray -> current.getOrNull(token.toInt())
                        null -> null
                        else -> error("Sensitive path does not resolve")
                    }
                }
                value != null
            }
        }.getOrDefault(true)
    }
}
