// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionTerminalTest {
    @Test
    fun unsuccessfulStatusesArePreservedForBothExecutionPaths() {
        for (started in listOf(false, true)) {
            assertEquals(TurnTerminal.FAILED, completionTerminal("failed", started))
            assertEquals(TurnTerminal.NEED_MORE_INFO, completionTerminal("need_more_info", started))
        }
        assertEquals(TurnTerminal.DIRECT_REPLY, completionTerminal("done", false))
        assertEquals(TurnTerminal.DONE, completionTerminal("done", true))
    }
}
