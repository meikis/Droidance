// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import kotlinx.serialization.json.Json

/** Resolve exact immutable original images within this turn; never a memo or a neighbouring frame. */
object CompletionReviewImages {
    fun resolve(events: List<AgentEventEnvelope>, threadId: String, turnId: String,
                sources: Set<String>): Map<String, String> = originalSources(events, threadId, turnId)
        .filterKeys { it in sources }

    private fun originalSources(events: List<AgentEventEnvelope>, threadId: String,
                                turnId: String): Map<String, String> = events.asSequence()
        .filter { it.threadId == threadId && it.turnId == turnId &&
            it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
        .map { Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson) }
        .filter { !it.observationId.isNullOrBlank() && it.mimeType.startsWith("image/") &&
            it.role != DetailAttachmentRole.DIAGNOSTIC }
        .groupBy { checkNotNull(it.observationId) }
        .mapNotNull { (id, attachments) ->
            attachments.map { it.contentRef }.distinct().singleOrNull()?.let { id to it }
        }.toMap()

}
