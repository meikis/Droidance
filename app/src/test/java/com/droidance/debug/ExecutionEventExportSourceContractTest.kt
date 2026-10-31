// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEventExportSourceContractTest {
    @Test
    fun runtimeHasNoParallelInMemoryTaskExperienceTrace() {
        val experienceDir = projectFile("app/src/main/java/com/droidance/agent").resolve("experience")
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val loop = com.droidance.agent.coordinationSources()

        assertFalse(experienceDir.resolve("TaskExperienceRecorder.kt").exists())
        assertFalse(experienceDir.resolve("TaskBenchmarkSummary.kt").exists())
        assertFalse(runtime.contains("TaskExperienceRecorder"))
        assertFalse(loop.contains("TaskExperienceRecorder"))
        assertTrue(runtime.contains("TurnMemoryCandidateProjector.project("))
        assertTrue(runtime.contains("eventStore.eventsForThread("))
    }

    private fun projectFile(path: String): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            File(current, path).takeIf(File::exists)?.let { return it }
            current = current.parentFile ?: error("Cannot locate ${path}")
        }
    }
}
