// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ContextCompactionExecutionTest {
    private val policy = ContextCompactionExecutionPolicy(90_000, 240_000, 1, 2048, 8192, 24000, 1, 4096)

    @Test
    fun `summary has no application deadline when disabled`() = runBlocking {
        val runner = ContextCompactionExecution(policy.copy(requestTimeoutMs = 0, totalTimeoutMs = 0))
        assertEquals("complete", runner.run {
            runner.request { timeout, _ ->
                assertEquals(0L, timeout)
                delay(30)
                "complete"
            }
        })
    }

    @Test
    fun `failed request retries the same chunk without retaining partial output`() = runBlocking {
        val runner = ContextCompactionExecution(policy)
        val chunks = mutableListOf<String>()
        var attempts = 0
        val summary = runner.run {
            ContextSummaryRunner(String::length).summarize("source", 30, 1) { _, chunk ->
                runner.request { _, _ ->
                    chunks += chunk
                    if (attempts++ == 0) throw IOException("connection lost")
                    "verified summary"
                }
            }
        }
        assertEquals(listOf("source", "source"), chunks)
        assertEquals("verified summary", summary)
    }

    @Test
    fun `request timeout is bounded and classified separately from cancellation`() {
        val runner = ContextCompactionExecution(policy.copy(requestTimeoutMs = 20, totalTimeoutMs = 500))
        var attempts = 0
        val error = assertThrows(ContextCompactionUnavailable::class.java) {
            runBlocking { runner.run { runner.request { _, _ -> attempts++; delay(1000); "partial" } } }
        }
        assertEquals(2, attempts)
        assertEquals("COMPACTION_REQUEST_TIMEOUT", error.diagnostic)
    }

    @Test
    fun `nested transport timeout does not masquerade as user cancellation`() {
        val runner = ContextCompactionExecution(policy)
        var attempts = 0
        val error = assertThrows(ContextCompactionUnavailable::class.java) {
            runBlocking { runner.run { runner.request { _, _ ->
                attempts++
                withTimeout(20) { delay(1000); "partial" }
            } } }
        }
        assertEquals("COMPACTION_REQUEST_TIMEOUT", error.diagnostic)
        assertEquals(2, attempts)
    }

    @Test
    fun `total budget covers all chunks and retries`() {
        val runner = ContextCompactionExecution(policy.copy(requestTimeoutMs = 30, totalTimeoutMs = 50))
        val error = assertThrows(ContextCompactionUnavailable::class.java) {
            runBlocking { runner.run { runner.request { _, _ -> delay(1000); "partial" } } }
        }
        assertEquals("COMPACTION_TOTAL_TIMEOUT", error.diagnostic)
    }

    @Test
    fun `output truncation retries the same source with a larger bounded allowance`() = runBlocking {
        val runner = ContextCompactionExecution(policy)
        val budgets = mutableListOf<Int>()
        val sourceChunks = mutableListOf<String>()
        val summary = runner.run {
            ContextSummaryRunner(String::length).summarize("original source", 100, 1) { previous, chunk ->
                assertTrue(previous.isEmpty())
                runner.request { _, tokens ->
                    sourceChunks += chunk
                    budgets += tokens
                    if (budgets.size == 1) throw ContextCompactionOutputLimit()
                    "complete summary"
                }
            }
        }
        assertEquals(listOf(2048, 4096), budgets)
        assertEquals(listOf("original source", "original source"), sourceChunks)
        assertEquals("complete summary", summary)
    }

    @Test
    fun `exhausted output allowance is unavailable and never a partial checkpoint`() {
        val runner = ContextCompactionExecution(policy.copy(requestRetries = 4, retryMaxOutputTokens = 3000))
        val budgets = mutableListOf<Int>()
        val error = assertThrows(ContextCompactionUnavailable::class.java) {
            runBlocking { runner.run { runner.request { _, tokens ->
                budgets += tokens
                throw ContextCompactionOutputLimit()
            } } }
        }
        assertEquals(listOf(2048, 3000), budgets)
        assertEquals("COMPACTION_OUTPUT_LIMIT", error.diagnostic)
    }

    @Test
    fun `parent timeout and cancellation are never converted into recoverable failure`() {
        val runner = ContextCompactionExecution(policy)
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { withTimeout(20) { runner.run { runner.request { _, _ -> delay(1000); "partial" } } } }
        }
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { runner.run { runner.request { _, _ -> attempts++; throw CancellationException("user canceled") } } }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `data and protocol failures are not retried`() {
        val runner = ContextCompactionExecution(policy)
        var attempts = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { runner.run { runner.request { _, _ -> attempts++; errorArgument() } } }
        }
        assertEquals(1, attempts)
    }

    private fun errorArgument(): String = throw IllegalArgumentException("invalid summary evidence")

    @Test
    fun `soft deferral requires headroom and cannot renew itself`() {
        val recovery = ContextCompactionRecovery(policy)
        assertFalse(recovery.defer(100_000, 100_000))
        assertFalse(recovery.defer(96_000, 100_000))
        assertTrue(recovery.defer(90_000, 100_000))
        assertFalse(recovery.defer(90_000, 100_000))
        recovery.checkpointCommitted()
        assertTrue(recovery.defer(90_000, 100_000))
    }
}
