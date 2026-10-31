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

class DurableRoundExecutionContextRegressionTest {
    @Test
    fun laterModelRoundsRestoreTheTurnActiveAttemptInsteadOfAllocatingAnotherExecution() {
        val coordinator = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/harness/DurableModelRoundCoordinator.kt"
        ).readText()

        assertTrue(coordinator.contains("eventStore.latestExecutionAttemptForTurn(turnScope)"))
        assertTrue(coordinator.contains("bindingPreparer.restoreExisting("))
        assertTrue(coordinator.contains("ExecutionAttemptState.CREATED"))
        assertTrue(coordinator.contains("ExecutionAttemptState.RUNNING"))
    }

    @Test
    fun modelRoundStartDoesNotPrepareAnAndroidBackendBeforeADeviceToolIsChosen() {
        val coordinator = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/harness/DurableModelRoundCoordinator.kt"
        ).readText()
        val begin = coordinator.substringAfter("suspend fun begin(")
            .substringBefore("suspend fun prepareDeviceBinding(")
        val lazyBinding = coordinator.substringAfter("suspend fun prepareDeviceBinding(")

        assertFalse(begin.contains("bindingPreparer.prepareNew("))
        assertFalse(begin.contains("bindingPreparer.restoreExisting("))
        assertTrue(lazyBinding.contains("bindingPreparer.prepareNew("))
        assertTrue(lazyBinding.contains("bindingPreparer.restoreExisting("))
        assertTrue(coordinator.contains("bindingResolution = AttemptBindingResolution.Missing"))
    }

    @Test
    fun observationCannotPromoteAnyToolToTaskCompletion() {
        val operator = com.droidance.agent.coordinationSources()

        assertFalse(operator.contains("groundedObservationCompletionResult"))
        assertFalse(operator.contains("context.groundsCompletion || context.hasFreshGroundedObservation"))
    }

    @Test
    fun executionAttemptIsSettledAtTurnBoundaryInsteadOfAfterEveryTool() {
        val operator = com.droidance.agent.coordinationSources()
        val turnLedger = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/AgentTurnLedger.kt"
        ).readText()

        assertFalse(operator.contains("attemptStateOverride"))
        assertTrue(turnLedger.contains("settleExecutionAttempt("))
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
