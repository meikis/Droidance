// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.graphics.BitmapFactory
import com.droidance.config.RuleConfigManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

typealias BlackFrameCheck = VirtualSpaceFrameInspector.BlackFrameCheck

@Singleton
class VirtualSpaceRuntimePolicy @Inject constructor(
    private val ruleConfigManager: RuleConfigManager
) : VirtualSpaceFrameInspector {

    override fun renderWaitTimeoutMs(): Long =
        ruleConfigManager.harnessPolicy().virtualSpace.renderWaitTimeoutMs

    override fun renderWaitPollMs(): Long =
        ruleConfigManager.harnessPolicy().virtualSpace.renderWaitPollMs

    override fun emptyFrameFailure(): String {
        val guidance = ruleConfigManager.message("virtual_space_empty_frame_guidance")
        return listOf(VirtualSpaceFrameInspector.EMPTY_FRAME_MARKER, guidance)
            .filter(String::isNotBlank)
            .joinToString(" ")
    }

    override fun checkBlackFrame(pngBytes: ByteArray): VirtualSpaceFrameInspector.BlackFrameCheck {
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            ?: return VirtualSpaceFrameInspector.BlackFrameCheck(isBlack = false, mean = 255.0, variance = 0.0)
        try {
            val stepX = max(1, bitmap.width / 32)
            val stepY = max(1, bitmap.height / 64)
            var count = 0
            var sum = 0.0
            var sumSquares = 0.0
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                    sum += luminance
                    sumSquares += luminance * luminance
                    count++
                    x += stepX
                }
                y += stepY
            }
            if (count == 0) return BlackFrameCheck(isBlack = false, mean = 255.0, variance = 0.0)
            val mean = sum / count
            val variance = sumSquares / count - mean * mean
            val policy = ruleConfigManager.harnessPolicy().virtualSpace
            return BlackFrameCheck(
                isBlack = mean <= policy.blackFrameMeanThreshold &&
                    variance <= policy.blackFrameVarianceThreshold,
                mean = mean,
                variance = variance
            )
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val EMPTY_FRAME_MARKER = "[VIRTUAL_SPACE_FRAME_EMPTY]"
    }
}
