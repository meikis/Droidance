// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.context.ContextPolicySnapshot
import com.droidance.core.agent.context.ContextCompactionOutputLimit
import com.droidance.core.agent.context.ContextCompactionUnavailable
import com.droidance.core.agent.context.ContextSummaryRunner
import com.droidance.core.agent.context.DurableContextMessageProjector
import com.droidance.core.agent.context.DurableContextProjection
import com.droidance.core.agent.context.StructuralContextTokenEstimator
import com.droidance.core.agent.context.ToolResultContextRenderer
import com.droidance.core.agent.ports.RuleSource
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolDefinition
import javax.inject.Inject
import com.droidance.core.agent.context.ContextCompactionExecution
import com.droidance.core.agent.context.ContextCompactionExecutionPolicy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.droidance.core.agent.blob.BlobStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Same model and prefix, isolated transport fork. Tool calls are never dispatched. */
class TaskContextCompactor @Inject constructor(
    private val modelClient: ModelClient,
    private val rules: RuleSource,
    private val blobs: BlobStore,
    private val debugLogManager: com.droidance.core.agent.debug.DebugLogAppender
) {
    suspend fun compact(
        projection: DurableContextProjection,
        policy: ContextPolicySnapshot,
        taskPrompt: String,
        executionPolicy: ContextCompactionExecutionPolicy,
        parentSystemPrompt: String,
        parentTools: List<ToolDefinition>,
        parentMessages: List<ChatMessage>
    ): String {
        val instruction = rules.readMarkdownSection("utility_prompts.md", "task_context_compaction")
        require(instruction.isNotBlank()) { "Missing compaction prompt" }
        val loaded = withContext(Dispatchers.IO) {
            HistoricalObservationImages(blobs).load(projection, policy.recentScreenshotLimit, null)
        }
        check(loaded.failures.isEmpty()) { "Compaction evidence could not be loaded; history retained" }
        val input = TaskCompactionInput.build(projection, taskPrompt, loaded.images)
        val estimator = StructuralContextTokenEstimator(policy.tokenEstimatorIdentity)
        val execution = ContextCompactionExecution(executionPolicy)
        val cancellationGeneration = modelClient.currentCancellationGeneration()
        require(parentSystemPrompt.isNotBlank())
        val inputBudget = policy.providerContextWindowTokens - policy.safetyMarginTokens - executionPolicy.retryMaxOutputTokens
        val parentImages = parentMessages.flatMap { it.imageBase64List.ifEmpty { listOfNotNull(it.imageBase64) } }.toSet()
        val fork = parentMessages + input.images.filter { it.imageBase64 !in parentImages } + ChatMessage(Role.USER, instruction)
        return execution.run {
            suspend fun generate(requestMessages: List<ChatMessage>): String {
                return execution.request { timeoutMs, outputTokens ->
                    val output = StringBuilder()
                    val started = System.nanoTime()
                    var firstContentMs: Long? = null
                    var finishReason: String? = null
                    var outcome = "completed"
                    debugLogManager.appendEvent(
                        "context compaction request: estimated_input_tokens=${estimator.estimateRequest(parentSystemPrompt, parentTools, requestMessages)} " +
                            "timeout_ms=$timeoutMs output_budget=$outputTokens"
                    )
                    try {
                        modelClient.streamChat(
                            messages = requestMessages,
                            tools = parentTools,
                            systemPrompt = parentSystemPrompt,
                            hasImage = requestMessages.any { it.imageBase64 != null || it.imageBase64List.isNotEmpty() },
                            maxOutputTokens = outputTokens,
                            requestTimeoutMs = timeoutMs,
                            requestCancellationGeneration = cancellationGeneration,
                            isolatedContext = true
                        ).collect { event ->
                            when (event) {
                                is LLMStreamEvent.ReasoningConfiguration, is LLMStreamEvent.RequestConfiguration -> Unit
                                is LLMStreamEvent.Activity -> Unit
                                is LLMStreamEvent.ResponseMetadata -> {
                                    event.finishReason?.let { finishReason = it }
                                }
                                is LLMStreamEvent.Content -> {
                                    if (firstContentMs == null && event.text.isNotEmpty()) {
                                        firstContentMs = (System.nanoTime() - started) / 1_000_000
                                    }
                                    output.append(event.text)
                                }
                                is LLMStreamEvent.ToolCallChunk -> throw ContextCompactionUnavailable("COMPACTION_TOOL_CALL_REJECTED")
                                is LLMStreamEvent.ReasoningChunk -> Unit
                            }
                        }
                        when (finishReason) {
                            "length" -> throw ContextCompactionOutputLimit()
                            null, "stop" -> Unit
                            else -> throw ContextCompactionUnavailable("COMPACTION_RESPONSE_INCOMPLETE")
                        }
                        if (output.isBlank()) throw ContextCompactionUnavailable("COMPACTION_EMPTY_OUTPUT")
                        output.toString()
                    } catch (error: Exception) {
                        outcome = error::class.simpleName.orEmpty()
                        throw compactionResponseFailure(error)
                    } finally {
                        debugLogManager.appendEvent(
                            "context compaction response: outcome=$outcome duration_ms=${(System.nanoTime() - started) / 1_000_000} " +
                                "first_content_ms=$firstContentMs output_chars=${output.length} finish_reason=$finishReason"
                        )
                    }
                }
            }
            // Ordinary compaction keeps the exact parent prefix. Oversized evidence still
            // needs bounded source chunks; those retain the same frozen system and tools.
            if (estimator.estimateRequest(parentSystemPrompt, parentTools, fork) <= inputBudget) {
                generate(fork)
            } else {
                val framingTokens = estimator.estimateRequest(parentSystemPrompt, parentTools,
                    listOf(ChatMessage(Role.USER, instruction)) + input.images) + 256
                ContextSummaryRunner(estimator::estimateText).summarize(
                    source = input.source,
                    inputBudget = minOf(inputBudget.toLong(), executionPolicy.chunkInputTokens.toLong() + framingTokens).toInt(),
                    promptTokens = framingTokens
                ) { previousSummary, chunk ->
                    generate(listOf(ChatMessage(Role.USER,
                        "<previous_summary>\n$previousSummary\n</previous_summary>\n<history_data>\n$chunk\n</history_data>")) +
                        input.images + ChatMessage(Role.USER, instruction))
                }
            }
        }
    }
}

/** Keep image bytes out of serialized text and reserve their cost in every summary chunk. */
internal data class TaskCompactionInput(val source: String, val images: List<ChatMessage>) {
    companion object {
        fun build(projection: DurableContextProjection, prompt: String, images: Map<Long, String>): TaskCompactionInput {
            val history = DurableContextMessageProjector.build(
                projection.copy(memoryContext = null), prompt, imagesByObservationSequence = images,
                toolResultText = ToolResultContextRenderer::fullText
            )
            val source = buildString {
                appendLine(projection.units.joinToString("\n") { "round=${it.roundId} sequence=${it.startSequence}..${it.endSequence}" })
                history.filterNot { it.historicalImage }.forEach {
                    appendLine(Json.encodeToString(it.copy(timestamp = 0)))
                }
            }
            return TaskCompactionInput(source, history.filter { it.historicalImage })
        }
    }
}

/** Provider termination is classified before any partial summary can become a checkpoint. */
internal fun compactionResponseFailure(error: Exception): Exception =
    if (error is com.droidance.core.model.llm.OpenAIResponsesIncompleteException && error.outputLimitReached)
        com.droidance.core.agent.context.ContextCompactionOutputLimit(error)
    else error
