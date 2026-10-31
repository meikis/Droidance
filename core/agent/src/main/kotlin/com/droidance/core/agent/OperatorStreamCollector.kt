// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.providerHttpException
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.LLMStreamEvent
import kotlinx.coroutines.ensureActive
import com.droidance.core.model.llm.withModelRequestTimeout
import kotlin.coroutines.coroutineContext

/** Collects a single provider stream; the caller owns round lifecycle and persistence. */
class OperatorStreamCollector(
    private val modelClient: ModelClient,
    private val diagnostic: (String) -> Unit
) {
    suspend fun collect(
        requestPlan: ModelStreamPlan,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String,
        hasImage: Boolean,
        timeoutMs: Long,
        cancellationGeneration: Long,
        maxOutputTokens: Int,
        enableComputerTool: Boolean,
        computerFrameSize: Pair<Int, Int>?,
        isolatedContext: Boolean = false,
        startedAt: Long,
        onMetrics: (Map<String, String>) -> Unit,
        onContent: suspend (String) -> Unit,
        progress: OperatorStreamProgress? = null,
        diagnosticScope: com.droidance.core.model.llm.ModelDiagnosticScope? = null,
        reasoningEffort: com.droidance.core.model.llm.models.ReasoningEffort = requestPlan.reasoningEffort,
        deadlines: ModelStreamDeadlinePolicy = ModelStreamDeadlinePolicy(timeoutMs, timeoutMs, timeoutMs)
    ): OperatorStreamCapture {
        val streamProgress = progress ?: OperatorStreamProgress()
        var failure: Throwable? = null
        val effort = reasoningEffort
        try {
            withModelRequestTimeout(timeoutMs) {
                // Keep tool choice unchanged across decisions and summary forks. The Harness
                // validates completion/action requirements after the response is committed.
                kotlinx.coroutines.withContext(diagnosticScope ?: kotlin.coroutines.EmptyCoroutineContext) {
                    modelClient.streamChat(
                        messages = messages,
                        tools = tools,
                        systemPrompt = systemPrompt,
                        hasImage = hasImage,
                        requestTimeoutMs = timeoutMs,
                        requestCancellationGeneration = cancellationGeneration,
                        requestPlan = requestPlan,
                        maxOutputTokens = maxOutputTokens,
                        enableComputerTool = enableComputerTool,
                        computerFrameSize = computerFrameSize,
                        isolatedContext = isolatedContext,
                        reasoningEffort = effort
                    ).collectWithDeadlines(deadlines, {
                        streamProgress.firstContentAt > 0 || streamProgress.firstToolAt > 0
                    }) { event ->
                        if (event is LLMStreamEvent.ReasoningConfiguration) {
                            streamProgress.reasoningConfiguration = event
                            streamProgress.reasoningConfigurationAttempts++
                            return@collectWithDeadlines
                        }
                        if (event is LLMStreamEvent.RequestConfiguration) {
                            streamProgress.requestFacts = event.facts
                            return@collectWithDeadlines
                        }
                        if (event !is LLMStreamEvent.Activity && streamProgress.firstEventAt == 0L) {
                            streamProgress.firstEventAt = System.currentTimeMillis()
                            diagnostic(
                                "LLM(operator) first stream event: ${streamProgress.firstEventAt - startedAt}ms"
                            )
                        }
                        streamProgress.lastEventAt = System.currentTimeMillis()
                        coroutineContext.ensureActive()
                        when (event) {
                            is LLMStreamEvent.ReasoningConfiguration, is LLMStreamEvent.RequestConfiguration -> Unit
                            is LLMStreamEvent.Activity -> {
                                if (streamProgress.firstProviderEventAt == 0L)
                                    streamProgress.firstProviderEventAt = streamProgress.lastEventAt
                                when (event.phase) {
                                    LLMStreamEvent.Activity.Phase.RESPONSE_STARTED -> Unit
                                    LLMStreamEvent.Activity.Phase.REASONING -> {
                                        if (streamProgress.firstReasoningAt == 0L)
                                            streamProgress.firstReasoningAt = streamProgress.lastEventAt
                                        if (streamProgress.reasoningSegmentAt == 0L)
                                            streamProgress.reasoningSegmentAt = streamProgress.lastEventAt
                                        streamProgress.reasoningActivityChars += event.textChars
                                    }
                                    LLMStreamEvent.Activity.Phase.REASONING_FINISHED -> {
                                        if (streamProgress.reasoningSegmentAt > 0L) {
                                            streamProgress.reasoningDurationMs += streamProgress.lastEventAt - streamProgress.reasoningSegmentAt
                                            streamProgress.reasoningSegmentAt = 0L
                                        }
                                    }
                                }
                            }
                            is LLMStreamEvent.ResponseMetadata -> {
                                if (streamProgress.firstMetadataAt == 0L) streamProgress.firstMetadataAt = streamProgress.lastEventAt
                                event.cachedInputTokens?.let { streamProgress.cachedInputTokens = it }
                                event.cacheWriteTokens?.let { streamProgress.cacheWriteTokens = it }
                                event.returnedModel?.let { streamProgress.returnedModel = it }
                                event.inputTokens?.let { streamProgress.inputTokens = it }
                                event.outputTokens?.let { streamProgress.outputTokens = it }
                                event.reasoningTokens?.let { streamProgress.reasoningTokens = it }
                            }
                            is LLMStreamEvent.Content -> {
                                if (event.text.isNotEmpty() && streamProgress.firstContentAt == 0L) streamProgress.firstContentAt = streamProgress.lastEventAt
                                streamProgress.content += event.text
                                if (event.text.isNotEmpty()) onContent(event.text)
                            }
                            is LLMStreamEvent.ToolCallChunk -> {
                                if ((event.arguments.isNotEmpty() || !event.name.isNullOrBlank()) && streamProgress.firstToolAt == 0L)
                                    streamProgress.firstToolAt = streamProgress.lastEventAt
                                val builder = streamProgress.toolCalls.getOrPut(event.index) { ToolCallBuilder() }
                                event.id?.let { builder.id = it }
                                event.name?.let { builder.name = it }
                                builder.arguments += event.arguments
                            }
                            is LLMStreamEvent.ReasoningChunk -> {
                                if (event.text.isNotEmpty() && streamProgress.firstReasoningAt == 0L) streamProgress.firstReasoningAt = streamProgress.lastEventAt
                                streamProgress.reasoning += event.text
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val totalMs = System.currentTimeMillis() - startedAt
            val firstEventMs = if (streamProgress.firstEventAt > 0) streamProgress.firstEventAt - startedAt else -1
            fun offset(at: Long) = if (at > 0) (at - startedAt).coerceAtLeast(0).toString() else ""
            val firstOutputAt = listOf(streamProgress.firstContentAt, streamProgress.firstToolAt).filter { it > 0 }.minOrNull()
            val incomplete = failure as? com.droidance.core.model.llm.OpenAIResponsesIncompleteException
            val providerFailure = failure?.providerHttpException()
            onMetrics(mapOf(
                "requested_model" to requestPlan.initialModel,
                "requested_reasoning_effort" to ((streamProgress.reasoningConfiguration?.requested ?: effort).wireValue ?: "default"),
                "sent_reasoning_effort" to (streamProgress.reasoningConfiguration?.sent?.let { it.wireValue ?: "default" } ?: "unknown"),
                "reasoning_fallback_reason" to streamProgress.reasoningConfiguration?.fallbackReason.orEmpty(),
                "supported_reasoning_efforts" to streamProgress.reasoningConfiguration?.supportedEfforts
                    .orEmpty().joinToString(",") { it.wireValue ?: "default" },
                "reasoning_configuration_attempts" to streamProgress.reasoningConfigurationAttempts.toString(),
                "responses_continuation" to streamProgress.requestFacts["responses_continuation"].orEmpty(),
                "responses_input_items" to streamProgress.requestFacts["responses_input_items"].orEmpty(),
                "responses_extra_fields" to streamProgress.requestFacts["responses_extra_fields"].orEmpty(),
                "request_duration_ms" to totalMs.toString(),
                "first_stream_event_ms" to firstEventMs.toString(),
                "first_provider_event_ms" to offset(streamProgress.firstProviderEventAt),
                "reasoning_stream_ms" to if (streamProgress.firstReasoningAt == 0L) "" else (streamProgress.reasoningDurationMs +
                    if (streamProgress.reasoningSegmentAt > 0L)
                        (streamProgress.lastEventAt - streamProgress.reasoningSegmentAt).coerceAtLeast(0) else 0).toString(),
                "response_reasoning_tokens" to streamProgress.reasoningTokens?.toString().orEmpty(),
                "first_content_event_ms" to offset(streamProgress.firstContentAt),
                "first_tool_event_ms" to offset(streamProgress.firstToolAt),
                "first_reasoning_event_ms" to offset(streamProgress.firstReasoningAt),
                "first_metadata_event_ms" to offset(streamProgress.firstMetadataAt),
                "last_stream_event_ms" to offset(streamProgress.lastEventAt),
                "stream_after_first_output_ms" to firstOutputAt?.let { (streamProgress.lastEventAt - it).coerceAtLeast(0).toString() }.orEmpty(),
                "first_actionable_output_ms" to firstOutputAt?.let { offset(it) }.orEmpty(),
                "response_cache_write_tokens" to streamProgress.cacheWriteTokens?.toString().orEmpty(),
                "response_cached_input_tokens" to streamProgress.cachedInputTokens?.toString().orEmpty(),
                "request_timeout_ms" to timeoutMs.toString(),
                "requested_output_tokens" to maxOutputTokens.toString(),
                "response_content_chars" to streamProgress.content.length.toString(),
                "response_reasoning_chars" to (streamProgress.reasoning.length + streamProgress.reasoningActivityChars).toString(),
                "response_tool_calls" to streamProgress.toolCalls.size.toString(),
                "response_http_status" to providerFailure?.statusCode?.toString().orEmpty(),
                "response_error_category" to providerFailure?.category?.name.orEmpty(),
                "response_provider_code" to providerFailure?.providerCode.orEmpty(),
                "response_failure_type" to failure?.javaClass?.simpleName.orEmpty(),
                "response_timeout_phase" to (failure as? ModelStreamTimeoutException)?.phase.orEmpty(),
                "response_output_limit_reached" to (incomplete?.outputLimitReached == true).toString(),
                "returned_model" to streamProgress.returnedModel.orEmpty(),
                "response_input_tokens" to streamProgress.inputTokens?.toString().orEmpty(),
                "response_output_tokens" to (streamProgress.outputTokens ?: incomplete?.outputTokens)?.toString().orEmpty()
            ))
            diagnostic(
                "⏱ LLM(operator): ${totalMs}ms image=$hasImage firstEvent=$firstEventMs"
            )
        }
        return OperatorStreamCapture(streamProgress.content, streamProgress.reasoning, streamProgress.toolCalls, streamProgress.firstEventAt)
    }

}

data class OperatorStreamProgress(
    var reasoningConfiguration: LLMStreamEvent.ReasoningConfiguration? = null,
    var reasoningConfigurationAttempts: Int = 0,
    var requestFacts: Map<String, String> = emptyMap(),
    var firstProviderEventAt: Long = 0L,
    var reasoningSegmentAt: Long = 0L,
    var reasoningDurationMs: Long = 0L,
    var reasoningActivityChars: Int = 0,
    var reasoningTokens: Int? = null,
    @Volatile var firstEventAt: Long = 0L,
    @Volatile var lastEventAt: Long = 0L,
    var firstContentAt: Long = 0L,
    var firstToolAt: Long = 0L,
    var firstReasoningAt: Long = 0L,
    var firstMetadataAt: Long = 0L,
    var cachedInputTokens: Int? = null,
    var cacheWriteTokens: Int? = null,
    var content: String = "",
    var reasoning: String = "",
    val toolCalls: MutableMap<Int, ToolCallBuilder> = mutableMapOf(),
    var returnedModel: String? = null,
    var inputTokens: Int? = null,
    var outputTokens: Int? = null
)

data class OperatorStreamCapture(
        val content: String,
        val reasoning: String,
        val toolCalls: Map<Int, ToolCallBuilder>,
        val firstEventAt: Long
    )


class ToolCallBuilder {
    var id: String = ""
    var name: String = ""
    var arguments: String = ""
}
