// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.device.*
import com.droidance.core.device.agent.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import javax.inject.Inject
import javax.inject.Singleton

/** Captures only a frozen Standard virtual display; display zero is never an observation source. */
@Singleton
class SidecarScreenCapture @Inject constructor(
    private val sidecarBridge: VirtualSpaceSidecarBridge,
    private val executionContextStore: ExecutionContextStore,
    private val virtualSpaceRuntimePolicy: VirtualSpaceFrameInspector,
    private val latencyTracker: com.droidance.core.agent.debug.TaskLatencyTracker,
    private val samplingPolicy: ObservationSamplingPolicy
) {
    @Volatile private var lastFailureReason = ""
    @Volatile private var cachedFrame: DeviceScreenFrame? = null
    fun invalidateFrameCache() { cachedFrame = null }
    fun getLastCapturedFrame(): DeviceScreenFrame? = cachedFrame
    fun isAvailable(): Boolean = sidecarBridge.isResidentBinderAlive()
    fun failureMessage(): String = "Screenshot failed: $lastFailureReason"
    suspend fun captureFrame(forceFresh: Boolean = false, displayId: Int? = null): DeviceScreenFrame? {
        val execution = executionContextStore.current()?.takeIf { displayId == null || it.displayId == displayId }
            ?: return null.also { lastFailureReason = "[VIRTUAL_SPACE_UNAVAILABLE] No frozen display" }
        return captureBoundFrame(execution, forceFresh)
    }
    suspend fun captureBoundFrame(executionContext: ExecutionContext, forceFresh: Boolean = false): DeviceScreenFrame? {
        if (!executionContext.isVirtualSpace || executionContext.mode != RuntimeMode.STANDARD || executionContext.displayId <= 0) {
            lastFailureReason = "[VIRTUAL_SPACE_UNAVAILABLE] Only Standard virtual-display capture is supported"
            return null
        }
        val displayId = executionContext.displayId
        val collector = kotlin.coroutines.coroutineContext[ObservationTiming]
        return runInterruptible(Dispatchers.IO) {
            captureFromBoundSidecar(executionContext, displayId, forceFresh, collector)
        }
    }
    private fun captureFromBoundSidecar(
        executionContext: ExecutionContext,
        displayId: Int?,
        forceFresh: Boolean,
        observationCollector: ObservationTiming? = null
    ): DeviceScreenFrame? {
        val binding = SidecarSessionBinding(
            runtimeMode = executionContext.mode,
            displayId = executionContext.displayId,
            backendSessionId = executionContext.backendSessionId
        )
        if (!sidecarBridge.isBoundSessionAlive(binding)) {
            lastFailureReason = "[BACKEND_SESSION_MISMATCH] Frozen screenshot backend is unavailable"
            return null
        }
        val started = System.currentTimeMillis()
        val snapshot = sidecarBridge.captureFrame(binding, forceFresh, samplingPolicy.sampleSide())
        observationCollector?.let { collector ->
            snapshot?.timings?.forEach { (stage, millis) -> collector.add(stage, millis) }
        }
        val bytes = snapshot?.png
        latencyTracker.recordTiming(
            if (bytes != null) "screenshot.bound_sidecar.ok" else "screenshot.bound_sidecar.miss",
            System.currentTimeMillis() - started
        )
        if (bytes == null || !(looksLikePng(bytes) || looksLikeJpeg(bytes))) {
            lastFailureReason = "[BACKEND_FRAME_FAILED] " + sidecarBridge.captureFailureDetail(binding)
                .ifBlank { "Frozen sidecar did not return a valid image" }
            return null
        }
        if (executionContext.isVirtualSpace) {
            val blackFrame = virtualSpaceRuntimePolicy.checkBlackFrame(bytes)
            if (blackFrame.isBlack) {
                lastFailureReason = virtualSpaceRuntimePolicy.emptyFrameFailure()
                return null
            }
        }
        return cacheBoundFrame(bytes, "bound_sidecar", displayId, snapshot.producedAtMs, snapshot.foregroundComponent, snapshot.pixelReference)
    }

    private fun cacheBoundFrame(bytes: ByteArray, source: String, displayId: Int?, producedAtMs: Long? = null, foregroundComponent: String? = null, pixelReference: ObservationPixelReference? = null): DeviceScreenFrame {
        lastFailureReason = ""
        latencyTracker.screenshots.incrementAndGet()
        return DeviceScreenFrame(
            pngBytes = bytes,
            source = source,
            producedAtMs = producedAtMs,
            foregroundComponent = foregroundComponent,
            pixelReference = pixelReference ?: rawPixelReference(bytes),
            displayId = displayId
        ).also { cachedFrame = it }
    }

    private fun rawPixelReference(bytes: ByteArray): ObservationPixelReference? {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val size = bitmap.width to bitmap.height
            val samples = ObservationPixelComparator.mappedGrid(ModelCoordinateMapper(scale = 1f,
                modelSize = size, physicalSize = size), size, size, samplingPolicy.sampleSide())
            ObservationPixelComparator.captureReference(bitmap.width, bitmap.height, samples, bitmap::getPixel)
        } finally { bitmap.recycle() }
    }

    /** Fresh decision frames are JPEG; preview frames may still be PNG. */
    private fun looksLikeJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    private fun looksLikePng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
    }
}
