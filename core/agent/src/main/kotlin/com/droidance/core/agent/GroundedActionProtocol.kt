// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.agent.tools.WorkspaceRecordProtocol
import kotlinx.serialization.json.*
import com.droidance.core.agent.event.ToolExecutionResult

/**
 * Retains optional model-authored object records against original sources supplied by the harness.
 * Source binding preserves provenance; it does not independently verify the model's wording.
 * The protocol-only field is removed before calls enter tool history or execution.
 */
object GroundedActionProtocol {
    const val SCREEN_EVIDENCE_ARGUMENT = "screen_evidence"
    const val RECORD_REPAIR_ARGUMENT = "record_repair_ref"

    data class GroundedEvidence(
        val toolCallId: String,
        val records: JsonArray
    )

    data class Result(
        val evidence: List<GroundedEvidence>,
        val retentionOutcomes: Map<String, RetentionOutcome>,
        val toolCalls: List<ToolCall>
    )

    data class RetentionOutcome(val records: JsonArray?, val errorCode: String? = null,
                                val rejectedUpdates: JsonArray = JsonArray(emptyList()),
                                val rejectedRecords: JsonElement? = null, val errorPath: String? = null,
                                val repairOf: String? = null) {
        fun receipt(roundRef: String, providerCallId: String): JsonObject = buildJsonObject {
            put("status", if (records != null) "saved" else "rejected")
            put("round_ref", roundRef)
            put("storage", "model_round_event")
            put("repair_ref", repairOf ?: "$roundRef:record:$providerCallId")
            repairOf?.let { put("repair_of", it) }
            errorPath?.let { put("error_path", it) }
            put("semantics_verified", false)
            if (errorCode != null) put("reason", errorCode)
            put("atomic_per_call", true)
            if (records == null) {
                put("rejected_updates", rejectedUpdates)
                rejectedRecords?.let { put("rejected_records", it) }
            }
            put("updates", JsonArray(records.orEmpty().map { element ->
                val record = element.jsonObject
                buildJsonObject {
                    put("record_id", record.getValue("record_id"))
                    put("field_names", JsonArray(record.getValue("fields").jsonObject.keys.map(::JsonPrimitive)))
                }
            }))
        }
    }

    /** Called only after the round commits, or inside its atomic rejection commit. */
    fun attachRetentionReceipt(result: ToolExecutionResult, outcome: RetentionOutcome?, roundRef: String, providerCallId: String): ToolExecutionResult {
        if (outcome == null) return result
        return attachRetentionReceipt(result, outcome.receipt(roundRef, providerCallId))
    }

    fun attachRetentionReceipt(result: ToolExecutionResult, receipt: JsonObject): ToolExecutionResult {
        val suffix = "\n[model_record_receipt]\n$receipt"
        if (result.outputText.endsWith(suffix)) return result
        return result.copy(outputText = result.outputText + suffix)
    }

    fun addScreenEvidenceParameter(
        tools: List<ToolDefinition>,
        parameterDescription: String,
        applyTo: Set<String>
    ): List<ToolDefinition> = tools.map { tool ->
        if (tool.name !in applyTo) return@map tool
        tool.copy(
            parameters = tool.parameters + (
                SCREEN_EVIDENCE_ARGUMENT to WorkspaceRecordProtocol.parameter.copy(
                    description = parameterDescription, required = false,
                    requiredWhen = emptyMap(), allowedWhen = emptyMap()
                )
            ) + (RECORD_REPAIR_ARGUMENT to com.droidance.core.model.llm.models.ToolParameter(
                "string", "Exact pending repair_ref being resolved by this screen_evidence update.", false))
        )
    }

    fun extractScreenEvidence(
        toolCalls: List<ToolCall>,
        pendingRepairs: Map<String, JsonObject> = emptyMap(),
        sourceExists: (String) -> Boolean
    ): Result {
        val evidence = mutableListOf<GroundedEvidence>()
        val outcomes = linkedMapOf<String, RetentionOutcome>()
        val sanitized = toolCalls.map { toolCall ->
            val arguments = runCatching {
                Json.parseToJsonElement(toolCall.arguments).jsonObject
            }.getOrNull() ?: return@map toolCall
            val value = arguments[SCREEN_EVIDENCE_ARGUMENT]
            val repairValue = arguments[RECORD_REPAIR_ARGUMENT]
            if (value != null || repairValue != null) {
                val repairRef = (repairValue as? JsonPrimitive)?.takeIf { it.isString }?.content
                val knownRepair = repairRef?.takeIf { it in pendingRepairs }
                val validated = runCatching {
                    if (repairValue != null && knownRepair == null)
                        throw WorkspaceRecordProtocol.RecordValidationException("RECORD_REPAIR_REF_UNAVAILABLE", RECORD_REPAIR_ARGUMENT)
                    val recordsArray = value as? JsonArray
                        ?: throw WorkspaceRecordProtocol.RecordValidationException("RECORDS_ARRAY_REQUIRED", SCREEN_EVIDENCE_ARGUMENT)
                    val originalCount = (pendingRepairs[knownRepair]?.get("rejected_records") as? JsonArray)?.size
                    if (originalCount != null && originalCount > 0 && recordsArray.size < originalCount)
                        throw WorkspaceRecordProtocol.RecordValidationException("RECORD_REPAIR_COUNT_MISMATCH", SCREEN_EVIDENCE_ARGUMENT)
                    WorkspaceRecordProtocol.merge(null, recordsArray, sourceExists)["records"] as JsonArray
                }
                val records = validated.getOrNull()
                if (records == null) {
                    val error = validated.exceptionOrNull()
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val code = error?.message?.takeIf { it.startsWith("RECORD") && it.all { c -> c.isUpperCase() || c == '_' } }
                        ?: "RECORD_FORMAT_INVALID"
                    val rejected = JsonArray((value as? JsonArray).orEmpty().mapIndexed { index, item -> buildJsonObject {
                        put("record_index", index)
                        val record = item as? JsonObject
                        (record?.get("record_id") as? JsonPrimitive)?.takeIf { it.isString }?.let { put("record_id", it) }
                        (record?.get("fields") as? JsonObject)?.let { fields ->
                            put("field_names", JsonArray(fields.keys.map(::JsonPrimitive)))
                        }
                    } })
                    outcomes[toolCall.id] = RetentionOutcome(null, code, rejected, value,
                        (error as? WorkspaceRecordProtocol.RecordValidationException)?.path, knownRepair)
                } else {
                    evidence += GroundedEvidence(toolCall.id, records)
                    outcomes[toolCall.id] = RetentionOutcome(records, repairOf = knownRepair)
                }
            }
            // An optional note is independent of action delivery; malformed notes cannot
            // reject or silently certify an otherwise valid device operation.
            toolCall.copy(arguments = JsonObject(arguments.filterKeys {
                it != SCREEN_EVIDENCE_ARGUMENT && it != RECORD_REPAIR_ARGUMENT
            }).toString())
        }
        return Result(evidence, outcomes, sanitized)
    }
}
