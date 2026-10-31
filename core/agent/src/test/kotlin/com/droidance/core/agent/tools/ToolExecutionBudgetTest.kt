// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import org.junit.Assert.*
import org.junit.Test

class ToolExecutionBudgetTest {
    @Test fun nestedCallsCannotResetDeadlineOrExpandAccess() {
        var now = 0L
        val root = ToolExecutionBudget(5000, deviceAccessAllowed = false, mutationsAllowed = false) { now }
        now = 2_000_000_000L
        val child = root.constrain(60_000)
        assertEquals(3000L, child.remainingMs())
        assertFalse(child.deviceAccessAllowed)
        assertFalse(child.mutationsAllowed)
        now = 5_000_000_000L
        assertEquals(0L, child.remainingMs())
        assertEquals(0L, root.remainingMs())
    }

    @Test fun workerDeadlineCanFurtherConstrainTheInvocation() {
        var now = 0L
        val root = ToolExecutionBudget(90_000, true, true) { now }
        val child = root.constrain(1500)
        now = 1_000_000_000L
        assertEquals(500L, child.remainingMs())
        assertEquals(89_000L, root.remainingMs())
    }
}
