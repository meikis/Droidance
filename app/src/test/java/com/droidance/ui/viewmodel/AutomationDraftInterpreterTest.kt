// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.automation.AutomationCadence
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class AutomationDraftInterpreterTest {
    @Test
    fun parsesGeneratedTitleAndCalendarSchedule() {
        val draft = AutomationDraftInterpreter.parse(
            """
            {
              "title": "领取每日权益",
              "instruction": "打开应用并领取每日权益",
              "cadence": "daily",
              "hour": 8,
              "minute": 0,
              "daysOfWeek": [],
              "time_zone": "Asia/Tokyo"
            }
            """.trimIndent(),
            instruction = "每天领取权益",
            referenceTimeMs = 1_800_000_000_000L
        )

        val parsed = requireNotNull(draft)
        val schedule = requireNotNull(parsed.schedule)
        assertEquals("领取每日权益", parsed.title)
        assertEquals("打开应用并领取每日权益", parsed.instruction)
        assertEquals(AutomationCadence.DAILY, schedule.cadence)
        assertEquals(8, schedule.hour)
        assertEquals("Asia/Tokyo", schedule.timeZoneId)
    }

    @Test
    fun parsesOneShotDelayFromTheReferenceTime() {
        val referenceTimeMs = 1_800_000_000_000L
        val draft = AutomationDraftInterpreter.parse(
            """{
              "title": "稍后检查订单",
              "instruction": "打开订单并检查最新状态",
              "cadence": "once",
              "delay_minutes": 5,
              "time_zone": "UTC"
            }""",
            instruction = "五分钟后检查订单",
            referenceTimeMs = referenceTimeMs
        )

        val schedule = requireNotNull(draft).schedule
        assertNotNull(schedule)
        assertEquals(AutomationCadence.ONCE, schedule?.cadence)
        assertEquals(referenceTimeMs + 5 * 60_000L, schedule?.runAtMs)
    }

    @Test
    fun preservesIncompletePlanAsClarificationDraft() {
        val draft = AutomationDraftInterpreter.parse(
            """{
              "title": "检查订单",
              "instruction": "打开订单并检查最新状态",
              "cadence": null,
              "clarification": "请告诉我什么时候执行，以及是否需要重复。"
            }""",
            instruction = "帮我检查订单",
            referenceTimeMs = 1_800_000_000_000L
        )

        val parsed = requireNotNull(draft)
        assertEquals("打开订单并检查最新状态", parsed.instruction)
        assertNull(parsed.schedule)
        assertEquals("请告诉我什么时候执行，以及是否需要重复。", parsed.clarification)
        assertEquals("帮我检查订单", parsed.originalRequest)
    }

    @Test
    fun preservesTimeOnlyRequestWhenTheModelAsksForTheMissingWork() {
        val draft = AutomationDraftInterpreter.parse(
            """{
              "title": "每日提醒",
              "instruction": "",
              "cadence": null,
              "clarification": "请告诉我每天需要执行什么操作。"
            }""",
            instruction = "每天提醒我",
            referenceTimeMs = 1_800_000_000_000L
        )

        val parsed = requireNotNull(draft)
        assertEquals("", parsed.instruction)
        assertNull(parsed.schedule)
        assertEquals("请告诉我每天需要执行什么操作。", parsed.clarification)
        assertEquals("每天提醒我", parsed.originalRequest)
    }

    @Test
    fun rejectsMissingInstructionOrClarification() {
        assertNull(AutomationDraftInterpreter.parse("""{"title":"Untimed","cadence":null}"""))
        assertNull(
            AutomationDraftInterpreter.parse(
                """{"title":"Need time","instruction":"检查订单","cadence":null}"""
            )
        )
        assertNull(
            AutomationDraftInterpreter.parse(
                """{"title":"Interval","instruction":"检查订单","cadence":"hourly","time_zone":"UTC"}"""
            )
        )
    }

    @Test
    fun usesTheCanonicalFrozenStreamingRequestPath() {
        val source = projectFile(
            "app/src/main/java/com/droidance/ui/viewmodel/AutomationDraftInterpreter.kt"
        ).readText()

        assertTrue(source.contains("val plan = modelClient.planStreamChat("))
        assertTrue(source.contains("requestPlan = plan"))
        assertTrue(source.contains("tools = emptyList()"))
        assertFalse(source.contains("llmRouter.chat("))
        assertFalse(source.contains("AgentRuntime"))
    }

    private fun projectFile(path: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $path")
}
