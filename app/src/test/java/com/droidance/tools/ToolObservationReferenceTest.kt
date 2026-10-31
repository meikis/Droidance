// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ToolExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolObservationReferenceTest {
    @Test
    fun groundedNativeReceiptHasOneStableReferenceForWireAndLedger() {
        val raw = "[ANDROID_CONTENT_RESULT]\n{\"rows\":[{\"value\":\"2984\"}]}"
        val rawResult = ToolProtocolResult.success(raw)
        val wire = ToolObservationReference.forGroundedCall(rawResult, true, "turn", "internal:call:0")
        val reference = ToolObservationReference.fromResult(wire)
        assertTrue(reference != null)
        assertEquals(ToolExecutionStatus.SUCCEEDED, wire.executionStatus)
        assertEquals(wire, ToolObservationReference.forGroundedCall(rawResult, true, "turn", "internal:call:0"))
        assertFalse(wire == ToolObservationReference.forGroundedCall(rawResult, true, "other-turn", "internal:call:0"))
        val ledger = com.droidance.core.agent.TaskEvidenceLedger()
        ledger.recordObservation(observationId = reference, toolName = "generic_read", rawText = wire.outputText,
            sourceKind = com.droidance.core.agent.EvidenceSourceKind.OBSERVED_STATE)
        assertTrue(ledger.hasCompletionEvidence(reference))
    }

    @Test
    fun ungroundedAndFailedCallsCannotAcquireAnObservationReference() {
        val receipt = ToolProtocolResult.success("[ACTIVITY_LAUNCHED]")
        assertSame(receipt, ToolObservationReference.forGroundedCall(receipt, false, "turn", "call"))
        assertNull(ToolObservationReference.fromResult(receipt))

        val failed = ToolProtocolResult.failed("FAILED", retryable = false)
        assertSame(failed, ToolObservationReference.forGroundedCall(failed, true, "turn", "call"))
        assertNull(ToolObservationReference.fromResult(failed))

        val unverified = ToolProtocolResult.accepted("[NATIVE_ACTION_ACCEPTED_UNVERIFIED]")
        assertSame(unverified, ToolObservationReference.forGroundedCall(unverified, true, "turn", "call"))
        assertNull(ToolObservationReference.fromResult(unverified))
    }

    @Test
    fun successfulScreenResultCarriesThePublishedObservationId() {
        val source = ToolProtocolResult.success("[SCREEN_STATE]\nwidth=1080 height=2376")
        val result = ToolObservationReference.attachToSuccessfulResult(
            result = source,
            observationId = "frame_42"
        )

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("frame_42", result.observationId)
        assertEquals("frame_42", result.sourceRef)
        assertEquals("frame_42", ToolObservationReference.fromResult(result))
    }

    @Test
    fun failedResultDoesNotClaimAnObservation() {
        val result = ToolProtocolResult.failed("SCREEN_CAPTURE_FAILED", retryable = true)

        val identified = ToolObservationReference.attachToSuccessfulResult(result, "frame_42")

        assertSame(result, identified)
        assertEquals(ToolExecutionStatus.FAILED, identified.executionStatus)
        assertNull(identified.observationId)
        assertFalse(identified.outputText.contains("observation_id="))
    }

    @Test
    fun outputTextObservationIdCannotSupplyTypedObservationMetadata() {
        val result = ToolProtocolResult.success("[SCREEN_STATE]\nobservation_id=existing_frame")

        assertNull(ToolObservationReference.fromResult(result))
        val attached = ToolObservationReference.attachToSuccessfulResult(result, "replacement_frame")

        assertEquals("replacement_frame", attached.observationId)
        assertEquals("replacement_frame", attached.sourceRef)
        assertTrue(attached.outputText.contains("observation_id=existing_frame"))
    }

    @Test
    fun existingTypedObservationReferenceIsPreserved() {
        val result = ToolProtocolResult.success("[SCREEN_STATE]").copy(observationId = "existing_frame")

        assertSame(result, ToolObservationReference.attachToSuccessfulResult(result, "replacement_frame"))
        assertEquals("existing_frame", ToolObservationReference.fromResult(result))
    }
}
