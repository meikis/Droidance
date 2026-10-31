// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class AutomationCadence {
    DAILY,
    WEEKLY,
    ONCE,
    INTERVAL
}

@Serializable
enum class AutomationMissedPolicy { RUN_ONCE, SKIP }

@Serializable
data class AutomationSchedule(
    val cadence: AutomationCadence,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<Int> = emptySet(),
    val timeZoneId: String,
    val runAtMs: Long = 0L,
    val intervalMinutes: Long = 0L,
    val endAtMs: Long = 0L,
    val maxOccurrences: Long = 0L,
    val missedPolicy: AutomationMissedPolicy = AutomationMissedPolicy.RUN_ONCE
) {
    init {
        require(hour in 0..23) { "Automation hour must be in 0..23" }
        require(minute in 0..59) { "Automation minute must be in 0..59" }
        require(daysOfWeek.all { it in 1..7 }) { "Automation weekdays must use ISO 1..7" }
        require(cadence != AutomationCadence.WEEKLY || daysOfWeek.isNotEmpty()) {
            "Weekly automation requires at least one weekday"
        }
        require(runAtMs >= 0L && endAtMs >= 0L && maxOccurrences >= 0L)
        require(cadence !in setOf(AutomationCadence.ONCE, AutomationCadence.INTERVAL) || runAtMs > 0L)
        require(cadence != AutomationCadence.INTERVAL || intervalMinutes in 1..5256000L)
        require(endAtMs == 0L || runAtMs == 0L || endAtMs >= runAtMs)
        require(endAtMs == 0L || maxOccurrences == 0L) { "Choose an end time or a trigger limit" }
        require(cadence != AutomationCadence.ONCE || (endAtMs == 0L && maxOccurrences == 0L))
        ZoneId.of(timeZoneId)
    }
}

@Serializable
data class AutomationDefinition(
    val id: String,
    val version: Int,
    val title: String,
    val instruction: String,
    val titleWasGenerated: Boolean,
    val schedule: AutomationSchedule,
    val enabled: Boolean = true,
    val alarmPrecision: AutomationAlarmPrecision? = null,
    val nextRunAtMs: Long = 0L,
    val triggeredCount: Long = 0L,
    val finishedAtMs: Long = 0L,
    val originalRequest: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(id.isNotBlank()) { "Automation id cannot be blank" }
        require(version > 0) { "Automation version must be positive" }
        require(title.isNotBlank()) { "Automation title cannot be blank" }
        require(instruction.isNotBlank()) { "Automation instruction cannot be blank" }
    }
}

@Serializable
enum class AutomationTrigger {
    SCHEDULED,
    MANUAL,
    RECOVERY
}

@Serializable
enum class AutomationExecutionState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    WAITING_FOR_USER,
    UNKNOWN,
    SKIPPED,
    CANCELLED
}

@Serializable
enum class AutomationDeliveryState {
    NOT_REQUESTED,
    PENDING,
    HANDOFF_ACCEPTED,
    FAILED
}

