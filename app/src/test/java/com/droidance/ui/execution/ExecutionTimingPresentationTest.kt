// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionTimingPresentationTest {
    @Test fun thought_and_all_actions_use_one_round_interval_instead_of_action_duration() {
        // Model request starts at 1s; thinking lasts 10s; actions finish at 12.4s.
        val timing = timing(listOf(round("one", 1000L, 12_400L)), now = 99_000L)
        assertEquals(1000L, timing.startedAt)
        assertEquals(11_400L, timing.durationMs)
        assertEquals(ExecutionProcessTimingState.FINISHED, timing.state)
    }

    @Test fun merged_progress_spans_its_actual_rounds_once_without_restarting_from_the_task_start() {
        val rounds = listOf(round("one", 10_000L, 12_000L), round("two", 13_000L, 16_000L))
        val timing = timing(rounds, roundIds = listOf("one", "two", "one"))
        assertEquals(10_000L, timing.startedAt)
        assertEquals(6000L, timing.durationMs)
    }

    @Test fun thinking_before_any_action_ticks_and_completion_freezes_the_whole_process() {
        val running = listOf(round("one", 1000L, null))
        assertEquals(ExecutionProcessTimingState.RUNNING, timing(running).state)
        assertEquals(4000L, timing(running).durationMs)
        assertEquals(5000L, timing(running, now = 6000L).durationMs)
        assertEquals(4500L, timing(listOf(round("one", 1000L, 5500L)), now = 60_000L).durationMs)
    }

    @Test fun settled_rounds_stay_finished_while_other_rounds_are_running_or_paused() {
        val round = listOf(round("one", 1000L, 3000L))
        for (state in TurnReadModelState.entries) {
            assertEquals(ExecutionProcessTimingState.FINISHED, timing(round, state).state)
            assertEquals(2000L, timing(round, state).durationMs)
        }
    }

    @Test fun paused_and_unrecorded_terminal_intervals_do_not_keep_ticking() {
        val running = listOf(round("one", 1000L, null))
        for (state in listOf(TurnReadModelState.WAITING_FOR_USER, TurnReadModelState.PAUSED_USER,
            TurnReadModelState.PAUSED_RECOVERY_REQUIRED)) {
            assertEquals(ExecutionProcessTimingState.PAUSED, timing(running, state).state)
            assertNull(timing(running, state).durationMs)
        }
        assertEquals(ExecutionProcessTimingState.UNRECORDED, timing(running, TurnReadModelState.TERMINAL).state)
        assertNull(timing(running, TurnReadModelState.TERMINAL).durationMs)
    }

    @Test fun absent_or_reversed_boundaries_do_not_fall_back_to_action_or_task_time() {
        assertNull(timing(emptyList()).durationMs)
        assertNull(timing(listOf(round("one", 1000L, 500L))).durationMs)
        assertNull(timing(listOf(round("one", 6000L, null))).durationMs)
        assertNull(timing(listOf(round("one", 0L, 500L))).durationMs)
        assertNull(timing(listOf(round("one", 1000L, 3000L)), roundIds = listOf("one", "missing")).durationMs)
    }

    @Test fun a_mismatched_round_map_cannot_supply_another_process_time() {
        val activity = activity(listOf(round("one", 1000L, 3000L))).copy(
            executionRoundTimings = mapOf("one" to round("another", 1000L, 3000L)))
        assertNull(executionProcessTiming(node(listOf("one")), activity, 5000L).durationMs)
    }

    @Test fun an_actual_zero_interval_remains_recorded() {
        assertEquals(0L, timing(listOf(round("one", 1000L, 1000L))).durationMs)
    }

    @Test fun timestamps_use_the_device_timezone_and_include_the_date_for_other_days() {
        val now = Instant.parse("2026-10-07T08:00:00Z").toEpochMilli()
        val sameDay = Instant.parse("2026-10-07T06:32:08Z").toEpochMilli()
        val previousDay = Instant.parse("2026-10-06T15:59:59Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals("14:32:08", formatExecutionStartTime(sameDay, now, zone, Locale.CHINA))
        assertEquals("2026-10-06 23:59:59", formatExecutionStartTime(previousDay, now, zone, Locale.US))
        assertEquals("2026-10-07 00:00:00", formatExecutionStartTime(
            Instant.parse("2026-10-06T16:00:00Z").toEpochMilli(),
            Instant.parse("2026-10-07T16:00:00Z").toEpochMilli(), zone, Locale.CHINA))
    }

    private fun timing(rounds: List<ExecutionRoundTimingReadModel>, state: TurnReadModelState = TurnReadModelState.RUNNING,
        now: Long = 5000L, roundIds: List<String> = rounds.map { it.roundId }) =
        executionProcessTiming(node(roundIds), activity(rounds).copy(state = state), now)

    private fun round(id: String, start: Long, end: Long?) = ExecutionRoundTimingReadModel(id, start, end)
    private fun node(roundIds: List<String>) = ExecutionProgressNode("node", 0, "Check", emptyList(), roundIds)
    private fun activity(rounds: List<ExecutionRoundTimingReadModel>) = TurnActivityReadModel(
        turnId = "turn", state = TurnReadModelState.RUNNING, entries = emptyList(), operationCount = 0,
        currentLabelResourceKey = null, publicProgressHistory = emptyList(), startedAt = 1L,
        completedAt = null, totalDurationMillis = null, executionRoundTimings = rounds.associateBy { it.roundId })
}
