// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.util.Log
import com.droidance.memory.MarkdownFileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable scheduler configuration and operational projection.
 * Agent execution evidence remains in the existing EventStore; this file only owns definitions,
 * idempotency keys, next deadlines, and delivery hand-off state.
 */
@Singleton
class AutomationStore @Inject constructor(
    private val fileManager: MarkdownFileManager,
    private val runtimeClient: dagger.Lazy<com.droidance.runtime.RuntimeProcessClient>?
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }
    private val _definitions = MutableStateFlow<List<AutomationDefinition>>(emptyList())
    private val _occurrences = MutableStateFlow<List<AutomationOccurrence>>(emptyList())
    private val _changeVersion = MutableStateFlow(0L)
    private var definitionsState = emptyList<AutomationDefinition>()
    private var occurrencesState = emptyList<AutomationOccurrence>()

    val definitions: StateFlow<List<AutomationDefinition>> get() =
        runtimeClient?.get()?.takeUnless { it.isOwner }?.automationDefinitions ?: _definitions.asStateFlow()
    val occurrences: StateFlow<List<AutomationOccurrence>> get() =
        runtimeClient?.get()?.takeUnless { it.isOwner }?.automationOccurrences ?: _occurrences.asStateFlow()
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    init {
        load()
    }

    fun definition(id: String): AutomationDefinition? = synchronized(lock) {
        definitionsState.firstOrNull { it.id == id }
    }

    fun upsertDefinition(definition: AutomationDefinition) = synchronized(lock) {
        definitionsState = (definitionsState.filterNot { it.id == definition.id } + definition)
            .sortedBy { it.createdAtMs }
        occurrencesState = occurrencesState.map {
            if (it.automationId == definition.id && it.executionState == AutomationExecutionState.QUEUED) {
                it.copy(executionState = AutomationExecutionState.CANCELLED,
                    failureCode = "PLAN_CHANGED", completedAtMs = definition.updatedAtMs,
                    updatedAtMs = definition.updatedAtMs)
            } else it
        }
        persistLocked()
    }

    fun updateDefinition(id: String, transform: (AutomationDefinition) -> AutomationDefinition) =
        synchronized(lock) {
            val current = definitionsState.firstOrNull { it.id == id } ?: return@synchronized
            val updated = transform(current)
            definitionsState = definitionsState.map { if (it.id == id) updated else it }
            persistLocked()
        }

    fun deleteDefinition(id: String) = synchronized(lock) {
        definitionsState = definitionsState.filterNot { it.id == id }
        occurrencesState = occurrencesState.filter {
            it.automationId != id || it.executionState == AutomationExecutionState.RUNNING ||
                it.deliveryState == AutomationDeliveryState.PENDING ||
                (it.deliveryState == AutomationDeliveryState.FAILED && it.deliveryAttempts < MAX_DELIVERY_ATTEMPTS)
        }
        persistLocked()
    }

    /** Commit occurrence creation and cursor advancement in one durable state write. */
    fun consumeDeadline(
        automationId: String,
        definitionVersion: Int,
        scheduledForMs: Long,
        nowMs: Long,
        trigger: AutomationTrigger
    ): Boolean = synchronized(lock) {
        val definition = definitionsState.firstOrNull { it.id == automationId } ?: return@synchronized false
        if (!definition.enabled || definition.finishedAtMs > 0L || definition.version != definitionVersion ||
            definition.nextRunAtMs != scheduledForMs || scheduledForMs > nowMs || scheduledForMs <= 0L
        ) return@synchronized false
        val schedule = definition.schedule
        val remaining = if (schedule.maxOccurrences == 0L) Long.MAX_VALUE else
            (schedule.maxOccurrences - definition.triggeredCount).coerceAtLeast(0L)
        val dueCount = minOf(AutomationScheduleCalculator.dueCount(schedule, scheduledForMs, nowMs), remaining)
        val latestDeadline = if (dueCount > 0L) AutomationScheduleCalculator.deadlineAt(schedule, scheduledForMs, dueCount - 1L) else scheduledForMs
        val missed = schedule.missedPolicy == AutomationMissedPolicy.SKIP && nowMs - latestDeadline > 60_000L
        val pending = occurrencesState.firstOrNull {
            it.automationId == automationId && it.definitionVersion == definitionVersion &&
                it.executionState == AutomationExecutionState.QUEUED
        }
        var enqueued = false
        if (dueCount > 0L) {
            if (pending != null && !missed) {
                replaceOccurrenceLocked(pending.copy(coalescedCount = pending.coalescedCount + dueCount,
                    latestScheduledForMs = latestDeadline, updatedAtMs = nowMs))
            } else {
                val id = AutomationOccurrenceIds.scheduled(automationId, definitionVersion, scheduledForMs)
                if (occurrencesState.none { it.id == id }) {
                    occurrencesState = occurrencesState + AutomationOccurrence(
                        id = id, automationId = automationId, definitionVersion = definitionVersion,
                        scheduledForMs = scheduledForMs, trigger = trigger,
                        instructionSnapshot = definition.instruction, titleSnapshot = definition.title,
                        coalescedCount = dueCount - 1L, latestScheduledForMs = latestDeadline,
                        missedPolicySnapshot = schedule.missedPolicy,
                        executionState = if (missed) AutomationExecutionState.SKIPPED else AutomationExecutionState.QUEUED,
                        failureCode = if (missed) "DEADLINE_MISSED" else "",
                        queuedAtMs = nowMs, completedAtMs = if (missed) nowMs else 0L, updatedAtMs = nowMs
                    )
                    enqueued = !missed
                }
            }
        }
        val count = definition.triggeredCount + dueCount
        val next = if (schedule.maxOccurrences > 0L && count >= schedule.maxOccurrences) 0L else
            AutomationScheduleCalculator.nextAfter(schedule, nowMs)
        val advanced = definition.copy(nextRunAtMs = next, triggeredCount = count,
            enabled = next > 0L, finishedAtMs = if (next == 0L) nowMs else 0L, updatedAtMs = nowMs)
        definitionsState = definitionsState.map { if (it.id == automationId) advanced else it }
        occurrencesState = trimHistory(occurrencesState)
        persistLocked()
        enqueued
    }

    fun enqueueManualOccurrence(
        definition: AutomationDefinition,
        nowMs: Long = System.currentTimeMillis()
    ): AutomationOccurrence = synchronized(lock) {
        occurrencesState.firstOrNull {
            it.automationId == definition.id && it.executionState in setOf(
                AutomationExecutionState.QUEUED,
                AutomationExecutionState.RUNNING
            )
        }?.let { return@synchronized it }
        val occurrence = AutomationOccurrence(
            id = AutomationOccurrenceIds.manual(definition.id, definition.version, nowMs),
            automationId = definition.id,
            definitionVersion = definition.version,
            scheduledForMs = nowMs,
            trigger = AutomationTrigger.MANUAL,
            instructionSnapshot = definition.instruction,
            titleSnapshot = definition.title,
            queuedAtMs = nowMs,
            updatedAtMs = nowMs
        )
        occurrencesState = trimHistory(occurrencesState + occurrence)
        persistLocked()
        occurrence
    }

    fun claimNextOccurrence(nowMs: Long = System.currentTimeMillis()): AutomationOccurrence? =
        synchronized(lock) {
            var skipped = false
            occurrencesState = occurrencesState.map {
                if (it.executionState == AutomationExecutionState.QUEUED && it.trigger != AutomationTrigger.MANUAL &&
                    it.missedPolicySnapshot == AutomationMissedPolicy.SKIP &&
                    nowMs - maxOf(it.scheduledForMs, it.latestScheduledForMs) > 60_000L) {
                    skipped = true
                    it.copy(executionState = AutomationExecutionState.SKIPPED, failureCode = "DEADLINE_MISSED",
                        completedAtMs = nowMs, updatedAtMs = nowMs)
                } else it
            }
            if (skipped) persistLocked()
            val next = occurrencesState
                .asSequence()
                .filter { it.executionState == AutomationExecutionState.QUEUED }
                .sortedWith(compareBy(AutomationOccurrence::scheduledForMs, AutomationOccurrence::queuedAtMs))
                .firstOrNull() ?: return@synchronized null
            val claimed = next.copy(
                executionState = AutomationExecutionState.RUNNING,
                instructionSnapshot = next.instructionSnapshot.ifBlank { definition(next.automationId)?.instruction.orEmpty() },
                titleSnapshot = next.titleSnapshot.ifBlank { definition(next.automationId)?.title.orEmpty() },
                startedAtMs = nowMs,
                updatedAtMs = nowMs
            )
            replaceOccurrenceLocked(claimed)
            persistLocked()
            claimed
        }

    fun bindTurn(id: String, turnId: String, startedAtMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val occurrence = checkNotNull(occurrencesState.firstOrNull { it.id == id })
        check(occurrence.executionState == AutomationExecutionState.RUNNING)
        check(occurrence.turnId == null || occurrence.turnId == turnId) { "AUTOMATION_TURN_CONFLICT" }
        replaceOccurrenceLocked(occurrence.copy(turnId = turnId, startedAtMs = startedAtMs, updatedAtMs = startedAtMs))
        persistLocked()
    }

    fun completeOccurrence(
        id: String,
        executionState: AutomationExecutionState,
        resultStatus: String,
        resultSummary: String,
        failureCode: String = "",
        completedAtMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val occurrence = occurrencesState.firstOrNull { it.id == id } ?: return@synchronized
        replaceOccurrenceLocked(
            occurrence.copy(
                executionState = executionState,
                deliveryState = AutomationDeliveryState.PENDING,
                resultStatus = resultStatus,
                resultSummary = resultSummary,
                failureCode = failureCode,
                completedAtMs = completedAtMs,
                nextDeliveryAttemptAtMs = completedAtMs,
                updatedAtMs = completedAtMs
            )
        )
        persistLocked()
    }

    fun recoverInterruptedOccurrences(nowMs: Long = System.currentTimeMillis()): Int =
        synchronized(lock) {
            var recovered = 0
            occurrencesState = occurrencesState.map { occurrence ->
                if (occurrence.executionState != AutomationExecutionState.RUNNING) {
                    occurrence
                } else {
                    recovered += 1
                    occurrence.copy(
                        executionState = AutomationExecutionState.UNKNOWN,
                        deliveryState = AutomationDeliveryState.PENDING,
                        resultStatus = "unknown",
                        failureCode = "PROCESS_INTERRUPTED",
                        completedAtMs = nowMs,
                        nextDeliveryAttemptAtMs = nowMs,
                        updatedAtMs = nowMs
                    )
                }
            }
            if (recovered > 0) persistLocked()
            recovered
        }

    fun nextDelivery(nowMs: Long = System.currentTimeMillis()): AutomationOccurrence? =
        synchronized(lock) {
            occurrencesState
                .asSequence()
                .filter { occurrence ->
                    occurrence.deliveryState == AutomationDeliveryState.PENDING ||
                        occurrence.deliveryState == AutomationDeliveryState.FAILED
                }
                .filter { it.deliveryAttempts < MAX_DELIVERY_ATTEMPTS }
                .filter { it.nextDeliveryAttemptAtMs <= nowMs }
                .minByOrNull { it.nextDeliveryAttemptAtMs }
        }

    fun nextDeliveryAtMs(): Long? = synchronized(lock) {
        occurrencesState
            .asSequence()
            .filter {
                (it.deliveryState == AutomationDeliveryState.PENDING ||
                    it.deliveryState == AutomationDeliveryState.FAILED) &&
                    it.deliveryAttempts < MAX_DELIVERY_ATTEMPTS
            }
            .minOfOrNull { it.nextDeliveryAttemptAtMs }
    }

    fun markDeliveryAccepted(id: String, nowMs: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            val occurrence = occurrencesState.firstOrNull { it.id == id } ?: return@synchronized
            replaceOccurrenceLocked(
                occurrence.copy(
                    deliveryState = AutomationDeliveryState.HANDOFF_ACCEPTED,
                    deliveryFailureCode = "",
                    deliveryAttempts = occurrence.deliveryAttempts + 1,
                    nextDeliveryAttemptAtMs = 0L,
                    updatedAtMs = nowMs
                )
            )
            persistLocked()
        }

    fun markDeliveryFailed(
        id: String,
        reasonCode: String,
        nowMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val occurrence = occurrencesState.firstOrNull { it.id == id } ?: return@synchronized
        val attempt = occurrence.deliveryAttempts + 1
        val nextAttemptAt = if (attempt < MAX_DELIVERY_ATTEMPTS) {
            nowMs + DELIVERY_RETRY_DELAY_MS * attempt
        } else {
            0L
        }
        replaceOccurrenceLocked(
            AutomationOccurrencePolicy.deliveryFailed(
                occurrence = occurrence,
                reasonCode = reasonCode,
                attemptedAtMs = nowMs,
                nextAttemptAtMs = nextAttemptAt
            )
        )
        persistLocked()
    }

    fun retryDelivery(id: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val occurrence = occurrencesState.firstOrNull { it.id == id } ?: return@synchronized
        if (occurrence.executionState in setOf(
                AutomationExecutionState.QUEUED,
                AutomationExecutionState.RUNNING
            )
        ) return@synchronized
        replaceOccurrenceLocked(
            occurrence.copy(
                deliveryState = AutomationDeliveryState.PENDING,
                deliveryFailureCode = "",
                deliveryAttempts = 0,
                nextDeliveryAttemptAtMs = nowMs,
                updatedAtMs = nowMs
            )
        )
        persistLocked()
    }

    private fun load() = synchronized(lock) {
        val content = fileManager.readFile(STATE_PATH) ?: return@synchronized
        runCatching { json.decodeFromString<AutomationStateDocument>(content) }
            .onSuccess { document ->
                require(document.schemaVersion == SCHEMA_VERSION) {
                    "Unsupported automation schema ${document.schemaVersion}"
                }
                definitionsState = document.definitions
                occurrencesState = trimHistory(document.occurrences)
                _definitions.value = definitionsState
                _occurrences.value = occurrencesState
            }
            .onFailure { error ->
                Log.e(TAG, "Automation state load failed: ${error.message}")
            }
    }

    private fun replaceOccurrenceLocked(occurrence: AutomationOccurrence) {
        occurrencesState = occurrencesState.map {
            if (it.id == occurrence.id) occurrence else it
        }
    }

    private fun trimHistory(occurrences: List<AutomationOccurrence>): List<AutomationOccurrence> =
        occurrences.partition {
            it.executionState in setOf(AutomationExecutionState.QUEUED, AutomationExecutionState.RUNNING) ||
                it.deliveryState == AutomationDeliveryState.PENDING ||
                (it.deliveryState == AutomationDeliveryState.FAILED && it.deliveryAttempts < MAX_DELIVERY_ATTEMPTS)
        }.let { (active, history) -> (active + history.sortedBy(AutomationOccurrence::queuedAtMs)
            .takeLast(MAX_OCCURRENCES)).sortedBy(AutomationOccurrence::queuedAtMs) }

    private fun persistLocked() {
        try {
            fileManager.writeFile(STATE_PATH, json.encodeToString(AutomationStateDocument(
                schemaVersion = SCHEMA_VERSION, definitions = definitionsState, occurrences = occurrencesState
            )))
        } catch (error: Throwable) {
            definitionsState = _definitions.value
            occurrencesState = _occurrences.value
            throw error
        }
        _definitions.value = definitionsState
        _occurrences.value = occurrencesState
        _changeVersion.value += 1L
    }

    private companion object {
        const val TAG = "AutomationStore"
        const val STATE_PATH = "automations/state.json"
        const val SCHEMA_VERSION = 1
        const val MAX_OCCURRENCES = 240
        const val MAX_DELIVERY_ATTEMPTS = 3
        const val DELIVERY_RETRY_DELAY_MS = 5 * 60 * 1000L
    }
}
