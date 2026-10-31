// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ToolHealthCheckContractTest {
    @Test
    fun retiredHealthCheckToolDoesNotLeaveAHiddenRuntimeSurface() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools")
            .resolve("ToolHealthCheckRegistrar.kt")
        val executor = com.droidance.agent.executorSources()

        assertFalse(registrar.exists())
        assertFalse(executor.contains("ToolHealthCheckRegistrar"))
        assertFalse(executor.contains("MarkdownFileManager"))
        assertFalse(ToolCatalog.operatorToolNames.contains("diagnose_tools"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
