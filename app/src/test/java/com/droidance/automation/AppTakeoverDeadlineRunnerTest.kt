// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AppTakeoverDeadlineRunnerTest {
    @Test fun independentExecutionCancellationIsNotReportedAsSuccessfulCompletion() = runBlocking {
        try {
            AppTakeoverDeadlineRunner.run(MutableStateFlow(0L), { null }, 60_000L) {
                throw CancellationException("user stopped execution")
            }
            fail("Cancellation must propagate to the existing caller")
        } catch (expected: CancellationException) {
            assertEquals("EXECUTION_CANCELLED", expected.message)
        }
    }

    @Test fun completionAtDeadlineCannotReportWithinDeadline() = runBlocking {
        var now = 0L
        assertFalse(AppTakeoverDeadlineRunner.run(MutableStateFlow(0L), { 100L }, 60_000L, { now }) {
            now = 100L
        })
    }

    @Test fun deadlineCancelsExternalRequestBeforeJoiningItsNonCooperativeCleanup() = runBlocking {
        val changes = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>()
        val externalRequestStopped = CompletableDeferred<Unit>()
        var now = 0L
        var cancellations = 0
        var cleaned = false
        val result = async {
            AppTakeoverDeadlineRunner.run(changes, { 100L }, 60_000L, { now },
                onDeadlineReached = { cancellations++; externalRequestStopped.complete(Unit) }) {
                try {
                    withContext(NonCancellable) { started.complete(Unit); externalRequestStopped.await() }
                } finally { cleaned = true }
            }
        }
        started.await()
        now = 100L
        changes.value++
        assertFalse(withTimeout(1_000L) { result.await() })
        assertEquals(1, cancellations)
        assertTrue(cleaned)
    }

    @Test fun blockingTheExecutionDispatcherCannotBlockTheHostDeadline() = runBlocking {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val release = java.util.concurrent.CountDownLatch(1)
        val started = CompletableDeferred<Unit>()
        val changes = MutableStateFlow(0L)
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        try {
            val result = async(dispatcher) {
                AppTakeoverDeadlineRunner.run(changes, { 100L }, 60_000L, now::get,
                    onDeadlineReached = { release.countDown() }) {
                    started.complete(Unit)
                    check(release.await(5L, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            started.await()
            now.set(100L)
            changes.value++
            assertFalse(withTimeout(2_000L) { result.await() })
        } finally { release.countDown(); dispatcher.close() }
    }

    @Test fun alreadyExpiredGoalDoesNotBeginAnotherOperation() = runBlocking {
        withContext(Dispatchers.Default) {
            var calls = 0
            repeat(50) {
                assertFalse(AppTakeoverDeadlineRunner.run(MutableStateFlow(0L), { 1L }, 60_000L, { 1L }) { calls++ })
            }
            assertEquals(0, calls)
        }
    }

    @Test fun deadlineCancelsASuspendedOperationAndWaitsForCleanupWithoutReplay() = runBlocking {
        val changes = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>()
        var now = 0L
        var calls = 0
        var cleaned = false
        val result = async {
            AppTakeoverDeadlineRunner.run(changes, { 10_000L }, 60_000L, { now }) {
                calls++
                started.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        started.await()
        now = 10_000L
        changes.value++
        assertFalse(result.await())
        assertTrue(cleaned)
        assertEquals(1, calls)
    }

    @Test fun explicitDeadlineChangesApplyToTheSameRunningOperation() = runBlocking {
        val changes = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>()
        val checkedAfterOriginalDeadline = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var now = 0L
        var deadline = 10_000L
        val result = async {
            AppTakeoverDeadlineRunner.run(changes, {
                if (now == 11_000L) checkedAfterOriginalDeadline.complete(Unit)
                deadline
            }, 60_000L, { now }) { started.complete(Unit); finish.await() }
        }
        started.await()
        deadline = 20_000L
        now = 11_000L
        changes.value++
        checkedAfterOriginalDeadline.await()
        assertTrue(result.isActive)
        finish.complete(Unit)
        assertTrue(result.await())
    }

    @Test fun shortenedDeadlineCancelsAnAlreadyRunningWait() = runBlocking {
        val changes = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>()
        var deadline = 20_000L
        val result = async {
            AppTakeoverDeadlineRunner.run(changes, { deadline }, 60_000L, { 5_000L }) {
                started.complete(Unit); awaitCancellation()
            }
        }
        started.await()
        deadline = 4_000L
        changes.value++
        assertFalse(result.await())
    }
}
