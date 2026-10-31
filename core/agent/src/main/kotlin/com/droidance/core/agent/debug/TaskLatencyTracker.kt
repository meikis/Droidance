// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.debug

import com.droidance.core.agent.debug.*

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskLatencyTracker @Inject constructor() {
    data class Snapshot(
        val llmCalls: Int,
        val screenshots: Int,
        val airShellCommands: Int,
        val toolRounds: Int
    )

    private var startTimeMs = 0L
    private val timings = ConcurrentHashMap<String, Long>()

    val llmCalls = AtomicInteger(0)
    val screenshots = AtomicInteger(0)
    val airShellCommands = AtomicInteger(0)
    val toolRounds = AtomicInteger(0)

    @Volatile
    var hotPathBlockedByReviewer = false

    fun startTask() {
        startTimeMs = System.currentTimeMillis()
        timings.clear()
        llmCalls.set(0)
        screenshots.set(0)
        airShellCommands.set(0)
        toolRounds.set(0)
        hotPathBlockedByReviewer = false
    }

    fun recordTiming(label: String, durationMs: Long) {
        timings.merge(label, durationMs) { old, new -> old + new }
    }

    fun snapshot(): Snapshot {
        return Snapshot(
            llmCalls = llmCalls.get(),
            screenshots = screenshots.get(),
            airShellCommands = airShellCommands.get(),
            toolRounds = toolRounds.get()
        )
    }

    inline fun <T> measure(label: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            recordTiming(label, System.currentTimeMillis() - start)
        }
    }

    fun generateSummary(): String {
        val totalMs = System.currentTimeMillis() - startTimeMs
        return buildString {
            appendLine("=== Task Latency Summary ===")
            appendLine("Total Time: ${totalMs}ms")
            appendLine("LLM Calls: ${llmCalls.get()}")
            appendLine("Screenshots: ${screenshots.get()}")
            appendLine("AIR Shell Commands: ${airShellCommands.get()}")
            appendLine("Tool Rounds: ${toolRounds.get()}")
            appendLine("Blocked by Reviewer/Queue: $hotPathBlockedByReviewer")
            appendLine("Timings:")
            timings.forEach { (label, duration) ->
                appendLine("  - $label: ${duration}ms")
            }
            appendLine("============================")
        }
    }
}
