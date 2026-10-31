// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.agent

import com.droidance.core.agent.*
import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
class RetainedDecisionImageIntegrationTest {
    @Test fun originalOfAPastDecisionFrameCanBeReadWithoutRebindingCurrentControls() = runBlocking {
        val f = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val goal = "Read a retained original while keeping the current viewport"
        val started = f.startTurn("retained-native-decision", goal, activate = false)
        val oldModel = byteArrayOf(-1, -40, -1, 1)
        val oldNative = byteArrayOf(-119, 80, 78, 71, 1)
        val newModel = byteArrayOf(-1, -40, -1, 2)
        val newNative = byteArrayOf(-119, 80, 78, 71, 2)
        val saved = f.blobStore.put(oldNative)
        val rendered = f.blobStore.put(oldModel)
        f.toolExecutor.postActionCaptureTools = setOf("android_act")
        var reads = 0
        f.toolExecutor.resultOverride = { call, _ -> when (call.name) {
            "android_act" -> ToolProtocolResult.success("Launch delivered")
            "android_read" -> if (reads++ == 0) f.toolExecutor.stageObservation(oldModel, "old-frame", "Old viewport", nativeFrameBytes = oldNative)
                else f.toolExecutor.stageObservation(newModel, "new-frame", "New viewport", sequence = 2, nativeFrameBytes = newNative)
            "session_read" -> ToolProtocolResult.success("Retained original").copy(historicalAttachment = DetailAttachmentAddedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION, attachmentId = "old-original", role = DetailAttachmentRole.RESULT,
                contentRef = saved.contentRef, contentHash = saved.contentHash, sizeBytes = saved.sizeBytes, mimeType = "image/png",
                observationId = "old-frame", width = 2, height = 2, modelContentRef = rendered.contentRef, modelSizeBytes = rendered.sizeBytes), observationId = "old-frame", sourceRef = "old-frame")
            else -> null
        } }
        f.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "open", "android_act", """{"op":"open","package_name":"sample.app"}"""))
        f.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "new", "android_read", """{"op":"screen"}"""))
        f.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "past", "session_read", """{"mode":"image","ref":"old-frame"}"""))
        f.model.roundResponses += listOf(f.completionCall("finish", """{"status":"done","results":[{"text":"Original read","evidence_refs":["old-frame"]}],"unresolved_requirements":[]}"""))
        f.coordinator.coordinateTask(taskGoal = goal, additionalContext = "", durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend, executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        assertEquals(f.diagnostics.joinToString("\n"), 4, f.model.recordedRequests.size)
        val request = f.model.recordedRequests.last()
        assertTrue(request.any { it.cacheablePrefix })
        assertTrue(request.filter { it.historicalImage }.none { it.cacheablePrefix })
        assertTrue(request.filter { it.imageBase64 == Base64.getEncoder().encodeToString(newModel) }.none { it.cacheablePrefix })
        assertEquals(1, request.count { it.historicalImage && it.imageBase64 == Base64.getEncoder().encodeToString(oldModel) })
        assertArrayEquals(oldNative, f.blobStore.read(saved.contentRef, 100))
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == Base64.getEncoder().encodeToString(newModel) })
        assertEquals("new-frame", f.evidenceLedger.latestGroundedObservation()?.id)
        assertTrue(f.evidenceLedger.hasCompletionEvidence("old-frame"))
    }
    @Test fun retainedLookupNeverChangesOriginalBytesOrCurrentExecutionBinding() {
        val ledger = TaskEvidenceLedger(); ledger.startTask("Source identity")
        val hash = "a".repeat(64)
        ledger.registerImageEvidence("original", hash, "execution:one", "Current", "current", true)
        ledger.registerImageEvidence("original", hash, "retained:task", "Read", "retained", false)
        try { ledger.registerImageEvidence("original", "b".repeat(64), "retained:task", "Changed", "bad", false); fail("Changed bytes accepted") } catch (_: IllegalArgumentException) { }
        try { ledger.registerImageEvidence("original", hash, "execution:two", "Moved", "bad-scope", true); fail("Changed current binding accepted") } catch (_: IllegalArgumentException) { }
    }
}
