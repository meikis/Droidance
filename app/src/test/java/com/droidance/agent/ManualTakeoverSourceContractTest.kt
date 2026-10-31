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

class ManualTakeoverSourceContractTest {
    @Test
    fun highRiskAuthorizationRequiresTheExplicitLocalResumePath() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val interaction = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/UserInteractionRequest.kt"
        ).readText()
        val resume = runtime.substringAfter("fun resumeManualTakeover(")
            .substringBefore("fun beginManualTakeoverFromOverlay()")

        assertTrue(interaction.contains("HIGH_RISK_CONFIRMATION"))
        assertTrue(runtime.contains("requestManualTakeover(reason, UserInteractionKind.HIGH_RISK_CONFIRMATION)"))
        assertTrue(executor.contains("riskDecision.requiresConfirmation"))
        assertTrue(executor.contains("requestHighRiskConfirmation(riskDecision.reason)"))
        assertTrue(resume.contains("responseSource = UserInteractionResponseSource.LOCAL_DEVICE"))
        assertTrue(resume.contains("UserInteractionKind.HIGH_RISK_CONFIRMATION"))
        assertTrue(resume.contains("ToolRiskPolicy::confirmNextHighRisk"))
        assertFalse(runtime.contains("RuntimeInputPatterns"))
        assertFalse(runtime.contains("looksLikeHighRiskConfirmation"))
    }

    @Test
    fun pendingDurableCallsCheckManualTakeoverRegardlessOfPlanMode() {
        val operator = com.droidance.agent.coordinationSources()
        val beforeCall = operator.substringAfter("for ((toolIdx, tc) in toolCalls.withIndex()) {")
            .substringBefore("totalToolCalls += 1")
        assertTrue(beforeCall.contains("if (!interventionEntered)"))
        assertTrue(beforeCall.contains("waitForActiveExecution()"))
        assertTrue(beforeCall.contains("takeManualResumeInstruction()"))
        assertTrue(beforeCall.contains("return ToolRoundOutcome.RestartRound"))
        assertFalse(beforeCall.contains("RoundExecutionMode.PARALLEL"))
    }

    @Test
    fun runtimePausesAndNotifiesWhenManualTakeoverIsRequested() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val markBlock = runtime.substringAfter("fun requestManualTakeover(reason: String)")
            .substringBefore("\n    fun requestManualAssistance")
        val clearBlock = runtime.substringAfter("private fun clearManualTakeoverAvailable()")
            .substringBefore("\n    fun cancelCurrentTask")

        assertTrue(markBlock.contains("pauseForManualControl(reason)"))
        assertTrue(markBlock.contains("_manualTakeoverAvailable.value = true"))
        assertTrue(markBlock.contains("_userInteractionRequest.value = request"))
        assertFalse(runtime.contains("executionEventJournal"))
        assertTrue(markBlock.contains("TaskExecutionService.notifyManualTakeoverRequired"))
        assertTrue(clearBlock.contains("TaskExecutionService.cancelManualTakeover"))
    }

    @Test
    fun modelWrittenTakeoverInstructionIsPublishedAsTheDurableUserMessage() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val operator = com.droidance.agent.coordinationSources()
        val assistanceBlock = runtime.substringAfter(
            "fun requestManualAssistance(reason: String, instruction: String?)"
        ).substringBefore("\n    /**")

        assertTrue(assistanceBlock.contains("val publicInstruction"))
        assertTrue(assistanceBlock.contains("requestManualTakeover(publicInstruction)"))
        assertTrue(assistanceBlock.contains("_currentActionDetail.value = publicInstruction"))
        assertTrue(!assistanceBlock.contains("requestManualTakeover(reason)"))
        assertFalse(operator.contains("HANDOFF_MARKER"))
        assertFalse(operator.contains("[NEED_TAKEOVER]"))
    }

    @Test
    fun askHumanWithoutPublicTextPromotesItsInstructionForDurablePlanning() {
        val operator = com.droidance.agent.coordinationSources()
        val executor = com.droidance.agent.executorSources()
        val fallback = operator.substringAfter("private fun interventionPublicReply(")
            .substringBefore("private fun isProgressSensitiveTool")
        val planning = operator.substringAfter("val interventionReply = interventionPublicReply(")
            .substringBefore("val selectedProviderOrders")

        assertTrue(fallback.contains("it.name == \"ask_human\""))
        assertTrue(fallback.contains("if (publicText.isNotBlank()) return publicText"))
        assertTrue(fallback.contains("sequenceOf(\"instruction\", \"reason\")"))
        assertFalse(planning.contains("onStreamContent(interventionReply)"))
        val notification = runtimeNotificationSource()
        assertFalse(notification.contains("onStreamContent"))
        assertTrue(planning.indexOf("interventionReply") < planning.indexOf("roundVisibleMessage.takeIf"))
        assertTrue(planning.contains("val durableCommit = commitDurableRound("))
        assertTrue(planning.contains("publicBlocks = durablePublicBlocks"))
        assertTrue(planning.contains("val roundPlan = durablePreparedExecution.roundPlan"))
        assertTrue(
            executor.contains(
                "toolTurnServices.requireCurrent().interaction.requestManualAssistance(reason, instruction)"
            )
        )
    }

    @Test
    fun pendingDeepLinkVerificationSurvivesManualResume() {
        val operator = com.droidance.agent.coordinationSources()
        val executor = com.droidance.agent.executorSources()
        val reset = operator.substringAfter("fun resetAfterManualResume(")
            .substringBefore("fun ")

        val toolSurfaceBlock = operator
            .substringAfter("val toolsForRound = tools")
            .substringBefore("val presentedToolNames")
        assertFalse(toolSurfaceBlock.contains("emptyList()"))
        assertFalse(operator.contains("finalizationGate.active"))
        assertFalse(reset.contains("toolExecutor.clearPendingDeepLinkVerification()"))
        assertFalse(executor.contains("fun clearPendingDeepLinkVerification()"))
        assertTrue(operator.contains("selectedCall?.contract?.binding == ToolBinding.DEVICE_BOUND"))
    }

    @Test
    fun userInitiatedPauseDoesNotMasqueradeAsAnAgentTakeoverRequest() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val toggle = runtime.substringAfter("fun togglePause()")
            .substringBefore("fun resumeManualTakeover(")
        val begin = runtime.substringAfter("fun beginManualTakeoverFromOverlay()")
            .substringBefore("private suspend fun waitIfPaused()")

        listOf(toggle, begin).forEach { userInitiatedPath ->
            assertTrue(userInitiatedPath.contains("pauseForManualControl("))
            assertTrue(!userInitiatedPath.contains("requestManualTakeover("))
            assertTrue(!userInitiatedPath.contains("executionEventJournal.takeoverRequested("))
            assertTrue(!userInitiatedPath.contains("notifyManualTakeoverRequired("))
        }
    }

    @Test
    fun resumeRequiresFreshObservationBeforeExecutingOldActions() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val operator = com.droidance.agent.coordinationSources()
        val policy = projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText()
        val toggleBlock = runtime.substringAfter("fun togglePause()")
            .substringBefore("\n    /** 如果处于暂停状态则挂起等待")
        val resumeBlock = runtime.substringAfter("fun resumeManualTakeover(")
            .substringBefore("fun beginManualTakeoverFromOverlay()")
        val waitBlock = runtime.substringAfter("private suspend fun waitIfPaused()")
            .substringBefore("private fun manualTakeoverWaitTimeoutMs()")

        assertTrue(runtime.contains("manualResumeRequiresObservation"))
        assertTrue(runtime.contains("fun takeManualResumeInstruction()"))
        assertTrue(policy.contains("[MANUAL_TAKEOVER_RESUMED]"))
        assertTrue(toggleBlock.contains("resumeManualTakeover()"))
        assertTrue(operator.contains("takeManualResumeInstruction"))
        assertTrue(operator.contains("operatorLoop@ while (true)"))
        assertTrue(operator.contains("continue@operatorLoop"))
        assertTrue(operator.contains("InterruptedToolTurn.discardPendingCalls("))
        assertTrue(operator.contains("completedToolCallCount"))
        assertTrue(policy.contains("manual_takeover_resumed:"))
        assertTrue(runtime.contains("ruleConfigManager.message(key)"))
        assertTrue(runtime.contains("text.replace(\"{${'$'}name}\", value)"))
        assertTrue(!runtime.contains("Call observe_screen first and continue"))
        assertTrue(resumeBlock.contains("manualResumeRequiresObservation = true"))
        assertTrue(!resumeBlock.contains("if (_isMobileUseTaskActive.value)"))
        assertTrue(waitBlock.contains("manualResumeRequiresObservation = true"))
        assertTrue(!waitBlock.contains("if (_isMobileUseTaskActive.value)"))
    }

    @Test
    fun overlayTakeoverEnterAndResumeAreExplicitAndCooperative() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val begin = runtime.substringAfter("fun beginManualTakeoverFromOverlay()")
            .substringBefore("private suspend fun waitIfPaused()")
        val resume = runtime.substringAfter("fun resumeManualTakeover(")
            .substringBefore("fun beginManualTakeoverFromOverlay()")

        assertTrue(!begin.contains("llmRouter.cancelActiveRequests()"))
        assertTrue(begin.contains("pauseForManualControl("))
        assertTrue(!begin.contains("requestManualTakeover("))
        assertTrue("enter takeover must never resume an already-paused task", !begin.contains("togglePause()"))
        assertTrue(resume.contains("ManualTakeoverState.RESUMING"))
        assertTrue(resume.contains("manualResumeRequiresObservation = true"))
        assertTrue("resume must not re-enter takeover", !resume.contains("requestManualTakeover("))
    }

    @Test
    fun overlayTakeoverTransitionIsDebouncedWithoutCancellingTheTask() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val overlay = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val begin = runtime.substringAfter("fun beginManualTakeoverFromOverlay()")
            .substringBefore("private suspend fun waitIfPaused()")

        assertTrue(begin.contains("ManualTakeoverTransitionPolicy.shouldAcceptOverlayTakeover("))
        assertTrue(runtime.contains("manualTakeoverTransitionDebounceMs"))
        assertTrue(begin.contains("return false"))
        assertTrue(begin.contains("return true"))
        assertTrue(overlay.contains("private fun handleRuntimeIslandAction() { openVirtualSpaceViewer() }"))
        assertTrue(!begin.contains("cancelActiveRequests"))
    }

    @Test
    fun emptyModelTimeoutRetriesTheSameCheckpointBeforeModelTimeout() {
        val operator = com.droidance.agent.coordinationSources()
        val timeoutBlock = operator.substringAfter("} catch (e: TimeoutCancellationException) {")
            .substringBefore("} catch (e: Exception) {")

        assertTrue(timeoutBlock.contains("retryModelRequest(\"LLM_TIMEOUT_RETRY\", e)"))
        assertTrue(!timeoutBlock.contains("rounds--"))
        assertTrue(timeoutBlock.contains("ModelRoundOutcome.RestartRound"))
        assertTrue(timeoutBlock.contains("modelTimeoutTerminal(fullContent)"))
        assertTrue(operator.contains("thresholds.llmTransientRetryLimit"))
        assertTrue(operator.contains("thresholds.llmTransientRetryBackoffMs"))
    }

    @Test
    fun groundedModelTimeoutRetriesBeforeClosingLocally() {
        val operator = com.droidance.agent.coordinationSources()
        val timeoutBlock = operator.substringAfter("} catch (e: TimeoutCancellationException) {")
            .substringBefore("} catch (e: Exception) {")
            .substringAfter("if (!hasPartial) {")
        val exceptionBlock = operator.substringAfter("} catch (e: Exception) {")
            .substringBefore("return ModelRoundOutcome.Ready(")
            .substringAfter("if (transportTimeout && !actionableOutput) {")

        assertTrue(timeoutBlock.contains("retryModelRequest(\"LLM_TIMEOUT_RETRY\", e)"))
        assertTrue(exceptionBlock.contains("ModelRoundRetryPolicy.shouldRetry("))
        assertFalse(timeoutBlock.contains("requestFinalReplyRepair("))
        assertFalse(exceptionBlock.contains("requestFinalReplyRepair("))
    }

    @Test
    fun modelExceptionAfterTakeoverStillForcesFreshObservation() {
        val operator = com.droidance.agent.coordinationSources()
        val exceptionBlock = operator.substringAfter("} catch (e: Exception) {")
            .substringBefore("debugLogManager.appendEvent(\"❌ Operator LLM exception")

        assertTrue(exceptionBlock.contains("waitForActiveExecution()"))
        assertTrue(exceptionBlock.contains("takeManualResumeInstruction()"))
        assertTrue(exceptionBlock.contains("ModelRoundOutcome.RestartRound"))
    }

    @Test
    fun notificationIsHighPriorityAndOpenedExplicitlyByTheUser() {
        val service = projectFile("app/src/main/java/com/droidance/daemon/TaskExecutionService.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(service.contains("MANUAL_TAKEOVER_CHANNEL_ID"))
        assertTrue(service.contains("MANUAL_TAKEOVER_NOTIFICATION_ID"))
        assertTrue(service.contains("fun notifyManualTakeoverRequired"))
        assertTrue(service.contains("fun cancelManualTakeover"))
        assertTrue(service.contains("NotificationManagerCompat.IMPORTANCE_HIGH"))
        assertTrue(service.contains("setOngoing(true)"))
        assertTrue(service.contains("getLaunchIntentForPackage"))
        listOf(
            "manual_takeover_channel_name",
            "manual_takeover_notification_title",
            "manual_takeover_notification_text"
        ).forEach { key ->
            assertTrue("Missing string resource: $key", strings.contains("name=\"$key\""))
            assertTrue("Notification service should use R.string.$key", service.contains("R.string.$key"))
        }
    }

    @Test
    fun taskCompletionKeepsManualControlWithoutNavigatingOrRequestingTakeover() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val host = projectFile("app/src/main/java/com/droidance/agent/ForegroundExecutionHost.kt").readText()
        val viewer = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt").readText()
        val overlay = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()

        val completionBlock = runtime.substringAfter("val wasMobileUseTask = !isChatMode && (")
            .substringBefore("manualResumeRequiresObservation = false")

        assertTrue(completionBlock.contains("_isPaused.value = true"))
        assertTrue(completionBlock.contains("_manualTakeoverState.value = ManualTakeoverState.PAUSED"))
        assertTrue(completionBlock.contains("_manualTakeoverAvailable.value = true"))
        assertTrue(completionBlock.contains("foregroundHost.cancelManualTakeover()"))
        assertFalse(runtime.contains("returnToApp"))
        assertFalse("完成进入接管不得触发接管通知", completionBlock.contains("notifyManualTakeoverRequired"))

        assertFalse(host.contains("startActivity"))
        assertFalse(host.contains("launchHostActivity"))

        val closeViewer = viewer.substringAfter("private fun closeViewer() {")
            .substringBefore("if (!isFinishing) finish()")
        assertFalse("Closing the viewer must preserve manual control", closeViewer.contains("resumeManualTakeover"))
        val onStop = viewer.substringAfter("override fun onStop() {")
            .substringBefore("override fun onDestroy()")
        assertFalse("Activity lifecycle must not resume the task", onStop.contains("resumeManualTakeover"))
        assertFalse("Task state must never close the viewer", viewer.contains("observedActiveTask"))

        val idleBlock = overlay.substringAfter("AgentState.IDLE -> {")
            .substringBefore("AgentState.THINKING")
        assertTrue(idleBlock.contains("runtimeSurfaceController.hideAll()"))
        assertFalse(overlay.contains("navigateToMain"))
        assertFalse(overlay.contains("returnToApp"))
    }

    private fun runtimeNotificationSource(): String =
        projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
            .substringAfter("override fun requestManualAssistance(")
            .substringBefore("/** Called from a native tool")

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
