// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.automation.AutomationSchedule
import com.droidance.config.RuleConfigManager
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@kotlinx.serialization.Serializable
data class AutomationDraft(
    val title: String,
    val instruction: String,
    val schedule: AutomationSchedule?,
    val originalRequest: String = "",
    val referenceTimeMs: Long = 0L,
    val clarification: String = "",
    val titleWasGenerated: Boolean = true,
    val editingAutomationId: String? = null
)

@Singleton
class AutomationDraftInterpreter @Inject constructor(
    private val modelClient: ModelClient,
    private val ruleConfigManager: RuleConfigManager
) {
    suspend fun interpret(
        instruction: String,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        previousDraft: AutomationDraft? = null,
        answer: String? = null
    ): AutomationDraft? {
        if (instruction.isBlank()) return null
        val currentDateTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(nowMs).atZone(zoneId)
        )
        val systemPrompt = ruleConfigManager.readMarkdownSection(PROMPT_FILE, PROMPT_SECTION)
            .replace("{{current_datetime}}", currentDateTime)
            .replace("{{current_timezone}}", zoneId.id)
            .trim()
        val content = run {
            val request = if (previousDraft == null) instruction.trim() else kotlinx.serialization.json.buildJsonObject {
                put("current_draft", Json.encodeToJsonElement(AutomationDraft.serializer(), previousDraft))
                put("user_reply", kotlinx.serialization.json.JsonPrimitive(answer.orEmpty()))
            }.toString()
            val messages = listOf(ChatMessage(role = Role.USER, content = request))
            val plan = modelClient.planStreamChat(messages = messages, hasImage = false)
            val output = StringBuilder()
            var returnedToolCall = false
            modelClient.streamChat(
                messages = messages,
                tools = emptyList(),
                systemPrompt = systemPrompt,
                hasImage = false,
                requestPlan = plan
            ).collect { event ->
                when (event) {
                    is LLMStreamEvent.ReasoningConfiguration, is LLMStreamEvent.RequestConfiguration -> Unit
                    is LLMStreamEvent.Activity -> Unit
                    is LLMStreamEvent.ResponseMetadata -> Unit
                    is LLMStreamEvent.Content -> output.append(event.text)
                    is LLMStreamEvent.ToolCallChunk -> returnedToolCall = true
                    is LLMStreamEvent.ReasoningChunk -> Unit
                }
            }
            output.toString().takeUnless { returnedToolCall }
        } ?: throw InvalidAutomationDraftResponse()
        return parse(content, instruction.trim(), nowMs) ?: throw InvalidAutomationDraftResponse()
    }

    companion object {
        private const val PROMPT_FILE = "automation_prompts.md"
        private const val PROMPT_SECTION = "automation_draft_generation"

        fun parse(content: String, instruction: String = "", referenceTimeMs: Long = System.currentTimeMillis()): AutomationDraft? {
            val jsonText = extractJsonObject(content) ?: return null
            return runCatching {
                val value = Json.parseToJsonElement(jsonText).jsonObject
                val title = value["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                    ?: return null
                val work = value["instruction"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val clarification = value["clarification"]?.jsonPrimitive?.contentOrNull.orEmpty()
                require(work.isNotBlank() || clarification.isNotBlank())
                val cadence = value["cadence"]?.jsonPrimitive?.contentOrNull
                val schedule = if (cadence == null) {
                    require(clarification.isNotBlank())
                    null
                } else com.droidance.automation.AutomationScheduleInput.parse(value, referenceTimeMs)
                AutomationDraft(title = title.take(MAX_TITLE_CHARS), instruction = work,
                    schedule = schedule, originalRequest = instruction, referenceTimeMs = referenceTimeMs,
                    clarification = clarification)
            }.getOrNull()
        }

        private fun extractJsonObject(content: String): String? {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return content.substring(start, end + 1)
        }

        private const val MAX_TITLE_CHARS = 80
    }
}

internal class InvalidAutomationDraftResponse : IllegalStateException("INVALID_DRAFT_RESPONSE")
