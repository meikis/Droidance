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

class OperatorRuntimeRulesSourceContractTest {
    @Test
    fun runtimeUsesTypedHarnessPolicyAndExternalizedMessages() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
        val prompts = (projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText() + "\n" +
            projectFile("app/src/main/assets/rules/model_context.md").readText())

        assertTrue(operator.contains("val harnessPolicy = ruleConfigManager.harnessPolicy()"))
        assertTrue(operator.contains("val thresholds = harnessPolicy.runtimeThresholds"))
        assertFalse(operator.contains("runtimeToolSets"))
        assertTrue(operator.contains("ruleConfigManager.message(key)"))
        assertTrue(runtime.contains("ruleConfigManager.harnessPolicy().runtimeThresholds"))
        assertTrue(runtime.contains("ruleConfigManager.message(key)"))
        assertTrue(policy.contains("schema_version: 1"))
        assertTrue(policy.contains("runtime_thresholds:"))
        assertFalse(policy.lineSequence().any { it.trim() == "messages:" })
        assertTrue(prompts.contains("messages:"))
        assertFalse(policy.contains("scroll_stuck_window"))
        assertFalse(prompts.contains("completion_evidence_required:"))
        assertTrue(operator.contains("code = \"COMPLETION_EVIDENCE_REQUIRED\""))
        assertFalse(operator.contains("collectionSummaryGroundingIssue"))
        assertFalse(operator.contains("runtime_policy.md"))
        assertFalse(runtime.contains("runtime_policy.md"))
        assertFalse(projectFile("app/src/main/assets/rules/runtime_policy.md").exists())
    }

    @Test
    fun tapMissKeepsWholeScreenAndNeverInjectsAHiddenRetargetTap() {
        val operatorFile = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskCoordinator.kt")
        val operator = com.droidance.agent.coordinationSources()
        val executor = com.droidance.agent.executorSources()
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()

        assertFalse(File(operatorFile.parentFile!!, "PrecisionRecoveryGate.kt").exists())
        assertFalse(operator.contains("PrecisionRecoveryGate"))
        assertFalse(executor.contains("observeScreenTool.publishTapMissZoom("))
        assertFalse(executor.contains("findBestTapFallbackElement("))
        assertFalse(observe.contains("suspend fun publishTapMissZoom("))
        assertFalse(observe.contains("fun setLastActionTarget("))
        assertFalse(executor.contains("observeScreenTool.setLastActionTarget(targets.last())"))
    }

    @Test
    fun emptyModelOutputClosesWithoutAHiddenProtocolRepairLoop() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val branch = operator.substringAfter("if (toolCalls.isEmpty() && fullContent.isBlank()) {")
            .substringBefore("val interventionReply")

        assertTrue(branch.contains("settlePendingDurableRound(\"EMPTY_MODEL_RESPONSE\")"))
        assertTrue(branch.contains("return modelProtocolFailure(guardText(\"empty_final_response\"))"))
        assertFalse(operator.contains("requestEmptyModelProtocolRepair"))
        assertFalse(operator.contains("EMPTY_MODEL_RESPONSE_RETRY"))
        assertFalse(operator.contains("ModelProtocolRepairTracker"))
    }

    @Test
    fun technicalGuardStopsDoNotMasqueradeAsHumanBoundaries() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertFalse(operator.contains("onManualTakeoverNeeded(wallStopText)"))
        assertFalse(operator.contains("onManualTakeoverNeeded(boundsFailText)"))
        assertFalse(operator.contains("onManualTakeoverNeeded(guardText(\"channel_unavailable_hint\"))"))
    }

    @Test
    fun evidenceLedgerCannotBeProjectedIntoAUserVisibleCompletion() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertFalse(operator.contains("groundedCompletionFallback"))
        assertFalse(operator.contains("buildGroundedCompletionFallback"))
    }

    @Test
    fun partialEvidenceNeverTurnsIntoHarnessManufacturedSuccess() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())

        assertFalse(operator.contains("scoped grounded result ready; withheld"))
        assertFalse(operator.contains("val scopedResultEvidenceReady"))
        assertFalse(operator.contains("groundedObservationCompletionResult"))
        assertFalse(operator.contains("grounded observation satisfies evidence; closing locally"))
        assertFalse(operator.contains("usesConfiguredMovementFamilyGate("))
        assertFalse(operator.contains("passiveMovementTools"))
    }

    @Test
    fun groundedDeviceExecutionRepairsMissingCompletionWithinTheSameLoop() {
        val operator = (projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskToolRound.kt").readText() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val branch = operator.substringAfter("// Once tools have been attempted")
            .substringBefore("// 解析结果")

        assertTrue(branch.contains("return completionProtocolUnconfirmed()"))
        assertTrue(branch.contains("completionRejectionCycleGuard.rejectMissingSignal()"))
        assertTrue(branch.contains("return null"))
        assertFalse(branch.contains("takeoverManaged"))
        assertFalse(branch.contains("finalTerminal = TurnTerminal.DONE"))
        assertFalse(branch.contains("completionEvidenceReferences()"))
        assertFalse(branch.contains("rounds--"))
    }

    @Test
    fun structuredCompletionUsesOnlyExplicitReferences() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val branch = operator.substringAfter("fun evaluateCompletionEvidenceGuards(")
            .substringBefore("suspend fun evaluateToolCallGuards(")

        assertTrue(branch.contains("ActionControlProtocol.completionReferencesValid(control, evidenceLedger::hasCompletionEvidence)"))
        assertFalse(branch.contains("fallback = evidenceLedger.completionEvidenceReferences()"))
        assertFalse(branch.contains("completionAuditMissing"))
        assertFalse(branch.contains("unmetRequirements"))
        assertTrue(branch.contains("latestTerminalObservationRef"))
        assertTrue(branch.contains("terminalObservationReferenceValid"))
        assertFalse(branch.contains("terminalObservationCited"))
        assertTrue(branch.contains("control?.terminalObservationRef.isNullOrBlank()"))
        assertTrue(branch.contains(".requiresTerminalObservation("))
        assertTrue(branch.contains("fullCompletionRequested = fullCompletionRequested"))
        assertTrue(branch.contains("takeoverManaged = takeoverManaged"))
        assertFalse(branch.contains("val terminalObservationRequired = executionStarted"))
        assertTrue(branch.contains("!takeoverManaged &&"))
        assertFalse(branch.contains("completionGoalAuditBlockResult"))
    }

    @Test
    fun currentRawFrameIsRegisteredBeforeTheModelCanClaimCompletion() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val executor = com.droidance.agent.executorSources()
        val requestSetup = operator.substringAfter("val hasCurrentScreenContext = canAttachScreenshot")
            .substringBefore("val durableContextDecision")

        assertTrue(requestSetup.contains("val detailId = java.util.UUID.randomUUID()"))
        assertTrue(requestSetup.contains("val decisionFrameObservation = evidenceRecorder.recordDecisionFrame("))
        assertTrue(requestSetup.indexOf("recordDecisionFrame(") < requestSetup.indexOf("TaskWorkingState("))
        assertTrue(requestSetup.contains("latestClosedObservationId = terminalObservationRefVisibleToModel"))
        assertTrue(operator.contains("screenshotRef = detailId"))
        assertFalse(executor.contains("reserveLlmRoundDetailId"))
    }

    @Test
    fun echoedInternalScreenProtocolDoesNotBecomeAnAcceptedFinal() {
        val operator = (projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskToolRound.kt").readText() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val branch = operator.substringAfter("val publicFinal = PublicAssistantText.sanitize")
            .substringBefore("// 解析结果")

        assertFalse(branch.contains("requestFinalReplyRepair("))
        assertTrue(branch.contains("return completionProtocolUnconfirmed()"))
        assertTrue(branch.contains("completionRejectionCycleGuard.rejectMissingSignal()"))
        assertTrue(branch.contains("return null"))
        assertFalse(branch.contains("groundedCompletionFallback"))
    }

    @Test
    fun finalReviewScaffoldingIsAbsentFromTheAgentLoop() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val planner = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/harness/ModelRoundContract.kt"
        ).readText()

        assertFalse(operator.contains("FinalizationGateState"))
        assertFalse(operator.contains("finalizationGate.active"))
        assertFalse(operator.contains("finalization_tool_block"))
        assertFalse(operator.contains("coordinateRecoveryActive"))
        assertTrue(operator.contains("val tools = durableTurn.stablePrefix.tools"))
        assertTrue(operator.contains("val toolsForRound = tools"))
        assertTrue(operator.contains("val presentedToolNames = toolsForRound.mapTo(linkedSetOf(), ToolDefinition::name)"))
        assertTrue(operator.contains("tools = toolsForRound"))
        assertTrue(operator.contains("val presentedToolNames = modelRound.presentedToolNames"))
        assertTrue(operator.contains("presentedToolNames = presentedToolNames"))
        assertFalse(operator.contains("RepairPurpose.FINAL_REPLY"))
        assertFalse(planner.contains("UNEXPECTED_TOOL_DURING_FINAL_REPAIR"))
        assertFalse(operator.contains("resumeExecutionFromFinalReview"))
        assertFalse(operator.contains("reflectedActionBlockResult"))
        assertFalse(operator.contains("coordinateRecoveryActionBlockResult"))
    }

    @Test
    fun modelAuthoredActionAssessmentAndTaskStateAreAbsent() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val protocol = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/ActionControlProtocol.kt").readText()

        listOf(
            "pendingActionAssessment",
            "previousAssessmentBlockReason",
            "CorroboratedProgressPolicy",
            "OperatorTaskState"
        ).forEach { obsolete -> assertFalse(operator.contains(obsolete)) }
        listOf(
            "task_step",
            "action_intent",
            "action_target",
            "previous_action_verdict",
            "previous_action_evidence"
        ).forEach { obsolete -> assertFalse(protocol.contains(obsolete)) }
    }

    @Test
    fun explicitDoneHasNoPassiveLoopRecoveryLayer() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val explicitDone = operator.indexOf("// `done(results)` is itself the model-authored structured final reply")

        assertTrue(explicitDone >= 0)
        assertFalse(operator.contains("Passive-loop detection"))
        assertFalse(operator.contains("passiveLoopActionAtoms"))
    }

    @Test
    fun everyRoundReanchorsTheOriginalTaskAtTheContextTail() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val tailBuilder = operator.substringAfter("private fun currentModelTailMessage(")
            .substringBefore("private fun appendFrameMessage")

        assertTrue(tailBuilder.contains("taskGoal: String"))
        assertTrue(tailBuilder.contains("appendLine(\"<active_task>\")"))
        assertTrue(tailBuilder.contains("appendLine(taskGoal.trim())"))
    }

    @Test
    fun operatorAllowsPlainFinalAnswersAndKeepsRouteVerificationNonBlocking() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val executor = com.droidance.agent.executorSources()

        assertFalse(operator.contains("forceToolCallAfterTimeout"))
        assertFalse(operator.contains("prepareModelTimeoutRecovery"))
        assertFalse(operator.contains("forceToolCall = executionStarted ||"))
        assertFalse(operator.contains("finalizationGate.active"))
        assertFalse(operator.contains("toolExecutor.hasPendingDeepLinkVerification()"))
        assertFalse(operator.contains("ToolCatalog.deepLinkVerificationToolNames"))
        assertFalse(operator.contains("hasCompletionEvidence(completionEvidenceRefs)"))
        assertTrue(operator.contains("completionReferencesValid(control, evidenceLedger::hasCompletionEvidence)"))
        val manualReset = operator.substringAfter("fun resetAfterManualResume(")
            .substringBefore("fun ")
        assertFalse(manualReset.contains("toolExecutor.clearPendingDeepLinkVerification()"))
        assertFalse(executor.contains("fun clearPendingDeepLinkVerification()"))
    }

    @Test
    fun executionProgressUsesTypedEvents() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val executor = com.droidance.agent.executorSources()
        val event = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/event/AgentEventContract.kt").readText()

        listOf(
            "TURN_STARTED",
            "MODEL_ROUND_STARTED",
            "MODEL_ROUND_COMPLETED",
            "TOOL_STARTED",
            "TOOL_FINISHED",
            "USER_INTERVENTION_REQUESTED",
            "EXECUTION_ATTEMPT_STATE_CHANGED",
            "TURN_COMPLETED"
        ).forEach { kind -> assertTrue("Execution event kind missing: $kind", event.contains(kind)) }
        assertFalse(runtime.contains("executionEventJournal"))
        assertFalse(operator.contains("executionEventJournal"))
        val session = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundSession.kt").readText()
        val committer = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundCommitter.kt").readText()
        assertTrue(operator.contains("roundSession.commit("))
        assertTrue(session.contains("committer.commit("))
        assertTrue(session.contains("coordinator.commit("))
        assertTrue(committer.contains("coordinator.commit("))
        assertFalse(session.contains("executionEventJournal"))
        assertFalse(committer.contains("executionEventJournal"))
        assertTrue(operator.contains("put(\"records\", evidence.records)"))
        assertFalse(executor.contains("executionEventJournal"))
    }

    @Test
    fun eachRoundCarriesOriginalSourcesAndBoundedFindings() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val ledger = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskEvidenceLedger.kt").readText()

        assertTrue(operator.contains("TaskContinuationContext.render("))
        assertFalse(operator.contains("MAX_ACTIVE_OBSERVATION_NOTE_CHARS"))
        assertTrue(operator.contains("recordDecisionFrame("))
        val decisionFrame = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorEvidenceRecorder.kt").readText().substringAfter("fun recordDecisionFrame(")
            .substringBefore("fun recordObservedState(")
        assertFalse(decisionFrame.contains("Current screenshot frame"))
        assertTrue(decisionFrame.contains("if (rawText.isNullOrBlank()"))
        assertTrue(ledger.contains("beforeObservationId"))
        assertTrue(ledger.contains("actionReceiptId"))
        assertTrue(ledger.contains("original_sources=["))
        assertTrue(ledger.contains("trimEnd().take(maxChars)"))
    }

    @Test
    fun operatorDoesNotDuplicatePromptOrConversationIntoDebugMarkdown() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val debugLog = projectFile("app/src/main/java/com/droidance/debug/DebugLogManager.kt").readText()

        assertFalse(operator.contains("debugLogManager.recordSystemPrompt("))
        assertFalse(operator.contains("debugLogManager.dumpConversation("))
        assertFalse(debugLog.contains("fun recordSystemPrompt("))
        assertFalse(debugLog.contains("fun dumpConversation("))
        assertFalse(debugLog.contains("ChatMessage"))
    }

    @Test
    fun successfulResultCardIsAUserVisibleTerminalWithoutAnotherModelRound() {
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()
        val operator = (com.droidance.agent.coordinationSources() + "\n" +
            projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/context/ModelContextAssembler.kt").readText())
        val branch = operator.substringAfter("if (selfManagementTool) {")
            .substringBefore("if (tc.name == \"done\"")

        assertTrue(catalog.contains("setOf(\"present_result_card\", \"done\")"))
        assertTrue(branch.contains("ToolResultStatus.isSuccess(result)"))
        assertTrue(branch.contains("resultCard.toUserFacingText()"))
        assertTrue(branch.contains("finalTerminal = TurnTerminal.DONE"))
        assertTrue(branch.contains("break"))
        val terminalGuard = operator.indexOf("if (finalTerminal == TurnTerminal.DONE)")
        val protocolRejections = operator.indexOf("val protocolRejections")
        assertTrue(terminalGuard >= 0)
        assertTrue(terminalGuard < protocolRejections)
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists() || path.endsWith("runtime_policy.md")) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
