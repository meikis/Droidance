// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.program

import com.droidance.core.device.program.*

import com.droidance.program.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.device.agent.device.ManagedShellCommands
import com.droidance.core.device.agent.device.SystemStateQuery
import com.droidance.core.device.tools.SystemQueryDiagnostic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ManagedShellCommandsInstrumentedTest {
    @Test fun multilineLoopsPipelinesAndPrintedProtocolTextRemainOrdinaryOutput() {
        val result = ManagedShellCommands().execute(UUID.randomUUID().toString(),
            "for value in first second; do printf '%s\\n' \"\$value\"; done | cat\nprintf '__PCSEP__[SHELL_OUTPUT_TRUNCATED]'\n", 5000)
        assertEquals("SUCCEEDED", result.executionStatus)
        assertEquals("first\nsecond\n__PCSEP__[SHELL_OUTPUT_TRUNCATED]", result.stdout)
        assertFalse(result.stdoutTruncated)
    }

    @Test fun oversizedOutputIsDrainedWithSeparateTransportMetadata() {
        val result = ManagedShellCommands().execute(UUID.randomUUID().toString(),
            "head -c 200000 /dev/zero | tr '\\000' x; printf error >&2", 5000)
        assertEquals("SUCCEEDED", result.executionStatus)
        assertEquals(200000L, result.stdoutBytes)
        assertTrue(result.stdoutTruncated)
        assertEquals(96 * 1024, result.stdout.length)
        assertEquals("error", result.stderr)
    }

    @Test fun typedQueryPreservesAPlatformFailureExcludedByLiteralSelection() {
        val shell = ManagedShellCommands()
        val result = shell.executeSystem(UUID.randomUUID().toString(),
            SystemStateQuery("droidance_nonexistent_service", lineFilter = "literal-with-no-match"), 5000)
        val output = requireNotNull(result.systemOutput)
        assertEquals(0, output.selectedLines)
        assertEquals("", output.stdout)
        assertEquals("SYSTEM_QUERY_SERVICE_UNAVAILABLE",
            output.diagnosticCode ?: SystemQueryDiagnostic.code("", result.stderr))
    }

    @Test fun outputAndExitCodeArePreservedAndIdentityCannotReplay() {
        val shell = ManagedShellCommands()
        val id = UUID.randomUUID().toString()
        val result = shell.execute(id, "printf hello; printf problem >&2; exit 7", 5000)
        assertEquals(7, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("problem", result.stderr)
        assertEquals("FAILED", result.executionStatus)
        assertEquals("SHELL_REVOKED", shell.execute(id, "echo replay", 5000).diagnosticCode)
    }
    @Test fun cancelAndTimeoutStopOrdinaryDescendantsBeforeDelayedEffect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (timeout in listOf(false, true)) {
            val file = File(context.cacheDir, "shell-test-" + UUID.randomUUID())
            val shell = ManagedShellCommands()
            val id = UUID.randomUUID().toString()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<ManagedShellCommands.Result> {
                    shell.execute(id, "(sleep 2; echo leaked > '${file.absolutePath}') & wait", if (timeout) 250 else 5000)
                }
                if (!timeout) { Thread.sleep(250); shell.cancel(id) }
                val result = future.get(5, TimeUnit.SECONDS)
                assertEquals(-1, result.exitCode)
                assertEquals(if (timeout) "INTERRUPTED" else "CANCELED", result.executionStatus)
                Thread.sleep(2200)
                assertFalse("descendant survived cancellation/timeout", file.exists())
            } finally { shell.cancel(id); executor.shutdownNow(); file.delete() }
        }
    }
    @Test fun cancellationBeforeDispatchDoesNotExecute() {
        val shell = ManagedShellCommands()
        val id = UUID.randomUUID().toString()
        shell.cancel(id)
        val result = shell.execute(id, "echo should-not-run", 5000)
        assertEquals("SHELL_REVOKED", result.diagnosticCode)
        assertEquals("NOT_EXECUTED", result.executionStatus)
        assertEquals("", result.stdout)
    }
}
