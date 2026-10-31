// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.Serializable

const val SKILL_LEARNING_EVENT_CONTRACT_VERSION = 1

@Serializable
enum class LearningActionSource {
    AGENT_TOOL,
    USER_DEMONSTRATION
}

@Serializable
data class LearningSessionStartedEventPayload(
    val contractVersion: Int,
    val sessionId: String,
    val captureMode: String,
    val skillName: String? = null,
    val goal: String? = null,
    val packageName: String? = null,
    val appLabel: String? = null
) {
    init {
        requireSkillLearningVersion(contractVersion)
        require(sessionId.isNotBlank()) { "Learning sessionId cannot be blank" }
        require(captureMode.isNotBlank()) { "Learning captureMode cannot be blank" }
        require(skillName == null || skillName.isNotBlank()) { "Learning skillName cannot be blank" }
        require(goal == null || goal.isNotBlank()) { "Learning goal cannot be blank" }
        require(packageName == null || packageName.isNotBlank()) {
            "Learning packageName cannot be blank"
        }
        require(appLabel == null || appLabel.isNotBlank()) { "Learning appLabel cannot be blank" }
    }
}

@Serializable
data class LearningActionRecordedEventPayload(
    val contractVersion: Int,
    val sessionId: String,
    val actionIndex: Int,
    val source: LearningActionSource,
    val sourceCallId: String?,
    val toolName: String,
    val argumentsJson: String,
    val resultSummary: String,
    val success: Boolean,
    val evidenceRef: String?,
    val packageName: String?,
    val activityName: String? = null,
    val actionType: String? = null,
    val beforeFrameRef: String? = null,
    val afterFrameRef: String? = null,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val durationMillis: Long? = null
) {
    init {
        requireSkillLearningVersion(contractVersion)
        require(sessionId.isNotBlank()) { "Learning sessionId cannot be blank" }
        require(actionIndex > 0) { "Learning actionIndex must be positive" }
        require(sourceCallId == null || sourceCallId.isNotBlank()) {
            "Learning sourceCallId cannot be blank"
        }
        require(toolName.isNotBlank()) { "Learning toolName cannot be blank" }
        require(argumentsJson.isNotBlank()) { "Learning arguments cannot be blank" }
        require(resultSummary.isNotBlank()) { "Learning result summary cannot be blank" }
        require(evidenceRef == null || evidenceRef.isNotBlank()) {
            "Learning evidenceRef cannot be blank"
        }
        require(packageName == null || packageName.isNotBlank()) {
            "Learning packageName cannot be blank"
        }
        require(activityName == null || activityName.isNotBlank()) {
            "Learning activityName cannot be blank"
        }
        require(actionType == null || actionType.isNotBlank()) {
            "Learning actionType cannot be blank"
        }
        require(beforeFrameRef == null || beforeFrameRef.isNotBlank()) {
            "Learning beforeFrameRef cannot be blank"
        }
        require(afterFrameRef == null || afterFrameRef.isNotBlank()) {
            "Learning afterFrameRef cannot be blank"
        }
        require(displayWidth == null || displayWidth > 0) {
            "Learning displayWidth must be positive"
        }
        require(displayHeight == null || displayHeight > 0) {
            "Learning displayHeight must be positive"
        }
        require(durationMillis == null || durationMillis >= 0L) {
            "Learning action duration cannot be negative"
        }
    }
}

@Serializable
data class LearningSessionCompletedEventPayload(
    val contractVersion: Int,
    val sessionId: String,
    val actionCount: Int,
    val durationMillis: Long
) {
    init {
        requireSkillLearningVersion(contractVersion)
        require(sessionId.isNotBlank()) { "Learning sessionId cannot be blank" }
        require(actionCount >= 0) { "Learning action count cannot be negative" }
        require(durationMillis >= 0L) { "Learning duration cannot be negative" }
    }
}

