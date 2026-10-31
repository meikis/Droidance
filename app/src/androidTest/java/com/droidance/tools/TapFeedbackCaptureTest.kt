// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.ToolExecutionStatus
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapFeedbackCaptureTest {
    @Test fun retainsActualSubmittedTouchAndLeavesOriginalAndRingCenterUntouched() {
        val bytes = source()
        val original = bytes.copyOf()
        val blobs = InMemoryBlobStore()
        val capture = TapFeedbackCapture("tap-call", frame(bytes))
        capture.record(DeviceInputResult(true, "delivered", ExecutedActionEvent.Tap(90, 140)),
            TapRequest(500, 500, "crop", ModelCoordinateMapper.Point(85, 135)))
        val result = capture.attach(ToolProtocolResult.accepted("delivery only"), blobs)
        assertArrayEquals(original, bytes)
        val tap = result.tapCoordinates.single()
        assertEquals(85, tap.mappedPhysicalX)
        assertEquals(90, tap.injectedPhysicalX)
        assertEquals(140, tap.injectedPhysicalY)
        assertEquals("crop", tap.coordinateRef)
        val image = result.actionFeedback.single()
        assertArrayEquals(original, blobs.read(requireNotNull(image.sourceContentRef), 1000000))
        assertEquals("decision", image.sourceObservationId)
        assertNotEquals(image.sourceContentRef, image.contentRef)
        val markedBytes = blobs.read(image.contentRef, 1000000)
        val marked = BitmapFactory.decodeByteArray(markedBytes, 0, markedBytes.size)
        try {
            assertEquals(Color.WHITE, marked.getPixel(90, 140))
            assertTrue((82..98).any { x -> (132..148).any { y -> Color.red(marked.getPixel(x, y)) > 150 && Color.green(marked.getPixel(x, y)) < 100 } })
        } finally { marked.recycle() }
        // Feedback cannot upgrade delivery to semantic success or discard cancellation.
        assertEquals(result.evidenceStatus, ToolProtocolResult.accepted("delivery only").evidenceStatus)
        val canceled = capture.attach(ToolProtocolResult.canceled("TEST_CANCELLED"), blobs)
        assertEquals(ToolExecutionStatus.CANCELED, canceled.executionStatus)
        assertEquals(tap, canceled.tapCoordinates.single())
    }

    @Test fun rejectedAndUnconfirmedInputsProduceNoInventedTouch() {
        val capture = TapFeedbackCapture("empty", frame(source()))
        capture.record(DeviceInputResult(false, "rejected", ExecutedActionEvent.Tap(90, 140)), null)
        capture.record(DeviceInputResult(true, "unknown location"), null)
        val result = capture.attach(ToolProtocolResult.notExecuted("STALE_OBSERVATION", true), InMemoryBlobStore())
        assertTrue(result.tapCoordinates.isEmpty())
        assertTrue(result.actionFeedback.isEmpty())
    }

    @Test fun rendersTheRetainedFailureScreenshotWhenSuppliedWithoutChangingItsBytes() {
        val path = InstrumentationRegistry.getArguments().getString("tap_fixture") ?: return
        val original = File(path).readBytes()
        val capture = TapFeedbackCapture("filter-failure", frame(original).copy(physicalFrameSize = 1080 to 2376))
        capture.record(DeviceInputResult(true, "submitted", ExecutedActionEvent.Tap(1015, 969)),
            TapRequest(940, 408, null, ModelCoordinateMapper.Point(1015, 969)))
        val blobs = InMemoryBlobStore()
        val feedback = capture.attach(ToolProtocolResult.accepted("submitted"), blobs).actionFeedback.single()
        assertArrayEquals(original, File(path).readBytes())
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "tap-feedback-audit.png")
            .writeBytes(blobs.read(feedback.contentRef, 8388608))
    }

    private fun frame(bytes: ByteArray) = ObservationEnvelope("decision", "execution", "backend", 64, 0,
        "observe", 1, 1, null, "pure_vlm", 1f, 200 to 300, 200 to 300, 0 to 0, nativeImagePng = bytes)

    private fun source(): ByteArray {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            return output.toByteArray()
        } finally { bitmap.recycle() }
    }
}
