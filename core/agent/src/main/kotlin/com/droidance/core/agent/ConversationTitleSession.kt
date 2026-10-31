// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.ModelRoundCompletedEventPayload
import com.droidance.core.agent.event.ThreadCreatedEventPayload
import com.droidance.core.agent.event.ThreadMetadataChangedEventPayload
import com.droidance.core.agent.event.TurnEventScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lets the same Agent review the main-task title once per user input. */
class ConversationTitleSession(private val events: AgentEventStore, private val scope: TurnEventScope) {
    private var evaluatedInputSequence = 0L

    data class Request(
        val inputSequence: Long?,
        val metadataSequence: Long?,
        val updateAllowed: Boolean,
        val context: String
    ) {
        fun acceptedTitle(title: String?): String? = title.takeIf { updateAllowed }

        fun finishMetadata(title: String?): Map<String, String> = buildMap {
            inputSequence?.let { put(INPUT_SEQUENCE, it.toString()) }
            acceptedTitle(title)?.let { put("conversation_title", it) }
        }
    }

    suspend fun request(
        turnEvents: List<AgentEventEnvelope>? = null,
        eligible: Boolean = true
    ): Request {
        val history = turnEvents ?: events.eventsForTurn(scope.turnId)
        val input = latestInput(history)
        val committedSequence = history.asSequence()
            .filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .mapNotNull { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson)
                .finishMetadata[INPUT_SEQUENCE]?.toLongOrNull() }.maxOrNull() ?: 0L
        val snapshot = snapshot(events.eventsForThread(scope.threadId))
        // A missing title leaves the initial snippet pending for the next ordinary response.
        // This never schedules a request solely for naming. Generated titles are still
        // considered once per new input, including across Phase A and session recovery.
        val pending = eligible && input != null &&
            (snapshot.state == "initial" || input.sequence > maxOf(evaluatedInputSequence, committedSequence))
        if (!pending) return Request(null, null, false, context(false))
        return Request(input!!.sequence, snapshot.sequence, !snapshot.locked,
            context(!snapshot.locked, snapshot.title, snapshot.state))
    }

    /** Called only after a successful durable model-round commit, before executing its tools. */
    suspend fun commit(request: Request, title: String?, roundId: String) {
        val inputSequence = request.inputSequence ?: return
        evaluatedInputSequence = maxOf(evaluatedInputSequence, inputSequence)
        val acceptedTitle = request.acceptedTitle(title) ?: return
        if (latestInput(events.eventsForTurn(scope.turnId))?.sequence != inputSequence) return
        val current = snapshot(events.eventsForThread(scope.threadId))
        if (current.locked || current.sequence != request.metadataSequence ||
            (current.state == "generated" && current.title == acceptedTitle)) return
        events.changeThreadMetadata(scope.threadId, "$AUTO_CHANGE_PREFIX$roundId", acceptedTitle, current.summary)
    }

    private data class Snapshot(val title: String, val summary: String?, val sequence: Long,
        val locked: Boolean, val state: String)

    private fun snapshot(history: List<AgentEventEnvelope>): Snapshot {
        val changed = history.lastOrNull { it.eventKind == AgentEventKind.THREAD_METADATA_CHANGED }
        if (changed != null) {
            val payload = Json.decodeFromString<ThreadMetadataChangedEventPayload>(changed.payloadJson)
            val automatic = changed.idempotencyKey.startsWith("thread:${scope.threadId}:metadata:$AUTO_CHANGE_PREFIX")
            return Snapshot(payload.title, payload.summary, changed.sequence, !automatic,
                if (automatic) "generated" else "locked")
        }
        val created = history.first { it.eventKind == AgentEventKind.THREAD_CREATED }
        return Snapshot(Json.decodeFromString<ThreadCreatedEventPayload>(created.payloadJson).initialTitle,
            null, created.sequence, false, "initial")
    }

    private fun latestInput(history: List<AgentEventEnvelope>) = history.lastOrNull {
        it.threadId == scope.threadId && it.turnId == scope.turnId &&
            it.eventKind in setOf(AgentEventKind.USER_MESSAGE, AgentEventKind.USER_STEER)
    }

    private fun context(allowed: Boolean, title: String? = null, state: String? = null): String =
        "<conversation_title_context>" + buildJsonObject {
            put("update_allowed", allowed)
            title?.let { put("current_title", it) }
            state?.let { put("title_state", it) }
            put("max_title_chars", ConversationTitleProtocol.MAX_TITLE_CHARS)
        } + "</conversation_title_context>"

    companion object {
        const val INPUT_SEQUENCE = "conversation_title_input_sequence"
        private const val AUTO_CHANGE_PREFIX = "auto-title:"
    }
}
