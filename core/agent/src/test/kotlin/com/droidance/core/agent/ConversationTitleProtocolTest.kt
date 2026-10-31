// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.junit.Assert.*
import org.junit.Test

class ConversationTitleProtocolTest {
    @Test fun namingMetadataStaysHiddenAtEveryStreamBoundary() {
        val wire = "<conversation_title>{\"title\":\"杭州行程规划\"}</conversation_title>\n我会先确认行程。"
        for (split in 0..wire.length) {
            val stream = PublicAssistantTextStream()
            val first = stream.append(wire.take(split))
            assertFalse(first.contains("杭州行程规划"))
            assertFalse(first.contains("conversation_title"))
            assertEquals("我会先确认行程。", first + stream.append(wire.drop(split)) + stream.finish())
        }
        val result = ConversationTitleProtocol.extract(wire)
        assertEquals("杭州行程规划", result.title)
        assertEquals("我会先确认行程。", result.body)
    }

    @Test fun interruptedNamingBlockAndItsPartialTagNeverBecomePublicText() {
        val wire = "<conversation_title>{\"title\":\"杭州行程规划\"}</conversation_title>"
        for (length in 1 until wire.length) {
            val stream = PublicAssistantTextStream()
            assertEquals("", stream.append(wire.take(length)) + stream.finish())
        }
    }

    @Test fun invalidOptionalMetadataDoesNotDiscardTheAnswer() {
        for (json in listOf("{}", "{\"title\":null}", "{\"title\":12}", "{\"title\":\" \"}",
            "{\"title\":\"x\",\"extra\":true}", "not json", "{\"title\":\"${"x".repeat(37)}\"}",
            "{\"title\":\"line\\nbreak\"}", "{\"title\":\"<think>hidden</think>\"}")) {
            val result = ConversationTitleProtocol.extract("<conversation_title>$json</conversation_title>Answer")
            assertNull(json, result.title)
            assertEquals("Answer", result.body)
        }
    }

    @Test fun titleLengthCountsUnicodeCharactersAndRejectsOverlongTitlesWithoutTruncating() {
        for (character in listOf("字", "x", "😀")) {
            val title = character.repeat(36)
            assertEquals(title, ConversationTitleProtocol.extract(
                "<conversation_title>{\"title\":\"$title\"}</conversation_title>Answer").title)
            val tooLong = ConversationTitleProtocol.extract(
                "<conversation_title>{\"title\":\"$title$character\"}</conversation_title>Answer")
            assertNull(tooLong.title)
            assertEquals("Answer", tooLong.body)
        }
    }

    @Test fun multipleTitlesAreIgnoredAndOrdinaryTextIsPreserved() {
        val block = "<conversation_title>{\"title\":\"Example\"}</conversation_title>"
        val result = ConversationTitleProtocol.extract("$block$block\nAnswer")
        assertNull(result.title)
        assertEquals("Answer", result.body)
        val ordinary = "<b>Answer</b> 1 < 2"
        assertEquals(ordinary, ConversationTitleProtocol.extract(ordinary).body)
    }

}
