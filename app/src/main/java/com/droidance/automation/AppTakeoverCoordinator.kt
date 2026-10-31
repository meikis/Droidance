// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.MessageSource
import com.droidance.agent.RuntimeCapabilityRegistry
import com.droidance.core.agent.PublicAssistantText
import com.droidance.core.agent.device.AppLaunchMode
import com.droidance.core.agent.device.DeviceExecutionStrategy
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.agent.device.VirtualSpaceManager
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.config.RuleConfigManager
import com.droidance.core.agent.di.ApplicationScope
import com.droidance.daemon.DeviceExecutionGuard
import com.droidance.daemon.TaskExecutionService
import com.droidance.core.device.tools.SidecarScreenCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * Durable signal queue and the only execution lane for persistent app takeover.
 * Notifications, deadline-based polls, and stable changes on the selected execution display wake
 * this same serial scheduler; none of them decides that a business message exists.
 */
@Singleton
class AppTakeoverCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val store: AppTakeoverStore,
    private val promptBuilder: AppTakeoverPromptBuilder,
    private val agentRuntime: AgentRuntime,
    private val deviceExecutionGuard: DeviceExecutionGuard,
    private val capabilityRegistry: RuntimeCapabilityRegistry,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val virtualSpaceSidecarBridge: VirtualSpaceSidecarBridge,
    private val ruleConfigManager: RuleConfigManager,
    private val providerConfigManager: com.droidance.settings.ProviderConfigManager
) {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        store.recoverInterruptedRuns()
        store.scheduleRecoveryScan()

        appScope.launch(Dispatchers.Default) {
            NotificationWatcher.notificationFlow.collect { info ->
                val accepted = store.enqueueNotification(
                    info = info,
                    debounceMs = thresholds().appTakeoverSignalDebounceMs
                )
                if (accepted) {
                    agentRuntime.debugLogManager.appendEvent(
                        "app_takeover signal=notification package=${info.packageName}"
                    )
                }
            }
        }
        appScope.launch(Dispatchers.Default) { schedulerLoop() }
        appScope.launch(Dispatchers.Default) { frameChangeLoop() }
    }

    private suspend fun schedulerLoop() {
        while (currentCoroutineContext().isActive) {
            expireTasks()
            if (agentRuntime.state.value != AgentState.IDLE) {
                val waitMs = store.nextDeadlineAtMs()?.minus(System.currentTimeMillis())
                    ?.coerceIn(1L, thresholds().appTakeoverSchedulerMaxSleepMs)
                    ?: thresholds().appTakeoverSchedulerMaxSleepMs
                withTimeoutOrNull(waitMs) { agentRuntime.state.first { it == AgentState.IDLE } }
                continue
            }
            val claim = store.claimNext()
            if (claim == null) {
                waitForReadySignal()
                continue
            }
            // A child job is still joined immediately, so execution stays serial. Cancelling the
            // active Agent run does not kill the permanent scheduler itself.
            supervisorScope {
                launch { executeClaimedRun(claim) }.join()
            }
        }
    }

    private suspend fun waitForReadySignal() {
        val observedVersion = store.changeVersion.value
        val now = System.currentTimeMillis()
        val configuredMaximum = thresholds().appTakeoverSchedulerMaxSleepMs
        val readyAt = listOfNotNull(store.nextReadyAtMs(), store.nextDeadlineAtMs()).minOrNull()
        val waitMs = readyAt
            ?.minus(now)
            ?.coerceAtLeast(1L)
            ?.coerceAtMost(configuredMaximum)
            ?: configuredMaximum
        withTimeoutOrNull(waitMs) {
            store.changeVersion.first { it != observedVersion }
        }
    }

    private suspend fun executeClaimedRun(claim: ClaimedAppTakeoverRun) {
        val completedWithinDeadline = AppTakeoverDeadlineRunner.run(store.changeVersion,
            deadlineAtMs = {
                (store.find(claim.watch.packageName)?.takeIf { it.activeRunId == claim.runId } ?: claim.watch).deadlineAtMs
            },
            maxSleepMs = thresholds().appTakeoverSchedulerMaxSleepMs,
            onDeadlineReached = { agentRuntime.cancelCurrentTask(taskId = "app_takeover", origin = "task_deadline") }
        ) { executeWithinDeadline(claim) }
        if (!completedWithinDeadline) store.failRun(claim.runId, "TASK_DEADLINE_REACHED", 0L)
        expireTasks()
        agentRuntime.refreshAppTakeoverResidency()
    }

    private fun expireTasks() {
        store.expireDue().forEach { watch ->
            notifyOutcome(promptBuilder.expired(watch))
            agentRuntime.debugLogManager.appendEvent("app_takeover expired package=${watch.packageName}")
        }
        agentRuntime.refreshAppTakeoverResidency()
    }

    private suspend fun executeWithinDeadline(claim: ClaimedAppTakeoverRun) {
        val config = thresholds()
        val attempts = if (config.appTakeoverExecutionRetryDelayMs > 0) 2 else 1
        var failure: Exception? = null

        repeat(attempts) { index ->
            var preparation: DeviceExecutionGuard.ExecutionPreparation? = null
            try {
                val strategy = capabilityRegistry.awaitTaskStrategy()
                check(
                    strategy.selection.effectiveMode != null &&
                        strategy.selection.unavailableReason == null &&
                        strategy.spaceSelection.unavailableReason == null
                ) {
                    strategy.spaceSelection.unavailableReason
                        ?: strategy.selection.unavailableReason
                        ?: "The selected execution route is unavailable"
                }
                agentRuntime.debugLogManager.appendEvent(
                    "app_takeover route space=${strategy.spaceSelection.effectiveSpace.name.lowercase()} " +
                        "mode=${strategy.selection.effectiveMode?.name?.lowercase()}"
                )
                preparation = deviceExecutionGuard.prepareForRemoteExecution(
                    reason = "${MessageSource.APP_TAKEOVER.wireName}:${claim.watch.packageName}",
                    strategy = strategy
                )
                val targetFrame = prepareRenderableTargetFrame(claim.watch.packageName, strategy)
                if (AppTakeoverHealthPolicy.canSkipModel(
                        signals = claim.signals,
                        targetAlreadyVisible = targetFrame.targetAlreadyVisible,
                        currentFingerprint = targetFrame.fingerprint,
                        lastSettledFingerprint = claim.watch.lastSettledFrameFingerprint,
                        nextCheckAtMs = claim.watch.nextCheckAtMs,
                        checkpoint = claim.watch.checkpoint,
                        nowMs = System.currentTimeMillis()
                    )
                ) {
                    val settled = store.completeRun(
                        runId = claim.runId,
                        checkpoint = null,
                        summary = promptBuilder.unchanged(claim.watch.packageName),
                        nextCheckSeconds = null,
                        settledFrameFingerprint = targetFrame.fingerprint,
                        healthCheckOnly = true
                    )
                    if (settled?.terminalReason == AppTakeoverTerminalReason.DEADLINE_REACHED) {
                        notifyOutcome(promptBuilder.expired(settled))
                    }
                    agentRuntime.debugLogManager.appendEvent(
                        "app_takeover health_check=unchanged package=${claim.watch.packageName} model_skipped=true"
                    )
                    return
                }
                val initialScreenFrame = captureExecutionFrame(strategy)
                    ?: error("[APP_TAKEOVER_PREFLIGHT_FAILED] retained display returned no native observation")
                val result = agentRuntime.processAppTakeoverInput(
                    userInput = promptBuilder.run(claim, targetFrame.targetAlreadyVisible),
                    conversationId = AppTakeoverSessionIds.threadId(claim.watch.packageName),
                    strategy = strategy,
                    initialScreenFrame = initialScreenFrame,
                    roundContext = { store.find(claim.watch.packageName)?.let(promptBuilder::roundContext).orEmpty() },
                    takeoverRunId = claim.runId,
                    modelPlan = claim.watch.modelSelection?.plan(providerConfigManager.getAllConfigs()),
                )
                val summary = PublicAssistantText.sanitize(result.response)
                val terminalFailure = result.status == "failed" || result.status == "need_more_info"
                val settledFingerprint = captureVisualFingerprint(strategy)
                val settled = store.completeRun(
                    runId = claim.runId,
                    checkpoint = result.checkpoint.takeIf { !terminalFailure },
                    summary = summary,
                    nextCheckSeconds = if (terminalFailure) claim.watch.pollIntervalMs / 1000L else result.nextCheckSeconds,
                    goalComplete = result.goalComplete && !terminalFailure,
                    settledFrameFingerprint = settledFingerprint
                )
                if (settled?.terminalReason == AppTakeoverTerminalReason.DEADLINE_REACHED) {
                    notifyOutcome(promptBuilder.expired(settled))
                } else if (settled != null && (result.notifyUser == true || terminalFailure || result.goalComplete)) {
                    notifyOutcome(summary)
                }
                agentRuntime.debugLogManager.appendEvent(
                    "app_takeover completed package=${claim.watch.packageName} " +
                        "status=${result.status.ifBlank { "implicit" }} " +
                        "next_check_seconds=${result.nextCheckSeconds} goal_complete=${result.goalComplete}"
                )
                return
            } catch (cancelled: CancellationException) {
                store.failRun(
                    runId = claim.runId,
                    reason = "cancelled",
                    retryDelayMs = config.appTakeoverExecutionRetryDelayMs
                )
                throw cancelled
            } catch (error: Exception) {
                failure = error
                agentRuntime.debugLogManager.appendEvent(
                    "app_takeover attempt=${index + 1}/$attempts package=${claim.watch.packageName} " +
                        "failed=${error.message.orEmpty().take(LOG_PREVIEW_CHARS)}"
                )
                if (index + 1 < attempts) delay(config.appTakeoverExecutionRetryDelayMs)
            } finally {
                preparation?.let {
                    deviceExecutionGuard.finishRemoteExecution(
                        preparation = it,
                        reason = claim.watch.packageName
                    )
                }
            }
        }

        val detail = failure?.message?.take(LOG_PREVIEW_CHARS)
            ?: failure?.javaClass?.simpleName.orEmpty()
        val report = promptBuilder.failure(claim.watch.packageName, detail)
        store.failRun(
            runId = claim.runId,
            reason = report,
            retryDelayMs = config.appTakeoverExecutionRetryDelayMs
        )
        notifyOutcome(promptBuilder.failureNotice())
        Log.e(TAG, "App takeover failed: ${failure?.javaClass?.simpleName}")
    }

    /**
     * Fail before an expensive model round when the selected display cannot produce any frame.
     * Existing renderable state is preserved so the Agent can still choose Deep Link or GUI entry;
     * only the retained virtual display needs an explicit empty-frame recovery.
     */
    private suspend fun prepareRenderableTargetFrame(
        packageName: String,
        strategy: DeviceExecutionStrategy
    ): PreparedTargetFrame = when (strategy.spaceSelection.effectiveSpace) {
        ExecutionSpace.MAIN_SCREEN -> error("Only virtual-space takeover is supported")

        ExecutionSpace.VIRTUAL_SPACE -> {
            check(virtualSpaceManager.ensureDisplayInteractive()) {
                "[APP_TAKEOVER_PREFLIGHT_FAILED] retained display is not interactive"
            }
            var prepared = capturePreparedFrame(packageName, strategy)
            if (prepared == null) {
                virtualSpaceManager.resetFrameChannel()
                virtualSpaceManager.ensureDisplayInteractive()
                prepared = capturePreparedFrame(packageName, strategy)
            }
            if (prepared == null) {
                val launch = virtualSpaceManager.launchApp(
                    packageName = packageName,
                    launchMode = AppLaunchMode.RESUME,
                    intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                )
                check(launch.commandSent) {
                    "[APP_TAKEOVER_PREFLIGHT_FAILED] target launch failed: ${launch.detail.take(LOG_PREVIEW_CHARS)}"
                }
                prepared = capturePreparedFrame(packageName, strategy)
            }
            prepared ?: error(
                "[APP_TAKEOVER_PREFLIGHT_FAILED] retained display returned no renderable frame"
            )
        }
    }

    private suspend fun capturePreparedFrame(
        packageName: String,
        strategy: DeviceExecutionStrategy
    ): PreparedTargetFrame? {
        val frame = capturePreviewFrame(strategy) ?: return null
        val signature = AppTakeoverVisualSignature.fromEncodedImage(frame) ?: return null
        return PreparedTargetFrame(
            targetAlreadyVisible = currentForegroundPackage(strategy)
                ?.equals(packageName, ignoreCase = true) == true,
            fingerprint = signature.fingerprint()
        )
    }

    private suspend fun captureVisualFingerprint(strategy: DeviceExecutionStrategy): String? =
        capturePreviewFrame(strategy)
            ?.let(AppTakeoverVisualSignature::fromEncodedImage)
            ?.fingerprint()

    private suspend fun capturePreviewFrame(strategy: DeviceExecutionStrategy): ByteArray? =
        when (strategy.spaceSelection.effectiveSpace) {
            ExecutionSpace.MAIN_SCREEN -> null
            ExecutionSpace.VIRTUAL_SPACE -> virtualSpaceManager.captureLatestPreviewJpeg()
        }

    /** Only an actual Agent run needs physical evidence; monitoring keeps its existing preview. */
    private suspend fun captureExecutionFrame(strategy: DeviceExecutionStrategy): ByteArray? {
        if (strategy.spaceSelection.effectiveSpace != ExecutionSpace.VIRTUAL_SPACE) return null
        val binding = virtualSpaceManager.activeBinding()
            ?.takeIf { it.runtimeMode == strategy.selection.effectiveMode } ?: return null
        val frame = withContext(Dispatchers.IO) {
            virtualSpaceSidecarBridge.captureFrame(binding, forceFresh = true,
                sampleSide = thresholds().preActionObservationSampleSide)
        } ?: return null
        return frame.png.takeIf { virtualSpaceManager.activeBinding() == binding }
    }

    private fun currentForegroundPackage(strategy: DeviceExecutionStrategy): String? =
        when (strategy.spaceSelection.effectiveSpace) {
            ExecutionSpace.MAIN_SCREEN -> null
            ExecutionSpace.VIRTUAL_SPACE -> virtualSpaceManager.currentForegroundPackage()
        }

    private suspend fun frameChangeLoop() {
        var foregroundPackage: String? = null
        var acceptedSignature: AppTakeoverVisualSignature? = null
        var candidateSignature: AppTakeoverVisualSignature? = null
        var stableSamples = 0
        var lastWakeAtMs = 0L
        var lastDisplayKeepaliveAtMs = 0L
        var executionRouteKey: String? = null

        while (currentCoroutineContext().isActive) {
            val config = thresholds()
            val observedVersion = store.changeVersion.value
            if (!store.hasEnabledWatches()) {
                foregroundPackage = null
                acceptedSignature = null
                candidateSignature = null
                stableSamples = 0
                lastDisplayKeepaliveAtMs = 0L
                executionRouteKey = null
                withTimeoutOrNull(config.appTakeoverSchedulerMaxSleepMs) {
                    store.changeVersion.first { it != observedVersion }
                }
                continue
            }
            delay(config.appTakeoverFramePollMs)
            if (agentRuntime.state.value != AgentState.IDLE) {
                // Keep the last accepted idle frame. Re-baselining blindly after a run can hide
                // content that arrived between the final Agent observation and Turn completion.
                continue
            }
            val strategy = capabilityRegistry.currentStrategy()
            val mode = strategy.selection.effectiveMode
            val routeReady = mode != null &&
                strategy.selection.unavailableReason == null &&
                strategy.spaceSelection.unavailableReason == null
            val routeKey = mode?.let { "${it.name}:${strategy.spaceSelection.effectiveSpace.name}" }
            if (!routeReady || routeKey != executionRouteKey) {
                foregroundPackage = null
                acceptedSignature = null
                candidateSignature = null
                stableSamples = 0
                lastDisplayKeepaliveAtMs = 0L
                executionRouteKey = routeKey.takeIf { routeReady }
                if (!routeReady) continue
            }
            if (
                strategy.spaceSelection.effectiveSpace == ExecutionSpace.VIRTUAL_SPACE &&
                !virtualSpaceManager.isActive()
            ) {
                foregroundPackage = null
                acceptedSignature = null
                candidateSignature = null
                stableSamples = 0
                lastDisplayKeepaliveAtMs = 0L
                continue
            }
            val now = System.currentTimeMillis()
            if (
                strategy.spaceSelection.effectiveSpace == ExecutionSpace.VIRTUAL_SPACE &&
                now - lastDisplayKeepaliveAtMs >= config.appTakeoverDisplayKeepaliveMs
            ) {
                virtualSpaceManager.ensureDisplayInteractive()
                lastDisplayKeepaliveAtMs = now
            }
            val packageName = currentForegroundPackage(strategy)
            val watch = packageName?.let(store::find)?.takeIf { it.enabled }
            if (packageName == null) continue
            if (watch == null) {
                foregroundPackage = packageName
                acceptedSignature = null
                candidateSignature = null
                stableSamples = 0
                continue
            }
            if (watch.activeRunId != null) {
                // Preflight and Agent actions redraw the selected display. They are execution
                // effects, not external wake signals, so never sample them into the change gate.
                continue
            }
            val frame = captureExecutionFrame(strategy) ?: continue
            val signature = AppTakeoverVisualSignature.fromEncodedImage(frame) ?: continue
            if (
                watch.lastSettledFrameFingerprint.isNotBlank() &&
                signature.fingerprint() == watch.lastSettledFrameFingerprint
            ) {
                foregroundPackage = packageName
                acceptedSignature = signature
                candidateSignature = null
                stableSamples = 0
                continue
            }
            val baseline = acceptedSignature
            if (baseline == null || foregroundPackage != packageName) {
                foregroundPackage = packageName
                acceptedSignature = signature
                candidateSignature = null
                stableSamples = 0
                continue
            }
            val changedFraction = baseline.changedFraction(
                signature,
                config.appTakeoverFramePixelDelta
            )
            if (changedFraction < config.appTakeoverFrameChangeFraction) {
                candidateSignature = null
                stableSamples = 0
                continue
            }
            val candidate = candidateSignature
            if (
                candidate != null &&
                candidate.changedFraction(signature, config.appTakeoverFramePixelDelta) <=
                config.appTakeoverFrameStableFraction
            ) {
                stableSamples += 1
            } else {
                candidateSignature = signature
                stableSamples = 1
            }
            if (
                stableSamples >= config.appTakeoverFrameStableSamples &&
                now - lastWakeAtMs >= config.appTakeoverFrameWakeCooldownMs
            ) {
                val fingerprint = signature.fingerprint()
                val accepted = store.enqueueFrameChange(
                    packageName = packageName,
                    frameFingerprint = fingerprint,
                    debounceMs = config.appTakeoverFrameSignalDebounceMs,
                    nowMs = now
                )
                acceptedSignature = signature
                candidateSignature = null
                stableSamples = 0
                if (accepted) {
                    lastWakeAtMs = now
                    agentRuntime.debugLogManager.appendEvent(
                        "app_takeover signal=${AppTakeoverSignalType.FRAME_CHANGE.name.lowercase()} " +
                            "package=$packageName"
                    )
                }
            }
        }
    }

    private fun notifyOutcome(text: String) {
        if (text.isBlank()) return
        TaskExecutionService.notifyTaskDone(context, text)
    }

    private fun thresholds() = ruleConfigManager.harnessPolicy().runtimeThresholds

    private companion object {
        const val TAG = "AppTakeoverCoordinator"
        const val LOG_PREVIEW_CHARS = 240
    }

    private data class PreparedTargetFrame(
        val targetAlreadyVisible: Boolean,
        val fingerprint: String
    )
}
