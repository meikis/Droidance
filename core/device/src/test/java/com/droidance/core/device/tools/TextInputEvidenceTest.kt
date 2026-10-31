// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInputEvidenceTest {
    @Test fun readbackIsBoundToPayloadEditorDisplaySessionAndReplacement() {
        val editor = "input_window=true;displayId=1;window_id=abc"
        val proof = TextInputReadback(1, "session", editor, editor, TextInputReadback.hash("sample"), true, true)
        assertTrue(proof.matches("sample", 1, "session", editor, true))
        assertFalse(proof.matches("other", 1, "session", editor, true))
        assertFalse(proof.matches("sample", 2, "session", editor, true))
        assertFalse(proof.matches("sample", 1, "new-session", editor, true))
        assertFalse(proof.matches("sample", 1, "session", editor + "other", true))
        assertFalse(proof.matches("sample", 1, "session", editor, false))
        assertFalse(proof.copy(targetBefore = "other").matches("sample", 1, "session", editor, true))
        assertFalse(proof.copy(textMatched = false).matches("sample", 1, "session", editor, true))
        assertFalse(proof.copy(targetBefore = "input_window=true", targetAfter = "input_window=true").matches("sample", 1, "session", "input_window=true", true))
    }

    @Test
    fun injectedWithoutDeliveryEvidenceIsUnverified() {
        val evidence = TextInputEvidence(
            focusConfirmed = true,
            payloadConfirmed = true,
            eventInjected = true,
            screenChanged = false,
            deliveryVerified = false
        )

        assertEquals(TextInputOutcome.DELIVERY_UNVERIFIED, evidence.outcome)
        assertTrue(evidence.accepted)
        assertFalse(evidence.succeeded)
    }

    @Test
    fun screenChangeAloneDoesNotProveTextDelivery() {
        val evidence = TextInputEvidence(
            focusConfirmed = true,
            payloadConfirmed = true,
            eventInjected = true,
            screenChanged = false,
            deliveryVerified = false
        ).withPostObservation(screenChanged = true, observationId = "obs-after")

        assertEquals(TextInputOutcome.DELIVERY_UNVERIFIED, evidence.outcome)
        assertFalse(evidence.succeeded)
        assertEquals("obs-after", evidence.observationId)
    }

    @Test
    fun destinationReadbackCanVerifyTextDelivery() {
        val evidence = TextInputEvidence(
            focusConfirmed = true,
            payloadConfirmed = true,
            eventInjected = true,
            screenChanged = false,
            deliveryVerified = false
        ).withPostObservation(
            screenChanged = true,
            observationId = "obs-after",
            destinationConfirmed = true
        )

        assertEquals(TextInputOutcome.DELIVERY_VERIFIED, evidence.outcome)
        assertTrue(evidence.succeeded)
    }

    @Test
    fun missingEditableFocusWinsOverLaterSignals() {
        val evidence = TextInputEvidence(
            focusConfirmed = false,
            payloadConfirmed = true,
            eventInjected = true,
            screenChanged = true,
            deliveryVerified = true
        )

        assertEquals(TextInputOutcome.NO_FOCUSED_INPUT_WINDOW, evidence.outcome)
        assertFalse(evidence.accepted)
        assertFalse(evidence.succeeded)
    }

    @Test
    fun unverifiedAttemptsNeverCreateALocalRetryBudget() {
        val guard = TextInputIdempotencyGuard()

        repeat(10) {
            guard.record("page", "field:min", "600", deliveryVerified = false)
        }

        assertFalse(guard.wasVerified("page", "field:min", "600"))
    }

    @Test
    fun verifiedPayloadIsIdempotentForTheSameSemanticDestination() {
        val guard = TextInputIdempotencyGuard()

        guard.record("page", "field:min", "600", deliveryVerified = true)

        assertTrue(guard.wasVerified("page", "field:min", "600"))
        assertFalse(guard.wasVerified("page", "field:min", "800"))
        assertFalse(guard.wasVerified("page", "field:max", "600"))
        assertFalse(guard.wasVerified("other-page", "field:min", "600"))
    }

    @Test
    fun resetReopensVerifiedPayloadForANewExecution() {
        val guard = TextInputIdempotencyGuard()
        guard.record("page", "field:min", "600", deliveryVerified = true)

        guard.reset()

        assertFalse(guard.wasVerified("page", "field:min", "600"))
    }
}
