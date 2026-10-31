// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolRegistrationAndDispatchContractTest {

    private fun projectFile(path: String): File {
        var current = File(System.getProperty("user.dir") ?: ".")
        while (current.parentFile != null) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        return File(path)
    }

    @Test
    fun shadowHandlersAndDualRegistryAreRemovedFromToolExecutor() {
        val executorFile = projectFile("app/src/main/java/com/droidance/tools/ToolExecutor.kt")
        assertTrue(executorFile.exists())
        val executorSource = executorFile.readText()

        // Verify toolDispatcher is no longer duplicated inside ToolExecutor
        assertFalse(
            "ToolExecutor should not instantiate duplicate ToolDispatcher",
            executorSource.contains("private val toolDispatcher")
        )
        assertFalse(
            "ToolExecutor should not call registerDomainHandlers",
            executorSource.contains("registerDomainHandlers()")
        )
        assertFalse(
            "ToolExecutor should not double-register into toolDispatcher",
            executorSource.contains("toolDispatcher.register(")
        )

        // Verify the 5 shadow handlers are completely deleted
        val handlerDir = projectFile("app/src/main/java/com/droidance/tools/handler")
        assertFalse("Shadow handler directory should be removed", handlerDir.exists())
    }

    @Test
    fun toolExecutorDirectlyDispatchesViaUnifiedRegistry() {
        val executorSource = com.droidance.agent.executorSources()

        // Tools are registered directly into the shared registry
        assertTrue(executorSource.contains("fun register(tool: Tool)"))
        assertTrue(executorSource.contains("toolRegistry.register(tool)"))

        // Lookup occurs directly from the shared registry
        assertTrue(executorSource.contains("val tool = toolRegistry.getTool(toolCall.name)"))
        assertTrue(executorSource.contains("code = \"TOOL_NOT_FOUND\""))
    }
}
