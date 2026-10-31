// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import android.graphics.BitmapFactory
import com.droidance.core.device.agent.ScreenVisualFingerprint
import com.droidance.core.device.tools.observe.VisionFrameScaler
import java.util.Base64

data class DeviceScreenFrame(
    val pngBytes: ByteArray,
    val source: String,
    val displayId: Int? = null,
    val capturedAtMs: Long = System.currentTimeMillis(),
    val producedAtMs: Long? = null,
    val foregroundComponent: String? = null,
    val pixelReference: ObservationPixelReference? = null
) {
    // Captured bytes belong to this frame and must not be modified by consumers.
    val contentHash: Long by lazy { pngBytes.contentHashCode().toLong() }
    val sha256: String by lazy { screenFrameFingerprint(pngBytes) }
    val visualFingerprint: Long? by lazy { ScreenVisualFingerprint.fromBytes(pngBytes) }
    val physicalSize: Pair<Int, Int>? by lazy {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, bounds)
        (bounds.outWidth to bounds.outHeight).takeIf { it.first > 0 && it.second > 0 }
    }
    private val encodedPng: String by lazy { Base64.getEncoder().encodeToString(pngBytes) }
    private val modelFrames = FrameDerivationCache<VisionFrameScaler.Config, VisionFrameScaler.ScaledFrame>()

    fun base64(): String = encodedPng
    fun modelFrame(config: VisionFrameScaler.Config): VisionFrameScaler.ScaledFrame? =
        modelFrames.getOrCompute(config) { VisionFrameScaler.scale(pngBytes, config) }
}

interface ObservationSamplingPolicy {
    fun sampleSide(): Int
}
