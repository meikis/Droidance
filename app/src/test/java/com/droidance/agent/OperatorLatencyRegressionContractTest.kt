// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.tools.ToolCatalog
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorLatencyRegressionContractTest {
    @Test
    fun terminalFinishReasonBoundsTheFinalUsageDrain() {
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()
        val stream = router.substringAfter("private fun streamOpenAICompatible(")
            .substringBefore("private class RequestCancelledBeforeRegistrationException")

        assertTrue(stream.contains("choice?.get(\"finish_reason\")"))
        assertTrue(stream.contains("usageState.observe(usage, reportedFinishReason != null)"))
        assertTrue(stream.contains("usageState.needsFinalUsageChunk"))
        assertTrue(stream.contains("FINAL_USAGE_GRACE_MS"))
        assertTrue(stream.contains("SocketTimeoutException"))
    }

    @Test
    fun operatorTimingSeparatesStreamEventKindsAndPreparationPhases() {
        val collector = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText()
        val coordinator = com.droidance.agent.coordinationSources()

        assertTrue(collector.contains("\"first_stream_event_ms\" to firstEventMs.toString()"))
        listOf(
            "first_content_event_ms",
            "first_tool_event_ms",
            "first_reasoning_event_ms",
            "first_metadata_event_ms",
            "last_stream_event_ms",
            "response_cached_input_tokens"
        ).forEach { assertTrue("missing stream metric $it", collector.contains("\"$it\"")) }
        assertTrue(collector.contains("cachedInputTokens?.let"))
        assertTrue(collector.contains("streamProgress.cachedInputTokens?.toString().orEmpty()"))
        assertTrue(coordinator.contains("System.nanoTime()"))
        listOf(
            "preparation_observation_ms",
            "preparation_retained_images_ms",
            "preparation_current_evidence_ms",
            "preparation_context_other_ms"
        ).forEach { assertTrue("missing preparation metric $it", coordinator.contains("\"$it\"")) }
    }

    @Test
    fun modelRoundsUseOneOutputBudget() {
        val operator = com.droidance.agent.coordinationSources()
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertTrue(policy.contains("llm_action_max_output_tokens: 12288"))
        assertTrue(policy.contains("llm_retry_max_output_tokens: 12288"))
        assertTrue(operator.contains("thresholds.llmActionMaxOutputTokens"))
        assertFalse(operator.contains("thresholds.llmFinalMaxOutputTokens"))
        assertFalse(policy.contains("llm_final_max_output_tokens"))
        assertTrue(router.contains("maxOutputTokens?.takeIf { it > 0 }?.let { put(\"max_tokens\", it) }"))
    }

    @Test
    fun operatorSurfaceIsSmallAndRecallsSkillsDirectly() {
        assertTrue("recall_skill" in ToolCatalog.operatorToolNames)
        assertTrue("session_search" in ToolCatalog.operatorToolNames)
        assertTrue("verify_skill" in ToolCatalog.operatorToolNames)
        assertFalse("query_deep_links" in ToolCatalog.operatorToolNames)
        assertFalse("read_notifications" in ToolCatalog.operatorToolNames)
        assertTrue("manage_app_takeover" in ToolCatalog.operatorToolNames)
        assertFalse("wait_for_content" in ToolCatalog.operatorToolNames)
        assertTrue("update_memory" in ToolCatalog.operatorToolNames)
        assertTrue("session_read" in ToolCatalog.operatorToolNames)
        assertTrue("program" in ToolCatalog.operatorToolNames)
    }

    @Test
    fun longTaskContextUsesConfiguredCheckpointAndImageBudgets() {
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertTrue(policy.contains("context_budget_percent: 90"))
        assertTrue(policy.contains("context_recent_image_limit: 0"))
        assertFalse(policy.contains("context_max_retained_messages"))
    }

    @Test
    fun responsesTransportResetsProviderHistoryAfterDurableCheckpoint() {
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()
        val operator = com.droidance.agent.coordinationSources()

        assertTrue(router.contains("resetResponsesSessionAfterContextCheckpoint"))
        assertFalse(router.contains("resetResponsesSessionForFinalReply"))
        assertTrue(router.contains("responsesTurnState = OpenAIResponsesTurnState()"))
        assertTrue(operator.contains("llmRouter.resetResponsesSessionAfterContextCheckpoint()"))
        assertFalse(operator.contains("llmRouter.resetResponsesSessionForFinalReply()"))
    }

    @Test
    fun modelRoundsReuseKnownFramesWithoutParallelDiagnosticsTimingState() {
        val operator = com.droidance.agent.coordinationSources()
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()
        val diagnostics = projectFile(
            "core/device/src/main/java/com/droidance/core/device/debug/DiagnosticsCollector.kt"
        ).readText()

        assertFalse(operator.contains("pendingObservation?.ocrEvaluated == true"))
        assertFalse(operator.contains("streamCapture.firstActionableAt"))
        assertFalse(diagnostics.contains("recordModelRoundTiming"))
        assertFalse(diagnostics.contains("request_body_bytes"))
        assertFalse(router.contains("recordLlmTransportTiming"))
    }

    @Test
    fun backgroundRunPrefetchesItsFirstScreenOnlyAfterFrozenBinding() {
        val operator = com.droidance.agent.coordinationSources()
        val prefetch = operator.substringAfter("if (initialScreenRequired && rounds == 1")
            .substringBefore("val pendingObservationReady")

        assertTrue(prefetch.contains("ensureDurableExecutionBinding(repairPurpose)"))
        assertTrue(prefetch.contains("freshObservationRequired = true"))
        assertTrue(operator.contains("toolExecutor.beginFrozenExecutionContext("))
        assertTrue(operator.contains("retryModelRequest(\"LLM_TIMEOUT_RETRY\", e)"))
        assertTrue(operator.contains("retryModelRequest(\"TRANSPORT_TIMEOUT_RETRY\", e)"))
    }

    @Test
    fun emptyFramesStayRecoverableAndRuntimeSurfaceFollowsTheActiveSession() {
        val observe = projectFile(
            "app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt"
        ).readText()
        val surface = projectFile(
            "app/src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt"
        ).readText()

        assertTrue(observe.contains("code = \"OBSERVATION_UNAVAILABLE\""))
        assertFalse(observe.contains("frameChannelUnavailable"))
        assertTrue(surface.contains("private fun currentBinding(): SidecarSessionBinding? = virtualSpaceManager.activeBinding()"))
        assertFalse(surface.contains("RuntimeMode.ULTRA"))
    }

    @Test
    fun exactRepeatGuardUsesCanonicalFullArguments() {
        val operator = com.droidance.agent.coordinationSources()
        val guard = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/ExactActionRepeatGuard.kt").readText()

        assertTrue(operator.contains("arguments = tc.arguments"))
        assertTrue(guard.contains("canonicalize(Json.parseToJsonElement(arguments))"))
        assertFalse(guard.contains("coordinate bucket"))
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
