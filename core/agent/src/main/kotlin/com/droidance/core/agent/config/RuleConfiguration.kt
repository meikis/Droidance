// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.config

import com.droidance.core.agent.config.*

import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolPresentationCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HarnessPolicy(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("runtime_thresholds")
    val runtimeThresholds: RuntimeThresholds,
    @SerialName("memory_context")
    val memoryContext: MemoryContextPolicy,
    @SerialName("human_assistance")
    val humanAssistance: HumanAssistancePolicy,
    @SerialName("tool_safety")
    val toolSafety: ToolSafetyPolicy,
    @SerialName("vision_frame")
    val visionFrame: VisionFramePolicy,
    @SerialName("virtual_space")
    val virtualSpace: VirtualSpacePolicy,
    @SerialName("screen_action_batch")
    val screenActionBatch: ScreenActionBatchPolicy,
    @SerialName("observation_fingerprint")
    val observationFingerprint: ObservationFingerprintPolicy
) {
    fun validate() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported harness_policy schema_version: $schemaVersion"
        }
        runtimeThresholds.validate()
        memoryContext.validate()
        humanAssistance.validate()
        toolSafety.validate()
        visionFrame.validate()
        virtualSpace.validate()
        screenActionBatch.validate()
        observationFingerprint.validate()
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class MemoryContextPolicy(
    @SerialName("core_profile_categories")
    val coreProfileCategories: List<String>,
    @SerialName("core_profile_max_items")
    val coreProfileMaxItems: Int,
    @SerialName("related_memory_max_items")
    val relatedMemoryMaxItems: Int,
    @SerialName("summary_max_chars")
    val summaryMaxChars: Int,
    @SerialName("turn_index_max_chars")
    val turnIndexMaxChars: Int,
    @SerialName("skill_catalog_max_chars")
    val skillCatalogMaxChars: Int,
    @SerialName("recall_detail_max_chars")
    val recallDetailMaxChars: Int,
    @SerialName("experience_max_updates") val experienceMaxUpdates: Int = 3,
    @SerialName("experience_max_chars") val experienceMaxChars: Int = 12000,
    @SerialName("experience_context_items") val experienceContextItems: Int = 3,
    @SerialName("experience_refresh_max") val experienceRefreshMax: Int = 2,
    @SerialName("experience_rank_candidates") val experienceRankCandidates: Int = 50,
    @SerialName("experience_rank_success_weight") val experienceRankSuccessWeight: Int = 2,
    @SerialName("experience_rank_failure_weight") val experienceRankFailureWeight: Int = 4
) {
    fun validate() {
        require(experienceMaxUpdates in 1..8 && experienceMaxChars in 1024..32000 && experienceContextItems in 1..8)
        require(experienceRefreshMax in 0..8 && experienceRankCandidates in 11..200 &&
            experienceRankSuccessWeight in 0..100 && experienceRankFailureWeight in 0..100)
        require(coreProfileCategories.isNotEmpty())
        validateUniqueValues("memory_context.core_profile_categories", coreProfileCategories)
        require(coreProfileCategories.all { it.matches(Regex("[a-z][a-z0-9._-]*")) })
        require(coreProfileMaxItems + relatedMemoryMaxItems + experienceContextItems <= 64)
        require(coreProfileMaxItems in 1..32)
        require(relatedMemoryMaxItems in 1..32)
        require(summaryMaxChars in 32..500)
        require(turnIndexMaxChars >= 256)
        require(skillCatalogMaxChars >= 512)
        require(recallDetailMaxChars >= summaryMaxChars)
    }
}

@Serializable
data class HarnessPromptPolicy(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("communication_contract")
    val communicationContract: CommunicationContract,
    val messages: Map<String, String>
) {
    fun validate() {
        require(schemaVersion == HarnessPolicy.CURRENT_SCHEMA_VERSION) {
            "Unsupported harness_prompts schema_version: $schemaVersion"
        }
        communicationContract.validate()
        validateMessageMap("messages", messages)
    }
}


/**
 * Screen-text fingerprint inputs. Tool receipt text and per-frame volatile text are transport
 * noise, not page content; keeping them in configuration keeps the verifier free of app or
 * task vocabulary.
 */
