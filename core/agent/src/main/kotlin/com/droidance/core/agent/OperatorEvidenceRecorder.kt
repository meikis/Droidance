// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.tools.ToolCatalog
import com.droidance.core.agent.tools.ToolObservationReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

/** Records evidence provenance for the operator without owning model or tool execution. */
class OperatorEvidenceRecorder @Inject constructor(
    private val evidenceLedger: TaskEvidenceLedger,
    private val evidenceFactExtractor: EvidenceFactExtractor,
    private val textFingerprint: ObservationTextFingerprint
) {

    data class RecordedToolEvidence(
        val validation: com.droidance.core.agent.event.ToolValidationEvidence?,
        val observation: ObservationFrame
    )

    fun recordToolEvidence(
        taskGoal: String,
        toolCall: ToolCall,
        result: ToolExecutionResult,
        blocked: Boolean,
        groundsCompletion: Boolean,
        beforeObservationId: String?
    ): RecordedToolEvidence {
        val validation = result.validation
        val observation = evidenceLedger.recordObservation(
            observationId = ToolObservationReference.fromResult(result).takeIf { groundsCompletion },
            toolName = toolCall.name,
            rawText = result.outputText,
            sourceKind = if (groundsCompletion && !blocked && ToolResultStatus.isSuccess(result)) {
                EvidenceSourceKind.OBSERVED_STATE
            } else {
                EvidenceSourceKind.ACTION_RECEIPT
            },
            foregroundPackage = validation?.foregroundPackage,
            textFingerprint = textFingerprint.buildTextFingerprint(result.outputText),
            progressState = validation?.progressState,
            validationReason = validation?.reason,
            actionCallId = toolCall.id,
            beforeObservationId = beforeObservationId,
            linksNextObservation = !blocked && toolCall.name in ToolCatalog.progressSensitiveToolNames
        )
        if (observation.sourceKind == EvidenceSourceKind.OBSERVED_STATE) {
            evidenceLedger.addFacts(evidenceFactExtractor.extractInline(taskGoal, observation))
        }
        if (!blocked && ToolResultStatus.isSuccess(result)) {
            val srcRef = result.sourceRef ?: ToolObservationReference.fromResult(result)
            if (srcRef != null && (groundsCompletion || toolCall.name in setOf("program", "shell"))) {
                val inputRefs = if (toolCall.name == "program") {
                    val root = result.data as? JsonObject
                    val receipts = root?.get("recent_receipts") as? JsonArray
                    receipts?.mapNotNull {
                        (it as? JsonObject)?.let { receipt -> receipt["source_ref"] ?: receipt["observation_id"] }?.let { id ->
                            (id as? JsonPrimitive)?.contentOrNull
                        }
                    }.orEmpty()
                } else emptyList()
                evidenceLedger.recordDerivedSource(
                    sourceRef = srcRef,
                    toolName = toolCall.name,
                    inputSourceRefs = inputRefs,
                    dataRef = result.dataRef,
                    outputPreview = result.preview ?: result.outputText.take(200)
                )
            }
        }
        return RecordedToolEvidence(validation, observation)
    }

    fun recordModelObservation(
        rawText: String,
        screenshotRef: String
    ) {
        if (rawText.isBlank()) return
        evidenceLedger.recordObservation(
            toolName = "model_observation",
            rawText = rawText,
            sourceKind = EvidenceSourceKind.MODEL_OUTPUT,
            screenshotRef = screenshotRef,
            textFingerprint = textFingerprint.buildTextFingerprint(rawText)
        )
    }

    data class DecisionFrame(val id: String)

    fun recordDecisionFrame(
        taskGoal: String,
        rawText: String?,
        screenshotRef: String,
        observationId: String?,
        frameHash: String?,
        executionScope: String?
    ): DecisionFrame? {
        if (rawText.isNullOrBlank() || screenshotRef.isBlank()) return null
        if (observationId != null && frameHash != null && executionScope != null) {
            return DecisionFrame(evidenceLedger.registerImageEvidence(
                observationId, frameHash, executionScope, rawText, screenshotRef, isCurrentState = true))
        }
        val observation = evidenceLedger.recordObservation(
            observationId = observationId,
            toolName = "model_decision_frame",
            rawText = rawText,
            sourceKind = EvidenceSourceKind.OBSERVED_STATE,
            screenshotRef = screenshotRef,
            textFingerprint = textFingerprint.buildTextFingerprint(rawText)
        )
        evidenceLedger.addFacts(evidenceFactExtractor.extractInline(taskGoal, observation))
        return DecisionFrame(observation.id)
    }

    fun recordObservedState(
        taskGoal: String,
        toolName: String,
        rawText: String
    ): ObservationFrame {
        val observation = evidenceLedger.recordObservation(
            toolName = toolName,
            rawText = rawText,
            sourceKind = EvidenceSourceKind.OBSERVED_STATE,
            textFingerprint = textFingerprint.buildTextFingerprint(rawText)
        )
        evidenceLedger.addFacts(evidenceFactExtractor.extractInline(taskGoal, observation))
        return observation
    }

}
