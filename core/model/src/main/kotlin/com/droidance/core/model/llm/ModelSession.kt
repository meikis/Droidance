// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Encapsulates an active model interaction session bound to a task turn.
 * Manages model session lifecycle, stateful context resets, and request cancellation.
 */
interface ModelSession : AutoCloseable {
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
        requestPlan: ModelStreamPlan? = null,
        isolatedContext: Boolean = false,
        reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT
    ): Flow<LLMStreamEvent>

    fun cancelActiveRequests()
    fun resetResponsesSessionAfterCheckpoint()
    fun resetResponsesSessionForAndroidActivation()
    fun currentCancellationGeneration(): Long
    fun wasCancellationRequestedSince(generation: Long): Boolean
    fun reportImageSendRejected(reason: String)
    fun canSendVisionMessages(): Boolean
    fun currentContextWindowTokens(): Int

    override fun close()
}
