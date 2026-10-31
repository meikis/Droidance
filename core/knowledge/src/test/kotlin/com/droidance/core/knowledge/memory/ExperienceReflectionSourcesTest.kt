// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.event.*
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceReflectionSourcesTest {
    @Test fun onlyASettledCurrentTurnSaveWithAnAppliedStorageReceiptAdmitsReflection() {
        val fixture = Fixture()
        fixture.learningCall("saved", "save", "experience:method:1")
        fixture.learningCall("conflict", "save", "experience:other:1")
        fixture.learningCall("used", "use", "experience:method:1")
        fixture.learningCall("historical", "save", "experience:old:1", turnId = "old-turn")
        fixture.complete()

        val sources = ExperienceReflectionSources.collect(fixture.events, "turn", emptyList())
        assertEquals(setOf("experience-save:saved", "experience-save:conflict"), sources.map { it.receiptKey }.toSet())
        val applied = ExperienceReflectionSources.applied(sources, mapOf(
            "experience-save:saved" to "APPLIED", "experience-save:conflict" to "EXPERIENCE_REVISION_CONFLICT",
            "experience-save:used" to "APPLIED", "experience-save:historical" to "APPLIED"))
        assertEquals(setOf("experience:method:1"), applied)
    }

    @Test fun proposedCompletionLearningNeedsAnAppliedReceiptAndRejectedProposalsDoNotAdmit() {
        val fixture = Fixture(); fixture.complete()
        val proposal = ExperienceProjection.Proposal("completion:0", record(), null)
        val rejected = ExperienceProjection.Proposal("completion:1", null, "EXPERIENCE_EVIDENCE_REQUIRED")
        val sources = ExperienceReflectionSources.collect(fixture.events, "turn", listOf(proposal, rejected))
        assertEquals(listOf(ExperienceReflectionSources.Source("completion:0", "experience:method:1")), sources)
        assertTrue(ExperienceReflectionSources.applied(sources, emptyMap()).isEmpty())
        assertTrue(ExperienceReflectionSources.applied(sources,
            mapOf("completion:0" to "EXPERIENCE_REVISION_CONFLICT", "completion:1" to "APPLIED")).isEmpty())
        assertEquals(setOf("experience:method:1"), ExperienceReflectionSources.applied(sources,
            mapOf("completion:0" to "APPLIED")))
    }

    @Test fun unfinishedTurnsAndUnconfirmedSavesDoNotAdmitReflection() {
        val fixture = Fixture(); fixture.learningCall("unconfirmed", "save", "experience:method:1", confirmed = false)
        assertTrue(ExperienceReflectionSources.collect(fixture.events, "turn", emptyList()).isEmpty())
        fixture.complete()
        assertTrue(ExperienceReflectionSources.collect(fixture.events, "turn", emptyList()).isEmpty())
    }

    private class Fixture {
        val events = mutableListOf<AgentEventEnvelope>()

        fun learningCall(callId: String, operation: String, ref: String, turnId: String = "turn", confirmed: Boolean = true) {
            val args = buildJsonObject { put("op", operation) }
            event(AgentEventKind.TOOL_CALL, ToolCallEventPayload(MODEL_ROUND_EVENT_CONTRACT_VERSION,
                0, callId, "experience", args.toString(), ToolCallSource.MODEL_REQUESTED,
                ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING, "a".repeat(64),
                ToolPresentationSnapshot("tool.experience", ToolPresentationCategory.SUPPORTING, ResultImageRole.NONE),
                "detail"), callId, turnId)
            val result = ToolProtocolResult.success(buildJsonObject {
                put("persistence_confirmed", confirmed); put("ref", ref); put("outcome", "unknown")
            }.toString())
            event(AgentEventKind.TOOL_FINISHED,
                ToolFinishedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION, result, "round"), callId, turnId)
        }

        fun complete() = event(AgentEventKind.TURN_COMPLETED,
            TurnCompletedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION, "done", systemNotice = "Complete."))

        private inline fun <reified T> event(kind: AgentEventKind, payload: T, callId: String? = null, turnId: String = "turn") {
            val sequence = events.size.toLong() + 1
            events += AgentEventEnvelope(AGENT_EVENT_SCHEMA_VERSION, "event-$sequence", kind, sequence,
                sequence * 10, "thread", turnId, "round", null, null, callId, "a".repeat(64),
                Json.encodeToString(payload), "key-$sequence")
        }
    }

    private fun record() = ExperienceRecord(ExperienceUpdate("method", 0, "A reusable method", "", emptyList(),
        "Observe the current screen.", "Apply the method.", "Inspect changed screens.", "Check the result.",
        "Stop and inspect uncertainty.", "", "unknown", "Not yet validated.", emptyList()),
        1, "thread", "turn", 1, emptyList(), emptyList(), 1)
}
