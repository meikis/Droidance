// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.config.markdownSection
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskSourceContextTest {
    @Test fun protocolRepairPreservesExactHistoryButAllowsAValidAlternative() {
        val context = TaskSourceContext("recovery_feedback")
        val failed = ToolCall("failed", "workspace", """{"op":"edit","path":"work.md","new_text":"retained addition"}""")
        val history = listOf(ChatMessage(Role.USER, "original scope"),
            ChatMessage(Role.ASSISTANT, "supported entries", toolCalls = listOf(failed)),
            ChatMessage(Role.TOOL, "missing text and expected_ref", toolCallId = failed.id))
        context.begin(failed, """{"missing_parameters":["text","expected_ref"]}""", protocolRepair = true)
        val result = context.project("original scope", emptyList(), history, "repair only fields")
        assertEquals(history, result.take(history.size))
        val payload = Json.parseToJsonElement(result.last().content).jsonObject
        assertEquals(Json.parseToJsonElement(failed.arguments), payload.getValue("arguments"))
        assertTrue(payload.getValue("recovery_feedback") is JsonObject)
        assertFalse(context.resolvedBy(com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("INVALID", true)))
        assertTrue(context.resolvedBy(com.droidance.core.agent.tools.ToolProtocolResult.success("Original source read")))
        context.reset()
        assertFalse(context.protocolRepair)
    }

    @Test fun completionRepairRetainsWorkButDoesNotRepeatObsoleteLongCandidates() {
        val context = TaskSourceContext("recovery_feedback")
        val prior = ToolCall("prior", "done", "obsolete answer".repeat(1000))
        val rejected = ToolCall("latest", "done", """{"results":[{"text":"latest supported answer"}]}""")
        val read = ToolCall("read", "session_read", "{}")
        val history = listOf(ChatMessage(Role.USER, "original scope"),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(prior)),
            ChatMessage(Role.TOOL, "old schema error", toolCallId = prior.id),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(read)),
            ChatMessage(Role.TOOL, "original source", toolCallId = read.id),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(rejected)),
            ChatMessage(Role.TOOL, "latest schema error", toolCallId = rejected.id))
        context.begin(rejected, "repair the missing field", protocolRepair = true)
        val projected = context.project("original scope", emptyList(), history, "repair")
        assertFalse(projected.any { it.content.contains("old schema error") })
        assertEquals(read, projected.single { !it.toolCalls.isNullOrEmpty() }.toolCalls!!.single())
        assertEquals(read.id, projected.single { it.role == Role.TOOL }.toolCallId)
        assertEquals(Json.parseToJsonElement(rejected.arguments),
            Json.parseToJsonElement(projected.last().content).jsonObject.getValue("arguments"))
        assertTrue(projected.sumOf { it.content.length } < 1000)
    }

    @Test fun recoveryWithholdsTheFailedPlanButRetainsNewEvidenceAndToolPairing() {
        val context = TaskSourceContext("recovery_feedback")
        val failed = ToolCall("blocked", "screen_act", "{\"actions\":[{\"op\":\"scroll\"}]}")
        val read = ToolCall("read", "session_read", "{}")
        context.begin(failed, "stationary view; scroll was not executed")
        val history = listOf(
            ChatMessage(Role.ASSISTANT, "old plan: scroll until a button appears"),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(failed)),
            ChatMessage(Role.TOOL, "blocked", toolCallId = failed.id),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(read)),
            ChatMessage(Role.TOOL, "original source", toolCallId = read.id)
        )
        val projected = context.project("original goal", listOf("explicit correction"), history, "replan",
            sourceIndex = "original receipts", taskRequirements = "retained requirements")
        assertFalse(projected.any { it.content.contains("old plan") })
        assertTrue(projected.any { it.content.contains("recovery_feedback") })
        assertTrue(projected.any { it.content.contains("retained requirements") })
        assertEquals(read.id, projected.single { it.role == Role.TOOL }.toolCallId)
        assertEquals(read, projected.single { it.role == Role.ASSISTANT }.toolCalls!!.single())
        context.reset()
        assertFalse(context.active)
    }

    private val candidate = ToolCall("candidate", "done", """{"status":"done","results":[{"text":"candidate finding","evidence_refs":["source"]}],"requirement_checks":[{"requirement_id":"self certified"}],"unresolved_requirements":[]}""")

    @Test fun isolatesEarlierAssertionsButKeepsOriginalImagesSteersAndReviewToolPairs() {
        val context = TaskSourceContext("review_feedback")
        context.begin(candidate, "review required")
        val read = ToolCall("read", "session_read", "{}")
        val image = ChatMessage(Role.USER, "source", imageBase64 = "pixels", historicalImage = true)
        val history = listOf(
            ChatMessage(Role.SYSTEM, "old memory"),
            ChatMessage(Role.USER, "old compaction"),
            ChatMessage(Role.ASSISTANT, "old false assertion"),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(candidate)),
            ChatMessage(Role.TOOL, "review required", toolCallId = candidate.id),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(read)),
            ChatMessage(Role.TOOL, "source read receipt", toolCallId = read.id), image
        )
        val projected = context.project("original request", listOf("user correction"), history, "review instruction")
        assertEquals(listOf("review instruction", "original request", "user correction"), projected.take(3).map { it.content })
        val text = projected.joinToString { it.content }
        assertFalse(text.contains("old memory")); assertFalse(text.contains("old compaction"))
        assertFalse(text.contains("old false assertion")); assertFalse(text.contains("self certified"))
        assertFalse(text.contains("candidate finding"))
        assertTrue(text.contains("review required"))
        assertEquals(read, projected.single { it.role == Role.ASSISTANT }.toolCalls!!.single())
        assertEquals(read.id, projected.single { it.role == Role.TOOL }.toolCallId)
        assertEquals(listOf(image), projected.filter { it.historicalImage })
    }

    @Test fun evictedBoundaryCannotRestoreBiasedHistoryAndResetReturnsToExecution() {
        val context = TaskSourceContext("review_feedback")
        context.begin(candidate, "review required")
        context.begin(ToolCall("later", "done", "{}"), "new source required")
        val projected = context.project("request", emptyList(),
            listOf(ChatMessage(Role.USER, "untrusted compacted assertion")), "review")
        assertTrue(context.active)
        assertFalse(projected.any { "candidate finding" in it.content })
        assertFalse(projected.any { "untrusted compacted assertion" in it.content })
        context.reset()
        assertFalse(context.active)
    }

    @Test fun aNewCandidateReplacesOldReviewFeedbackAndAssertions() {
        val context = TaskSourceContext("review_feedback")
        context.begin(candidate, "old feedback")
        val next = ToolCall("next", "done", "{}")
        context.begin(next, "current feedback")
        val projected = context.project("original", emptyList(), listOf(
            ChatMessage(Role.TOOL, "old feedback", toolCallId = candidate.id),
            ChatMessage(Role.ASSISTANT, "obsolete assertion"),
            ChatMessage(Role.TOOL, "current feedback", toolCallId = next.id)
        ), "instruction")
        assertFalse(projected.any { "old feedback" in it.content || "obsolete assertion" in it.content })
        assertTrue(projected.any { "current feedback" in it.content })
    }

    @Test fun preservesBoundedPriorConversationForAReferentialUserRequest() {
        val context = TaskSourceContext("review_feedback")
        context.begin(candidate, "review required")
        val projected = context.project("Compare those two", emptyList(), emptyList(), "review",
            priorConversation = "user: compare the north route and south route")
        assertTrue(projected.any { "prior_conversation_context" in it.content && "north route" in it.content })
    }

    @Test fun uncitedOriginalSourcesRemainDiscoverableAfterAssertionsAreWithheld() {
        val context = TaskSourceContext("review_feedback")
        context.begin(candidate, "review required")
        val projected = context.project("original task", emptyList(),
            listOf(ChatMessage(Role.ASSISTANT, "unsupported claim")), "review",
            sourceIndex = "{\"sources\":[{\"observation_ref\":\"uncited-original\"}]}",
            taskRequirements = "{\"requirements\":[\"original selection constraint\"]}")
        assertTrue(projected.any { "original_source_index" in it.content && "uncited-original" in it.content })
        assertFalse(projected.any { "unsupported claim" in it.content })
        assertTrue(projected.any { "model_derived_task_requirements" in it.content && "original selection constraint" in it.content })
        assertFalse(projected.any { it.historicalImage }) // An index is not pixel delivery.
    }

    @Test fun focusedRecoveryProjectionKeepsCumulativeFailureAndRepetitionFactsVisible() {
        val policy = Json.decodeFromString<ObservationRecoveryPolicy>(markdownSection(
            projectFile("app/src/main/assets/rules/observation_recovery.md").readText(), "policy"
        )).also { it.validate() }
        val recovery = ObservationRecovery(policy)
        val failed = ToolCall("failed-call", "screen_act", """{"actions":[{"op":"click","x":420,"y":360}]}""")
        val failure = ToolExecutionResult(
            outputText = """{"details":{"attempted":1}}""",
            executionStatus = ToolExecutionStatus.NOT_EXECUTED,
            evidenceStatus = ToolEvidenceStatus.UNKNOWN,
            diagnosticCode = "STALE_OBSERVATION"
        )
        recovery.observe("session", "full", "observation-1", 0L, "same-frame")
        repeat(policy.attempts) { recovery.settled(failed.name, failed.arguments, failure) }

        val receipt = buildJsonObject {
            put("tool", JsonPrimitive(failed.name))
            put("arguments", JsonPrimitive(failed.arguments))
            put("receipt", JsonPrimitive(failure.outputText))
            put("repetition_facts", recovery.recoveryFacts(failed.name, failed.arguments))
            put("failed_methods", recovery.failedMethodHistory())
        }.toString()
        val context = TaskSourceContext("recovery_feedback")
        context.begin(failed, receipt)
        val projected = context.project(
            originalRequest = "Inspect the requested control and continue the task.",
            userSteers = emptyList(),
            history = listOf(
                ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(failed)),
                ChatMessage(Role.TOOL, failure.outputText, toolCallId = failed.id)
            ),
            instruction = "Review this failed method and choose a grounded alternative."
        )

        val wrapper = projected.single { it.role == Role.USER && it.content.contains("recovery_feedback") }
        val visibleReceipt = Json.parseToJsonElement(wrapper.content).jsonObject
            .getValue("recovery_feedback").jsonObject
        val repetitionFacts = visibleReceipt.getValue("repetition_facts").jsonObject
        val failedMethods = visibleReceipt.getValue("failed_methods").jsonArray
        assertEquals(policy.attempts, repetitionFacts.getValue("repeated_failed_attempts").jsonPrimitive.int)
        assertEquals("STALE_OBSERVATION", repetitionFacts.getValue("diagnostic_code").jsonPrimitive.content)
        assertEquals(policy.attempts, failedMethods.single().jsonObject.getValue("failed_attempts").jsonPrimitive.int)
        assertEquals("STALE_OBSERVATION", failedMethods.single().jsonObject.getValue("diagnostic_code").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(failed.arguments), failedMethods.single().jsonObject.getValue("arguments"))
    }

    private fun projectFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path")
    }
}
