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

class AgentLoopSourceContractTest {
    @Test
    fun completionCandidatesStayBufferedUntilTheCompletionGuardsAcceptThem() {
        val source = com.droidance.agent.coordinationSources()
        val request = sourceSection(source, "val streamCapture = operatorStreamCollector.collect(",
            "fullContent = streamCapture.content")
        assertTrue(request.contains("onContent = {}"))
        assertFalse(request.contains("onStreamContent("))
        val publicBlocks = sourceSection(source, "val durablePublicBlocks = listOfNotNull(",
            "val deviceBoundToolNames =")
        assertTrue(publicBlocks.contains("interventionReply ?: roundVisibleMessage.takeIf"))
        assertTrue(publicBlocks.contains("toolCalls.none { call -> call.name in ToolCatalog.finalizationToolNames }"))
    }

    @Test
    fun stepTerminalSeparatesSystemErrorsFromModelTaskFailure() {
        val taskPlan = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TurnResult.kt").readText()
        val terminal = taskPlan.substringAfter("enum class TurnTerminal")
            .substringBefore("}")

        assertTrue(terminal.contains("DIRECT_REPLY"))
        assertTrue(terminal.contains("DONE"))
        assertTrue(terminal.contains("FAILED"))
        assertTrue(terminal.contains("SYSTEM_ERROR"))
        assertFalse(terminal.contains("CHAT"))
    }

    @Test
    fun toolFreeReplyAfterRetryOrUserSteerDoesNotStartExecution() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val plainTextBranch = sourceSection(operator, "// A tool-free reply remains conversational after a retry or user steer.",
            "// Once tools have been attempted")

