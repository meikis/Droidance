// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSwitchSourceContractTest {
    @Test
    fun switched_turn_cannot_append_old_cancel_summary_to_new_history() {
        val source = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        assertTrue(source.contains("private var conversationEpoch: Long = 0L"))
        assertTrue(source.contains("val turnEpoch = conversationEpoch"))
        assertTrue(source.contains("if (turnEpoch != conversationEpoch)"))
        val guardedCancellationWrite = Regex(
            Regex.escape("if (turnEpoch == conversationEpoch) {") + "\\s*" +
                Regex.escape("conversationHistory.add(ChatMessage(role = Role.ASSISTANT, content = cancelSummary))")
        )
        assertTrue(guardedCancellationWrite.containsMatchIn(source))
        assertTrue(source.contains("conversationEpoch += 1L"))
    }

    private fun projectFile(path: String): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path")
    }
}
