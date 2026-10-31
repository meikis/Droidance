// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanHandoffPolicySourceContractTest {
    @Test fun explicitResumeIsRequiredAndPauseWaitHasNoTimeout() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val steer = runtime.substringAfter("suspend fun recordUserSteer").substringBefore("private var activeAgent")
        val wait = runtime.substringAfter("private suspend fun waitIfPaused()").substringBefore("private fun manualTakeoverWaitTimeoutMs")
        org.junit.Assert.assertFalse(steer.contains("resumeManualTakeover("))
        org.junit.Assert.assertFalse(wait.contains("withTimeout"))
        assertTrue(wait.contains("_isPaused.first { !it }"))
        assertTrue(wait.contains("agentTurnLedger.pauseForUser"))
        assertTrue(runtime.contains("PauseAwareToolExecution.run(_isPaused,"))
        val operator = com.droidance.agent.coordinationSources()
        assertTrue(operator.contains("TOOL_INTERRUPTED_FOR_HUMAN"))
        assertTrue(operator.contains("onManualTakeoverNeeded(interventionReply"))
    }

    @Test fun enlargedOutputBudgetSurvivesSuccessfulRounds() {
        val operator = com.droidance.agent.coordinationSources()
        assertTrue(operator.contains("expandedOutputBudget(modelOutputBudget"))
        assertTrue(Regex("modelOutputBudget = thresholds.llmActionMaxOutputTokens").findAll(operator).count() == 1)
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
