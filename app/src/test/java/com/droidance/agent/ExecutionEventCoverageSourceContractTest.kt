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

class ExecutionEventCoverageSourceContractTest {
    private val root = locateProjectRoot()

    @Test
    fun runtime_operator_executor_use_typed_events_without_hidden_reasoning() {
        val runtime = source("app/src/main/java/com/droidance/agent/AgentRuntime.kt")
        val operator = com.droidance.agent.coordinationSources()
        val executor = com.droidance.agent.executorSources()
        val home = source("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt")

        assertFalse(runtime.contains("executionEventJournal"))
        assertFalse(operator.contains("executionEventJournal"))
        val session = source("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundSession.kt")
        val committer = source("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundCommitter.kt")
        assertTrue(operator.contains("roundSession.commit("))
        assertTrue(session.contains("committer.commit("))
        assertTrue(session.contains("coordinator.commit("))
        assertTrue(committer.contains("coordinator.commit("))
        assertFalse(session.contains("executionEventJournal"))
        assertFalse(committer.contains("executionEventJournal"))
        assertTrue(operator.contains("put(\"records\", evidence.records)"))
        assertFalse(operator.contains("recordLlmRoundDetail("))
        assertFalse(operator.contains("ToolCallDetailStore"))
        assertFalse(executor.contains("ToolCallDetailStore"))
        assertFalse(File(root, "app/src/main/java/com/droidance/tools/ToolCallDetailStore.kt").exists())
        assertFalse(executor.contains("executionEventJournal"))
        assertFalse(home.contains("onExecutionEvent ="))
        assertTrue(home.contains("eventStore.observeThreadProjectionResult(conversationId)"))
        assertFalse(home.contains("ConversationManager"))
        assertFalse(home.contains("saveConversation("))
        assertFalse(listOf(runtime, operator, home).any { it.contains("onStepLog") })
    }

    @Test
    fun postActionObservationIsAttachedToTheSettledDeviceCall() {
        val operator = com.droidance.agent.coordinationSources()
        val persistence = operator.substringAfter("suspend fun persistPendingPostActionEvidence(")
            .substringBefore("fun appendGuardFeedbackToRecentToolResult(")
        val settlement = operator.substringAfter("suspend fun settleDurableTool(")
            .substringBefore("suspend fun processToolCalls()")
        val decisionFrame = operator.substringAfter("val decisionFrameBytes =")
            .substringBefore("val providerToolCalls =")
        val requestBoundary = operator.substringAfter("val decisionFrameBytes =")
            .substringBefore("operatorStreamCollector.collect(")

        assertTrue(persistence.contains("DetailAttachmentRole.CURRENT_OBSERVATION"))
        assertTrue(persistence.contains("attachmentId ="))
        assertTrue(persistence.contains("observation:"))
        assertTrue(persistence.contains("eventStore.addDetailAttachment("))
        assertTrue(persistence.contains("pendingPostActionAttachmentScope = null"))
        assertTrue(persistence.contains("event.eventKind == com.droidance.core.agent.event.AgentEventKind.DETAIL_ATTACHMENT_ADDED"))
        assertTrue(persistence.contains(".observationId == observationId"))
        assertTrue(settlement.contains("pendingPostActionAttachmentScope = ToolLifecycleEventScope("))
        assertTrue(settlement.contains("attemptId = attempt?.scope?.attemptId"))
        assertTrue(settlement.contains("checkNotNull(attempt)"))
        assertTrue(settlement.contains("call.contract.name in recoveryPolicy.refreshAfterTools"))
        assertTrue(settlement.contains("expectsPostActionImage"))
        assertTrue(settlement.contains("mayChangeDisplay"))
        assertTrue(decisionFrame.contains("persistPendingPostActionEvidence("))
        assertTrue(decisionFrame.contains("frameBytes = pendingObservation?.nativeImagePng"))
        assertTrue(decisionFrame.contains("frameText = decisionFrameText"))
        assertTrue(
            requestBoundary.indexOf("persistPendingPostActionEvidence(") <
                requestBoundary.indexOf("beginDurableRound(repairPurpose)")
        )
    }

    @Test
    fun operatorMaterializesScreenshotBoundFactsBeforeOldScreensLeaveContext() {
        val operator = com.droidance.agent.coordinationSources() + "\n" +
            source("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorEvidenceRecorder.kt")
        val runtime = source("app/src/main/java/com/droidance/agent/AgentRuntime.kt")
        val extractor = source("core/agent/src/main/kotlin/com/droidance/core/agent/EvidenceExtractorAgent.kt")
        val rules = source("app/src/main/assets/rules/harness_policy.yaml")

        assertTrue(operator.contains("private val evidenceFactExtractor: EvidenceFactExtractor"))
        assertTrue(operator.contains("evidenceFactExtractor.extractInline(taskGoal, observation)"))
        assertTrue(operator.contains("evidenceLedger.addFacts("))
        assertFalse(operator.contains("evidenceFactExtractor.extract("))
        assertTrue(operator.contains("toolName = \"model_observation\""))
        assertTrue(runtime.contains("materializeOperatorEvidence(intentSourceText)"))
        assertTrue(runtime.contains("evidenceFactExtractor.extractInline(taskGoal, observation)"))
        assertTrue(extractor.contains("fun extractInline("))
        assertTrue(extractor.contains("EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION"))
        assertTrue(extractor.contains("!observation.screenshotRef.isNullOrBlank()"))
        assertFalse(rules.contains("- model_observation"))
    }

    @Test
    fun old_string_protocol_and_failure_marker_are_absent() {
        assertFalse(File(root, "app/src/main/java/com/droidance/agent/RuntimeStepLogFormatter.kt").exists())
        val production = File(root, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertFalse(production.contains("[STEP_FAIL]"))
        assertFalse(production.contains("The task stopped before completion could be confirmed."))
    }

    @Test
    fun toolExecutorExceptionsUseStructuredEnglishProtocol() {
        val executor = com.droidance.agent.executorSources()
        val failureBlock = executor.substringAfter("} catch (e: CancellationException) {")
            .substringBefore("private fun shouldAutoCaptureAfterTool")

        assertTrue(failureBlock.contains("code = \"TOOL_EXECUTION_CANCELLED\""))
        assertTrue(failureBlock.contains("code = \"TOOL_EXECUTION_FAILED\""))
        assertTrue(failureBlock.contains("ToolProtocolResult.notExecuted("))
        assertTrue(failureBlock.contains("ToolProtocolResult.failed("))
        assertFalse(failureBlock.contains("context.getString("))
        assertFalse(failureBlock.contains("R.string.execution_cancelled"))
        assertFalse(failureBlock.contains("R.string.execution_tool_failed"))
    }

    @Test
    fun terminal_fallbacks_exist_in_both_locales() {
        val zh = source("app/src/main/res/values-zh/strings.xml")
        val en = source("app/src/main/res/values/strings.xml")
        listOf(
            "execution_completion_not_confirmed",
            "execution_cancelled",
            "execution_takeover_timeout"
        ).forEach { key ->
            assertTrue("missing zh resource $key", zh.contains("name=\"$key\""))
            assertTrue("missing en resource $key", en.contains("name=\"$key\""))
        }
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun locateProjectRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("project root not found")
    }
}
