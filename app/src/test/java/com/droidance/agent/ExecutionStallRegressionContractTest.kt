// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStallRegressionContractTest {
    @Test
    fun operatorLoopSplitsLargeCoroutinePhasesIntoHarnessHelpers() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertTrue(operator.contains("suspend fun prepareModelObservation(): ModelRoundOutcome?"))
        assertTrue(operator.contains("prepareModelObservation()?.let { return it }"))
        assertTrue(operator.contains("fun evaluateToolCallGuards("))
        assertTrue(operator.contains("val guard = evaluateToolCallGuards("))
        assertTrue(operator.contains("suspend fun executeToolCall("))
        assertTrue(operator.contains("val result = executeToolCall("))
    }

    @Test
    fun modelScreenEvidenceCannotSelfCertifyRuntimeGoalProgress() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val agentDir = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskCoordinator.kt").parentFile

        assertTrue(operator.contains("put(\"records\", evidence.records)"))
        assertFalse(operator.contains("sameRoundGroundedFactIds"))
        assertFalse(operator.contains("sameRoundGroundedGoalFactIds"))
        assertFalse(operator.contains("groundedGoalProgress"))
        assertTrue(operator.contains("val verifiedGoalProgress = validation?.goalProgress == true"))
        assertFalse(File(agentDir, "ClosedActionProgressPolicy.kt").exists())
    }

    @Test
    fun contextCheckpointEpochsAreScopedToTheirTurn() {
        val store = projectFile(
            "core/persistence/src/main/java/com/droidance/core/persistence/agent/event/RoomAgentEventStore.kt"
        ).readText()
        val checkpoint = store.substringAfter("suspend fun recordContextCheckpoint(")
            .substringBefore("/** Returns the latest durable checkpoint")
        val durableEpoch = store.substringAfter("private suspend fun requireCurrentDurableEpoch(")
            .substringBefore("private suspend fun requireCheckpointTurnSession")

        assertTrue(checkpoint.contains("model-session:\$turnId:\$identityHash:checkpoint:\$nextEpoch"))
        assertTrue(checkpoint.contains("model-session:\$turnId:\$identityHash:epoch:\$nextEpoch"))
        assertTrue(durableEpoch.contains("it.turnId == turnId"))
        assertTrue(durableEpoch.contains("turn.modelSessionIdentity != currentSession.identity"))
        assertFalse(durableEpoch.contains("dao.eventsForThread(currentSession.identity.threadId)"))
    }

    @Test
    fun checkpointCommitConflictIsReportedAsRuntimeFailure() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val checkpointFailure = operator.substringAfter("context checkpoint failed closed:")
            .substringBefore("if (durableContextDecision is ContextCheckpointDecision.CheckpointReady)")

        assertTrue(checkpointFailure.contains("guardText("))
        assertTrue(checkpointFailure.contains("\"context_checkpoint_failed\""))
        assertTrue(checkpointFailure.contains("TurnFailureReason.RUNTIME_EXCEPTION"))
        assertFalse(checkpointFailure.contains("TurnFailureReason.MODEL_PROTOCOL_ERROR"))
    }

    @Test
    fun operatorRoundsHaveBoundedRecoveryButAllowContinuingReasoning() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertTrue(operator.contains("baseTimeoutMs = thresholds.llmRoundTimeoutMs"))
        assertTrue(operator.contains("requestedTimeoutMs = RoundTimeoutBudgetPolicy.forOutputBudget("))
        assertTrue(operator.contains("taskWallMs = maxDeviceTaskWallMs"))
        assertFalse(operator.contains("FIRST_ROUND_TIMEOUT_MS"))
        assertFalse(operator.contains("FOLLOWUP_ROUND_TIMEOUT_MS"))
        // Values are covered by HarnessPolicyYamlParseTest invariants; only the disabled
        // first-output deadline is a fixed contract.
        assertTrue(policy.contains("llm_first_output_timeout_ms: 0"))
        assertFalse(policy.contains("llm_final_reply_timeout_ms"))
    }

    @Test
    fun transportTimeoutNeverPublishesEvidenceFragmentsOrStartsASecondFinalRepair() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val prompts = (projectFile("app/src/main/assets/rules/public_fallbacks.md").readText() + "\n" +
            projectFile("app/src/main/assets/rules/model_context.md").readText())
        val exceptionBlock = operator.substringAfter("} catch (e: Exception) {")
            .substringBefore("return ModelRoundOutcome.Ready(")

        assertFalse(operator.contains("groundedCompletionFallback"))
        assertFalse(exceptionBlock.contains("TRANSPORT_TIMEOUT_GROUNDED_FALLBACK"))
        assertFalse(exceptionBlock.contains("FINAL_REPLY_TRANSPORT_TIMEOUT"))
        assertFalse(exceptionBlock.contains("requestFinalReplyRepair("))
        assertTrue(prompts.contains("termination_model_timeout"))
        assertFalse(prompts.contains("termination_model_timeout_simplified_chinese:"))
    }

    @Test
    fun modelAuthoredDoneSummaryClosesBeforeAnyFollowUpRepair() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val resultHandling = operator.substringAfter("when (val toolRoundOutcome = processToolCalls())")
        val conclusionBlock = operator.substringAfter("if (toolExecutor.isCompletionRequested())")
            .substringBefore("val requestedCompletionStatus")

        assertTrue(resultHandling.contains("if (toolExecutor.isCompletionRequested())"))
        assertFalse(resultHandling.contains("RoundFollowUp.REPAIR_FINAL"))
        assertTrue(conclusionBlock.contains("toolExecutor.completionSummary()"))
        assertTrue(operator.contains("completionTerminal(requestedCompletionStatus, executionStarted = true)"))
        assertFalse(operator.contains("TaskCompletionPolicy.normalizeStatus"))
    }

    @Test
    fun taskTimeLimitStopsActionsBeforeBoundedExplanation() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val budgetBlock = operator.substringAfter("val elapsedMs = activeElapsedMs()")
            .substringBefore("val repairPurpose = RepairPurpose.NONE")

        assertTrue(budgetBlock.contains("failureReason = TurnFailureReason.TASK_TIME_LIMIT"))
        assertFalse(budgetBlock.contains("requestFinalReplyRepair("))
    }

    @Test
    fun exactRepeatRejectionStillKeepsEvidenceForTheSameAgent() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val repeatBlock = operator.substringAfter("val exactActionRepeatBlockResult =")
            .substringBefore("val successfulDoneRequested")

        assertTrue(repeatBlock.contains("exactActionRepeatGuard.wasExecuted("))
        assertTrue(repeatBlock.contains("repeatObservationKey"))
        assertTrue(repeatBlock.contains("code = \"EXACT_ACTION_REPEATED\""))

        assertFalse(operator.contains("recentFinalReplyScreens"))
    }

    @Test
    fun ordinaryRoundsAndRecoveryRetainSourceLinkedFindings() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()
        val contracts = projectFile("app/src/main/assets/rules/tool_contracts.yaml").readText()

        assertTrue(operator.contains("TaskContinuationContext.render("))
        assertFalse(operator.contains("evidenceLedger.buildObservationNotebook("))
        assertTrue(operator.contains("taskResearchNote = null"))
        assertFalse(operator.contains("TaskObservationJournal.context("))
        assertFalse(operator.contains("CompletionReviewImages.originalSourceIds(reviewEvents"))
        assertFalse(operator.contains("val browsingBlockResult"))
        assertTrue(operator.contains("<task_research_note>"))
        assertFalse(operator.contains("observationNotebook = observationNotebookForRound"))

        val completionRepair = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskCompletionStage.kt").readText()
            .substringAfter("internal fun TaskTurnSession.completionEvidenceRejection(")
            .substringBefore("internal fun completionFreshObservationRequired")
        assertTrue(completionRepair.contains("evidenceLedger.completionEvidenceReferences()"))
        assertFalse(completionRepair.contains("buildObservationNotebook("))
        assertTrue(catalog.contains("setOf(\"present_result_card\", \"done\")"))
        assertTrue(
            contracts.substringAfter("present_result_card:")
                .substringBefore("\n  done:")
                .contains("effect: COMPLETION")
        )
        assertFalse(operator.contains("RepairPurpose.FINAL_REPLY"))
    }

    @Test
    fun finalReplyRepairScaffoldingIsAbsent() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertFalse(operator.contains("requestFinalReplyRepair"))
        assertFalse(operator.contains("resumeExecutionFromFinalReview"))
        assertFalse(operator.contains("RepairPurpose.FINAL_REPLY"))
        assertFalse(policy.contains("llm_final_reply_timeout_ms"))
        assertFalse(policy.contains("llm_final_max_output_tokens"))
    }

    @Test
    fun wallAndRejectedActionProtocolUseNoHiddenRepairBudget() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val wallBlock = operator.substringAfter("val elapsedMs = activeElapsedMs()")
            .substringBefore("val repairPurpose = RepairPurpose.NONE")
        val rejectionBlock = operator.substringAfter("val protocolRejections =")
            .substringBefore("runCatching {")

        assertFalse(wallBlock.contains("requestFinalReplyRepair("))
        assertFalse(wallBlock.contains("return wallStopResult(elapsedMs)"))
        assertFalse(rejectionBlock.contains("requestFinalReplyRepair("))
        assertFalse(rejectionBlock.contains("retryLimit = 2"))
        assertFalse(rejectionBlock.contains("rounds--"))
        assertTrue(rejectionBlock.contains(
            "rejected tool protocol results returned to the normal model loop"
        ))
        assertTrue(rejectionBlock.contains("return null"))
    }

    @Test
    fun notExecutedRouteSelectionDoesNotInvalidateTheExistingObservation() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val resultHandling = operator.substringAfter("messages.add(ChatMessage(")
            .substringBefore("completedToolCallCount += 1")

        assertTrue(resultHandling.contains("val toolNotExecuted = ToolResultStatus.isNotExecuted(result)"))
        assertTrue(resultHandling.contains("!toolNotExecuted"))
    }

    @Test
    fun decisionFramesDoNotRelyOnAuxiliaryOcrEngine() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val executor = com.droidance.agent.executorSources()

        assertFalse(operator.contains("toolExecutor.extractReadableScreenText("))
        assertFalse(executor.contains("screenOcr.recognizeFromBase64("))
    }

    @Test
    fun transportTimeoutsRetryWithoutInjectingAForcedStrategy() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        assertTrue(operator.contains("isLlmTransportTimeout(e)"))
        assertFalse(operator.contains("prepareModelTimeoutRecovery()"))
        assertFalse(operator.contains("forceToolCallAfterTimeout"))
        assertTrue(operator.contains("ModelRoundOutcome.Terminal(modelTimeoutTerminal(fullContent))"))
    }

    @Test
    fun eachUiTaskClearsThePreviousCompletionStateBeforeOperatorExecution() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val start = runtime.substringAfter("debugLogManager.appendEvent(\"▶️ run_ui_task:")
            .substringBefore("val intentSourceText")
        assertTrue(start.contains("toolExecutor.resetTaskState()"))
    }

    @Test
    fun newTaskDoesNotObserveThePreviousTaskScreenForRouting() {
        val rules = (projectFile("app/src/main/assets/rules/public_fallbacks.md").readText() + "\n" +
            projectFile("app/src/main/assets/rules/model_context.md").readText())
        val entry = rules.substringAfter("## missing_screen_state_entry")
            .substringBefore("\n## screen_unchanged_frame")

        assertTrue(entry.contains("No current screen is attached"))
        assertTrue(entry.contains("retained display content is not current evidence"))
        assertFalse(entry.contains("choose the first native, route, or observation tool"))
    }

    @Test
    fun virtualPackageEntryReceivesTheRequestedLaunchFlags() {
        val registrar = projectFile(
            "app/src/main/java/com/droidance/tools/AppLaunchService.kt"
        ).readText()
        val manager = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt"
        ).readText()
        val virtualEntry = registrar.substringAfter("private suspend fun launchPackageInVirtualSpace(")
            .substringBefore("\n    private suspend fun launchExplicitIntent(")
        val managerLaunch = manager.substringAfter("fun launchApp(")
            .substringBefore("\n    /** 从当前冻结的 sidecar")

        assertTrue(registrar.contains("launchMode.intentFlags"))
        assertTrue(
            virtualEntry.contains(
                "deviceBackend.openPackage(packageName, launchMode, intentFlags)"
            )
        )
        assertTrue(managerLaunch.contains("intentFlags: Int"))
        assertTrue(
            Regex("launchPackage\\(binding, packageName, component, launchMode, intentFlags\\)")
                .findAll(managerLaunch)
                .count() == 1
        )
        assertFalse(managerLaunch.contains("KEYCODE_HOME"))
        assertFalse(managerLaunch.contains("val flags = when (launchMode)"))
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
