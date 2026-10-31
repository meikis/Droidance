// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTakeoverSourceContractTest {

    @Test
    fun notificationPollingAndStableFrameSignalsShareOneSerialCoordinator() {
        val coordinator = projectFile(
            "app/src/main/java/com/droidance/automation/AppTakeoverCoordinator.kt"
        ).readText()

        assertTrue(coordinator.contains("NotificationWatcher.notificationFlow.collect"))
        assertTrue(coordinator.contains("store.claimNext"))
        assertTrue(coordinator.contains("executeClaimedRun"))
        assertTrue(coordinator.contains("captureLatestPreviewJpeg"))
        assertTrue(coordinator.contains("AppTakeoverVisualSignature.fromEncodedImage"))
        assertTrue(coordinator.contains("appTakeoverFrameChangeFraction"))
        assertTrue(coordinator.contains("currentForegroundPackage"))
        assertTrue(coordinator.contains("AppTakeoverSignalType.FRAME_CHANGE"))
        assertTrue(coordinator.contains("AppTakeoverHealthPolicy.canSkipModel"))
        assertTrue(coordinator.contains("prepareRenderableTargetFrame"))
        assertTrue(coordinator.contains("initialScreenFrame = initialScreenFrame"))
        assertTrue(coordinator.contains("val initialScreenFrame = captureExecutionFrame(strategy)"))
        assertTrue(coordinator.contains("watch.activeRunId != null"))
        assertTrue(coordinator.contains("signature.fingerprint() == watch.lastSettledFrameFingerprint"))
        assertTrue(coordinator.contains("MessageSource.APP_TAKEOVER"))
        assertTrue(coordinator.contains("capabilityRegistry.currentStrategy()"))
        assertFalse(coordinator.contains("capabilityRegistry.virtualStrategy()"))
        assertTrue(coordinator.contains("ExecutionSpace.MAIN_SCREEN ->"))
        assertTrue(coordinator.contains("ExecutionSpace.VIRTUAL_SPACE ->"))
        assertTrue(coordinator.contains("virtualSpaceManager.captureLatestPreviewJpeg("))
        assertTrue(coordinator.contains("captureExecutionFrame(strategy)"))
        val preparedSource = coordinator.substringAfter("private suspend fun capturePreparedFrame(")
            .substringBefore("private suspend fun captureVisualFingerprint(")
        assertTrue(preparedSource.contains("capturePreviewFrame(strategy)"))
        val nativeSource = coordinator.substringAfter("private suspend fun captureExecutionFrame(")
            .substringBefore("private fun currentForegroundPackage(")
        assertTrue(nativeSource.contains("virtualSpaceManager.activeBinding()"))
        assertTrue(nativeSource.contains("virtualSpaceSidecarBridge.captureFrame(binding, forceFresh = true"))
        assertTrue(nativeSource.contains("return frame.png.takeIf"))
        assertFalse(nativeSource.contains("captureLatestPreviewJpeg"))
        val preparedRun = coordinator.substringAfter("val targetFrame = prepareRenderableTargetFrame(")
            .substringBefore("val result = agentRuntime.processAppTakeoverInput(")
        assertTrue(preparedRun.indexOf("return") in 0 until preparedRun.indexOf("val initialScreenFrame = captureExecutionFrame(strategy)"))
        assertFalse(coordinator.contains("async {"))

        val store = projectFile(
            "app/src/main/java/com/droidance/automation/AppTakeoverStore.kt"
        ).readText()
        assertTrue(store.contains("recoverInterruptedRuns"))
        assertTrue(store.contains("scheduleRecoveryScan"))
        assertTrue(store.contains("activePollDueAtMs"))
        assertTrue(store.contains("lastSummary = if (healthCheckOnly) watch.lastSummary else summary"))
        assertTrue(store.contains(".commit()"))

        val watcher = projectFile(
            "app/src/main/java/com/droidance/automation/NotificationWatcher.kt"
        ).readText()
        assertTrue(watcher.contains("setLaunchDisplayId"))
        assertTrue(watcher.contains("contentIntent.send(this, 0"))
    }

    @Test
    fun naturalLanguageToolManagesMultipleAppsAndPollingIntervalAtomically() {
        val registrar = projectFile(
            "app/src/main/java/com/droidance/tools/NotificationToolRegistrar.kt"
        ).readText()
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()
        val contracts = projectFile("app/src/main/assets/rules/tool_contracts.yaml").readText()

        assertTrue(registrar.contains("manage_app_takeover"))
        assertTrue(registrar.contains("\"apps\" to ToolParameter("))
        assertTrue(registrar.contains("poll_interval_seconds"))
        assertTrue(registrar.contains("success_summary"))
        assertTrue(registrar.contains("Configuration is not execution"))
        assertFalse(registrar.contains("completionState.record("))
        assertFalse(registrar.contains("locallyGrounded = true"))
        assertFalse(registrar.contains("not an app-launch or page-state substitute"))
        assertFalse(registrar.contains("Call read_notifications first"))
        assertFalse(registrar.contains("faster than opening an app"))
        assertTrue(catalog.contains("\"manage_app_takeover\""))
        assertTrue(contracts.contains("  manage_app_takeover:"))
        assertFalse(catalog.contains("\"watch_notifications\""))
        assertFalse(catalog.contains("\"unwatch_notifications\""))

        assertTrue(
            projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt")
                .readText()
                .contains("param.toJsonSchema()")
        )
    }

    @Test
    fun takeoverBehaviorLivesInMarkdownAndIdleResidencyIsWaiting() {
        val prompts = projectFile("app/src/main/assets/rules/automation_prompts.md").readText()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(prompts.contains("## app_takeover_run_prompt"))
        assertTrue(prompts.contains("independent objects"))
        assertTrue(prompts.contains("fresh selected-display frame attached"))
        assertFalse(prompts.contains("same virtual screen"))
        assertTrue(runtime.contains("initialScreenRequired = request.source == MessageSource.APP_TAKEOVER"))
        assertTrue(prompts.contains("{{display_state}}"))
        assertTrue(prompts.contains("checkpoint"))
        assertTrue(runtime.contains("takeoverResidentStatus"))
        assertTrue(strings.contains("name=\"app_takeover_waiting\""))
    }

    @Test
    fun defaultCadenceAndFrameWakeThresholdsAreExternalConfiguration() {
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
        val config = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/config/RuleConfiguration.kt"
        ).readText()

        assertTrue(policy.contains("app_takeover_default_poll_ms: 60000"))
        assertTrue(policy.contains("app_takeover_frame_poll_ms:"))
        assertTrue(policy.contains("app_takeover_frame_signal_debounce_ms:"))
        assertTrue(policy.contains("app_takeover_frame_stable_samples:"))
        assertTrue(policy.contains("app_takeover_frame_change_fraction:"))
        assertTrue(policy.contains("app_takeover_display_keepalive_ms:"))
        assertTrue(config.contains("appTakeoverDefaultPollMs"))
        assertTrue(config.contains("appTakeoverFramePollMs"))
        assertTrue(config.contains("appTakeoverFrameChangeFraction"))
    }

    @Test
    fun waitingStatusSurvivesTurnContextAndIsVisibleInsideTheApp() {
        val manager = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt"
        ).readText()
        val surface = projectFile(
            "app/src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt"
        ).readText()
        val home = projectFile(
            "app/src/main/java/com/droidance/ui/screens/HomeScreen.kt"
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt"
        ).readText()

        assertTrue(manager.contains("fun activeBinding(): SidecarSessionBinding?"))
        assertTrue(surface.contains("= virtualSpaceManager.activeBinding()"))
        assertFalse(home.contains("RuntimeStatusIsland("))
        assertTrue(home.contains("AppTakeoverLogCard("))
        assertTrue(home.contains("onStop = viewModel::stopAppTakeoverStatus"))
        assertTrue(viewModel.contains("agentRuntime.appTakeoverTurnActive"))
        assertTrue(viewModel.contains("appTakeoverStore.changeVersion"))
        assertTrue(viewModel.contains("app_takeover_status_waiting"))
        assertTrue(viewModel.contains("agentRuntime.stopAppTakeoverResidency(origin = \"chat_takeover_log_stop\")"))
    }

    @Test
    fun everyVisibleStopEntryClearsResidencyBeforeCancellingOnlyTheTakeoverTurn() {
        val runtime = projectFile(
            "app/src/main/java/com/droidance/agent/AgentRuntime.kt"
        ).readText()
        val stopBody = runtime.substringAfter("fun stopAppTakeoverResidency(")
            .substringBefore("/** 进入循环等待状态")

        assertTrue(stopBody.contains("appTakeoverStore.hasEnabledWatches()"))
        assertTrue(stopBody.contains("appTakeoverStore.clear()"))
        assertTrue(stopBody.contains("_takeoverResidentStatus.value = null"))
        assertTrue(stopBody.contains("cancelCurrentTask(taskId = \"app_takeover\", origin = origin)"))
        assertFalse(stopBody.contains("virtualSpaceManager.stop()"))
        assertFalse(stopBody.contains("cancelCurrentTask(origin = origin)"))
    }

    @Test
    fun preparedTakeoverFrameIsReusedByTheFirstModelRound() {
        val coordinator = projectFile(
            "app/src/main/java/com/droidance/automation/AppTakeoverCoordinator.kt"
        ).readText()
        val runtime = projectFile(
            "app/src/main/java/com/droidance/agent/AgentRuntime.kt"
        ).readText()
        val operator = com.droidance.agent.coordinationSources()
        val executor = com.droidance.agent.executorSources()

        assertTrue(coordinator.contains("initialScreenFrame = initialScreenFrame"))
        assertTrue(runtime.contains("initialScreenFrame = initialScreenFrame"))
        assertTrue(operator.contains("toolExecutor.stagePreparedObservation(frame)"))
        assertTrue(executor.contains("observeScreenTool.execute(preparedFrame)"))
    }

    @Test
    fun takeoverReadsCompletionSnapshotFromRuntimeOutcomeBeforeTurnStateIsReleased() {
        val runtime = projectFile(
            "app/src/main/java/com/droidance/agent/AgentRuntime.kt"
        ).readText()
        val outcome = runtime
            .substringAfter("data class RuntimeTurnOutcome(")
            .substringBefore("data class AutomationTurnResult(")
        val takeover = runtime
            .substringAfter("suspend fun processAppTakeoverInput(")
            .substringBefore("private suspend fun processInputInternal(")
        val taskBody = runtime.substringAfter(") { taskSession ->")
            .substringBefore("} finally {\n            _agentLoopState.value = AgentLoopState.COMPLETED")
        val returnedOutcome = taskBody.substringAfterLast("RuntimeTurnOutcome(")
        val lifecycle = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskLifecycle.kt").readText()
        assertTrue(lifecycle.indexOf("execute(active)") < lifecycle.indexOf("releaseResources(active)"))

        assertTrue(outcome.contains("val completion: com.droidance.tools.CompletionResult?"))
        assertTrue(returnedOutcome.contains("completion = toolExecutor.completionResult()"))
        assertTrue(takeover.contains("val outcome = processInputInternal("))
        assertTrue(takeover.contains("outcome.completion?.checkpoint"))
        assertTrue(takeover.contains("outcome.completion?.nextCheckSeconds"))
        assertTrue(takeover.contains("outcome.completion?.notifyUser"))
        assertTrue(takeover.indexOf("outcome.completion?.checkpoint") < takeover.indexOf("finally"))
        assertFalse(takeover.contains("toolExecutor.completionResult()"))
    }

    @Test
    fun takeover_status_keeps_every_turn_log_without_a_watch_count_suffix() {
        val runtime = projectFile(
            "app/src/main/java/com/droidance/agent/AgentRuntime.kt"
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt"
        ).readText()
        val zh = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val en = projectFile("app/src/main/res/values/strings.xml").readText()
        val takeoverTurn = runtime.substringAfter("suspend fun processAppTakeoverInput(")
            .substringBefore("private suspend fun processInputInternal(")

        assertFalse(runtime.contains("appTakeoverExecutionEvents"))
        assertFalse(takeoverTurn.contains("onExecutionEvent"))
        assertTrue(runtime.contains("_appTakeoverConversationId.value = conversationId"))
        assertTrue(viewModel.contains("eventStore.observeThreadProjectionResult(threadId)"))
        assertFalse(viewModel.contains("app_takeover_status_title, enabledCount"))
        assertTrue(zh.contains("name=\"app_takeover_status_title\">应用接管中</string>"))
        assertTrue(en.contains("name=\"app_takeover_status_title\">App takeover active</string>"))
    }

    @Test
    fun staleLaunchFrameDoesNotPoisonInputAndBackendRejectionsStopReplanning() {
        val manager = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt"
        ).readText()
        val operator = com.droidance.agent.coordinationSources()
        val launchBody = manager.substringAfter("fun launchApp(")
            .substringBefore("fun launchHostActivity(")

        assertFalse(launchBody.contains("lifecycleFrameReady = frameReady"))
        assertFalse(operator.contains("requestFinalReplyRepair"))
        assertTrue(operator.contains("AttemptDiagnosticCode.BACKEND_UNAVAILABLE"))
        assertTrue(operator.contains("AttemptDiagnosticCode.AIR_CHANNEL_RECOVERY_REQUIRED"))
        assertTrue(operator.contains("AttemptDiagnosticCode.AIR_PAIRING_REQUIRED"))
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
