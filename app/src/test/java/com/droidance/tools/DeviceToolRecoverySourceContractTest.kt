// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device recovery paths that need a real Android runtime are pinned at their source boundary. */
class DeviceToolRecoverySourceContractTest {
    @Test fun aStaleSameProtocolSidecarRelaunchesItselfWhenTheAirShellIsUnavailable() {
        val bridge = source("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt")
        val relaunch = bridge.substringAfter("private fun relaunchThroughSameProtocolSidecar(")
            .substringBefore("private fun stopResidentSidecarProcess(")
        assertTrue(relaunch.contains("stale.protocolVersion == SIDECAR_PROTOCOL_VERSION"))
        assertTrue(relaunch.contains("stale.execShellCommand(buildAppProcessCommand(width, height, densityDpi)"))
        assertTrue("Only a sidecar from the installed APK counts as replaced",
            relaunch.contains("service != null && isProtocolCompatible(service)"))
        val replace = bridge.substringAfter("private fun replaceIncompatibleResidentSidecar(")
            .substringBefore("private fun relaunchThroughSameProtocolSidecar(")
        assertTrue(replace.contains("!shellRunner.isAvailable(ShellIdentity.SHELL) &&"))

        val registry = source("app/src/main/java/com/droidance/agent/RuntimeCapabilityRegistry.kt")
        val entry = registry.substringAfter("suspend fun awaitTaskStrategy(").substringBefore("val waitMs =")
        assertTrue("Task entry repairs a stale sidecar before freezing its strategy",
            entry.contains("replaceStaleResidentSidecar()"))
        val monitor = registry.substringAfter("fun monitorChannelAvailability(").substringBefore("delay(15_000)")
        assertTrue(monitor.contains("replaceStaleResidentSidecar()"))
    }

    @Test fun anEditorWithoutATextInputTypeFailsTheImeCommitAtOnceWithItsReason() {
        val ime = source("app/src/main/java/com/droidance/agent/device/AgentTextInputMethodService.kt")
        val commit = ime.substringAfter("internal fun commit(").substringBefore("internal fun pendingCommitDelivered")
        assertTrue(commit.contains("\"editor_type_null\" -> return DeviceInputResult(false"))
        assertTrue(commit.contains("Use method=shortcut or paste_key"))
        assertTrue(ime.contains("blocker=\$blocker"))
        val input = source("app/src/main/java/com/droidance/agent/device/NativeImeTextInput.kt")
        assertTrue(input.contains("AgentTextInputMethodService.connectionState(binding.displayId, foreground)"))
    }

    @Test fun aVisitReturnFailsOnlyWhileTheScreenStillShowsTheVisitedTarget() {
        val executor = source("app/src/main/java/com/droidance/tools/ToolExecutor.kt")
        assertTrue(executor.contains("if (mappedAction.returnsFromVisit && pressed.executionStatus == ToolExecutionStatus.SUCCEEDED)"))
        assertTrue(executor.contains("if (mappedAction.capturesVisitTarget) visitTarget = frame.pixelReference"))
        val confirm = executor.substringAfter("private suspend fun confirmVisitReturn(")
            .substringBefore("private suspend fun executeScreenActionBatch(")
        assertTrue(confirm.contains("val target = visitTarget ?: return pressed"))
        assertTrue(confirm.contains("awaitRenderingSettled(\"action_capture_policy\")"))
        assertTrue(confirm.contains("scrollCheckpoint = ScreenBatchScrollCheckpoint(target, SystemClock.elapsedRealtime())"))
        assertTrue("A moved origin, or an unavailable comparison, is never a failed return",
            confirm.contains("if (targetPpm == null || targetPpm > limitPpm) return pressed"))
        assertTrue(confirm.contains("code = \"VISIT_RETURN_NOT_CONFIRMED\""))
        assertTrue(source("app/src/main/assets/rules/harness_policy.yaml").contains("visit_return_change_fraction: 0.45"))
        assertTrue(source("app/src/main/assets/rules/observation_recovery.md").contains("\"VISIT_RETURN_NOT_CONFIRMED\""))
    }

    @Test fun anUnsettledCollectionPageIsRetainedAndTheCollectionContinues() {
        val executor = source("app/src/main/java/com/droidance/tools/ToolExecutor.kt")
        assertFalse(executor.contains("BATCH_COLLECTION_FRAME_NOT_STABLE"))
        assertTrue(executor.contains("[COLLECTION_FRAME_UNSETTLED] rendering_state=\$state; frame_saved=true"))
        assertTrue(executor.contains("scrollGrounding.captured(observation, frame.pixelReference, SystemClock.elapsedRealtime())"))
        assertFalse(source("app/src/main/assets/rules/observation_recovery.md").contains("BATCH_COLLECTION_FRAME_NOT_STABLE"))
    }

