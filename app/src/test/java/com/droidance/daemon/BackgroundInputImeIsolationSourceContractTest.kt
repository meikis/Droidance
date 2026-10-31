// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class BackgroundInputImeIsolationSourceContractTest {
    @Test
    fun executionInputDoesNotSwitchTheGlobalIme() {
        val input = File("../core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()

        assertFalse(input.contains("imeEnsureReady"))
        assertFalse(input.contains("ime enable"))
        assertFalse(input.contains("ime set"))
    }

    @Test
    fun taskCleanupDoesNotNeedAnImeRestoreHook() {
        val executor = com.droidance.agent.executorSources()
        val runtime = File("src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        assertFalse(executor.contains("restoreInputMethod"))
        assertFalse(runtime.contains("toolExecutor.restoreInputMethod()"))
    }

}
