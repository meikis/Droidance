// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One monotonic clock for a Turn's active work, including model and tool execution. */
class TaskExecutionClock(private val nowMs: () -> Long = ::monotonicTimeMs) {
    data class Snapshot(val accumulatedMs: Long, val runningSinceMs: Long?) {
        fun elapsedMs(nowMs: Long = monotonicTimeMs()): Long = accumulatedMs +
            (runningSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L)
    }

    private val _state = MutableStateFlow(Snapshot(0L, nowMs()))
    val state: StateFlow<Snapshot> = _state.asStateFlow()
    private var finished = false

    @Synchronized
    fun elapsedMs(): Long = _state.value.elapsedMs(nowMs())

    @Synchronized
    fun pause() {
        val current = _state.value
        if (current.runningSinceMs != null) {
            _state.value = Snapshot(current.elapsedMs(nowMs()), null)
        }
    }

    @Synchronized
    fun resume() {
        if (!finished && _state.value.runningSinceMs == null) {
            _state.value = _state.value.copy(runningSinceMs = nowMs())
        }
    }

    @Synchronized
    fun finish(): Long {
        pause()
        finished = true
        return _state.value.accumulatedMs
    }

    companion object {
        fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L
    }
}

data class LiveTaskTiming(val turnId: String, val clock: TaskExecutionClock.Snapshot)
