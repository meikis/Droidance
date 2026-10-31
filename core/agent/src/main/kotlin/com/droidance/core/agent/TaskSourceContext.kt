// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.tools.ToolCatalog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/** A request projection for the same Agent. The durable execution history remains intact. */
class TaskSourceContext(private val feedbackKey: String) {
    private var candidate: ToolCall? = null
    private var feedback: String = ""
    val active: Boolean get() = candidate != null
    val candidateToolName: String? get() = candidate?.name
    var protocolRepair: Boolean = false
        private set

    fun begin(call: ToolCall, result: String, protocolRepair: Boolean = false) {
        candidate = call
        feedback = result
        this.protocolRepair = protocolRepair
    }

    fun reset() { candidate = null; feedback = ""; protocolRepair = false }

    /** A rejected format is an incident, not a mandatory future operation. */
    fun resolvedBy(result: com.droidance.core.agent.event.ToolExecutionResult): Boolean =
        protocolRepair && active && result.executionStatus == com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED

    fun project(
        originalRequest: String,
        userSteers: List<String>,
        history: List<ChatMessage>,
        instruction: String,
        priorConversation: String = "",
        sourceIndex: String = "",
        taskRequirements: String = ""
    ): List<ChatMessage> {
        val call = checkNotNull(candidate)
        // Keep execution history for field repair, but do not replay every failed long answer.
        // The exact latest native completion below is the only proposal being repaired.
        val repairedHistory = if (protocolRepair && call.name in ToolCatalog.finalizationToolNames) {
            val obsoleteCalls = history.flatMap { it.toolCalls.orEmpty() }
                .filter { it.name in ToolCatalog.finalizationToolNames }.map { it.id }.toSet()
            history.mapNotNull { message ->
                if (message.role == Role.TOOL && message.toolCallId in obsoleteCalls) null
                else if (!message.toolCalls.isNullOrEmpty()) {
                    val retained = message.toolCalls.orEmpty().filterNot { it.id in obsoleteCalls }
                    if (retained.isEmpty() && message.content.isBlank()) null
                    else message.copy(toolCalls = retained.takeIf { it.isNotEmpty() })
                } else message
            }
        } else history
        if (protocolRepair) return repairedHistory + repairFeedback(instruction)
        val boundary = history.indexOfFirst { it.role == Role.TOOL && it.toolCallId == call.id }
        // Source review must keep settled original text as well as original pixels.
        // Declarations identify receipts; prior assistant proposals are not source evidence.
        val preceding = if (boundary < 0) emptyList() else history.take(boundary)
        val declarations = preceding.flatMap { it.toolCalls.orEmpty() }.associateBy { it.id }
        val originals = preceding.filter { message ->
            message.role == Role.TOOL && declarations[message.toolCallId]?.name?.let {
                it !in ToolCatalog.finalizationToolNames
            } == true
        }.map { message ->
            ChatMessage(Role.USER, buildJsonObject {
                put("settled_tool_result", buildJsonObject {
                    put("call_id", message.toolCallId)
                    put("tool", declarations.getValue(checkNotNull(message.toolCallId)).name)
                    put("arguments", nativeJson(declarations.getValue(checkNotNull(message.toolCallId)).arguments))
                    put("content", message.content)
                })
            }.toString())
        }
        // If compaction evicted the boundary, restart this source review from the request
        // and original pixels. Never silently fall back to pre-review assertions or summaries.
        val subsequent = if (boundary < 0) emptyList() else history.drop(boundary + 1)
            .filter { it.role != Role.SYSTEM && !it.historicalImage }
            .let { tail ->
                val retainedCallIds = mutableSetOf<String>()
                tail.map { message ->
                    if (message.role == Role.ASSISTANT) {
                        retainedCallIds += message.toolCalls.orEmpty().map { it.id }
                    }
                    // A multi-call round may have settled suffix results after the rejected
                    // call. Its proposal was withheld above, so preserve those receipts as
                    // source data rather than emitting tool outputs without declarations.
                    if (message.role == Role.TOOL && message.toolCallId !in retainedCallIds) {
                        message.copy(role = Role.USER, toolCallId = null, content = buildJsonObject {
                            put("settled_tool_result", buildJsonObject {
                                put("call_id", message.toolCallId)
                                declarations[message.toolCallId]?.let { declaration ->
                                    put("tool", declaration.name)
                                    put("arguments", nativeJson(declaration.arguments))
                                }
                                put("content", message.content)
                            })
                        }.toString())
                    } else message
                }
            }
        return listOf(ChatMessage(Role.SYSTEM, instruction), ChatMessage(Role.USER, originalRequest)) +
            listOfNotNull(priorConversation.takeIf(String::isNotBlank)?.let {
                ChatMessage(Role.USER, buildJsonObject { put("prior_conversation_context", it) }.toString())
            }) +
            userSteers.map { ChatMessage(Role.USER, it) } +
            listOfNotNull(taskRequirements.takeIf(String::isNotBlank)?.let {
                ChatMessage(Role.USER, buildJsonObject { put("model_derived_task_requirements", it) }.toString())
            }) +
            listOfNotNull(sourceIndex.takeIf(String::isNotBlank)?.let {
                ChatMessage(Role.USER, buildJsonObject { put("original_source_index", nativeJson(it)) }.toString())
            }) +
            originals +
            ChatMessage(Role.USER, buildJsonObject {
                put(feedbackKey, nativeJson(feedback))
            }.toString()) + subsequent + history.filter { it.historicalImage }
    }

    /** The exact rejected call and its receipt, appended after unchanged history for a field repair. */
    fun repairFeedback(instruction: String): ChatMessage {
        val call = checkNotNull(candidate)
        return ChatMessage(Role.USER, buildJsonObject {
            put("instruction", instruction)
            put("rejected_call_id", call.id)
            put("tool", call.name)
            put("arguments", nativeJson(call.arguments))
            put(feedbackKey, nativeJson(feedback))
        }.toString())
    }

    private fun nativeJson(text: String) = runCatching { Json.parseToJsonElement(text) }
        .getOrElse { kotlinx.serialization.json.JsonPrimitive(text) }
}
