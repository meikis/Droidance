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
import com.droidance.core.agent.prompt.StablePrefixBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCheckpointTest {
    private val stablePrefix = StablePrefixBuilder.build(
        systemPrompt = "You are Droidance.",
        tools = emptyList(),
        indexEntries = emptyList()
    )

    @Test
    fun `does not checkpoint below the frozen policy trigger`() {
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))

        val decision = planner.plan(input(textUnits(2), providerWindow = 210))

        assertEquals(
            ContextCheckpointDecision.Unchanged(
                reason = ContextUnchangedReason.BELOW_TRIGGER,
                tokensBefore = 55,
                triggerTokens = 100
            ),
            decision
        )
    }

    @Test
    fun `closed round count never bypasses the token trigger`() {
        val units = textUnits(20)
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 2))

        val decision = planner.plan(input(units, providerWindow = 410))

        assertTrue(decision is ContextCheckpointDecision.Unchanged)
        val unchanged = decision as ContextCheckpointDecision.Unchanged
        assertEquals(ContextUnchangedReason.BELOW_TRIGGER, unchanged.reason)
        assertTrue(unchanged.tokensBefore < unchanged.triggerTokens)
    }

    @Test
    fun `uses only latest closed public progress as current task state`() {
        val units = textUnits(count = 6, progressRounds = setOf(4))
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))

        val ready = planner.plan(input(units, providerWindow = 210)) as ContextCheckpointDecision.CheckpointReady

        assertEquals(8L, ready.payload.protection.currentTaskStateItemSequence)
        assertEquals(listOf("round-2", "round-3", "round-5"), ready.payload.droppedUnits.map(ContextUnitRef::roundId))
        assertEquals(
            listOf("round-1", "round-4", "round-6"),
            ready.payload.retainedUnits.map(ContextUnitRef::roundId)
        )
        ContextCheckpointValidator.validateAgainst(
            payload = ready.payload,
            parentCheckpoint = null,
            stablePrefix = stablePrefix,
            dynamicRequestSuffix = ContextDynamicRequestSuffix.empty(),
            sortedSourceUnits = units,
            currentObservationProjections = ContextObservationProjection.initial(units)
        )
    }

    @Test
    fun `latest screenshot stays verbatim and older history is summarized`() {
        val units = screenshotUnits(summaryMissingForRound = 3)
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 30))

        val ready = planner.plan(
            input(units, providerWindow = 210, recentScreenshotLimit = 1)
        ) as ContextCheckpointDecision.CheckpointReady

        assertEquals(listOf("round-2", "round-3"), ready.payload.droppedUnits.map(ContextUnitRef::roundId))
        assertEquals(
            listOf(ObservationProjectionMode.IMAGE),
            ready.payload.observations.map(ObservationProjectionRef::mode)
        )
        assertFalse(ready.payload.observationTransitions.any { it.itemSequence == 8L })
    }

    @Test
    fun `child checkpoint starts from parent projection and cannot resurrect dropped ancestors`() {
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))
        val all = textUnits(10)
        val firstSource = all.take(6)
        val first = planner.plan(input(firstSource, providerWindow = 210))
            as ContextCheckpointDecision.CheckpointReady
        val parent = ContextCheckpointRecord("checkpoint-1", first.payload)
        val childSource = first.projection.units + all.drop(6)

        val second = planner.plan(
            input(
                units = childSource,
                providerWindow = 210,
                parentCheckpoint = parent,
                currentObservationProjections = first.projection.observations
            )
        ) as ContextCheckpointDecision.CheckpointReady

        assertEquals("checkpoint-1", second.payload.parentCheckpointEventId)
        assertEquals(listOf("round-6", "round-7", "round-8", "round-9"), second.payload.droppedUnits.map(ContextUnitRef::roundId))
        assertEquals(
            listOf("round-1", "round-10"),
            second.payload.retainedUnits.map(ContextUnitRef::roundId)
        )
        assertFalse(second.payload.retainedUnits.any { it.roundId == "round-2" || it.roundId == "round-3" })
        ContextCheckpointValidator.validate(parent.payload)
        ContextCheckpointValidator.validate(second.payload)
        assertTrue(second.payload.sourceThroughSequence > parent.payload.sourceThroughSequence)
        assertEquals(parent.payload.stablePrefixHash, second.payload.stablePrefixHash)
        assertEquals(parent.payload.contextPolicyHash, second.payload.contextPolicyHash)
    }

    @Test
    fun `frozen estimator identity must cover the complete provider request`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextTokenEstimatorCoverage(
                stablePrefix = true,
                dynamicSuffix = true,
                providerFraming = false,
                userAssistantItems = true,
                toolProtocol = true,
                observationText = true,
                observationImages = true
            )
        }

        val estimator = FixedTokenEstimator(stableTokens = 10, unitTokens = 20)
        val mismatched = ContextCheckpointPlanner(estimator.copyWithModel("other-model"))
        assertThrows(IllegalArgumentException::class.java) {
            mismatched.plan(input(textUnits(6), providerWindow = 210))
        }
    }

    @Test
    fun `checkpoint payload round trips and rejects a modified projection fingerprint`() {
        val units = textUnits(6)
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))
        val payload = (planner.plan(input(units, providerWindow = 210))
            as ContextCheckpointDecision.CheckpointReady).payload

        val decoded = Json.decodeFromString<ContextCheckpointPayload>(Json.encodeToString(payload))
        assertEquals(payload, decoded)
        ContextCheckpointValidator.validate(decoded)

        assertThrows(IllegalArgumentException::class.java) {
            ContextCheckpointValidator.validate(decoded.copy(projectionHash = "0".repeat(64)))
        }
    }

    @Test fun `source model image pairs survive checkpointing and participate in its fingerprint`() {
        val modelRef = "sha256:" + "d".repeat(64)
        val units = ContextUnitBuilder.build(screenshotUnits(-1).flatMap { it.items }.map { item ->
            if (item is ObservationRefAgentItem) item.copy(content =
                (item.content as ScreenshotObservationContentRef).copy(modelContentRef = modelRef)) else item
        })
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 30))
        val ready = planner.plan(input(units, providerWindow = 210)) as ContextCheckpointDecision.CheckpointReady
        assertTrue(ready.payload.observations.isNotEmpty())
        assertTrue(ready.payload.observations.all { it.modelContentRef == modelRef })
        val decoded = Json.decodeFromString<ContextCheckpointPayload>(Json.encodeToString(ready.payload))
        ContextCheckpointValidator.validate(decoded)
        val changed = decoded.observations.map { it.copy(modelContentRef = "sha256:" + "e".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) {
            ContextCheckpointValidator.validate(decoded.copy(observations = changed))
        }
    }

    @Test
    fun `ordinary public reply is not silently promoted to task state`() {
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))
        val ready = planner.plan(input(textUnits(6), providerWindow = 210))
            as ContextCheckpointDecision.CheckpointReady

        assertNull(ready.payload.protection.currentTaskStateItemSequence)
    }

    @Test
    fun `latest closed tool result is protected when public progress is absent`() {
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 30))
        val ready = planner.plan(
            input(screenshotUnits(summaryMissingForRound = -1), providerWindow = 210)
        ) as ContextCheckpointDecision.CheckpointReady

        assertEquals(10L, ready.payload.protection.currentTaskStateItemSequence)
        assertTrue(ready.payload.retainedUnits.any { it.roundId == "round-4" })
    }


    @Test
    fun `watermark requests a model summary before dropping any unit`() {
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))
        val source = input(textUnits(6), 210).copy(compactionSummary = null)
        assertTrue(planner.plan(source) is ContextCheckpointDecision.CompactionRequired)
        assertTrue(planner.plan(source.copy(compactionSummary = " ")) is ContextCheckpointDecision.CompactionRequired)
    }

    @Test
    fun `summary is fingerprinted and survives provider projection`() {
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))
        val ready = planner.plan(input(textUnits(6), 210)) as ContextCheckpointDecision.CheckpointReady
        assertEquals(ready.payload.compactionSummary, ready.projection.compactionSummary)
        assertEquals(ready.payload.projectionHash, ready.projection.hash)
        assertThrows(IllegalArgumentException::class.java) {
            ContextCheckpointValidator.validate(ready.payload.copy(compactionSummary = "tampered"))
        }
    }

    @Test
    fun `hundred percent cannot proceed without successful compaction`() {
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))
        val source = input(textUnits(6), 100)
        assertTrue(planner.plan(source.copy(compactionSummary = null)) is ContextCheckpointDecision.CompactionRequired)
        val compacted = planner.plan(source) as ContextCheckpointDecision.CheckpointReady
        assertTrue(compacted.payload.tokenEvidence.tokensBefore >= compacted.payload.tokenEvidence.hardWindowTokens)
        assertTrue(compacted.payload.tokenEvidence.tokensAfter < compacted.payload.tokenEvidence.hardWindowTokens)
    }

    @Test
    fun `request retry can reuse a checkpoint without inventing a new source epoch`() {
        val planner = ContextCheckpointPlanner(FixedTokenEstimator(stableTokens = 10, unitTokens = 20))
        val ready = planner.plan(input(textUnits(6), 210)) as ContextCheckpointDecision.CheckpointReady
        val reused = planner.plan(input(ready.projection.units, 210,
            parentCheckpoint = ContextCheckpointRecord("checkpoint-1", ready.payload),
            currentObservationProjections = ready.projection.observations))
        assertTrue(reused is ContextCheckpointDecision.Unchanged)
    }

    private fun input(
        units: List<ContextUnit>,
        providerWindow: Int,
        recentScreenshotLimit: Int = 1,
        parentCheckpoint: ContextCheckpointRecord? = null,
        currentObservationProjections: List<ObservationProjectionRef> =
            ContextObservationProjection.initial(units)
    ) = ContextCheckpointPlanInput(
        parentCheckpoint = parentCheckpoint,
        stablePrefix = stablePrefix,
        dynamicRequestSuffix = ContextDynamicRequestSuffix.empty(),
        sortedUnits = units,
        currentObservationProjections = currentObservationProjections,
        contextPolicy = policy(providerWindow, recentScreenshotLimit),
        compactionSummary = "Model-authored task summary"
    )

    private fun policy(providerWindow: Int, recentScreenshotLimit: Int) = ContextPolicySnapshot(
        providerContextWindowTokens = providerWindow,
        maxOutputTokens = 10,
        safetyMarginTokens = 0,
        compressionTriggerNumerator = 1,
        compressionTriggerDenominator = 2,
        recentScreenshotLimit = recentScreenshotLimit,
        tokenEstimatorIdentity = FixedTokenEstimator.identity
    )

    private fun textUnits(
        count: Int,
        progressRounds: Set<Int> = emptySet()
    ): List<ContextUnit> = ContextUnitBuilder.build(
        (1..count).flatMap { index ->
            val sequence = index * 2L - 1L
            listOf(
                user(sequence, "round-$index"),
                assistant(sequence + 1L, "round-$index", index in progressRounds)
            )
        }
    )

    private fun screenshotUnits(summaryMissingForRound: Int): List<ContextUnit> {
        val items = mutableListOf<AgentItem>(user(1, "round-1"), assistant(2, "round-1"))
        var sequence = 3L
        for (roundNumber in 2..4) {
            val roundId = "round-$roundNumber"
            val callId = "call-$roundNumber"
            items += call(sequence, roundId, callId)
            items += result(sequence + 1L, roundId, callId)
            items += ObservationRefAgentItem(
                itemId = "item-${sequence + 2L}",
                sequence = sequence + 2L,
                roundId = roundId,
                observationId = "observation-$roundNumber",
                content = ScreenshotObservationContentRef(
                    imageRef = "sha256:image-$roundNumber",
                    textSummaryRef = if (roundNumber == summaryMissingForRound) {
                        null
                    } else {
                        "sha256:summary-$roundNumber"
                    }
                ),
                callId = callId
            )
            sequence += 3L
        }
        return ContextUnitBuilder.build(items)
    }

    private fun user(sequence: Long, roundId: String) = UserInputAgentItem(
        itemId = "item-$sequence",
        sequence = sequence,
        roundId = roundId,
        kind = UserInputKind.MESSAGE,
        text = "request-$sequence"
    )

    private fun assistant(sequence: Long, roundId: String, progress: Boolean = false) =
        AssistantOutputAgentItem(
            itemId = "item-$sequence",
            sequence = sequence,
            roundId = roundId,
            kind = if (progress) PublicMessageKind.PROGRESS else PublicMessageKind.START,
            text = "reply-$sequence"
        )

    private fun call(sequence: Long, roundId: String, callId: String): ToolCallAgentItem {
        val name = "tool-$callId"
        return ToolCallAgentItem(
            itemId = "item-$sequence",
            sequence = sequence,
            roundId = roundId,
            call = PlannedToolCall(
                providerOrder = 0,
                callId = callId,
                providerCall = ProviderToolCall(callId, name, "{}"),
                contract = RegisteredToolContract(
                    name,
                    "$name description",
                    ToolBinding.TURN_LOCAL,
                    ToolEffect.READ_ONLY,
                    emptyMap()
                )
            )
        )
    }

    private fun result(sequence: Long, roundId: String, callId: String) = ToolResultAgentItem(
        itemId = "item-$sequence",
        sequence = sequence,
        roundId = roundId,
        callId = callId,
        result = ToolExecutionResult(
            outputText = "result",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.CAPTURED
        )
    )

    private class FixedTokenEstimator(
        private val stableTokens: Int,
        private val unitTokens: Int,
        override val identity: ContextTokenEstimatorIdentity = FixedTokenEstimator.identity
    ) : ContextTokenEstimator {
        override fun estimateStablePrefix(stablePrefix: com.droidance.core.agent.prompt.StablePrefix): Int = stableTokens

        override fun estimateDynamicSuffix(dynamicSuffix: ContextDynamicRequestSuffix): Int = 5

        override fun estimateUnit(
            unit: ContextUnit,
            observations: Map<Long, ObservationProjectionRef>
        ): Int = unitTokens

        fun copyWithModel(modelId: String) = FixedTokenEstimator(
            stableTokens = stableTokens,
            unitTokens = unitTokens,
            identity = identity.copy(modelId = modelId)
        )

        companion object {
            val identity = ContextTokenEstimatorIdentity(
                transportKind = "generic_chat_completions",
                adapterVersion = "adapter-v1",
                modelId = "model-v1",
                requestSerializerVersion = "serializer-v1",
                tokenizerVersion = "tokenizer-v1",
                imageCostVersion = "image-v1",
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
        }
    }
}
