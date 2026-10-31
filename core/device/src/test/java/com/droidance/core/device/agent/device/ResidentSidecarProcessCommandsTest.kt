// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidentSidecarProcessCommandsTest {
    @Test
    fun parsesOnlyValidatedShellSidecarPidsFromProcessProbe() {
        val output = """
            4312
            noise
            4313
            [AIR_SHELL_TIMEOUT]
            -8
        """.trimIndent()

        assertEquals(listOf(4312, 4313), ResidentSidecarProcessCommands.parsePids(output))
    }

    @Test
    fun processProbeTargetsOnlyTheRequestedSidecarIdentityWithoutMatchingItself() {
        val shellCommand = ResidentSidecarProcessCommands.listPidsCommand(2000)

        assertTrue(shellCommand.contains("ps -A -o UID,PID,ARGS"))
        assertTrue(shellCommand.contains("[V]irtualSpaceSidecarMain"))
        assertTrue(shellCommand.contains("shell"))
        assertTrue(shellCommand.contains("2000"))
        assertFalse(shellCommand.contains("root"))
        assertFalse(shellCommand.contains("pgrep"))
    }

    @Test
    fun currentProtocolProbeDoesNotLetAnOldUnkillableSidecarBlockStartup() {
        val command = ResidentSidecarProcessCommands.listPidsCommand(
            uid = 2000,
            protocolVersion = 21
        )

        assertTrue(command.contains("[V]irtualSpaceSidecarMain"))
        assertTrue(command.contains("[d]roidance-sidecar-protocol=21"))
        assertFalse(command.contains("pgrep"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun processProbeRejectsPrivilegedIdentity() {
        ResidentSidecarProcessCommands.listPidsCommand(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun processProbeRejectsUnsupportedIdentity() {
        ResidentSidecarProcessCommands.listPidsCommand(10000)
    }

    @Test
    fun stopCommandUsesOnlyParsedNumericPidsAndSupportsForceFallback() {
        assertEquals(
            "kill 4312 2>/dev/null; kill 4313 2>/dev/null",
            ResidentSidecarProcessCommands.stopCommand(listOf(4312, 4313, 4312), force = false)
        )
        assertEquals(
            "kill -9 4312 2>/dev/null; kill -9 4313 2>/dev/null",
            ResidentSidecarProcessCommands.stopCommand(listOf(4312, 4313), force = true)
        )
        assertEquals(null, ResidentSidecarProcessCommands.stopCommand(emptyList(), force = true))
    }
}
