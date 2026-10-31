// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.*
import org.junit.Test

class DecisionImageIdentityTest {
    private val hash = "a".repeat(64)
    private fun record(ledger: TaskEvidenceLedger, id: String, contentHash: String = hash, scope: String = "turn:device:display") =
        ledger.registerImageEvidence(id, contentHash, scope, "Visible frame fixture", "frame:$contentHash", isCurrentState = true)

    @Test fun retainedPagesPreserveCurrentStateAndRequireOriginalImagesForCompletion() {
        val ledger = TaskEvidenceLedger()
        record(ledger, "current")
        ledger.registerImageEvidence("page-1", "b".repeat(64), "scope", "Collected page", "blob-page-1", isCurrentState = false)
        assertEquals("current", ledger.latestGroundedObservation()?.id)
        assertTrue(ledger.hasCompletionEvidence("page-1"))
        assertEquals(setOf("page-1"), ledger.completionImageSources("page-1"))
        ledger.registerImageEvidence("page-1", "b".repeat(64), "scope", "Collected page", "blob-page-1", isCurrentState = false)
        assertEquals("current", ledger.latestGroundedObservation()?.id)
    }

    @Test fun originalV11InputObservationIdsRemainUsableAfterPayloadEviction() {
        val ledger = TaskEvidenceLedger()
        val ids = listOf("call_c5e6023a-e55f-4d48-82f1-bf4030967ba2", "call_ed5f1938-71aa-4fe9-9476-083fafee5219")
        ids.forEach { assertEquals(it, record(ledger, it)) }
        repeat(300) { ledger.recordObservation(toolName = "read", rawText = "fixture $it", sourceKind = EvidenceSourceKind.OBSERVED_STATE) }
        ids.forEach { assertEquals(it, record(ledger, it)); assertTrue(ledger.hasCompletionEvidence(it)) }
        assertEquals(ids.toSet(), ledger.completionImageSources(ids.joinToString(",")))
        assertFalse(ledger.hasCompletionEvidence("invented"))
        ledger.startTask("Another task")
        assertFalse(ledger.hasCompletionEvidence(ids.first()))
        assertTrue(ledger.completionImageSources(ids.joinToString(",")).isEmpty())
    }

    @Test fun modelReadbackRequiresItsActualOriginalImageIncludingReusedFrames() {
        val ledger = TaskEvidenceLedger()
        ledger.registerImageEvidence("frame", hash, "scope", "pixels", "detail1", isCurrentState = true)
        ledger.registerImageEvidence("frame", hash, "scope", "pixels", "detail2", isCurrentState = true)
        val first = ledger.recordObservation(toolName = "model_screen_evidence", rawText = "reading",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION, screenshotRef = "detail1", isCurrentState = true)
        val second = ledger.recordObservation(toolName = "model_screen_evidence", rawText = "reading again",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION, screenshotRef = "detail2", isCurrentState = true)
        assertEquals(setOf("frame"), ledger.completionImageSources("${first.id},${second.id}"))
    }

    @Test fun sameIdentityCannotSwitchPixelsOrExecutionScope() {
        val ledger = TaskEvidenceLedger()
        record(ledger, "frame")
        assertThrows(IllegalArgumentException::class.java) { record(ledger, "frame", "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { record(ledger, "frame", scope = "other-display") }
    }

    @Test fun anActionReceiptOrModelAssertionCannotBePromotedByReusingItsId() {
        for (kind in listOf(EvidenceSourceKind.ACTION_RECEIPT, EvidenceSourceKind.MODEL_OUTPUT)) {
            val ledger = TaskEvidenceLedger()
            ledger.recordObservation(observationId = "receipt", toolName = "act", rawText = "claim", sourceKind = kind)
            assertThrows(IllegalArgumentException::class.java) { record(ledger, "receipt") }
            assertFalse(ledger.hasCompletionEvidence("receipt"))
        }
    }
}
