// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationScheduleCalculatorTest {
    @Test
    fun dailyScheduleKeepsLocalWallClockAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.DAILY,
            hour = 8,
            minute = 0,
            timeZoneId = zone.id
        )
        val beforeDst = ZonedDateTime.of(2026, 3, 7, 7, 0, 0, 0, zone).toInstant()

        val first = AutomationScheduleCalculator.nextAfter(schedule, beforeDst.toEpochMilli())
        val second = AutomationScheduleCalculator.nextAfter(schedule, first)

        assertEquals(8, Instant.ofEpochMilli(first).atZone(zone).hour)
        assertEquals(8, Instant.ofEpochMilli(second).atZone(zone).hour)
        assertEquals(23L, (second - first) / 3_600_000L)
    }

    @Test
    fun weeklyScheduleChoosesTheNextSelectedDayAfterTheReferenceInstant() {
        val zone = ZoneId.of("Asia/Tokyo")
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.WEEKLY,
            hour = 7,
            minute = 30,
            daysOfWeek = setOf(1, 5),
            timeZoneId = zone.id
        )
        val mondayAfterRun = ZonedDateTime.of(2026, 8, 24, 8, 0, 0, 0, zone).toInstant()

        val next = Instant.ofEpochMilli(
            AutomationScheduleCalculator.nextAfter(schedule, mondayAfterRun.toEpochMilli())
        ).atZone(zone)

        assertEquals(5, next.dayOfWeek.value)
        assertEquals(7, next.hour)
        assertEquals(30, next.minute)
    }

    @Test
    fun nextOccurrenceIsAlwaysStrictlyAfterTheReferenceInstant() {
        val zone = ZoneId.of("UTC")
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.DAILY,
            hour = 8,
            minute = 0,
            timeZoneId = zone.id
        )
        val exactlyAtRun = ZonedDateTime.of(2026, 8, 23, 8, 0, 0, 0, zone).toInstant()

        val next = AutomationScheduleCalculator.nextAfter(schedule, exactlyAtRun.toEpochMilli())

        assertTrue(next > exactlyAtRun.toEpochMilli())
        assertEquals(24L, (next - exactlyAtRun.toEpochMilli()) / 3_600_000L)
    }

    @Test
    fun intervalScheduleIncludesTheFirstDeadlineAndStopsAtItsInclusiveEnd() {
        val first = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli()
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.INTERVAL,
            hour = 0,
            minute = 0,
            timeZoneId = "UTC",
            runAtMs = first,
            intervalMinutes = 15,
            endAtMs = first + 30 * 60_000L
        )

        assertEquals(first, AutomationScheduleCalculator.nextAfter(schedule, first - 1L))
        assertEquals(first + 15 * 60_000L, AutomationScheduleCalculator.nextAfter(schedule, first))
        assertEquals(3L, AutomationScheduleCalculator.dueCount(schedule, first, first + 60 * 60_000L))
        assertEquals(first + 30 * 60_000L, AutomationScheduleCalculator.deadlineAt(schedule, first, 2L))
        assertEquals(0L, AutomationScheduleCalculator.nextAfter(schedule, first + 60 * 60_000L))
    }

    @Test
    fun dailyDueCountAndDeadlinesFollowTheLocalClockAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.DAILY,
            hour = 8,
            minute = 0,
            timeZoneId = zone.id
        )
        val first = ZonedDateTime.of(2026, 3, 6, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val through = ZonedDateTime.of(2026, 3, 9, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val second = AutomationScheduleCalculator.deadlineAt(schedule, first, 1L)
        val third = AutomationScheduleCalculator.deadlineAt(schedule, first, 2L)

        assertEquals(4L, AutomationScheduleCalculator.dueCount(schedule, first, through))
        assertEquals(23L, (third - second) / 3_600_000L)
        assertEquals(8, Instant.ofEpochMilli(third).atZone(zone).hour)
        assertEquals(8, Instant.ofEpochMilli(
            AutomationScheduleCalculator.deadlineAt(schedule, first, 3L)
        ).atZone(zone).hour)
    }

    @Test
    fun weeklyDueCountAndDeadlineAtRespectSelectedIsoWeekdays() {
        val zone = ZoneId.of("Asia/Tokyo")
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.WEEKLY,
            hour = 7,
            minute = 30,
            daysOfWeek = setOf(1, 5),
            timeZoneId = zone.id
        )
        val first = ZonedDateTime.of(2026, 8, 24, 7, 30, 0, 0, zone).toInstant().toEpochMilli()
        val through = ZonedDateTime.of(2026, 8, 31, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val second = AutomationScheduleCalculator.deadlineAt(schedule, first, 1L)
        val third = AutomationScheduleCalculator.deadlineAt(schedule, first, 2L)

        assertEquals(3L, AutomationScheduleCalculator.dueCount(schedule, first, through))
        assertEquals(5, Instant.ofEpochMilli(second).atZone(zone).dayOfWeek.value)
        assertEquals(1, Instant.ofEpochMilli(third).atZone(zone).dayOfWeek.value)
        assertTrue(third < through)
    }

    @Test
    fun oneShotHasOneDueDeadlineAndNoLaterOccurrence() {
        val runAt = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli()
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.ONCE,
            hour = 0,
            minute = 0,
            timeZoneId = "UTC",
            runAtMs = runAt
        )

        assertEquals(0L, AutomationScheduleCalculator.dueCount(schedule, runAt, runAt - 1L))
        assertEquals(1L, AutomationScheduleCalculator.dueCount(schedule, runAt, runAt))
        assertEquals(runAt, AutomationScheduleCalculator.deadlineAt(schedule, runAt, 0L))
        assertEquals(0L, AutomationScheduleCalculator.nextAfter(schedule, runAt))
    }
}
