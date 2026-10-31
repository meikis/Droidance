// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.prompt.StablePrefix
import com.droidance.core.agent.prompt.sha256Hex
import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val CONTEXT_CHECKPOINT_CONTRACT_VERSION = 1

@Serializable
data class ContextTokenEstimatorCoverage(
    val stablePrefix: Boolean,
    val dynamicSuffix: Boolean,
    val providerFraming: Boolean,
    val userAssistantItems: Boolean,
    val toolProtocol: Boolean,
    val observationText: Boolean,
    val observationImages: Boolean
) {
    init {
        require(
            stablePrefix &&
                dynamicSuffix &&
                providerFraming &&
                userAssistantItems &&
                toolProtocol &&
                observationText &&
                observationImages
        ) { "Context token estimator must cover the complete provider request" }
    }
}

@Serializable
data class ContextTokenEstimatorIdentity(
    val transportKind: String,
    val adapterVersion: String,
    val modelId: String,
    val requestSerializerVersion: String,
    val tokenizerVersion: String,
    val imageCostVersion: String,
    val coverage: ContextTokenEstimatorCoverage
) {
    init {
        require(transportKind.isNotBlank()) { "Estimator transport kind cannot be blank" }
        require(adapterVersion.isNotBlank()) { "Estimator adapter version cannot be blank" }
        require(modelId.isNotBlank()) { "Estimator model id cannot be blank" }
        require(requestSerializerVersion.isNotBlank()) {
            "Estimator request serializer version cannot be blank"
        }
        require(tokenizerVersion.isNotBlank()) { "Estimator tokenizer version cannot be blank" }
        require(imageCostVersion.isNotBlank()) { "Estimator image cost version cannot be blank" }
    }
}

@Serializable
data class ContextPolicySnapshot(
    val providerContextWindowTokens: Int,
    val maxOutputTokens: Int,
    val safetyMarginTokens: Int,
    val compressionTriggerNumerator: Int,
    val compressionTriggerDenominator: Int,
    val recentScreenshotLimit: Int,
    val tokenEstimatorIdentity: ContextTokenEstimatorIdentity
) {
    init {
        require(providerContextWindowTokens > 0) { "Provider context window must be positive" }
        require(maxOutputTokens > 0) { "Reserved model output tokens must be positive" }
        require(safetyMarginTokens >= 0) { "Context safety margin cannot be negative" }
        require(
            maxOutputTokens.toLong() + safetyMarginTokens.toLong() < providerContextWindowTokens.toLong()
        ) { "Output reserve and safety margin must leave a positive input window" }
        require(compressionTriggerNumerator > 0) { "Compression trigger numerator must be positive" }
        require(compressionTriggerDenominator > 0) { "Compression trigger denominator must be positive" }
        require(compressionTriggerNumerator <= compressionTriggerDenominator) {
            "Compression trigger ratio cannot exceed one"
        }
        require(recentScreenshotLimit >= 0) { "Recent screenshot limit cannot be negative" }
    }

    val hardInputTokens: Int
        get() = providerContextWindowTokens - maxOutputTokens - safetyMarginTokens

    val triggerTokens: Int
        get() {
            val scaledWindow = hardInputTokens.toLong() * compressionTriggerNumerator
            return ((scaledWindow + compressionTriggerDenominator - 1L) /
                compressionTriggerDenominator).toInt()
        }

    val hash: String
        get() = contextPolicyHash(this)
}

data class ContextDynamicRequestPart(
    val partId: String,
    val canonicalContent: String
) {
    init {
        require(partId.isNotBlank()) { "Dynamic request part id cannot be blank" }
        require(canonicalContent.isNotBlank()) { "Dynamic request part content cannot be blank" }
    }
}

class ContextDynamicRequestSuffix(parts: List<ContextDynamicRequestPart>) {
    val parts: List<ContextDynamicRequestPart> = immutableList(parts)

    init {
        require(this.parts.map(ContextDynamicRequestPart::partId).distinct().size == this.parts.size) {
            "Dynamic request part ids must be unique"
        }
    }

    val hash: String
        get() = dynamicRequestSuffixHash(this)

    companion object {
        fun empty(): ContextDynamicRequestSuffix = ContextDynamicRequestSuffix(emptyList())
    }
}

@Serializable
data class ContextUnitRef(
    val roundId: String,
    val startSequence: Long,
    val endSequence: Long,
    val unitHash: String
)

@Serializable
data class ContextProtectionSnapshot(
    val firstTaskItemSequence: Long,
    val currentTaskStateItemSequence: Long?,
    val latestEvidenceItemSequence: Long?,
    val latestClosedRoundId: String,
    val recentScreenshotItemSequences: List<Long>
)

@Serializable
enum class ObservationProjectionMode {
    TEXT,
    IMAGE,
    TEXT_SUMMARY
}

@Serializable
data class ObservationProjectionRef(
    val itemSequence: Long,
    val observationId: String,
    val mode: ObservationProjectionMode,
    val contentRef: String,
    val sourceContentRef: String? = null,
    val modelContentRef: String? = null
)

@Serializable
data class ObservationProjectionTransition(
    val itemSequence: Long,
    val observationId: String,
    val fromMode: ObservationProjectionMode,
    val fromContentRef: String,
    val toMode: ObservationProjectionMode,
    val toContentRef: String
)

@Serializable
data class ContextUnitTokenEvidence(
    val roundId: String,
    val unitHash: String,
    val tokens: Int
)

@Serializable
data class ContextTokenEvidence(
    val tokensBefore: Int,
    val triggerTokens: Int,
    val stablePrefixTokens: Int,
    val dynamicSuffixTokens: Int,
    val protectedTokens: Int,
    val tokensAfter: Int,
    val hardWindowTokens: Int,
    val protectedUnitTokens: List<ContextUnitTokenEvidence>
)

@Serializable
data class ContextCheckpointPayload(
    val contractVersion: Int,
    val parentCheckpointEventId: String?,
    val sourceThroughSequence: Long,
    val sourceProjectionHash: String,
    val projectionHash: String,
    val stablePrefixHash: String,
    val dynamicRequestSuffixHash: String,
    val contextPolicyHash: String,
    val contextPolicy: ContextPolicySnapshot,
    val retainedUnits: List<ContextUnitRef>,
    val droppedUnits: List<ContextUnitRef>,
    val protection: ContextProtectionSnapshot,
    val observations: List<ObservationProjectionRef>,
    val observationTransitions: List<ObservationProjectionTransition>,
    val tokenEvidence: ContextTokenEvidence,
    val compactionSummary: String? = null
)

