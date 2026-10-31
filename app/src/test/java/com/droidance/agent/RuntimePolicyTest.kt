// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import com.droidance.core.agent.tools.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePolicyTest {
    @Test
    fun guardFinalizationExposesOnlyCompletionWhileNormalExecutionKeepsIntervention() {
        assertEquals(setOf("present_result_card", "done"), ToolCatalog.finalizationToolNames)
        assertFalse("swipe" in ToolCatalog.finalizationToolNames)
        assertTrue("ask_human" in ToolCatalog.operatorToolNames)
    }

    @Test
    fun publicFallbackLookupNeverStartsAnotherModelRequest() {
        val rules = projectFile("app/src/main/java/com/droidance/config/RuleConfigManager.kt").readText()

        assertFalse(rules.contains("LLMRouter"))
        assertFalse(rules.contains("llmRouter.chat("))
        assertFalse(rules.contains("withTimeoutOrNull"))
        assertFalse(rules.contains("languageName"))
    }

    @Test
    fun finalOutputKeepsTheOperatorTextWithoutASecondGroundingLayer() {
        val rules = projectFile("app/src/main/java/com/droidance/config/RuleConfigManager.kt").readText()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        assertTrue(runtime.contains("evidenceLedger.stripInternalEvidence(uiResult.operatorOutput).trim()"))
        assertFalse(runtime.contains("FinalGroundingResult"))
        assertFalse(runtime.contains("groundFinalResponse("))
        assertFalse(runtime.contains("completionSupported"))
        assertFalse(rules.contains("groundFinalAnswer("))
        assertFalse(rules.contains("ConcreteClaimEvidencePolicy"))
        assertFalse(rules.contains("hasConcreteClaim"))
    }

    @Test
    fun publicFallbackDoesNotClassifyModelWordingOrMarkup() {
        val rules = projectFile("app/src/main/java/com/droidance/config/RuleConfigManager.kt").readText()

        assertFalse(rules.contains("BypassPolicy"))
        assertFalse(rules.contains("guardrail_markers"))
        assertFalse(rules.contains("isFailureOrGuardrail"))
    }

    @Test
    fun finalTraceUsesCanonicalTerminalStatus() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val statusBlock = runtime.substringAfter("val terminalProjection = RuntimeTerminalProjection.from")
            .substringBefore("scheduleTurnMemoryUpdate")

        assertFalse(statusBlock.contains("looksLikeFailureResponse"))
        assertTrue(statusBlock.contains("terminalProjection.executionTerminalStatus"))
        assertFalse(statusBlock.contains("taskRunStatus"))
    }

    @Test
    fun taskDoneNotificationShowsModelOutputWithoutOutcomeStatus() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val service = projectFile("app/src/main/java/com/droidance/daemon/TaskExecutionService.kt").readText()

        // 通知正文就是模型最终公开输出，机器终态通过结构化字段传递。
        assertFalse(runtime.contains("runtime_notification_completed_title"))
        assertFalse(runtime.contains("runtime_notification_need_more_info_title"))
        assertFalse(runtime.contains("runtime_notification_not_completed_title"))

        val notificationBlock = runtime
            .substringBefore("TaskExecutionService.notifyTaskDone")
            .takeLast(600)
        assertTrue(notificationBlock.contains("PublicAssistantText.sanitize("))
        assertTrue(notificationBlock.contains("evidenceLedger.stripInternalEvidence(response)"))
        assertFalse(notificationBlock.contains("finishedTaskStatus"))

        val doneBlock = service.substringAfter("fun notifyTaskDone(")
            .substringBefore("fun notifyManualTakeoverRequired(")
        assertTrue(doneBlock.contains("BigTextStyle().bigText(text)"))
        assertFalse(doneBlock.contains("setContentTitle("))
        assertFalse(doneBlock.contains("android.R.drawable."))
    }

    @Test
    fun failuresUseOnlyALocalTerminationReasonWhenNoModelFinalExists() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val rules = projectFile("app/src/main/java/com/droidance/config/RuleConfigManager.kt").readText()
        val rescueBlock = runtime.substringAfter("// 非成功终态只使用模型已给出的公开失败说明")
            .substringBefore("return result.copy(")

        assertTrue(rescueBlock.contains("ruleConfigManager.terminationFallback("))
        assertTrue(rescueBlock.contains("reasonMarker = result.failureReason.name.lowercase()"))
        assertFalse(rescueBlock.contains("summarizeWithEvidence("))
        assertFalse(rescueBlock.contains("llmRouter"))
        assertFalse(rescueBlock.contains("execution_evidence_rescue"))
        assertFalse(rules.contains("verifiedFactTexts"))
    }

    @Test
    fun terminationFallbacksLiveInLocalizedMarkdown() {
        val policy = projectFile("app/src/main/assets/rules/public_fallbacks.md").readText()

        assertTrue(policy.contains("translations"))
        assertFalse(policy.contains("claim_gate:"))
        assertTrue(policy.contains("termination_default"))
        assertTrue(policy.contains("termination_task_time_limit"))
        assertTrue(policy.contains("termination_channel_down"))
        assertTrue(policy.contains("termination_model_timeout"))
        assertTrue(policy.contains("termination_model_unavailable"))
        assertTrue(policy.contains("termination_runtime_exception"))
        assertTrue(policy.contains("termination_air_channel_recovery_required"))
        assertTrue(policy.contains("termination_air_pairing_required"))
        assertTrue(policy.contains("termination_guard_stop"))
        assertFalse(policy.contains("termination_evidence_template"))
        assertFalse(policy.contains("termination_summary_prompt"))
    }

    @Test
    fun runtimeProtocolFallbacksDoNotDependOnUiLocaleResources() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val markerBlock = runtime.substringAfter("private fun resolveInternalTerminalMarker(")
            .substringBefore("private suspend fun buildCancelledTaskSummary(")
        val rescueBlock = runtime.substringAfter("// 非成功终态只使用模型已给出的公开失败说明")
            .substringBefore("private fun operatorStableSystemContext()")

        assertFalse(runtime.contains("private fun localizedTaskString("))
        assertFalse(markerBlock.contains("runCatching"))
        assertTrue(markerBlock.contains("ruleConfigManager.terminationFallback("))
        assertTrue(rescueBlock.contains("reasonMarker = \"runtime_exception\""))
        assertFalse(rescueBlock.contains("R.string.execution_tool_failed"))
        assertFalse(rescueBlock.contains("R.string.execution_no_result"))
    }

    @Test
    fun boundedTerminationKeepsEvidenceWithoutARecoverableStallGate() {
        val policy = projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText()

        val result = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolProtocolResult.kt"
        ).readText()

        assertFalse(policy.contains("argument_schema_mismatch:"))
        assertTrue(result.contains("\"retryable\""))
        assertTrue(result.contains("\"evidenceRefs\""))
        assertFalse(policy.contains("finalization_gate:"))
        assertFalse(policy.contains("finalization_tool_block:"))
    }

    @Test
    fun modelRetryAndTakeoverDebounceBudgetsLiveInTypedPolicy() {
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
        val thresholds = policy.substringAfter("runtime_thresholds:")
            .substringBefore("communication_contract:")

        assertTrue(thresholds.contains("llm_transient_retry_limit:"))
        assertTrue(thresholds.contains("llm_transient_retry_backoff_ms:"))
        assertTrue(thresholds.contains("manual_takeover_transition_debounce_ms:"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
