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
    fun stopCommandSignalsParsedProcessGroupsAndSupportsForceFallback() {
        val graceful = ResidentSidecarProcessCommands.stopCommand(listOf(4312, 4313, 4312), force = false)!!
        val forced = ResidentSidecarProcessCommands.stopCommand(listOf(4312), force = true)!!

        assertTrue(graceful.contains("/proc/4312/stat"))
        assertTrue(graceful.contains("kill -- -\$g"))
        assertTrue(graceful.contains("kill 4313 2>/dev/null"))
        assertEquals(1, Regex("/proc/4312/stat").findAll(graceful).count())
        assertTrue(forced.contains("kill -9 -- -\$g"))
        assertTrue(forced.contains("kill -9 4312 2>/dev/null"))
        assertEquals(null, ResidentSidecarProcessCommands.stopCommand(emptyList(), force = true))
    }

    @Test
    fun supervisorScriptCannotBeMistakenForASidecarProcess() {
        val probe = Regex("VirtualSpaceSidecarMain|droidance-sidecar-protocol=")
        val command = launchCommand()
        val supervisorArgs = command.substringAfter("setsid sh -c ").substringBefore(" </dev/null")

        assertTrue(probe.containsMatchIn(command))
        assertFalse(probe.containsMatchIn(supervisorArgs))
    }

    @Test
    fun supervisorRestartsOnlyAbnormalExitsWhileTheInstallIsCurrent() {
        val command = launchCommand()

        assertTrue(command.contains("[ \$rc -eq 0 ] && break"))
        assertTrue(command.contains("supervisor: sidecar exited rc=\$rc"))
        assertTrue(command.contains(">>/data/local/tmp/droidance_vspace.log"))
        assertTrue(command.contains("[ -f \"\$CLASSPATH\" ] || break"))
        assertTrue(command.contains("development_settings_enabled"))
        assertTrue(command.contains("sleep 5"))
    }

    private fun launchCommand() = ResidentSidecarProcessCommands.launchCommand(
        apkPath = "/data/app/base.apk",
        mainClass = "com.droidance.core.device.agent.device.VirtualSpaceSidecarMain",
        width = 1080,
        height = 2400,
        densityDpi = 420,
        packageName = "com.droidance",
        protocolVersion = 21,
        escape = { "'$it'" }
    )
}