data class ContextCheckpointRecord(
    val eventId: String,
    val payload: ContextCheckpointPayload
) {
    init {
        require(eventId.isNotBlank()) { "Context checkpoint event id cannot be blank" }
    }
}

data class ContextCheckpointPlanInput(
    val parentCheckpoint: ContextCheckpointRecord?,
    val stablePrefix: StablePrefix,
    val dynamicRequestSuffix: ContextDynamicRequestSuffix,
    val sortedUnits: List<ContextUnit>,
    val currentObservationProjections: List<ObservationProjectionRef>,
    val contextPolicy: ContextPolicySnapshot,
    val compactionSummary: String? = null,
    val retainedRequestTokens: Int = 0
)

interface ContextTokenEstimator {
    val identity: ContextTokenEstimatorIdentity

    fun estimateSummary(summary: String?): Int = summary?.let {
        estimateDynamicSuffix(ContextDynamicRequestSuffix(listOf(ContextDynamicRequestPart("compaction_summary", it))))
    } ?: 0

    fun estimateStablePrefix(stablePrefix: StablePrefix): Int

    fun estimateDynamicSuffix(dynamicSuffix: ContextDynamicRequestSuffix): Int

    fun estimateUnit(
        unit: ContextUnit,
        observations: Map<Long, ObservationProjectionRef>
    ): Int
}

class ContextProviderProjection internal constructor(
    units: List<ContextUnit>,
    observations: List<ObservationProjectionRef>,
    val compactionSummary: String? = null
) {
    val units: List<ContextUnit> = immutableList(units)
    val observations: List<ObservationProjectionRef> = immutableList(observations)
    val hash: String = contextProjectionHash(this.units.map(ContextUnit::toRef), this.observations, compactionSummary)
}

object ContextObservationProjection {
    /** Builds the first projection before any checkpoint has replaced older images. */
    fun initial(sortedUnits: List<ContextUnit>): List<ObservationProjectionRef> {
        ContextUnitProtector.protect(sortedUnits, emptySet())
        return immutableList(
            projectObservations(
                units = sortedUnits,
                currentBySequence = emptyMap(),
                recentScreenshotSequences = null
            )
        )
    }
}

enum class ContextUnchangedReason {
    BELOW_TRIGGER,
    NO_ELIGIBLE_PROGRESS,
    NO_NEW_CLOSED_ROUND,
    COMPACTION_DEFERRED
}

enum class ContextLimitReason {
    PROTECTED_CONTEXT_EXCEEDS_HARD_WINDOW,
    AFTER_COMPACTION_EXCEEDS_HARD_WINDOW,
    NO_COMPACTION_CAPACITY
}

sealed interface ContextCheckpointDecision {
    data class CompactionRequired(val tokensBefore: Int, val triggerTokens: Int) : ContextCheckpointDecision

    data class Unchanged(
        val reason: ContextUnchangedReason,
        val tokensBefore: Int,
        val triggerTokens: Int
    ) : ContextCheckpointDecision

    data class CheckpointReady(
        val payload: ContextCheckpointPayload,
        val projection: ContextProviderProjection
    ) : ContextCheckpointDecision

    data class ContextLimitExceeded(
        val reason: ContextLimitReason,
        val evidence: ContextTokenEvidence
    ) : ContextCheckpointDecision
}

