// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationManager @Inject constructor(
    private val store: AutomationStore,
    private val alarmScheduler: AutomationAlarmScheduler,
    private val runtimeClient: dagger.Lazy<com.droidance.runtime.RuntimeProcessClient>?
) {
    private val client get() = runtimeClient?.get()?.takeUnless { it.isOwner }
    private fun remote(operation: String, arguments: android.os.Bundle = android.os.Bundle()): android.os.Bundle =
        requireNotNull(client).command("automationAdmin", android.os.Bundle(arguments).apply { putString("operation", operation) })

    @Synchronized
    fun create(
        title: String,
        instruction: String,
        titleWasGenerated: Boolean,
        schedule: AutomationSchedule,
        nowMs: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
        originalRequest: String = ""
    ): AutomationDefinition {
        client?.let { return kotlinx.serialization.json.Json.decodeFromString(remote("create", android.os.Bundle().apply {
            putString("title", title); putString("instruction", instruction); putBoolean("generated", titleWasGenerated)
            putString("schedule", kotlinx.serialization.json.Json.encodeToString(AutomationSchedule.serializer(), schedule))
            putLong("now", nowMs); putString("id", id); putString("original", originalRequest)
        }).getString("result")!!) }
        val normalizedSchedule = schedule.copy(timeZoneId = ZoneId.of(schedule.timeZoneId).id)
        store.definition(id)?.let { current ->
            check(current.title == title.trim() && current.instruction == instruction.trim() &&
                current.schedule == normalizedSchedule) { "AUTOMATION_REQUEST_CONFLICT" }
            return scheduleAndRecord(current)
        }
        val first = initialDeadline(normalizedSchedule, nowMs, includeFirstInterval = true)
        val definition = AutomationDefinition(
            id = id,
            version = 1,
            title = title.trim(),
            instruction = instruction.trim(),
            titleWasGenerated = titleWasGenerated,
            schedule = normalizedSchedule,
            nextRunAtMs = first,
            originalRequest = originalRequest,
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        )
        store.upsertDefinition(definition)
        return scheduleAndRecord(definition)
    }

    @Synchronized
    fun update(
        id: String,
        title: String,
        instruction: String,
        schedule: AutomationSchedule,
        nowMs: Long = System.currentTimeMillis(),
        originalRequest: String? = null
    ): AutomationDefinition? {
        client?.let { return kotlinx.serialization.json.Json.decodeFromString(remote("update", android.os.Bundle().apply {
            putString("title", title); putString("instruction", instruction); putString("id", id)
            putString("schedule", kotlinx.serialization.json.Json.encodeToString(AutomationSchedule.serializer(), schedule))
            putLong("now", nowMs); putString("original", originalRequest)
        }).getString("result")!!) }
        val current = store.definition(id) ?: return null
        val normalizedSchedule = schedule.copy(timeZoneId = ZoneId.of(schedule.timeZoneId).id)
        val changedSchedule = normalizedSchedule != current.schedule
        val rearm = current.finishedAtMs > 0L
        val first = initialDeadline(normalizedSchedule, nowMs, includeFirstInterval = changedSchedule)
        val updated = current.copy(
            enabled = current.enabled || rearm,
            finishedAtMs = 0L,
            triggeredCount = if (changedSchedule || rearm) 0L else current.triggeredCount,
            originalRequest = originalRequest ?: current.originalRequest,
            version = current.version + 1,
            title = title.trim(),
            instruction = instruction.trim(),
            titleWasGenerated = false,
            schedule = normalizedSchedule,
            nextRunAtMs = if (current.enabled || rearm) {
                if (!changedSchedule && current.nextRunAtMs > 0L) current.nextRunAtMs else first
            } else {
                0L
            },
            updatedAtMs = nowMs
        )
        store.upsertDefinition(updated)
        return scheduleAndRecord(updated)
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean, nowMs: Long = System.currentTimeMillis()) {
        client?.let { remote("enable", android.os.Bundle().apply { putString("id", id); putBoolean("enabled", enabled); putLong("now", nowMs) }); return }
        val current = store.definition(id) ?: return
        if (current.enabled && enabled) return
        require(!enabled || current.finishedAtMs == 0L) { "SCHEDULE_FINISHED" }
        val updated = current.copy(
            version = current.version + 1,
            enabled = enabled,
            nextRunAtMs = if (enabled) {
                initialDeadline(current.schedule, nowMs)
            } else {
                0L
            },
            updatedAtMs = nowMs
        )
        store.upsertDefinition(updated)
        scheduleAndRecord(updated)
    }

    @Synchronized
    fun delete(id: String) {
        client?.let { remote("delete", android.os.Bundle().apply { putString("id", id) }); return }
        alarmScheduler.cancel(id)
        store.deleteDefinition(id)
    }

    @Synchronized
    fun runNow(id: String, nowMs: Long = System.currentTimeMillis()): AutomationOccurrence? {
        client?.let { return kotlinx.serialization.json.Json.decodeFromString(remote("run", android.os.Bundle().apply { putString("id", id); putLong("now", nowMs) }).getString("result")!!) }
        val definition = store.definition(id) ?: return null
        return store.enqueueManualOccurrence(definition, nowMs)
    }

    @Synchronized
    fun retryDelivery(occurrenceId: String, nowMs: Long = System.currentTimeMillis()) {
        client?.let { remote("retry", android.os.Bundle().apply { putString("id", occurrenceId); putLong("now", nowMs) }); return }
        store.retryDelivery(occurrenceId, nowMs)
    }

    @Synchronized
    fun onAlarm(
        automationId: String,
        definitionVersion: Int,
        scheduledForMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        client?.let { return remote("alarm", android.os.Bundle().apply {
            putString("id", automationId); putInt("version", definitionVersion); putLong("scheduled", scheduledForMs); putLong("now", nowMs)
        }).getBoolean("result") }
        val enqueued = store.consumeDeadline(automationId, definitionVersion, scheduledForMs, nowMs,
            AutomationTrigger.SCHEDULED)
        store.definition(automationId)?.let(::scheduleAndRecord)
        return enqueued
    }

    @Synchronized
    fun dispatchDue(nowMs: Long = System.currentTimeMillis()): Int {
        client?.let { return remote("dispatch", android.os.Bundle().apply { putLong("now", nowMs) }).getInt("result") }
        var count = 0
        store.definitions.value.filter { it.enabled && it.nextRunAtMs in 1..nowMs }.forEach {
            if (onAlarm(it.id, it.version, it.nextRunAtMs, nowMs)) count++
        }
        return count
    }

    @Synchronized
    fun restoreSchedules(nowMs: Long = System.currentTimeMillis()): Int {
        client?.let { return remote("restore", android.os.Bundle().apply { putLong("now", nowMs) }).getInt("result") }
        var recovered = 0
        store.definitions.value.forEach { definition ->
            if (!definition.enabled || definition.finishedAtMs > 0L) {
                alarmScheduler.cancel(definition.id)
            } else if (definition.nextRunAtMs in 1..nowMs) {
                if (store.consumeDeadline(definition.id, definition.version, definition.nextRunAtMs,
                        nowMs, AutomationTrigger.RECOVERY)) recovered++
                store.definition(definition.id)?.let(::scheduleAndRecord)
            } else {
                val next = definition.nextRunAtMs.takeIf { it > 0L }
                    ?: AutomationScheduleCalculator.nextAfter(definition.schedule, nowMs)
                val restored = definition.copy(nextRunAtMs = next, enabled = next > 0L,
                    finishedAtMs = if (next == 0L) nowMs else 0L, updatedAtMs = nowMs)
                store.updateDefinition(definition.id) { restored }
                scheduleAndRecord(restored)
            }
        }
        return recovered
    }

    private fun initialDeadline(schedule: AutomationSchedule, nowMs: Long, includeFirstInterval: Boolean = false): Long {
        require(schedule.cadence != AutomationCadence.ONCE || schedule.runAtMs > nowMs) { "SCHEDULE_TIME_PASSED" }
        val next = if (includeFirstInterval && schedule.cadence == AutomationCadence.INTERVAL) schedule.runAtMs else
            AutomationScheduleCalculator.nextAfter(schedule, nowMs)
        require(schedule.endAtMs == 0L || schedule.endAtMs > nowMs) { "SCHEDULE_TIME_PASSED" }
        require(next > 0L) { "SCHEDULE_TIME_PASSED" }
        return next
    }

    private fun scheduleAndRecord(definition: AutomationDefinition): AutomationDefinition {
        val precision = if (definition.enabled) alarmScheduler.schedule(definition) else {
            alarmScheduler.cancel(definition.id)
            null
        }
        val scheduled = definition.copy(alarmPrecision = precision)
        store.updateDefinition(definition.id) { current ->
            if (current.version == definition.version && current.nextRunAtMs == definition.nextRunAtMs) scheduled else current
        }
        return scheduled
    }

    fun alarmPrecision(): AutomationAlarmPrecision = alarmScheduler.precision()
}
