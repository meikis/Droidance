// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.program

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramTerminationTest {
    @Test fun cancellationRemainsCancellationUnlessAnActualBudgetExpired() {
        val original = CancellationException("user stopped")
        assertEquals("canceled", programFailureState(original, deadlineExpired = false, invocationActive = false))
        assertEquals("timed_out", programFailureState(original, deadlineExpired = true, invocationActive = false))
        assertEquals("failed", programFailureState(IllegalStateException("host failed"), false, true))
    }

    @Test fun localChildTimeoutDoesNotTurnIntoUserCancellation() = runBlocking {
        val timeout = try {
            withTimeout(1) { awaitCancellation() }
        } catch (error: TimeoutCancellationException) { error }
        assertEquals("timed_out", programFailureState(timeout, deadlineExpired = false, invocationActive = true))
        assertEquals("canceled", programFailureState(timeout, deadlineExpired = false, invocationActive = false))
    }
}
