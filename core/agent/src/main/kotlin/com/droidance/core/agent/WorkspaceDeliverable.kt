// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.workspace.TaskWorkspace
import java.net.URLEncoder
import java.net.URLDecoder
import java.net.URI
import kotlinx.serialization.json.*

/** Local, immutable, published workspace files. No filesystem path or arbitrary blob access. */
object WorkspaceDeliverable {
    fun isLink(link: String): Boolean = runCatching { URI(link).let {
        it.scheme == "droidance" && it.host == "deliverable"
    } }.getOrDefault(false)
    fun link(turnId: String, path: String, reference: String): String =
        "droidance://deliverable?turn=" + encode(turnId) + "&path=" + encode(path) + "&ref=" + encode(reference)

    suspend fun read(link: String, events: AgentEventStore, workspace: TaskWorkspace): Pair<String, String> {
        val uri = URI(link)
        require(uri.scheme == "droidance" && uri.host == "deliverable")
        val args = uri.rawQuery.split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            require(pair.size == 2)
            decode(pair[0]) to decode(pair[1])
        }
        require(args.keys == setOf("turn", "path", "ref"))
        val turn = args.getValue("turn")
        val path = args.getValue("path")
        val reference = args.getValue("ref")
        require(link == link(turn, path, reference))
        val history = events.eventsForTurn(turn)
        val publications = SessionEvidenceProjector.steps(history, history.firstOrNull()?.threadId.orEmpty(), turn)
            .filter { it.call.effect == ToolEffect.COMPLETION && it.call.name in setOf("done", "present_result_card") &&
                it.result.isVerifiedSuccess }.filter { step ->
                runCatching {
                    val body = Json.parseToJsonElement(step.call.argumentsJson).jsonObject
                    (body["results"] ?: body["items"])!!.jsonArray.any { entry ->
                        link in CompletionLinks.destinations(com.droidance.core.agent.harness.PublicResultEntry.parse(entry).markdown())
                    }
                }.getOrDefault(false)
            }
        require(history.any { event -> event.eventKind == AgentEventKind.TURN_COMPLETED &&
            publications.any { it.event.sequence < event.sequence } &&
            link in CompletionLinks.destinations(Json.decodeFromString<TurnCompletedEventPayload>(event.payloadJson).assistantReply.orEmpty()) }) {
            "DELIVERABLE_NOT_PUBLISHED"
        }
        val thread = history.first().threadId
        val version = TaskWorkspace.versions(history, TurnEventScope(thread, turn))
            .firstOrNull { it.path == path && it.contentRef == reference } ?: error("DELIVERABLE_VERSION_MISSING")
        return path to workspace.readVersion(version)
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
}
