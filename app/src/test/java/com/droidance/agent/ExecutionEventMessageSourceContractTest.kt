// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEventMessageSourceContractTest {
    @Test
    fun homeUsesEventStoreProjectionWithoutPersistingRuntimeEventsInConversation() {
        val conversation = projectPath("app/src/main/java/com/droidance/config/Conversation.kt")
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        val home = projectFile("app/src/main/java/com/droidance/ui/screens/HomeScreen.kt").readText()
        val message = projectFile("app/src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt").readText()

        assertFalse(conversation.exists())
        assertFalse(viewModel.contains("onExecutionEvent = { event ->"))
        assertFalse(viewModel.contains("appendExecutionEvent(event)"))
        assertTrue(viewModel.contains("eventStore.observeThreadProjectionResult(conversationId)"))
        assertTrue(viewModel.contains("thread.segmentPresentations(timing)"))
        assertFalse(viewModel.contains("executionEvents = msg.executionEvents"))
        assertFalse(viewModel.contains("executionEvents = it.executionEvents"))
        assertFalse(home.contains("executionEvents = message.executionEvents"))
        assertTrue(message.contains("internal fun AppTakeoverLogCard("))
    }

    @Test
    fun timeline_does_not_infer_events_from_rendered_message_text() {
        val timeline = projectFile("app/src/main/java/com/droidance/ui/execution/EventStoreProcessTimeline.kt").readText()

        assertFalse(timeline.contains("parseProcessNodes"))
        assertFalse(timeline.contains("ExecutionEvent"))
        assertFalse(timeline.contains("<process>"))
        assertFalse(timeline.contains("startsWith(\"> \")"))
        assertFalse(timeline.contains("indexOf('✓')"))
        assertFalse(timeline.contains("indexOf('✗')"))
        assertFalse(timeline.contains("Regex("))
    }

    private fun projectFile(path: String): File {
        val file = projectPath(path)
        if (file.exists()) return file
        error("Cannot locate $path")
    }

    private fun projectPath(path: String): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            if (File(current, "settings.gradle.kts").exists()) return File(current, path)
            current = current.parentFile ?: error("Cannot locate ${path}")
        }
    }
}
