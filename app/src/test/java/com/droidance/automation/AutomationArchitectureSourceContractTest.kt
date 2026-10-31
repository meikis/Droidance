// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationArchitectureSourceContractTest {
    @Test
    fun recurringCalendarAutomationUsesOneShotAlarmsInsteadOfPeriodicWorkers() {
        val scheduler = projectFile(
            "app/src/main/java/com/droidance/automation/AutomationAlarmScheduler.kt"
        ).readText()

        assertTrue(scheduler.contains("setExactAndAllowWhileIdle"))
        assertTrue(scheduler.contains("setAndAllowWhileIdle"))
        assertTrue(scheduler.contains("canScheduleExactAlarms"))
        assertFalse(scheduler.contains("PeriodicWorkRequest"))
        assertFalse(scheduler.contains("WorkManager"))
    }

    @Test
    fun automationExecutesThroughTheExistingAgentAndNeverThroughScripts() {
        val coordinator = projectFile(
            "app/src/main/java/com/droidance/automation/AutomationCoordinator.kt"
        ).readText()

        assertTrue(coordinator.contains("agentRuntime.processAutomationInput("))
        assertTrue(coordinator.contains("DeviceExecutionGuard"))
        assertFalse(coordinator.contains("ScriptExecutor"))
        assertFalse(coordinator.contains("LLMRouter"))
    }

    @Test
    fun deliveryRetryIsASeparateLaneFromDeviceExecution() {
        val coordinator = projectFile(
            "app/src/main/java/com/droidance/automation/AutomationCoordinator.kt"
        ).readText()
        val deliveryLane = coordinator.substringAfter("private fun deliver(")

        assertTrue(deliveryLane.contains("TaskExecutionService.notifyTaskCompleted"))
        assertTrue(deliveryLane.contains("TaskExecutionService.notifyTaskInterrupted"))
        assertFalse(deliveryLane.contains("processAutomationInput"))
        assertFalse(deliveryLane.contains("enqueueScheduledOccurrence"))
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