class ContextCheckpointPlanner(
    private val tokenEstimator: ContextTokenEstimator
) {
    fun plan(input: ContextCheckpointPlanInput): ContextCheckpointDecision {
        validateInput(input)
        val stableTokens = tokenEstimator.estimateStablePrefix(input.stablePrefix).also(::requireTokenCount)
        val dynamicTokens = tokenEstimator.estimateDynamicSuffix(input.dynamicRequestSuffix)
            .also(::requireTokenCount)
        val currentObservations = immutableList(input.currentObservationProjections)
        val tokensBefore = checkedTokenSum(input.retainedRequestTokens.also(::requireTokenCount), listOf(estimateTotal(
            input.sortedUnits,
            currentObservations,
            stableTokens,
            dynamicTokens + tokenEstimator.estimateSummary(input.parentCheckpoint?.payload?.compactionSummary),
            input.contextPolicy.recentScreenshotLimit
        )))
        val triggerTokens = input.contextPolicy.triggerTokens
        if (tokensBefore < triggerTokens) {
            return ContextCheckpointDecision.Unchanged(
                reason = ContextUnchangedReason.BELOW_TRIGGER,
                tokensBefore = tokensBefore,
                triggerTokens = triggerTokens
            )
        }

        if (input.parentCheckpoint?.payload?.sourceThroughSequence == input.sortedUnits.last().endSequence) {
            if (tokensBefore < input.contextPolicy.hardInputTokens) {
                return ContextCheckpointDecision.Unchanged(ContextUnchangedReason.NO_NEW_CLOSED_ROUND, tokensBefore, triggerTokens)
            }
            return ContextCheckpointDecision.ContextLimitExceeded(
                ContextLimitReason.NO_COMPACTION_CAPACITY,
                evidence(tokensBefore, triggerTokens, stableTokens, dynamicTokens,
                    tokensBefore, tokensBefore, input.contextPolicy.hardInputTokens, emptyList())
            )
        }

        val summary = input.compactionSummary?.trim()?.takeIf(String::isNotBlank)
            ?: return ContextCheckpointDecision.CompactionRequired(tokensBefore, triggerTokens)
        val summaryTokens = tokenEstimator.estimateSummary(summary)
        val allItems = input.sortedUnits.flatMap(ContextUnit::items)
        val firstTask = allItems.filterIsInstance<UserInputAgentItem>()
            .firstOrNull { it.kind == UserInputKind.MESSAGE }
            ?: throw IllegalArgumentException("Context projection requires a first task message")
        val currentTaskState = ContextTaskStateSelector.latestClosedTaskState(input.sortedUnits)
        val observations = allItems.filterIsInstance<ObservationRefAgentItem>()
        val latestEvidenceSequence = observations.maxOfOrNull(ObservationRefAgentItem::sequence)
        val currentObservationsBySequence = currentObservations.associateBy(
            ObservationProjectionRef::itemSequence
        )
        val recentScreenshots = HistoricalImageSelection.budgetedSequences(
            currentObservations, input.contextPolicy.recentScreenshotLimit).sorted()
        val latestUnit = input.sortedUnits.last()
        val protectedSequences = buildSet {
            add(firstTask.sequence)
            currentTaskState?.let { add(it.sequence) }
            latestEvidenceSequence?.let(::add)
            addAll(recentScreenshots)
            add(latestUnit.items.last().sequence)
        }
        val protectedUnits = ContextUnitProtector.protect(input.sortedUnits, protectedSequences)
        val projectedObservations = projectObservations(
            units = input.sortedUnits,
            currentBySequence = currentObservationsBySequence,
            recentScreenshotSequences = recentScreenshots.toSet()
        )
        val projectedObservationBudget = observationsForEstimation(
            projectedObservations, input.contextPolicy.recentScreenshotLimit
        )
        val protectedTokenEvidence = protectedUnits.filter(ProtectedContextUnit::isProtected).map { protected ->
            ContextUnitTokenEvidence(
                roundId = protected.unit.roundId,
                unitHash = protected.unit.toRef().unitHash,
                tokens = estimateUnit(protected.unit, projectedObservationBudget)
            )
        }
        val protectedTokens = checkedTokenSum(
            stableTokens,
            listOf(dynamicTokens + summaryTokens) + protectedTokenEvidence.map(ContextUnitTokenEvidence::tokens)
        )
        if (protectedTokens > input.contextPolicy.hardInputTokens) {
            return ContextCheckpointDecision.ContextLimitExceeded(
                reason = ContextLimitReason.PROTECTED_CONTEXT_EXCEEDS_HARD_WINDOW,
                evidence = evidence(
                    tokensBefore = tokensBefore,
                    triggerTokens = triggerTokens,
                    stableTokens = stableTokens,
                    dynamicTokens = dynamicTokens + summaryTokens,
                    protectedTokens = protectedTokens,
                    tokensAfter = estimateTotal(
                        input.sortedUnits,
                        projectedObservations,
                        stableTokens,
                        dynamicTokens,
                        input.contextPolicy.recentScreenshotLimit
                    ),
                    hardWindowTokens = input.contextPolicy.hardInputTokens,
                    protectedUnitTokens = protectedTokenEvidence
                )
            )
        }

        val dropPlan = ContextUnitDropSelector.selectSummarized(protectedUnits)
        val retainedSequences = dropPlan.retained
            .flatMap { unit -> unit.items.map(AgentItem::sequence) }
            .toSet()
        val retainedObservations = projectedObservations.filter { it.itemSequence in retainedSequences }
        val observationTransitions = observationTransitions(
            currentBySequence = currentObservationsBySequence,
            projected = retainedObservations
        )
        val tokensAfter = estimateTotal(
            dropPlan.retained,
            retainedObservations,
            stableTokens,
            dynamicTokens + summaryTokens,
            input.contextPolicy.recentScreenshotLimit
        )
        if (tokensAfter >= tokensBefore || (dropPlan.dropped.isEmpty() && observationTransitions.isEmpty())) {
            return ContextCheckpointDecision.ContextLimitExceeded(
                reason = ContextLimitReason.NO_COMPACTION_CAPACITY,
                evidence = evidence(tokensBefore, triggerTokens, stableTokens, dynamicTokens + summaryTokens,
                    protectedTokens, tokensAfter, input.contextPolicy.hardInputTokens, protectedTokenEvidence)
            )
        }
        if (tokensAfter > input.contextPolicy.hardInputTokens) {
            return ContextCheckpointDecision.ContextLimitExceeded(
                reason = ContextLimitReason.AFTER_COMPACTION_EXCEEDS_HARD_WINDOW,
                evidence = evidence(
                    tokensBefore = tokensBefore,
                    triggerTokens = triggerTokens,
                    stableTokens = stableTokens,
                    dynamicTokens = dynamicTokens + summaryTokens,
                    protectedTokens = protectedTokens,
                    tokensAfter = tokensAfter,
                    hardWindowTokens = input.contextPolicy.hardInputTokens,
                    protectedUnitTokens = protectedTokenEvidence
                )
            )
        }

        val sourceRefs = input.sortedUnits.map(ContextUnit::toRef)
        val retainedRefs = dropPlan.retained.map(ContextUnit::toRef)
        val payload = ContextCheckpointPayload(
            contractVersion = CONTEXT_CHECKPOINT_CONTRACT_VERSION,
            parentCheckpointEventId = input.parentCheckpoint?.eventId,
            sourceThroughSequence = input.sortedUnits.last().endSequence,
            sourceProjectionHash = contextProjectionHash(sourceRefs, currentObservations, input.parentCheckpoint?.payload?.compactionSummary),
            projectionHash = contextProjectionHash(retainedRefs, retainedObservations, summary),
            stablePrefixHash = input.stablePrefix.hash,
            dynamicRequestSuffixHash = input.dynamicRequestSuffix.hash,
            contextPolicyHash = input.contextPolicy.hash,
            contextPolicy = input.contextPolicy,
            retainedUnits = retainedRefs,
            droppedUnits = dropPlan.dropped.map(ContextUnit::toRef),
            protection = ContextProtectionSnapshot(
                firstTaskItemSequence = firstTask.sequence,
                currentTaskStateItemSequence = currentTaskState?.sequence,
                latestEvidenceItemSequence = latestEvidenceSequence,
                latestClosedRoundId = latestUnit.roundId,
                recentScreenshotItemSequences = recentScreenshots
            ),
            observations = retainedObservations,
            observationTransitions = observationTransitions,
            compactionSummary = summary,
            tokenEvidence = evidence(
                tokensBefore = tokensBefore,
                triggerTokens = triggerTokens,
                stableTokens = stableTokens,
                dynamicTokens = dynamicTokens + summaryTokens,
                protectedTokens = protectedTokens,
                tokensAfter = tokensAfter,
                hardWindowTokens = input.contextPolicy.hardInputTokens,
                protectedUnitTokens = protectedTokenEvidence
            )
        )
        ContextCheckpointValidator.validateAgainst(
            payload = payload,
            parentCheckpoint = input.parentCheckpoint,
            stablePrefix = input.stablePrefix,
            dynamicRequestSuffix = input.dynamicRequestSuffix,
            sortedSourceUnits = input.sortedUnits,
            currentObservationProjections = currentObservations
        )
        return ContextCheckpointDecision.CheckpointReady(
            payload = payload,
            projection = ContextProviderProjection(dropPlan.retained, retainedObservations, summary)
        )
    }

    private fun validateInput(input: ContextCheckpointPlanInput) {
        require(input.sortedUnits.isNotEmpty()) { "Context checkpoint requires at least one ContextUnit" }
        ContextUnitProtector.protect(input.sortedUnits, emptySet())
        validateCurrentObservationProjections(
            projections = input.currentObservationProjections,
            sortedUnits = input.sortedUnits
        )
        require(tokenEstimator.identity == input.contextPolicy.tokenEstimatorIdentity) {
            "Frozen context policy estimator identity does not match the active estimator"
        }
        validateSourceLineage(
            parentCheckpoint = input.parentCheckpoint,
            stablePrefixHash = input.stablePrefix.hash,
            contextPolicyHash = input.contextPolicy.hash,
            sortedSourceUnits = input.sortedUnits,
            currentObservationProjections = input.currentObservationProjections
        )
    }

    private fun estimateTotal(
        units: List<ContextUnit>,
        observations: List<ObservationProjectionRef>,
        stableTokens: Int,
        dynamicTokens: Int,
        recentScreenshotLimit: Int
    ): Int {
        val observationBudget = observationsForEstimation(observations, recentScreenshotLimit)
        return checkedTokenSum(
            stableTokens,
            listOf(dynamicTokens) + units.map { unit -> estimateUnit(unit, observationBudget) }
        )
    }

    private fun observationsForEstimation(
        observations: List<ObservationProjectionRef>,
        recentScreenshotLimit: Int
    ): Map<Long, ObservationProjectionRef> {
        val imageSequences = HistoricalImageSelection.budgetedSequences(observations, recentScreenshotLimit)
        // This is an attachment-cost view only. Every original observation reference remains
        // in its ContextUnit and the durable projection, even when its pixels are not sent.
        return observations.filter {
            it.mode != ObservationProjectionMode.IMAGE || it.itemSequence in imageSequences
        }.associateBy(ObservationProjectionRef::itemSequence)
    }

    private fun estimateUnit(
        unit: ContextUnit,
        observations: Map<Long, ObservationProjectionRef>
    ): Int {
        val unitSequences = unit.items.map(AgentItem::sequence).toSet()
        val unitObservations = observations
            .filterKeys { it in unitSequences }
        return tokenEstimator.estimateUnit(unit, unitObservations).also(::requireTokenCount)
    }
}

