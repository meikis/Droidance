// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import kotlin.math.ceil

/** Input metrics only: compositor mirrors have no JPEG polling or app-side displayed-frame counter. */
internal class VirtualSpaceViewerPerformanceTracker(private val reportWindowMs: Long = 5_000L) {
    private var windowStartedAtMs: Long? = null
    private var maxPendingTouchCount = 0
    private val touchQueueLagMs = mutableListOf<Long>()
    private val touchInjectionMs = mutableListOf<Long>()

    @Synchronized fun recordTouchQueued(pendingCount: Int) {
        maxPendingTouchCount = maxOf(maxPendingTouchCount, pendingCount)
    }

    @Synchronized fun recordTouchDispatched(queueLagMs: Long, injectionMs: Long) {
        touchQueueLagMs += queueLagMs.coerceAtLeast(0L)
        touchInjectionMs += injectionMs.coerceAtLeast(0L)
    }

    @Synchronized fun report(nowMs: Long): Report? {
        val started = windowStartedAtMs ?: nowMs.also { windowStartedAtMs = it }
        if (nowMs - started < reportWindowMs) return null
        val result = if (touchQueueLagMs.isEmpty()) null else Report(
            maxPendingTouchCount, percentile(touchQueueLagMs), percentile(touchInjectionMs))
        windowStartedAtMs = nowMs
        maxPendingTouchCount = 0
        touchQueueLagMs.clear()
        touchInjectionMs.clear()
        return result
    }

    data class Report(val maxPendingTouchCount: Int, val touchQueueLagP95Ms: Long, val touchInjectionP95Ms: Long) {
        fun toLogLine(): String = "virtual_space_viewer_input_performance" +
            " touch_pending_max=$maxPendingTouchCount touch_queue_lag_p95_ms=$touchQueueLagP95Ms" +
            " touch_injection_p95_ms=$touchInjectionP95Ms"
    }

    private fun percentile(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceAtLeast(0)]
    }
}
