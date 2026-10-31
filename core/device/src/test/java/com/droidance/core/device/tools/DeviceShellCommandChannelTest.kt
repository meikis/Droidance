// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceShellCommandChannelTest {

    @Test fun truncationSurvivesBothTransportsForInventoryCompletenessChecks() {
        for (binder in listOf(true, false)) {
            val truncated = AdbShellExecutor.Result("partial", "", 0, stdoutTruncated = true)
            val channel = DeviceShellCommandChannel({ binder }, { _, _ -> truncated }, { true },
                { _, _ -> truncated }, {}, { DeviceShellCommandChannel.Backend.AIR_SHELL })
            assertTrue(channel.exec("read").stdoutTruncated)
        }
    }

    private fun result(exit: Int, out: String = "", err: String = "") =
        AdbShellExecutor.Result(out, err, exit)

    @Test
    fun prefersBinderWhenResidentBinderAliveAndAdbAdbDown() {
        var binderCalls = 0
        var adbCalls = 0
        val channel = DeviceShellCommandChannel(
            binderAvailable = { true },
            binderExec = { _, _ -> binderCalls++; result(0, "binder-out") },
            shellAvailable = { false },
            shellExec = { _, _ -> adbCalls++; result(0, "adb-out") },
            log = {},
            shellBackend = { DeviceShellCommandChannel.Backend.AIR_SHELL }
        )

        assertTrue(channel.isAvailable())
        val outcome = channel.exec("id")
        assertEquals(DeviceShellCommandChannel.Backend.BINDER, outcome.backend)
        assertEquals("binder-out", outcome.output)
        assertTrue(outcome.success)
        assertEquals(1, binderCalls)
        assertEquals(0, adbCalls)
    }

    @Test
    fun usesAdbWhenBinderUnavailable() {
        val channel = DeviceShellCommandChannel(
            binderAvailable = { false },
            binderExec = { _, _ -> fail("binder must not run when unavailable"); null },
            shellAvailable = { true },
            shellExec = { _, _ -> result(0, "adb-out") },
            log = {},
            shellBackend = { DeviceShellCommandChannel.Backend.AIR_SHELL }
        )

        val outcome = channel.exec("id")
        assertEquals(DeviceShellCommandChannel.Backend.AIR_SHELL, outcome.backend)
        assertEquals("adb-out", outcome.output)
    }

    @Test
    fun uncertainBinderDeliveryNeverReplaysThroughAdb() {
        var binderCalls = 0
        var adbCalls = 0
        val channel = DeviceShellCommandChannel(
            binderAvailable = { true },
            binderExec = { _, _ -> binderCalls++; null },
            shellAvailable = { true },
            shellExec = { _, _ -> adbCalls++; result(0, "adb-out") },
            log = {},
            shellBackend = { DeviceShellCommandChannel.Backend.AIR_SHELL }
        )

        val outcome = channel.exec("id")
        assertEquals(DeviceShellCommandChannel.Backend.BINDER, outcome.backend)
        assertEquals(1, binderCalls)
        assertEquals(0, adbCalls)
        assertTrue(outcome.stderr.contains("SHELL_DELIVERY_UNKNOWN"))
    }

    @Test
    fun structuredUnavailableWhenNeitherBackendPresent() {
        val channel = DeviceShellCommandChannel(
            binderAvailable = { false },
            binderExec = { _, _ -> null },
            shellAvailable = { false },
            shellExec = { _, _ -> fail("shell must not run when unavailable"); result(0) },
            log = {},
            shellBackend = { DeviceShellCommandChannel.Backend.AIR_SHELL }
        )

        assertFalse(channel.isAvailable())
        val outcome = channel.exec("id")
        assertEquals(DeviceShellCommandChannel.Backend.NONE, outcome.backend)
        assertFalse(outcome.available)
        assertFalse(outcome.success)
        assertEquals(-1, outcome.exitCode)
    }

    @Test
    fun logsBackendAndExitStatusButNeverCommandOutput() {
        val logs = mutableListOf<String>()
        val channel = DeviceShellCommandChannel(
            binderAvailable = { true },
            binderExec = { _, _ -> result(0, "SENSITIVE-CLIP-CONTENT") },
            shellAvailable = { false },
            shellExec = { _, _ -> result(0) },
            log = { logs.add(it) },
            shellBackend = { DeviceShellCommandChannel.Backend.AIR_SHELL }
        )

        channel.exec("id")
        assertTrue(logs.any { it.contains("backend=binder") && it.contains("exit=0") })
        assertFalse("logs must not leak command output", logs.any { it.contains("SENSITIVE-CLIP-CONTENT") })
    }
}
