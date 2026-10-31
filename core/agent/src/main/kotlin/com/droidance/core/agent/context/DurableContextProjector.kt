// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AssistantMessageEventPayload
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.PublicProgressEventPayload
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolCallRejectedEventPayload
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.UserTextEventPayload
import com.droidance.core.agent.event.MemoryContextLoadedEventPayload
import com.droidance.core.agent.harness.PlannedToolCall
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.harness.RegisteredToolContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Projects only closed durable rounds into provider-visible ContextUnits.
 * Audit events, model reasoning and execution-attempt lifecycle facts stay out
 * of the provider projection by construction.
 */
object DurableContextProjector {
    const val MODEL_ARGUMENTS_METADATA = "model_tool_arguments_by_order"
    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Projects the complete durable history, then applies the previous checkpoint as a
     * cumulative source boundary. Dropped units are intentionally never rehydrated from raw
     * events: the parent checkpoint's retained units and observation refs are the only inherited
     * provider projection.
     */
    fun project(
        events: List<AgentEventEnvelope>,
        parentCheckpoint: ContextCheckpointRecord? = null
    ): DurableContextProjection? {
        val ordered = events.sortedBy(AgentEventEnvelope::sequence)
        val memoryContextEvents = ordered.filter {
            it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED
        }
        val memoryContext = memoryContextEvents.lastOrNull()?.let { event ->
            json.decodeFromString(MemoryContextLoadedEventPayload.serializer(), event.payloadJson)
        }
        val starts = ordered.asSequence()
            .filter { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED }
            .mapNotNull { event -> event.roundId?.let { it to event } }
            .toMap()
        if (starts.isEmpty()) return null

        val completedRoundIds = ordered.asSequence()
            .filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .mapNotNull(AgentEventEnvelope::roundId)
            .filter(starts::containsKey)
            .distinct()
            .sortedBy { starts.getValue(it).sequence }
            .toList()
        if (completedRoundIds.isEmpty()) return null

        val roundEndExclusive = completedRoundIds.mapIndexed { index, roundId ->
            val startSequence = starts.getValue(roundId).sequence
            val nextStart = completedRoundIds.getOrNull(index + 1)
                ?.let { starts.getValue(it).sequence }
                ?: Long.MAX_VALUE
            roundId to (startSequence to nextStart)
        }.toMap()

        data class RoundProjection(
            val roundId: String,
            val startSequence: Long,
            val calls: LinkedHashMap<String, ToolCallAgentItem>,
            val results: LinkedHashMap<String, ToolResultAgentItem>,
            val outputs: List<AgentItem>,
            val observations: List<ObservationRefAgentItem>
        )

        val candidates = completedRoundIds.mapNotNull { roundId ->
            val (startSequence, endExclusive) = roundEndExclusive.getValue(roundId)
            val roundEvents = ordered.filter { event ->
                event.sequence >= startSequence && event.sequence < endExclusive && event.roundId == roundId
            }
            val completed = roundEvents.single { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            val metadata = json.parseToJsonElement(completed.payloadJson).jsonObject["finishMetadata"] as? JsonObject
            val modelArguments = metadata?.get(MODEL_ARGUMENTS_METADATA)?.let { encoded ->
                require(encoded is JsonPrimitive && encoded.isString)
                json.parseToJsonElement(encoded.content).jsonObject.mapValues { (_, value) ->
                    require(value is JsonPrimitive && value.isString)
                    value.content
                }
            }.orEmpty()
            val callsById = linkedMapOf<String, ToolCallAgentItem>()
            roundEvents.filter { it.eventKind == AgentEventKind.TOOL_CALL }.forEach { event ->
                val callId = requireNotNull(event.callId) { "Durable ToolCall is missing callId" }
                val payload = json.decodeFromString(ToolCallEventPayload.serializer(), event.payloadJson)
                val contract = RegisteredToolContract(
                    name = payload.name,
                    description = "Historical provider tool contract",
                    binding = payload.binding,
                    effect = payload.effect,
                    parameters = emptyMap()
                )
                callsById[callId] = ToolCallAgentItem(
                    itemId = event.eventId,
                    sequence = event.sequence,
                    roundId = roundId,
                    call = PlannedToolCall(
                        providerOrder = payload.providerOrder,
                        callId = callId,
                        providerCall = ProviderToolCall(
                            providerCallId = payload.providerCallId,
                            name = payload.name,
                            argumentsJson = modelArguments[payload.providerOrder.toString()] ?: payload.argumentsJson
                        ),
                        contract = contract
                    )
                )
            }

            val resultsByCallId = linkedMapOf<String, ToolResultAgentItem>()
            roundEvents.filter {
                it.eventKind == AgentEventKind.TOOL_FINISHED ||
                    it.eventKind == AgentEventKind.TOOL_CALL_REJECTED
            }.forEach { event ->
                val callId = requireNotNull(event.callId) { "Durable tool result is missing callId" }
                val result = when (event.eventKind) {
                    AgentEventKind.TOOL_FINISHED -> json.decodeFromString(
                        ToolFinishedEventPayload.serializer(), event.payloadJson
                    ).result
                    AgentEventKind.TOOL_CALL_REJECTED -> json.decodeFromString(
                        ToolCallRejectedEventPayload.serializer(), event.payloadJson
                    ).result
                    else -> error("Unsupported durable result event")
                }
                resultsByCallId[callId] = ToolResultAgentItem(
                    itemId = event.eventId,
                    sequence = event.sequence,
                    roundId = roundId,
                    callId = callId,
                    result = result
                )
            }
            if (callsById.keys.isNotEmpty() && resultsByCallId.keys != callsById.keys) return@mapNotNull null

            val outputs = buildList {
                roundEvents.filter { it.eventKind == AgentEventKind.ASSISTANT_MESSAGE }.forEach { event ->
                    val payload = json.decodeFromString(AssistantMessageEventPayload.serializer(), event.payloadJson)
                    add(
                        AssistantOutputAgentItem(
                            itemId = event.eventId,
                            sequence = event.sequence,
                            roundId = roundId,
                            kind = payload.kind,
                            text = payload.publicBlocks.joinToString("\n")
                        )
                    )
                }
                roundEvents.filter { it.eventKind == AgentEventKind.PUBLIC_PROGRESS }.forEach { event ->
                    val payload = json.decodeFromString(PublicProgressEventPayload.serializer(), event.payloadJson)
                    add(
                        AssistantOutputAgentItem(
                            itemId = event.eventId,
                            sequence = event.sequence,
                            roundId = roundId,
                            kind = PublicMessageKind.PROGRESS,
                            text = payload.publicBlocks.joinToString("\n")
                        )
                    )
                }
            }
            if (outputs.isEmpty() && callsById.isEmpty()) return@mapNotNull null

            val observations = mutableListOf<ObservationRefAgentItem>()
            roundEvents.filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }.forEach { event ->
                val payload = json.decodeFromString(DetailAttachmentAddedEventPayload.serializer(), event.payloadJson)
                val observationId = payload.observationId ?: return@forEach
                val content = if (payload.mimeType.startsWith("image/")) {
                    ScreenshotObservationContentRef(
                        imageRef = payload.contentRef,
                        textSummaryRef = payload.contentRef,
                        sourceObservationId = payload.sourceObservationId,
                        sourceContentRef = payload.sourceContentRef,
                        sourceRegion = payload.sourceRegion,
                        modelContentRef = payload.modelContentRef
                    )
                } else {
                    TextObservationContentRef(payload.contentRef)
                }
                observations += ObservationRefAgentItem(
                    itemId = event.eventId,
                    sequence = event.sequence,
                    roundId = roundId,
                    observationId = observationId,
                    content = content,
                    callId = event.callId
                )
            }
            RoundProjection(roundId, startSequence, callsById, resultsByCallId, outputs, observations)
        }
        if (candidates.isEmpty()) return null

        val itemsByRound = candidates.associate { it.roundId to mutableListOf<AgentItem>() }
        ordered.filter { it.eventKind == AgentEventKind.USER_MESSAGE || it.eventKind == AgentEventKind.USER_STEER }
            .forEach { event ->
                val target = candidates.firstOrNull { candidate ->
                    event.sequence < candidate.startSequence
                }?.roundId ?: candidates.firstOrNull()
                    ?.takeIf { event.sequence < it.startSequence }
                    ?.roundId
                // A steer recorded after the latest closed round belongs to the next round
                // that has not started yet. Do not append it to the previous unit and violate
                // the user-before-assistant protocol; it will be projected on the next call.
                if (target == null) return@forEach
                val payload = json.decodeFromString(UserTextEventPayload.serializer(), event.payloadJson)
                itemsByRound.getValue(target) += UserInputAgentItem(
                    itemId = event.eventId,
                    sequence = event.sequence,
                    roundId = target,
                    kind = if (event.eventKind == AgentEventKind.USER_STEER) {
                        UserInputKind.STEER
                    } else {
                        UserInputKind.MESSAGE
                    },
                    text = payload.text
                )
            }

        candidates.forEach { candidate ->
            val roundItems = itemsByRound.getValue(candidate.roundId)
            roundItems += candidate.outputs
            roundItems += candidate.calls.values
            roundItems += candidate.results.values
            roundItems += candidate.observations
        }

        val sortedItems = itemsByRound.values
            .flatten()
            .distinctBy(AgentItem::itemId)
            .sortedBy(AgentItem::sequence)
            .let { items ->
                val observationsById = mutableMapOf<String, ObservationContentRef>()
                items.filter { item ->
                    if (item !is ObservationRefAgentItem) return@filter true
                    val previous = observationsById.putIfAbsent(item.observationId, item.content)
                    require(previous == null || previous == item.content) {
                        "One observation id cannot reference different durable content"
                    }
                    previous == null
                }
            }
        if (sortedItems.isEmpty()) return null
        val units = runCatching { ContextUnitBuilder.build(sortedItems) }.getOrNull() ?: return null
        if (units.isEmpty()) return null

        val sourceUnits = if (parentCheckpoint == null) {
            units
        } else {
            val retainedRefs = parentCheckpoint.payload.retainedUnits.toSet()
            val inherited = units.filter { it.toRef() in retainedRefs }
            if (inherited.map(ContextUnit::toRef) != parentCheckpoint.payload.retainedUnits) return null
            val appended = units.filter { it.endSequence > parentCheckpoint.payload.sourceThroughSequence }
            // A checkpoint is itself a valid cumulative provider projection.  The first
            // request after committing it normally has no newly closed round yet, so returning
            // null here would make the live Operator keep its pre-checkpoint message list and
            // silently defeat compaction.  Keep the inherited projection until a later round
            // appends new durable units; only an overlapping append is invalid lineage.
            if (appended.any { it.startSequence <= parentCheckpoint.payload.sourceThroughSequence }) {
                return null
            }
            inherited + appended
        }
        if (sourceUnits.isEmpty()) return null
        val appendedUnits = if (parentCheckpoint == null) {
            sourceUnits
        } else {
            sourceUnits.drop(parentCheckpoint.payload.retainedUnits.size)
        }
        val observations = if (parentCheckpoint == null) {
            ContextObservationProjection.initial(sourceUnits)
        } else {
            parentCheckpoint.payload.observations + ContextObservationProjection.initial(appendedUnits)
        }
        return DurableContextProjection(
            units = sourceUnits,
            observations = observations,
            memoryContext = memoryContext,
            compactionSummary = parentCheckpoint?.payload?.compactionSummary
        )
    }
}

data class DurableContextProjection(
    val units: List<ContextUnit>,
    val observations: List<ObservationProjectionRef>,
    val memoryContext: MemoryContextLoadedEventPayload? = null,
    val compactionSummary: String? = null
)