@Serializable
data class ObservationFingerprintPolicy(
    @SerialName("tool_receipt_markers")
    val toolReceiptMarkers: List<String>,
    @SerialName("volatile_text_pattern")
    val volatileTextPattern: String,
    @SerialName("volatile_text_placeholder")
    val volatileTextPlaceholder: String
) {
    fun validate() {
        validateUniqueValues("observation_fingerprint.tool_receipt_markers", toolReceiptMarkers)
        require(toolReceiptMarkers.all { it.isNotBlank() && it.length <= 64 })
        require(volatileTextPlaceholder.isNotBlank() && volatileTextPlaceholder.length <= 16)
        runCatching { Regex(volatileTextPattern) }.getOrElse {
            throw IllegalArgumentException("observation_fingerprint.volatile_text_pattern is not a valid regex")
        }
    }
}

@Serializable
data class RuntimeThresholds(
    @SerialName("channel_unavailable_stop_repeats")
    val channelUnavailableStopRepeats: Int,
    @SerialName("frame_recovery_repeats")
    val frameRecoveryRepeats: Int,
    @SerialName("max_device_task_wall_ms")
    val maxDeviceTaskWallMs: Long,
    @SerialName("app_takeover_default_poll_ms")
    val appTakeoverDefaultPollMs: Long,
    @SerialName("app_takeover_min_poll_ms")
    val appTakeoverMinPollMs: Long,
    @SerialName("app_takeover_max_poll_ms")
    val appTakeoverMaxPollMs: Long,
    @SerialName("app_takeover_signal_debounce_ms")
    val appTakeoverSignalDebounceMs: Long,
    @SerialName("app_takeover_frame_signal_debounce_ms")
    val appTakeoverFrameSignalDebounceMs: Long,
    @SerialName("app_takeover_execution_retry_delay_ms")
    val appTakeoverExecutionRetryDelayMs: Long,
    @SerialName("app_takeover_scheduler_max_sleep_ms")
    val appTakeoverSchedulerMaxSleepMs: Long,
    @SerialName("app_takeover_frame_poll_ms")
    val appTakeoverFramePollMs: Long,
    @SerialName("app_takeover_frame_stable_samples")
    val appTakeoverFrameStableSamples: Int,
    @SerialName("app_takeover_frame_wake_cooldown_ms")
    val appTakeoverFrameWakeCooldownMs: Long,
    @SerialName("app_takeover_frame_pixel_delta")
    val appTakeoverFramePixelDelta: Int,
    @SerialName("app_takeover_frame_change_fraction")
    val appTakeoverFrameChangeFraction: Double,
    @SerialName("app_takeover_frame_stable_fraction")
    val appTakeoverFrameStableFraction: Double,
    @SerialName("app_takeover_display_keepalive_ms")
    val appTakeoverDisplayKeepaliveMs: Long,
    @SerialName("manual_takeover_wait_timeout_ms")
    val manualTakeoverWaitTimeoutMs: Long,
    @SerialName("manual_takeover_transition_debounce_ms")
    val manualTakeoverTransitionDebounceMs: Long,
    @SerialName("llm_transient_retry_limit")
    val llmTransientRetryLimit: Int,
    @SerialName("llm_transient_retry_backoff_ms")
    val llmTransientRetryBackoffMs: Long,
    @SerialName("llm_recovery_timeout_ms")
    val llmRecoveryTimeoutMs: Long,
    @SerialName("llm_round_timeout_ms")
    val llmRoundTimeoutMs: Long,
    @SerialName("llm_first_event_timeout_ms")
    val llmFirstEventTimeoutMs: Long,
    @SerialName("llm_stream_idle_timeout_ms")
    val llmStreamIdleTimeoutMs: Long,
    @SerialName("llm_first_output_timeout_ms")
    val llmFirstOutputTimeoutMs: Long,
    @SerialName("llm_action_max_output_tokens")
    val llmActionMaxOutputTokens: Int,
    @SerialName("llm_retry_max_output_tokens")
    val llmRetryMaxOutputTokens: Int,
    @SerialName("llm_output_truncation_retries")
    val llmOutputTruncationRetries: Int,
    @SerialName("tool_execution_timeout_ms")
    val toolExecutionTimeoutMs: Long,
    @SerialName("task_entry_capability_wait_ms")
    val taskEntryCapabilityWaitMs: Long,
    @SerialName("route_entry_settle_timeout_ms")
    val routeEntrySettleTimeoutMs: Long,
    @SerialName("app_launch_settle_timeout_ms")
    val appLaunchSettleTimeoutMs: Long,
    @SerialName("app_first_open_launch_mode")
    val appFirstOpenLaunchMode: String,
    @SerialName("post_action_reusable_frame_ttl_ms")
    val postActionReusableFrameTtlMs: Long,
    @SerialName("context_budget_percent")
    val contextBudgetPercent: Int,
    @SerialName("context_recent_image_limit")
    val contextRecentImageLimit: Int,
    @SerialName("context_capture_history_max_images")
    val contextCaptureHistoryMaxImages: Int,
    @SerialName("context_capture_history_max_bytes")
    val contextCaptureHistoryMaxBytes: Long,
    @SerialName("input_focus_probe_timeout_ms")
    val inputFocusProbeTimeoutMs: Long,
    @SerialName("foreground_package_cache_ttl_ms")
    val foregroundPackageCacheTtlMs: Long,
    @SerialName("route_attestation_attempts")
    val routeAttestationAttempts: Int,
    @SerialName("route_attestation_delay_ms")
    val routeAttestationDelayMs: Long,
    @SerialName("route_attestation_timeout_ms")
    val routeAttestationTimeoutMs: Long,
    @SerialName("pre_action_observation_mode")
    val preActionObservationMode: String,
    @SerialName("pre_action_observation_sample_side")
    val preActionObservationSampleSide: Int,
    @SerialName("pre_action_observation_pixel_delta")
    val preActionObservationPixelDelta: Int,
    @SerialName("pre_action_observation_change_fraction")
    val preActionObservationChangeFraction: Double,
    @SerialName("pre_action_target_radius")
    val preActionTargetRadius: Int,
    @SerialName("visit_return_change_fraction")
    val visitReturnChangeFraction: Double,
    @SerialName("pre_action_motion_readings")
    val preActionMotionReadings: Int,
    @SerialName("pre_action_motion_interval_ms")
    val preActionMotionIntervalMs: Long,
    @SerialName("pre_action_motion_min_still_fraction")
    val preActionMotionMinStillFraction: Double,
    @SerialName("pre_action_stable_history_frames")
    val preActionStableHistoryFrames: Int,
    @SerialName("pre_action_stable_history_window_ms")
    val preActionStableHistoryWindowMs: Long,
    @SerialName("pre_action_stable_history_min_span_ms")
    val preActionStableHistoryMinSpanMs: Long,
    @SerialName("pre_action_stable_motion_changes")
    val preActionStableMotionChanges: Int,
    @SerialName("pre_action_surface_sampling_interval_ms")
    val preActionSurfaceSamplingIntervalMs: Long,
) {
    fun validate() {
        require(channelUnavailableStopRepeats > 0)
        require(frameRecoveryRepeats > 0)
        require(maxDeviceTaskWallMs >= 0)
        require(appTakeoverDefaultPollMs in appTakeoverMinPollMs..appTakeoverMaxPollMs)
        require(appTakeoverMinPollMs > 0)
        require(appTakeoverMaxPollMs >= appTakeoverMinPollMs)
        require(appTakeoverSignalDebounceMs >= 0)
        require(appTakeoverFrameSignalDebounceMs >= 0)
        require(appTakeoverExecutionRetryDelayMs >= 0)
        require(appTakeoverSchedulerMaxSleepMs > 0)
        require(appTakeoverFramePollMs > 0)
        require(appTakeoverFrameStableSamples > 0)
        require(appTakeoverFrameWakeCooldownMs >= 0)
        require(appTakeoverFramePixelDelta in 1..255)
        require(appTakeoverFrameChangeFraction in 0.0..1.0)
        require(appTakeoverFrameStableFraction in 0.0..appTakeoverFrameChangeFraction)
        require(appTakeoverDisplayKeepaliveMs > 0)
        require(manualTakeoverWaitTimeoutMs == 0L || manualTakeoverWaitTimeoutMs >= 30_000)
        require(manualTakeoverTransitionDebounceMs >= 0)
        require(llmTransientRetryLimit >= 0)
        require(llmTransientRetryBackoffMs >= 0)
        require(llmRecoveryTimeoutMs > 0)
        require(llmRoundTimeoutMs >= 0)
        require(llmFirstEventTimeoutMs >= 0 && llmStreamIdleTimeoutMs >= 0 && llmFirstOutputTimeoutMs >= 0)
        require(llmActionMaxOutputTokens > 0)
        require(llmRetryMaxOutputTokens >= llmActionMaxOutputTokens)
        require(llmOutputTruncationRetries >= 0)
        require(toolExecutionTimeoutMs > 0)
        require(taskEntryCapabilityWaitMs >= 0)
        require(routeEntrySettleTimeoutMs > 0)
        require(appLaunchSettleTimeoutMs >= 0)
        require(appFirstOpenLaunchMode in setOf("resume", "reset_task", "fresh"))
        require(postActionReusableFrameTtlMs >= 0)
        require(contextBudgetPercent in 1..100)
        require(contextRecentImageLimit >= 0)
        require(contextCaptureHistoryMaxImages >= 0)
        require(contextCaptureHistoryMaxBytes >= 0)
        require(inputFocusProbeTimeoutMs > 0)
        require(foregroundPackageCacheTtlMs >= 0)
        require(routeAttestationAttempts > 0)
        require(routeAttestationDelayMs >= 0)
        require(routeAttestationTimeoutMs > 0)
        require(preActionObservationMode in setOf("observe", "enforce"))
        require(preActionObservationSampleSide in 2..64)
        require(preActionObservationPixelDelta in 0..255)
        require(preActionObservationChangeFraction in 0.0..1.0)
        require(preActionTargetRadius in 1..500)
        require(visitReturnChangeFraction > 0.0 && visitReturnChangeFraction <= 1.0)
        require(preActionMotionReadings in 0..4 && preActionMotionIntervalMs in 1..1000)
        require(preActionMotionMinStillFraction > 0.0 && preActionMotionMinStillFraction <= 1.0)
        require(preActionStableHistoryFrames in 0..32 && preActionStableHistoryWindowMs > 0)
        require(preActionStableHistoryMinSpanMs in 0..preActionStableHistoryWindowMs)
        require(preActionStableMotionChanges >= 2 && preActionSurfaceSamplingIntervalMs >= 0)
    }
}

