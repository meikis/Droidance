// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.tools.OperatorToolProfile
import com.droidance.core.agent.tools.ToolCatalog
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IdleWorkSchedulerTest {
    @Test
    fun foregroundCancelsReflectionWaitsForCleanupThenRetriesTheSameSource() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = IdleWorkScheduler(scope)
            val started = CompletableDeferred<Unit>()
            val canceled = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val foregroundStarted = CompletableDeferred<Unit>()
            val foregroundFinish = CompletableDeferred<Unit>()
            val retried = CompletableDeferred<Unit>()
            var attempts = 0
            scheduler.enqueue("source-turn") {
                attempts++
                if (attempts == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally {
                        canceled.complete(Unit)
                        withContext(NonCancellable) { releaseCleanup.await() }
                    }
                } else retried.complete(Unit)
            }
            withTimeout(3000) { started.await() }
            val foreground = async {
                scheduler.runForeground {
                    foregroundStarted.complete(Unit)
                    foregroundFinish.await()
                }
            }
            withTimeout(3000) { canceled.await() }
            assertFalse(foregroundStarted.isCompleted)
            assertEquals(1, scheduler.state.value.pending)
            releaseCleanup.complete(Unit)
            withTimeout(3000) { foregroundStarted.await() }
            assertFalse(retried.isCompleted)
            foregroundFinish.complete(Unit)
            withTimeout(3000) { foreground.await(); retried.await() }
            assertEquals(2, attempts)
        } finally { scope.cancel() }
    }

    @Test
    fun disablingClearsQueuedSourcesWithoutStartingThem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = IdleWorkScheduler(scope)
            var calls = 0
            scheduler.runForeground {
                scheduler.enqueue("one") { calls++ }
                scheduler.enqueue("one") { calls++ }
                scheduler.enqueue("two") { calls++ }
                assertEquals(2, scheduler.state.value.pending)
                scheduler.clearPending()
            }
            assertEquals(0, scheduler.state.value.pending)
            assertEquals(0, calls)
        } finally { scope.cancel() }
    }

    @Test
    fun admittingAnotherSourcePreservesTheRunningStatus() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = IdleWorkScheduler(scope)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            scheduler.enqueue("one") { started.complete(Unit); finish.await() }
            withTimeout(3000) { started.await() }
            scheduler.enqueue("two") { }
            assertTrue(scheduler.state.value.running)
            assertEquals(2, scheduler.state.value.pending)
            scheduler.clearPending()
        } finally { scope.cancel() }
    }

    @Test
    fun backgroundKnowledgeCannotActOnDevicesRunScriptsScheduleOrPublish() {
        val names = ToolCatalog.operatorToolNamesFor(null, null, OperatorToolProfile.BACKGROUND_KNOWLEDGE)
        assertEquals(setOf("session_read", "recall_skill", "read_skill_file", "skill_source", "save_skill", "done"), names)
        assertTrue(names.intersect(ToolCatalog.nativeActionToolNames + ToolCatalog.screenChangingToolNames).isEmpty())
        assertFalse("program" in names)
        assertFalse("automation" in names)
        assertFalse("run_skill" in names)
    }
}
