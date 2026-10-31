// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.event.TurnActivityReadModel
import com.droidance.core.agent.event.TurnReadModelState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class ExecutionProcessTimingState {
    FINISHED, RUNNING, PAUSED, UNRECORDED
}

internal data class ExecutionProcessTiming(
    val startedAt: Long?,
    val durationMs: Long?,
    val state: ExecutionProcessTimingState
)

/** Public progress owns actual model rounds, including their thinking and every tool in those rounds. */
internal fun executionProcessTiming(
    node: ExecutionProgressNode,
    activity: TurnActivityReadModel,
    nowMs: Long
): ExecutionProcessTiming {
    val rounds = node.roundIds.distinct().map { roundId ->
        activity.executionRoundTimings[roundId]?.takeIf { it.roundId == roundId && it.startedAt > 0L }
    }
    if (rounds.isEmpty() || rounds.any { it == null }) {
        return ExecutionProcessTiming(null, null, ExecutionProcessTimingState.UNRECORDED)
    }
    val recorded = rounds.filterNotNull()
    val startedAt = recorded.minOf { it.startedAt }
    if (recorded.any { round -> round.finishedAt?.let { it < round.startedAt } == true }) {
        return ExecutionProcessTiming(startedAt, null, ExecutionProcessTimingState.UNRECORDED)
    }
    val pending = recorded.any { it.finishedAt == null }
    if (pending && activity.state != TurnReadModelState.RUNNING) {
        return ExecutionProcessTiming(startedAt, null,
            if (activity.state == TurnReadModelState.TERMINAL) ExecutionProcessTimingState.UNRECORDED
            else ExecutionProcessTimingState.PAUSED)
    }
    val finishedAt = if (pending) nowMs else recorded.maxOf { checkNotNull(it.finishedAt) }
    return ExecutionProcessTiming(startedAt, (finishedAt - startedAt).takeIf { it >= 0L },
        if (pending) ExecutionProcessTimingState.RUNNING else ExecutionProcessTimingState.FINISHED)
}

internal fun formatExecutionStartTime(
    startedAt: Long,
    nowMs: Long,
    zone: ZoneId,
    locale: Locale
): String {
    val start = Instant.ofEpochMilli(startedAt).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val pattern = if (start.toLocalDate() == today) "HH:mm:ss" else "yyyy-MM-dd HH:mm:ss"
    return start.format(DateTimeFormatter.ofPattern(pattern, locale))
}
