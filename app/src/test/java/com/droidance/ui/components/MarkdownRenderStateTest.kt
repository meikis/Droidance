// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import org.junit.Assert.*
import org.junit.Test

class MarkdownRenderStateTest {
    @Test fun everyStreamPrefixPreservesSourceIncludingTablesListsAndFences() {
        val content = "# Title\n\nParagraph **bold**.\n\n1. First\n\n2. Second\n\n```text\n| a | b |\n\ncode\n```\n\n| A | B |\n| --- | --- |\n| x | y |\n\nEnd."
        val state = MarkdownRenderState()
        for (length in 0..content.length) {
            val prefix = content.take(length)
            assertEquals("Source at $length", prefix, state.update(prefix).joinToString(""))
        }
        val blocks = state.update(content)
        assertTrue(blocks.any { it.contains("1. First\n\n2. Second") })
        assertTrue(blocks.any { it.contains("```text\n| a | b |\n\ncode\n```") })
        assertTrue(blocks.any { it.contains("| A | B |\n| --- | --- |\n| x | y |") })
    }

    @Test fun completedParagraphStaysTheSameWhileTailAndNewBlocksArrive() {
        val state = MarkdownRenderState()
        val first = state.update("Completed.\n\nTail\n").first()
        assertSame(first, state.update("Completed.\n\nTail\ncontinues.").first())
        assertSame(first, state.update("Completed.\n\nTail\ncontinues.\n\nNew tail").first())
    }

    @Test fun replacementAndTruncationDoNotKeepStaleBlocks() {
        val state = MarkdownRenderState()
        state.update("Old.\n\nReply.")
        assertEquals("New.", state.update("New.").joinToString(""))
        assertEquals("", state.update("").joinToString(""))
    }

    @Test fun referenceDefinitionsKeepTheirDocumentContext() {
        val state = MarkdownRenderState()
        state.update("Read [the source][id].\n\nAnother paragraph.")
        val content = "Read [the source][id].\n\nAnother paragraph.\n\n[id]: https://example.com"
        assertEquals(listOf(content), state.update(content))
    }

    @Test fun incompleteFenceAndTableHeaderStayInTheActiveTail() {
        val state = MarkdownRenderState()
        val blocks = state.update("Intro.\n\n```kotlin\nval a = 1\n\nval b = 2")
        assertEquals(2, blocks.size)
        assertTrue(blocks.last().contains("val a = 1\n\nval b = 2"))
        val table = MarkdownRenderState().update("Intro.\n\n| A | B |\n| --- | --- |\n| x |")
        assertTrue(table.last().contains("| A | B |\n| --- | --- |\n| x |"))
    }
}
