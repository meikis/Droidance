// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.core.agent.PublicAssistantText
import com.droidance.agent.ActiveConversationTask
import com.droidance.core.agent.event.ConversationEntryKind
import com.droidance.core.agent.event.ConversationEntryReadModel
import com.droidance.ui.execution.ConversationExecutionSegment
import com.droidance.ui.execution.conversationExecutionSegments

/** A completion or late callback only updates its exact segment. */
internal fun List<UIMessage>.replaceAssistantInPlace(updated: UIMessage): List<UIMessage> {
    val index = indexOfFirst { !it.isUser && it.messageId == updated.messageId && it.turnId == updated.turnId }
    if (index < 0) return this
    return toMutableList().also { it[index] = updated }
}

internal fun reconcileConversationMessages(
    current: List<UIMessage>,
    projected: List<UIMessage>,
    terminalTurnIds: Set<String>,
    activeTasks: List<ActiveConversationTask>
): List<UIMessage> {
    val currentById = current.associateBy(UIMessage::messageId)
    val messages = projected.map { durable ->
        val live = currentById[durable.messageId]
        if (!durable.isUser && durable.turnId !in terminalTurnIds && live != null &&
            (live.completedAtMs > 0L && durable.isCurrentSegment || durable.text.isBlank() && live.isTransient)
        ) durable.copy(text = live.text, isTransient = live.isTransient,
            completedAtMs = durable.completedAtMs.takeIf { it > 0 } ?: live.completedAtMs) else durable
    }.toMutableList()
    val durableIds = projected.mapTo(mutableSetOf(), UIMessage::messageId)
    current.filter { it.isUser && it.isTransient && it.messageId !in durableIds }.forEach(messages::add)

    fun placeAssistant(message: UIMessage) {
        if (messages.any { it.messageId == message.messageId }) return
        val anchor = messages.indexOfFirst { it.isUser &&
            (message.anchorMessageId?.let { anchor -> it.messageId == anchor } ?: (it.turnId == message.turnId)) }
        if (anchor < 0) messages.add(message) else messages.add(anchor + 1, message)
    }
    val unfinished = activeTasks.filterNot { it.turnId in terminalTurnIds }
    unfinished.forEach { active ->
        val userId = "message:${active.request.requestId}"
        if (messages.none { it.messageId == userId }) messages.add(UIMessage(
            text = active.request.rawText, isUser = true,
            timestamp = formatMessageTime(active.submittedAtMs), timestampMs = active.submittedAtMs,
            turnId = active.turnId, messageId = userId, isTransient = true,
            imageBytes = active.request.images.mapNotNull {
                runCatching { java.util.Base64.getMimeDecoder().decode(it) }.getOrNull()
            }))
    }
    current.filter { !it.isUser && (it.isTransient || it.completedAtMs > 0L) && it.turnId !in terminalTurnIds &&
        projected.none { durable -> !durable.isUser && durable.turnId == it.turnId } }.forEach(::placeAssistant)
    unfinished.forEach { active ->
        if (messages.none { !it.isUser && it.turnId == active.turnId }) placeAssistant(UIMessage(
            text = "", isUser = false, timestamp = formatMessageTime(active.submittedAtMs),
            timestampMs = active.submittedAtMs, startedAtMs = active.submittedAtMs,
            turnId = active.turnId, messageId = "assistant:${active.turnId}", isTransient = true))
        messages.indices.filter { !messages[it].isUser && messages[it].turnId == active.turnId }.forEach { index ->
            val message = messages[index]
            val scoped = active.streamingByRound.filterKeys { round ->
                if (round == null) message.isCurrentSegment else round in message.roundIds
            }.values.joinToString("")
            if (scoped.isNotEmpty() && (message.text.isBlank() || message.isTransient)) {
                messages[index] = message.copy(text = scoped, isTransient = true,
                    startedAtMs = message.startedAtMs.takeIf { it > 0L } ?: active.submittedAtMs)
            }
        }
    }
    return messages
}

internal suspend fun projectConversationMessages(
    entries: List<ConversationEntryReadModel>,
    segments: List<ConversationExecutionSegment> = conversationExecutionSegments(entries, emptyList()),
    loadImage: suspend (com.droidance.core.agent.event.UserAttachmentPayload) -> ByteArray?
): List<UIMessage> {
    val byInput = segments.associateBy { it.input.itemId }
    return buildList {
        entries.filter { it.isUserEntry() }.forEach { entry ->
            add(UIMessage(turnId = entry.turnId, messageId = entry.itemId, text = entry.text, isUser = true,
                isGuidance = entry.kind == ConversationEntryKind.USER_STEER,
                timestamp = formatMessageTime(entry.occurredAt), timestampMs = entry.occurredAt,
                imageBytes = entry.attachments.mapNotNull { loadImage(it) }))
            val segment = byInput[entry.itemId] ?: return@forEach
            if (!segment.isCurrent && segment.replies.isEmpty() && segment.roundIds.isEmpty()) return@forEach
            val replies = segment.replies
            val visible = if (replies.any { it.kind in setOf(ConversationEntryKind.TURN_ASSISTANT_REPLY,
                    ConversationEntryKind.ASSISTANT_START, ConversationEntryKind.ASSISTANT_STAGE_RESULT,
                    ConversationEntryKind.ASSISTANT_USER_INTERVENTION) })
                replies.filterNot { it.kind == ConversationEntryKind.TURN_SYSTEM_NOTICE } else replies
            add(UIMessage(turnId = entry.turnId, messageId = segment.messageId, anchorMessageId = entry.itemId,
                roundIds = segment.roundIds, isCurrentSegment = segment.isCurrent,
                text = visible.map { PublicAssistantText.sanitize(it.text).trim() }.filter(String::isNotBlank)
                    .distinct().joinToString("\n\n"), isUser = false,
                timestamp = formatMessageTime(entry.occurredAt), timestampMs = entry.occurredAt,
                startedAtMs = entry.occurredAt, completedAtMs = segment.nextInput?.occurredAt ?: 0L))
        }
    }
}

private fun ConversationEntryReadModel.isUserEntry(): Boolean =
    kind == ConversationEntryKind.USER_MESSAGE || kind == ConversationEntryKind.USER_STEER

private fun formatMessageTime(timestampMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestampMs))
