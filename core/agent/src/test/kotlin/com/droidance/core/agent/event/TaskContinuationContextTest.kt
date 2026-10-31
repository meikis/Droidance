// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TaskContinuationContextTest {
    private fun requirementMemo(index: Int, requirements: String, status: ToolExecutionStatus = ToolExecutionStatus.SUCCEEDED,
                                constraints: String = "[]") =
        settled(index, status).map { event ->
            if (event.eventKind != AgentEventKind.TOOL_CALL) event else event.copy(payloadJson = Json.encodeToString(
                Json.decodeFromString<ToolCallEventPayload>(event.payloadJson).copy(name = "task_memo",
                    argumentsJson = """{"record_path":"work.md","state":"working note","requirements":$requirements,"steps":[],"output_constraints":$constraints,"requested_deliverables":[],"protected_values":[]}""")))
        }

    @Test fun extendingRequirementsKeepsConstraintIdsAndCannotDropOriginalConstraints() {
        val original = requirementMemo(1, "[\"read state\"]", constraints = "[\"omit identifiers\"]")
        val extended = requirementMemo(2, "[\"read state\",\"deliver report\"]", constraints = "[\"omit identifiers\",\"keep order\"]")
        val narrowed = requirementMemo(3, "[\"read state\",\"deliver report\"]")
        val plan = TaskRequirementPlan.current(original + extended + narrowed, "thread", "turn")!!
        assertEquals(listOf("r1", "r2", "c1", "c2"), plan.requirementIds)
        val rendered = Json.parseToJsonElement(plan.render()).toString()
        assertTrue(rendered.contains("\"id\":\"c1\",\"label\":\"omit identifiers\""))
        assertFalse(plan.isCoveredBy(listOf("r1", "r2")))
        assertTrue(plan.isCoveredBy(listOf("r1", "r2", "c1", "c2")))
    }

    @Test fun firstRequirementPlanSurvivesMemoReplacementCompletionAndCompaction() {
        val events = requirementMemo(1, "[\"first requirement\",\"second requirement\"]") +
            requirementMemo(2, "[\"narrower task\"]") + settled(3) +
            event(20, AgentEventKind.CONTEXT_CHECKPOINT, null, "{}")
        val plan = TaskRequirementPlan.current(events.reversed() + events, "thread", "turn")!!
        assertEquals(listOf("first requirement", "second requirement"), plan.requirements)
        assertTrue(plan.isCoveredBy(listOf("r2", "r1")))
        assertFalse(plan.isCoveredBy(listOf("first requirement")))
        assertFalse(plan.isCoveredBy(listOf("first requirement", "first requirement")))
        assertNull(TaskRequirementPlan.current(events, "thread", "other-turn"))
        val extended = TaskRequirementPlan.current(events + requirementMemo(6,
            "[\"first requirement\",\"second requirement\",\"previously missed requirement\"]"), "thread", "turn")!!
        assertEquals(3, extended.requirements.size)
        assertFalse(extended.isCoveredBy(plan.requirementIds))
    }

    @Test fun onlyAUserUpdateCanStartAnotherRequirementsEpoch() {
        val original = requirementMemo(1, "[\"original\"]")
        val update = event(8, AgentEventKind.USER_STEER, null, "{}")
        assertNull(TaskRequirementPlan.current(original + update, "thread", "turn"))
        val failed = requirementMemo(3, "[\"failed\"]", ToolExecutionStatus.FAILED)
        val newPlan = requirementMemo(4, "[\"updated requirement\"]")
        assertNull(TaskRequirementPlan.current(original + update + failed, "thread", "turn"))
        assertEquals(listOf("updated requirement"), TaskRequirementPlan.current(
            original + update + failed + newPlan, "thread", "turn")!!.requirements)
    }

    @Test fun anExistingDeclarationStaysPendingAcrossSeveralUpdatesUntilSuccessfulRefresh() {
        val original = requirementMemo(1, "[\"original\"]", constraints = "[\"keep private\"]")
        val updates = listOf(event(8, AgentEventKind.USER_STEER, null, "{}"),
            event(9, AgentEventKind.USER_STEER, null, "{}"))
        val failed = requirementMemo(3, "[\"failed refresh\"]", ToolExecutionStatus.FAILED)
        val events = original + updates + failed
        val previous = TaskRequirementPlan.current(original, "thread", "turn")!!
        assertNull(TaskRequirementPlan.current(events, "thread", "turn"))
        assertEquals(previous, TaskRequirementPlan.pendingRefresh(events.reversed() + events, "thread", "turn"))
        val refreshed = events + requirementMemo(4, "[\"updated requirement\"]", constraints = "[\"keep private\"]")
        assertNull(TaskRequirementPlan.pendingRefresh(refreshed, "thread", "turn"))
        assertEquals(listOf("updated requirement"), TaskRequirementPlan.current(refreshed, "thread", "turn")!!.requirements)
    }

    @Test fun userUpdatesNeverCreateAPlanAndPendingRefreshIsScopedToItsTurn() {
        val updates = listOf(event(8, AgentEventKind.USER_STEER, null, "{}"),
            event(9, AgentEventKind.USER_STEER, null, "{}"))
        assertNull(TaskRequirementPlan.current(updates, "thread", "turn"))
        assertNull(TaskRequirementPlan.pendingRefresh(updates, "thread", "turn"))
        val original = requirementMemo(1, "[\"original\"]")
        assertNull(TaskRequirementPlan.pendingRefresh(original, "thread", "turn"))
        assertNull(TaskRequirementPlan.pendingRefresh(original + updates, "thread", "other-turn"))
        assertNull(TaskRequirementPlan.pendingRefresh(original + updates, "other-thread", "turn"))
    }

    @Test fun anOldInFlightMemoCannotReplaceThePendingPreUpdateContract() {
        val original = requirementMemo(1, "[\"original\"]", constraints = "[\"keep private\"]")
        val inFlight = requirementMemo(3, "[\"in-flight replacement\"]").map {
            if (it.eventKind == AgentEventKind.TOOL_CALL) it
            else it.copy(sequence = it.sequence + 1, eventId = it.eventId + "-delayed")
        }
        val events = original + inFlight + event(13, AgentEventKind.USER_STEER, null, "{}")
        assertNull(TaskRequirementPlan.current(events, "thread", "turn"))
        assertEquals(TaskRequirementPlan.current(original, "thread", "turn"),
            TaskRequirementPlan.pendingRefresh(events, "thread", "turn"))
    }

    @Test fun noUpdateKeepsUnregisteredChatContextEmptyAndRegisteredScopeUnchanged() {
        assertEquals("", TaskRequirementPlan.modelContext(emptyList(), "thread", "turn"))
        val original = requirementMemo(1, "[\"original\"]", constraints = "[\"keep private\"]")
        assertEquals(TaskRequirementPlan.current(original, "thread", "turn")!!.render(),
            TaskRequirementPlan.modelContext(original, "thread", "turn"))
    }

    @Test fun updateFactRemainsPendingAfterAnOldInFlightMemoAndFailedFreshMemo() {
        val original = requirementMemo(1, "[\"original\"]")
        val inFlight = requirementMemo(3, "[\"original\"]").map {
            if (it.eventKind == AgentEventKind.TOOL_CALL) it
            else it.copy(sequence = it.sequence + 1, eventId = it.eventId + "-delayed")
        }
        val update = event(13, AgentEventKind.USER_STEER, null, "{}")
        val events = original + inFlight + update +
            requirementMemo(4, "[\"new scope\"]", ToolExecutionStatus.NOT_EXECUTED) +
            event(30, AgentEventKind.CONTEXT_CHECKPOINT, null, "{}")
        assertNull(TaskRequirementPlan.current(events, "thread", "turn"))
        val rendered = TaskRequirementPlan.modelContext(events.reversed() + events, "thread", "turn")
        val context = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("false", context.getValue("scope_current").jsonPrimitive.content)
        assertEquals(update.eventId, context.getValue("latest_user_update_ref").jsonPrimitive.content)
        assertEquals("13", context.getValue("latest_user_update_sequence").jsonPrimitive.content)
        assertFalse(context.containsKey("requirements"))
        assertFalse(rendered.contains("original"))
    }

    @Test fun aFreshSuccessfulMemoClearsPendingStatusAndForeignUpdatesCannotInvalidateIt() {
        val original = requirementMemo(1, "[\"original\"]")
        val update = event(8, AgentEventKind.USER_STEER, null, "{}")
        val fresh = requirementMemo(4, "[\"applicable scope\"]", constraints = "[\"keep private\"]")
        val foreign = event(30, AgentEventKind.USER_STEER, null, "{}").copy(turnId = "foreign-turn")
        val events = original + update + fresh + foreign
        val plan = TaskRequirementPlan.current(events, "thread", "turn")!!
        assertEquals(listOf("applicable scope"), plan.requirements)
        assertEquals(plan.render(), TaskRequirementPlan.modelContext(events, "thread", "turn"))
        assertFalse(TaskRequirementPlan.modelContext(events, "thread", "turn").contains("scope_current"))
        assertEquals("", TaskRequirementPlan.modelContext(events, "foreign-thread", "turn"))
    }

    @Test fun latestSuccessfulMemoSurvivesLaterCallsAndCheckpoint() {
        fun memo(index: Int, text: String, status: ToolExecutionStatus) = settled(index, status).map { event ->
            when (event.eventKind) {
                AgentEventKind.TOOL_CALL -> event.copy(payloadJson = Json.encodeToString(
                    Json.decodeFromString<ToolCallEventPayload>(event.payloadJson).copy(
                        name = "task_memo", binding = ToolBinding.TURN_LOCAL)))
                AgentEventKind.TOOL_FINISHED -> event.copy(payloadJson = Json.encodeToString(
                    Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson).let {
                        it.copy(result = it.result.copy(outputText = text))
                    }))
                else -> event
            }
        }
        val events = memo(1, "A=12.34 ref=obs-a; B remains", ToolExecutionStatus.SUCCEEDED) +
            memo(2, "invalid replacement", ToolExecutionStatus.FAILED) + (3..15).flatMap { settled(it) } +
            event(200, AgentEventKind.CONTEXT_CHECKPOINT, null, "{}")
        val rendered = TaskContinuationContext.render(events, "thread", "turn")
        assertEquals("A=12.34 ref=obs-a; B remains", TaskContinuationContext.latestMemo(events, "thread", "turn")?.state)
        assertNull(TaskContinuationContext.latestMemo(events, "thread", "other-turn"))
        assertTrue(rendered.contains("A=12.34 ref=obs-a; B remains"))
        assertTrue(rendered.contains("model_authored_task_memo"))
        assertFalse(rendered.contains("invalid replacement"))
    }

    @Test fun keepsLatestModelProgressAndSettledResultsAcrossCompaction() {
        val progress = event(200, AgentEventKind.PUBLIC_PROGRESS, null,
            Json.encodeToString(PublicProgressEventPayload(1, listOf("Observed A; B remains."), "detail")))
        val events = (1..10).flatMap { settled(it) } + progress +
            event(201, AgentEventKind.CONTEXT_CHECKPOINT, null, "{}")
        val rendered = TaskContinuationContext.render(events, "thread", "turn")
        assertFalse(rendered.contains("Observed A; B remains."))
        assertFalse(rendered.contains("model_reported_progress"))
        assertFalse(rendered.contains("call-1\""))
        assertTrue(rendered.contains("call-10"))
        assertTrue(rendered.contains("\"evidence_ref\":\"call-10\""))
        assertFalse(rendered.contains("\"call_ref\""))
        assertEquals(rendered, TaskContinuationContext.render(events.reversed() + events, "thread", "turn"))
    }

    @Test fun retainedRequestHistoryIsNotRepeatedInTheContinuationTail() {
        val events = (1..4).flatMap { settled(it) }
        val full = TaskContinuationContext.render(events, "thread", "turn")
        assertTrue(full.contains("recent_calls_oldest_first"))
        val retained = TaskContinuationContext.render(events, "thread", "turn", historyRetained = true)
        assertEquals("", retained)
        val failed = events + settled(5, ToolExecutionStatus.FAILED)
        val withFailure = TaskContinuationContext.render(failed, "thread", "turn", historyRetained = true)
        assertFalse(withFailure.contains("recent_calls_oldest_first"))
        assertFalse(withFailure.contains("failed_device_calls"))
    }

    @Test fun rejectedAndUncertainOutcomesRemainFactsWithoutInventingCompletion() {
        val rendered = TaskContinuationContext.render(settled(1, ToolExecutionStatus.INTERRUPTED) +
            settled(2, evidence = ToolEvidenceStatus.INVALID), "thread", "turn")
        assertTrue(rendered.contains("INTERRUPTED"))
        assertTrue(rendered.contains("INVALID"))
        assertFalse(rendered.contains("semantic_success"))
        assertEquals("", TaskContinuationContext.render(settled(3).take(1), "thread", "turn"))
        assertEquals("", TaskContinuationContext.render(settled(4), "thread", "other-turn"))
    }

    @Test fun focusedRecoveryRetainsEarlierNativeFailuresWithoutModelNarration() {
        val denied = settled(1, ToolExecutionStatus.NOT_EXECUTED).map { e ->
            when (e.eventKind) {
                AgentEventKind.TOOL_CALL -> e.copy(payloadJson = Json.encodeToString(
                    Json.decodeFromString<ToolCallEventPayload>(e.payloadJson).copy(
                        name = "android_act", argumentsJson = """{"op":"open","uri":"example://search"}""")))
                AgentEventKind.TOOL_FINISHED -> e.copy(payloadJson = Json.encodeToString(
                    Json.decodeFromString<ToolFinishedEventPayload>(e.payloadJson).let {
                        it.copy(result = it.result.copy(diagnosticCode = "NATIVE_TARGET_NOT_EXPORTED",
                            outputText = "Target component is not exported"))
                    }))
                else -> e
            }
        }
        val progress = event(200, AgentEventKind.PUBLIC_PROGRESS, null,
            Json.encodeToString(PublicProgressEventPayload(1, listOf("unsupported model assertion"), "detail")))
        val events = denied + (2..10).flatMap { settled(it) } + progress
        val rendered = TaskContinuationContext.render(events, "thread", "turn", includeModelAuthoredMemo = false)
        assertTrue(rendered.contains("NATIVE_TARGET_NOT_EXPORTED"))
        assertTrue(rendered.contains("example://search"))
        assertTrue(rendered.contains("Target component is not exported"))
        assertTrue(rendered.contains("call-10"))
        assertFalse(rendered.contains("unsupported model assertion"))
        assertFalse(rendered.contains("model_authored_task_memo"))
        assertEquals("", TaskContinuationContext.render(events, "thread", "other", false))
    }

    @Test fun objectUpdatesRetainFirstSeenOrderSourcesAndPendingFieldsAcrossCompaction() {
        fun round(sequence: Long, records: String) = event(sequence, AgentEventKind.MODEL_ROUND_COMPLETED, null,
            Json.encodeToString(ModelRoundCompletedEventPayload(1, "a".repeat(64), SubmittedItemSequenceRange(1, 1), 1,
                emptyList(), null, mapOf("model_observations" to
                    """[{"provider_call_id":"model-call","records":$records}]"""),
                com.droidance.core.agent.harness.RoundExecutionMode.SERIAL, com.droidance.core.agent.harness.RoundFollowUp.NONE, null, null, null, null, "detail")))
        val initial = round(1, """[{"record_id":"a","fields":{"name":{"value":"First","source_ref":"source-a"}},"assessment":"excluded"},{"record_id":"b","fields":{"name":{"value":"Second","source_ref":"source-b"}},"uncertainties":["price unread"]}]""")
        val update = round(2, """[{"record_id":"b","fields":{"price":{"value":"89 each","source_ref":"detail-b"}},"assessment":"selected","uncertainties":[]}]""")
        val events = listOf(initial, update, event(3, AgentEventKind.CONTEXT_CHECKPOINT, null, "{}"))
        val rendered = TaskContinuationContext.render(events.reversed() + events, "thread", "turn")
        assertTrue(rendered.contains("model_authored_objects"))
        assertTrue(rendered.contains("source-b"))
        assertTrue(rendered.contains("detail-b"))
        assertTrue(rendered.contains("89 each"))
        assertFalse(rendered.contains("price unread"))
        assertTrue(rendered.indexOf("First") < rendered.indexOf("Second"))
        val recovery = TaskContinuationContext.render(events, "thread", "turn", includeModelAuthoredMemo = false)
        assertTrue(recovery.contains("model_authored_objects"))
        assertTrue(recovery.contains("Second"))
        assertTrue(recovery.contains("source-b"))
        assertTrue(recovery.contains("89 each"))
        assertFalse(recovery.contains("price unread"))
        assertEquals("", TaskContinuationContext.render(events, "thread", "foreign"))
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
    @Test fun rejectionSurvivesLaterRoundsAndOnlyItsExplicitRepairClearsIt() {
        fun round(sequence: Long, receipts: String) = event(sequence, AgentEventKind.MODEL_ROUND_COMPLETED, null,
            Json.encodeToString(ModelRoundCompletedEventPayload(1, "a".repeat(64), SubmittedItemSequenceRange(1, 1), 1,
                emptyList(), null, mapOf("model_record_receipts" to receipts),
                com.droidance.core.agent.harness.RoundExecutionMode.SERIAL, com.droidance.core.agent.harness.RoundFollowUp.NONE,
                null, null, null, null, "detail")))
        val rejection = round(1, """{"call":{"status":"rejected","repair_ref":"repair-a","round_ref":"round-a","reason":"RECORD_ID_REQUIRED","error_path":"records[0].record_id","rejected_records":[{"fields":{"name":{"value":"Original","source_ref":"source-a"}}}]}}""")
        val unrelated = round(2, """{"call":{"status":"saved","repair_ref":"unrelated","round_ref":"round-b"}}""")
        val later = round(3, "{}")
        val history = listOf(rejection, unrelated, later)
        val rendered = TaskContinuationContext.render(history, "thread", "turn", false)
        assertTrue(rendered.contains("pending_model_record_repairs"))
        assertTrue(rendered.contains("Original"))
        assertTrue(rendered.contains("records[0].record_id"))
        val pending = ModelRecordRepairs.project(history, "thread", "turn")
        assertEquals(setOf("repair-a"), pending.pending.keys)
        assertTrue(pending.active(4)); assertFalse(pending.active(2))
        val saved = round(4, """{"call":{"status":"saved","repair_ref":"repair-a","repair_of":"repair-a","round_ref":"round-c"}}""")
        assertTrue(ModelRecordRepairs.project(history + saved, "thread", "turn").pending.isEmpty())
        assertTrue(ModelRecordRepairs.project(history, "thread", "foreign").pending.isEmpty())
        assertEquals(pending, ModelRecordRepairs.project(history.reversed() + history, "thread", "turn"))
    }

    @Test fun oversizedRejectedPayloadKeepsItsOriginalReceiptReferenceWithinBudget() {
        val receipt = kotlinx.serialization.json.buildJsonObject {
            put("round_ref", kotlinx.serialization.json.JsonPrimitive("round-a"))
            put("reason", kotlinx.serialization.json.JsonPrimitive("RECORD_ID_REQUIRED"))
            put("error_path", kotlinx.serialization.json.JsonPrimitive("records[0].record_id"))
            put("rejected_records", kotlinx.serialization.json.JsonPrimitive("x".repeat(10000)))
        }
        val snapshot = ModelRecordRepairs.Snapshot(mapOf("repair-a" to
            ModelRecordRepairs.Pending(receipt, 1, "settled-tool-event")), 0)
        val text = snapshot.context(512).toString()
        assertTrue(text.length <= 512)
        assertTrue(text.contains("settled-tool-event"))
        assertTrue(text.contains("read_original_receipt"))
        assertFalse(text.contains("xxxx"))
    }

}
