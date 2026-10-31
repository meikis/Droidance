// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.models

/**
 * 流式输出事件
 */
sealed class LLMStreamEvent {
    /** Local transport facts, not provider activity or proof of the model's internal effort. */
    data class ReasoningConfiguration(
        val requested: ReasoningEffort,
        val sent: ReasoningEffort,
        val fallbackReason: String?,
        val supportedEfforts: List<ReasoningEffort>
    ) : LLMStreamEvent()

    /** Local facts about the request actually sent, such as continuation and extra fields. */
    data class RequestConfiguration(val facts: Map<String, String>) : LLMStreamEvent()

    /** Wire-reported metadata; absent usage/model values remain unknown. */
    data class ResponseMetadata(
        val returnedModel: String?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cachedInputTokens: Int? = null,
        val reasoningTokens: Int? = null,
        val finishReason: String? = null,
        val cacheWriteTokens: Int? = null
    ) : LLMStreamEvent()

    /** 文本内容分块 */
    data class Content(val text: String) : LLMStreamEvent()

    /** 工具调用分块 */
    data class ToolCallChunk(
        val index: Int,          // 工具列表中的索引
        val id: String? = null,  // 工具调用 ID（只有第一次块有）
        val name: String? = null,// 工具名称（只有第一次块有）
        val arguments: String    // 参数的增量部分
    ) : LLMStreamEvent()

    /** Provider 推理内容分块，仅用于协议回传，不显示给用户。 */
    data class ReasoningChunk(val text: String) : LLMStreamEvent()

    /** Transport activity without retaining or publishing provider reasoning text. */
    data class Activity(val phase: Phase, val textChars: Int = 0) : LLMStreamEvent() {
        enum class Phase { RESPONSE_STARTED, REASONING, REASONING_FINISHED }
    }
}
