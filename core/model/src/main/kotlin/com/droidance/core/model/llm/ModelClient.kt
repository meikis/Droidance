// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * 面向 Agent 决策循环与上层业务的稳定模型传输接口。
 * 隐藏底层 HTTP 连接池与 Provider 专有网络传输细节。
 */
interface ModelClient {
    val modelSwitches: kotlinx.coroutines.flow.SharedFlow<ModelSwitchEvent>

    fun createSession(initialPlan: ModelStreamPlan? = null): ModelSession

    fun planStreamChat(
        messages: List<ChatMessage>,
        hasImage: Boolean
    ): ModelStreamPlan

    suspend fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        systemPrompt: String? = null,
        hasImage: Boolean = false,
        enableComputerTool: Boolean = false,
        computerFrameSize: Pair<Int, Int>? = null,
        maxOutputTokens: Int? = null,
        requestTimeoutMs: Long? = null,
        requestCancellationGeneration: Long? = null,
        requestPlan: ModelStreamPlan? = null,
        isolatedContext: Boolean = false,
        reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT
    ): Flow<LLMStreamEvent>

    fun currentContextWindowTokens(): Int
    fun resetResponsesSessionAfterContextCheckpoint()
    fun resetResponsesSessionForAndroidActivation()
    fun currentCancellationGeneration(): Long
    fun wasCancellationRequestedSince(generation: Long): Boolean
    fun cancelActiveRequests()
    fun reportImageSendRejected(reason: String)
    fun canSendVisionMessages(): Boolean
    /** Explicit reasoning efforts the current provider model accepts; empty when only its defaults apply. */
    fun explicitReasoningEfforts(): Set<com.droidance.core.model.llm.models.ReasoningEffort>
    fun beginTurn()
    fun endTurn()
}

data class ModelSwitchEvent(val fromModel: String, val toModel: String, val reason: String)

/** No usable Provider or model is configured; the user must set one up before any task can run. */
class ModelNotConfiguredException(message: String) : IllegalStateException(message)