    @Test fun aTapRejectedAfterAnInBatchWaitSaysTheWaitDidNotRegroundIt() {
        val executor = source("app/src/main/java/com/droidance/tools/ToolExecutor.kt")
        assertTrue(executor.contains("?.getOrNull(mappedAction.actionIndex - 2)?.operation == ScreenBatchOperation.WAIT"))
        assertTrue(executor.contains("rejection.withDetail(\"after_in_batch_wait\""))
        val rules = source("app/src/main/assets/rules/completion_recovery.md")
        assertTrue(rules.contains("A wait inside a batch does not re-ground later coordinates"))
        assertFalse(rules.contains("A wait helps only when a later action in the same batch needs content"))
    }

    @Test fun onlyNewViewsAreProbedForLateLoadingContent() {
        val session = source("app/src/main/java/com/droidance/tools/ToolObservationSession.kt")
        assertTrue(session.contains("lateChangeProbeMs = checkNotNull(config[\"lateChangeProbeMs\"]"))
        assertTrue(session.contains("changedFromDecision = {"))
        val recovery = source("app/src/main/assets/rules/observation_recovery.md")
        val action = recovery.substringAfter("## action_capture_policy").substringBefore("##")
        assertTrue(action.contains("\"lateChangeProbeMs\":600,\"newViewChangedFraction\":0.3"))
        // Every section read as a capture policy declares the probe, so none fails to load.
        for (section in listOf("gesture_capture_policy", "collection_capture_policy", "input_settle_policy")) {
            assertTrue(recovery.substringAfter("## $section").substringBefore("##").contains("\"lateChangeProbeMs\":0"))
        }
    }

    @Test fun aRejectedActionIsReconsideredOnlyOnSamplesThatHeldStill() {
        val session = source("app/src/main/java/com/droidance/tools/ToolObservationSession.kt")
        val check = session.substringAfter("suspend fun checkPreActionObservation(").substringBefore("private class MotionDecision")
        assertTrue(check.contains("val decision = decide(reference) { px, py -> colors.getValue(px to py) }"))
        assertTrue("The motion retry runs only after the normal decision rejected",
            check.indexOf("if (decision.allowed) {") < check.indexOf("motionTolerantDecision(execution, reference, currentSample, policy, ::decide)"))
        val retry = session.substringAfter("private suspend fun motionTolerantDecision(").substringBefore("private fun preActionObservationMetrics(")
        assertTrue(retry.contains("PreActionFreshness.stillReference(reference, readings, policy.preActionObservationPixelDelta)"))
        assertTrue(retry.contains("stillFraction >= policy.preActionMotionMinStillFraction"))
        assertTrue("Another display, session or geometry never counts as still",
            retry.contains("next.backendSessionId != execution.backendSessionId"))
        val policy = source("app/src/main/assets/rules/harness_policy.yaml")
        assertTrue(policy.contains("pre_action_motion_readings: 2"))
        assertTrue(policy.contains("pre_action_motion_min_still_fraction: 0.15"))
    }

    @Test fun aTargetlessSwipeOnTheSameSurfaceIsNotStoppedByAPlayingVideo() {
        val session = source("app/src/main/java/com/droidance/tools/ToolObservationSession.kt")
        val check = session.substringAfter("suspend fun checkPreActionObservation(").substringBefore("private class MotionDecision")
        assertTrue("Only after the pixel and still-sample decisions rejected",
            check.indexOf("if (motion.allowed) {") < check.indexOf("if (surfaceGesture && target == null && scrollCheckpoint == null) {"))
        assertTrue(check.contains("val sameSurface = decided != null && current == decided"))
        assertTrue(session.contains("foregroundComponent = observation.foregroundComponent"))
        val executor = source("app/src/main/java/com/droidance/tools/ToolExecutor.kt")
        assertTrue(executor.contains("surfaceGesture = operation in VIEWPORT_GESTURES"))
        assertTrue(executor.contains("setOf(ScreenBatchOperation.SWIPE, ScreenBatchOperation.DRAG, ScreenBatchOperation.PATH)"))
        val envelope = source("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ObservationEnvelopeStore.kt")
        assertTrue(envelope.contains("foregroundComponent = foregroundComponent"))
    }

    @Test fun theStillSampleReadingReportsWhetherThePageMovesOnItsOwn() {
        val session = source("app/src/main/java/com/droidance/tools/ToolObservationSession.kt")
        assertTrue(session.contains("\"pre_action_motion_moving\" to if (still.samples.size < reference.samples.size) 1L else 0L"))
    }