object ContextCheckpointValidator {
    fun validate(payload: ContextCheckpointPayload) {
        require(payload.contractVersion == CONTEXT_CHECKPOINT_CONTRACT_VERSION) {
            "Unsupported context checkpoint contract version"
        }
        require(payload.parentCheckpointEventId == null || payload.parentCheckpointEventId.isNotBlank()) {
            "Parent checkpoint event id cannot be blank"
        }
        require(payload.sourceThroughSequence > 0) { "Checkpoint source sequence must be positive" }
        requireHash(payload.sourceProjectionHash, "Source projection")
        requireHash(payload.projectionHash, "Provider projection")
        requireHash(payload.stablePrefixHash, "Stable prefix")
        requireHash(payload.dynamicRequestSuffixHash, "Dynamic request suffix")
        requireHash(payload.contextPolicyHash, "Context policy")
        require(payload.contextPolicyHash == payload.contextPolicy.hash) {
            "Checkpoint context policy hash is inconsistent"
        }
        require(payload.retainedUnits.isNotEmpty()) { "Checkpoint must retain at least one ContextUnit" }
        validateUnitRefs(payload.retainedUnits, "Retained")
        validateUnitRefs(payload.droppedUnits, "Dropped")

        val retainedRoundIds = payload.retainedUnits.map(ContextUnitRef::roundId).toSet()
        val droppedRoundIds = payload.droppedUnits.map(ContextUnitRef::roundId).toSet()
        require(retainedRoundIds.intersect(droppedRoundIds).isEmpty()) {
            "Retained and dropped ContextUnits must be disjoint"
        }
        val allRefs = (payload.retainedUnits + payload.droppedUnits).sortedBy(ContextUnitRef::startSequence)
        require(allRefs.zipWithNext().all { (before, after) -> before.endSequence < after.startSequence }) {
            "Checkpoint ContextUnits must be non-overlapping"
        }
        require(allRefs.last().endSequence == payload.sourceThroughSequence) {
            "Checkpoint source sequence must match its ContextUnit partition"
        }
        require(payload.retainedUnits.last().endSequence == payload.sourceThroughSequence) {
            "The latest closed ContextUnit must remain in the cumulative projection"
        }
        require(payload.droppedUnits.isNotEmpty() || payload.observationTransitions.isNotEmpty()) {
            "Checkpoint must make projection progress"
        }

        validateProtection(payload.protection, payload.retainedUnits)
        validateObservations(payload.observations, payload.retainedUnits, payload.protection)
        validateObservationTransitions(payload.observationTransitions, payload.observations)
        require(
            payload.projectionHash == contextProjectionHash(payload.retainedUnits, payload.observations, payload.compactionSummary)
        ) { "Checkpoint provider projection hash is inconsistent" }
        validateTokenEvidence(
            payload.tokenEvidence,
            payload.contextPolicy,
            payload.retainedUnits,
            payload.droppedUnits
        )
    }

