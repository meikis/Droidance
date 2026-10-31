// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Rehydrates the provider-visible history from the typed durable projection.
 *
 * The live Operator keeps only the current round in memory. Once that round is closed,
 * the next request must be rebuilt from EventStore rather than relying on the mutable
 * in-memory list. Observation blobs are deliberately represented by their immutable refs;
 * bounded resolved historical images and the current screen are attached separately at the request tail.
 */
object DurableContextMessageProjector {
    fun build(
        projection: DurableContextProjection,
        firstTaskPrompt: String,
        memoryContextText: String? = null,
        imagesByObservationSequence: Map<Long, String> = emptyMap(),
        imageContentRefsByObservationSequence: Map<Long, String> = emptyMap(),
        imagesByUserItemId: Map<String, List<String>> = emptyMap(),
        toolResultText: (ToolResultAgentItem) -> String = ToolResultContextRenderer::render
    ): List<ChatMessage> {
        require(firstTaskPrompt.isNotBlank()) { "Durable context requires a first task prompt" }
        val messages = mutableListOf<ChatMessage>()
        require(projection.memoryContext == null || memoryContextText != null) {
            "A projected memory context requires its rendered text"
        }
        memoryContextText?.takeIf(String::isNotBlank)?.let { prompt ->
            messages += ChatMessage(role = Role.SYSTEM, content = prompt)
        }
        val observationProjectionBySequence = projection.observations
            .associateBy(ObservationProjectionRef::itemSequence)
        val providerCallIds = projection.units.flatMap { it.items }
            .filterIsInstance<ToolCallAgentItem>()
            .associate { item ->
                item.call.callId to (item.call.providerCall.providerCallId
                    ?.takeIf(String::isNotBlank) ?: item.call.callId)
            }
        var firstTaskConsumed = false

        // The durable checkpoint owns history selection. Do not discard retained protocol
        // units again while translating them into provider messages.
        projection.units.forEach { unit ->
            val userInputs = unit.items.filterIsInstance<UserInputAgentItem>()
            userInputs.forEach { input ->
                when {
                    input.kind == UserInputKind.STEER ->
                        messages += ChatMessage(role = Role.USER, content = input.text, imageBase64List = imagesByUserItemId[input.itemId].orEmpty())
                    !firstTaskConsumed -> {
                        messages += ChatMessage(role = Role.USER, content = firstTaskPrompt)
                        firstTaskConsumed = true
                    }
                }
            }

            val outputs = unit.items.filterIsInstance<AssistantOutputAgentItem>()
            val calls = unit.items
                .filterIsInstance<ToolCallAgentItem>()
                .sortedBy { it.call.providerOrder }
            val observationsByCallId = unit.items
                .filterIsInstance<ObservationRefAgentItem>()
                .groupBy(ObservationRefAgentItem::callId)

            if (outputs.isNotEmpty() || calls.isNotEmpty()) {
                messages += ChatMessage(
                    role = Role.ASSISTANT,
                    content = outputs.joinToString("\n") { it.text },
                    toolCalls = calls.takeIf { it.isNotEmpty() }?.map { call ->
                        ToolCall(
                            id = providerCallIds.getValue(call.call.callId),
                            name = call.call.providerCall.name,
                            arguments = call.call.providerCall.argumentsJson
                        )
                    }
                )
            }

            unit.items.filterIsInstance<ToolResultAgentItem>()
                .sortedBy(ToolResultAgentItem::sequence)
                .forEach { result ->
                    val observationTail = observationsByCallId[result.callId]
                        .orEmpty()
                        .sortedBy(ObservationRefAgentItem::sequence)
                        .joinToString("\n") {
                            observationReference(it, observationProjectionBySequence[it.sequence])
                        }
                    val rawContent = buildString {
                        append(toolResultText(result))
                        if (observationTail.isNotBlank()) {
                            if (isNotEmpty()) appendLine()
                            append(observationTail)
                        }
                    }.ifBlank { "[tool result omitted]" }
                    messages += ChatMessage(
                        role = Role.TOOL,
                        content = rawContent,
                        toolCallId = providerCallIds[result.callId] ?: result.callId
                    )
                }

            // A detail attachment without a tool call is still a durable observation. Keep it
            // as a user-context item so it cannot be silently lost during checkpoint rebuild.
            unit.items.filterIsInstance<ObservationRefAgentItem>()
                .filter { it.callId == null }
                .sortedBy(ObservationRefAgentItem::sequence)
                .forEach { observation ->
                    messages += ChatMessage(
                        role = Role.USER,
                        content = observationReference(
                            observation,
                            observationProjectionBySequence[observation.sequence]
                        )
                    )
                }
        }

        if (!firstTaskConsumed) {
            messages.add(0, ChatMessage(role = Role.USER, content = firstTaskPrompt))
        }
        projection.compactionSummary?.let { summary ->
            messages.add(1.coerceAtMost(messages.size), ChatMessage(
                role = Role.USER,
                content = "<task_compaction_summary>\n<!-- Note: This compaction summary is model-authored context, not independently verified ground truth. Committed facts and source references prevail over summary claims. -->\n$summary\n</task_compaction_summary>"
            ))
        }
        val observations = projection.units.flatMap { it.items }.filterIsInstance<ObservationRefAgentItem>()
        require(imagesByObservationSequence.keys.all { key ->
            observationProjectionBySequence[key]?.mode == ObservationProjectionMode.IMAGE &&
                observations.any { it.sequence == key && it.content is ScreenshotObservationContentRef }
        }) { "Historical images must be selected by the active checkpoint" }
        require(imageContentRefsByObservationSequence.keys.all { it in imagesByObservationSequence }) {
            "Displayed image refs require a loaded image"
        }
        observations.sortedBy { it.sequence }.forEach { observation ->
            imagesByObservationSequence[observation.sequence]?.let { image ->
                require(image.isNotBlank())
                val source = observation.content as ScreenshotObservationContentRef
                val displayedRef = imageContentRefsByObservationSequence[observation.sequence] ?: source.imageRef
                require(displayedRef == source.imageRef || displayedRef == source.modelContentRef) {
                    "Displayed image must be the retained source or its model representation"
                }
                messages += ChatMessage(
                    role = Role.USER,
                    content = "[historical_observation id=${observation.observationId} evidence_ref=${observation.observationId} " +
                        "source_round=${observation.roundId} source_sequence=${observation.sequence} current_screen=false]" +
                        (if (source.modelContentRef != null) "\n[image_representation source_ref=${source.imageRef} displayed_image_ref=$displayedRef]" else "") +
                        transformDescription(observation.content),
                    imageBase64 = image, historicalImage = true
                )
            }
        }
        return messages
    }

