// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

object VirtualSpaceFrameFreshness {
    data class FrameSample(
        val bytes: ByteArray,
        val producedAtMs: Long
    )

    data class WaitResult(
        val fresh: Boolean,
        val sawPreviousRenderableFrame: Boolean,
        val polls: Int = 0,
        val missingFrames: Int = 0,
        val blackFrames: Int = 0,
        val oldFrames: Int = 0,
        val unchangedFrames: Int = 0,
        val elapsedMs: Long = 0
    )

    fun isFresh(
        previousFrame: FrameSample?,
        candidateFrame: FrameSample?,
        isBlack: Boolean,
        minProducedAtMs: Long,
        requireContentChange: Boolean = true
    ): Boolean {
        if (candidateFrame == null || isBlack || candidateFrame.producedAtMs <= minProducedAtMs) return false
        return !requireContentChange || previousFrame == null || !previousFrame.bytes.contentEquals(candidateFrame.bytes)
    }

    fun waitForFreshFrame(
        previousFrame: FrameSample?,
        minProducedAtMs: Long,
        timeoutMs: Long,
        requireContentChange: Boolean = true,
        pollMs: Long,
        capture: (remainingMs: Long) -> FrameSample?,
        isBlack: (ByteArray) -> Boolean,
        nowMs: () -> Long,
        sleep: (Long) -> Unit
    ): WaitResult {
        val startedMs = nowMs()
        val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
        var sawPreviousRenderableFrame = false
        var consecutiveFreshPolls = 0
        var polls = 0
        var missing = 0
        var black = 0
        var old = 0
        var unchanged = 0
        fun result(fresh: Boolean) = WaitResult(fresh, sawPreviousRenderableFrame,
            polls, missing, black, old, unchanged, (nowMs() - startedMs).coerceAtLeast(0L))
        while (true) {
            val elapsedMs = (nowMs() - startedMs).coerceAtLeast(0L)
            val remainingMs = boundedTimeoutMs - elapsedMs
            if (remainingMs <= 0L) break

            polls++
            val candidateFrame = capture(remainingMs)
            if (candidateFrame == null) { missing++; consecutiveFreshPolls = 0 }
            if (candidateFrame != null) {
                val candidateIsBlack = isBlack(candidateFrame.bytes)
                when {
                    candidateIsBlack -> black++
                    candidateFrame.producedAtMs <= minProducedAtMs -> old++
                    requireContentChange && previousFrame?.bytes?.contentEquals(candidateFrame.bytes) == true -> unchanged++
                }
                if (isFresh(previousFrame, candidateFrame, candidateIsBlack, minProducedAtMs, requireContentChange)) {
                    consecutiveFreshPolls += 1
                    if (consecutiveFreshPolls >= REQUIRED_FRESH_POLLS) {
                        return result(true)
                    }
                } else {
                    consecutiveFreshPolls = 0
                }
                if (!candidateIsBlack) sawPreviousRenderableFrame = true
            }

            val remainingAfterCaptureMs = boundedTimeoutMs - (nowMs() - startedMs).coerceAtLeast(0L)
            val sleepMs = minOf(pollMs.coerceAtLeast(1L), remainingAfterCaptureMs)
            if (sleepMs > 0L) sleep(sleepMs)
        }
        return result(false)
    }

    // 连续两次可读即可；不能要求 PNG 字节相同，否则含动画/时钟的正常页面永远无法就绪。
    private const val REQUIRED_FRESH_POLLS = 2
}