    fun validateAgainst(
        payload: ContextCheckpointPayload,
        parentCheckpoint: ContextCheckpointRecord?,
        stablePrefix: StablePrefix,
        dynamicRequestSuffix: ContextDynamicRequestSuffix,
        sortedSourceUnits: List<ContextUnit>,
        currentObservationProjections: List<ObservationProjectionRef>
    ) {
        validate(payload)
        require(sortedSourceUnits.isNotEmpty()) { "Checkpoint source ContextUnits cannot be empty" }
        ContextUnitProtector.protect(sortedSourceUnits, emptySet())
        validateCurrentObservationProjections(currentObservationProjections, sortedSourceUnits)
        validateSourceLineage(
            parentCheckpoint = parentCheckpoint,
            stablePrefixHash = stablePrefix.hash,
            contextPolicyHash = payload.contextPolicyHash,
            sortedSourceUnits = sortedSourceUnits,
            currentObservationProjections = currentObservationProjections
        )
        require(parentCheckpoint == null || payload.sourceThroughSequence > parentCheckpoint.payload.sourceThroughSequence) {
            "A committed checkpoint requires newly closed history"
        }
        require(payload.parentCheckpointEventId == parentCheckpoint?.eventId) {
            "Checkpoint parent does not match the expected durable checkpoint"
        }
        require(payload.stablePrefixHash == stablePrefix.hash) {
            "Checkpoint stable prefix does not match the frozen prefix"
        }
        require(payload.dynamicRequestSuffixHash == dynamicRequestSuffix.hash) {
            "Checkpoint dynamic request suffix hash is inconsistent"
        }

        val actualRefs = sortedSourceUnits.map(ContextUnit::toRef)
        val checkpointRefs = (payload.retainedUnits + payload.droppedUnits)
            .sortedBy(ContextUnitRef::startSequence)
        require(checkpointRefs == actualRefs) {
            "Checkpoint ContextUnit partition does not match its source projection"
        }
        require(payload.sourceThroughSequence == sortedSourceUnits.last().endSequence) {
            "Checkpoint source sequence does not match the latest closed ContextUnit"
        }
        require(
            payload.sourceProjectionHash == contextProjectionHash(actualRefs, currentObservationProjections, parentCheckpoint?.payload?.compactionSummary)
        ) { "Checkpoint source projection hash is inconsistent" }

        val allItems = sortedSourceUnits.flatMap(ContextUnit::items)
        val firstTask = allItems.filterIsInstance<UserInputAgentItem>()
            .firstOrNull { it.kind == UserInputKind.MESSAGE }
            ?: throw IllegalArgumentException("Checkpoint source requires a first task message")
        val expectedTaskState = ContextTaskStateSelector.latestClosedTaskState(sortedSourceUnits)
        val sourceObservations = allItems.filterIsInstance<ObservationRefAgentItem>()
        val latestEvidence = sourceObservations.maxOfOrNull(ObservationRefAgentItem::sequence)
        val currentBySequence = currentObservationProjections.associateBy(
            ObservationProjectionRef::itemSequence
        )
        val recentScreenshots = HistoricalImageSelection.budgetedSequences(
            currentObservationProjections, payload.contextPolicy.recentScreenshotLimit).sorted()
        require(payload.protection.firstTaskItemSequence == firstTask.sequence) {
            "Checkpoint first task anchor does not match its source projection"
        }
        require(payload.protection.currentTaskStateItemSequence == expectedTaskState?.sequence) {
            "Checkpoint current task state must be the latest closed PUBLIC_PROGRESS"
        }
        require(payload.protection.latestEvidenceItemSequence == latestEvidence) {
            "Checkpoint latest evidence anchor does not match its source projection"
        }
        require(payload.protection.latestClosedRoundId == sortedSourceUnits.last().roundId) {
            "Checkpoint latest closed round does not match its source projection"
        }
        require(payload.protection.recentScreenshotItemSequences == recentScreenshots) {
            "Checkpoint recent screenshot anchors do not match its current image projection"
        }

        val fixedSequences = buildSet {
            add(firstTask.sequence)
            expectedTaskState?.let { add(it.sequence) }
            latestEvidence?.let(::add)
            addAll(recentScreenshots)
            add(sortedSourceUnits.last().items.last().sequence)
        }
        val protectedUnits = ContextUnitProtector.protect(sortedSourceUnits, fixedSequences)
        val expectedDropPlan = ContextUnitDropSelector.selectSummarized(protectedUnits)
        require(payload.retainedUnits == expectedDropPlan.retained.map(ContextUnit::toRef)) {
            "Checkpoint cumulative retained projection is not the deterministic drop result"
        }
        require(payload.droppedUnits == expectedDropPlan.dropped.map(ContextUnit::toRef)) {
            "Checkpoint transition-local dropped projection is not deterministic"
        }

        val retainedSequences = expectedDropPlan.retained
            .flatMap { unit -> unit.items.map(AgentItem::sequence) }
            .toSet()
        val expectedObservations = projectObservations(
            units = sortedSourceUnits,
            currentBySequence = currentBySequence,
            recentScreenshotSequences = recentScreenshots.toSet()
        ).filter { it.itemSequence in retainedSequences }
        require(payload.observations == expectedObservations) {
            "Checkpoint observation projection does not match typed source refs"
        }
        val expectedTransitions = observationTransitions(currentBySequence, expectedObservations)
        require(payload.observationTransitions == expectedTransitions) {
            "Checkpoint observation transitions are not the monotonic typed projection"
        }
        require(payload.droppedUnits.isNotEmpty() || expectedTransitions.isNotEmpty()) {
            "Checkpoint did not change its source projection"
        }

        val expectedProtectedRoundIds = protectedUnits
            .filter(ProtectedContextUnit::isProtected)
            .map { it.unit.roundId }
        require(
            payload.tokenEvidence.protectedUnitTokens.map(ContextUnitTokenEvidence::roundId) ==
                expectedProtectedRoundIds
        ) { "Checkpoint protected token evidence does not match protected ContextUnits" }
    }

    private fun validateUnitRefs(refs: List<ContextUnitRef>, label: String) {
        require(refs.map(ContextUnitRef::roundId).distinct().size == refs.size) {
            "$label ContextUnit round ids must be unique"
        }
        require(refs.zipWithNext().all { (before, after) -> before.endSequence < after.startSequence }) {
            "$label ContextUnits must be sorted and non-overlapping"
        }
        refs.forEach { ref ->
            require(ref.roundId.isNotBlank()) { "$label ContextUnit round id cannot be blank" }
            require(ref.startSequence > 0 && ref.startSequence <= ref.endSequence) {
                "$label ContextUnit sequence range is invalid"
            }
            requireHash(ref.unitHash, "$label ContextUnit")
        }
    }

    private fun validateProtection(
        protection: ContextProtectionSnapshot,
        retainedUnits: List<ContextUnitRef>
    ) {
        require(protection.firstTaskItemSequence > 0) { "First task item sequence must be positive" }
        require(
            protection.currentTaskStateItemSequence == null ||
                protection.currentTaskStateItemSequence > 0
        ) { "Current task state item sequence must be positive" }
        require(protection.latestEvidenceItemSequence == null || protection.latestEvidenceItemSequence > 0) {
            "Latest evidence item sequence must be positive"
        }
        require(protection.latestClosedRoundId in retainedUnits.map(ContextUnitRef::roundId)) {
            "Latest closed ContextUnit must be retained"
        }
        require(
            protection.recentScreenshotItemSequences.distinct().size ==
                protection.recentScreenshotItemSequences.size
        ) { "Recent screenshot item sequences must be unique" }
        require(
            protection.recentScreenshotItemSequences.zipWithNext().all { (before, after) -> before < after }
        ) { "Recent screenshot item sequences must be sorted" }
        val retainedRanges = retainedUnits.map { it.startSequence..it.endSequence }
        val anchors = buildList {
            add(protection.firstTaskItemSequence)
            protection.currentTaskStateItemSequence?.let(::add)
            protection.latestEvidenceItemSequence?.let(::add)
            addAll(protection.recentScreenshotItemSequences)
        }
        require(anchors.all { sequence -> retainedRanges.any { sequence in it } }) {
            "Every checkpoint protection anchor must be retained"
        }
    }

