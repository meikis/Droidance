// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.prompt.StablePrefix
import com.droidance.core.model.llm.ProviderMessageProjection
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.model.llm.models.toJsonSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic request-size estimator used by the durable checkpoint policy.
 * It deliberately estimates every provider-visible field and records its
 * version in the frozen identity so a later tokenizer change cannot silently
 * reuse an old checkpoint.
 */
class StructuralContextTokenEstimator(
    override val identity: ContextTokenEstimatorIdentity,
    private val imageTokens: Int = DEFAULT_IMAGE_TOKENS
) : ContextTokenEstimator {
    init {
        require(imageTokens > 0) { "Image token estimate must be positive" }
    }

    override fun estimateStablePrefix(stablePrefix: StablePrefix): Int = estimateText(stablePrefix.canonicalJson)

    /** Bound the actual source projection, including dynamic schemas and per-image cost.
     * Image bytes are not text tokens. Keep source labels and provider message framing. */
    fun estimateRequest(systemPrompt: String, tools: List<ToolDefinition>, messages: List<ChatMessage>): Int {
        val projected = ProviderMessageProjection.forOpenAiCompatible(messages)
        val textMessages = projected.map { it.copy(imageBase64 = null, imageBase64List = emptyList(), timestamp = 0) }
        val images = projected.sumOf { it.imageBase64List.ifEmpty { listOfNotNull(it.imageBase64) }.size }
        val schemas = JsonArray(tools.map { tool -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", tool.name); put("description", tool.description); put("strict", false)
                put("parameters", ToolParameter("object", "", properties = tool.parameters).toJsonSchema())
            })
        } })
        return (estimateText(systemPrompt).toLong() + estimateText(schemas.toString()) +
            estimateText(Json.encodeToString(textMessages)) + images.toLong() * imageTokens)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun estimateDynamicSuffix(dynamicSuffix: ContextDynamicRequestSuffix): Int =
        dynamicSuffix.parts.sumOf { part ->
            estimateText(part.partId) + estimateText(part.canonicalContent) +
                if (part.partId.endsWith("_image") && part.canonicalContent != "none") imageTokens else 0
        }

    override fun estimateUnit(
        unit: ContextUnit,
        observations: Map<Long, ObservationProjectionRef>
    ): Int = unit.items.sumOf { item ->
        val itemTokens = when (item) {
            is UserInputAgentItem -> estimateText(item.text)
            is AssistantOutputAgentItem -> estimateText(item.text) + estimateText(item.kind.name)
            is ToolCallAgentItem -> estimateText(item.call.callId) +
                estimateText(item.call.providerCall.name) +
                estimateText(item.call.providerCall.argumentsJson) +
                estimateText(item.call.contract.description)
            is ToolResultAgentItem -> estimateText(item.callId) + estimateText(ToolResultContextRenderer.render(item)) +
                estimateText(item.result.executionStatus.name) + estimateText(item.result.evidenceStatus.name)
            is ObservationRefAgentItem -> estimateText(item.observationId) +
                estimateText(observationText(item)) +
                if (observations[item.sequence]?.mode == ObservationProjectionMode.IMAGE) imageTokens else 0
        }
        itemTokens.coerceAtLeast(1)
    }.coerceAtMost(Int.MAX_VALUE)

    private fun observationText(item: ObservationRefAgentItem): String = when (val content = item.content) {
        is TextObservationContentRef -> content.textRef
        is ScreenshotObservationContentRef -> listOfNotNull(content.textSummaryRef ?: content.imageRef,
            content.sourceObservationId, content.sourceContentRef, content.sourceRegion?.toString(), content.modelContentRef).joinToString("\n")
    }

    fun estimateText(text: String): Int {
        if (text.isBlank()) return 0
        var quarterTokens = 0L
        text.forEach { character ->
            quarterTokens += if (character.code <= 0x7f) 1L else 4L
        }
        return ((quarterTokens + 3L) / 4L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    companion object {
        const val DEFAULT_IMAGE_TOKENS = 1024

        fun forSession(
            transportKind: String,
            adapterVersion: String,
            modelId: String
        ): StructuralContextTokenEstimator = StructuralContextTokenEstimator(
            identity = ContextTokenEstimatorIdentity(
                transportKind = transportKind,
                adapterVersion = adapterVersion,
                modelId = modelId,
                requestSerializerVersion = "droidance-context-v5-native-model-images",
                tokenizerVersion = "structural-utf8-quarter-v1",
                imageCostVersion = "bounded-retained-image-v2",
                coverage = ContextTokenEstimatorCoverage(
                    stablePrefix = true,
                    dynamicSuffix = true,
                    providerFraming = true,
                    userAssistantItems = true,
                    toolProtocol = true,
                    observationText = true,
                    observationImages = true
                )
            )
        )
    }
}
