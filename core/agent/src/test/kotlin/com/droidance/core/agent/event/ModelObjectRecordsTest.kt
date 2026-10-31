// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.tools.WorkspaceTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelObjectRecordsTest {
    private fun round(sequence: Long, records: String, turn: String = "turn") = AgentEventEnvelope(
        1, "event-$sequence-$turn", AgentEventKind.MODEL_ROUND_COMPLETED, sequence, sequence, "thread", turn,
        "round-$sequence", null, null, null, "a".repeat(64), Json.encodeToString(ModelRoundCompletedEventPayload.serializer(),
            ModelRoundCompletedEventPayload(1, "a".repeat(64), SubmittedItemSequenceRange(1, 1), 1,
                emptyList(), null, mapOf("model_observations" to """[{"provider_call_id":"call","records":$records}]"""),
                com.droidance.core.agent.harness.RoundExecutionMode.SERIAL, com.droidance.core.agent.harness.RoundFollowUp.NONE,
                null, null, null, null, "detail")), "key-$sequence-$turn")

    @Test fun completeIndexPagesCoverEveryObjectAndExactReadsKeepEarlierSources() {
        val records = (1..30).joinToString(",", "[", "]") { i ->
            """{"record_id":"row-$i","fields":{"name":{"value":"Entry $i 😀","source_ref":"source-$i"}},"uncertainties":["detail missing"]}"""
        }
        val first = round(1, records)
        val update = round(2, """[{"record_id":"row-1","fields":{"detail":{"value":"New value","source_ref":"detail-1"}},"uncertainties":[]}]""")
        val snapshot = ModelObjectRecords.project(listOf(first, update), "thread", "turn")
        var offset = 0
        val text = StringBuilder()
        do {
            val page = snapshot.page(emptyList(), offset, 83)
            assertEquals(30, page.getValue("total_objects").jsonPrimitive.int)
            text.append(page.getValue("text").jsonPrimitive.content)
            val next = page.getValue("next_offset").jsonPrimitive.int
            assertTrue(next > offset)
            offset = next
        } while (page.getValue("has_more").jsonPrimitive.boolean)
        val index = Json.parseToJsonElement(text.toString()).jsonArray
        assertEquals((1..30).map { "row-$it" }, index.map { it.jsonObject.getValue("record_id").jsonPrimitive.content })
        assertEquals("source-1", index.first().jsonObject.getValue("fields").jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse(text.contains("Entry 1")) // Index never repeats full field values.
        val selected = snapshot.page(listOf("row-1"), 0, 4096)
        val row = Json.parseToJsonElement(selected.getValue("text").jsonPrimitive.content).jsonArray.single().jsonObject
        assertEquals(2, row.getValue("fields").jsonObject.size)
        assertTrue(row.getValue("uncertainties").jsonArray.isEmpty())
        val context = TaskContinuationContext.render(listOf(first, update), "thread", "turn")
        assertTrue(context.contains("model_authored_object_index"))
        assertFalse(context.contains("\"has_more\":true"))
        val contextBody = Json.parseToJsonElement(context.substringAfter("<task_continuation_context>\n")
            .substringBefore("\n</task_continuation_context>")).jsonObject
        assertEquals(30, contextBody.getValue("model_authored_objects").jsonArray.size)
        assertFalse(contextBody.containsKey("omitted_object_count"))
        assertThrows(IllegalArgumentException::class.java) { snapshot.page(listOf("foreign"), 0, 80) }
    }

    @Test fun successfulStructuredFileWritesShareTheIndexAndFailuresCannotReplaceItsFields() {
        val initial = round(1, """[{"record_id":"a","fields":{"name":{"value":"First","source_ref":"original"}}}]""")
        fun write(seq: Long, records: String, success: Boolean = true): List<AgentEventEnvelope> {
            val callId = "write-$seq"
            val declaration = initial.copy(eventId = "call-$seq", eventKind = AgentEventKind.TOOL_CALL,
                sequence = seq, callId = callId, payloadJson = Json.encodeToString(ToolCallEventPayload.serializer(),
                    ToolCallEventPayload(1, 0, "provider-$seq", "workspace", """{"op":"records","path":"rows.json","records":$records}""",
                        ToolCallSource.MODEL_REQUESTED, ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING,
                        "a".repeat(64), ToolPresentationSnapshot("tool.workspace", ToolPresentationCategory.PRIMARY,
                            ResultImageRole.NONE), "detail")))
            val settled = declaration.copy(eventId = "result-$seq", eventKind = AgentEventKind.TOOL_FINISHED,
                sequence = seq + 1, payloadJson = Json.encodeToString(ToolFinishedEventPayload.serializer(),
                    ToolFinishedEventPayload(1, ToolExecutionResult("file receipt",
                        if (success) ToolExecutionStatus.SUCCEEDED else ToolExecutionStatus.NOT_EXECUTED,
                        ToolEvidenceStatus.NOT_REQUIRED, diagnosticCode = if (success) null else "WORKSPACE_VERSION_CHANGED"), "detail")))
            return listOf(declaration, settled)
        }
        val saved = write(2, """[{"record_id":"a","fields":{"price":{"value":"42 each","source_ref":"detail"}}},{"record_id":"b","fields":{"name":{"value":"Second","source_ref":"other"}}}]""")
        val failed = write(4, """[{"record_id":"a","fields":{"price":{"value":"wrong","source_ref":"uncommitted"}}},{"record_id":"unsaved","fields":{"name":{"value":"Absent","source_ref":"uncommitted"}}}]""", false)
        val malformed = write(6, "[]", false).map { event ->
            if (event.eventKind != AgentEventKind.TOOL_CALL) event else event.copy(payloadJson =
                Json.encodeToString(ToolCallEventPayload.serializer(),
                    Json.decodeFromString<ToolCallEventPayload>(event.payloadJson).copy(argumentsJson = "[")))
        }
        val events = listOf(initial) + saved + failed + malformed
        val snapshot = ModelObjectRecords.project(events.reversed() + events, "thread", "turn")
        assertEquals(listOf("a", "b"), snapshot.objects.keys.toList())
        assertEquals(setOf("name", "price"), snapshot.objects.getValue("a").getValue("fields").jsonObject.keys)
        assertEquals("42 each", snapshot.objects.getValue("a").getValue("fields").jsonObject.getValue("price").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(3L, snapshot.updatedSequences.getValue("a"))
        assertEquals(1, ModelObjectRecords.project(events, "thread", "turn", 2).objects.size)
        assertTrue(ModelObjectRecords.project(events, "thread", "foreign").objects.isEmpty())
        val context = TaskContinuationContext.render(events, "thread", "turn")
        assertTrue(context.contains("Second")); assertTrue(context.contains("detail"))
        val contextBody = Json.parseToJsonElement(context.substringAfter("<task_continuation_context>\n")
            .substringBefore("\n</task_continuation_context>")).jsonObject
        assertEquals(2, contextBody.getValue("model_authored_objects").jsonArray.size)
    }

    @Test fun workspaceReadsFrozenObjectPagesWithoutMutatingStateOrCrossingTurnScope() = runBlocking {
        val events = mutableListOf(round(1, """[{"record_id":"a","fields":{"value":{"value":"Original 😀 value","source_ref":"original"}}}]"""))
        val store = object : AgentEventStore by InMemoryAgentEventStore() {
            override suspend fun eventsForTurn(turnId: String) = events.filter { it.turnId == turnId }
        }
        val workspace = TaskWorkspace(store, InMemoryBlobStore())
        val scope = TurnEventScope("thread", "turn")
        val before = workspace.objects(scope, listOf("a"), null, 0, 29)
        events += round(2, """[{"record_id":"a","fields":{"value":{"value":"Changed value","source_ref":"new"}}}]""")
        var offset = before.getValue("next_offset").jsonPrimitive.int
        val through = before.getValue("through_sequence").jsonPrimitive.long
        val text = StringBuilder(before.getValue("text").jsonPrimitive.content)
        do {
            val page = workspace.objects(scope, listOf("a"), through, offset, 29)
            text.append(page.getValue("text").jsonPrimitive.content)
            offset = page.getValue("next_offset").jsonPrimitive.int
        } while (page.getValue("has_more").jsonPrimitive.boolean)
        assertTrue(text.contains("Original 😀 value"))
        assertFalse(text.contains("Changed value"))
        assertEquals(0, workspace.objects(scope.copy(turnId = "other"), emptyList(), null, 0, 80).getValue("total_objects").jsonPrimitive.int)
        assertFalse(WorkspaceTool.isWorkingStateMutation("""{"op":"objects","record_ids":["a"]}"""))
        val tool = WorkspaceTool(workspace, { scope }, { if (it == "policy") """{"maxFiles":8,"maxFileChars":4096,"maxRetainedBytes":16384,"pageChars":12000}""" else "Workspace" }, { error("No device/source read") })
        val result = tool.execute("""{"op":"objects","record_ids":["a"],"through_sequence":1}""")
        assertTrue(result.isVerifiedSuccess)
        assertEquals(false, result.stateChanged)
        assertTrue(result.outputText.contains("Original"))
        assertEquals(2, events.size)
        assertFalse(tool.execute("""{"op":"objects","record_ids":"a"}""").isVerifiedSuccess)
    }
}