    private fun validateObservations(
        observations: List<ObservationProjectionRef>,
        retainedUnits: List<ContextUnitRef>,
        protection: ContextProtectionSnapshot
    ) {
        require(observations.zipWithNext().all { (before, after) -> before.itemSequence < after.itemSequence }) {
            "Observation projections must be sorted by item sequence"
        }
        require(observations.map(ObservationProjectionRef::itemSequence).distinct().size == observations.size) {
            "Observation projection item sequences must be unique"
        }
        require(observations.map(ObservationProjectionRef::observationId).distinct().size == observations.size) {
            "Observation projection ids must be unique"
        }
        val retainedRanges = retainedUnits.map { it.startSequence..it.endSequence }
        observations.forEach { observation ->
            require(observation.itemSequence > 0) { "Observation item sequence must be positive" }
            require(observation.observationId.isNotBlank()) { "Observation id cannot be blank" }
            require(observation.contentRef.isNotBlank()) { "Observation content ref cannot be blank" }
            require(retainedRanges.any { observation.itemSequence in it }) {
                "Observation projection must belong to a retained ContextUnit"
            }
        }
        val projectionsBySequence = observations.associateBy(ObservationProjectionRef::itemSequence)
        protection.recentScreenshotItemSequences.forEach { screenshotSequence ->
            require(projectionsBySequence[screenshotSequence]?.mode == ObservationProjectionMode.IMAGE) {
                "Recent screenshot anchors must retain image projections"
            }
        }
    }

    private fun validateObservationTransitions(
        transitions: List<ObservationProjectionTransition>,
        observations: List<ObservationProjectionRef>
    ) {
        require(transitions.zipWithNext().all { (before, after) -> before.itemSequence < after.itemSequence }) {
            "Observation transitions must be sorted by item sequence"
        }
        require(transitions.map(ObservationProjectionTransition::itemSequence).distinct().size == transitions.size) {
            "Observation transition item sequences must be unique"
        }
        val observationsBySequence = observations.associateBy(ObservationProjectionRef::itemSequence)
        transitions.forEach { transition ->
            require(transition.itemSequence > 0) { "Observation transition sequence must be positive" }
            require(transition.observationId.isNotBlank()) { "Observation transition id cannot be blank" }
            require(transition.fromContentRef.isNotBlank() && transition.toContentRef.isNotBlank()) {
                "Observation transition refs cannot be blank"
            }
            require(
                transition.fromMode == ObservationProjectionMode.IMAGE &&
                    transition.toMode == ObservationProjectionMode.TEXT_SUMMARY
            ) { "Observation projection may only move from IMAGE to TEXT_SUMMARY" }
            val projected = observationsBySequence[transition.itemSequence]
            require(
                projected?.observationId == transition.observationId &&
                    projected.mode == transition.toMode &&
                    projected.contentRef == transition.toContentRef
            ) { "Observation transition target must match the provider projection" }
        }
    }

    private fun validateTokenEvidence(
        evidence: ContextTokenEvidence,
        contextPolicy: ContextPolicySnapshot,
        retainedUnits: List<ContextUnitRef>,
        droppedUnits: List<ContextUnitRef>
    ) {
        val retainedByRoundId = retainedUnits.associateBy(ContextUnitRef::roundId)
        require(evidence.tokensBefore >= 0) { "Pre-checkpoint token evidence cannot be negative" }
        require(evidence.triggerTokens == contextPolicy.triggerTokens) {
            "Checkpoint trigger token evidence is inconsistent"
        }
        require(evidence.tokensBefore >= evidence.triggerTokens) {
            "Checkpoint was created below its trigger"
        }
        require(evidence.stablePrefixTokens >= 0) { "Stable prefix token evidence cannot be negative" }
        require(evidence.dynamicSuffixTokens >= 0) { "Dynamic suffix token evidence cannot be negative" }
        require(evidence.protectedUnitTokens.all { it.tokens >= 0 }) {
            "Protected ContextUnit token evidence cannot be negative"
        }
        require(
            evidence.protectedUnitTokens.map(ContextUnitTokenEvidence::roundId).distinct().size ==
                evidence.protectedUnitTokens.size
        ) { "Protected ContextUnit token evidence must be unique" }
        require(evidence.protectedUnitTokens.all { it.roundId in retainedByRoundId }) {
            "Protected token evidence must belong to retained ContextUnits"
        }
        evidence.protectedUnitTokens.forEach { unitEvidence ->
            requireHash(unitEvidence.unitHash, "Protected ContextUnit")
            require(retainedByRoundId.getValue(unitEvidence.roundId).unitHash == unitEvidence.unitHash) {
                "Protected token evidence fingerprint must match its retained ContextUnit"
            }
        }
        require(
            checkedTokenSum(
                evidence.stablePrefixTokens,
                listOf(evidence.dynamicSuffixTokens) +
                    evidence.protectedUnitTokens.map(ContextUnitTokenEvidence::tokens)
            ) == evidence.protectedTokens
        ) { "Protected token evidence total is inconsistent" }
        require(evidence.protectedTokens <= evidence.tokensAfter) {
            "Post-checkpoint tokens cannot be smaller than protected tokens"
        }
        require(evidence.tokensAfter <= evidence.hardWindowTokens) {
            "A valid checkpoint cannot exceed the hard context window"
        }
        require(evidence.hardWindowTokens == contextPolicy.hardInputTokens) {
            "Hard context window evidence is inconsistent"
        }
    }
}

