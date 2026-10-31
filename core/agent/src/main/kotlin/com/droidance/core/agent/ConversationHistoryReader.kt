// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.ConversationEntryKind
import com.droidance.core.agent.event.ConversationEntryReadModel
import com.droidance.core.agent.event.reportedToolResults
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import javax.inject.Inject

/** Model history is projected from durable facts, independently of chat UI formatting. */
class ConversationHistoryReader @Inject constructor(private val events: AgentEventStore) {
    suspend fun read(threadId: String, excludingTurnId: String? = null): List<ChatMessage> {
        val thread = events.threadReadModel(threadId) ?: return emptyList()
        if (thread.metadata?.tombstoned == true) return emptyList()
        val handovers = thread.turns.filter { it.turnId != excludingTurnId }.flatMap { turn ->
            reportedToolResults(turn.turnId, thread.activityByTurn[turn.turnId], thread.toolDetails).map { result ->
                ConversationEntryReadModel(result.eventId, result.eventId, threadId, turn.turnId,
                    result.sequence, 0L, ConversationEntryKind.ASSISTANT_STAGE_RESULT,
                    result.text, result.roundIds.singleOrNull())
            }
        }
        return project((thread.conversation.entries.filter { it.turnId != excludingTurnId } + handovers)
            .sortedBy { it.sequence })
    }

    internal fun project(entries: List<ConversationEntryReadModel>): List<ChatMessage> {
        val assistants = entries.filterNot(::isUser).groupBy { it.turnId }
        val lastAssistantIds = assistants.values.mapTo(mutableSetOf()) { it.last().itemId }
        return entries.mapNotNull { entry ->
            if (isUser(entry)) ChatMessage(role = Role.USER, content = entry.text)
            else if (entry.itemId in lastAssistantIds) {
                val text = assistants.getValue(entry.turnId).asSequence()
                    .map { PublicAssistantText.sanitize(it.text).trim() }
                    .filter(String::isNotBlank).distinct().joinToString("\n\n")
                ChatMessage(role = Role.ASSISTANT, content = text)
            } else null
        }.takeLast(20)
    }

    private fun isUser(entry: ConversationEntryReadModel): Boolean =
        entry.kind == ConversationEntryKind.USER_MESSAGE || entry.kind == ConversationEntryKind.USER_STEER
}
