// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AppTakeoverStore @Inject constructor(
    @ApplicationContext context: Context
) {
    @Serializable
    private data class PersistedState(
        val watches: List<AppTakeoverWatch> = emptyList(),
        val turnDeadlines: Map<String, Long> = emptyMap(),
        val turnRuns: Map<String, String> = emptyMap()
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion = _changeVersion.asStateFlow()

    @Synchronized
    fun bindTurnDeadline(turnId: String, deadlineAtMs: Long) {
        val state = loadState()
        writeState(state.copy(turnDeadlines = state.turnDeadlines + (turnId to deadlineAtMs)))
    }

    @Synchronized
    fun bindTakeoverTurn(turnId: String, runId: String) {
        val watch = checkNotNull(load().firstOrNull { it.activeRunId == runId }) { "TAKEOVER_RUN_NOT_OWNED" }
        val state = loadState()
        writeState(state.copy(turnRuns = state.turnRuns + (turnId to runId),
            turnDeadlines = state.turnDeadlines + (watch.deadlineAtMs?.let { mapOf(turnId to it) } ?: emptyMap())))
    }

    @Synchronized
    fun isTakeoverTurn(turnId: String): Boolean = turnId in loadState().turnRuns

    @Synchronized
    fun releaseTurnDeadline(turnId: String) {
        val state = loadState()
        if (turnId in state.turnDeadlines || turnId in state.turnRuns) writeState(state.copy(
            turnDeadlines = state.turnDeadlines - turnId, turnRuns = state.turnRuns - turnId))
    }

    @Synchronized
    fun deadlineForTurn(turnId: String?): Long? {
        if (turnId == null) return null
        val state = loadState()
        val watches = forOriginTurn(turnId) + state.watches.filter {
            state.turnRuns[turnId] != null && it.activeRunId == state.turnRuns[turnId]
        }
        return watches.mapNotNull { it.deadlineAtMs }.minOrNull() ?: state.turnDeadlines[turnId]
    }

    @Synchronized
    fun list(): List<AppTakeoverWatch> = load()

    @Synchronized
    fun find(packageName: String): AppTakeoverWatch? = load().firstOrNull {
        it.packageName.equals(packageName, ignoreCase = true)
    }

    @Synchronized
    fun hasEnabledWatches(): Boolean = load().any { it.enabled }

    /** Exact execution ownership; expired bounds still cancel a registering run in progress. */
    @Synchronized
    fun forOriginTurn(turnId: String?): List<AppTakeoverWatch> = if (turnId == null) emptyList() else load().filter {
        it.originTurnId == turnId && it.terminalReason != AppTakeoverTerminalReason.GOAL_COMPLETED
    }

    /** Saved progress is visible only to its exact registering Turn or currently bound child. */
    @Synchronized
    fun forTurn(turnId: String): List<AppTakeoverWatch> {
        val state = loadState()
        val runId = state.turnRuns[turnId]
        return state.watches.filter {
            (it.originTurnId == turnId || (runId != null && it.activeRunId == runId)) &&
                it.terminalReason != AppTakeoverTerminalReason.GOAL_COMPLETED
        }
    }

    @Synchronized
    fun start(
        packageNames: List<String>,
        instruction: String,
        pollIntervalMs: Long,
        taskStartedAtMs: Long,
        deadlineAtMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        originTurnId: String? = null,
        modelSelection: AppTakeoverModelSelection? = null
    ): List<AppTakeoverWatch> {
        val normalized = packageNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val current = load().associateBy { it.packageName.lowercase() }.toMutableMap()
        normalized.forEach { packageName ->
            val key = packageName.lowercase()
            val existing = current[key]
            current[key] = AppTakeoverWatch(
                    packageName = packageName,
                    instruction = instruction,
                    pollIntervalMs = pollIntervalMs,
                    nextPollAtMs = nowMs,
                    nextCheckAtMs = nowMs,
                    taskStartedAtMs = taskStartedAtMs,
                    originTurnId = originTurnId,
                    modelSelection = modelSelection,
                    deadlineAtMs = deadlineAtMs ?: originTurnId?.let { loadState().turnDeadlines[it] },
                    createdAtMs = existing?.createdAtMs ?: nowMs,
                    updatedAtMs = nowMs
                )
        }
        return persist(current.values.sortedBy { it.createdAtMs })
    }

    @Synchronized
    fun update(
        packageNames: List<String>,
        instruction: String?,
        pollIntervalMs: Long?,
        deadlineAtMs: Long? = null,
        checkpoint: String? = null,
        checkpointSummary: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): List<AppTakeoverWatch> {
        val targets = packageNames.map { it.lowercase() }.toSet()
        return persist(load().map { watch ->
            if (watch.packageName.lowercase() !in targets) {
                watch
            } else {
                watch.copy(
                    instruction = instruction ?: watch.instruction,
                    pollIntervalMs = pollIntervalMs ?: watch.pollIntervalMs,
                    deadlineAtMs = deadlineAtMs ?: watch.deadlineAtMs,
                    checkpoint = checkpoint ?: watch.checkpoint,
                    lastSummary = if (checkpoint != null) checkpointSummary ?: watch.lastSummary else watch.lastSummary,
                    nextCheckAtMs = if (instruction != null) nowMs else watch.nextCheckAtMs,
                    nextPollAtMs = pollIntervalMs?.let { nowMs + it }
                        ?: watch.nextPollAtMs,
                    updatedAtMs = nowMs
                )
            }
        })
    }

    @Synchronized
    fun setEnabled(
        packageNames: List<String>,
        enabled: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): List<AppTakeoverWatch> {
        val targets = packageNames.map { it.lowercase() }.toSet()
        if (enabled) check(load().filter { it.packageName.lowercase() in targets }.none {
            it.terminalReason != null || AppTakeoverTaskPolicy.expired(it, nowMs)
        }) { "APP_TAKEOVER_FINISHED" }
        return persist(load().map { watch ->
            if (watch.packageName.lowercase() !in targets) {
                watch
            } else {
                watch.copy(
                    enabled = enabled,
                    nextPollAtMs = if (enabled) nowMs else watch.nextPollAtMs,
                    updatedAtMs = nowMs
                )
            }
        })
    }

    @Synchronized
    fun remove(packageNames: List<String>): List<AppTakeoverWatch> {
        val targets = packageNames.map { it.lowercase() }.toSet()
        return persist(load().filterNot { it.packageName.lowercase() in targets })
    }

    @Synchronized
    fun clear(): List<AppTakeoverWatch> = persist(emptyList())

    @Synchronized
    fun enqueueNotification(
        info: NotificationInfo,
        debounceMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val signal = AppTakeoverSignal(
            id = "notification:${info.key}:${info.timestamp}",
            type = AppTakeoverSignalType.NOTIFICATION,
            occurredAtMs = info.timestamp,
            notificationKey = info.key,
            title = info.title,
            content = info.content
        )
        return enqueueSignal(info.packageName, signal, debounceMs, nowMs)
    }

    @Synchronized
    fun enqueueFrameChange(
        packageName: String,
        frameFingerprint: String,
        debounceMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = enqueueSignal(
        packageName = packageName,
        signal = AppTakeoverSignal(
            id = "frame:$frameFingerprint",
            type = AppTakeoverSignalType.FRAME_CHANGE,
            occurredAtMs = nowMs
        ),
        debounceMs = debounceMs,
        nowMs = nowMs
    )

    @Synchronized
    fun recoverInterruptedRuns(nowMs: Long = System.currentTimeMillis()) {
        val recovered = load().map { watch ->
            if (watch.activeRunId == null) {
                watch
            } else {
                watch.copy(
                    pendingSignals = mergeSignals(watch.activeSignals + watch.pendingSignals),
                    pendingNotBeforeAtMs = nowMs,
                    activeRunId = null,
                    activeRunStartedAtMs = 0L,
                    activePollDueAtMs = 0L,
                    activeSignals = emptyList(),
                    nextPollAtMs = nowMs,
                    updatedAtMs = nowMs
                )
            }
        }
        writeState(loadState().copy(watches = recovered, turnDeadlines = emptyMap(), turnRuns = emptyMap()))
    }

    /** Process restoration must rebuild the retained display/status before an old future poll. */
    @Synchronized
    fun scheduleRecoveryScan(nowMs: Long = System.currentTimeMillis()) {
        val watches = load()
        if (watches.none { it.enabled && it.activeRunId == null }) return
        persist(watches.map { watch ->
            if (watch.enabled && watch.activeRunId == null) {
                watch.copy(
                    nextPollAtMs = minOf(watch.nextPollAtMs, nowMs),
                    updatedAtMs = nowMs
                )
            } else {
                watch
            }
        })
    }

    @Synchronized
    fun claimNext(nowMs: Long = System.currentTimeMillis()): ClaimedAppTakeoverRun? {
        val current = load()
        val selected = AppTakeoverQueuePolicy.selectNext(current, nowMs) ?: return null
        val runId = UUID.randomUUID().toString()
        val duePoll = selected.nextPollAtMs <= nowMs
        val signals = buildList {
            addAll(selected.pendingSignals)
            if (duePoll) {
                add(
                    AppTakeoverSignal(
                        id = "poll:$runId",
                        type = AppTakeoverSignalType.POLL,
                        occurredAtMs = nowMs
                    )
                )
            }
            if (selected.nextCheckAtMs?.let { it <= nowMs } == true) {
                add(AppTakeoverSignal("recheck:$runId", AppTakeoverSignalType.RECHECK, nowMs))
            }
        }
        val claimed = selected.copy(
            pendingSignals = emptyList(),
            pendingNotBeforeAtMs = 0L,
            activeRunId = runId,
            activeRunStartedAtMs = nowMs,
            activePollDueAtMs = selected.nextPollAtMs,
            activeSignals = signals,
            lastClaimedAtMs = nowMs,
            nextPollAtMs = Long.MAX_VALUE,
            updatedAtMs = nowMs
        )
        persist(current.map { if (it.packageName == selected.packageName) claimed else it })
        return ClaimedAppTakeoverRun(runId = runId, watch = claimed, signals = signals)
    }

    @Synchronized
    fun completeRun(
        runId: String,
        checkpoint: String?,
        summary: String,
        nextCheckSeconds: Long?,
        goalComplete: Boolean = false,
        settledFrameFingerprint: String? = null,
        healthCheckOnly: Boolean = false,
        completedAtMs: Long = System.currentTimeMillis()
    ): AppTakeoverWatch? {
        var completed: AppTakeoverWatch? = null
        persist(load().map { watch ->
            if (watch.activeRunId != runId) {
                watch
            } else {
                settledWatch(watch, checkpoint, summary, nextCheckSeconds, goalComplete,
                    settledFrameFingerprint, healthCheckOnly, completedAtMs).also { completed = it }
            }
        })
        return completed
    }

    /** The registering Turn's done state is transferred into its newly registered goals. */
    @Synchronized
    fun completeOriginTurn(
        turnId: String,
        checkpoint: String?,
        summary: String,
        nextCheckSeconds: Long?,
        goalComplete: Boolean,
        completedAtMs: Long = System.currentTimeMillis()
    ) {
        val current = load()
        val eligible = current.filter { it.originTurnId == turnId && it.activeRunId == null &&
            it.terminalReason == null && !AppTakeoverTaskPolicy.expired(it, completedAtMs) }
        if (eligible.isEmpty()) return
        persist(current.map { watch ->
            if (watch in eligible) settledWatch(watch, checkpoint, summary, nextCheckSeconds,
                goalComplete, null, false, completedAtMs) else watch
        })
    }

    private fun settledWatch(
        watch: AppTakeoverWatch,
        checkpoint: String?,
        summary: String,
        nextCheckSeconds: Long?,
        goalComplete: Boolean,
        settledFrameFingerprint: String?,
        healthCheckOnly: Boolean,
        completedAtMs: Long
    ): AppTakeoverWatch {
        val terminal = if (AppTakeoverTaskPolicy.expired(watch, completedAtMs)) {
            AppTakeoverTerminalReason.DEADLINE_REACHED
        } else if (goalComplete) AppTakeoverTerminalReason.GOAL_COMPLETED else watch.terminalReason
        return watch.copy(
            checkpoint = checkpoint?.trim()?.takeIf { it.isNotBlank() } ?: watch.checkpoint,
            lastSummary = if (healthCheckOnly) watch.lastSummary else summary,
            lastFailure = "",
            enabled = watch.enabled && terminal == null,
            terminalReason = terminal,
            pendingSignals = if (terminal != null) emptyList() else watch.pendingSignals,
            nextCheckAtMs = if (terminal != null) null else if (healthCheckOnly) watch.nextCheckAtMs else nextCheckSeconds?.let {
                AppTakeoverTaskPolicy.checkAt(completedAtMs, it)
            },
            nextPollAtMs = AppTakeoverQueuePolicy.nextPollAt(
                previousDueAtMs = watch.activePollDueAtMs,
                completedAtMs = completedAtMs,
                pollIntervalMs = watch.pollIntervalMs
            ),
            activeRunId = null,
            activeRunStartedAtMs = 0L,
            activePollDueAtMs = 0L,
            activeSignals = emptyList(),
            lastCompletedAtMs = completedAtMs,
            lastHealthCheckAtMs = if (healthCheckOnly) {
                completedAtMs
            } else {
                watch.lastHealthCheckAtMs
            },
            lastSettledFrameFingerprint = settledFrameFingerprint
                ?.takeIf(String::isNotBlank)
                ?: watch.lastSettledFrameFingerprint,
            consecutiveFailures = 0,
            updatedAtMs = completedAtMs
        )
    }

    /** Expiry also applies while paused or queued; active runs are bounded by their owner. */
    @Synchronized
    fun expireDue(nowMs: Long = System.currentTimeMillis()): List<AppTakeoverWatch> {
        val expired = mutableListOf<AppTakeoverWatch>()
        val current = load()
        val updated = current.map { watch ->
            if (watch.activeRunId == null && AppTakeoverTaskPolicy.expired(watch, nowMs)) {
                watch.copy(enabled = false, terminalReason = AppTakeoverTerminalReason.DEADLINE_REACHED,
                    nextCheckAtMs = null, pendingSignals = emptyList(), updatedAtMs = nowMs).also(expired::add)
            } else watch
        }
        if (expired.isNotEmpty()) persist(updated)
        return expired
    }

    @Synchronized
    fun nextDeadlineAtMs(): Long? = load().filter { it.terminalReason == null && it.activeRunId == null }
        .mapNotNull { it.deadlineAtMs }.minOrNull()

    @Synchronized
    fun failRun(
        runId: String,
        reason: String,
        retryDelayMs: Long,
        failedAtMs: Long = System.currentTimeMillis()
    ) {
        persist(load().map { watch ->
            if (watch.activeRunId != runId) {
                watch
            } else {
                watch.copy(
                    lastFailure = reason,
                    nextPollAtMs = AppTakeoverQueuePolicy.nextRetryAt(
                        failedAtMs = failedAtMs,
                        pollIntervalMs = watch.pollIntervalMs,
                        retryDelayMs = retryDelayMs
                    ),
                    activeRunId = null,
                    activeRunStartedAtMs = 0L,
                    activePollDueAtMs = 0L,
                    activeSignals = emptyList(),
                    lastCompletedAtMs = failedAtMs,
                    consecutiveFailures = watch.consecutiveFailures + 1,
                    updatedAtMs = failedAtMs
                )
            }
        })
    }

    @Synchronized
    fun nextReadyAtMs(): Long? = load()
        .filter { it.enabled && it.activeRunId == null }
        .minOfOrNull { AppTakeoverQueuePolicy.readyAt(it) }

    private fun enqueueSignal(
        packageName: String,
        signal: AppTakeoverSignal,
        debounceMs: Long,
        nowMs: Long
    ): Boolean {
        var accepted = false
        val next = load().map { watch ->
            if (!watch.enabled || !watch.packageName.equals(packageName, ignoreCase = true)) {
                watch
            } else if (
                watch.pendingSignals.any { it.id == signal.id } ||
                watch.activeSignals.any { it.id == signal.id }
            ) {
                watch
            } else {
                accepted = true
                watch.copy(
                    pendingSignals = mergeSignals(watch.pendingSignals + signal),
                    pendingNotBeforeAtMs = nowMs + debounceMs,
                    updatedAtMs = nowMs
                )
            }
        }
        if (accepted) persist(next)
        return accepted
    }

    private fun mergeSignals(signals: List<AppTakeoverSignal>): List<AppTakeoverSignal> =
        signals.asReversed().distinctBy { it.id }.asReversed().takeLast(MAX_PENDING_SIGNALS)

    private fun loadState(): PersistedState {
        val raw = prefs.getString(KEY_STATE, null).orEmpty()
        return runCatching { json.decodeFromString<PersistedState>(raw) }.getOrDefault(PersistedState())
    }
    private fun load(): List<AppTakeoverWatch> = loadState().watches

    private fun writeState(state: PersistedState) {
        check(prefs.edit().putString(KEY_STATE, json.encodeToString(state)).commit()) {
            "Failed to persist app takeover state"
        }
        _changeVersion.value += 1L
    }
    private fun persist(watches: List<AppTakeoverWatch>): List<AppTakeoverWatch> {
        val state = loadState()
        val liveBounds = state.turnRuns.mapNotNull { (turn, run) ->
            watches.firstOrNull { it.activeRunId == run }?.deadlineAtMs?.let { turn to it }
        }.toMap()
        // Keep the last live bound until its execution releases ownership, even after settlement
        // clears the scheduler claim. A resumed child cannot lose its deadline in that gap.
        writeState(state.copy(watches = watches, turnDeadlines = state.turnDeadlines + liveBounds))
        return watches
    }

    private companion object {
        private const val PREFS_NAME = "droidance_app_takeover"
        private const val KEY_STATE = "state"
        private const val MAX_PENDING_SIGNALS = 50
    }
}
