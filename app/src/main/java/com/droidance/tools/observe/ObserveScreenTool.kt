// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools.observe

import com.droidance.core.device.tools.observe.*

import com.droidance.tools.observe.*

import com.droidance.core.device.tools.DeviceScreenFrame
import com.droidance.core.agent.event.ToolExecutionResult
import android.util.LruCache
import com.droidance.agent.LocalActionVerifier
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.config.RuleConfigManager
import com.droidance.debug.DebugLogManager
import com.droidance.core.device.debug.DiagnosticsCollector
import com.droidance.core.agent.tools.ModelCoordinateMapper
import com.droidance.core.agent.tools.ToolProtocolResult
import com.droidance.core.device.tools.ObservedTargetCache
import kotlinx.coroutines.delay
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.device.agent.device.SidecarSessionBinding
import com.droidance.core.device.agent.device.VirtualDisplayContentState
import com.droidance.agent.RuntimeCapabilityRegistry
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

@Singleton
class ObserveScreenTool @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val deviceBackend: DeviceBackend,
    private val debugLogManager: DebugLogManager,
    private val diagnostics: DiagnosticsCollector,
    private val ruleConfigManager: RuleConfigManager,
    private val localActionVerifier: LocalActionVerifier,
    private val capabilityRegistry: Lazy<RuntimeCapabilityRegistry>,
    private val executionContextStore: ExecutionContextStore,
    private val virtualSpaceManager: Lazy<com.droidance.agent.device.VirtualSpaceManager>
) {
    data class PublishedVisionFrame(
        val imageBase64: String?,
        val mode: String,
        val frameScale: Float,
        val frameSize: Pair<Int, Int>?,
        val physicalFrameSize: Pair<Int, Int>?,
        val physicalOrigin: Pair<Int, Int>,
        val readableText: String? = null,
        val screenText: String? = null,
        val producedAtMs: Long? = null,
        val targets: List<ObservedTargetCache.TargetEntry> = emptyList(),
        val pixelReference: com.droidance.core.agent.tools.ObservationPixelReference? = null,
        val nativeImagePng: ByteArray? = null,
        val nativeFrameSha256: String? = null,
        val nativeVisualFingerprint: Long? = null,
        val foregroundComponent: String? = null
    )

    private fun frameRecoveryRepeats(): Int =
        ruleConfigManager.harnessPolicy().runtimeThresholds.frameRecoveryRepeats
    companion object {
        /** 指纹观察缓存容量：覆盖"列表→详情→返回列表"往返，命中即省缩放+编码。 */
        const val OBSERVE_CACHE_MAX_ENTRIES = 8
        const val MODE_PURE_VLM = "pure_vlm"
        const val MODE_CROP_ZOOM = "crop_zoom"
        const val MODE_NONE = "none"
    }

    /** 图像与坐标元数据必须作为同一帧原子发布。 */
    var setPendingVisionFrame: (suspend (PublishedVisionFrame) -> Unit)? = null

    @Volatile
    private var latestFrameTargets: List<ObservedTargetCache.TargetEntry> = emptyList()

    fun latestTargets(): List<ObservedTargetCache.TargetEntry> = latestFrameTargets

    private data class ObserveCacheEntry(
        val fingerprint: String,
        val mode: String,
        val responseText: String,
        val modelFrameBase64: String?,
        val frameScale: Float,
        val frameSize: Pair<Int, Int>?,
        val physicalFrameSize: Pair<Int, Int>?,
        val frameOrigin: Pair<Int, Int>,
        val readableText: String?,
        val screenText: String? = null,
        val targets: List<ObservedTargetCache.TargetEntry> = emptyList()
    )

    @Volatile
    private var lastScreenFingerprint: String? = null

    /**
     * 同一指纹帧的完整观察结果缓存（LRU）。多帧容量让"回到旧页"也命中，
     * 而不只是"连续两次观察同一帧"：AppTakeover 巡检与列表往返是主要受益场景。
     */
    private val observeCache = LruCache<String, ObserveCacheEntry>(OBSERVE_CACHE_MAX_ENTRIES)

    fun invalidateCache() {
        lastScreenFingerprint = null
        observeCache.evictAll()
    }

    /** Return the current frame without an unconditional rendering-stability window. */
    private suspend fun captureObservedFrame(): DeviceScreenFrame? =
        deviceBackend.captureScreen(forceFresh = true)

    private suspend fun publishPendingScreenshot(
        base64: String?,
        mode: String,
        frame: VisionFrameScaler.ScaledFrame? = null,
        physicalOrigin: Pair<Int, Int> = 0 to 0,
        readableText: String? = null,
        screenText: String? = null,
        producedAtMs: Long? = null,
        targets: List<ObservedTargetCache.TargetEntry> = emptyList(),
        pixelReference: com.droidance.core.agent.tools.ObservationPixelReference? = null,
        nativeImagePng: ByteArray? = null,
        nativeFrameSha256: String? = null,
        nativeVisualFingerprint: Long? = null,
        foregroundComponent: String? = null
    ) {
        publishPendingScreenshot(
            base64 = base64,
            mode = mode,
            frameScale = frame?.scale ?: 1f,
            frameSize = frame?.modelSize,
            physicalFrameSize = frame?.physicalSize,
            physicalOrigin = physicalOrigin,
            readableText = readableText,
            screenText = screenText,
            producedAtMs = producedAtMs,
            targets = targets,
            pixelReference = pixelReference,
            nativeImagePng = nativeImagePng,
            nativeFrameSha256 = nativeFrameSha256,
            nativeVisualFingerprint = nativeVisualFingerprint,
            foregroundComponent = foregroundComponent
        )
    }

    private suspend fun publishPendingScreenshot(
        base64: String?,
        mode: String,
        frameScale: Float,
        frameSize: Pair<Int, Int>?,
        physicalFrameSize: Pair<Int, Int>?,
        physicalOrigin: Pair<Int, Int> = 0 to 0,
        readableText: String? = null,
        screenText: String? = null,
        producedAtMs: Long? = null,
        targets: List<ObservedTargetCache.TargetEntry> = emptyList(),
        pixelReference: com.droidance.core.agent.tools.ObservationPixelReference? = null,
        nativeImagePng: ByteArray? = null,
        nativeFrameSha256: String? = null,
        nativeVisualFingerprint: Long? = null,
        foregroundComponent: String? = null
    ) {
        latestFrameTargets = targets
        setPendingVisionFrame?.invoke(
            PublishedVisionFrame(
                imageBase64 = base64,
                mode = mode,
                frameScale = frameScale,
                frameSize = frameSize,
                physicalFrameSize = physicalFrameSize,
                physicalOrigin = physicalOrigin,
                readableText = readableText,
                screenText = screenText,
                producedAtMs = producedAtMs,
                targets = targets,
                pixelReference = pixelReference,
                nativeImagePng = nativeImagePng,
                nativeFrameSha256 = nativeFrameSha256,
                nativeVisualFingerprint = nativeVisualFingerprint,
                foregroundComponent = foregroundComponent
            )
        )
    }

    private fun frameConfig(): VisionFrameScaler.Config =
        ObservationFrameConfig.fromPolicy(ruleConfigManager.harnessPolicy().visionFrame).scalerConfig()

    private fun logFrameScale(frame: VisionFrameScaler.ScaledFrame) {
        debugLogManager.appendEvent(
            "vision_frame: physical=${frame.physicalWidth}x${frame.physicalHeight} " +
                "model=${frame.width}x${frame.height} scale=${String.format(Locale.US, "%.4f", frame.scale)} " +
                "bytes=${frame.byteCount / 1024}KB"
        )
    }

    private fun recordCuaFrameMetadata(
        perceptionMode: String,
        modelFrame: VisionFrameScaler.ScaledFrame?,
        screenSize: Pair<Int, Int>?
    ) {
        val scale = modelFrame?.scale ?: 1f
        diagnostics.recordCuaFrame(
            perceptionMode = perceptionMode,
            modelGrid = modelFrame?.modelSize ?: screenSize,
            physicalSize = modelFrame?.physicalSize ?: screenSize,
            frameScale = scale,
            detailMode = if (scale < 0.999f) "scaled" else "original"
        )
    }

    fun executeCropZoom(arguments: String, original: ByteArray): RetainedImageCrop.Image =
        RetainedImageCrop.crop(arguments, original)

    suspend fun execute(providedFrame: DeviceScreenFrame? = null): ToolExecutionResult {
        val totalStarted = System.currentTimeMillis()
        val registry = capabilityRegistry.get()
        val capabilitySnapshot = registry.current()
        val executionContext = executionContextStore.requireCurrent()
        val displayId = executionContext.displayId
        // 按当前执行空间截图。虚拟空间必须保持与主屏隔离，不能在后台帧失败时偷看主屏。
        val captureDisplayId = displayId.takeUnless { it == 0 }
        val frame = providedFrame ?: if (executionContext.isVirtualSpace) {
            com.droidance.core.agent.tools.captureWithFrameRecovery(
                attempts = frameRecoveryRepeats(),
                capture = { captureObservedFrame() },
                reset = { virtualSpaceManager.get().resetFrameChannel() },
                report = { stage, success ->
                    diagnostics.recordObservation(displayId, stage, success,
                        if (success) "" else if (stage == "reset") virtualSpaceManager.get().lastFailureReason()
                        else deviceBackend.screenFailureMessage())
                }
            )
        } else captureObservedFrame()
        if (frame == null) {
            val reason = deviceBackend.screenFailureMessage()
            if (executionContext.isVirtualSpace) {
                debugLogManager.appendEvent("observe_screen displayId=$displayId screenshot failed; virtual space stays isolated: $reason")
                publishPendingScreenshot(null, MODE_NONE)
                val contentState = virtualSpaceManager.get().inspectDisplayContent(
                    SidecarSessionBinding(executionContext.mode, displayId, executionContext.backendSessionId)
                )
                if (contentState == VirtualDisplayContentState.EMPTY) {
                    return ToolProtocolResult.failed(
                        code = "VIRTUAL_SPACE_EMPTY",
                        retryable = true,
                        details = buildJsonObject {
                            put("display_id", JsonPrimitive(displayId))
                            put("display_valid", JsonPrimitive(true))
                            put("task_query_succeeded", JsonPrimitive(true))
                            put("activity_count", JsonPrimitive(0))
                        }
                    )
                }
                return ToolProtocolResult.failed(
                    code = "OBSERVATION_UNAVAILABLE",
                    retryable = false,
                    details = buildJsonObject {
                        put("display_id", JsonPrimitive(displayId))
                        put("cause", JsonPrimitive(reason.take(500)))
                        put("display_content_state", JsonPrimitive(contentState.name))
                        put("recovery_attempted", JsonPrimitive(true))
                        put("recovery_condition", JsonPrimitive("human_restores_observable_surface"))
                    }
                )
            }
            debugLogManager.appendEvent("observe_screen screenshot failed: $reason")
            return ToolProtocolResult.failed("SCREEN_CAPTURE_FAILED", true)
        }
        val ownPackages = setOf(appContext.packageName, "com.droidance", "com.droidance.debug")
        val fgPkg = localActionVerifier.currentForegroundPackageForContext(executionContext)
        if (fgPkg in ownPackages) {
            debugLogManager.appendEvent("observe_screen foreground is Droidance ($fgPkg)")
            publishPendingScreenshot(null, MODE_NONE)
            return ToolProtocolResult.notExecuted(
                code = "FOREGROUND_IS_DROIDANCE",
                retryable = true,
                details = buildJsonObject { put("foreground_package", JsonPrimitive(fgPkg.orEmpty())) }
            )
        }
        val bytes = frame.pngBytes
        val fingerprint = frame.sha256
        val perceptionMode = resolvePerceptionMode(capabilitySnapshot.visionProviderAvailable)
        val config = frameConfig()
        val cacheKey = observeCacheKey(fingerprint, perceptionMode, config)
        observeCache.get(cacheKey)?.let { cached ->
            publishPendingScreenshot(
                cached.modelFrameBase64,
                cached.mode,
                cached.frameScale,
                cached.frameSize,
                cached.physicalFrameSize,
                cached.frameOrigin,
                cached.readableText,
                cached.screenText,
                pixelReference = frame.pixelReference,
                producedAtMs = frame.producedAtMs,
                targets = cached.targets,
                nativeImagePng = bytes.takeIf { cached.mode == MODE_PURE_VLM },
                nativeFrameSha256 = frame.sha256,
                nativeVisualFingerprint = if (cached.mode == MODE_PURE_VLM) frame.visualFingerprint else null,
                foregroundComponent = frame.foregroundComponent
            )
            lastScreenFingerprint = fingerprint
            debugLogManager.appendEvent("⚡ observe_cache=hit mode=$perceptionMode fingerprint=$fingerprint")
            return if (cached.mode == MODE_NONE) ToolProtocolResult.failed("VISION_PROVIDER_UNAVAILABLE", false)
            else ToolProtocolResult.success(cached.responseText)
        }
        debugLogManager.appendEvent("⚡ observe_cache=miss mode=$perceptionMode fingerprint=$fingerprint")
        val captureMs = System.currentTimeMillis() - totalStarted

        if (perceptionMode == MODE_PURE_VLM) {
            val activeWindow = resolveActiveWindow(
                localActionVerifier.currentForegroundComponentForContext(executionContext)
            )
            val screenSize = frame.physicalSize
            val modelFrame = frame.modelFrame(config)?.also(::logFrameScale)
            val modelBase64 = modelFrame?.base64Jpeg ?: frame.base64()
            publishPendingScreenshot(
                modelBase64,
                MODE_PURE_VLM,
                modelFrame,
                readableText = null,
                screenText = null,
                pixelReference = frame.pixelReference,
                producedAtMs = frame.producedAtMs,
                targets = emptyList(),
                nativeImagePng = bytes,
                nativeFrameSha256 = frame.sha256,
                nativeVisualFingerprint = frame.visualFingerprint,
                foregroundComponent = frame.foregroundComponent
            )
            recordCuaFrameMetadata(
                perceptionMode = MODE_PURE_VLM,
                modelFrame = modelFrame,
                screenSize = screenSize
            )
            val responseText = buildPureVlmResponse(activeWindow, modelFrame?.modelSize ?: screenSize)
            val totalMs = System.currentTimeMillis() - totalStarted
            debugLogManager.appendEvent(
                "⚡ pure_vlm observe: capture=${captureMs}ms, capture_source=${frame.source}, " +
                    "total=${totalMs}ms, pkg=${activeWindow?.packageName ?: "unknown"}"
            )
            lastScreenFingerprint = fingerprint
            observeCache.put(
                cacheKey,
                ObserveCacheEntry(
                    fingerprint = fingerprint,
                    mode = MODE_PURE_VLM,
                    responseText = responseText,
                    modelFrameBase64 = modelBase64,
                    frameScale = modelFrame?.scale ?: 1f,
                    frameSize = modelFrame?.modelSize ?: screenSize,
                    physicalFrameSize = modelFrame?.physicalSize ?: screenSize,
                    frameOrigin = 0 to 0,
                    readableText = null,
                    screenText = null,
                    targets = emptyList()
                )
            )
            return ToolProtocolResult.success(responseText)
        }

        val activeWindow = resolveActiveWindow(
            localActionVerifier.currentForegroundComponentForContext(executionContext)
        )
        val screenSize = frame.physicalSize
        publishPendingScreenshot(null, MODE_NONE)
        recordCuaFrameMetadata(
            perceptionMode = MODE_NONE,
            modelFrame = null,
            screenSize = screenSize
        )
        val responseText = buildVisionUnavailableResponse(activeWindow, screenSize)
        val totalMs = System.currentTimeMillis() - totalStarted
        debugLogManager.appendEvent(
            "⚡ observe_unavailable: som=false, vision_provider=false, " +
                "capture=${captureMs}ms, capture_source=${frame.source}, total=${totalMs}ms"
        )
        lastScreenFingerprint = fingerprint
        observeCache.put(
            cacheKey,
            ObserveCacheEntry(
                fingerprint = fingerprint,
                mode = MODE_NONE,
                responseText = responseText,
                modelFrameBase64 = null,
                frameScale = 1f,
                frameSize = null,
                physicalFrameSize = null,
                frameOrigin = 0 to 0,
                readableText = null,
                targets = emptyList()
            )
        )
        return ToolProtocolResult.failed("VISION_PROVIDER_UNAVAILABLE", false)
    }

    private fun observeCacheKey(fingerprint: String, mode: String, config: VisionFrameScaler.Config): String =
        "$fingerprint|$mode|${config.maxLongEdge}:${config.maxPixels}:${config.jpegQuality}"

    private fun buildPureVlmResponse(
        activeWindow: ActiveWindow?,
        screenshotGrid: Pair<Int, Int>?
    ): String {
        return buildString {
            appendLine("[PURE_VLM_SCREEN]")
            appendLine("A clean Android screenshot is attached to the next vision model call.")
            if (screenshotGrid != null) {
                appendLine("Attached frame dimensions: ${screenshotGrid.first}x${screenshotGrid.second} pixels (rendering metadata only).")
                appendLine("Action coordinate space: normalized integers x=0..1000 and y=0..1000.")
                appendLine("Frame and physical device pixels are mapped internally and are not valid action coordinates.")
            }
            appendLine("The screenshot is clean; no element ids or coordinate grid are drawn on it.")
            appendLine()
            appendLine("Foreground: ${activeWindow?.packageName ?: "unknown"}/${activeWindow?.activityName ?: "unknown"}")
            appendLine()
        }.trimEnd()
    }



    private fun buildVisionUnavailableResponse(activeWindow: ActiveWindow?, screenSize: Pair<Int, Int>?): String {
        return buildString {
            appendLine("[VISION_PROVIDER_UNAVAILABLE]")
            appendLine("The active model provider cannot receive screenshots.")
            appendLine("No screenshot attachment was produced.")
            appendLine()
            appendLine("Foreground: ${activeWindow?.packageName ?: "unknown"}/${activeWindow?.activityName ?: "unknown"}")
            if (screenSize != null) {
                appendLine("Screen: ${screenSize.first}x${screenSize.second}")
            }
        }.trimEnd()
    }

    private fun resolvePerceptionMode(visionProviderAvailable: Boolean): String {
        return if (visionProviderAvailable) MODE_PURE_VLM else MODE_NONE
    }


    private fun parseActiveWindow(windowName: String?): ActiveWindow? {
        val component = windowName
            ?.trim()
            ?.substringBefore(' ')
            ?.substringBefore('#')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val separatorIndex = component.indexOf('/')
        if (separatorIndex <= 0) return ActiveWindow(packageName = component, activityName = null)

        val packageName = component.substring(0, separatorIndex)
        val rawActivityName = component.substring(separatorIndex + 1).takeIf { it.isNotBlank() }
        val activityName = rawActivityName?.let {
            if (it.startsWith(".")) packageName + it else it
        }
        return ActiveWindow(packageName = packageName, activityName = activityName)
    }

    private fun resolveActiveWindow(windowName: String?): ActiveWindow? = parseActiveWindow(windowName)

    private data class ActiveWindow(
        val packageName: String,
        val activityName: String?
    )

}
