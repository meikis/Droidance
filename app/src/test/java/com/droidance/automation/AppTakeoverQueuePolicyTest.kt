// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTakeoverQueuePolicyTest {

    @Test
    fun dueAppsAreSelectedFairlyAndNeverWhileAlreadyRunning() {
        val now = 100_000L
        val recentlyClaimed = takeover(
            packageName = "app.recent",
            nextPollAtMs = now,
            lastClaimedAtMs = 90_000L
        )
        val neverClaimed = takeover(
            packageName = "app.never",
            nextPollAtMs = now,
            lastClaimedAtMs = 0L
        )
        val running = takeover(
            packageName = "app.running",
            nextPollAtMs = now,
            lastClaimedAtMs = 0L,
            activeRunId = "run"
        )

        assertEquals(
            "app.never",
            AppTakeoverQueuePolicy.selectNext(listOf(recentlyClaimed, neverClaimed, running), now)
                ?.packageName
        )
    }

    @Test
    fun pendingSignalsRespectTheirDebounceBoundary() {
        val watch = takeover(
            packageName = "app.pending",
            nextPollAtMs = Long.MAX_VALUE,
            pendingSignals = listOf(
                AppTakeoverSignal(
                    id = "notification:key",
                    type = AppTakeoverSignalType.NOTIFICATION,
                    occurredAtMs = 1_000L
                )
            ),
            pendingNotBeforeAtMs = 2_000L
        )

        assertNull(AppTakeoverQueuePolicy.selectNext(listOf(watch), 1_999L))
        assertEquals(watch, AppTakeoverQueuePolicy.selectNext(listOf(watch), 2_000L))
    }

    @Test
    fun completionPreservesDeadlineCadenceAndCatchesUpOnceAfterAnOverrun() {
        assertEquals(150_000L, AppTakeoverQueuePolicy.nextPollAt(150_000L, 100_000L, 60_000L))
        assertEquals(160_000L, AppTakeoverQueuePolicy.nextPollAt(100_000L, 110_000L, 60_000L))
        assertEquals(160_000L, AppTakeoverQueuePolicy.nextPollAt(100_000L, 160_000L, 60_000L))
        assertEquals(160_000L, AppTakeoverQueuePolicy.nextPollAt(100_000L, 160_000L, 60_000L))
    }

    @Test
    fun unchangedScreenFastPathOnlyAcceptsAPureScheduledPollOnTheVisibleTarget() {
        val poll = AppTakeoverSignal("poll", AppTakeoverSignalType.POLL, 1L)
        val change = AppTakeoverSignal("frame", AppTakeoverSignalType.FRAME_CHANGE, 2L)

        assertEquals(
            true,
            AppTakeoverHealthPolicy.canSkipModel(listOf(poll), true, "same", "same", null, "saved state", 3L)
        )
        assertEquals(
            false,
            AppTakeoverHealthPolicy.canSkipModel(listOf(poll, change), true, "same", "same", null, "saved state", 3L)
        )
        assertEquals(
            false,
            AppTakeoverHealthPolicy.canSkipModel(listOf(poll), false, "same", "same", null, "saved state", 3L)
        )
    }

    @Test
    fun takeoverSessionIdIsStableAcrossPackageNameFormatting() {
        assertEquals(
            AppTakeoverSessionIds.threadId("com.example.chat"),
            AppTakeoverSessionIds.threadId("  COM.EXAMPLE.CHAT  ")
        )
    }

    @Test
    fun terminalFailureNeverCreatesATightRetryLoopBelowTheConfiguredPoll() {
        assertEquals(160_000L, AppTakeoverQueuePolicy.nextRetryAt(100_000L, 60_000L, 3_000L))
        assertEquals(220_000L, AppTakeoverQueuePolicy.nextRetryAt(100_000L, 60_000L, 120_000L))
    }

    @Test
    fun plannedRecheckRunsOnUnchangedPixelsButEarlierHealthPollCanSkip() {
        val poll = AppTakeoverSignal("poll", AppTakeoverSignalType.POLL, 1L)
        assertEquals(true, AppTakeoverHealthPolicy.canSkipModel(listOf(poll), true, "same", "same", 100L, "objects", 99L))
        assertEquals(false, AppTakeoverHealthPolicy.canSkipModel(listOf(poll), true, "same", "same", 100L, "objects", 100L))
        assertEquals(false, AppTakeoverHealthPolicy.canSkipModel(listOf(poll), true, "same", "same", null, "", 100L))
        val recheck = AppTakeoverSignal("due", AppTakeoverSignalType.RECHECK, 100L)
        assertEquals(false, AppTakeoverHealthPolicy.canSkipModel(listOf(recheck), true, "same", "same", null, "objects", 100L))
    }

    @Test
    fun recheckDeadlineAndGoalTerminalStateControlEligibilityWithoutBusinessParsing() {
        val pending = takeover("app.goal", 500L).copy(nextCheckAtMs = 100L, deadlineAtMs = 200L)
        assertNull(AppTakeoverQueuePolicy.selectNext(listOf(pending), 99L))
        assertEquals(pending, AppTakeoverQueuePolicy.selectNext(listOf(pending), 100L))
        assertNull(AppTakeoverQueuePolicy.selectNext(listOf(pending), 200L))
        assertNull(AppTakeoverQueuePolicy.selectNext(listOf(pending.copy(terminalReason = AppTakeoverTerminalReason.GOAL_COMPLETED)), 100L))
    }

    @Test
    fun durationUsesHostRequestAnchorAndRejectsAmbiguousOrOverflowingTime() {
        assertEquals(1_201_000L, AppTakeoverTaskPolicy.deadline(1200L, null, 1000L))
        assertEquals(0L, AppTakeoverTaskPolicy.deadline(null, "1970-01-01T08:00:00+08:00", 999L))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { AppTakeoverTaskPolicy.deadline(1L, "1970-01-01T00:00:00Z", 1L) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { AppTakeoverTaskPolicy.deadline(0L, null, 1L) }
        org.junit.Assert.assertThrows(ArithmeticException::class.java) { AppTakeoverTaskPolicy.deadline(Long.MAX_VALUE, null, 1L) }
        org.junit.Assert.assertThrows(java.time.format.DateTimeParseException::class.java) { AppTakeoverTaskPolicy.deadline(null, "2026-10-12T08:00:00", 1L) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { AppTakeoverTaskPolicy.checkAt(1L, -1L) }
    }

    private fun takeover(
        packageName: String,
        nextPollAtMs: Long,
        lastClaimedAtMs: Long = 0L,
        activeRunId: String? = null,
        pendingSignals: List<AppTakeoverSignal> = emptyList(),
        pendingNotBeforeAtMs: Long = 0L
    ) = AppTakeoverWatch(
        packageName = packageName,
        instruction = "handle new items",
        pollIntervalMs = 60_000L,
        nextPollAtMs = nextPollAtMs,
        nextCheckAtMs = null,
        lastClaimedAtMs = lastClaimedAtMs,
        activeRunId = activeRunId,
        pendingSignals = pendingSignals,
        pendingNotBeforeAtMs = pendingNotBeforeAtMs
    )
}