private fun validateSourceLineage(
    parentCheckpoint: ContextCheckpointRecord?,
    stablePrefixHash: String,
    contextPolicyHash: String,
    sortedSourceUnits: List<ContextUnit>,
    currentObservationProjections: List<ObservationProjectionRef>
) {
    if (parentCheckpoint == null) {
        require(currentObservationProjections == ContextObservationProjection.initial(sortedSourceUnits)) {
            "A genesis projection must use typed initial observation refs"
        }
        return
    }

    ContextCheckpointValidator.validate(parentCheckpoint.payload)
    val parent = parentCheckpoint.payload
    require(parent.stablePrefixHash == stablePrefixHash) {
        "A child checkpoint cannot change its frozen stable prefix"
    }
    require(parent.contextPolicyHash == contextPolicyHash) {
        "A child checkpoint cannot change its frozen context policy"
    }
    require(sortedSourceUnits.last().endSequence >= parent.sourceThroughSequence) {
        "Checkpoint source sequence must strictly advance beyond its parent"
    }
    require(sortedSourceUnits.none {
        it.startSequence <= parent.sourceThroughSequence && it.endSequence > parent.sourceThroughSequence
    }) { "A ContextUnit cannot straddle the parent checkpoint source boundary" }

    val inheritedUnits = sortedSourceUnits.takeWhile {
        it.endSequence <= parent.sourceThroughSequence
    }
    val appendedUnits = sortedSourceUnits.drop(inheritedUnits.size)
    require(inheritedUnits.map(ContextUnit::toRef) == parent.retainedUnits) {
        "Checkpoint source must start with its parent's full retained projection"
    }
    require(appendedUnits.all {
        it.startSequence > parent.sourceThroughSequence
    }) { "A child checkpoint requires newly closed ContextUnits after its parent source"
    }

    val inheritedObservations = currentObservationProjections.filter {
        it.itemSequence <= parent.sourceThroughSequence
    }
    val appendedObservations = currentObservationProjections.filter {
        it.itemSequence > parent.sourceThroughSequence
    }
    require(inheritedObservations == parent.observations) {
        "Parent observation projection must be applied cumulatively and without rehydration"
    }
    require(appendedObservations == ContextObservationProjection.initial(appendedUnits)) {
        "Newly closed ContextUnits must enter with typed initial observation refs"
    }
}

private fun projectObservations(
    units: List<ContextUnit>,
    currentBySequence: Map<Long, ObservationProjectionRef>,
    recentScreenshotSequences: Set<Long>?
): List<ObservationProjectionRef> = units
    .flatMap(ContextUnit::items)
    .filterIsInstance<ObservationRefAgentItem>()
    .sortedBy(ObservationRefAgentItem::sequence)
    .map { observation ->
        when (val content = observation.content) {
            is TextObservationContentRef -> ObservationProjectionRef(
                itemSequence = observation.sequence,
                observationId = observation.observationId,
                mode = ObservationProjectionMode.TEXT,
                contentRef = content.textRef
            )
            is ScreenshotObservationContentRef -> {
                val current = currentBySequence[observation.sequence]
                val alreadySummarized = current?.mode == ObservationProjectionMode.TEXT_SUMMARY
                val keepImage = !alreadySummarized && (
                    recentScreenshotSequences == null ||
                        observation.sequence in recentScreenshotSequences ||
                        content.textSummaryRef == null
                    )
                if (keepImage) {
                    ObservationProjectionRef(
                        itemSequence = observation.sequence,
                        observationId = observation.observationId,
                        mode = ObservationProjectionMode.IMAGE,
                        contentRef = content.imageRef,
                        sourceContentRef = content.sourceContentRef,
                        modelContentRef = content.modelContentRef
                    )
                } else {
                    ObservationProjectionRef(
                        itemSequence = observation.sequence,
                        observationId = observation.observationId,
                        mode = ObservationProjectionMode.TEXT_SUMMARY,
                        contentRef = checkNotNull(content.textSummaryRef) {
                            "Screenshot without a text summary cannot be compacted"
                        },
                        sourceContentRef = content.sourceContentRef,
                        modelContentRef = content.modelContentRef
                    )
                }
            }
        }
    }

private fun observationTransitions(
    currentBySequence: Map<Long, ObservationProjectionRef>,
    projected: List<ObservationProjectionRef>
): List<ObservationProjectionTransition> = projected.mapNotNull { next ->
    val current = currentBySequence.getValue(next.itemSequence)
    if (current == next) {
        null
    } else {
        require(
            current.observationId == next.observationId &&
                current.mode == ObservationProjectionMode.IMAGE &&
                next.mode == ObservationProjectionMode.TEXT_SUMMARY
        ) { "Observation projection may only move from IMAGE to TEXT_SUMMARY" }
        ObservationProjectionTransition(
            itemSequence = next.itemSequence,
            observationId = next.observationId,
            fromMode = current.mode,
            fromContentRef = current.contentRef,
            toMode = next.mode,
            toContentRef = next.contentRef
        )
    }
}

private fun validateCurrentObservationProjections(
    projections: List<ObservationProjectionRef>,
    sortedUnits: List<ContextUnit>
) {
    val sourceObservations = sortedUnits
        .flatMap(ContextUnit::items)
        .filterIsInstance<ObservationRefAgentItem>()
        .sortedBy(ObservationRefAgentItem::sequence)
    require(projections.size == sourceObservations.size) {
        "Current observation projection must cover every source observation exactly once"
    }
    projections.zip(sourceObservations).forEach { (projection, source) ->
        require(projection.itemSequence == source.sequence && projection.observationId == source.observationId) {
            "Current observation projection identity does not match its source observation"
        }
        val matchesTypedSource = when (val content = source.content) {
            is TextObservationContentRef ->
                projection.mode == ObservationProjectionMode.TEXT && projection.contentRef == content.textRef &&
                    projection.sourceContentRef == null && projection.modelContentRef == null
            is ScreenshotObservationContentRef ->
                projection.sourceContentRef == content.sourceContentRef && projection.modelContentRef == content.modelContentRef &&
                    ((projection.mode == ObservationProjectionMode.IMAGE && projection.contentRef == content.imageRef) ||
                    (content.textSummaryRef != null &&
                        projection.mode == ObservationProjectionMode.TEXT_SUMMARY &&
                        projection.contentRef == content.textSummaryRef))
        }
        require(matchesTypedSource) {
            "Current observation projection is incompatible with its typed source ref"
        }
    }
}

internal fun ContextUnit.toRef(): ContextUnitRef = ContextUnitRef(
    roundId = roundId,
    startSequence = startSequence,
    endSequence = endSequence,
    unitHash = sha256Hex(JsonArray(items.map(AgentItem::fingerprintJson)).toString())
)

