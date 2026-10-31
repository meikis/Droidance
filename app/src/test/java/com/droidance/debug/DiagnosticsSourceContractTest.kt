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

class DiagnosticsSourceContractTest {
    @Test
    fun llmAndToolCallsAreNotMirroredIntoDiagnosticsTaskState() {
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val operator = com.droidance.agent.coordinationSources()

        assertTrue(router.contains("private val diagnostics: TransportDiagnostics"))
        assertFalse(router.contains("diagnostics.accumulateLlmCall"))
        assertFalse(executor.contains("diagnostics.accumulateToolCall"))
        assertFalse(executor.contains("diagnostics.addStep("))
        assertFalse(operator.contains("diagnostics.recordActionOutcome("))
        assertTrue(executor.contains("ExecutionPathClassifier.classify("))
        assertFalse(executor.contains("diagnostics.recordExecutionPath("))
    }

    @Test
    fun diagnosticsCollectorKeepsOnlyFocusedOperationalEvidence() {
        val collector = projectFile("core/device/src/main/java/com/droidance/core/device/debug/DiagnosticsCollector.kt").readText()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val operator = com.droidance.agent.coordinationSources()
        val executor = com.droidance.agent.executorSources()

        assertTrue(collector.contains("recordTokenUsage("))
        assertTrue(collector.contains("recordCuaFrame("))
        assertTrue(collector.contains("recordInjection("))
        assertTrue(collector.contains("logTypeText("))
        assertTrue(runtime.contains("diagnostics.startTaskDiagnostics(request.requestId)"))
        assertTrue(runtime.contains("diagnostics.finishTaskDiagnostics(request.requestId)"))
        assertFalse(collector.contains("fun addEvent("))
        assertFalse(collector.contains("fun addStep("))
        assertFalse(collector.contains("recordRouterVerdict("))
        assertFalse(collector.contains("recordActionOutcome("))
        assertFalse(operator.contains("diagnostics.addEvent("))
        assertFalse(executor.contains("diagnostics.addStep("))
    }

    @Test
    fun envSnapshotIncludesAirPairingAndShellHealth() {
        val collector = projectFile("core/device/src/main/java/com/droidance/core/device/debug/DiagnosticsCollector.kt").readText()
        val exporter = projectFile("app/src/main/java/com/droidance/debug/DebugExportManager.kt").readText()

        assertTrue("Env snapshot should record whether AIR has a prior pairing", collector.contains("air_previously_paired"))
        assertTrue("Env snapshot should record current AIR pairing state", collector.contains("air_pairing_state"))
        assertTrue("Env snapshot should separate AIR pairing trust from connection state", collector.contains("air_pairing_trust_state"))
        assertTrue("Env snapshot should record whether a Wi-Fi transport exists", collector.contains("air_wifi_transport_available"))
        assertTrue("Env snapshot should record whether AIR shell is actually ready", collector.contains("air_shell_ready"))
        assertTrue("Env snapshot should preserve the latest AIR shell failure", collector.contains("air_shell_last_failure"))
        assertTrue("Debug export should read AIR pairing manager state", exporter.contains("private val airModePairingManager"))
        assertTrue("Debug export should include AIR shell health snapshot", exporter.contains("airModePairingManager.shellHealthSnapshot()"))
        assertTrue("Debug export should include AIR pairing trust state", exporter.contains("airShellHealth.pairingTrustState"))
        assertTrue("Debug export should include current Wi-Fi transport availability", exporter.contains("airShellHealth.wifiTransportAvailable"))
    }

    @Test
    fun debugExportIncludesAirEndpointAndCuaFrameDiagnostics() {
        val collector = projectFile("core/device/src/main/java/com/droidance/core/device/debug/DiagnosticsCollector.kt").readText()
        val exporter = projectFile("app/src/main/java/com/droidance/debug/DebugExportManager.kt").readText()
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()

        listOf(
            "air_active_endpoint",
            "air_endpoint_source",
            "air_last_probe_output",
            "cua_frame",
            "model_grid",
            "physical_size",
            "frame_scale",
            "detail_mode",
            "perception_mode"
        ).forEach { field ->
            assertTrue("Env snapshot should export $field", collector.contains(field))
        }

        assertTrue("Debug export should pass the active AIR endpoint into env snapshot", exporter.contains("airShellHealth.activeEndpoint"))
        assertTrue("ObserveScreenTool should record the latest CUA frame metadata for debug export", observe.contains("diagnostics.recordCuaFrame("))
    }

    @Test
    fun ruleConfigVersionBumpsWhenBundledRulesChange() {
        val source = projectFile("app/src/main/java/com/droidance/config/RuleConfigManager.kt").readText()

        assertTrue(source.contains("""BUILTIN_VERSION = "2026-09-14-2""""))
    }

    @Test
    fun debugExportIdentifiesPublicVersion() {
        val exporter = projectFile("app/src/main/java/com/droidance/debug/DebugExportManager.kt").readText()

        assertTrue(exporter.contains("BuildConfig.VERSION_NAME"))
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
