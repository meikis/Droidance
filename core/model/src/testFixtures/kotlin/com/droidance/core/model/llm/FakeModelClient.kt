// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.util.concurrent.atomic.AtomicLong

class FakeModelClient(
    var cannedStreamEvents: List<LLMStreamEvent> = emptyList(),
    var cannedContextWindowTokens: Int = 128_000,
    var visionAvailable: Boolean = true,
    var reasoningEfforts: Set<ReasoningEffort> = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
) : ModelClient {
    private val switchEvents = kotlinx.coroutines.flow.MutableSharedFlow<ModelSwitchEvent>(extraBufferCapacity = 16)
    override val modelSwitches: kotlinx.coroutines.flow.SharedFlow<ModelSwitchEvent> = switchEvents
    var turnDepth: Int = 0
    val cancellationGen = AtomicLong(0L)
    var activeRequestsCancelled = false
    var contextCheckpointResetCount = 0
    var androidActivationResetCount = 0

    val roundResponses: MutableList<List<LLMStreamEvent>> = mutableListOf()
    val recordedRequests: MutableList<List<ChatMessage>> = mutableListOf()
    val recordedTools: MutableList<List<ToolDefinition>> = mutableListOf()
    val recordedMaxOutputTokens: MutableList<Int?> = mutableListOf()
    val recordedReasoningEfforts: MutableList<ReasoningEffort> = mutableListOf()
    var nextStreamFailure: Throwable? = null
    var sessionInitialPlan: ModelStreamPlan? = null
        private set

    val dummyConfig = ProviderConfig(
        id = "fake_provider",
        name = "Fake Provider",
        apiType = ApiType.OPENAI_COMPATIBLE,
        transportKind = com.droidance.core.model.llm.session.ModelTransport.GENERIC_CHAT_COMPLETIONS,
        baseUrl = "https://fake.local",
        apiKey = "fake_key",
        models = listOf("fake_model"),
        defaultModel = "fake_model"
    )

    override fun createSession(initialPlan: ModelStreamPlan?): ModelSession {
        sessionInitialPlan = initialPlan
        beginTurn()
        return object : ModelSession {
            override fun planStreamChat(messages: List<ChatMessage>, hasImage: Boolean): ModelStreamPlan =
                this@FakeModelClient.planStreamChat(messages, hasImage)

            override suspend fun streamChat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>,
                systemPrompt: String?,
                hasImage: Boolean,
                enableComputerTool: Boolean,
                computerFrameSize: Pair<Int, Int>?,
                maxOutputTokens: Int?,
                requestTimeoutMs: Long?,
                requestPlan: ModelStreamPlan?,
                isolatedContext: Boolean,
                reasoningEffort: ReasoningEffort
            ): Flow<LLMStreamEvent> = this@FakeModelClient.streamChat(
                messages, tools, systemPrompt, hasImage, enableComputerTool,
                computerFrameSize, maxOutputTokens, requestTimeoutMs, null, requestPlan, isolatedContext, reasoningEffort
            )

            override fun cancelActiveRequests() = this@FakeModelClient.cancelActiveRequests()
            override fun resetResponsesSessionAfterCheckpoint() = this@FakeModelClient.resetResponsesSessionAfterContextCheckpoint()
            override fun resetResponsesSessionForAndroidActivation() = this@FakeModelClient.resetResponsesSessionForAndroidActivation()
            override fun currentCancellationGeneration(): Long = this@FakeModelClient.currentCancellationGeneration()
            override fun wasCancellationRequestedSince(generation: Long): Boolean = this@FakeModelClient.wasCancellationRequestedSince(generation)
            override fun reportImageSendRejected(reason: String) = this@FakeModelClient.reportImageSendRejected(reason)
            override fun canSendVisionMessages(): Boolean = this@FakeModelClient.canSendVisionMessages()
            override fun currentContextWindowTokens(): Int = this@FakeModelClient.currentContextWindowTokens()
            override fun close() = this@FakeModelClient.endTurn()
        }
    }

    override fun planStreamChat(
        messages: List<ChatMessage>,
        hasImage: Boolean
    ): ModelStreamPlan {
        sessionInitialPlan?.let { return it.copy(imageIncluded = hasImage && visionAvailable) }
        return ModelStreamPlan.fromProvider(
            config = dummyConfig,
            initialModel = "fake_model",
            imageIncluded = hasImage && visionAvailable
        )
    }

    override suspend fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        hasImage: Boolean,
        enableComputerTool: Boolean,
        computerFrameSize: Pair<Int, Int>?,
        maxOutputTokens: Int?,
        requestTimeoutMs: Long?,
        requestCancellationGeneration: Long?,
        requestPlan: ModelStreamPlan?,
        isolatedContext: Boolean,
        reasoningEffort: ReasoningEffort
    ): Flow<LLMStreamEvent> {
        recordedRequests.add(messages.toList())
        recordedTools.add(tools.toList())
        recordedMaxOutputTokens.add(maxOutputTokens)
        recordedReasoningEfforts.add(reasoningEffort)
        nextStreamFailure?.let { failure ->
            nextStreamFailure = null
            throw failure
        }
        val events = if (roundResponses.isNotEmpty()) {
            roundResponses.removeAt(0)
        } else {
            cannedStreamEvents
        }
        return events.asFlow()
    }

    override fun currentContextWindowTokens(): Int = cannedContextWindowTokens
    override fun resetResponsesSessionAfterContextCheckpoint() { contextCheckpointResetCount++ }
    override fun resetResponsesSessionForAndroidActivation() { androidActivationResetCount++ }
    override fun currentCancellationGeneration(): Long = cancellationGen.get()
    override fun wasCancellationRequestedSince(generation: Long): Boolean = cancellationGen.get() != generation
    override fun cancelActiveRequests() {
        cancellationGen.incrementAndGet()
        activeRequestsCancelled = true
    }
    override fun reportImageSendRejected(reason: String) {}
    override fun canSendVisionMessages(): Boolean = visionAvailable
    override fun explicitReasoningEfforts(): Set<ReasoningEffort> = reasoningEfforts
    override fun beginTurn() { turnDepth++ }
    override fun endTurn() {
        turnDepth = (turnDepth - 1).coerceAtLeast(0)
        if (turnDepth == 0) sessionInitialPlan = null
    }
}
