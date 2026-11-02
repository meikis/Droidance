// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.model.llm.ModelDiagnosticScope
import com.droidance.core.model.llm.ModelRequestUsage
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ConversationTokenUsagePersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "token-usage-test-${UUID.randomUUID()}"
    private val isolatedContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    }

    @After fun cleanUp() { context.deleteSharedPreferences(preferenceName) }

    @Test fun retriesCompactionAndUnscopedCallsPersistWithoutCrossConversationLeakage() {
        val repository = TokenUsageRepository(isolatedContext)
        val scope = ModelDiagnosticScope("thread-a", "turn-a", "round-a")
        repository.recordRequest(ModelRequestUsage("p", "m", 10_000, 2_000, 8_000, scope))
        repository.recordRequest(ModelRequestUsage("p", "m", scope = scope))
        repository.recordRequest(ModelRequestUsage("p", "m", 1_000, 200, scope = scope.copy(roundId = "compaction")))
        repository.recordRequest(ModelRequestUsage("p", "m", 900, 100))
        repository.recordRequest(ModelRequestUsage("p", "m", 100, 20, scope = scope.copy(threadId = "thread-b")))
        val reloaded = TokenUsageRepository(isolatedContext)
        val usage = reloaded.turnRecords.value.single { it.threadId == "thread-a" }.totals
        assertEquals(13_200L, usage.totalTokens)
        assertEquals(3L, usage.calls)
        assertFalse(usage.isComplete)
        assertEquals(repository.turnRecords.value.associateBy { it.threadId }, reloaded.turnRecords.value.associateBy { it.threadId })
        assertEquals(5L, reloaded.records.value.single().calls)
        assertEquals(12_000L, reloaded.records.value.single().promptTokens)
    }

    @Test fun deletingHistoryRemovesOnlyItsAttributionAndPreservesDailyStatistics() {
        val repository = TokenUsageRepository(isolatedContext)
        listOf("a", "b").forEach { thread ->
            repository.recordRequest(ModelRequestUsage("p", "m", 100, 20,
                scope = ModelDiagnosticScope(thread, "turn", "round")))
        }
        val daily = repository.records.value
        repository.deleteConversationUsage(setOf("a"))
        assertEquals(listOf("b"), TokenUsageRepository(isolatedContext).turnRecords.value.map { it.threadId })
        assertEquals(daily, repository.records.value)
        repository.deleteConversationUsage()
        assertTrue(TokenUsageRepository(isolatedContext).turnRecords.value.isEmpty())
        assertEquals(daily, TokenUsageRepository(isolatedContext).records.value)
    }
}
