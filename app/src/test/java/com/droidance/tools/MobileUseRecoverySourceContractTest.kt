// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileUseRecoverySourceContractTest {
    @Test
    fun deviceExecutionUsesEventStoreInsteadOfTextProcessMarkers() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        // 单 Agent 收敛后不再有独立启动回执模型：模型首轮"准备执行"文本由 AgentRuntime / TaskCoordinator 的
        // Provider Content 直接流入气泡，Kotlin 不再调用 buildTaskStartAcknowledgement / task_start_reply。
        assertFalse("No separate task-start-reply model call on the single-agent hot path", runtime.contains("buildTaskStartAcknowledgement("))
        assertFalse(runtime.contains("diagnostics.addEvent(\"task_start_reply\""))

        // 执行过程由 EventStore timeline 投影，公开文本流不再混入 UI 协议标记。
        val onExecStartBlock = runtime.substringAfter("onExecutionStarted = {")
            .substringBefore("},")
        assertFalse(onExecStartBlock.contains("onStreamContent("))
        assertFalse(onExecStartBlock.contains("<process"))
        assertFalse(runtime.contains("<report"))
    }

    @Test
    fun inputDeliveryEvidenceComesFromExecutedInputResult() {
        val touch = projectFile("core/device/src/main/java/com/droidance/core/device/tools/TouchInputService.kt").readText()
        val executor = com.droidance.agent.executorSources()

        assertTrue(touch.contains("actionEvent"))
        assertTrue(executor.contains("recordExecutedActionResult("))
        assertTrue(executor.contains("actionDeliveryByToolCall.getOrPut(callId)"))
        val finishEvent = executor
            .substringAfter("fun finishEvent(result: ToolExecutionResult): ToolExecutionResult {")
            .substringBefore("operationRecorder.recordToolExecution(")
        assertTrue(finishEvent.contains("invocationState.actionDeliveryByToolCall"))
        assertTrue(finishEvent.contains("remove(eventToolCallId)?.all { it }"))
        assertFalse(executor.contains("consumeActionDeliveryEvidence"))
        assertFalse(executor.contains("consumeExecutedActionTargets"))
        assertFalse("ToolExecutor must not emit post-hoc visual events", executor.contains("emitActionEvent("))
        assertTrue("Root injection owns the lifecycle boundary", sidecarInput().contains("inputActionLifecycle.execute("))
    }

    @Test
    fun virtualSpaceTextInputRoutesThroughDisplaySidecar() {
        val sidecarInput = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val aidl = projectFile("core/device/src/main/aidl/com/droidance/core/device/agent/device/IVirtualSpaceSidecar.aidl").readText()

        assertTrue(sidecarInput.contains("sidecarBridge.injectText("))
        assertTrue(sidecarInput.contains("expectedBackendSessionId = context.backendSessionId"))
        assertTrue(sidecarInput.contains("sidecarBridge.injectKeyCombo(binding()"))
        assertTrue(sidecarInput.contains("""val route = "sidecar-display:${'$'}{context.displayId}:"""))
        assertTrue(sidecarInput.contains("diagnostics.logTypeText(text, route"))
        assertTrue(bridge.contains("expectedBackendSessionId: String"))
        assertTrue(aidl.contains("Bundle text(int displayId, String text, boolean clearExisting, String expectedFocusTarget, String method);"))
        assertTrue(sidecarInput.contains("expectedFocusTarget = expectedFocusTarget"))
        assertTrue(bridge.contains("service.text(displayId, text, clearExisting, expectedFocusTarget, method.wireName)"))
    }

    @Test
    fun removedReplaySidecarDoesNotForceAKeywordRecoveryLayerIntoOperator() {
        val operator = com.droidance.agent.coordinationSources()
        val replay = requireNotNull(
            projectFile("app/src/main/java/com/droidance/debug/DebugLogManager.kt").parentFile
        ).resolve("ReplayFrameRecorder.kt")

        assertFalse(replay.exists())
        assertFalse(operator.contains("input_transition_guard"))
        assertFalse(operator.contains("action_recovery_guard"))
    }

    @Test
    fun markdownRulesUseGenericProgressGuardsWithoutInputIntentKeywords() {
        val behavior = projectFile("app/src/main/assets/rules/agent_core.md").readText()
        val runtimeRules = projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText()

        assertFalse(behavior.contains("text/search/input intent"))
        assertFalse(behavior.contains("not another same-target tap"))
        assertFalse(runtimeRules.contains("exact_action_repeated:"))
        assertFalse(runtimeRules.contains("input_transition"))
    }

    @Test
    fun operatorHasNoKeywordDrivenInputTransitionGuard() {
        val operator = com.droidance.agent.coordinationSources()
        val guard = File(projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskCoordinator.kt").parentFile, "InputTransitionGuard.kt")

        assertFalse(guard.exists())
        assertFalse(operator.contains("inputTransitionGuard"))
        assertFalse(operator.contains("input_transition"))
        assertFalse(operator.contains("tryRunSubmitSearchRecovery"))
        assertFalse(operator.contains("ToolCall(\"input_transition_recovery\""))
        assertFalse(operator.contains("tryRecoverTapOnlyInputTransition"))
    }

    @Test
    fun inputRecoveryDoesNotInferOrSubmitAQueryInsideKotlin() {
        val operator = com.droidance.agent.coordinationSources()

        assertFalse(operator.contains("extractLabeledValue(extraSystemContext"))
        assertFalse(operator.contains("extractJsonStringValue(extraSystemContext"))
        assertFalse(operator.contains("submittedSearchQueries"))
        assertFalse(operator.contains("lastTextInputRecoveryQuery"))
    }

    @Test
    fun inputTransitionGuardDoesNotReplayAHiddenCompoundTool() {
        val operator = com.droidance.agent.coordinationSources()

        assertFalse(operator.contains("lastTextInputRecoveryTarget"))
        assertFalse(operator.contains("input_transition_recovery_skip"))
        assertFalse(operator.contains("\"submit_search\""))
    }

    @Test
    fun inputRecoveryIsLeftToTheModelAndTheExactRepeatGuard() {
        val operator = com.droidance.agent.coordinationSources()

        assertFalse(operator.contains("inputTransition.shouldReflect"))
        assertFalse(operator.contains("inputTransition.shouldStop"))
        assertFalse(operator.contains("input_transition_guard"))
        assertFalse(operator.contains("action_stall_reflection"))
        assertTrue(operator.contains("exactActionRepeatGuard"))
        assertFalse(operator.contains("tryRunSubmitSearchRecovery"))
    }

    @Test
    fun rawPixelChangesDoNotInstallAKeywordPhaseGuard() {
        val operator = com.droidance.agent.coordinationSources()

        assertFalse(operator.contains("rawNoGoalPhaseKey("))
        assertFalse(operator.contains("taskStateLooksInputRecovery(operatorTaskState)"))
        assertFalse(operator.contains("RawChangedNoGoalProgressGuard"))
        assertFalse(operator.contains("rawChangedNoGoalProgress"))
    }

    @Test
    fun inputRecoveryHasNoKotlinKeywordExtraction() {
        val operator = com.droidance.agent.coordinationSources()

        assertFalse(operator.contains("Keyword summary"))
        assertFalse(operator.contains("Parsed keyword"))
        assertFalse(operator.contains("private fun extractLabeledValue"))
        assertFalse(operator.contains("private fun extractJsonStringValue"))
    }

    @Test
    fun agentLoopHasNoParallelCoordinateReplaySideChannel() {
        val operator = com.droidance.agent.coordinationSources()

        assertFalse(operator.contains("toolCallContainsTextInput("))
        assertFalse(operator.contains("MobileActionBatch.parse(arguments)"))
        assertFalse(operator.contains("actualTargets = executedTargets"))
        assertFalse(operator.contains("recordReplayFrame("))
        assertFalse(operator.contains("selectInputRecoveryTarget("))
        assertFalse(operator.contains("lastInputRecoveryTarget"))
    }

    @Test
    fun mobileUseDebugExportRecordsRecoveryFields() {
        val collector = projectFile("core/device/src/main/java/com/droidance/core/device/debug/DiagnosticsCollector.kt").readText()
        val replay = projectFile("app/src/main/java/com/droidance/debug/AgentEventReplayExporter.kt").readText()
        val operator = com.droidance.agent.coordinationSources()
        val executor = com.droidance.agent.executorSources()

        assertFalse(collector.contains("recordExecutionSpace("))
        assertTrue(replay.contains("val terminalStatus: String?"))
        assertTrue(replay.contains("turn.terminalStatus"))
        assertFalse(collector.contains("\\\"runtime_final_status\\\""))
        assertFalse(collector.contains("\\\"completion_progress_signal_missing\\\""))
        assertFalse(collector.contains("completed_without_progress"))
        assertFalse(collector.contains("\\\"operator_terminal_summary\\\""))
        assertFalse(operator.contains("input_transition_guard"))
        assertFalse(operator.contains("action_stall_reflection"))
        assertFalse(operator.contains("action_atoms"))
        assertTrue(operator.contains("EXACT_ACTION_REPEATED"))
        assertFalse(executor.contains("diagnostics.recordExecutionSpace("))
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

    private fun sidecarInput(): String =
        projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()
}
