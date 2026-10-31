// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PublicAssistantTextTest {
    @Test
    fun finalMarkdownKeepsNestedListsCodeIndentationAndHardBreaks() {
        val reply = "# Findings\n\n- Result\n    - Qualified detail\n\n```text\n    aligned    columns\n```\n\nFirst line  \nSecond line"
        assertEquals(reply, PublicAssistantText.sanitize(reply))
    }

    @Test
    fun reasoning_transport_is_removed_at_every_stream_boundary() {
        val tag = "think_never_used_51bce0c785ca2f68081bfa7d91973934"
        for (wire in listOf("继续读取</$tag>", "<think>private</think>继续读取", "<$tag>private</$tag>继续读取")) {
            for (split in 0..wire.length) {
                val stream = PublicAssistantTextStream()
                val first = stream.append(wire.take(split))
                assertFalse(first.contains("private"))
                assertFalse(first.contains("think"))
                val text = first + stream.append(wire.drop(split)) + stream.finish()
                assertEquals("继续读取", text)
            }
            assertEquals("继续读取", PublicAssistantText.sanitize(wire))
        }
    }

    @Test
    fun interrupted_control_prefix_is_not_flushed_as_public_text() {
        val stream = PublicAssistantTextStream()
        assertEquals("继续", stream.append("继续</think_never_used_abcd"))
        assertEquals("", stream.finish())
    }

    @Test
    fun ordinary_markup_and_comparisons_are_not_removed() {
        assertEquals("<b>hello</b> 1 < 2", PublicAssistantText.sanitize("<b>hello</b> 1 < 2"))
        assertEquals("hello", PublicAssistantText.sanitize("hello<think>private"))
    }

    @Test
    fun plainModelReplyIsNotRewritten() {
        val reply = "I could not confirm the requested result."

        assertEquals(reply, PublicAssistantText.sanitize(reply))
    }

    @Test
    fun internalEvidenceNeverReachesPublicText() {
        val reply = """
            Done checking.
            [[DROIDANCE_TASK_EVIDENCE]]
            fact_1: internal detail
            [[/DROIDANCE_TASK_EVIDENCE]]
        """.trimIndent()

        assertEquals("Done checking.", PublicAssistantText.sanitize(reply))
    }

    @Test
    fun visionTransportEnvelopeDoesNotExposeScreenText() {
        val reply = """
            [PURE_VLM_SCREEN]
            Attached frame dimensions: 1080x2400
            Readable text digest:
            Settings
        """.trimIndent()

        assertEquals("", PublicAssistantText.sanitize(reply))
    }

    @Test
    fun sanitizerDoesNotContainTaskStatusMarkupProtocol() {
        val source = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/PublicAssistantText.kt").readText()

        assertFalse(source.contains("TaskReportFormatter"))
        assertFalse(source.contains("<report"))
        assertFalse(source.contains("<process"))
    }

    private fun projectFile(path: String) = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
        .map { java.io.File(it, path) }
        .firstOrNull { it.isFile }
        ?: error("Missing project file: $path")
}
