// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Invocation-local feedback always draws submitted touches on the immutable pre-action source. */
class TapFeedbackCapture(private val callId: String, private val source: ObservationEnvelope?) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TapFeedbackCapture>
    private val taps = mutableListOf<TapCoordinates>()

    fun record(result: DeviceInputResult, request: TapRequest?) {
        val tap = result.actionEvent as? ExecutedActionEvent.Tap ?: return
        val frame = source ?: return
        if (!result.success) return
        taps += TapCoordinates(taps.size + 1, request?.x, request?.y, request?.coordinateRef,
            request?.physical?.x, request?.physical?.y, tap.x, tap.y, frame.observationId,
            frame.executionId, frame.backendSessionId, frame.displayId, frame.rotationDegrees)
    }

    fun attach(result: ToolExecutionResult, blobs: BlobStore): ToolExecutionResult {
        if (taps.isEmpty()) return result
        val coordinates = taps.toList()
        var feedback: DetailAttachmentAddedEventPayload? = null
        try {
            source?.nativeImagePng?.let { original ->
                val sourceBlob = blobs.put(original)
                val marked = TapFeedbackRenderer.render(original, coordinates)
                val blob = blobs.put(marked.bytes)
                feedback = DetailAttachmentAddedEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION, "$callId:tap-feedback", DetailAttachmentRole.RESULT,
                    blob.contentRef, blob.contentHash, blob.sizeBytes, "image/png", marked.width, marked.height,
                    "$callId:tap-feedback", source.observationId, sourceBlob.contentRef,
                    ImageSourceRegion(0, 0, marked.width, marked.height, marked.width, marked.height))
            }
        } catch (_: Exception) {
            // Encoding failure must preserve known delivery and its coordinates.
        }
        return result.copy(tapCoordinates = coordinates, actionFeedback = listOfNotNull(feedback),
            outputText = result.outputText + "\n[tap_coordinates; physical pixels submitted by executor, not a platform hit-test]\n" +
                Json.encodeToString(coordinates) + (feedback?.let {
                    "\n[tap_feedback image_ref=${it.contentRef}; pre-action source=${it.sourceObservationId}; " +
                        "numbered red rings mark submitted touches, not targets; historical image, not current screen]"
                } ?: "\n[tap_feedback image unavailable; coordinates retained]"),
            metrics = result.metrics + mapOf("tap_feedback_images" to if (feedback == null) 0L else 1L))
    }
}

object TapFeedbackRenderer {
    data class Image(val bytes: ByteArray, val width: Int, val height: Int)

    fun render(original: ByteArray, taps: List<TapCoordinates>): Image {
        val source = BitmapFactory.decodeByteArray(original, 0, original.size) ?: error("TAP_SOURCE_DECODE_FAILED")
        val marked = source.copy(Bitmap.Config.ARGB_8888, true) ?: error("TAP_SOURCE_COPY_FAILED")
        try {
            val canvas = Canvas(marked)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = (minOf(marked.width, marked.height) * 0.012f).coerceAtLeast(6f)
            taps.forEach { tap ->
                val x = tap.injectedPhysicalX.toFloat(); val y = tap.injectedPhysicalY.toFloat()
                // A hollow ring leaves the control and exact center visible.
                paint.style = Paint.Style.STROKE
                paint.color = Color.WHITE; paint.strokeWidth = 6f
                canvas.drawCircle(x, y, radius, paint)
                paint.color = Color.rgb(220, 30, 45); paint.strokeWidth = 3f
                canvas.drawCircle(x, y, radius, paint)
                paint.style = Paint.Style.FILL; paint.textSize = radius * 1.5f
                val labelX = (x + radius + 3).coerceAtMost(marked.width - radius * 2)
                val labelY = (y - radius - 3).coerceAtLeast(paint.textSize)
                paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                canvas.drawText(tap.tapIndex.toString(), labelX, labelY, paint)
                paint.color = Color.rgb(220, 30, 45); paint.style = Paint.Style.FILL
                canvas.drawText(tap.tapIndex.toString(), labelX, labelY, paint)
            }
            val output = ByteArrayOutputStream()
            check(marked.compress(Bitmap.CompressFormat.PNG, 100, output))
            return Image(output.toByteArray(), marked.width, marked.height)
        } finally { marked.recycle(); source.recycle() }
    }
}