private fun AgentItem.fingerprintJson() = buildJsonObject {
    put("itemId", itemId)
    put("sequence", sequence)
    put("roundId", roundId)
    when (val item = this@fingerprintJson) {
        is UserInputAgentItem -> {
            put("type", "user_input")
            put("kind", item.kind.name)
            put("text", item.text)
        }
        is AssistantOutputAgentItem -> {
            put("type", "assistant_output")
            put("kind", item.kind.name)
            put("text", item.text)
        }
        is ToolCallAgentItem -> {
            put("type", "tool_call")
            put("providerOrder", item.call.providerOrder)
            put("callId", item.call.callId)
            put("providerCallId", item.call.providerCall.providerCallId.orEmpty())
            put("toolName", item.call.providerCall.name)
            put("argumentsJson", item.call.providerCall.argumentsJson)
            put("binding", item.call.contract.binding.name)
            put("effect", item.call.contract.effect.name)
        }
        is ToolResultAgentItem -> {
            put("type", "tool_result")
            put("callId", item.callId)
            put("outputText", item.result.outputText)
            put("executionStatus", item.result.executionStatus.name)
            put("evidenceStatus", item.result.evidenceStatus.name)
            put("observationId", item.result.observationId.orEmpty())
            put("diagnosticCode", item.result.diagnosticCode.orEmpty())
            put("metrics", buildJsonObject {
                item.result.metrics.toSortedMap().forEach { (name, value) -> put(name, value) }
            })
        }
        is ObservationRefAgentItem -> {
            put("type", "observation_ref")
            put("observationId", item.observationId)
            put("callId", item.callId.orEmpty())
            when (val content = item.content) {
                is TextObservationContentRef -> {
                    put("contentType", "text")
                    put("textRef", content.textRef)
                }
                is ScreenshotObservationContentRef -> {
                    put("contentType", "screenshot")
                    put("imageRef", content.imageRef)
                    put("textSummaryRef", content.textSummaryRef.orEmpty())
                    content.sourceObservationId?.let { put("sourceObservationId", it) }
                    content.sourceContentRef?.let { put("sourceContentRef", it) }
                    content.modelContentRef?.let { put("modelContentRef", it) }
                    content.sourceRegion?.let { put("sourceRegion", Json.encodeToJsonElement(it)) }
                }
            }
        }
    }
}

private fun contextProjectionHash(
    units: List<ContextUnitRef>,
    observations: List<ObservationProjectionRef>,
    summary: String? = null
): String = sha256Hex(buildJsonObject {
    summary?.let { put("compactionSummary", it) }
    put("units", JsonArray(units.map { unit ->
        buildJsonObject {
            put("roundId", unit.roundId)
            put("startSequence", unit.startSequence)
            put("endSequence", unit.endSequence)
            put("unitHash", unit.unitHash)
        }
    }))
    put("observations", JsonArray(observations.map { observation ->
        buildJsonObject {
            put("itemSequence", observation.itemSequence)
            put("observationId", observation.observationId)
            put("mode", observation.mode.name)
            put("contentRef", observation.contentRef)
            observation.sourceContentRef?.let { put("sourceContentRef", it) }
            observation.modelContentRef?.let { put("modelContentRef", it) }
        }
    }))
}.toString())

private fun contextPolicyHash(policy: ContextPolicySnapshot): String = sha256Hex(buildJsonObject {
    put("providerContextWindowTokens", policy.providerContextWindowTokens)
    put("maxOutputTokens", policy.maxOutputTokens)
    put("safetyMarginTokens", policy.safetyMarginTokens)
    put("compressionTriggerNumerator", policy.compressionTriggerNumerator)
    put("compressionTriggerDenominator", policy.compressionTriggerDenominator)
    put("recentScreenshotLimit", policy.recentScreenshotLimit)
    put("tokenEstimatorIdentity", buildJsonObject {
        val identity = policy.tokenEstimatorIdentity
        put("transportKind", identity.transportKind)
        put("adapterVersion", identity.adapterVersion)
        put("modelId", identity.modelId)
        put("requestSerializerVersion", identity.requestSerializerVersion)
        put("tokenizerVersion", identity.tokenizerVersion)
        put("imageCostVersion", identity.imageCostVersion)
        put("coverage", buildJsonObject {
            put("stablePrefix", identity.coverage.stablePrefix)
            put("dynamicSuffix", identity.coverage.dynamicSuffix)
            put("providerFraming", identity.coverage.providerFraming)
            put("userAssistantItems", identity.coverage.userAssistantItems)
            put("toolProtocol", identity.coverage.toolProtocol)
            put("observationText", identity.coverage.observationText)
            put("observationImages", identity.coverage.observationImages)
        })
    })
}.toString())

private fun dynamicRequestSuffixHash(suffix: ContextDynamicRequestSuffix): String = sha256Hex(
    JsonArray(suffix.parts.map { part ->
        buildJsonObject {
            put("partId", part.partId)
            put("canonicalContent", part.canonicalContent)
        }
    }).toString()
)

private fun evidence(
    tokensBefore: Int,
    triggerTokens: Int,
    stableTokens: Int,
    dynamicTokens: Int,
    protectedTokens: Int,
    tokensAfter: Int,
    hardWindowTokens: Int,
    protectedUnitTokens: List<ContextUnitTokenEvidence>
) = ContextTokenEvidence(
    tokensBefore = tokensBefore,
    triggerTokens = triggerTokens,
    stablePrefixTokens = stableTokens,
    dynamicSuffixTokens = dynamicTokens,
    protectedTokens = protectedTokens,
    tokensAfter = tokensAfter,
    hardWindowTokens = hardWindowTokens,
    protectedUnitTokens = protectedUnitTokens
)

private fun requireTokenCount(tokens: Int) {
    require(tokens >= 0) { "Token estimates cannot be negative" }
}

private fun checkedTokenSum(stableTokens: Int, remainingTokens: List<Int>): Int {
    requireTokenCount(stableTokens)
    remainingTokens.forEach(::requireTokenCount)
    val total = stableTokens.toLong() + remainingTokens.sumOf(Int::toLong)
    require(total <= Int.MAX_VALUE) { "Token estimate exceeds the supported integer range" }
    return total.toInt()
}

private fun requireHash(hash: String, label: String) {
    require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label hash must be lowercase SHA-256"
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