@Serializable
data class AutomationOccurrence(
    val id: String,
    val automationId: String,
    val definitionVersion: Int,
    val scheduledForMs: Long,
    val trigger: AutomationTrigger = AutomationTrigger.SCHEDULED,
    val instructionSnapshot: String = "",
    val titleSnapshot: String = "",
    val coalescedCount: Long = 0L,
    val latestScheduledForMs: Long = 0L,
    val missedPolicySnapshot: AutomationMissedPolicy = AutomationMissedPolicy.RUN_ONCE,
    val executionState: AutomationExecutionState = AutomationExecutionState.QUEUED,
    val deliveryState: AutomationDeliveryState = AutomationDeliveryState.NOT_REQUESTED,
    val threadId: String = AutomationSessionIds.threadId(automationId),
    val turnId: String? = null,
    val resultStatus: String = "",
    val resultSummary: String = "",
    val failureCode: String = "",
    val deliveryFailureCode: String = "",
    val deliveryAttempts: Int = 0,
    val nextDeliveryAttemptAtMs: Long = 0L,
    val queuedAtMs: Long = System.currentTimeMillis(),
    val startedAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class AutomationStateDocument(
    val schemaVersion: Int = 1,
    val definitions: List<AutomationDefinition> = emptyList(),
    val occurrences: List<AutomationOccurrence> = emptyList()
)

object AutomationScheduleCalculator {
    fun nextAfter(schedule: AutomationSchedule, afterEpochMs: Long): Long {
        val zone = ZoneId.of(schedule.timeZoneId)
        val after = Instant.ofEpochMilli(afterEpochMs).atZone(zone)
        val localTime = LocalTime.of(schedule.hour, schedule.minute)
        val next = when (schedule.cadence) {
            AutomationCadence.ONCE -> schedule.runAtMs.takeIf { it > afterEpochMs } ?: 0L
            AutomationCadence.INTERVAL -> {
                if (afterEpochMs < schedule.runAtMs) schedule.runAtMs else {
                    val intervalMs = Math.multiplyExact(schedule.intervalMinutes, 60_000L)
                    val steps = (afterEpochMs - schedule.runAtMs) / intervalMs + 1L
                    Math.addExact(schedule.runAtMs, Math.multiplyExact(steps, intervalMs))
                }
            }
            AutomationCadence.DAILY -> {
                var candidate = after.toLocalDate().atTime(localTime).atZone(zone)
                if (!candidate.isAfter(after)) candidate = after.toLocalDate().plusDays(1).atTime(localTime).atZone(zone)
                candidate.toInstant().toEpochMilli()
            }

            AutomationCadence.WEEKLY -> nextWeekly(after, localTime, schedule.daysOfWeek, zone)
        }
        return next.takeIf { schedule.endAtMs == 0L || it <= schedule.endAtMs } ?: 0L
    }

    /** Counts planned deadlines, including deadlines merged while the device was unavailable. */
    fun dueCount(schedule: AutomationSchedule, firstMs: Long, nowMs: Long): Long {
        val through = if (schedule.endAtMs > 0L) minOf(nowMs, schedule.endAtMs) else nowMs
        if (firstMs <= 0L || firstMs > through) return 0L
        if (schedule.cadence == AutomationCadence.ONCE) return 1L
        if (schedule.cadence == AutomationCadence.INTERVAL) {
            return (through - firstMs) / Math.multiplyExact(schedule.intervalMinutes, 60_000L) + 1L
        }
        val zone = ZoneId.of(schedule.timeZoneId)
        val firstDate = Instant.ofEpochMilli(firstMs).atZone(zone).toLocalDate()
        val throughZoned = Instant.ofEpochMilli(through).atZone(zone)
        var lastDate = throughZoned.toLocalDate()
        if (lastDate.atTime(schedule.hour, schedule.minute).atZone(zone).toInstant().toEpochMilli() > through) {
            lastDate = lastDate.minusDays(1)
        }
        val days = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate) + 1L
        if (days <= 0L) return 0L
        if (schedule.cadence == AutomationCadence.DAILY) return days
        return (days / 7L) * schedule.daysOfWeek.size + (0 until (days % 7L).toInt()).count {
            firstDate.plusDays(it.toLong()).dayOfWeek.value in schedule.daysOfWeek
        }
    }

    fun deadlineAt(schedule: AutomationSchedule, firstMs: Long, index: Long): Long {
        require(index >= 0L)
        if (schedule.cadence == AutomationCadence.ONCE) return firstMs
        if (schedule.cadence == AutomationCadence.INTERVAL) return Math.addExact(firstMs,
            Math.multiplyExact(index, Math.multiplyExact(schedule.intervalMinutes, 60_000L)))
        val zone = ZoneId.of(schedule.timeZoneId)
        val first = Instant.ofEpochMilli(firstMs).atZone(zone).toLocalDate()
        val offset = if (schedule.cadence == AutomationCadence.DAILY) index else {
            val offsets = schedule.daysOfWeek.map { (it - first.dayOfWeek.value + 7) % 7 }.sorted()
            (index / offsets.size) * 7L + offsets[(index % offsets.size).toInt()]
        }
        return first.plusDays(offset).atTime(schedule.hour, schedule.minute).atZone(zone).toInstant().toEpochMilli()
    }

    private fun nextWeekly(
        after: ZonedDateTime,
        localTime: LocalTime,
        daysOfWeek: Set<Int>,
        zone: ZoneId
    ): Long {
        for (offset in 0..7) {
            val date = after.toLocalDate().plusDays(offset.toLong())
            if (DayOfWeek.from(date).value !in daysOfWeek) continue
            val candidate = date.atTime(localTime).atZone(zone)
            if (candidate.isAfter(after)) return candidate.toInstant().toEpochMilli()
        }
        error("Unable to calculate the next weekly automation occurrence")
    }
}

object AutomationOccurrenceIds {
    fun scheduled(automationId: String, definitionVersion: Int, scheduledForMs: Long): String =
        stable("scheduled:$automationId:$definitionVersion:$scheduledForMs")

    fun manual(automationId: String, definitionVersion: Int, requestedAtMs: Long): String =
        stable("manual:$automationId:$definitionVersion:$requestedAtMs:${UUID.randomUUID()}")

    private fun stable(value: String): String = UUID.nameUUIDFromBytes(
        value.toByteArray(StandardCharsets.UTF_8)
    ).toString()
}

object AutomationSessionIds {
    fun threadId(automationId: String): String = UUID.nameUUIDFromBytes(
        "automation:${automationId.trim().lowercase()}".toByteArray(StandardCharsets.UTF_8)
    ).toString()
}

object AutomationOccurrencePolicy {
    fun deliveryFailed(
        occurrence: AutomationOccurrence,
        reasonCode: String,
        attemptedAtMs: Long,
        nextAttemptAtMs: Long = 0L
    ): AutomationOccurrence = occurrence.copy(
        deliveryState = AutomationDeliveryState.FAILED,
        deliveryFailureCode = reasonCode,
        deliveryAttempts = occurrence.deliveryAttempts + 1,
        nextDeliveryAttemptAtMs = nextAttemptAtMs,
        updatedAtMs = attemptedAtMs
    )
}
