// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ContextSummaryRunnerTest {
    @Test
    fun `every source character is summarized and previous summaries cross chunk boundaries`() = runBlocking {
        val chunks = mutableListOf<String>()
        val previous = mutableListOf<String>()
        val result = ContextSummaryRunner(String::length).summarize("abcdefghijklmnopqrst", 10, 2) { prior, chunk ->
            assertTrue(prior.length + chunk.length + 2 <= 10)
            previous += prior
            chunks += chunk
            "memo"
        }
        assertEquals("abcdefghijklmnopqrst", chunks.joinToString(""))
        assertEquals("", previous.first())
        assertTrue(previous.drop(1).all { it == "memo" })
        assertEquals("memo", result)
    }

    @Test
    fun `new task does not inherit a previous tasks summary`() = runBlocking {
        val runner = ContextSummaryRunner(String::length)
        runner.summarize("task-one", 100, 1) { _, _ -> "old private context" }
        runner.summarize("task-two", 100, 1) { prior, chunk ->
            assertEquals("", prior)
            assertEquals("task-two", chunk)
            "new summary"
        }
        Unit
    }

    @Test
    fun `empty output and cancellation never produce a partial summary`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ContextSummaryRunner(String::length).summarize("source", 20, 1) { _, _ -> " " } }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { ContextSummaryRunner(String::length).summarize("source", 20, 1) { _, _ -> throw CancellationException() } }
        }
    }

    @Test
    fun `source with surrogate pairs is not corrupted at chunk boundaries`() = runBlocking {
        val seen = mutableListOf<String>()
        ContextSummaryRunner(String::length).summarize("abc😀def", 5, 1) { _, chunk ->
            seen += chunk
            "x"
        }
        assertEquals("abc😀def", seen.joinToString(""))
        assertTrue(seen.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test
    fun `capacity exposes reserve and permits reporting an overfull input`() {
        val capacity = ContextCapacity(110, 150, 100, 90)
        assertEquals(110, capacity.usedPercent)
        assertTrue(capacity.render().contains("model_window_tokens=150"))
        assertTrue(capacity.render().contains("usable_input_tokens=100"))
        assertTrue(capacity.render().contains("estimation=\"structural\""))
    }
}
