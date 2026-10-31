// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationScheduleInputTest {
    @Test
    fun delayUsesTheHostReferenceTimeForOneShotPlans() {
        val referenceTimeMs = 1_800_000_000_000L
        val schedule = AutomationScheduleInput.parse(
            Json.parseToJsonElement(
                """{
                    "cadence":"ONCE",
                    "delay_minutes":5,
                    "time_zone":"Asia/Shanghai"
                }"""
            ).jsonObject,
            referenceTimeMs
        )

        assertEquals(AutomationCadence.ONCE, schedule.cadence)
        assertEquals(referenceTimeMs + 5 * 60_000L, schedule.runAtMs)
        assertEquals("Asia/Shanghai", schedule.timeZoneId)
    }

    @Test
    fun absoluteIntervalInputPreservesSnakeCaseFieldsAndEndConditions() {
        val schedule = AutomationScheduleInput.parse(
            Json.parseToJsonElement(
                """{
                    "cadence":"INTERVAL",
                    "run_at":"2026-09-21T15:00:00+08:00",
                    "interval_minutes":5,
                    "end_at":"2026-09-21T16:00:00+08:00",
                    "missed_policy":"SKIP",
                    "time_zone":"Asia/Shanghai"
                }"""
            ).jsonObject,
            referenceTimeMs = 1_800_000_000_000L
        )

        assertEquals(AutomationCadence.INTERVAL, schedule.cadence)
        assertEquals(5L, schedule.intervalMinutes)
        assertEquals(0L, schedule.maxOccurrences)
        assertEquals(AutomationMissedPolicy.SKIP, schedule.missedPolicy)
        assertEquals(Instant.parse("2026-09-21T07:00:00Z").toEpochMilli(), schedule.runAtMs)
        assertEquals(Instant.parse("2026-09-21T08:00:00Z").toEpochMilli(), schedule.endAtMs)

        val countLimited = AutomationScheduleInput.parse(
            Json.parseToJsonElement(
                """{
                    "cadence":"INTERVAL",
                    "run_at":"2026-09-21T15:00:00+08:00",
                    "interval_minutes":5,
                    "max_occurrences":12,
                    "time_zone":"Asia/Shanghai"
                }"""
            ).jsonObject,
            referenceTimeMs = 1_800_000_000_000L
        )
        assertEquals(12L, countLimited.maxOccurrences)

        val weekly = AutomationScheduleInput.parse(
            Json.parseToJsonElement(
                """{
                    "cadence":"WEEKLY",
                    "hour":8,
                    "minute":30,
                    "days_of_week":[1,5],
                    "time_zone":"Asia/Shanghai"
                }"""
            ).jsonObject,
            referenceTimeMs = 1_800_000_000_000L
        )
        assertEquals(setOf(1, 5), weekly.daysOfWeek)
        assertEquals(8, weekly.hour)
        assertEquals(30, weekly.minute)
    }

    @Test
    fun zeroDelayIntervalStartsFromTheHostReferenceClock() {
        val referenceTimeMs = 1_800_000_000_000L
        val schedule = AutomationScheduleInput.parse(
            Json.parseToJsonElement(
                """{
                    "cadence":"INTERVAL",
                    "delay_minutes":0,
                    "interval_minutes":30,
                    "time_zone":"UTC"
                }"""
            ).jsonObject,
            referenceTimeMs
        )

        assertEquals(referenceTimeMs, schedule.runAtMs)
        assertEquals(30L, schedule.intervalMinutes)
    }

    @Test
    fun rejectsConflictingStartAndEndConditions() {
        assertThrows(IllegalArgumentException::class.java) {
            AutomationScheduleInput.parse(
                Json.parseToJsonElement(
                    """{
                        "cadence":"ONCE",
                        "run_at":"2026-09-21T15:00:00Z",
                        "delay_minutes":5,
                        "time_zone":"UTC"
                    }"""
                ).jsonObject,
                referenceTimeMs = 1_800_000_000_000L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            AutomationScheduleInput.parse(
                Json.parseToJsonElement(
                    """{
                        "cadence":"INTERVAL",
                        "run_at":"2026-09-21T15:00:00Z",
                        "interval_minutes":5,
                        "end_at":"2026-09-21T14:00:00Z",
                        "time_zone":"UTC"
                    }"""
                ).jsonObject,
                referenceTimeMs = 1_800_000_000_000L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            AutomationScheduleInput.parse(
                Json.parseToJsonElement(
                    """{
                        "cadence":"INTERVAL",
                        "run_at":"2026-09-21T15:00:00Z",
                        "interval_minutes":5,
                        "end_at":"2026-09-21T16:00:00Z",
                        "max_occurrences":4,
                        "time_zone":"UTC"
                    }"""
                ).jsonObject,
                referenceTimeMs = 1_800_000_000_000L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            AutomationScheduleInput.parse(
                Json.parseToJsonElement(
                    """{
                        "cadence":"ONCE",
                        "delay_minutes":"five",
                        "time_zone":"UTC"
                    }"""
                ).jsonObject,
                referenceTimeMs = 1_800_000_000_000L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            AutomationScheduleInput.parse(
                Json.parseToJsonElement(
                    """{
                        "cadence":"DAILY",
                        "run_at":"2026-09-21T15:00:00Z",
                        "hour":8,
                        "minute":0,
                        "time_zone":"UTC"
                    }"""
                ).jsonObject,
                referenceTimeMs = 1_800_000_000_000L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            AutomationScheduleInput.parse(
                Json.parseToJsonElement(
                    """{
                        "cadence":"WEEKLY",
                        "delay_minutes":0,
                        "hour":8,
                        "minute":0,
                        "days_of_week":[1],
                        "time_zone":"UTC"
                    }"""
                ).jsonObject,
                referenceTimeMs = 1_800_000_000_000L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            AutomationScheduleInput.parse(
                Json.parseToJsonElement(
                    """{
                        "cadence":"DAILY",
                        "hour":8,
                        "minute":0,
                        "interval_minutes":5,
                        "time_zone":"UTC"
                    }"""
                ).jsonObject,
                referenceTimeMs = 1_800_000_000_000L
            )
        }
    }
}
