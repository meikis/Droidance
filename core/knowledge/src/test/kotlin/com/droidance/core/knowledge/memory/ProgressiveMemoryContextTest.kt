// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import com.droidance.core.agent.event.MemoryContextItemPayload
import com.droidance.core.agent.event.MemoryContextLoadedEventPayload
import com.droidance.core.agent.event.MemoryContextScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveMemoryContextTest {
    @Test fun recentPreferenceEntriesDoNotCrowdOutOtherCoreCategories() {
        val entries = listOf(
            ProgressiveMemoryEntry("profile:language", ProgressiveMemoryScope.PROFILE, "language", "Use Chinese", "2020-01-01"),
            ProgressiveMemoryEntry("profile:address", ProgressiveMemoryScope.PROFILE, "communication", "Call me Alex", "2020-01-01")
        ) + (1..12).map { ProgressiveMemoryEntry("profile:preference-$it", ProgressiveMemoryScope.PROFILE,
            "preference", "Preference $it", "2026-10-${it.toString().padStart(2, '0')}") }
        fun select(rows: List<ProgressiveMemoryEntry>) = ProgressiveMemorySelector.select(rows, "unrelated",
            setOf("language", "communication", "preference"), 4, 1, 120)
        val selected = select(entries)
        assertEquals(selected, select(entries.reversed()))
        assertTrue(selected.any { it.ref == "profile:language" })
        assertTrue(selected.any { it.ref == "profile:address" })
        assertTrue(selected.any { it.ref == "profile:preference-12" })
        assertEquals(4, selected.size)
    }

    @Test
    fun currentExactKeySurvivesRepeatedHistoricalGoalsInABoundedIndex() {
        val key = "morning_routine_preference"
        val query = "不操作手机，不修改任何记忆。请告诉我长期记忆里${key}对应的当前测试偏好；另外我没有告诉过你我的临时测试幸运数字，请明确说明不知道，不要猜。"
        val old = (1..8).map { index ->
            ProgressiveMemoryEntry("episode:$index", ProgressiveMemoryScope.EPISODE, "task",
                "$query 过去记录：晨练先拉伸七分钟", updatedAt = "2026-09-29")
        }
        val current = ProgressiveMemoryEntry("long_term:$key", ProgressiveMemoryScope.LONG_TERM,
            "preference", "晨练先拉伸十一分钟", updatedAt = "2026-09-28")
        val selected = ProgressiveMemorySelector.select(old + current, query, emptySet(), 1, 6, 120)
        assertEquals(6, selected.size)
        assertEquals(current.ref, selected.first().ref)
        assertEquals(MemoryContextScope.LONG_TERM, selected.first().scope)
        assertEquals("晨练先拉伸十一分钟", selected.first().summary)
    }

    @Test
    fun queryWithoutSearchableTermsDoesNotLoadUnrelatedMemory() {
        val entries = listOf(
            ProgressiveMemoryEntry("profile:language", ProgressiveMemoryScope.PROFILE, "language", "Reply in English"),
            ProgressiveMemoryEntry("profile:trip", ProgressiveMemoryScope.PROFILE, "general", "Previous trip details"),
            ProgressiveMemoryEntry("long_term:note", ProgressiveMemoryScope.LONG_TERM, "general", "Unrelated saved note"),
            ProgressiveMemoryEntry("episode:past", ProgressiveMemoryScope.EPISODE, "task", "Unrelated past task")
        )

        for (query in listOf("", "   ", "好", "👍", "?", "a")) {
            val selected = ProgressiveMemorySelector.select(
                entries = entries,
                query = query,
                coreProfileCategories = setOf("language"),
                coreProfileMaxItems = 4,
                relatedMemoryMaxItems = 6,
                summaryMaxChars = 120
            )

            assertEquals("query=$query", listOf("profile:language"), selected.map { it.ref })
            assertEquals(MemoryContextScope.CORE_PROFILE, selected.single().scope)
        }
    }

    @Test
    fun queryWithoutSearchableTermsAndWithoutCoreProfileProducesNoMemoryPrompt() {
        val selected = ProgressiveMemorySelector.select(
            entries = listOf(
                ProgressiveMemoryEntry("long_term:note", ProgressiveMemoryScope.LONG_TERM, "general", "Unrelated saved note")
            ),
            query = "👍",
            coreProfileCategories = emptySet(),
            coreProfileMaxItems = 4,
            relatedMemoryMaxItems = 6,
            summaryMaxChars = 120
        )
        val payload = MemoryContextLoadedEventPayload(
            contractVersion = 1,
            queryHash = "a".repeat(64),
            items = selected
        )

        assertTrue(selected.isEmpty())
        assertEquals("", MemoryContextPromptRenderer.render(payload, maxChars = 900))
    }

    @Test
    fun coreProfileStaysLoadedWhileOnlyRelevantGrowingMemoryEntersTheTurnIndex() {
        val profile = """
            # User Profile

            - [memory:key=preferred_address][category=communication][updated=2026-08-25] 称呼用户为 Alex
            - [memory:key=language][category=language][updated=2026-08-25] 使用简体中文回复
            - [memory:key=temporary_trip][category=general][updated=2026-08-20] 上周去过大阪
        """.trimIndent()
        val longTerm = buildString {
            appendLine("# Memory")
            repeat(30) { index ->
                appendLine("- [memory:key=unrelated_$index][category=general][updated=2026-08-01] 无关历史条目 $index")
            }
            appendLine("- [memory:key=jd_delivery][category=task][updated=2026-08-24] 京东订单物流查询曾经需要进入订单详情页验证")
        }
        val entries = DurableMemoryDocument.contextEntries(profile, ProgressiveMemoryScope.PROFILE) +
            DurableMemoryDocument.contextEntries(longTerm, ProgressiveMemoryScope.LONG_TERM)

        val selected = ProgressiveMemorySelector.select(
            entries = entries,
            query = "继续查询京东物流",
            coreProfileCategories = setOf("communication", "language"),
            coreProfileMaxItems = 4,
            relatedMemoryMaxItems = 3,
            summaryMaxChars = 120
        )

        assertTrue(selected.any { it.ref == "profile:preferred_address" })
        assertTrue(selected.any { it.ref == "profile:language" })
        assertTrue(selected.any { it.ref == "long_term:jd_delivery" })
        assertFalse(selected.any { it.ref == "profile:temporary_trip" })
        assertFalse(selected.any { it.ref == "long_term:unrelated_0" })
    }

    @Test
    fun renderedTurnIndexIsBoundedAndContainsReferencesInsteadOfFullHistory() {
        val payload = MemoryContextLoadedEventPayload(
            contractVersion = 1,
            queryHash = "a".repeat(64),
            items = (1..20).map { index ->
                MemoryContextItemPayload(
                    ref = "episode:${index.toString().padStart(64, 'a')}",
                    scope = MemoryContextScope.EPISODE,
                    summary = "历史任务 $index " + "细节 ".repeat(40),
                    sourceThroughSequence = index.toLong()
                )
            }
        )

        val prompt = MemoryContextPromptRenderer.render(payload, maxChars = 900)

        assertTrue(prompt.length <= 900)
        assertTrue(prompt.contains("<memory_index>"))
        assertTrue(prompt.contains("ref=episode:"))
        assertTrue(prompt.endsWith("</memory_index>"))
        assertFalse(prompt.contains("细节 ".repeat(40)))
    }

    @Test
    fun genericInstructionPrefixDoesNotLoadUnrelatedTaskEpisodes() {
        val entries = listOf(
            ProgressiveMemoryEntry(
                ref = "episode:${"a".repeat(64)}",
                scope = ProgressiveMemoryScope.EPISODE,
                category = "task",
                content = "帮我在淘宝搜索最新的苹果手机"
            ),
            ProgressiveMemoryEntry(
                ref = "episode:${"b".repeat(64)}",
                scope = ProgressiveMemoryScope.EPISODE,
                category = "task",
                content = "帮我在京东查询手机价格"
            )
        )

        val selected = ProgressiveMemorySelector.select(
            entries = entries,
            query = "帮我在美团上点一个猪脚饭送过来",
            coreProfileCategories = emptySet(),
            coreProfileMaxItems = 1,
            relatedMemoryMaxItems = 6,
            summaryMaxChars = 120
        )

        assertTrue(selected.isEmpty())
    }

}
