// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AdbLifecycleGateTest {
    @Test fun aRunningTaskDefersDaemonRestartAndCancellationReleasesTheLane() = runBlocking {
        val gate = AdbLifecycleGate()
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withTask { CompletableDeferred<Unit>().await() }
        }
        var restarted = false
        assertNull(gate.tryWhileIdle { restarted = true })
        assertFalse(restarted)
        task.cancelAndJoin()
        assertEquals(true, gate.tryWhileIdle { restarted = true; true })
        assertTrue(restarted)
    }

    @Test fun aTaskCannotStartUntilAnInFlightRestartSettles() = runBlocking {
        val gate = AdbLifecycleGate()
        val settle = CompletableDeferred<Unit>()
        val restart = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.whileIdle { settle.await() }
        }
        val admitted = CompletableDeferred<Unit>()
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withTask { admitted.complete(Unit) }
        }
        assertFalse(admitted.isCompleted)
        settle.complete(Unit)
        withTimeout(1_000) { restart.join(); task.join() }
        assertTrue(admitted.isCompleted)
    }

    @Test fun preflightAndRuntimeCanNestWithoutAllowingRestartBetweenThem() = runBlocking {
        val gate = AdbLifecycleGate()
        gate.withTask {
            assertEquals("runtime", withTimeout(1_000) { gate.withTask { "runtime" } })
            assertNull(gate.tryWhileIdle { "restart" })
        }
        assertEquals("restart", gate.tryWhileIdle { "restart" })
    }

    @Test fun shutdownWaitsForEveryAdmittedTaskAndAllowsTheirCleanup() = runBlocking {
        val gate = AdbLifecycleGate()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTask { releaseFirst.await() } }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTask { releaseSecond.await() } }
        var closed = false
        val shutdown = launch(start = CoroutineStart.UNDISPATCHED) { gate.whileIdle { closed = true } }
        releaseFirst.complete(Unit)
        first.join()
        assertFalse(closed)
        releaseSecond.complete(Unit)
        withTimeout(1_000) { second.join(); shutdown.join() }
        assertTrue(closed)
    }

    @Test fun failedRestartDoesNotBlockLaterTaskAdmission() = runBlocking {
        val gate = AdbLifecycleGate()
        runCatching { gate.tryWhileIdle { error("daemon restart failed") } }
        assertEquals("task", withTimeout(1_000) { gate.withTask { "task" } })
    }
}
