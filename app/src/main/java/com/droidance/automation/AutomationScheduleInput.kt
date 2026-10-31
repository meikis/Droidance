// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import java.time.Instant
import kotlinx.serialization.json.*

/** Shared protocol for model drafts and the Agent tool; relative offsets use a host-owned clock. */
object AutomationScheduleInput {
    fun parse(value: JsonObject, referenceTimeMs: Long): AutomationSchedule {
        fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull
        fun number(key: String, default: Long = 0L): Long = value[key]?.let {
            if (it is JsonNull) default else requireNotNull(it.jsonPrimitive.longOrNull) { "INVALID_$key" }
        } ?: default
        val cadence = AutomationCadence.valueOf(requireNotNull(text("cadence")).uppercase())
        val absolute = text("run_at")?.let { Instant.parse(it).toEpochMilli() }
        val delay = value["delay_minutes"]?.takeUnless { it is JsonNull }?.let {
            requireNotNull(it.jsonPrimitive.longOrNull) { "INVALID_DELAY" }
        }
        require(absolute == null || delay == null) { "CONFLICTING_START_TIME" }
        require(delay == null || delay >= 0L) { "INVALID_DELAY" }
        require(cadence in setOf(AutomationCadence.ONCE, AutomationCadence.INTERVAL) || (absolute == null && delay == null)) {
            "START_TIME_REQUIRES_ONCE_OR_INTERVAL"
        }
        require(cadence == AutomationCadence.INTERVAL || number("interval_minutes") == 0L) { "INTERVAL_REQUIRES_INTERVAL_CADENCE" }
        val runAt = absolute ?: delay?.let { Math.addExact(referenceTimeMs, Math.multiplyExact(it, 60_000L)) } ?: 0L
        return AutomationSchedule(
            cadence = cadence,
            hour = if (cadence in setOf(AutomationCadence.DAILY, AutomationCadence.WEEKLY))
                requireNotNull(value["hour"]?.jsonPrimitive?.intOrNull) else 0,
            minute = if (cadence in setOf(AutomationCadence.DAILY, AutomationCadence.WEEKLY))
                requireNotNull(value["minute"]?.jsonPrimitive?.intOrNull) else 0,
            daysOfWeek = value["days_of_week"]?.takeUnless { it is JsonNull }?.jsonArray
                ?.map { requireNotNull(it.jsonPrimitive.intOrNull) }?.toSet().orEmpty(),
            timeZoneId = requireNotNull(text("time_zone")),
            runAtMs = runAt,
            intervalMinutes = number("interval_minutes"),
            endAtMs = text("end_at")?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
            maxOccurrences = number("max_occurrences"),
            missedPolicy = text("missed_policy")?.let { AutomationMissedPolicy.valueOf(it.uppercase()) }
                ?: AutomationMissedPolicy.RUN_ONCE
        )
    }
}