    @Test fun aRejectedTapIsJudgedOnceMoreOnTheSurfacesStableInterface() {
        val session = source("app/src/main/java/com/droidance/tools/ToolObservationSession.kt")
        val check = session.substringAfter("suspend fun checkPreActionObservation(").substringBefore("private class MotionDecision")
        assertTrue("After the pixel and still-sample decisions, before the swipe surface rule",
            check.indexOf("if (motion.allowed) {") < check.indexOf("stableInterfaceDecision(") &&
                check.indexOf("stableInterfaceDecision(") < check.indexOf("if (surfaceGesture && target == null"))
        val stable = session.substringAfter("private fun stableInterfaceDecision(").substringBefore("private val samplingScope")
        assertTrue(stable.contains("val foreground = lease.foregroundComponent ?: return MotionDecision(false, emptyMap())"))
        assertTrue(stable.contains("span < policy.preActionStableHistoryMinSpanMs"))
        assertTrue(stable.contains("PreActionFreshness.withoutRecurringMotion("))
        val sampling = session.substringAfter("private fun startSurfaceSampling(").substringBefore("fun stopSurfaceSampling()")
        assertTrue("Another surface never vouches for the decided one", sampling.contains("if (current != foreground) break"))
        assertTrue(sampling.contains("currentModelObservationLease === lease"))
        assertTrue(session.contains("currentModelObservationLease?.let(::startSurfaceSampling)"))
        assertTrue("Sampling ends once the Agent acts", check.contains("stopSurfaceSampling()"))
        assertTrue("A target without enough stable structure is never allowed", stable.contains("decision.allowed && decision.local != null"))
        assertTrue(session.contains("it.foregroundComponent == foregroundComponent"))
        assertTrue(session.contains("rememberSurfaceFrame(observation.pixelReference, observation.foregroundComponent, observation.capturedAtElapsedMs)"))
        assertTrue(source("app/src/main/assets/rules/harness_policy.yaml").contains("pre_action_stable_history_min_span_ms: 2000"))
    }

    @Test fun aTappedInputWindowIsAwaitedWithinTheBudgetAndAFocusFailureNamesItsReason() {
        val executor = source("app/src/main/java/com/droidance/tools/ToolExecutor.kt")
        val wait = executor.substringAfter("private suspend fun awaitTappedInputFocus(").substringBefore("private suspend fun executeScreenActionBatch(")
        assertTrue(wait.contains("while (readiness.target == null && readiness.diagnosticCode !in FINAL_FOCUS_DIAGNOSTICS)"))
        assertTrue(wait.contains("observationSession.awaitRenderingSettled(\"input_settle_policy\")"))
        assertTrue(wait.contains("deviceBackend.awaitInputFocus(remaining)"))
        assertTrue(executor.contains("put(\"focus_diagnostic\", JsonPrimitive(readiness.diagnosticCode))"))
        assertTrue(executor.contains("\"INPUT_BACKEND_SESSION_CHANGED\""))
    }

    @Test fun aModelRoundCannotSpendMoreThanTheCappedOutputBudgetAgainAndAgain() {
        val policy = source("app/src/main/assets/rules/harness_policy.yaml")
        assertTrue(policy.contains("llm_action_max_output_tokens: 12288"))
        assertTrue("Truncation never expands the budget", policy.contains("llm_retry_max_output_tokens: 12288"))
        assertTrue(policy.contains("llm_output_truncation_retries: 1"))
        val round = source("core/agent/src/main/kotlin/com/droidance/core/agent/TaskModelRound.kt")
        assertTrue(round.contains("modelOutputTruncations > thresholds.llmOutputTruncationRetries"))
        assertTrue(round.contains("if (!truncationRetriesExhausted && ModelRoundRetryPolicy.shouldRetry("))
    }

    @Test fun aBrieflyUnavailableChannelIsRetriedAtTaskEntryAndReportedAsAChannelLoss() {
        val probe = source("app/src/main/java/com/droidance/agent/attempt/AndroidExecutionAttemptBackendProbe.kt")
        val prepare = probe.substringAfter("override suspend fun prepare(").substringBefore("override suspend fun inspect(")
        assertTrue(prepare.contains("runtimeThresholds.taskEntryCapabilityWaitMs"))
        assertTrue(prepare.contains("if (observation.backendAvailable) return observation"))
        assertTrue(prepare.contains("if (android.os.SystemClock.elapsedRealtime() >= deadline) return unavailable(route)"))
        val round = source("core/agent/src/main/kotlin/com/droidance/core/agent/TaskToolRound.kt")
        assertTrue(round.contains("modelProtocolFailure(terminationClaim, failureReason = TurnFailureReason.CHANNEL_DOWN)"))
    }

    private fun source(path: String): String {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate.readText()
            current = current.parentFile ?: break
        }
        error("Cannot locate $path")
    }
}
