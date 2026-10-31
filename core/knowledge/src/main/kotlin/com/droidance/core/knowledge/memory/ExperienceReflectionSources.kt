// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.event.*
import kotlinx.serialization.json.*

/** Possible learning writes; admission still requires their actual persistent APPLIED receipt. */
object ExperienceReflectionSources {
    data class Source(val receiptKey: String, val ref: String)

    fun collect(
        events: List<AgentEventEnvelope>,
        turnId: String,
        completionProposals: List<ExperienceProjection.Proposal>
    ): List<Source> {
        val completed = events.singleOrNull {
            it.turnId == turnId && it.eventKind == AgentEventKind.TURN_COMPLETED
        } ?: return emptyList()
        val immediate = SessionEvidenceProjector.steps(events, completed.threadId, turnId).mapNotNull { step ->
            if (step.event.sequence >= completed.sequence || step.call.name != "experience" ||
                !step.result.isVerifiedSuccess) return@mapNotNull null
            val args = Json.parseToJsonElement(step.call.argumentsJson).jsonObject
            if (args["op"]?.jsonPrimitive?.contentOrNull != "save") return@mapNotNull null
            val receipt = runCatching { Json.parseToJsonElement(step.result.outputText).jsonObject }.getOrNull()
                ?: return@mapNotNull null
            if (receipt["persistence_confirmed"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
            val ref = receipt["ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Source("experience-save:${step.event.callId}", ref)
        }
        val projected = completionProposals.mapNotNull { proposal ->
            proposal.record?.takeIf { it.threadId == completed.threadId && it.turnId == turnId }
                ?.let { Source(proposal.key, it.ref) }
        }
        return (immediate + projected).distinctBy { it.receiptKey }
    }

    fun applied(sources: List<Source>, receiptOutcomes: Map<String, String?>): Set<String> = sources
        .filter { receiptOutcomes[it.receiptKey] == "APPLIED" }.map { it.ref }.toSet()
}
