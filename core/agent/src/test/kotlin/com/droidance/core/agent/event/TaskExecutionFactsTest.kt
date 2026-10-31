// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionFactsTest {
    @Test fun recentOutcomesAreBoundedScopedAndKeepUnknownStateSeparateFromSuccessfulExecution() {
        val events = (1..12).flatMap { settled(it) } + settled(13).map { it.copy(turnId = "other") }
        val recent = TaskExecutionFacts.recent(events.reversed() + events, "thread", "turn", 2)
        assertEquals(2, recent.size)
        assertEquals("call-11", recent.first().jsonObject.getValue("result_observation_ref").jsonPrimitive.content)
        assertEquals(JsonNull, recent.last().jsonObject["state_changed"])
        assertEquals("SUCCEEDED", recent.last().jsonObject.getValue("execution_status").jsonPrimitive.content)
        assertTrue(recent.toString().contains("INPUT_OBSERVATION"))
        assertTrue(!recent.toString().contains("goal_success"))
        assertTrue(!recent.toString().contains("receipt"))
    }

    @Test
    fun closedCallsSurviveCheckpointAndReconstructionWithoutCountingAttachmentsAsCalls() {
        val events = (1..23).flatMap { settled(it) } +
            event(1000, AgentEventKind.CONTEXT_CHECKPOINT, null, "{}")
        val facts = TaskExecutionFacts.project(events, "thread", "turn")
        assertEquals(23, facts.single().calls)
        assertEquals(23, facts.single().inputObservations)
        assertEquals("call-23", facts.single().latestEvidenceRef)
        assertEquals(facts, TaskExecutionFacts.project(events.reversed(), "thread", "turn"))
        assertEquals(facts, TaskExecutionFacts.project(events + events, "thread", "turn"))
    }

    @Test
    fun scopeUnsettledCallsAndUnlinkedAttachmentsCannotInflateCounts() {
        val one = settled(1)
        val events = one + settled(2).map { it.copy(turnId = "other") } +
            settled(3).map { it.copy(threadId = "other") } + settled(4).take(1) +
            settled(5).drop(1) + one.last().copy(eventId = "loose", callId = null)
        assertEquals(1, TaskExecutionFacts.project(events, "thread", "turn").single().calls)
    }

    @Test
    fun failuresAndInvalidEvidenceRemainDistinctFromSuccessfulObservations() {
        val events = settled(1) + settled(2, ToolExecutionStatus.FAILED) +
            settled(3, evidence = ToolEvidenceStatus.INVALID)
        val facts = TaskExecutionFacts.project(events, "thread", "turn")
        assertEquals(3, facts.sumOf { it.calls })
        assertEquals(1, facts.sumOf { it.inputObservations })
        assertTrue(facts.any { it.status == ToolExecutionStatus.FAILED })
        assertTrue(facts.any { it.evidence == ToolEvidenceStatus.INVALID })
    }

    @Test
    fun onlyMatchingPostSettlementInputAttachmentsCountAsObservations() {
        val original = settled(1)
        val attachment = Json.decodeFromString<DetailAttachmentAddedEventPayload>(original.last().payloadJson)
        val variants = listOf(
            original.dropLast(1),
            original.dropLast(1) + original.last().copy(payloadJson = Json.encodeToString(
                attachment.copy(role = DetailAttachmentRole.RESULT))),
            original.dropLast(1) + original.last().copy(payloadJson = Json.encodeToString(
                attachment.copy(role = DetailAttachmentRole.DIAGNOSTIC))),
            original.dropLast(1) + original.last().copy(payloadJson = Json.encodeToString(
                attachment.copy(observationId = "unrelated"))),
            original.dropLast(1) + original.last().copy(sequence = 1)
        )
        variants.forEach { events ->
            val fact = TaskExecutionFacts.project(events, "thread", "turn").single()
            assertEquals(1, fact.calls)
            assertEquals(0, fact.inputObservations)
            assertEquals(null, fact.latestEvidenceRef)
        }
    }

    @Test
    fun rejectedAndInterruptedCallsAreReportedWithoutInventingSuccess() {
        val original = settled(1)
        val rejected = original.first() to original[1].copy(
            eventKind = AgentEventKind.TOOL_CALL_REJECTED,
            payloadJson = Json.encodeToString(ToolCallRejectedEventPayload(
                1, ToolExecutionResult("not executed", ToolExecutionStatus.NOT_EXECUTED,
                    ToolEvidenceStatus.NOT_REQUIRED, diagnosticCode = "TEST"), null, null, "round-detail"
            ))
        )
        val facts = TaskExecutionFacts.project(listOf(rejected.first, rejected.second) +
            settled(2, ToolExecutionStatus.CANCELED) + settled(3, ToolExecutionStatus.INTERRUPTED),
            "thread", "turn")
        assertEquals(3, facts.sumOf { it.calls })
        assertEquals(0, facts.sumOf { it.inputObservations })
        assertEquals(setOf(ToolExecutionStatus.NOT_EXECUTED, ToolExecutionStatus.CANCELED,
            ToolExecutionStatus.INTERRUPTED), facts.map { it.status }.toSet())
    }

    @Test
    fun serializedProjectionOmitsRawPayloadsAndDoesNotAssertGoalSuccess() {
        val rendered = TaskExecutionFacts.render(settled(1), "thread", "turn")
        val retained = TaskExecutionFacts.render(settled(1), "thread", "turn", historyRetained = true)
        assertTrue(retained.contains("cumulative_execution_facts"))
        assertFalse(retained.contains("recent_execution_facts"))
        assertTrue(rendered.contains("\"calls\":1"))
        assertTrue(rendered.contains("\"latestEvidenceRef\":\"call-1\""))
        assertTrue(!rendered.contains("latestCallRef"))
        listOf("argumentsJson", "outputText", "receipt", "goal_success", "task_completed").forEach {
            assertTrue(it, !rendered.contains(it))
        }
        assertEquals("", TaskExecutionFacts.render(emptyList(), "thread", "turn"))
    }

    private fun settled(
        index: Int,
        status: ToolExecutionStatus = ToolExecutionStatus.SUCCEEDED,
        evidence: ToolEvidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
    ): List<AgentEventEnvelope> {
        val call = "call-$index"
        val seq = index * 4L
        return listOf(
            event(seq, AgentEventKind.TOOL_CALL, call, Json.encodeToString(ToolCallEventPayload(
                contractVersion = 1, providerOrder = 0, providerCallId = call,
                name = "generic_read", argumentsJson = "{}", source = ToolCallSource.MODEL_REQUESTED,
                binding = ToolBinding.DEVICE_BOUND, effect = ToolEffect.READ_ONLY,
                contractHash = "a".repeat(64), presentation = ToolPresentationSnapshot(
                    "tool.read", ToolPresentationCategory.PRIMARY, ResultImageRole.INPUT
                ), roundDetailRef = "round-detail"
            ))),
            event(seq + 1, AgentEventKind.TOOL_FINISHED, call, Json.encodeToString(ToolFinishedEventPayload(
                1, ToolExecutionResult("receipt", status, evidence, call, "TEST"), "round-detail"
            ))),
            event(seq + 2, AgentEventKind.DETAIL_ATTACHMENT_ADDED, call, Json.encodeToString(
                DetailAttachmentAddedEventPayload(1, "attachment-$index", DetailAttachmentRole.INPUT_OBSERVATION,
                    "sha256:" + "a".repeat(64), "a".repeat(64), 12, "image/jpeg", observationId = call)
            ))
        )
    }

    private fun event(sequence: Long, kind: AgentEventKind, call: String?, payload: String) =
        AgentEventEnvelope(1, "event-$sequence", kind, sequence, sequence, "thread", "turn",
            "round", null, null, call, "a".repeat(64), payload, "key-$sequence")
}
