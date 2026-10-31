// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SharedPreferences persistence, isolated from the installed user's app and providers. */
@RunWith(AndroidJUnit4::class)
class AppTakeoverTaskInstrumentedTest {
    @Test fun hostDeadlineInterruptsBlockedExecutionAndPersistsExpiry() = fixture { context ->
        kotlinx.coroutines.runBlocking {
            val store = AppTakeoverStore(context)
            val start = System.currentTimeMillis()
            store.start(listOf("example.app"), "Goal", 60_000L, start, start + 1_000L, start)
            val first = store.claimNext(start)!!
            store.completeRun(first.runId, "A waiting; C answered", "Retained progress", 0L, completedAtMs = start)
            val active = store.claimNext(start)!!
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            val stopped = java.util.concurrent.CountDownLatch(1)
            var cancelled = false
            var began = false
            try {
                val completed = kotlinx.coroutines.withContext(dispatcher) {
                    AppTakeoverDeadlineRunner.run(store.changeVersion,
                        deadlineAtMs = { store.find("example.app")!!.deadlineAtMs }, maxSleepMs = 100L,
                        onDeadlineReached = { cancelled = true; stopped.countDown() }) {
                        began = true
                        check(stopped.await(5L, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
                assertFalse(completed)
                assertTrue(began)
                assertTrue(cancelled)
                store.failRun(active.runId, "TASK_DEADLINE_REACHED", 0L)
                assertEquals(AppTakeoverTerminalReason.DEADLINE_REACHED, store.expireDue().single().terminalReason)
                val restored = AppTakeoverStore(context).find("example.app")!!
                assertFalse(restored.enabled)
                assertEquals("A waiting; C answered", restored.checkpoint)
                assertNull(restored.activeRunId)
            } finally { stopped.countDown(); dispatcher.close() }
        }
    }

    @Test fun objectProgressAndRecheckSurviveReopenPauseAndInterruptedRun() = fixture { context ->
        var store = AppTakeoverStore(context)
        store.start(listOf("example.app"), "Continue the user's goal", 60_000L, 1_000L, 300_000L, 1_000L)
        val first = store.claimNext(1_000L)!!
        val state = "A: waiting, effect sent once; C: answered, missing evidence; " + "retained facts;".repeat(400)
        store.completeRun(first.runId, state, "Two objects advanced; verification pending", 120L, completedAtMs = 2_000L)
        store = AppTakeoverStore(context)
        assertEquals(state, store.find("example.app")!!.checkpoint)
        assertEquals(122_000L, store.find("example.app")!!.nextCheckAtMs)
        store.setEnabled(listOf("example.app"), false, 3_000L)
        store.setEnabled(listOf("example.app"), true, 4_000L)
        val second = store.claimNext(4_000L)!!
        store.update(listOf("example.app"), null, null, checkpoint = "$state C: now verified", nowMs = 5_000L)
        store = AppTakeoverStore(context)
        store.recoverInterruptedRuns(6_000L)
        val watch = store.find("example.app")!!
        assertEquals(1_000L, watch.taskStartedAtMs)
        assertEquals(300_000L, watch.deadlineAtMs)
        assertTrue(watch.checkpoint.endsWith("C: now verified"))
        assertNull(watch.activeRunId)
        assertNull(store.completeRun(second.runId, "stale state", "stale result", 0L, completedAtMs = 7_000L))
        assertTrue(store.find("example.app")!!.checkpoint.endsWith("C: now verified"))
    }

    @Test fun healthChecksDoNotConsumePlannedRecheckAndNewSignalsRemainQueued() = fixture { context ->
        val store = AppTakeoverStore(context)
        store.start(listOf("example.app"), "Goal", 60_000L, 0L, 300_000L, 0L)
        val first = store.claimNext(0L)!!
        store.completeRun(first.runId, "saved objects", "progress", 120L, completedAtMs = 1_000L)
        val health = store.claimNext(61_000L)!!
        assertEquals(listOf(AppTakeoverSignalType.POLL), health.signals.map { it.type })
        store.enqueueFrameChange("example.app", "external-change", 0L, 61_001L)
        store.completeRun(health.runId, null, "unchanged", null, healthCheckOnly = true, completedAtMs = 61_002L)
        val restored = AppTakeoverStore(context).find("example.app")!!
        assertEquals(121_000L, restored.nextCheckAtMs)
        assertEquals("progress", restored.lastSummary)
        assertEquals(1, restored.pendingSignals.size)
        val signal = store.claimNext(61_003L)!!
        store.completeRun(signal.runId, "saved objects", "progress", 1L, completedAtMs = 61_004L)
        assertTrue(store.claimNext(62_004L)!!.signals.any { it.type == AppTakeoverSignalType.RECHECK })
    }

    @Test fun deadlineStopsPausedGoalsOnceAndCannotBeRenewedByResume() = fixture { context ->
        val store = AppTakeoverStore(context)
        store.start(listOf("example.app"), "Goal", 60_000L, 0L, 10_000L, 0L)
        store.setEnabled(listOf("example.app"), false, 1_000L)
        assertTrue(store.expireDue(9_999L).isEmpty())
        assertEquals(AppTakeoverTerminalReason.DEADLINE_REACHED, store.expireDue(10_000L).single().terminalReason)
        assertTrue(AppTakeoverStore(context).expireDue(11_000L).isEmpty())
        assertNull(store.claimNext(11_000L))
        assertThrows(IllegalStateException::class.java) { store.setEnabled(listOf("example.app"), true, 11_000L) }
    }

    @Test fun cancellationPreservesProgressAndDeadlineTakesPrecedenceOverLateCompletion() = fixture { context ->
        val store = AppTakeoverStore(context)
        store.start(listOf("example.app"), "Goal", 60_000L, 0L, 10_000L, 0L)
        val first = store.claimNext(0L)!!
        store.completeRun(first.runId, "completed effects", "partial progress", 0L, completedAtMs = 1_000L)
        val interrupted = store.claimNext(1_000L)!!
        store.failRun(interrupted.runId, "cancelled", 0L, 10_000L)
        assertEquals("partial progress", store.expireDue(10_000L).single().lastSummary)
        store.start(listOf("example.app"), "New goal", 60_000L, 20_000L, 30_000L, 20_000L)
        assertEquals("", store.find("example.app")!!.checkpoint)
        val late = store.claimNext(20_000L)!!
        val terminal = store.completeRun(late.runId, "state", "late claim", null, goalComplete = true, completedAtMs = 30_000L)!!
        assertFalse(terminal.enabled)
        assertEquals(AppTakeoverTerminalReason.DEADLINE_REACHED, terminal.terminalReason)
    }

    @Test fun wholeGoalCompletionStopsOneWatchWhileOtherObjectsAndAppsRemainEligible() = fixture { context ->
        val store = AppTakeoverStore(context)
        store.start(listOf("example.a", "example.b"), "Goal", 60_000L, 0L, 30_000L, 0L)
        val first = store.claimNext(0L)!!
        store.completeRun(first.runId, "verified result", "complete", null, goalComplete = true, completedAtMs = 1_000L)
        assertEquals(AppTakeoverTerminalReason.GOAL_COMPLETED, store.find(first.watch.packageName)!!.terminalReason)
        assertTrue(store.hasEnabledWatches())
        assertNotEquals(first.watch.packageName, store.claimNext(1_001L)!!.watch.packageName)
    }

    @Test fun registeringTurnRetainsDeadlineThroughExpiryButCannotBoundAnotherTurn() = fixture { context ->
        val store = AppTakeoverStore(context)
        store.start(listOf("example.app"), "Goal", 60_000L, 0L, 10_000L, 0L, originTurnId = "origin")
        assertTrue(store.forOriginTurn(null).isEmpty())
        assertTrue(store.forOriginTurn("unrelated").isEmpty())
        assertEquals(10_000L, AppTakeoverStore(context).forOriginTurn("origin").single().deadlineAtMs)
        store.update(listOf("example.app"), null, null, deadlineAtMs = 5_000L, nowMs = 1_000L)
        store.expireDue(5_000L)
        assertEquals(5_000L, store.forOriginTurn("origin").single().deadlineAtMs)
        store.remove(listOf("example.app"))
        assertTrue(store.forOriginTurn("origin").isEmpty())
    }

    @Test fun firstTurnCheckpointAndChosenRecheckTransferToRegisteredGoal() = fixture { context ->
        var store = AppTakeoverStore(context)
        store.start(listOf("example.app"), "Goal", 60_000L, 0L, 10_000L, 0L, originTurnId = "origin")
        store.completeOriginTurn("unrelated", "wrong", "wrong", 0L, true, 1_000L)
        assertEquals("", store.find("example.app")!!.checkpoint)
        store.completeOriginTurn("origin", "A waiting; C answered", "Progress", 2L, false, 1_000L)
        store = AppTakeoverStore(context)
        assertEquals("A waiting; C answered", store.find("example.app")!!.checkpoint)
        assertEquals(3_000L, store.find("example.app")!!.nextCheckAtMs)
        assertNull(store.claimNext(2_999L))
        assertTrue(store.claimNext(3_000L)!!.signals.any { it.type == AppTakeoverSignalType.RECHECK })
    }

    @Test fun firstResponseBudgetSurvivesReopenAndTransfersBeforeTargetDiscoveryEnds() = fixture { context ->
        var store = AppTakeoverStore(context)
        store.bindTurnDeadline("origin", 10_000L)
        store.start(listOf("unrelated.app"), "Other goal", 60_000L, 0L, null, 0L)
        store = AppTakeoverStore(context)
        assertEquals(10_000L, store.deadlineForTurn("origin"))
        assertNull(store.deadlineForTurn("unrelated"))
        store.start(listOf("example.app"), "Goal", 60_000L, 0L, null, 0L, originTurnId = "origin")
        store.completeOriginTurn("origin", "Retained facts", "Progress", 0L, false, 1_000L)
        store.releaseTurnDeadline("origin")
        assertEquals(10_000L, AppTakeoverStore(context).find("example.app")!!.deadlineAtMs)
    }

    @Test fun exactChildBindingUsesLiveWatchDeadlineAndReleaseCannotAffectAnotherTurn() = fixture { context ->
        val store = AppTakeoverStore(context)
        store.start(listOf("example.app"), "Goal", 60_000L, 0L, 10_000L, 0L)
        val run = store.claimNext(0L)!!
        store.bindTakeoverTurn("child", run.runId)
        assertEquals(10_000L, AppTakeoverStore(context).deadlineForTurn("child"))
        store.update(listOf("example.app"), null, null, deadlineAtMs = 20_000L, nowMs = 1_000L)
        assertEquals(20_000L, store.deadlineForTurn("child"))
        assertNull(store.deadlineForTurn("other"))
        store.update(listOf("example.app"), null, null, deadlineAtMs = 5_000L, nowMs = 2_000L)
        store.failRun(run.runId, "TASK_DEADLINE_REACHED", 0L, failedAtMs = 5_000L)
        store.expireDue(5_000L)
        assertNull(store.find("example.app")!!.activeRunId)
        assertEquals(5_000L, AppTakeoverStore(context).deadlineForTurn("child"))
        store.releaseTurnDeadline("child")
        assertNull(store.deadlineForTurn("child"))
        assertEquals(5_000L, store.find("example.app")!!.deadlineAtMs)
    }

    @Test fun savedProgressIsScopedToTheRegisteringTurnAndItsBoundChild() = fixture { context ->
        val store = AppTakeoverStore(context)
        store.start(listOf("example.app"), "Goal", 60_000L, 0L, 5_000L, 0L,
            originTurnId = "origin")
        val run = store.claimNext(0L)!!
        store.bindTakeoverTurn("child", run.runId)
        store.update(listOf("example.app"), null, null,
            checkpoint = "Grounded saved progress", checkpointSummary = "Progress saved", nowMs = 1_000L)
        store.start(listOf("other.app"), "Other goal", 60_000L, 0L, 20_000L, 0L,
            originTurnId = "other-origin")
        val reopened = AppTakeoverStore(context)
        assertEquals("Grounded saved progress", reopened.forTurn("origin").single().checkpoint)
        assertEquals("Grounded saved progress", reopened.forTurn("child").single().checkpoint)
        assertEquals("other.app", reopened.forTurn("other-origin").single().packageName)
        assertTrue(reopened.forTurn("unrelated").isEmpty())
        reopened.releaseTurnDeadline("child")
        assertTrue(reopened.forTurn("child").isEmpty())
        assertEquals("Grounded saved progress", reopened.forTurn("origin").single().checkpoint)
        reopened.failRun(run.runId, "Deadline reached", 0L, failedAtMs = 5_000L)
        reopened.expireDue(5_000L)
        assertEquals(AppTakeoverTerminalReason.DEADLINE_REACHED,
            reopened.forTurn("origin").single().terminalReason)
    }

    @Test fun selectedAgentModelSurvivesReopenAndChildClaimWithoutPersistingCredentials() = fixture { context ->
        val selection = AppTakeoverModelSelection("provider", "selected-vision-model")
        val store = AppTakeoverStore(context)
        store.start(listOf("test.model"), "Observe", 60_000, 1000, 20_000,
            nowMs = 1000, originTurnId = "origin", modelSelection = selection)
        val reopened = AppTakeoverStore(context)
        assertEquals(selection, reopened.find("test.model")!!.modelSelection)
        val claim = reopened.claimNext(1000)!!
        assertEquals(selection, claim.watch.modelSelection)
        reopened.completeRun(claim.runId, "Saved state", "Progress", nextCheckSeconds = 1, completedAtMs = 2000)
        assertEquals(selection, AppTakeoverStore(context).find("test.model")!!.modelSelection)
    }

    private fun fixture(block: (Context) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "takeover-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try { block(context) } finally { base.getSharedPreferences(name, 0).edit().clear().commit() }
    }
}
