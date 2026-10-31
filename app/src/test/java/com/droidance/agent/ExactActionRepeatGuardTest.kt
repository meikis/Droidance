// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.event.ToolExecutionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactActionRepeatGuardTest {
    @Test
    fun blocksOnlyTheSameCallAgainstTheSameObservation() {
        val guard = ExactActionRepeatGuard()

        guard.record("execution-1:4", "screen_act", """{"actions":[{"op":"click","x":10,"y":20}]}""")

        assertTrue(
            guard.wasExecuted(
                "execution-1:4",
                "screen_act",
                """{"actions":[{"y":20,"x":10,"op":"click"}]}"""
            )
        )
        assertFalse(
            guard.wasExecuted(
                "execution-1:5",
                "screen_act",
                """{"actions":[{"op":"click","x":10,"y":20}]}"""
            )
        )
        assertFalse(
            guard.wasExecuted(
                "execution-1:4",
                "screen_act",
                """{"actions":[{"op":"click","x":11,"y":20}]}"""
            )
        )
    }

    @Test
    fun resetAllowsTheSameCallAfterManualResume() {
        val guard = ExactActionRepeatGuard()
        val arguments = """{"actions":[{"op":"click","x":10,"y":20}]}"""
        guard.record("execution-1:4", "screen_act", arguments)

        guard.reset()

        assertFalse(guard.wasExecuted("execution-1:4", "screen_act", arguments))
    }

    @Test
    fun explicitNotExecutedSettlementReleasesOnlyTheReservedCall() {
        val guard = ExactActionRepeatGuard()
        val arguments = """{"actions":[{"op":"click","x":10,"y":20}]}"""
        guard.record("execution-1:4", "screen_act", arguments)

        guard.settle("execution-1:4", "screen_act", arguments, ToolExecutionStatus.NOT_EXECUTED)

        assertFalse(guard.wasExecuted("execution-1:4", "screen_act", arguments))
    }

    @Test
    fun everyNonNotExecutedSettlementKeepsTheCallReserved() {
        for (status in ToolExecutionStatus.entries.filter { it != ToolExecutionStatus.NOT_EXECUTED }) {
            val guard = ExactActionRepeatGuard()
            val arguments = """{"actions":[{"op":"click","x":10,"y":20}]}"""
            guard.record("execution-1:4", "screen_act", arguments)

            guard.settle("execution-1:4", "screen_act", arguments, status)

            assertTrue("$status must remain protected", guard.wasExecuted("execution-1:4", "screen_act", arguments))
        }
    }
}
