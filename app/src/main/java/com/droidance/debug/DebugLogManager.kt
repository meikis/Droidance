// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.memory.MarkdownFileManager
import android.util.Log
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight operational log. Durable Agent facts and replay data belong to AgentEventStore.
 */
@Singleton
class DebugLogManager @Inject constructor(
    private val fileManager: MarkdownFileManager
) : DebugLogAppender {
    companion object {
        private const val DEBUG_DIR = "debug"
        private const val LATEST_FILE = "$DEBUG_DIR/conversation-latest.md"
    }

    private val readableTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** Append diagnostics only; this stream must never be used for replay or recovery. */
    override fun appendEvent(event: String) {
        appendDiagnostic(LATEST_FILE, event)
    }

    /**
     * Append a subsystem event to a stream that is not replaced when a new model session starts.
     * This keeps setup failures diagnosable even when the user runs a task before exporting logs.
     */
    override fun appendPersistentEvent(stream: String, event: String) {
        require(stream.matches(Regex("[a-z0-9-]+"))) { "Invalid debug event stream" }
        appendDiagnostic("$DEBUG_DIR/$stream-events.md", event)
    }

    private fun appendDiagnostic(path: String, event: String) {
        val now = readableTimeFormat.format(Date())
        try {
            fileManager.appendFile(path, "\n> **[$now]** $event\n")
        } catch (_: IOException) {
            // Diagnostic storage is not an execution dependency. Do not echo private event data.
            Log.w("DebugLogManager", "DIAGNOSTIC_LOG_WRITE_FAILED")
        }
    }

}
