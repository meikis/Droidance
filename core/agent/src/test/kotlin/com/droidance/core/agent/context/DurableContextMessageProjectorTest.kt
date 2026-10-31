// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.harness.PlannedToolCall
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.model.llm.models.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableContextMessageProjectorTest {
    @Test
    fun memoryPayloadCannotBeSilentlyDroppedDuringMessageAssembly() {
        val payload = com.droidance.core.agent.event.MemoryContextLoadedEventPayload(
            contractVersion = 1,
            queryHash = "a".repeat(64),
            items = emptyList()
        )
        val projection = DurableContextProjection(emptyList(), emptyList(), payload)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DurableContextMessageProjector.build(projection, "task")
        }
        val messages = DurableContextMessageProjector.build(
            projection, "task", memoryContextText = "rendered memory"
        )
        assertEquals("rendered memory", messages.single { it.role == Role.SYSTEM }.content)
    }

    @Test
    fun empty_interrupted_result_preserves_unknown_effect_in_model_history() {
        val call = PlannedToolCall(0, "old-call", ProviderToolCall("provider", "screen_act", "{}"),
            RegisteredToolContract("screen_act", "Screen action", ToolBinding.DEVICE_BOUND,
                ToolEffect.STATE_CHANGING, emptyMap()))
        val units = ContextUnitBuilder.build(listOf(
            UserInputAgentItem("user", 1L, "old-round", UserInputKind.MESSAGE, "original goal"),
            ToolCallAgentItem("call", 2L, "old-round", call),
            ToolResultAgentItem("result", 3L, "old-round", "old-call", ToolExecutionResult(
                outputText = "", executionStatus = ToolExecutionStatus.INTERRUPTED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN, diagnosticCode = "PROCESS_RECOVERY"
            ))
        ))
        val messages = DurableContextMessageProjector.build(
            DurableContextProjection(units, emptyList()), "original goal"
        )
        val result = messages.single { it.role == Role.TOOL }
        assertEquals("provider", result.toolCallId)
        assertTrue(result.content.contains("INTERRUPTED"))
        assertTrue(result.content.contains("UNKNOWN"))
        assertTrue(result.content.contains("PROCESS_RECOVERY"))
    }

    @Test
    fun activeProjectionKeepsEveryUnitSelectedByTheDurableCheckpoint() {
        val items = mutableListOf<AgentItem>()
        var sequence = 1L
        items += UserInputAgentItem(
            itemId = "task",
            sequence = sequence++,
            roundId = "round-1",
            kind = UserInputKind.MESSAGE,
            text = "old task"
        )
        items += AssistantOutputAgentItem(
            itemId = "assistant-1",
            sequence = sequence++,
            roundId = "round-1",
            kind = PublicMessageKind.PROGRESS,
            text = "old progress"
        )
        (2..4).forEach { round ->
            items += AssistantOutputAgentItem(
                itemId = "assistant-$round",
                sequence = sequence++,
                roundId = "round-$round",
                kind = PublicMessageKind.PROGRESS,
                text = "progress-$round"
            )
        }
        val units = ContextUnitBuilder.build(items)

        val messages = DurableContextMessageProjector.build(
            projection = DurableContextProjection(units, emptyList()),
            firstTaskPrompt = "current task"
        )

        assertEquals("current task", messages.first().content)
        assertEquals(
            listOf("old progress", "progress-2", "progress-3", "progress-4"),
            messages.drop(1).map { it.content }
        )
        assertEquals(1, messages.count { it.role == Role.USER })
    }

    @Test
    fun checkpointHistoryKeepsAllCallResultPairsAndItsNewestReceipt() {
        val contract = RegisteredToolContract(
            name = "screen_act",
            description = "Historical screen action",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            parameters = emptyMap()
        )
        val items = mutableListOf<AgentItem>()
        var sequence = 1L
        items += UserInputAgentItem("task", sequence++, "round-1", UserInputKind.MESSAGE, "old task")
        (1..3).forEach { round ->
            val roundId = "round-$round"
            val callId = "call-$round"
            val call = PlannedToolCall(
                providerOrder = 0,
                callId = callId,
                providerCall = ProviderToolCall("provider-$round", "screen_act", "{}"),
                contract = contract
            )
            items += AssistantOutputAgentItem(
                "assistant-$round",
                sequence++,
                roundId,
                PublicMessageKind.PROGRESS,
                "progress-$round"
            )
            items += ToolCallAgentItem("call-item-$round", sequence++, roundId, call)
            items += ToolResultAgentItem(
                itemId = "result-$round",
                sequence = sequence++,
                roundId = roundId,
                callId = callId,
                result = ToolExecutionResult(
                    outputText = "tool-result-$round-" + "x".repeat(120),
                    executionStatus = ToolExecutionStatus.SUCCEEDED,
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
                )
            )
        }

        val messages = DurableContextMessageProjector.build(
            projection = DurableContextProjection(ContextUnitBuilder.build(items), emptyList()),
            firstTaskPrompt = "current task"
        )
        val toolMessages = messages.filter { it.role == Role.TOOL }

        assertEquals(3, toolMessages.size)
        assertEquals(listOf("provider-1", "provider-2", "provider-3"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages.first().content.contains("tool-result-1-" + "x".repeat(120)))
        assertTrue(toolMessages.last().content.contains("tool-result-3"))
        assertTrue(!toolMessages.last().content.contains("OLD_TOOL_RESULT_OMITTED"))
    }

    @Test
    fun rebuildsProtocolAndReplacesDurableAnchorWithCurrentPrompt() {
        val contract = RegisteredToolContract(
            name = "click",
            description = "Historical click contract",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            parameters = emptyMap()
        )
        val call = PlannedToolCall(
            providerOrder = 0,
            callId = "call-1",
            providerCall = ProviderToolCall("provider-1", "click", "{\"x\":1}"),
            contract = contract
        )
        val units = ContextUnitBuilder.build(
            listOf(
                UserInputAgentItem("item-1", 1, "round-1", UserInputKind.MESSAGE, "old task"),
                AssistantOutputAgentItem("item-2", 2, "round-1", PublicMessageKind.PROGRESS, "I will click"),
                ToolCallAgentItem("item-3", 3, "round-1", call),
                ToolResultAgentItem(
                    itemId = "item-4",
                    sequence = 4,
                    roundId = "round-1",
                    callId = "call-1",
                    result = ToolExecutionResult(
                        outputText = "clicked",
                        executionStatus = ToolExecutionStatus.SUCCEEDED,
                        evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
                    )
                ),
                ObservationRefAgentItem(
                    itemId = "item-5",
                    sequence = 5,
                    roundId = "round-1",
                    observationId = "obs-1",
                    content = TextObservationContentRef("sha256:text"),
                    callId = "call-1"
                )
            )
        )

        val messages = DurableContextMessageProjector.build(
            DurableContextProjection(
                units,
                listOf(
                    ObservationProjectionRef(
                        itemSequence = 5,
                        observationId = "obs-1",
                        mode = ObservationProjectionMode.TEXT,
                        contentRef = "sha256:text"
                    )
                )
            ),
            "current task prompt"
        )

        assertEquals(3, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("current task prompt", messages[0].content)
        assertEquals(Role.ASSISTANT, messages[1].role)
        assertEquals("click", messages[1].toolCalls?.single()?.name)
        assertEquals(Role.TOOL, messages[2].role)
        assertTrue(messages[2].content.contains("clicked"))
        assertTrue(messages[2].content.contains("sha256:text"))
        assertEquals("provider-1", messages[2].toolCallId)
        assertEquals("provider-1", messages[1].toolCalls?.single()?.id)
    }

    @Test
    fun preservesActionablePreflightFeedbackAcrossDurableRebuild() {
        val contract = RegisteredToolContract(
            name = "android_act",
            description = "Open an Android destination",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            parameters = emptyMap()
        )
        val call = PlannedToolCall(
            providerOrder = 0,
            callId = "call-route",
            providerCall = ProviderToolCall("provider-route", "android_act", "{\"op\":\"open\"}"),
            contract = contract
        )
        val feedback = "Local policy rejected this call"
        val units = ContextUnitBuilder.build(
            listOf(
                UserInputAgentItem("item-1", 1, "round-1", UserInputKind.MESSAGE, "search products"),
                ToolCallAgentItem("item-2", 2, "round-1", call),
                ToolResultAgentItem(
                    itemId = "item-3",
                    sequence = 3,
                    roundId = "round-1",
                    callId = "call-route",
                    result = ToolExecutionResult(
                        outputText = feedback,
                        executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                        evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                        diagnosticCode = "POLICY_REJECTED"
                    )
                )
            )
        )

        val messages = DurableContextMessageProjector.build(
            DurableContextProjection(units, emptyList()),
            "current task prompt"
        )

        assertEquals(feedback, messages.single { it.role == Role.TOOL }.content)
        assertTrue(messages.none { it.content == "[tool result omitted]" })
    }
}