@Serializable
data class LearningSkillCompiledEventPayload(
    val contractVersion: Int,
    val sessionId: String,
    val skillId: String,
    val baselineDurationMillis: Long,
    val baselineActionCount: Int,
    val baselineModelRounds: Int,
    val baselinePromptTokens: Int,
    val baselineCompletionTokens: Int
) {
    init {
        requireSkillLearningVersion(contractVersion)
        require(sessionId.isNotBlank()) { "Learning sessionId cannot be blank" }
        require(skillId.isNotBlank()) { "Compiled learning skillId cannot be blank" }
        require(baselineDurationMillis >= 0L) { "Baseline duration cannot be negative" }
        require(baselineActionCount >= 0) { "Baseline action count cannot be negative" }
        require(baselineModelRounds >= 0) { "Baseline model rounds cannot be negative" }
        require(baselinePromptTokens >= 0) { "Baseline prompt tokens cannot be negative" }
        require(baselineCompletionTokens >= 0) { "Baseline completion tokens cannot be negative" }
    }
}

@Serializable
enum class CompiledSkillRunMode {
    VERIFICATION,
    REUSE
}

@Serializable
data class CompiledSkillRunStartedEventPayload(
    val contractVersion: Int,
    val runId: String,
    val skillId: String,
    val mode: CompiledSkillRunMode,
    val checkpointKey: String?
) {
    init {
        requireSkillLearningVersion(contractVersion)
        require(runId.isNotBlank()) { "Compiled Skill runId cannot be blank" }
        require(skillId.isNotBlank()) { "Compiled Skill id cannot be blank" }
        require(checkpointKey == null || checkpointKey.isNotBlank()) {
            "Compiled Skill checkpointKey cannot be blank"
        }
    }
}

@Serializable
data class CompiledSkillRunCompletedEventPayload(
    val contractVersion: Int,
    val runId: String,
    val skillId: String,
    val mode: CompiledSkillRunMode,
    val durationMillis: Long,
    val plannedSteps: Int,
    val executedSteps: Int,
    val divergenceCount: Int,
    val semanticSuccess: Boolean,
    val localModelRounds: Int,
    val localPromptTokens: Int,
    val resultHashes: List<String>,
    val verificationEvidenceRefs: List<String>
) {
    init {
        requireSkillLearningVersion(contractVersion)
        require(runId.isNotBlank()) { "Compiled Skill runId cannot be blank" }
        require(skillId.isNotBlank()) { "Compiled Skill id cannot be blank" }
        require(durationMillis >= 0L) { "Compiled Skill run duration cannot be negative" }
        require(plannedSteps >= 0) { "Compiled Skill planned steps cannot be negative" }
        require(executedSteps >= 0) { "Compiled Skill executed steps cannot be negative" }
        require(executedSteps <= plannedSteps || plannedSteps == 0) {
            "Compiled Skill executed steps cannot exceed its plan"
        }
        require(divergenceCount >= 0) { "Compiled Skill divergence count cannot be negative" }
        require(localModelRounds >= 0) { "Compiled Skill local model rounds cannot be negative" }
        require(localPromptTokens >= 0) { "Compiled Skill local prompt tokens cannot be negative" }
        require(resultHashes.all { it.matches(Regex("^[0-9a-f]{64}$")) }) {
            "Compiled Skill result hashes must be lowercase SHA-256"
        }
        require(verificationEvidenceRefs.all(String::isNotBlank)) {
            "Compiled Skill evidence refs cannot be blank"
        }
        require(!semanticSuccess || verificationEvidenceRefs.isNotEmpty()) {
            "Semantic Skill success requires verification evidence"
        }
    }
}

private fun requireSkillLearningVersion(contractVersion: Int) {
    require(contractVersion == SKILL_LEARNING_EVENT_CONTRACT_VERSION) {
        "Unsupported Skill learning event contractVersion: $contractVersion"
    }
}
