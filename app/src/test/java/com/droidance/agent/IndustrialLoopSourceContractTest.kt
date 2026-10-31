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

class IndustrialLoopSourceContractTest {
    @Test
    fun postActionObservationCarriesFactsWithoutPrescribingTheNextAction() {
        val rules = (projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText() + "\n" +
            projectFile("app/src/main/assets/rules/model_context.md").readText())
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertFalse(rules.contains("post_action_observation:"))
        assertFalse(rules.contains("post_action_observation_tail:"))
        assertFalse(source.contains("postActionObservationTail"))
        assertFalse(source.contains("OBSERVATION_ALREADY_ATTACHED"))
        assertFalse(rules.contains("observe_screen/screenshot/perceive/read_screen"))
    }

    @Test
    fun operatorDoesNotInjectAnOldScreenMerelyBecauseAnotherRoundStarted() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val rules = (projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText() + "\n" +
            projectFile("app/src/main/assets/rules/model_context.md").readText())

        assertTrue(source.contains("prompt(\"missing_screen_state_entry\")"))
        assertFalse(source.contains("rounds > 1 || hasPreloadedScreenshot"))
        assertTrue(source.contains("freshObservationRequired"))
        assertTrue(source.contains("val pendingObservationReady = if (freshObservationRequired && forceFreshObservationCapture)"))
        assertTrue(source.contains("if (freshObservationRequired)"))
        assertTrue(source.contains("toolExecutor.invalidatePendingObservation()"))
        assertTrue(source.contains("if (!pendingObservationReady && toolExecutor.pendingScreenshotBase64 == null)"))
        assertTrue(rules.contains("## missing_screen_state_entry"))
        assertFalse(rules.contains("choose the first native, route, or observation tool"))
        assertFalse(source.contains("call `observe_screen` first"))
    }

    @Test
    fun explicitObservationCallsAlwaysUseTheNormalExecutionAndSettlementPath() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()
        val execution = source.substringAfter("suspend fun executeToolCall(")
            .substringBefore("var interventionEntered")

        assertFalse(source.contains("isRedundantObservationOnlyTurn"))
        assertFalse(source.contains("consumePostActionObservationFor"))
        assertFalse(source.contains("attachedObservationReuse"))
        assertTrue(execution.contains("executeSelectedTool("))
        assertTrue(execution.contains("settleDurableTool("))
        assertTrue(observe.contains("deviceBackend.captureScreen(forceFresh = true)"))
    }

    @Test
    fun postActionFramesRemainAvailableAsModelContext() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        assertTrue(source.contains("persistPendingPostActionEvidence("))
        assertTrue(source.contains("reusablePostActionObservation = refreshedObservation"))
        assertTrue(source.contains("toolExecutor.consumePendingObservation()?.let { observation ->"))
        assertTrue(source.contains("toolExecutor.bindModelFrame(observation)"))
    }

    @Test
    fun userInterventionFollowUpCreatesAHardPauseBarrier() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val followUpBlock = source
            .substringAfter("when (val toolRoundOutcome = processToolCalls())")
            .substringBefore("val terminalBackendDiagnostics")

        assertTrue(followUpBlock.contains("roundPlan.followUp == RoundFollowUp.WAIT_FOR_USER"))
        assertTrue(followUpBlock.contains("if (!isPaused())"))
        assertTrue(followUpBlock.contains("waitForActiveExecution()"))
        assertTrue(followUpBlock.contains("takeManualResumeInstruction()"))
        assertTrue(followUpBlock.contains("continue@operatorLoop"))
    }

    @Test
    fun directToolResultKeepsHarnessReplanPolicyWithoutWrapperProtocol() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val rules = (projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText() + "\n" +
            projectFile("app/src/main/assets/rules/model_context.md").readText())

        assertFalse(source.contains("extractUpdatedScreenState(result)"))
        assertFalse(source.contains("stale_planned_actions_discarded"))
        assertFalse(source.contains("RuntimeActionAtomExtractor"))
        assertTrue(source.contains("exactActionRepeatGuard.record("))
        assertTrue(source.contains("suspend fun executeSelectedTool("))
        assertTrue(source.contains("val executingCall = checkNotNull(selectedCall)"))
        assertTrue(source.contains("binding = executingCall.contract.binding"))
        assertTrue(source.contains("code = \"TOOL_EXECUTION_TIMEOUT\""))
        assertTrue(source.contains("val result = locallyRejectedResult ?: run {"))
        assertTrue(source.contains("output = identifiedResult"))
        assertFalse(rules.contains("stale_planned_actions_discarded"))
        assertFalse(rules.contains("[HARNESS_REPLAN]"))
    }

    @Test
    fun protocolFailuresForceFreshScreenBeforeRetryingScreenActions() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val planBlock = source.substringAfter("if (planRejectionCode != null) {")
            .substringBefore("continue")
        val boundsBlock = source.substringAfter("if (coordinateProtocolFailure) {")
            .substringBefore("if (locallyRejectedResult == null")

        assertTrue(planBlock.contains("currentObservationLease = null"))
        assertTrue(planBlock.contains("freshObservationRequired = true"))
        assertTrue(boundsBlock.contains("toolExecutor.invalidateModelFrameBinding()"))
        assertTrue(boundsBlock.contains("toolExecutor.invalidatePendingObservation()"))
        assertTrue(boundsBlock.contains("freshObservationRequired = true"))
        assertFalse(source.contains("coordinateRecoveryActive"))
    }

    @Test
    fun successfulStateChangingActionsForceASettledObservationBeforeTheNextDecision() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val bindingBlock = source.substringAfter(
            "selectedCall?.contract?.effect == ToolEffect.STATE_CHANGING"
        ).substringBefore("completedToolCallCount += 1")

        assertTrue(bindingBlock.contains("currentObservationLease = null"))
        assertTrue(bindingBlock.contains("toolExecutor.invalidateModelFrameBinding()"))
        assertTrue(bindingBlock.contains("freshObservationRequired = true"))
    }

    @Test
    fun strategyLoopControlIsReducedToExactObservationBoundRepetition() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val guard = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/ExactActionRepeatGuard.kt").readText()
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertTrue(operator.contains("ExactActionRepeatGuard()"))
        assertTrue(operator.contains("exactActionRepeatGuard.wasExecuted("))
        assertTrue(operator.contains("exactActionRepeatGuard.record("))
        assertTrue(guard.contains("observationId"))
        assertTrue(guard.contains("canonicalArguments(arguments)"))
        assertFalse(operator.contains("ActionStallDetector"))
        assertFalse(operator.contains("ReflectedActionGate"))
        assertFalse(operator.contains("SemanticLookupGuard"))
        assertFalse(operator.contains("ClosedActionProgressPolicy"))
        assertFalse(policy.contains("no_progress_stop_repeats"))
    }

    @Test
    fun captureHookKeepsDroidanceOverlayForegroundWithoutDeviceWindowGate() {
        val source = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val hookBody = source.substringAfter("sidecarCapture.beforeCaptureHook = { displayId ->")
            .substringBefore("\n        sidecarCapture.afterCaptureHook")

        assertFalse(source.contains("com.oplus.gesture"))
        assertFalse(source.contains("hasGestureWindow"))
        assertFalse(hookBody.contains("setOverlayCaptureHidden(true)"))
        assertFalse(hookBody.contains("removeOverlayWindowsForCapture()"))
        assertFalse(hookBody.contains("delay(360L)"))
    }

    @Test
    fun executionAvatarUsesRealMobileUseActionsFromEitherExecutionSpace() {
        val source = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()

        assertTrue(source.contains("private fun isRuntimeActive()"))
        assertTrue(source.contains("private fun shouldShowExternalTaskOverlay()"))
        // 小手由真实输入事件驱动，并交给目标 display 的 shell sidecar 图层渲染。
        assertTrue(source.contains("agentRuntime.isMobileUseTaskActive.value"))
        assertTrue(source.contains("foregroundState.isInForeground"))
        assertFalse(source.contains("if (event.displayId != 0) return@post"))
        assertTrue(source.contains("runtimeSurfaceController.showAction(event)"))
        assertTrue(source.contains("runtimeSurfaceController.finishAction(event)"))
    }

    @Test
    fun captureHookDoesNotHideOverlayBeforeScreenshotAnalysis() {
        val source = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        assertFalse(source.contains("beforeCaptureHook"))
        assertFalse(source.contains("beginMainScreenCapture"))
    }

    @Test
    fun runtimeDoesNotLaunchASeparateEvidenceSummarizer() {
        val source = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val composer = File(projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").parentFile, "EvidenceSummaryComposer.kt")

        assertFalse(composer.exists())
        assertFalse(source.contains("Evidence summarization visual attempt failed"))
        assertFalse(source.contains("Retrying evidence summarization without image"))
        assertFalse(source.contains("summarizeEvidence"))
    }

    @Test
    fun memoryCandidateProjectionRunsOffTheUserVisiblePath() {
        val source = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())

        assertTrue(source.contains("finishScope.launch"))
        assertTrue(source.contains("TurnMemoryCandidateProjector.project("))
        assertFalse(source.contains("persistTrajectoryMemory("))
    }

    @Test
    fun explicitTaskDoneKeepsStructuredTerminalWithoutAFinalRewriteLayer() {
        val source = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())

        assertFalse(source.contains("skipFinalGrounding = result.taskDoneByModel"))
        assertFalse(source.contains("Final claim gate skipped: explicit task_done"))
        assertFalse(source.contains("groundFinalResponse("))
        assertFalse(source.contains("completionSupported"))
        assertTrue(source.contains("RuntimeUiOutcome.NEED_MORE_INFO"))
    }

    @Test
    fun operatorInjectsModelMaintainedWorkingMemoryWithoutPersistingOldScreens() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertTrue(source.contains("com.droidance.core.agent.workspace.TaskWorkspace.overview("))
        assertTrue(source.contains("<task_working_memory>"))
        assertTrue(source.contains("systemPrompt = systemPrompt"))
        assertFalse(source.contains("roundSystemPrompt"))
        assertTrue(source.contains("durableContextCheckpointCoordinator.evaluateAndCommit"))
        assertFalse(source.contains("ContextManager"))
    }

    @Test
    fun operatorDoesNotAcceptSelfManagementOnlyCompletion() {
        val source = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertTrue(source.contains("ToolCatalog.selfManagementToolNames"))
        assertTrue(source.contains("executionToolCalls"))
        assertTrue(source.contains("toolExecutor.clearCompletionRequest()"))
        assertTrue(source.contains("hasGroundedExecution()"))
    }

    @Test
    fun runtimeHasNoIndependentTaskDecomposerOrKeywordTrigger() {
        val source = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val rules = projectFile("app/src/main/assets/rules/automation_prompts.md").readText()
        val decomposer = File(projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").parentFile, "timeline/TaskDecomposer.kt")

        assertFalse(decomposer.exists())
        assertFalse(source.contains("shouldRunTaskDecomposer"))
        assertFalse(source.contains("decompose_trigger_signals"))
        assertFalse(rules.contains("decompose_trigger_signals:"))
    }

    @Test
    fun homeDispatchesEachRequestToTheSingleRuntimeLoopOnce() {
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        val rules = projectFile("app/src/main/assets/rules/automation_prompts.md").readText()
        val builder = File(
            projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").parentFile,
            "LoopContinuationPromptBuilder.kt"
        )

        assertFalse(builder.exists())
        assertFalse(viewModel.contains("AGENT_LOOP"))
        assertFalse(viewModel.contains("isInLoopMode"))
        assertFalse(viewModel.contains("while (shouldContinue)"))
        assertFalse(rules.contains("## loop_continuation_prompt"))
        assertTrue(rules.contains("## app_takeover_run_prompt"))
    }

    /** The loop has no configured or hidden round-count limit. */
    @Test
    fun modelLoopHasNoRoundLimit() {
        val rules = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertFalse(rules.contains("default_ui_steps"))
        assertFalse(rules.contains("max_no_progress_actions"))
        assertFalse(rules.contains("default_max_device_rounds:"))
        assertFalse(runtime.contains("runtimeMaxUiSteps"))
        assertFalse(operator.contains("maxRounds"))
    }

    @Test
    fun evidenceRejectedCompletionReturnsFactsToTheNormalCountedLoop() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val completionRejectionBlock = operator.substringAfter("if (rejectedCompletionResult != null) {")
            .substringBefore("} else if (guardedRejectionResult != null)")

        assertTrue(operator.contains("val rejectedCompletionResult = when"))
        assertTrue(operator.contains("prematureTaskDone -> guardedRejectionResult"))
        assertTrue(operator.contains("completionEvidenceBlockResult != null -> completionEvidenceBlockResult"))
        assertFalse(completionRejectionBlock.contains("requestFinalReplyRepair("))
        assertFalse(completionRejectionBlock.contains("groundedCompletionFallback"))
        assertTrue(completionRejectionBlock.contains(
            "ungrounded completion rejected; result returned to the normal model loop"
        ))
        assertFalse(completionRejectionBlock.contains("rounds--"))
        assertFalse(operator.contains("ModelProtocolRepairTracker"))
        assertFalse(operator.contains("ModelProtocolFailure"))
    }

    @Test
    fun textInputFactsReturnToTheNormalLoopWithoutASpecialRecoveryBudget() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertTrue(operator.contains("toolDiagnosticCode == \"TEXT_INPUT_DUPLICATE_VERIFIED\""))
        assertTrue(operator.contains("toolDiagnosticCode == \"TEXT_INPUT_TARGET_TRANSITIONED\""))
        assertFalse(operator.contains("TEXT_INPUT_RETRY_EXHAUSTED"))
        assertFalse(operator.contains("textInputRecoveryRoundUsed"))
        assertFalse(operator.contains("requestTextInputRecoveryRound"))
        assertFalse(operator.contains("one text input recovery round granted"))
    }

    @Test
    fun locallyRejectedCallsAreSettledBeforeAnyLoopContinue() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        val settlement = operator.indexOf("settleLocallyRejectedToolCall(")
        val planContinue = operator.indexOf("if (planRejectionCode != null)", settlement)
        val selfManagementContinue = operator.indexOf("if (selfManagementTool)", settlement)
        val hardBlockContinue = operator.indexOf("if (hardBlockResult != null)", settlement)

        assertTrue(settlement >= 0)
        assertTrue(planContinue > settlement)
        assertTrue(selfManagementContinue > settlement)
        val rejectedContinue = operator.indexOf("if (hardBlockResult != null || toolNotExecuted)", settlement)
        assertTrue(rejectedContinue > settlement)
    }

    @Test
    fun durableSettlementUsesExecutionEvidenceWithoutRouteStrategyPreflight() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val settlement = operator.substringAfter("suspend fun settleDurableTool(")
            .substringBefore("suspend fun processToolCalls()")

        assertTrue(settlement.contains("executionStatusOverride ?: output.executionStatus"))
        assertTrue(settlement.contains("result = enrichedOutput.copy("))
        assertFalse(settlement.contains("ToolExecutionStatus.NOT_EXECUTED"))
        assertTrue(settlement.contains("output.diagnosticCode"))
        assertFalse(operator.contains("toolExecutor.preflightToolCall(call)"))
        assertFalse(operator.contains("toolPreflightRejection"))
        assertTrue(operator.contains("blocked = toolNotExecuted"))
        assertTrue(operator.contains("toolNotExecuted -> \"not_executed\""))
    }

    @Test
    fun newTaskContextComesFromDurablePublicConversationEntries() {
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())

        val history = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/ConversationHistoryReader.kt").readText()
        assertTrue(runtime.contains("conversationHistoryReader.read(activeTurn.scope.threadId"))
        assertTrue(history.contains("events.threadReadModel(threadId)"))
        assertTrue(history.contains("thread.conversation.entries"))
        assertTrue(history.contains("PublicAssistantText.sanitize(it.text)"))
        assertFalse(runtime.contains("msg.content.contains(\"</process>\")"))
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
