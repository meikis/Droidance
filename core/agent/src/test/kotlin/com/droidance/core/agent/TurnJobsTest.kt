// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class TurnJobsTest {
    @Test fun canceledTurnCannotCancelNextTurn() {
        val jobs = TurnJobs()
        val old = Job()
        jobs.begin("ui", old)
        val lateTargets = jobs.cancellationTargets("ui")
        jobs.release("ui", old)
        val next = Job()
        jobs.begin("ui", next)
        lateTargets.forEach { it.cancel() }
        assertTrue(old.isCancelled)
        assertTrue(next.isActive)
        assertSame(next, jobs.runningJob)
    }

    @Test fun cancellationOfDifferentSourceDoesNotReachRunningTurn() {
        val jobs = TurnJobs()
        val ui = Job()
        val scheduled = Job()
        jobs.begin("ui", ui)
        jobs.register("scheduled", scheduled)
        jobs.cancellationTargets("scheduled").forEach { it.cancel() }
        assertTrue(ui.isActive)
        assertTrue(scheduled.isCancelled)
    }

    @Test fun oldCleanupCannotUnregisterReplacementJob() {
        val jobs = TurnJobs()
        val old = Job()
        val replacement = Job()
        jobs.begin("ui", old)
        jobs.register("ui", replacement)
        jobs.release("ui", old)
        assertNull(jobs.runningJob)
        assertEquals(listOf(replacement), jobs.cancellationTargets("ui"))
    }

    @Test fun preCanceledJobStaysCanceledAtActivation() {
        val job = Job().also { it.cancel() }
        val jobs = TurnJobs()
        jobs.begin("ui", job)
        assertTrue(jobs.runningJob!!.isCancelled)
    }
}
