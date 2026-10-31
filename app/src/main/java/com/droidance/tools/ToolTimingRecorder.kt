// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.util.Log
import kotlinx.coroutines.CancellationException

class ToolTimingRecorder {
    data class Event(
        val name: String,
        val durationMs: Long,
        val success: Boolean,
        val detail: String
    )

    private val lock = Any()
    private val events = ArrayDeque<Event>()

    suspend fun <T> measure(name: String, block: suspend () -> T): T {
        val started = System.currentTimeMillis()
        return try {
            val value = block()
            record(name, System.currentTimeMillis() - started, success = true)
            value
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            record(name, System.currentTimeMillis() - started, success = false, detail = e.message.orEmpty())
            throw e
        }
    }

    fun record(name: String, durationMs: Long, success: Boolean, detail: String = "") {
        val event = Event(name, durationMs, success, detail.take(MAX_DETAIL_CHARS).replace("\n", " "))
        synchronized(lock) {
            events.addLast(event)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        Log.d("DroidanceTiming", "$name ${durationMs}ms ${if (success) "ok" else "fail"} ${event.detail}")
    }

    fun summary(limit: Int = 20): String {
        val items = synchronized(lock) { events.takeLast(limit.coerceIn(1, MAX_EVENTS)) }
        if (items.isEmpty()) return "No tool timing records"
        return items.joinToString("\n") {
            "- ${it.name}: ${it.durationMs}ms ${if (it.success) "ok" else "fail"}${it.detail.takeIf { detail -> detail.isNotBlank() }?.let { detail -> " / $detail" } ?: ""}"
        }
    }

    private companion object {
        const val MAX_EVENTS = 80
        const val MAX_DETAIL_CHARS = 80
    }
}
