// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.debug.DebugLogAppender
import com.droidance.core.agent.debug.recordRuntimeFailure
import org.junit.Assert.*
import org.junit.Test

class RuntimeFailureDiagnosticsTest {
    @Test fun retainedStackLocatesTheCauseWithoutCopyingExceptionMessages() {
        var recorded = ""
        val appender = object : DebugLogAppender {
            override fun appendEvent(event: String) = Unit
            override fun appendPersistentEvent(stream: String, event: String) { recorded = "$stream: $event" }
        }
        val failure = IllegalStateException("credential-sentinel", java.io.IOException("private-provider-body"))
        appender.recordRuntimeFailure("reference-123", "phase=operator turn=turn-1", failure)
        assertTrue(recorded.contains("runtime-failures"))
        assertTrue(recorded.contains("reference-123"))
        assertTrue(recorded.contains("IllegalStateException"))
        assertTrue(recorded.contains("IOException"))
        assertTrue(recorded.contains("RuntimeFailureDiagnosticsTest"))
        assertFalse(recorded.contains("credential-sentinel"))
        assertFalse(recorded.contains("private-provider-body"))
    }
}
