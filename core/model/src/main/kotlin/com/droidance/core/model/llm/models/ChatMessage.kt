// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.models

import kotlinx.serialization.Serializable

/**
 * 统一聊天消息模型
 * 用于在 Agent 和 LLM Provider 之间传递消息
 */
@Serializable
data class ChatMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall>? = null,    // LLM 返回的工具调用
    val toolCallId: String? = null,           // 工具执行结果对应的 ID
    val imageBase64: String? = null,          // 可选的图片内容（截图）
    val historicalImage: Boolean = false, // Historical evidence, never the current computer frame
    val imageBase64List: List<String> = emptyList(), // 可选的多图内容
    val reasoningContent: String? = null,     // Provider 推理内容，多轮协议需要时回传
    val timestamp: Long = System.currentTimeMillis(),
    /** Request-local cache eligibility; never persisted as conversation content. */
    @kotlinx.serialization.Transient val cacheablePrefix: Boolean = false
)

/**
 * 消息角色
 */
@Serializable
enum class Role {
    SYSTEM,     // 系统提示词（SOUL.md 内容）
    USER,       // 用户消息
    ASSISTANT,  // LLM 回复
    TOOL        // 工具调用结果
}

/**
 * 工具调用定义
 */
@Serializable
data class ToolCall(
    val id: String,            // 工具调用 ID
    val name: String,          // 工具名称，如 "screenshot", "click"
    val arguments: String      // JSON 格式的参数
)

/**
 * 工具定义（发送给 LLM 的工具清单）
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter>
)

/**
 * 工具参数定义
 */
@Serializable
data class ToolParameter(
    val type: String,           // "string", "integer", "boolean" 等
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
    val items: ToolParameter? = null,
    val properties: Map<String, ToolParameter>? = null,
    val additionalProperties: Boolean? = null,
    val additionalPropertySchema: ToolParameter? = null,
    /** Optional metadata validated by its consumer after dispatch, independently of the main effect. */
    val deferredValidation: Boolean = false,
    /** Required when any named sibling selector has one of these protocol values. */
    val requiredWhen: Map<String, List<String>> = emptyMap(),
    /** Present only for the listed values of every named sibling selector. */
    val allowedWhen: Map<String, List<String>> = emptyMap(),
    /** JSON Schema string length in Unicode code points, not UTF-16 code units. */
    val minLength: Int? = null,
    /** JSON Schema minimum number of array elements. */
    val minItems: Int? = null
)
