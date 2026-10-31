// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TaskSourceContextTest {
    private val rejected = ToolCall("stale", "screen_act", """{"actions":[{"op":"click","x":100,"y":200}]}""")
    private val skipped = ToolCall("skipped", "task_memo", "{}")
    private val receipt = """{"execution_status":"NOT_EXECUTED","code":"PRIOR_CALL_FAILED"}"""
    private val initial = listOf(
        ChatMessage(Role.USER, "Inspect the visible page."),
        ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(rejected, skipped)),
        ChatMessage(Role.TOOL, "PRE_ACTION_OBSERVATION_CHANGED", toolCallId = rejected.id),
        ChatMessage(Role.TOOL, receipt, toolCallId = skipped.id)
    )

    private fun project(history: List<ChatMessage>, protocolRepair: Boolean = false): List<ChatMessage> =
        TaskSourceContext("recovery_feedback").apply {
            begin(rejected, "PRE_ACTION_OBSERVATION_CHANGED", protocolRepair)
        }.project("Inspect the visible page.", emptyList(), history, "Review original evidence.")

    @Test fun withheldMultiCallRoundRetainsItsSuffixAsSourceDataWithoutOrphanToolResults() {
        val request = project(initial)
        assertFalse(request.any { it.role == Role.TOOL || !it.toolCalls.isNullOrEmpty() })
        val source = request.filter { it.role == Role.USER }.mapNotNull {
            runCatching { Json.parseToJsonElement(it.content).jsonObject["settled_tool_result"]?.jsonObject }.getOrNull()
        }.single()
        assertEquals(skipped.id, source.getValue("call_id").jsonPrimitive.content)
        assertEquals(receipt, source.getValue("content").jsonPrimitive.content)
        assertEquals(initial.last().role, Role.TOOL)
    }

    @Test fun laterCompleteRoundsAndTheirToolResultsKeepTheirNativeProtocol() {
        val later = ToolCall("read", "workspace", """{"op":"read","path":"notes.md"}""")
        val tail = listOf(
            ChatMessage(Role.USER, "Preserve this user clarification."),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(later)),
            ChatMessage(Role.TOOL, "record contents", toolCallId = later.id)
        )
        assertEquals(tail, project(initial + tail).takeLast(tail.size))
    }

    @Test fun sourceReviewKeepsExactEarlierNativeReceiptsWithoutReplayingProposalsOrOldCompletions() {
        val read = ToolCall("native-read", "android_read", """{"op":"device"}""")
        val old = ToolCall("old-answer", "done", "{}")
        val raw = """[ANDROID_DEVICE_RESULT]
{"captured_at_ms":42,"screen_effective_width_px":1080,"screen_mode_width_px":1264}"""
        val sourceHistory = listOf(
            ChatMessage(Role.ASSISTANT, "Unsupported interpretation", toolCalls = listOf(read)),
            ChatMessage(Role.TOOL, raw, toolCallId = read.id),
            ChatMessage(Role.ASSISTANT, "Old proposal", toolCalls = listOf(old)),
            ChatMessage(Role.TOOL, "Old public answer is not source evidence", toolCallId = old.id)
        ) + initial
        val review = project(sourceHistory)
        val retained = review.filter { it.role == Role.USER }.mapNotNull {
            runCatching { Json.parseToJsonElement(it.content).jsonObject["settled_tool_result"]?.jsonObject }.getOrNull()
        }
        val original = retained.single { it["call_id"]?.jsonPrimitive?.content == read.id }
        assertEquals("android_read", original.getValue("tool").jsonPrimitive.content)
        assertEquals("device", original.getValue("arguments").jsonObject.getValue("op").jsonPrimitive.content)
        assertEquals(raw, original.getValue("content").jsonPrimitive.content)
        assertFalse(review.any { it.content.contains("Unsupported interpretation") || it.content.contains("Old public answer") })
        assertFalse(review.any { it.role == Role.TOOL || !it.toolCalls.isNullOrEmpty() })
        assertEquals(sourceHistory.first().content, "Unsupported interpretation")
    }

    @Test fun focusedRecoveryRetainsSettledSelectionArgumentsAndStructuredFeedback() {
        val filter = ToolCall("filter", "screen_act", """{"actions":[{"op":"type_text","text":"100","clear":true},{"op":"press_key","key":"ENTER"}]}""")
        val history = listOf(ChatMessage(Role.ASSISTANT, "This proves ranking", toolCalls = listOf(filter)),
            ChatMessage(Role.TOOL, "Input delivered; outcome unknown", toolCallId = filter.id)) + initial
        val context = TaskSourceContext("recovery_feedback").apply { begin(rejected, """{"code":"STALE"}""") }
        val messages = context.project("Original goal", listOf("Current correction"), history, "Review",
            sourceIndex = """{"sources":[{"event_ref":"source"}]}""")
        val body = messages.mapNotNull { runCatching { Json.parseToJsonElement(it.content).jsonObject }.getOrNull() }
        val settled = body.mapNotNull { it["settled_tool_result"]?.jsonObject }.single { it["call_id"]?.jsonPrimitive?.content == "filter" }
        assertEquals(Json.parseToJsonElement(filter.arguments), settled["arguments"])
        assertTrue(body.single { "original_source_index" in it }["original_source_index"] is kotlinx.serialization.json.JsonObject)
        assertEquals("STALE", body.single { "recovery_feedback" in it }.getValue("recovery_feedback").jsonObject.getValue("code").jsonPrimitive.content)
        assertFalse(messages.any { it.content.contains("This proves ranking") })
    }

    @Test fun protocolRepairKeepsTheWholeDeclaredRoundAndCompactedBoundaryDoesNotReplayOldHistory() {
        assertEquals(initial, project(initial, protocolRepair = true).take(initial.size))
        val compacted = project(initial.take(2))
        assertFalse(compacted.any { it.role == Role.ASSISTANT || it.role == Role.TOOL })
    }
}