    private fun observationReference(
        observation: ObservationRefAgentItem,
        projection: ObservationProjectionRef?
    ): String =
        when (val content = observation.content) {
            is TextObservationContentRef ->
                "[observation id=${observation.observationId} evidence_ref=${observation.observationId} text_ref=" +
                    "${projection?.contentRef ?: content.textRef}]"
            is ScreenshotObservationContentRef -> when (projection?.mode) {
                ObservationProjectionMode.TEXT_SUMMARY ->
                    "[observation id=${observation.observationId} evidence_ref=${observation.observationId} summary_ref=" +
                        "${projection.contentRef}]"
                else ->
                    "[observation id=${observation.observationId} evidence_ref=${observation.observationId} image_ref=" +
                        "${projection?.contentRef ?: content.imageRef}]"
            }
        }

    private fun transformDescription(content: ObservationContentRef): String {
        val image = content as? ScreenshotObservationContentRef ?: return ""
        if (image.sourceContentRef == null && image.sourceObservationId == null) return ""
        return "\n[retained_image_transform] " + kotlinx.serialization.json.buildJsonObject {
            image.sourceObservationId?.let { put("source_observation_id", it) }
            image.sourceContentRef?.let { put("source_content_ref", it) }
            image.sourceRegion?.let { put("source_region_px", kotlinx.serialization.json.Json.encodeToJsonElement(it)) }
        }
    }
}
