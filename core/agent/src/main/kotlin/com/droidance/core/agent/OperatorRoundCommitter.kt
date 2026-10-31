// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.attempt.PreparedRoundExecutionPlan
import com.droidance.core.agent.event.*
import com.droidance.core.agent.harness.*
import com.droidance.core.agent.tools.ToolJson
import kotlinx.serialization.json.*
import com.droidance.core.model.llm.models.ToolCall

fun compatibleToolPresentation(
    plannedEffect: ToolEffect,
    configured: com.droidance.core.agent.event.ToolPresentationSnapshot?
): com.droidance.core.agent.event.ToolPresentationSnapshot {
    val compatible = configured != null &&
        ((plannedEffect == ToolEffect.COMPLETION) ==
            (configured.category == com.droidance.core.agent.event.ToolPresentationCategory.COMPLETION))
    if (compatible) return checkNotNull(configured)
    return com.droidance.core.agent.event.ToolPresentationSnapshot(
        labelResourceKey = "tool.unknown",
        category = if (plannedEffect == ToolEffect.COMPLETION) {
            com.droidance.core.agent.event.ToolPresentationCategory.COMPLETION
        } else {
            com.droidance.core.agent.event.ToolPresentationCategory.DIAGNOSTIC
        },
        resultImageRole = ResultImageRole.NONE
    )
}

/** Owns the conversion from scheduled calls to one durable model-round commit. */
class OperatorRoundCommitter(private val coordinator: DurableModelRoundCoordinator) {
    data class Commit(
        val prepared: PreparedRoundExecutionPlan,
        val record: NewlyCommittedModelRoundPlan,
        val publicStartCommitted: Boolean
    )

    suspend fun commit(
        handle: DurableModelRoundCoordinator.RoundHandle,
        scheduler: OperatorRoundScheduler,
        toolPresentations: Map<String, ToolPresentationSnapshot?>,
        presentedToolNames: Set<String>,
        publicBlocks: List<String>,
        calls: List<ToolCall>,
        finishMetadata: Map<String, String>,
        modelParameters: Map<String, Map<String, com.droidance.core.model.llm.models.ToolParameter>>,
        actionTitlesByProviderOrder: Map<Int, String> = emptyMap(),
        mutationsRestricted: Boolean = false
    ): Commit {
        val planned = scheduler.planPrepared(
            roundId = handle.scope.roundId, publicBlocks = publicBlocks, toolCalls = calls,
            startCommitted = handle.startCommitted, repairPurpose = handle.repairPurpose,
            presentedToolNames = presentedToolNames, attemptBindingProvider = { handle.bindingResolution },
            mutationsRestricted = mutationsRestricted, finishMetadata = finishMetadata
        )
        val originalArguments = finishMetadata[ActionControlProtocol.CONTENT_ARGUMENTS_METADATA]
            ?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()
        val recordReceipts = finishMetadata["model_record_receipts"]?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()
        val prepared = PreparedRoundExecutionPlan(
            planned.roundPlan, planned.attemptBinding, planned.candidateCapabilitySnapshotRef,
            planned.candidateCapabilitySnapshotHash, planned.toolStarts, toolRejections = planned.toolRejections.map { rejection ->
            val fileError = finishMetadata["completion_submission_errors"]?.let {
                Json.parseToJsonElement(it).jsonObject[rejection.call.providerCall.providerCallId]?.jsonPrimitive?.content
            }
            val enriched = if (rejection.call.contract.name in setOf("done", "present_result_card") &&
                rejection.result.diagnosticCode == ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH) {
                val control = ActionControlProtocol.Control(rejection.call.providerCall.providerCallId.orEmpty(), null,
                    invalidArgument = if (fileError != null) CompletionSubmissionProtocol.ARGUMENT else "draft",
                    invalidReason = fileError ?: "invalid_draft_body",
                    invalidDetails = finishMetadata["completion_submission_error_details"]?.let {
                        Json.parseToJsonElement(it).jsonObject[rejection.call.providerCall.providerCallId] as? JsonObject
                    },
                    originalArguments = originalArguments[rejection.call.providerOrder.toString()]?.jsonPrimitive?.content
                        ?: rejection.call.providerCall.argumentsJson)
                val bodyParameters = modelParameters.getValue(rejection.call.contract.name)
                val parameters = if (ActionControlProtocol.repairsSubmission(control))
                    CompletionSubmissionProtocol.parameters(bodyParameters) else bodyParameters
                val reference = finishMetadata["completion_candidate_refs"]?.let {
                    Json.parseToJsonElement(it).jsonObject[rejection.call.providerCall.providerCallId] as? JsonObject
                }
                rejection.copy(result = ActionControlProtocol.argumentFailure(control, parameters, reference))
            }
            else if (rejection.result.diagnosticCode != ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH) rejection
            else rejection.copy(result = ToolArgumentSchemaFeedback.forModel(
                rejection.call.contract,
                originalArguments[rejection.call.providerOrder.toString()]?.jsonPrimitive?.content
                    ?: rejection.call.providerCall.argumentsJson,
                modelParameters.getValue(rejection.call.contract.name),
                rejection.result
            ))
            val receipt = recordReceipts[rejection.call.providerCall.providerCallId] as? JsonObject
            if (receipt == null) enriched else enriched.copy(result = GroundedActionProtocol.attachRetentionReceipt(enriched.result, receipt))
        })
        val presentations = (prepared.roundPlan.selectedCalls + prepared.roundPlan.rejectedCalls.map { it.call })
            .associate { planned ->
                val configured = toolPresentations[planned.contract.name]
                planned.callId to compatibleToolPresentation(planned.contract.effect, configured).copy(
                    actionTitle = actionTitlesByProviderOrder[planned.providerOrder]
                )
            }
        val publicStart = prepared.roundPlan.publicMessage?.kind in setOf(
            PublicMessageKind.START, PublicMessageKind.USER_INTERVENTION
        )
        val assistantMessageRequired = publicStart || prepared.roundPlan.publicMessage?.kind == PublicMessageKind.STAGE_RESULT
        val record = coordinator.commit(
            handle = handle,
            normalizedRound = NormalizedModelRound.create(
                publicBlocks = publicBlocks, finishMetadata = finishMetadata,
                toolCalls = calls.map { ProviderToolCall(it.id.takeIf(String::isNotBlank), it.name, ToolJson.sanitizeArgumentsJson(it.arguments)) }
            ),
            preparedExecution = prepared, presentationsByCallId = presentations,
            publicMessageId = if (assistantMessageRequired) "assistant:${handle.scope.roundId}" else null
        )
        val newly = record as? NewlyCommittedModelRoundPlan
            ?: error("A live ModelRound must commit as NewlyCommitted")
        return Commit(prepared, newly, publicStart)
    }
}
