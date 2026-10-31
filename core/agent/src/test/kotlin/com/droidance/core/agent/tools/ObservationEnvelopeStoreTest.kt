// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ObservationEnvelopeStoreTest {
    @Test fun nativeEvidenceAndModelEncodingRemainIndependentInOneObservation() {
        val store = ObservationEnvelopeStore()
        val token = store.beginCapture("execution", "backend", 4, 0, "observe")
        val native = byteArrayOf(1, 2, 3)
        assertTrue(store.publish(token, 1L, "model-jpeg", "vision", 0.5f,
            540 to 1188, 1080 to 2376, 0 to 0, nativeImagePng = native,
            nativeFrameSha256 = "native-hash", nativeVisualFingerprint = 42L))
        native[0] = 9
        val observation = checkNotNull(store.consume("execution", "backend", 4))
        assertEquals("model-jpeg", observation.imageBase64)
        assertArrayEquals(byteArrayOf(1, 2, 3), observation.nativeImagePng)
        assertEquals(540 to 1188, observation.frameSize)
        assertEquals(1080 to 2376, observation.physicalFrameSize)
        assertEquals("native-hash", observation.nativeFrameSha256)
        assertEquals(42L, observation.nativeVisualFingerprint)
        assertNull(store.consume("execution", "backend", 4))
    }

    @Test fun aTextOnlyObservationCannotCarryAnUnrelatedNativeFrameIdentity() {
        val store = ObservationEnvelopeStore()
        val token = store.beginCapture("execution", "backend", 4, 0, "read")
        assertTrue(store.publish(token, 1, null, "text", 1f, null, null, 0 to 0,
            screenText = "current text", nativeFrameSha256 = "old-hash", nativeVisualFingerprint = 42))
        val observation = checkNotNull(store.consume("execution", "backend", 4))
        assertNull(observation.nativeFrameSha256)
        assertNull(observation.nativeVisualFingerprint)
    }

    @Test
    fun repeatedActionCapturesHaveIndependentObservationIdentities() {
        val store = ObservationEnvelopeStore()
        val first = store.beginCapture("execution", "backend", 0, 0, "same-action")
        val second = store.beginCapture("execution", "backend", 0, 0, "same-action")
        assertFalse(first.observationId == second.observationId)
        assertEquals(first.actionId, second.actionId)
        val restarted = ObservationEnvelopeStore().beginCapture("execution", "backend", 0, 0, "same-action")
        assertFalse(second.observationId == restarted.observationId)
    }

    @Test
    fun normalizedActionLabelsCannotAliasObservationIdentity() {
        val store = ObservationEnvelopeStore()
        val first = store.beginCapture("execution", "backend", 0, 0, "Action.One")
        val second = store.beginCapture("execution", "backend", 0, 0, "action_one")
        assertFalse(first.observationId == second.observationId)
        assertEquals("Action.One", first.actionId)
        assertEquals("action_one", second.actionId)
    }

    @Test
    fun olderAsyncCaptureCannotReplaceLatestActionObservation() {
        val store = ObservationEnvelopeStore()
        val old = store.beginCapture("execution", "backend", 0, 0, "action_old")
        val latest = store.beginCapture("execution", "backend", 0, 0, "action_latest")

        assertTrue(
            publish(
                store,
                latest,
                "latest",
                0.5f,
                500 to 900,
                readableText = "latest readable",
                screenText = "latest screen"
            )
        )
        assertFalse(
            publish(
                store,
                old,
                "old",
                1f,
                1000 to 1800,
                readableText = "old readable",
                screenText = "old screen"
            )
        )

        val observation = store.consume("execution", "backend", 0)
        assertEquals("latest", observation?.imageBase64)
        assertEquals("action_latest", observation?.actionId)
        assertEquals(latest.observationId, observation?.observationId)
        assertFalse(old.observationId == latest.observationId)
        assertEquals(0.5f, observation?.frameScale)
        assertEquals(500 to 900, observation?.frameSize)
        assertEquals("latest readable", observation?.readableText)
        assertEquals("latest screen", observation?.screenText)
    }

    @Test
    fun imageAndCoordinateMetadataAreConsumedTogetherOnce() {
        val store = ObservationEnvelopeStore()
        val token = store.beginCapture("execution", "backend", 0, 90, "observe")
        publish(store, token, "frame", 0.75f, 750 to 1200)

        val observation = store.consume("execution", "backend", 0)

        assertEquals("frame", observation?.imageBase64)
        assertEquals(0.75f, observation?.frameScale)
        assertEquals(750 to 1200, observation?.frameSize)
        assertEquals(90, observation?.rotationDegrees)
        assertNull(store.consume("execution", "backend", 0))
    }

    @Test
    fun readableTextAndScreenTextTravelWithTheSameFrame() {
        val store = ObservationEnvelopeStore()
        val token = store.beginCapture("execution", "backend", 0, 0, "observe")
        assertTrue(store.publish(
            token = token,
            capturedAtElapsedMs = 1L,
            imageBase64 = "frame",
            mode = "vision",
            frameScale = 1f,
            frameSize = 100 to 200,
            physicalFrameSize = 100 to 200,
            frameOrigin = 0 to 0,
            readableText = "visible text",
            screenText = "screen text"
        ))

        val observation = store.consume("execution", "backend", 0)

        assertEquals("visible text", observation?.readableText)
        assertEquals("screen text", observation?.screenText)
    }

    @Test
    fun screenTextIsConsumedWhenReadableTextIsAbsent() {
        val store = ObservationEnvelopeStore()
        val token = store.beginCapture("execution", "backend", 0, 0, "observe")

        assertTrue(
            store.publish(
                token = token,
                capturedAtElapsedMs = 2L,
                imageBase64 = null,
                mode = "text",
                frameScale = 1f,
                frameSize = 100 to 200,
                physicalFrameSize = 100 to 200,
                frameOrigin = 0 to 0,
                readableText = null,
                screenText = "current screen text"
            )
        )

        val observation = store.consume("execution", "backend", 0)

        assertEquals("current screen text", observation?.screenText)
        assertNull(observation?.readableText)
    }

    @Test
    fun observationCannotCrossBackendSessionOrDisplayBinding() {
        val store = ObservationEnvelopeStore()
        val token = store.beginCapture("execution", "backend-a", 4, 270, "observe")
        assertTrue(publish(store, token, "frame", 1f, 1000 to 1800))

        assertNull(store.peek("execution", "backend-b", 4))
        assertNull(store.peek("execution", "backend-a", 0))
        assertNull(store.consume("execution", "backend-b", 4))

        val observation = store.consume("execution", "backend-a", 4)
        assertEquals("backend-a", observation?.backendSessionId)
        assertEquals(4, observation?.displayId)
        assertEquals(270, observation?.rotationDegrees)
        assertEquals("frame", observation?.imageBase64)
    }

    @Test
    fun invalidationRejectsCaptureFromPreviousExecutionLifecycle() {
        val store = ObservationEnvelopeStore()
        val token = store.beginCapture("execution_old", "backend", 0, 0, "action")
        store.invalidate()

        assertFalse(publish(store, token, "stale", 1f, null))
        assertNull(store.peek("execution_old", "backend", 0))
    }

    private fun publish(
        store: ObservationEnvelopeStore,
        token: ObservationEnvelopeStore.CaptureToken,
        image: String,
        scale: Float,
        size: Pair<Int, Int>?,
        readableText: String? = null,
        screenText: String? = null
    ): Boolean = store.publish(
        token = token,
        capturedAtElapsedMs = 1L,
        imageBase64 = image,
        mode = "vision",
        frameScale = scale,
        frameSize = size,
        physicalFrameSize = size,
        frameOrigin = 0 to 0,
        readableText = readableText,
        screenText = screenText
    )
}
