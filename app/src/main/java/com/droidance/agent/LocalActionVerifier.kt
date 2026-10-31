// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.LocalActionEvidence.ProgressState
import com.droidance.core.agent.LocalActionEvidence.TextFingerprint
import com.droidance.core.agent.LocalActionEvidence.ObservationLite
import com.droidance.core.agent.LocalActionEvidence.ValidationResult
import android.os.SystemClock
import com.droidance.core.device.agent.device.DisplaySnapshot
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.core.agent.device.ExecutionContext
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.config.RuleConfigManager
import com.droidance.core.device.tools.AdbShellExecutor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本地轻量动作验证器。
 *
 * 只使用设备本地信号判断动作是否有进展，不调用 LLM：
 * - virtual-display screenshot hash
 * - 前台包名
 * - 可见文字指纹
 * - 连续无进展计数
 */
@Singleton
class LocalActionVerifier @Inject constructor(
    private val deviceBackend: DeviceBackend,
    private val executionContextStore: ExecutionContextStore,
    private val sidecarBridge: VirtualSpaceSidecarBridge,
    private val ruleConfigManager: RuleConfigManager,
    private val textFingerprint: ObservationTextFingerprint
) {
    private val lock = Any()
    private var lastNoProgressSignature: String? = null
    private var noProgressRepeats: Int = 0
    private val events = ArrayDeque<ValidationResult>()

    fun reset() {
        synchronized(lock) {
            lastNoProgressSignature = null
            noProgressRepeats = 0
            events.clear()
        }
    }

    suspend fun capture(
        visibleText: String = "",
        preferLastFrame: Boolean = false,
        forceFresh: Boolean = false
    ): ObservationLite {
        val snapshot = captureDisplaySnapshot(
            preferLastFrame = preferLastFrame,
            forceFresh = forceFresh
        )
        return describeSnapshot(snapshot, visibleText)
    }

    suspend fun describeSnapshot(snapshot: DisplaySnapshot, visibleText: String = "",
        visualFingerprint: Long? = snapshot.nativeFrame.let { frame ->
            if (frame != null) frame.visualFingerprint else ScreenVisualFingerprint.fromBytes(snapshot.imagePng)
        }): ObservationLite {
        val screenHash = snapshot.nativeFrame?.contentHash ?: snapshot.imagePng
            .takeIf { it.isNotEmpty() }
            ?.contentHashCode()
            ?.toLong()
        val foregroundPackage = snapshot.foregroundComponent?.substringBefore('/')
        val observedText = visibleText.takeIf { it.isNotBlank() }
        val textFingerprint = observedText?.takeIf { it.isNotBlank() }?.let { buildTextFingerprint(it) }
        return ObservationLite(
            screenHash = screenHash,
            foregroundPackage = foregroundPackage,
            visualFingerprint = visualFingerprint,
            textFingerprint = textFingerprint,
            semanticState = ScreenSemanticState.from(
                foregroundPackage = foregroundPackage,
                foregroundActivityOrWindow = snapshot.foregroundComponent,
                screenHash = screenHash,
                screenStateText = observedText
            ),
            observationId = snapshot.observationId,
            backendSessionId = snapshot.backendSessionId,
            displayId = snapshot.displayId
        )
    }

    suspend fun quickScreenHash(
        preferLastFrame: Boolean = false,
        forceFresh: Boolean = false
    ): Long? {
        return runCatching {
            captureDisplaySnapshot(preferLastFrame, forceFresh)
                .imagePng
                .takeIf { it.isNotEmpty() }
                ?.contentHashCode()
                ?.toLong()
        }.getOrNull()
    }

    suspend fun awaitRouteTransition(
        before: ObservationLite?,
        expectedPackage: String?,
        requireFreshFrame: Boolean = false
    ): ObservationLite? {
        var latest: ObservationLite? = null
        var targetForegroundSeen = false
        val thresholds = ruleConfigManager.harnessPolicy().runtimeThresholds
        val attempts = thresholds.routeAttestationAttempts
        val delayMs = thresholds.routeAttestationDelayMs
        val deadline = SystemClock.elapsedRealtime() + thresholds.routeAttestationTimeoutMs
        val targetPackage = expectedPackage
            ?.substringBefore('/')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val canUseForegroundFastPath = targetPackage != null
        repeat(attempts) { attempt ->
            if (SystemClock.elapsedRealtime() >= deadline) {
                if (requireFreshFrame && targetForegroundSeen && latest?.foregroundPackage == targetPackage) {
                    deviceBackend.invalidateScreenCache()
                    return@awaitRouteTransition packageObservation(before, targetPackage)
                }
                return@awaitRouteTransition latest
            }
            if (canUseForegroundFastPath) {
                // 普通显式启动只确认目标包；Deep Link 还会在目标包出现后取一帧新的
                // 渲染结果，页面语义仍由下一轮 observe_screen 读取。只有需要新帧时
                // 才触发昂贵的截图，避免普通启动被 Binder/sidecar 放大。
                val foregroundPackage = runCatching {
                    currentForegroundPackageForContext()
                }.getOrNull()
                if (foregroundPackage == targetPackage) {
                    targetForegroundSeen = true
                    if (!requireFreshFrame) {
                        return@awaitRouteTransition packageObservation(before, foregroundPackage)
                    }
                    // An explicit Deep Link can switch the foreground package before the
                    // virtual-display sidecar has published the destination frame.  Capture
                    // until the first post-route frame differs from the pre-route frame so the
                    // next observe_screen cannot pair the new package with an old app image.
                    val candidate = runCatching { capture(forceFresh = true) }.getOrNull()
                    if (candidate != null) {
                        latest = candidate
                        if (
                            candidate.foregroundPackage == targetPackage &&
                            routeFrameIsFresh(before, candidate)
                        ) {
                            return@awaitRouteTransition candidate
                        }
                    } else {
                        latest = packageObservation(before, foregroundPackage)
                    }
                }
                if (
                    attempt == attempts - 1 &&
                    !foregroundPackage.isNullOrBlank() &&
                    foregroundPackage != targetPackage
                ) {
                    // 只在最后一次仍是其它前台包时返回失败证据；短暂的旧页面不应
                    // 抢在目标 Activity 完成切换前触发 GUI fallback。
                    return@awaitRouteTransition packageObservation(before, foregroundPackage)
                }
            } else {
                if (attempt == attempts - 1) {
                    // 没有目标包时保留原有的最后一帧诊断；此类 Intent 仍保持未验证语义。
                    latest = runCatching { capture(forceFresh = true) }.getOrNull()
                    val current = latest
                    if (current != null && routeTransitionObserved(before, current, expectedPackage)) {
                        return@awaitRouteTransition current
                    }
                }
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (attempt < attempts - 1 && delayMs > 0L && remaining > 0L) {
                delay(delayMs.coerceAtMost(remaining))
            }
        }
        if (requireFreshFrame && targetForegroundSeen && latest?.foregroundPackage == targetPackage) {
            // Keep the package evidence, but do not leave an unverified stale frame in the
            // shared capture cache for the following observe_screen call.
            deviceBackend.invalidateScreenCache()
            return packageObservation(before, targetPackage)
        }
        return latest
    }

    suspend fun captureDisplaySnapshot(
        preferLastFrame: Boolean,
        forceFresh: Boolean
    ): DisplaySnapshot {
        val context = executionContextStore.requireCurrent()
        val captureDisplayId = context.displayId.takeUnless { it == 0 }
        val cachedFrame = if (preferLastFrame) {
            deviceBackend.lastScreenFrame()?.takeIf { it.displayId == captureDisplayId }
        } else {
            null
        }
        val frame = cachedFrame ?: com.droidance.core.device.tools.ObservationTiming.measure("observation_capture_ms") {
            deviceBackend.captureScreen(forceFresh = forceFresh)
        }
        kotlin.coroutines.coroutineContext[com.droidance.core.device.tools.ObservationTiming]?.add(
            if (cachedFrame != null) "observation_cached_frames" else "observation_captures", 1)
        val imagePng = frame?.pngBytes ?: byteArrayOf()
        val size = frame?.physicalSize
        return DisplaySnapshot(
            observationId = UUID.randomUUID().toString(),
            backendSessionId = context.backendSessionId,
            displayId = context.displayId,
            imagePng = imagePng,
            width = size?.first ?: 0,
            height = size?.second ?: 0,
            rotation = 0,
            foregroundComponent = frame?.foregroundComponent ?:
                if (frame == null) null else currentForegroundComponentForContext(context),
            capturedAtElapsedMs = SystemClock.elapsedRealtime(),
            nativeFrame = frame
        ).also { executionContextStore.recordForeground(context, it.foregroundComponent) }
    }

    fun validate(
        toolName: String,
        arguments: String,
        before: ObservationLite?,
        after: ObservationLite?,
        expectedPackage: String? = null,
        launchedUnverified: Boolean = false
    ): ValidationResult {
        val foreground = after?.foregroundPackage ?: before?.foregroundPackage
        val beforeText = before?.textFingerprint
        val afterText = after?.textFingerprint
        val textChanged = beforeText != null && afterText != null &&
            beforeText.allTextHash != afterText.allTextHash
        val progress = LocalProgressClassifier.classify(
            toolName = toolName,
            expectedPackage = expectedPackage,
            beforeForegroundPackage = before?.foregroundPackage,
            foregroundPackage = foreground,
            launchedUnverified = launchedUnverified,
            beforeHash = before?.screenHash,
            afterHash = after?.screenHash,
            textChanged = textChanged,
            beforeSemanticState = before?.semanticState,
            afterSemanticState = after?.semanticState
        )

        // Repetition/oscillation is owned by ObservationRecovery. An Activity can host
        // many distinct views; revisiting its page key is not a semantic loop.
        val noProgressCount = recordProgress(toolName, progress, before, after)
        val reason = when {
            progress == ProgressState.ACTION_CONFIRMED -> "the tool runtime confirmed the intended action effect"
            progress == ProgressState.RAW_CHANGED -> "raw pixels changed; the action may have taken effect, but semantic or goal progress is not confirmed"
            progress == ProgressState.STATE_CHANGED -> "semantic screen state changed, but goal progress is not confirmed"
            progress == ProgressState.GOAL_PROGRESS ->
                "the visible page content was substantially replaced, so this page advanced"
            progress == ProgressState.CHANGED -> "screen or visible elements changed"
            progress == ProgressState.UNCHANGED -> "screen unchanged, action may not have taken effect or the page may be at its boundary"
            progress == ProgressState.CONTEXT_CHANGED -> "foreground context changed, a system layer or another window may have taken over"
            progress == ProgressState.PARTIAL -> "only screenshot pixels changed, target semantics are not confirmed"
            progress == ProgressState.ROUTE_LAUNCHED_UNVERIFIED -> if (expectedPackage.isNullOrBlank()) {
                "route launch was accepted, but the destination page is not verified yet"
            } else {
                "target app is foreground, but page semantics are not verified yet"
            }
            progress == ProgressState.ROUTE_VERIFIED -> "foreground package matches the target app"
            progress == ProgressState.ROUTE_FAILED -> if (expectedPackage.isNullOrBlank()) {
                "route launch could not be verified"
            } else {
                "foreground package does not match the target app"
            }
            else -> "local signals are insufficient to confirm the action result"
        }
        val semanticChanged =
            after?.semanticState?.hasStateChangeComparedTo(before?.semanticState) == true || textChanged
        val result = ValidationResult(
            toolName = toolName,
            progressState = progress,
            reason = reason,
            beforeHash = before?.screenHash,
            afterHash = after?.screenHash,
            foregroundPackage = foreground,
            expectedPackage = expectedPackage,
            noProgressCount = noProgressCount,
            rawChanged = before?.screenHash != null && after?.screenHash != null && before.screenHash != after.screenHash,
            semanticChanged = semanticChanged,
            semanticObservable = semanticChanged ||
                (before?.textFingerprint != null && after?.textFingerprint != null),
            goalProgress = progress == ProgressState.GOAL_PROGRESS,
            screenSemanticState = after?.semanticState,
            visualFingerprint = after?.visualFingerprint
        )
        synchronized(lock) {
            events.addLast(result)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        return result
    }

    /**
     * Promote a local visual comparison with a stronger tool-level receipt. This updates the same
     * task-local fact stream and clears stagnation; it does not infer user-goal completion.
     */
    fun confirmRuntimeProgress(
        validation: ValidationResult,
        reason: String
    ): ValidationResult {
        val confirmed = validation.copy(
            progressState = ProgressState.ACTION_CONFIRMED,
            reason = reason,
            noProgressCount = 0,
            runtimeProgress = true
        )
        synchronized(lock) {
            lastNoProgressSignature = null
            noProgressRepeats = 0
            if (events.lastOrNull() == validation) events.removeLast()
            events.addLast(confirmed)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        return confirmed
    }

    fun currentForegroundPackageForContext(
        context: ExecutionContext = executionContextStore.requireCurrent()
    ): String? = currentForegroundComponentForContext(context)?.substringBefore('/')

    fun currentForegroundComponentForContext(
        context: ExecutionContext = executionContextStore.requireCurrent()
    ): String? {
        sidecarBridge.getForegroundComponent(
            context.displayId,
            context.mode,
            context.backendSessionId
        )?.let { return it }
        return null
    }

    fun appLaunchSettleTimeoutMs(): Long = ruleConfigManager.harnessPolicy().runtimeThresholds.appLaunchSettleTimeoutMs

    fun appFirstOpenLaunchMode(): com.droidance.core.agent.device.AppLaunchMode = checkNotNull(
        com.droidance.core.agent.device.AppLaunchMode.parse(ruleConfigManager.harnessPolicy().runtimeThresholds.appFirstOpenLaunchMode))

    suspend fun awaitForegroundComponentExit(
        launcherComponent: String,
        timeoutMs: Long = ruleConfigManager.harnessPolicy().runtimeThresholds.routeEntrySettleTimeoutMs
    ): Boolean {
        val thresholds = ruleConfigManager.harnessPolicy().runtimeThresholds
        val launcherClass = launcherComponent.substringAfter('/', "").trimStart('.')
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var current = currentForegroundComponentForContext()
        while (SystemClock.elapsedRealtime() < deadline) {
            val currentClass = current?.substringAfter('/', "")?.trimStart('.')
            if (!currentClass.isNullOrBlank() && currentClass != launcherClass) return true
            val remaining = deadline - SystemClock.elapsedRealtime()
            delay(thresholds.routeAttestationDelayMs.coerceAtLeast(50L).coerceAtMost(remaining.coerceAtLeast(1L)))
            current = currentForegroundComponentForContext()
        }
        val currentClass = current?.substringAfter('/', "")?.trimStart('.')
        return !currentClass.isNullOrBlank() && currentClass != launcherClass
    }




    private fun routeTransitionObserved(
        before: ObservationLite?,
        after: ObservationLite,
        expectedPackage: String?
    ): Boolean {
        if (before == null) return false
        return LocalProgressClassifier.classify(
            toolName = "route_transition",
            expectedPackage = expectedPackage,
            beforeForegroundPackage = before.foregroundPackage,
            foregroundPackage = after.foregroundPackage,
            launchedUnverified = true,
            beforeHash = before.screenHash,
            afterHash = after.screenHash,
            textChanged = false,
            beforeSemanticState = before.semanticState,
            afterSemanticState = after.semanticState
        ) == ProgressState.ROUTE_LAUNCHED_UNVERIFIED
    }

    private fun routeFrameIsFresh(
        before: ObservationLite?,
        candidate: ObservationLite
    ): Boolean {
        val beforeHash = before?.screenHash
        val candidateHash = candidate.screenHash
        // If the pre-route frame was unavailable, the candidate was captured only after the
        // target package became foreground; the later screen observation remains responsible
        // for semantic page verification.
        return beforeHash == null || candidateHash == null || beforeHash != candidateHash
    }

    private fun packageObservation(
        before: ObservationLite?,
        foregroundPackage: String?
    ): ObservationLite = (before ?: ObservationLite(screenHash = null, foregroundPackage = foregroundPackage)).copy(
        screenHash = null,
        foregroundPackage = foregroundPackage,
        textFingerprint = null,
        semanticState = null,
        observationId = UUID.randomUUID().toString(),
        capturedAtMs = System.currentTimeMillis()
    )

    fun buildTextFingerprint(rawText: String): TextFingerprint = textFingerprint.buildTextFingerprint(rawText)

    companion object {
        private const val MAX_EVENTS = 120

    }


    private fun recordProgress(
        toolName: String,
        progress: ProgressState,
        before: ObservationLite?,
        after: ObservationLite?
    ): Int {
        val semanticPage = after?.semanticState?.pageKey
            ?: before?.semanticState?.pageKey
            ?: after?.foregroundPackage
            ?: before?.foregroundPackage
            ?: "unknown"
        val signature = "$toolName:$semanticPage"
        synchronized(lock) {
            return if (progress == ProgressState.UNCHANGED ||
                progress == ProgressState.ROUTE_FAILED
            ) {
                if (lastNoProgressSignature == signature) {
                    noProgressRepeats += 1
                } else {
                    lastNoProgressSignature = signature
                    noProgressRepeats = 1
                }
                noProgressRepeats
            } else {
                if (progress == ProgressState.ACTION_CONFIRMED ||
                    progress == ProgressState.GOAL_PROGRESS ||
                    progress == ProgressState.CHANGED ||
                    progress == ProgressState.STATE_CHANGED ||
                    progress == ProgressState.ROUTE_VERIFIED ||
                    progress == ProgressState.ROUTE_LAUNCHED_UNVERIFIED ||
                    progress == ProgressState.RAW_CHANGED ||
                    progress == ProgressState.CONTEXT_CHANGED ||
                    progress == ProgressState.PARTIAL
                ) {
                    lastNoProgressSignature = null
                    noProgressRepeats = 0
                }
                0
            }
        }
    }

}
