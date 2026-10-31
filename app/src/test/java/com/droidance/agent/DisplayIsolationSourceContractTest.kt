// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayIsolationSourceContractTest {
    @Test
    fun virtualObservationUsesFrozenDisplayAndSidecarSession() {
        val context = file("core/agent/src/main/kotlin/com/droidance/core/agent/device/ExecutionContext.kt")
        val verifier = file("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt")
        val observe = file("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt")
        val executor = file("app/src/main/java/com/droidance/tools/ToolExecutor.kt")

        assertTrue(context.contains("backendSessionId"))
        assertTrue(verifier.contains("executionContextStore.requireCurrent()"))
        assertTrue(verifier.contains("sidecarBridge.getForegroundComponent"))
        assertTrue(verifier.contains("context.backendSessionId"))
        assertFalse(observe.contains("localActionVerifier.currentForegroundPackageName()"))
        assertFalse(executor.contains("executionContextStore.current() ?: resolveFreshExecutionContext()"))
    }

    private fun file(path: String): String {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate.readText()
            current = current.parentFile ?: error("Project file not found: $path")
        }
    }
}
