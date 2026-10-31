// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class DurationSamples(
    val sampleCount: Int,
    val totalMs: Long,
    val minMs: Long,
    val maxMs: Long
)

/** Scoped Agent reports, never independent proof of task success. */
@Serializable
internal data class SkillRunMetrics(
    val runId: String,
    val name: String,
    val revision: String,
    val draft: Boolean,
    val selection: String,
    val scripted: Boolean,
    val resumed: Boolean,
    val assessment: String = "unassessed",
    val evidenceKind: String? = null,
    val assessmentElapsedMs: Long? = null
)

/** Mechanical facts only. A terminal status is never a semantic assessment. */
@Serializable
internal data class TaskAnalysisMetrics(
    val scope: String = "single_turn",
    val semanticSuccess: Boolean? = null,
    val semanticAssessment: String = "not_evaluated",
    val runtimeBuildVersions: List<String>,
    val currentEditorReadbacksVerified: Int,
    val targetFocusBindingsVerified: Int,
    val currentEditorReadbackMetricsAvailable: Int,
    val targetFocusBindingMetricsAvailable: Int,
    val modelIdentities: List<Map<String, String>>,
    val returnedModels: List<String>,
    val reportedInputTokens: Long?,
    val reportedOutputTokens: Long?,
    val reportedReasoningTokens: Long?,
    val promptCache: PromptCacheMetrics,
    val preparationStageDurations: Map<String, DurationSamples>,
    val modelStageDurations: Map<String, DurationSamples>,
    val observationStageDurations: Map<String, DurationSamples>,
    val measuredUsageRequests: Int,
    val modelRounds: Int,
    val toolCalls: Int,
    val rejectedCalls: Int,
    val completionReviewRequests: Int,
    val rejectedCallDiagnosticCounts: Map<String, Int>,
    val failedCalls: Int,
    val executionStatusCounts: Map<String, Int>,
    val diagnosticCounts: Map<String, Int>,
    val outputLimitFailures: Int,
    val requestedOutputBudgets: List<Int>,
    val verifiedTextDeliveries: Int,
    val unverifiedTextDeliveries: Int,
    val textDeliveryMetricsAvailable: Int,
    val textInputStageDurations: Map<String, DurationSamples>,
    val maxReportedInputTokensPerRequest: Long?,
    val modelRequestDurationMs: Long?,
    val measuredModelRequests: Int,
    val toolDurationMs: Long,
    val totalDurationMs: Long?,
    val humanWaitDurationMs: Long,
    val activeDurationMs: Long?,
    val unclosedHumanWait: Boolean,
    val unclosedToolIntervals: Int,
    val batchPrimitiveAttempts: Long,
    val batchPrimitiveSucceededReceipts: Long,
    val batchExecutionDurationMs: Long,
    val screenActionBatchCalls: Long,
    val scalarScreenActionCalls: Long,
    val batchRequestedActions: Long,
    val batchCompletedActions: Long,
    val batchUnexecutedActions: Long,
    val batchStopDiagnosticCounts: Map<String, Int>,
    val actionGroupFinalObservations: Long,
    val actionGroupCaptureSamples: Long,
    val actionGroupObservationDurationMs: Long,
    val preActionSamplingDurationMs: Long,
    val frameWaitDurationMs: Long,
    val experienceDetailHits: Long,
    val experienceVersionMismatches: Long,
    /** Recall facts only: injected method refs, explicit searches and experience tool receipts. */
    val memoryContextLoads: Int,
    val experienceContextRefs: List<String>,
    val experienceSearchCalls: Int,
    val experienceToolOutcomes: Map<String, Int>,
    val experienceCompletionProposals: Int,
    val programResumeDecisions: Map<String, Int>,
    val programChildDiagnosticCounts: Map<String, Int>,
    val skillSelections: List<Map<String, String>>,
    val skillRuns: List<SkillRunMetrics>,
    val skillFileReads: Long,
    val skillAssessmentReads: Long,
    val skillReadChars: Long
) {
    companion object {
        fun project(events: List<AgentEventEnvelope>): TaskAnalysisMetrics {
            val identities = linkedSetOf<Map<String, String>>()
            val returnedModels = sortedSetOf<String>()
            val builds = sortedSetOf<String>()
            var editorVerified = 0
            var targetVerified = 0
            var editorMeasured = 0
            var targetMeasured = 0
            val inputs = mutableListOf<Long>()
            val outputs = mutableListOf<Long>()
            val reasoningTokens = mutableListOf<Long>()
            val cacheReads = mutableListOf<Long>()
            val cacheWrites = mutableListOf<Long>()
            var cacheMeasuredInput = 0L
            var cacheRatioRequests = 0
            var cacheRatioRead = 0L
            var localReuses = 0
            val prefixResets = sortedMapOf<String, Int>()
            val preparationStages = sortedMapOf<String, MutableList<Long>>()
            val modelStages = sortedMapOf<String, MutableList<Long>>()
            val observationStages = sortedMapOf<String, MutableList<Long>>()
            val statuses = sortedMapOf<String, Int>()
            val diagnostics = sortedMapOf<String, Int>()
            val rejections = sortedMapOf<String, Int>()
            val inputStages = sortedMapOf<String, MutableList<Long>>()
            val starts = mutableMapOf<String, Long>()
            val durations = mutableListOf<Long>()
            val budgets = sortedSetOf<Int>()
            var batchAttempts = 0L
            var batchSucceeded = 0L
            var batchMs = 0L
            var batchCalls = 0L
            var scalarCalls = 0L
            var batchRequested = 0L
            var batchCompleted = 0L
            var batchUnexecuted = 0L
            var finalObservations = 0L
            var captureSamples = 0L
            var groupObservationMs = 0L
            val batchStops = sortedMapOf<String, Int>()
            var samplingMs = 0L
            var frameWaitMs = 0L
            var experienceHits = 0L
            var experienceMismatches = 0L
            var memoryLoads = 0
            val experienceRefs = linkedSetOf<String>()
            var experienceSearches = 0
            val experienceCalls = mutableMapOf<String, String>()
            val experienceOutcomes = sortedMapOf<String, Int>()
            var experienceProposals = 0
            val resumeDecisions = sortedMapOf<String, Int>()
            val childDiagnostics = sortedMapOf<String, Int>()
            val skillSelections = linkedSetOf<Map<String, String>>()
            val skillRuns = linkedMapOf<String, SkillRunMetrics>()
            val skillStarts = mutableMapOf<String, Long>()
            var skillFileReads = 0L
            var skillAssessmentReads = 0L
            var skillReadChars = 0L
            val settledCalls = events.filter { it.eventKind in setOf(AgentEventKind.TOOL_FINISHED, AgentEventKind.TOOL_CALL_REJECTED) }.mapNotNull { it.callId }.toSet()
            var pausedAt: Long? = null
            var humanWaitMs = 0L
            var toolMs = 0L
            var totalMs: Long? = null
            var verified = 0
            var unverified = 0
            var outputLimits = 0
            var completionReviews = 0
            fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
            fun JsonObject.number(key: String) = text(key)?.toLongOrNull()
            fun count(map: MutableMap<String, Int>, key: String?) {
                if (!key.isNullOrBlank()) map[key] = (map[key] ?: 0) + 1
            }
            fun accumulateInputMetrics(metrics: JsonObject?) {
                listOf("focus", "injection", "readback", "observation", "total").forEach { stage ->
                    metrics?.number("text_input_${stage}_ms")?.takeIf { it >= 0 }?.let {
                        inputStages.getOrPut(stage) { mutableListOf() }.add(it)
                    }
                }
                metrics?.number("text_input_current_editor_readback_verified")?.let {
                    editorMeasured++; if (it == 1L) editorVerified++
                }
                metrics?.number("text_input_target_focus_bound")?.let {
                    targetMeasured++; if (it == 1L) targetVerified++
                }
                when (metrics?.number("text_input_delivery_verified")) {
                    1L -> verified++
                    0L -> unverified++
                }
            }
            fun accumulateExecutionMetrics(result: JsonObject?) {
                val metrics = result?.get("metrics") as? JsonObject
                metrics.orEmpty().filterKeys { key -> key.endsWith("_ms") &&
                    (key.startsWith("observation_") || key == "tap_delivery_ms" || key == "gesture_delivery_ms")
                }.forEach { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let {
                    observationStages.getOrPut(key) { mutableListOf() }.add(it)
                } }
                // A batch retains per-member metrics plus the last member's top-level receipt.
                // Use members when present so the last input is not counted twice.
                val inputPrefixes = metrics.orEmpty().keys.filter {
                    it.startsWith("action_") && "_text_input_" in it
                }.map { it.substringBefore("_text_input_") + "_" }.distinct()
                if (inputPrefixes.isEmpty()) accumulateInputMetrics(metrics)
                else inputPrefixes.forEach { prefix -> accumulateInputMetrics(JsonObject(metrics.orEmpty()
                    .filterKeys { it.startsWith(prefix + "text_input_") }
                    .mapKeys { (key, _) -> key.removePrefix(prefix) })) }
                batchCalls += metrics?.number("screen_action_batch_calls") ?: 0
                scalarCalls += metrics?.number("screen_action_scalar_calls") ?: 0
                val requested = metrics?.number("batch_requested_actions")
                val completed = metrics?.number("batch_completed_actions")
                batchRequested += requested ?: 0
                batchCompleted += completed ?: 0
                batchUnexecuted += metrics?.number("batch_unexecuted_actions") ?: 0
                if (requested != null && completed != null && completed < requested)
                    count(batchStops, result.text("diagnosticCode") ?: "UNKNOWN")
                finalObservations += metrics?.number("action_group_final_observations") ?: 0
                captureSamples += metrics?.number("action_group_capture_samples") ?: 0
                groupObservationMs += metrics?.number("action_group_observation_ms") ?: 0
                batchAttempts += metrics?.number("batch_primitive_attempts") ?: 0
                batchSucceeded += metrics?.number("batch_primitive_succeeded_receipts") ?: 0
                batchMs += metrics?.number("batch_execution_ms") ?: 0
                samplingMs += metrics.orEmpty().filterKeys { it == "pre_action_observation_check_ms" ||
                    (it.startsWith("action_") && it.endsWith("_pre_action_observation_check_ms")) }
                    .values.sumOf { (it as? JsonPrimitive)?.longOrNull ?: 0L }
                frameWaitMs += metrics?.number("observation_wait_ms") ?: 0
                experienceHits += metrics?.number("experience_detail_hit") ?: 0
                experienceMismatches += metrics?.number("experience_version_mismatch") ?: 0
                skillFileReads += metrics?.number("skill_file_reads") ?: 0
                skillAssessmentReads += metrics?.number("skill_assessment_reads") ?: 0
                skillReadChars += metrics?.number("skill_read_chars") ?: 0
            }
            events.sortedBy { it.sequence }.forEach { event ->
                val p = Json.parseToJsonElement(event.payloadJson).jsonObject
                when (event.eventKind) {
                    AgentEventKind.TURN_STARTED, AgentEventKind.MODEL_SESSION_ACTIVATED -> {
                        val identity = p["modelSessionIdentity"] as? JsonObject
                        if (identity != null) identities += listOf("providerId", "modelId", "transportKind")
                            .mapNotNull { key -> identity.text(key)?.let { key to it } }.toMap()
                    }
                    AgentEventKind.MODEL_ROUND_COMPLETED -> {
                        val m = p["finishMetadata"] as? JsonObject ?: JsonObject(emptyMap())
                        m.text("runtime_build_version")?.takeIf(String::isNotBlank)?.let(builds::add)
                        m.text("returned_model")?.takeIf(String::isNotBlank)?.let(returnedModels::add)
                        m.number("response_input_tokens")?.let(inputs::add)
                        m.number("response_output_tokens")?.let(outputs::add)
                        m.number("response_reasoning_tokens")?.let(reasoningTokens::add)
                        val cached = m.number("response_cached_input_tokens")?.takeIf { it >= 0 }
                        cached?.let(cacheReads::add)
                        m.number("response_cache_write_tokens")?.takeIf { it >= 0 }?.let(cacheWrites::add)
                        val input = m.number("response_input_tokens")
                        if (cached != null && input != null && input > 0 && cached <= input) {
                            cacheMeasuredInput += input
                            cacheRatioRead += cached
                            cacheRatioRequests++
                        }
                        if (m.text("local_prefix_reused") == "true") localReuses++
                        else m.text("local_prefix_reset_reason")?.takeIf(String::isNotBlank)?.let { count(prefixResets, it) }
                        m.keys.filter { it.startsWith("preparation_") && it.endsWith("_ms") }.forEach { key ->
                            m.number(key)?.takeIf { it >= 0 }?.let { preparationStages.getOrPut(key) { mutableListOf() }.add(it) }
                        }
                        listOf("first_actionable_output_ms", "stream_after_first_output_ms", "first_provider_event_ms", "first_stream_event_ms", "first_reasoning_event_ms",
                            "reasoning_stream_ms", "first_content_event_ms", "first_tool_event_ms").forEach { key ->
                            m.number(key)?.takeIf { it >= 0 }?.let { modelStages.getOrPut(key) { mutableListOf() }.add(it) }
                        }
                        m.number("request_duration_ms")?.let(durations::add)
                        m.text("requested_output_tokens")?.toIntOrNull()?.let(budgets::add)
                        if (m.text("response_output_limit_reached") == "true") outputLimits++
                        count(diagnostics, p.text("terminalDiagnosticCode"))
                    }
                    AgentEventKind.PROGRAM_EXECUTION -> {
                        val data = p["data"] as? JsonObject
                        when (p.text("phase")) {
                            "skills_selected" -> (data?.get("skills") as? JsonArray).orEmpty().forEach { item ->
                                val selected = item as? JsonObject ?: return@forEach
                                skillSelections += listOf("name", "revision", "draft")
                                    .mapNotNull { key -> selected.text(key)?.let { key to it } }.toMap()
                            }
                            "skill_started" -> {
                                val id = data?.text("run_id")
                                val name = data?.text("skill")
                                val revision = data?.text("revision")
                                if (data != null && id != null && name != null && revision != null && id !in skillRuns) {
                                    skillStarts[id] = event.occurredAt
                                    skillRuns[id] = SkillRunMetrics(id, name, revision,
                                        data.text("draft") == "true", data.text("selection") ?: "unspecified",
                                        data["path"] != null && data["path"] != JsonNull, data.text("resume") == "true")
                                }
                            }
                            "skill_assessed" -> {
                                val id = data?.text("run_id")
                                val prior = skillRuns[id]
                                val outcome = data?.text("outcome")
                                if (data != null && prior != null && data.text("revision") == prior.revision &&
                                    outcome in setOf("succeeded", "failed", "unknown")) {
                                    skillRuns[prior.runId] = prior.copy(assessment = outcome!!,
                                        evidenceKind = data.text("evidence_kind"),
                                        assessmentElapsedMs = skillStarts[id]?.let { (event.occurredAt - it).coerceAtLeast(0) })
                                }
                            }
                        }
                        if (p.text("phase") == "resume_decision") count(resumeDecisions, data?.text("decision"))
                        if (p.text("phase") == "device_finished") {
                            val childResult = data?.get("result") as? JsonObject
                            count(childDiagnostics, childResult?.text("diagnosticCode"))
                            if (data?.text("child_id") !in settledCalls) accumulateExecutionMetrics(childResult)
                        }
                    }
                    AgentEventKind.MEMORY_CONTEXT_LOADED -> {
                        memoryLoads++
                        (p["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                            .filter { it.text("scope") == "EXPERIENCE" }.mapNotNull { it.text("ref") }.let(experienceRefs::addAll)
                    }
                    AgentEventKind.TOOL_CALL -> {
                        val arguments = p.text("argumentsJson")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                        when (p.text("name")) {
                            "experience" -> event.callId?.let { experienceCalls[it] = arguments?.text("op") ?: "unknown" }
                            "session_search" -> if (arguments?.text("scope") in setOf("experience", "all")) experienceSearches++
                            "done" -> experienceProposals += (arguments?.get("experience_updates") as? JsonArray)?.size ?: 0
                        }
                    }
                    AgentEventKind.TOOL_STARTED -> event.callId?.let { starts[it] = event.occurredAt }
                    AgentEventKind.TOOL_FINISHED, AgentEventKind.TOOL_CALL_REJECTED -> {
                        event.callId?.let { starts.remove(it) }?.let { toolMs += (event.occurredAt - it).coerceAtLeast(0) }
                        val result = p["result"] as? JsonObject ?: JsonObject(emptyMap())
                        count(statuses, result.text("executionStatus"))
                        event.callId?.let(experienceCalls::get)?.let { op -> count(experienceOutcomes, "$op:${result.text("executionStatus")}") }
                        count(diagnostics, result.text("diagnosticCode"))
                        if (result.text("executionStatus") == "NOT_EXECUTED") {
                            if (result.text("diagnosticCode") == com.droidance.core.agent.CompletionReviewGate.CODE) completionReviews++
                            else count(rejections, result.text("diagnosticCode"))
                        }
                        accumulateExecutionMetrics(result)
                    }
                    AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED -> {
                        if (p.text("state") == "PAUSED_USER") {
                            if (pausedAt == null) pausedAt = event.occurredAt
                        } else if (p.text("state") in setOf("RUNNING", "COMPLETED", "CANCELED", "FAILED")) {
                            pausedAt?.let { humanWaitMs += (event.occurredAt - it).coerceAtLeast(0) }
                            pausedAt = null
                        }
                    }
                    AgentEventKind.TURN_COMPLETED -> {
                        totalMs = p.number("totalDurationMillis")
                        pausedAt?.let { humanWaitMs += (event.occurredAt - it).coerceAtLeast(0) }
                        pausedAt = null
                    }
                    else -> Unit
                }
            }
            return TaskAnalysisMetrics(
                modelIdentities = identities.toList(), returnedModels = returnedModels.toList(),
                runtimeBuildVersions = builds.toList(),
                currentEditorReadbacksVerified = editorVerified, targetFocusBindingsVerified = targetVerified,
                currentEditorReadbackMetricsAvailable = editorMeasured, targetFocusBindingMetricsAvailable = targetMeasured,
                reportedInputTokens = inputs.takeIf { it.isNotEmpty() }?.sum(),
                reportedOutputTokens = outputs.takeIf { it.isNotEmpty() }?.sum(), measuredUsageRequests = outputs.size,
                reportedReasoningTokens = reasoningTokens.takeIf { it.isNotEmpty() }?.sum(),
                promptCache = PromptCacheMetrics(
                    reportedCachedTokens = cacheReads.takeIf { it.isNotEmpty() }?.sum(),
                    reportedWriteTokens = cacheWrites.takeIf { it.isNotEmpty() }?.sum(),
                    readReportedRequests = cacheReads.size, writeReportedRequests = cacheWrites.size,
                    hitRequests = cacheReads.count { it > 0 }, missRequests = cacheReads.count { it == 0L },
                    tokenReadRatio = if (cacheMeasuredInput > 0) cacheRatioRead.toDouble() / cacheMeasuredInput else null,
                    ratioMeasuredRequests = cacheRatioRequests,
                    localPrefixReuses = localReuses, localPrefixResetReasons = prefixResets
                ),
                preparationStageDurations = preparationStages.mapValues { (_, samples) ->
                    DurationSamples(samples.size, samples.sum(), samples.min(), samples.max())
                },
                modelStageDurations = modelStages.mapValues { (_, samples) ->
                    DurationSamples(samples.size, samples.sum(), samples.min(), samples.max())
                },
                observationStageDurations = observationStages.mapValues { (_, samples) ->
                    DurationSamples(samples.size, samples.sum(), samples.min(), samples.max())
                },
                modelRounds = events.count { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED },
                toolCalls = events.count { it.eventKind == AgentEventKind.TOOL_CALL },
                rejectedCalls = (statuses["NOT_EXECUTED"] ?: 0) - completionReviews,
                completionReviewRequests = completionReviews,
                rejectedCallDiagnosticCounts = rejections,
                failedCalls = statuses["FAILED"] ?: 0,
                executionStatusCounts = statuses, diagnosticCounts = diagnostics,
                outputLimitFailures = outputLimits, requestedOutputBudgets = budgets.toList(),
                verifiedTextDeliveries = verified, unverifiedTextDeliveries = unverified,
                textDeliveryMetricsAvailable = verified + unverified,
                textInputStageDurations = inputStages.mapValues { (_, samples) ->
                    DurationSamples(samples.size, samples.sum(), samples.min(), samples.max())
                },
                maxReportedInputTokensPerRequest = inputs.maxOrNull(),
                modelRequestDurationMs = durations.takeIf { it.isNotEmpty() }?.sum(),
                measuredModelRequests = durations.size, toolDurationMs = toolMs,
                totalDurationMs = totalMs,
                humanWaitDurationMs = humanWaitMs,
                activeDurationMs = totalMs?.let { (it - humanWaitMs).coerceAtLeast(0) },
                unclosedHumanWait = pausedAt != null,
                unclosedToolIntervals = starts.size,
                batchPrimitiveAttempts = batchAttempts, batchPrimitiveSucceededReceipts = batchSucceeded,
                batchExecutionDurationMs = batchMs, preActionSamplingDurationMs = samplingMs,
                screenActionBatchCalls = batchCalls, scalarScreenActionCalls = scalarCalls,
                batchRequestedActions = batchRequested, batchCompletedActions = batchCompleted,
                batchUnexecutedActions = batchUnexecuted, batchStopDiagnosticCounts = batchStops,
                actionGroupFinalObservations = finalObservations, actionGroupCaptureSamples = captureSamples,
                actionGroupObservationDurationMs = groupObservationMs,
                frameWaitDurationMs = frameWaitMs, experienceDetailHits = experienceHits,
                experienceVersionMismatches = experienceMismatches,
                memoryContextLoads = memoryLoads, experienceContextRefs = experienceRefs.toList(),
                experienceSearchCalls = experienceSearches, experienceToolOutcomes = experienceOutcomes,
                experienceCompletionProposals = experienceProposals, programResumeDecisions = resumeDecisions, programChildDiagnosticCounts = childDiagnostics,
                skillSelections = skillSelections.toList(), skillRuns = skillRuns.values.toList(),
                skillFileReads = skillFileReads, skillAssessmentReads = skillAssessmentReads, skillReadChars = skillReadChars
            )
        }
    }
}

/** Provider facts and local prefix reuse are deliberately independent measurements. */
@Serializable
internal data class PromptCacheMetrics(
    val reportedCachedTokens: Long?,
    val reportedWriteTokens: Long?,
    val readReportedRequests: Int,
    val writeReportedRequests: Int,
    val hitRequests: Int,
    val missRequests: Int,
    val tokenReadRatio: Double?,
    val ratioMeasuredRequests: Int,
    val localPrefixReuses: Int,
    val localPrefixResetReasons: Map<String, Int>
)
