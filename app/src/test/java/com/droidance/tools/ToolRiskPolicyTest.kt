// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.config.ToolSafetyPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRiskPolicyTest {
    private val scope = TurnEventScope("confirmation-test", java.util.UUID.randomUUID().toString())
    private val safetyPolicy = ToolSafetyPolicy(
        confirmationTools = listOf(
            "send_sms", "make_call", "read_sms", "read_notifications",
            "set_alarm"
        ),
        shellDestructivePatterns = listOf(
            "(^|\\s)rm\\s+-",
            "(^|\\s)rm\\s+",
            "(^|\\s)settings\\s+(put|delete)\\s+"
        ),
        shellForbiddenPatterns = listOf("uiautomator\\s+dump", "accessibility"),
    )

    @Test
    fun runShellAllowsReadOnlyAutomationCommand() {
        val decision = ToolRiskPolicy.assess(
            toolName = "shell",
            arguments = """{"command":"dumpsys window"}""",
            policy = safetyPolicy, scope = scope
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun runShellAllowsGenericReadOnlyCmdQueries() {
        val settings = ToolRiskPolicy.assess(
            toolName = "shell",
            arguments = """{"command":"cmd settings get secure sms_default_application"}""",
            policy = safetyPolicy, scope = scope
        )
        val role = ToolRiskPolicy.assess(
            toolName = "shell",
            arguments = """{"command":"cmd role get-role-holders android.app.role.SMS"}""",
            policy = safetyPolicy, scope = scope
        )

        assertTrue(settings.allowed)
        assertTrue(role.allowed)
    }

    @Test
    fun runShellAllowsGeneralValidCommandsAndPipes() {
        val decision = ToolRiskPolicy.assess(
            toolName = "shell",
            arguments = """{"command":"dumpsys window | grep -i focus && echo ok"}""",
            policy = safetyPolicy, scope = scope
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun runShellBlocksForbiddenUiTreeReads() {
        val decision = ToolRiskPolicy.assess(
            toolName = "shell",
            arguments = """{"command":"uiautomator dump /sdcard/ui.xml"}""",
            policy = safetyPolicy, scope = scope
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("UI-tree reads and simulated input are blocked by policy"))
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun configuredShellPolicyBlocksSimulatedInputButNotInputReads() {
        val config = java.io.File(projectRoot(), "app/src/main/assets/rules/harness_policy.yaml").readLines()
        val forbidden = config.dropWhile { it.trim() != "shell_forbidden_patterns:" }.drop(1)
            .takeWhile { it.startsWith("    ") }.map { it.trim() }.filter { it.startsWith("- '") }
            .map { it.removePrefix("- '").removeSuffix("'") }
        val policy = safetyPolicy.copy(shellForbiddenPatterns = forbidden)
        for (command in listOf("input tap 990 185", "input swipe 500 1200 500 400 300", "input keyevent 4",
            "input text hello", "input -d 0 tap 1 1", "sleep 1 && input tap 1 1", "cmd input tap 1 1",
            "input touchscreen swipe 1 1 2 2", "sendevent /dev/input/event2 3 57 0")) {
            val decision = ToolRiskPolicy.assess("shell", """{"command":"$command"}""", policy, scope)
            assertFalse(command, decision.allowed)
            assertFalse(command, decision.requiresConfirmation)
            assertTrue(decision.reason.contains("act through screen_act on the bound display"))
        }
        for (command in listOf("dumpsys input", "getevent -p", "settings get secure default_input_method", "ime list -s")) {
            assertTrue(command, ToolRiskPolicy.assess("shell", """{"command":"$command"}""", policy, scope).allowed)
        }
    }

    private fun projectRoot(): java.io.File {
        var current = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!java.io.File(current, "settings.gradle.kts").isFile) current = checkNotNull(current.parentFile)
        return current
    }

    @Test
    fun runShellBlocksDestructiveCommand() {
        val decision = ToolRiskPolicy.assess(
            toolName = "shell",
            arguments = """{"command":"rm -rf /sdcard/Download"}""",
            policy = safetyPolicy, scope = scope
        )

        assertFalse(decision.allowed)
        assertTrue(decision.requiresConfirmation)
        assertTrue(decision.reason.contains("\"execution_status\":\"NOT_EXECUTED\""))
        assertTrue(decision.reason.contains("\"code\":\"HIGH_RISK_CONFIRMATION_REQUIRED\""))
    }

    @Test
    fun confirmationAllowsExactlyOneHighRiskOperation() {
        val blocked = ToolRiskPolicy.assess(
            toolName = "set_alarm",
            arguments = """{"hour":8,"minute":0}""",
            policy = safetyPolicy, scope = scope
        )
        assertFalse(blocked.allowed)
        ToolRiskPolicy.confirmNextHighRisk(scope)

        val first = ToolRiskPolicy.assess(
            toolName = "set_alarm",
            arguments = """{"hour":8,"minute":0}""",
            policy = safetyPolicy, scope = scope
        )
        val second = ToolRiskPolicy.assess(
            toolName = "set_alarm",
            arguments = """{"hour":9,"minute":0}""",
            policy = safetyPolicy, scope = scope
        )

        assertTrue(first.allowed)
        assertFalse(second.allowed)
    }

    @Test
    fun confirmationCannotBeReusedForDifferentArguments() {
        ToolRiskPolicy.assess(
            toolName = "set_alarm",
            arguments = """{"hour":8,"minute":0}""",
            policy = safetyPolicy, scope = scope
        )
        ToolRiskPolicy.confirmNextHighRisk(scope)
        val different = ToolRiskPolicy.assess(
            toolName = "set_alarm",
            arguments = """{"hour":9,"minute":0}""",
            policy = safetyPolicy, scope = scope
        )
        assertFalse(different.allowed)
    }
}