@Serializable
data class CommunicationContract(
    @SerialName("screen_evidence_parameter")
    val screenEvidenceParameter: String,
    @SerialName("evidence_refs_parameter")
    val evidenceRefsParameter: String,
    @SerialName("terminal_observation_ref_parameter")
    val terminalObservationRefParameter: String
) {
    fun validate() {
        require(
            listOf(
                screenEvidenceParameter,
                evidenceRefsParameter,
                terminalObservationRefParameter
            ).all(String::isNotBlank)
        )
    }
}

@Serializable
data class HumanAssistancePolicy(
    @SerialName("observation_unavailable_diagnostics")
    val observationUnavailableDiagnostics: List<String>,
    @SerialName("allowed_boundary_types")
    val allowedBoundaryTypes: List<String>
) {
    fun validate() {
        validateUniqueValues("human_assistance.observation_unavailable_diagnostics", observationUnavailableDiagnostics)
        require(observationUnavailableDiagnostics.all { it.matches(Regex("[A-Z][A-Z0-9_]*")) })
        validateUniqueValues("human_assistance.allowed_boundary_types", allowedBoundaryTypes)
        require(allowedBoundaryTypes.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
    }
}

/**
 * Tool-effect boundaries are policy data, not agent/business logic. Keeping these patterns in
 * harness_policy.yaml lets a deployment tighten or relax the generic mobile-use boundary without
 * rebuilding Kotlin code.
 */
@Serializable
data class ToolSafetyPolicy(
    @SerialName("confirmation_tools")
    val confirmationTools: List<String>,
    @SerialName("shell_destructive_patterns")
    val shellDestructivePatterns: List<String>,
    @SerialName("shell_forbidden_patterns")
    val shellForbiddenPatterns: List<String>
) {
    fun validate() {
        validateUniqueValues("tool_safety.confirmation_tools", confirmationTools)
        validateUniqueValues("tool_safety.shell_destructive_patterns", shellDestructivePatterns)
        validateUniqueValues("tool_safety.shell_forbidden_patterns", shellForbiddenPatterns)
        require(confirmationTools.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
        shellDestructivePatterns.forEach(::Regex)
        shellForbiddenPatterns.forEach(::Regex)
    }
}


@Serializable
data class VirtualSpacePolicy(
    @SerialName("render_wait_timeout_ms")
    val renderWaitTimeoutMs: Long,
    @SerialName("render_wait_poll_ms")
    val renderWaitPollMs: Long,
    @SerialName("sidecar_call_timeout_ms")
    val sidecarCallTimeoutMs: Long,
    @SerialName("sidecar_capture_timeout_ms")
    val sidecarCaptureTimeoutMs: Long,
    @SerialName("black_frame_mean_threshold")
    val blackFrameMeanThreshold: Double,
    @SerialName("black_frame_variance_threshold")
    val blackFrameVarianceThreshold: Double
) {
    fun validate() {
        require(renderWaitTimeoutMs > 0)
        require(renderWaitPollMs > 0)
        require(renderWaitPollMs <= renderWaitTimeoutMs)
        require(sidecarCallTimeoutMs > 0)
        require(sidecarCaptureTimeoutMs > 0)
        require(blackFrameMeanThreshold.isFinite() && blackFrameMeanThreshold >= 0)
        require(blackFrameVarianceThreshold.isFinite() && blackFrameVarianceThreshold >= 0)
    }
}

@Serializable
data class ToolContractsDocument(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("tool_contracts")
    val toolContracts: Map<String, ToolContractConfig>
) {
    fun validate() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported tool_contracts schema_version: $schemaVersion"
        }
        require(toolContracts.isNotEmpty()) { "Tool contract configuration is empty" }
        toolContracts.forEach { (name, contract) -> contract.validate(name) }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class ToolContractConfig(
    val binding: ToolBinding,
    val effect: ToolEffect,
    @SerialName("grounds_completion")
    val groundsCompletion: Boolean,
    @SerialName("required_capability")
    val requiredCapability: DeviceCapability? = null,
    val label: String,
    val category: ToolPresentationCategory,
    @SerialName("result_image_role")
    val resultImageRole: ResultImageRole,
    @SerialName("sensitive_field_paths")
    val sensitiveFieldPaths: List<String> = emptyList(),
    @SerialName("requires_result_before_action")
    val requiresResultBeforeAction: Boolean = false
) {
    fun validate(name: String) {
        require(name.isNotBlank()) { "Tool contract name cannot be blank" }
        require((binding == ToolBinding.DEVICE_BOUND) == (requiredCapability != null)) {
            "Tool contract $name must declare required_capability exactly for DEVICE_BOUND tools"
        }
        require(label.isNotBlank()) { "Tool contract $name label cannot be blank" }
        require(!requiresResultBeforeAction || effect == ToolEffect.READ_ONLY) {
            "Tool contract $name requires_result_before_action needs READ_ONLY"
        }
        validateUniqueValues("tool_contracts.$name.sensitive_field_paths", sensitiveFieldPaths, allowEmpty = true)
    }
}

private fun validateMessageMap(name: String, values: Map<String, String>) {
    require(values.isNotEmpty()) { "$name cannot be empty" }
    require(values.keys.all(String::isNotBlank)) { "$name keys cannot be blank" }
    require(values.values.all(String::isNotBlank)) { "$name values cannot be blank" }
}

private fun validateUniqueValues(name: String, values: List<String>, allowEmpty: Boolean = false) {
    if (!allowEmpty) require(values.isNotEmpty()) { "$name cannot be empty" }
    require(values.all(String::isNotBlank)) { "$name cannot contain blank values" }
    require(values.distinct().size == values.size) { "$name values must be unique" }
}
