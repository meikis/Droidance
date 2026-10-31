// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AssistantMessageEventPayload
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.PublicProgressEventPayload
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolCallSource
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.UserTextEventPayload
import com.droidance.core.agent.event.MemoryContextItemPayload
import com.droidance.core.agent.event.MemoryContextLoadedEventPayload
import com.droidance.core.agent.event.MemoryContextScope
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.prompt.StablePrefixBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableContextProjectorTest {
    private val json = Json

    @Test
    fun `memory index is rehydrated from EventStore beside closed round units`() {
        val memoryPayload = MemoryContextLoadedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            queryHash = "a".repeat(64),
            items = listOf(
                MemoryContextItemPayload(
                    ref = "profile:preferred_address",
                    scope = MemoryContextScope.CORE_PROFILE,
                    summary = "称呼用户为 Alex"
                )
            )
        )
        val events = listOf(
            event(1, AgentEventKind.USER_MESSAGE, payload = json.encodeToString(
                UserTextEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    messageId = "message-1",
                    text = "继续任务"
                )
            )),
            event(2, AgentEventKind.MEMORY_CONTEXT_LOADED, payload = json.encodeToString(memoryPayload)),
            event(3, AgentEventKind.MODEL_ROUND_STARTED, roundId = "round-1"),
            event(4, AgentEventKind.ASSISTANT_MESSAGE, roundId = "round-1", payload = json.encodeToString(
                AssistantMessageEventPayload(
                    contractVersion = 1,
                    messageId = "assistant-1",
                    kind = PublicMessageKind.START,
                    publicBlocks = listOf("开始"),
                    roundDetailRef = "detail-1"
                )
            )),
            event(5, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "round-1")
        )

        val projection = requireNotNull(DurableContextProjector.project(events))
        val messages = DurableContextMessageProjector.build(
            projection = projection,
            firstTaskPrompt = "继续任务",
            memoryContextText = "<memory_index>ref=profile:preferred_address</memory_index>"
        )

        assertEquals(memoryPayload, projection.memoryContext)
        assertTrue(messages.first().content.contains("ref=profile:preferred_address"))
        assertEquals(com.droidance.core.model.llm.models.Role.SYSTEM, messages.first().role)
    }

    @Test
    fun `skips empty closed rounds and keeps the first user anchor`() {
        val events = listOf(
            event(1, AgentEventKind.USER_MESSAGE, payload = json.encodeToString(
                UserTextEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    messageId = "message-1",
                    text = "完成任务"
                )
            )),
            event(2, AgentEventKind.MODEL_ROUND_STARTED, roundId = "empty"),
            event(3, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "empty"),
            event(4, AgentEventKind.MODEL_ROUND_STARTED, roundId = "round-1"),
            event(5, AgentEventKind.ASSISTANT_MESSAGE, roundId = "round-1", payload = json.encodeToString(
                AssistantMessageEventPayload(
                    contractVersion = 1,
                    messageId = "assistant-1",
                    kind = PublicMessageKind.START,
                    publicBlocks = listOf("开始执行"),
                    roundDetailRef = "detail-1"
                )
            )),
            event(6, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "round-1"),
            event(7, AgentEventKind.MODEL_ROUND_STARTED, roundId = "round-2"),
            event(8, AgentEventKind.PUBLIC_PROGRESS, roundId = "round-2", payload = json.encodeToString(
                PublicProgressEventPayload(
                    contractVersion = 1,
                    publicBlocks = listOf("仍在执行"),
                    roundDetailRef = "detail-2"
                )
            )),
            event(9, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "round-2"),
            event(10, AgentEventKind.USER_STEER, payload = json.encodeToString(
                UserTextEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    messageId = "steer-1",
                    text = "调整方向"
                )
            ))
        )

        val projection = DurableContextProjector.project(events)

        assertNotNull(projection)
        assertEquals(listOf("round-1", "round-2"), projection!!.units.map(ContextUnit::roundId))
        assertEquals("完成任务", projection.units.first().items.first().let { (it as UserInputAgentItem).text })
        assertTrue(projection.units.none { it.roundId == "empty" })
        assertTrue(projection.units.flatMap(ContextUnit::items).none { it.itemId == "event-10" })
    }

    @Test
    fun `checkpoint projection remains available before a new round is appended`() {
        val events = listOf(
            event(1, AgentEventKind.USER_MESSAGE, payload = json.encodeToString(
                UserTextEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    messageId = "message-1",
                    text = "完成任务"
                )
            )),
            event(2, AgentEventKind.MODEL_ROUND_STARTED, roundId = "round-1"),
            event(3, AgentEventKind.ASSISTANT_MESSAGE, roundId = "round-1", payload = json.encodeToString(
                AssistantMessageEventPayload(
                    contractVersion = 1,
                    messageId = "assistant-1",
                    kind = PublicMessageKind.START,
                    publicBlocks = listOf("开始"),
                    roundDetailRef = "detail-1"
                )
            )),
            event(4, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "round-1"),
            event(5, AgentEventKind.MODEL_ROUND_STARTED, roundId = "round-2"),
            event(6, AgentEventKind.PUBLIC_PROGRESS, roundId = "round-2", payload = json.encodeToString(
                PublicProgressEventPayload(
                    contractVersion = 1,
                    publicBlocks = listOf("继续".repeat(40)),
                    roundDetailRef = "detail-2"
                )
            )),
            event(7, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "round-2"),
            event(8, AgentEventKind.MODEL_ROUND_STARTED, roundId = "round-3"),
            event(9, AgentEventKind.PUBLIC_PROGRESS, roundId = "round-3", payload = json.encodeToString(
                PublicProgressEventPayload(
                    contractVersion = 1,
                    publicBlocks = listOf("当前状态"),
                    roundDetailRef = "detail-3"
                )
            )),
            event(10, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "round-3")
        )
        val source = requireNotNull(DurableContextProjector.project(events))
        val stablePrefix = StablePrefixBuilder.build(
            systemPrompt = "You are Droidance.",
            tools = emptyList(),
            indexEntries = emptyList()
        )
        val estimator = StructuralContextTokenEstimator.forSession(
            transportKind = "GENERIC_CHAT_COMPLETIONS",
            adapterVersion = "llm-router-v1",
            modelId = "model"
        )
        val policy = ContextPolicySnapshot(
            providerContextWindowTokens = 80,
            maxOutputTokens = 10,
            safetyMarginTokens = 0,
            compressionTriggerNumerator = 1,
            compressionTriggerDenominator = 4,
            recentScreenshotLimit = 1,
            tokenEstimatorIdentity = estimator.identity
        )
        val ready = ContextCheckpointPlanner(estimator).plan(
            ContextCheckpointPlanInput(
                parentCheckpoint = null,
                stablePrefix = stablePrefix,
                dynamicRequestSuffix = ContextDynamicRequestSuffix.empty(),
                sortedUnits = source.units,
                currentObservationProjections = source.observations,
                contextPolicy = policy,
                compactionSummary = "Model-authored task summary"
            )
        ) as ContextCheckpointDecision.CheckpointReady

        val rebuilt = DurableContextProjector.project(
            events = events,
            parentCheckpoint = ContextCheckpointRecord("checkpoint-1", ready.payload)
        )

        assertNotNull(rebuilt)
        assertEquals(
            listOf("round-1", "round-3"),
            rebuilt!!.units.map(ContextUnit::roundId)
        )
        assertTrue(rebuilt.observations.isEmpty())
        assertEquals(ready.payload.compactionSummary, rebuilt.compactionSummary)
        val replayed = DurableContextMessageProjector.build(rebuilt, "task")
        assertTrue(replayed.any { it.content.contains("<task_compaction_summary>") })
        val independent = requireNotNull(DurableContextProjector.project(events))
        assertEquals(null, independent.compactionSummary)
    }

    @Test
    fun `structural estimator accounts for current image marker`() {
        val estimator = StructuralContextTokenEstimator.forSession("GENERIC_CHAT_COMPLETIONS", "llm-router-v1", "model")
        val textOnly = estimator.estimateDynamicSuffix(
            ContextDynamicRequestSuffix(listOf(ContextDynamicRequestPart("current_screen_image", "none")))
        )
        val imageAttached = estimator.estimateDynamicSuffix(
            ContextDynamicRequestSuffix(listOf(ContextDynamicRequestPart("current_screen_image", "attached")))
        )

        assertTrue(imageAttached > textOnly)
    }

    @Test
    fun `reused observation fact is projected only once across closed rounds`() {
        val contentHash = "a".repeat(64)
        fun toolCall(sequence: Long, roundId: String, callId: String) = event(
            sequence,
            AgentEventKind.TOOL_CALL,
            roundId,
            callId,
            json.encodeToString(
                ToolCallEventPayload(
                    contractVersion = 1,
                    providerOrder = 0,
                    providerCallId = callId,
                    name = "screen_act",
                    argumentsJson = "{}",
                    source = ToolCallSource.MODEL_REQUESTED,
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING,
                    contractHash = "b".repeat(64),
                    presentation = ToolPresentationSnapshot(
                        labelResourceKey = "tool.screen_act",
                        category = ToolPresentationCategory.PRIMARY,
                        resultImageRole = ResultImageRole.BEFORE_AND_AFTER
                    ),
                    roundDetailRef = "detail-$roundId"
                )
            )
        )
        fun toolFinished(sequence: Long, roundId: String, callId: String) = event(
            sequence,
            AgentEventKind.TOOL_FINISHED,
            roundId,
            callId,
            json.encodeToString(
                ToolFinishedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    result = ToolExecutionResult(
                        outputText = "completed",
                        executionStatus = ToolExecutionStatus.SUCCEEDED,
                        evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
                    ),
                    roundDetailRef = "detail-$roundId"
                )
            )
        )
        fun attachment(sequence: Long, roundId: String, callId: String, attachmentId: String) = event(
            sequence,
            AgentEventKind.DETAIL_ATTACHMENT_ADDED,
            roundId,
            callId,
            json.encodeToString(
                DetailAttachmentAddedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    attachmentId = attachmentId,
                    role = DetailAttachmentRole.INPUT_OBSERVATION,
                    contentRef = "sha256:$contentHash",
                    contentHash = contentHash,
                    sizeBytes = 1,
                    mimeType = "image/jpeg",
                    width = 1,
                    height = 1,
                    observationId = "shared-observation",
                    modelContentRef = "sha256:" + "d".repeat(64),
                    modelSizeBytes = 1
                )
            )
        )
        val events = listOf(
            event(1, AgentEventKind.USER_MESSAGE, payload = json.encodeToString(
                UserTextEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    messageId = "message-1",
                    text = "完成任务"
                )
            )),
            event(2, AgentEventKind.MODEL_ROUND_STARTED, roundId = "round-1"),
            toolCall(3, "round-1", "call-1"),
            toolFinished(4, "round-1", "call-1"),
            attachment(5, "round-1", "call-1", "attachment-1"),
            event(6, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "round-1"),
            event(7, AgentEventKind.MODEL_ROUND_STARTED, roundId = "round-2"),
            toolCall(8, "round-2", "call-2"),
            toolFinished(9, "round-2", "call-2"),
            attachment(10, "round-2", "call-2", "attachment-2"),
            event(11, AgentEventKind.MODEL_ROUND_COMPLETED, roundId = "round-2")
        )

        val projection = requireNotNull(DurableContextProjector.project(events))

        assertEquals(
            listOf("shared-observation"),
            projection.observations.map(ObservationProjectionRef::observationId)
        )
        assertEquals(
            1,
            projection.units.flatMap(ContextUnit::items)
                .filterIsInstance<ObservationRefAgentItem>()
                .size
        )

        // Provider IDs can repeat even without compaction; they do not identify observations.
        val repeatedProviderEvents = events.map { entry ->
            when (entry.eventKind) {
                AgentEventKind.TOOL_CALL -> entry.copy(payloadJson = json.encodeToString(
                    json.decodeFromString<ToolCallEventPayload>(entry.payloadJson).copy(providerCallId = "screen_act_2")
                ))
                AgentEventKind.DETAIL_ATTACHMENT_ADDED -> {
                    val hash = if (entry.sequence == 5L) "a".repeat(64) else "c".repeat(64)
                    entry.copy(payloadJson = json.encodeToString(
                        json.decodeFromString<DetailAttachmentAddedEventPayload>(entry.payloadJson).copy(
                            observationId = "observation-${entry.sequence}", contentHash = hash, contentRef = "sha256:$hash"
                        )
                    ))
                }
                else -> entry
            }
        }
        val distinct = requireNotNull(DurableContextProjector.project(repeatedProviderEvents))
        assertEquals(listOf("observation-5", "observation-10"), distinct.observations.map { it.observationId })
        assertTrue(distinct.observations.all { it.modelContentRef == "sha256:" + "d".repeat(64) })
        assertTrue(distinct.units.flatMap(ContextUnit::items).filterIsInstance<ObservationRefAgentItem>()
            .all { (it.content as ScreenshotObservationContentRef).modelContentRef == "sha256:" + "d".repeat(64) })

        // Even duplicates in a single round must not hide conflicting content.
        val originalAttachment = events.first { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
        val conflict = originalAttachment.copy(eventId = "conflicting-attachment", sequence = 6,
            payloadJson = json.encodeToString(
                json.decodeFromString<DetailAttachmentAddedEventPayload>(originalAttachment.payloadJson).copy(
                    attachmentId = "conflict", contentHash = "c".repeat(64), contentRef = "sha256:${"c".repeat(64)}"
                )
            ))
        val corrupted = events.map { if (it.sequence >= 6) it.copy(sequence = it.sequence + 1) else it } + conflict
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DurableContextProjector.project(corrupted)
        }
    }

    @Test
    fun `completion repair history preserves provider arguments rather than compiled executor summary`() {
        val original = """{"candidate_id":"${"b".repeat(64)}","requirement_ids":["r1"],"assessment":"matched","output_review":"checked"}"""
        val compiled = """{"status":"done","summary":"Value -1.2, not 12"}"""
        val metadata = kotlinx.serialization.json.buildJsonObject {
            put("finishMetadata", kotlinx.serialization.json.buildJsonObject {
                put(DurableContextProjector.MODEL_ARGUMENTS_METADATA, kotlinx.serialization.json.JsonPrimitive(
                    kotlinx.serialization.json.buildJsonObject {
                        put("0", kotlinx.serialization.json.JsonPrimitive(original))
                    }.toString()
                ))
            })
        }
        val events = listOf(
            event(1, AgentEventKind.USER_MESSAGE, payload = json.encodeToString(UserTextEventPayload(1, "user", "Original request"))),
            event(2, AgentEventKind.MODEL_ROUND_STARTED, "round"),
            event(3, AgentEventKind.MODEL_ROUND_COMPLETED, "round", payload = metadata.toString()),
            event(4, AgentEventKind.TOOL_CALL, "round", "call", json.encodeToString(ToolCallEventPayload(
                contractVersion = 1, providerOrder = 0, providerCallId = "provider-call", name = "done",
                argumentsJson = compiled, source = ToolCallSource.MODEL_REQUESTED,
                binding = ToolBinding.TURN_LOCAL, effect = ToolEffect.COMPLETION, contractHash = "a".repeat(64),
                presentation = ToolPresentationSnapshot("tool.done", ToolPresentationCategory.COMPLETION, ResultImageRole.NONE),
                roundDetailRef = "detail-round"
            ))),
            event(5, AgentEventKind.TOOL_FINISHED, "round", "call", json.encodeToString(ToolFinishedEventPayload(
                contractVersion = 1, result = ToolExecutionResult("Review required", ToolExecutionStatus.NOT_EXECUTED,
                    ToolEvidenceStatus.NOT_REQUIRED, diagnosticCode = "COMPLETION_REVIEW_REQUIRED"), roundDetailRef = "detail-round"
            )))
        )
        val projection = requireNotNull(DurableContextProjector.project(events))
        val messages = DurableContextMessageProjector.build(projection, "Original request")
        assertEquals(original, messages.single { !it.toolCalls.isNullOrEmpty() }.toolCalls!!.single().arguments)
        assertEquals(compiled, json.decodeFromString<ToolCallEventPayload>(events.single { it.eventKind == AgentEventKind.TOOL_CALL }.payloadJson).argumentsJson)
    }

    private fun event(
        sequence: Long,
        kind: AgentEventKind,
        roundId: String? = null,
        callId: String? = null,
        payload: String = "{}"
    ) = AgentEventEnvelope(
        schemaVersion = 2,
        eventId = "event-$sequence",
        eventKind = kind,
        sequence = sequence,
        occurredAt = sequence,
        threadId = "thread-1",
        turnId = "turn-1",
        roundId = roundId,
        attemptId = null,
        executionId = null,
        callId = callId,
        payloadHash = "0".repeat(64),
        payloadJson = payload,
        idempotencyKey = "idempotency-$sequence"
    )
}