        assertTrue(plainTextBranch.contains("!executionStarted"))
        assertTrue(plainTextBranch.contains("totalToolCalls == 0"))
        assertTrue(plainTextBranch.contains("streamFallbackContent(fullContent)"))
        assertTrue(plainTextBranch.contains("terminal = TurnTerminal.DIRECT_REPLY"))
        assertFalse(plainTextBranch.contains("ensureExecutionStarted()"))
    }

    @Test
    fun firstToolCommitsTheVisibleModelDecisionBeforeStartingExecutionExactlyOnce() {
        val operator = com.droidance.agent.coordinationSources()
        val publicIndex = operator.indexOf("val durablePublicBlocks = listOfNotNull(")
        val commitIndex = operator.indexOf("val durableCommit = commitDurableRound(", publicIndex)
        val planIndex = operator.indexOf("val roundPlan = durablePreparedExecution.roundPlan", commitIndex)
        val startIndex = operator.indexOf("ensureExecutionStarted(toolCalls[executionStartCall.providerOrder].name)", planIndex)
        assertTrue(publicIndex >= 0 && commitIndex > publicIndex && planIndex > commitIndex && startIndex > planIndex)
        assertTrue(operator.contains("publicBlocks = durablePublicBlocks"))
        assertTrue(operator.contains("PublicAssistantText.sanitize(fullContent).trim()"))
        assertTrue(operator.contains("var executionStarted = false"))
        assertTrue(operator.contains("if (executionStarted) return"))
        assertTrue(operator.contains("onExecutionStarted(firstToolName)"))
    }

    @Test
    fun firstRoundCompletionMetadataStaysOnTheDirectReplyPath() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val toolBranch = operator.substringAfter("if (toolCalls.isNotEmpty()) {")
            .substringBefore("for ((toolIdx, tc) in toolCalls.withIndex())")
        val completionBranch = operator.substringAfter("if (toolExecutor.isCompletionRequested()) {")
            .substringBefore("if (roundPlan.followUp == RoundFollowUp.WAIT_FOR_USER")

        assertTrue(toolBranch.contains("firstDeviceExecutionStartCall()"))
        assertTrue(completionBranch.contains("if (!executionStarted)"))
        assertFalse(completionBranch.contains("diagnostics.recordRouterVerdict"))
        assertTrue(completionBranch.contains("completionTerminal(requestedCompletionStatus, executionStarted = false)"))
        assertTrue(operator.contains("var deviceExecutionRequested = false"))
        assertTrue(operator.contains("it.contract.binding == ToolBinding.DEVICE_BOUND"))
        assertTrue(operator.contains("successfulDoneRequested && deviceExecutionRequested"))
    }

    @Test
    fun turnLocalToolsBypassTheDeviceExecutionContext() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val executor = com.droidance.agent.executorSources()
        val executionFunction = operator.substringAfter("suspend fun executeSelectedTool(")
            .substringBefore("val durableScope")

        assertTrue(executionFunction.contains("ToolBinding.TURN_LOCAL"))
        assertTrue(executionFunction.contains("toolExecutor.executeTurnLocal(toolCall)"))
        assertTrue(executionFunction.contains("toolExecutor.execute(toolCall)"))
        assertTrue(executor.contains("suspend fun executeTurnLocal(toolCall: ToolCall)"))
        assertTrue(executor.contains("ToolBinding.TURN_LOCAL -> null"))
    }

    @Test
    fun completionCallsAreNeverRewrittenToUnvalidatedPlainText() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        assertFalse(operator.contains("firstRoundCompletionOnlyDirectReply"))
        assertFalse(operator.contains("rawParsedToolCalls = emptyList()"))
    }

    @Test
    fun durableStartReceiptIsTrackedSeparatelyFromPhysicalExecution() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val eventStore = projectFile(
            "core/persistence/src/main/java/com/droidance/core/persistence/agent/event/RoomAgentEventStore.kt"
        ).readText()
        val session = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundSession.kt").readText()
        val durableBegin = session.substringAfter("suspend fun begin(").substringBefore("suspend fun settle(")
        val durableCommit = session.substringAfter("suspend fun commit(")
        val committer = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundCommitter.kt").readText()
        val toolBranch = operator.substringAfter("if (toolCalls.isNotEmpty()) {")
            .substringBefore("for ((toolIdx, tc) in toolCalls.withIndex())")
        assertTrue(operator.contains("durableTurn.startCommitted,"))
        assertTrue(durableBegin.contains("startCommitted = publicStartCommitted"))
        assertTrue(durableCommit.contains("if (result.publicStartCommitted) markPublicStartCommitted()"))
        assertTrue(committer.contains("PublicMessageKind.USER_INTERVENTION"))
        assertTrue(eventStore.contains("PublicMessageKind.USER_INTERVENTION"))
        assertTrue(toolBranch.contains("val roundPlan = durablePreparedExecution.roundPlan"))
        assertFalse(operator.contains("roundSession.markPublicStartCommitted()"))
        assertFalse(operator.contains("operatorRoundScheduler.plan("))
        assertFalse(durableBegin.contains("startCommitted = executionStarted"))
        assertFalse(toolBranch.contains("startCommitted = executionStarted"))
    }

    @Test
    fun durableRoundBeginsBeforeTheRequestButBindsTheDeviceOnlyAfterADeviceToolCall() {
        val operator = com.droidance.agent.coordinationSources()
        val requestBoundary = sourceSection(operator, "val roundTimeoutMs =",
            "val streamCapture = operatorStreamCollector.collect(")
        val postResponseBoundary = sourceSection(operator, "val durablePublicBlocks =", "val durableCommit =")
        val bindingIndex = postResponseBoundary.indexOf("ensureDurableExecutionBinding(repairPurpose)")
        assertTrue(requestBoundary.contains("beginDurableRound(repairPurpose)"))
        assertFalse(requestBoundary.contains("ensureDurableExecutionBinding(repairPurpose)"))
        assertTrue(postResponseBoundary.contains("toolCalls.any { call ->"))
        assertTrue(postResponseBoundary.contains("call.name in deviceBoundToolNames"))
        assertTrue(postResponseBoundary.contains("contract.forArguments(call.arguments).binding == ToolBinding.DEVICE_BOUND"))
        assertFalse(postResponseBoundary.contains("toolPreflightRejections"))
        val selectedIndex = postResponseBoundary.indexOf("if (deviceToolSelected)")
        assertTrue(selectedIndex >= 0 && bindingIndex > selectedIndex)
    }

    @Test
    fun toolDrivenCompletionUsesOnlyTheAcceptedStructuredSummary() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val completionBranch = operator.substringAfter(
            "if (toolExecutor.isCompletionRequested())"
        ).substringBefore("val requestedCompletionStatus")

        assertTrue(completionBranch.contains("val conclusion = completionSummary"))
        assertFalse(completionBranch.contains("modelReply"))
    }

    @Test
    fun durableToolSettlementRetainsConfiguredScreenEvidenceForTheDetailPage() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val settlement = operator.substringAfter("suspend fun durableToolAttachments(")
            .substringBefore("suspend fun processToolCalls()")

        assertTrue(settlement.contains("ResultImageRole.INPUT"))
        assertTrue(settlement.contains("ResultImageRole.BEFORE_AND_AFTER"))
        assertTrue(settlement.contains("toolExecutor.awaitPendingScreenshot()"))
        assertTrue(settlement.contains("outputFrameBytes ?: sourceDecisionBytes"))
        assertFalse(settlement.contains("ToolCallDetailStore"))
        assertTrue(settlement.contains("resolvedObservationId"))
        assertTrue(settlement.contains("output.observationId"))
        assertTrue(settlement.contains("materializeDetailAttachments(blobStore, pending)"))
        assertTrue(settlement.contains("eventStore.finishToolWithAttachments("))
        assertFalse(settlement.contains("eventStore.finishTool("))
    }

    @Test
    fun channelDownTerminationUsesTypedClaimPolicies() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertTrue(operator.contains("fallbackText(\"termination_channel_down\")"))
        assertTrue(operator.contains("fallbackText(\"termination_air_channel_recovery_required\")"))
        assertTrue(operator.contains("fallbackText(\"termination_air_pairing_required\")"))
        assertFalse(operator.contains("guardText(\"termination_channel_down\")"))
    }

    @Test
    fun task_time_limit_preserves_reason_for_bounded_stopped_reply() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val budgetBranch = operator.substringAfter("val elapsedMs = activeElapsedMs()")
            .substringBefore("val repairPurpose = RepairPurpose.NONE")

        assertTrue(budgetBranch.contains("failureReason = TurnFailureReason.TASK_TIME_LIMIT"))
        val execution = sourceSection(operator, "suspend fun execute(): TurnResult", "internal fun promptValuePreview(")
        val loopIndex = execution.indexOf("result = try {")
        val sealIndex = execution.indexOf("while (!trySealInput())")
        val explainIndex = execution.indexOf("explainStoppedTask(result)")
        assertTrue(loopIndex >= 0 && sealIndex > loopIndex && explainIndex > sealIndex)
        assertTrue(execution.contains("runOperatorLoop()"))
        assertFalse(operator.contains("verifiedProgressFallback("))
    }

    @Test
    fun firstToolDoesNotRequirePresentationOnlyProtocolArguments() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val toolBranch = operator.substringAfter("if (toolCalls.isNotEmpty()) {")
            .substringBefore("for ((toolIdx, tc) in toolCalls.withIndex())")

        assertFalse(operator.contains("FirstActionCommunication"))
        assertFalse(operator.contains("USER_MESSAGE_ARGUMENT"))
        assertTrue(operator.contains("val roundVisibleMessage = PublicAssistantText.sanitize(fullContent).trim()"))
        assertTrue(toolBranch.contains("roundPlan.firstDeviceExecutionStartCall()"))
        assertFalse(operator.contains("pseudoToolCalls"))
        assertFalse(operator.contains("buildTaskStartAcknowledgement("))

        assertFalse(operator.contains("first_action_user_message"))
        assertFalse(operator.contains("languageManager.processingLabel()"))
    }

    @Test
    fun modelRoundsUseDurableEventStoreInsteadOfLegacyJournal() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertFalse(operator.contains("executionEventJournal"))
        assertFalse(operator.contains("modelDecisionSummary("))
        val session = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundSession.kt").readText()
        val committer = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundCommitter.kt").readText()
        assertTrue(operator.contains("roundSession.commit("))
        assertTrue(session.contains("committer.commit("))
        assertTrue(session.contains("coordinator.commit("))
        assertTrue(committer.contains("coordinator.commit("))
        assertFalse(session.contains("executionEventJournal"))
        assertFalse(committer.contains("executionEventJournal"))
        assertTrue(committer.contains("NormalizedModelRound.create("))
        assertTrue(operator.contains("put(\"records\", evidence.records)"))
    }

    @Test
    fun missingPageObservationIsDiagnosticAndNeverBlocksAValidDeviceAction() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
        val observationBlock = operator.substringAfter(
            "val groundedAction = GroundedActionProtocol.extractScreenEvidence("
        ).substringBefore("val completionSubmission")

        assertTrue(observationBlock.contains("sourceExists = evidenceLedger::hasCompletionEvidence"))
        assertFalse(observationBlock.contains("requiredFor ="))
        assertTrue(observationBlock.contains("retentionOutcomes"))
        assertTrue(observationBlock.contains("debugLogManager.appendEvent"))
        assertFalse(observationBlock.contains("continue@operatorLoop"))
        assertFalse(observationBlock.contains("requestFinalReplyRepair"))
        assertFalse(operator.contains("requestScreenObservationRepair"))
        assertFalse(policy.contains("screen_observation_required:"))
    }

    @Test
    fun groundedScreenEvidenceIsRecordedWithoutLegacyPageLogProjection() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertTrue(operator.contains("put(\"records\", evidence.records)"))
        assertFalse(operator.contains("sameRoundGroundedFactIds"))
        assertFalse(operator.contains("val visiblePageObservation"))
        assertFalse(operator.contains("executionEventJournal.screenObserved("))
    }

    @Test
    fun exactRepeatGuardDoesNotRequestUserTakeoverOrInferStrategy() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertFalse(operator.contains("onManualTakeoverNeeded(scrollLoopStopText)"))
        assertFalse(operator.contains("onManualTakeoverNeeded(noProgressStopText)"))
        assertFalse(operator.contains("\"source\" to \"no_progress_guard\""))
        assertFalse(operator.contains("\"source\" to \"scroll_loop_guard\""))
        assertFalse(operator.contains("noProgressStopText"))
        assertFalse(operator.contains("scrollLoopStopText"))
        assertTrue(operator.contains("exactActionRepeatGuard.wasExecuted("))
        assertTrue(operator.contains("code = \"EXACT_ACTION_REPEATED\""))
        assertFalse(operator.contains("reflectedActionGate"))
        assertFalse(operator.contains("passiveLoopActionAtoms"))
    }

    @Test
    fun exactRepeatIdentityChangesWithTheObservationSequence() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val guard = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/ExactActionRepeatGuard.kt").readText()

        assertTrue(operator.contains("\"\${envelope.executionId}:\${envelope.sequence}\""))
        assertTrue(guard.contains("observationId.trim()"))
        assertFalse(guard.contains("goalProgress"))
    }

    @Test
    fun completionRejectionIsStructuredWithoutHarnessAuthoredRepairInstructions() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val harness = (projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText() + "\n" +
            projectFile("app/src/main/assets/rules/model_context.md").readText())
        assertTrue(operator.contains("fun TaskTurnSession.completionEvidenceRejection("))
        assertTrue(operator.contains("code = \"COMPLETION_EVIDENCE_REQUIRED\""))
        assertTrue(operator.contains("evidenceRefs = evidenceRefs"))
        assertTrue(operator.contains("completionEvidenceRejection(control, latestTerminalObservationRef)"))
        assertTrue(operator.contains("ungrounded completion rejected; result returned to the normal model loop"))
        assertFalse(operator.contains("ModelProtocolRepairTracker"))
        assertFalse(operator.contains("completionEvidenceRepairCount"))
        assertFalse(operator.contains("completionGoalAuditText"))
        assertFalse(harness.contains("completion_evidence_repair:"))
        assertFalse(harness.contains("completion_evidence_required:"))
    }

    @Test
    fun imageOnlyDecisionFrameStillGetsACurrentObservationReference() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertTrue(operator.contains("val decisionFrameText = textObservationForRound"))
        assertTrue(operator.contains("takeIf { canAttachScreenshot && !pendingScreenshot.isNullOrBlank() }"))
        assertTrue(operator.contains("Current Android screen frame:"))
        assertTrue(operator.contains("rawText = decisionFrameText"))
        assertTrue(operator.contains("rawObservationId = decisionFrameObservation?.id"))
    }

    @Test
    fun routeOscillationIsLeftToAgentJudgment() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        assertFalse(operator.contains("cycleActions.forEach"))
        assertFalse(operator.contains("consecutiveNoProgressStreak"))
        assertFalse(operator.contains("noProgressRecoveryAttempted"))
    }

    @Test
    fun sharedOperatorContextCarriesSoulAndRelevantUserMemory() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val runUiTask = runtime.substringAfter("private suspend fun runUiTask(")
            .substringBefore("private fun ensureLocalUiAutomationResidency(")
        val stableContext = sourceSection(runtime, "private fun operatorStableSystemContext(): String",
            "private fun skillAuthoringContext(): String")
        val turnContext = sourceSection(runtime, "private suspend fun operatorTurnContext(",
            "private fun ensureLocalUiAutomationResidency(")
        assertTrue(stableContext.contains("runtimePromptPolicy.agentSoulBlock(agentWorkspaceStore.readCustomSoul(activeAgent))"))
        assertTrue(stableContext.contains("runtimePromptPolicy.memoryBehaviorBlock()"))
        assertTrue(runtime.contains("progressiveMemoryContextProjector.project("))
        assertFalse(turnContext.contains("MemoryContextPromptRenderer.render("))
        assertTrue(runtime.contains("extraSystemContext = operatorStableSystemContext()"))
        assertTrue(runtime.contains("eventStore.recordMemoryContextLoaded(taskSession.durableTurn.scope, payload)"))
        val coordinator = com.droidance.agent.coordinationSources()
        assertTrue(coordinator.contains("it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED"))
        assertTrue(runUiTask.contains("turnContext = turnContext"))
        assertTrue(agentLoopUsesFrozenPrefix())
        assertFalse(runUiTask.contains("outputLanguageInstruction()"))
    }

    @Test
    fun actualStreamPlanDrivesBothImageRequestAndReplayFrame() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val planIndex = operator.indexOf("val streamRequestPlan = llmRouter.planStreamChat(")
        val payloadIndex = maxOf(
            operator.indexOf("val messagesForLLM = modelContextAssembler.buildMessagesForModel("),
            operator.indexOf("var messagesForLLM = modelContextAssembler.buildMessagesForModel(")
        )
        val promptIndex = operator.indexOf("val systemPrompt = durableTurn.stablePrefix.systemPrompt")

        assertTrue(planIndex >= 0)
        assertTrue(payloadIndex > planIndex)
        assertTrue(promptIndex > planIndex)
        assertTrue(operator.contains("systemPrompt = trustedOperatorSystemPrompt(extraSystemContext)"))
        assertTrue(Regex("""messages = messages,\s*hasImage = pendingScreenshot != null""").containsMatchIn(operator))
        assertTrue(operator.contains("val canAttachScreenshot = streamRequestPlan.imageIncluded"))
        assertTrue(operator.contains("requestPlan = streamRequestPlan"))
        assertTrue(operator.contains("val decisionFrameBytes = pendingScreenshot"))
        assertTrue(operator.contains("?.takeIf { canAttachScreenshot }"))
        assertTrue(operator.contains("?.let(::decodeModelFrame)"))
        assertTrue(operator.contains("hasImage = messagesForLLM.any { !it.imageBase64.isNullOrBlank() || it.imageBase64List.isNotEmpty() }"))
        assertTrue(operator.contains("postActionCaptureScheduled = toolExecutor.schedulesPostActionCapture(tc.name)"))
        assertFalse(operator.contains("The current screenshot is attached to this message"))
    }

    private fun agentLoopUsesFrozenPrefix(): Boolean =
        com.droidance.agent.coordinationSources()
            .contains("val systemPrompt = durableTurn.stablePrefix.systemPrompt")

    @Test
    fun runtimeDoesNotPreRouteEmptyInputToChooseOperatorPerceptionMode() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val runUiTask = runtime.substringAfter("private suspend fun runUiTask(")
            .substringBefore("private fun ensureLocalUiAutomationResidency(")

        assertFalse(runUiTask.contains("llmRouter.canSendVisionMessages()"))
        assertFalse(runUiTask.contains("visionPerception ="))
    }

    @Test
    fun operatorTimeoutIsAlsoAppliedToTheUnderlyingHttpCall() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()

        assertTrue(operator.contains("requestTimeoutMs = timeoutMs"))
        assertTrue(router.contains("call.timeout().timeout(requestTimeoutMs"))
    }

    @Test
    fun takeoverCancellationGenerationIsCapturedAndRegisteredWithTheRequest() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()

        assertTrue(operator.contains("llmRouter.currentCancellationGeneration()"))
        assertTrue(operator.contains("requestCancellationGeneration = cancellationGeneration"))
        assertTrue(router.contains("cancellationGeneration.incrementAndGet()"))
        assertTrue(router.contains("registerActiveCall(call, requestCancellationGeneration)"))
        assertTrue(router.contains("synchronized(activeCalls)"))
    }

    @Test
    fun toolCallsAreNotExecutedEagerlyWhileResponseIsStillStreaming() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val streamCollector = operator.substringAfter("streamChat(")
            .substringBefore("} finally {")

        assertFalse(streamCollector.contains("toolExecutor.execute("))
        assertFalse(operator.contains("eagerResults"))
    }

    @Test
    fun publicContentStreamsImmediatelyWhileHiddenReasoningStaysInternal() {
        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val streamCollector = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText().substringAfter("suspend fun collect(")
            .substringBefore("private fun operatorSystemPrompt")
        val contentBranch = streamCollector.substringAfter("is LLMStreamEvent.Content ->")
            .substringBefore("is LLMStreamEvent.ToolCallChunk ->")
        val reasoningBranch = streamCollector.substringAfter("is LLMStreamEvent.ReasoningChunk ->")

        assertTrue(contentBranch.contains("onContent(event.text)"))
        assertTrue(contentBranch.contains("content += event.text"))
        assertTrue(reasoningBranch.contains("reasoning += event.text"))
        assertFalse(reasoningBranch.contains("onStreamContent"))
    }

    @Test
    fun persistentTakeoverTriggersOrdinaryGuiTurnsWithoutASecondAgent() {
        val taskPlan = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TurnResult.kt").readText()
        assertTrue(taskPlan.contains("takeoverManaged"))

        val operator = ((com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()) + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        assertTrue(operator.contains("manage_app_takeover"))
        assertTrue(operator.contains("takeoverManaged"))

        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        assertTrue(runtime.contains("takeoverResidentStatus"))
        assertTrue(runtime.contains("processAppTakeoverInput"))
        assertTrue(runtime.contains("source = MessageSource.APP_TAKEOVER"))
    }

    private fun sourceSection(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue("Missing source boundary: $start", startIndex >= 0)
        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        assertTrue("Missing source boundary: $end", endIndex >= contentStart)
        return source.substring(contentStart, endIndex)
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
