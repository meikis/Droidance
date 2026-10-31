// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.BatchScreenshotEvidence
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.harness.PendingDetailAttachment
import com.droidance.core.agent.harness.materializeDetailAttachments
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Shared by the sequential calls of one model response, not reset for each batch. */
class ScreenBatchCaptureBudget(private val policy: ScreenActionBatchPolicy) {
    var images: Int = 0
        private set
    var bytes: Long = 0
        private set
    var largestObservedCaptureBytes: Long = 0
        private set
    fun observe(size: Long) { require(size > 0); largestObservedCaptureBytes = maxOf(largestObservedCaptureBytes, size) }
    fun hasCapacity() = images < policy.maxCaptureImages && bytes < policy.maxCaptureBytes
    fun accepts(size: Long) = size > 0 && hasCapacity() && size <= policy.maxCaptureBytes - bytes
    fun canStartCollectionScroll() = hasCapacity() &&
        (largestObservedCaptureBytes == 0L || accepts(largestObservedCaptureBytes))

    fun record(size: Long) {
        check(accepts(size))
        observe(size)
        images++
        bytes += size
    }

    fun facts(): JsonObject = buildJsonObject {
        put("scope", "current_model_response")
        put("capture_scope", "explicit_batch_screenshots")
        put("max_images", policy.maxCaptureImages); put("max_bytes", policy.maxCaptureBytes)
        put("used_images", images); put("used_bytes", bytes)
        put("remaining_images", policy.maxCaptureImages - images); put("remaining_bytes", policy.maxCaptureBytes - bytes)
        put("largest_observed_capture_bytes", largestObservedCaptureBytes)
        put("byte_accounting", "retained_native_plus_distinct_model_image")
    }

    companion object {
        fun nextResponseFacts(policy: ScreenActionBatchPolicy, observedCaptureBytes: Long): JsonObject = buildJsonObject {
            put("scope", "next_model_response")
            put("capture_scope", "explicit_batch_screenshots")
            put("remaining_images", policy.maxCaptureImages); put("remaining_bytes", policy.maxCaptureBytes)
            put("largest_recent_capture_bytes", observedCaptureBytes)
            put("estimate_available", observedCaptureBytes > 0)
            if (observedCaptureBytes > 0) put("estimated_images_fitting", minOf(policy.maxCaptureImages.toLong(), policy.maxCaptureBytes / observedCaptureBytes))
            put("estimate_is_guarantee", false)
            put("byte_accounting", "retained_native_plus_distinct_model_image")
        }
    }
}

/** Invocation-local evidence survives interruption; only immutable refs enter event payloads. */
class ScreenBatchCaptures(
    val callId: String,
    private val budget: ScreenBatchCaptureBudget,
    private val blobs: BlobStore
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScreenBatchCaptures>
    private val saved = mutableListOf<BatchScreenshotEvidence>()
    private var retainedBytes = 0L
    val screenshots: List<BatchScreenshotEvidence> get() = saved.toList()
    fun hasCapacity() = budget.hasCapacity()

    /** Stop before moving to a page whose evidence is unlikely to fit this response. */
    fun beforeCollectionScroll(): ToolExecutionResult? = if (budget.canStartCollectionScroll()) null else
        ToolProtocolResult.notExecuted("BATCH_CAPTURE_CAPACITY", true, details = buildJsonObject {
            put("reason", "next_capture_estimate_exceeds_remaining_budget")
            put("estimated_capture_bytes", budget.largestObservedCaptureBytes)
            put("estimate_is_guarantee", false)
            put("capture_budget", budget.facts())
        })

    suspend fun save(
        actionIndex: Int, capturedAtMs: Long, executionId: String, backendSessionId: String,
        displayId: Int, rotationDegrees: Int, bytes: ByteArray, width: Int, height: Int,
        modelImageBytes: ByteArray? = null
    ): ToolExecutionResult {
        val imageLimit = com.droidance.core.agent.HistoricalObservationImages.MAX_IMAGE_BYTES
        if (bytes.size > imageLimit || (modelImageBytes?.size ?: 0) > imageLimit) {
            return ToolProtocolResult.notExecuted("CAPTURE_IMAGE_EXCEEDS_LIMIT", false, details = buildJsonObject {
                put("max_image_bytes", imageLimit)
                put("native_bytes", bytes.size)
                put("model_bytes", modelImageBytes?.size ?: 0)
            })
        }
        // Both views belong to one logical capture, but both retained blobs consume bytes.
        val size = bytes.size.toLong() +
            (modelImageBytes?.takeUnless { it.contentEquals(bytes) }?.size ?: 0)
        budget.observe(size)
        if (!budget.accepts(size)) return ToolProtocolResult.notExecuted("BATCH_CAPTURE_CAPACITY", true,
            details = buildJsonObject { put("requested_capture_bytes", size); put("capture_budget", budget.facts()) })
            .copy(metrics = mapOf("capture_largest_observed_bytes" to budget.largestObservedCaptureBytes))
        val id = "$callId:capture:$actionIndex"
        // Complete a local blob write and register its ref together. Cancellation cannot lose
        // a captured prefix between disk persistence and the enclosing tool's settlement.
        withContext(NonCancellable) {
            val attachment = materializeDetailAttachments(blobs, listOf(PendingDetailAttachment(
                id, DetailAttachmentRole.RESULT, bytes, encodedImageMimeType(bytes), width, height, id,
                modelImageBytes = modelImageBytes
            ))).single()
            saved += BatchScreenshotEvidence(actionIndex, capturedAtMs, executionId, backendSessionId,
                displayId, rotationDegrees, attachment)
            budget.record(size)
            retainedBytes += size
        }
        return ToolProtocolResult.success("Captured page evidence: $id")
    }

    fun attach(result: ToolExecutionResult): ToolExecutionResult {
        if (result.metrics["capture_budget_used_images"] == budget.images.toLong() &&
            result.metrics["capture_budget_used_bytes"] == budget.bytes &&
            result.metrics["capture_largest_observed_bytes"] == budget.largestObservedCaptureBytes &&
            result.batchScreenshots == screenshots) return result
        val screenshotText = if (saved.isEmpty() || result.batchScreenshots == screenshots) "" else
            "\n[batch_screenshots; ordered evidence, not current target bindings]\n" +
                JsonArray(saved.map { frame -> buildJsonObject {
                    put("action_index", frame.actionIndex)
                    put("captured_at_ms", frame.capturedAtMs)
                    put("source_ref", frame.attachment.observationId)
                    put("content_ref", frame.attachment.contentRef)
                } })
        // The next round context always carries fresh budget facts. Repeat them here only when
        // this response consumed or probed the budget, which later calls in it share.
        val budgetText = if (budget.images == 0 && budget.largestObservedCaptureBytes == 0L) "" else
            "\n[capture_batch_budget]\n" + budget.facts()
        return result.copy(
            batchScreenshots = screenshots,
            outputText = result.outputText + screenshotText + budgetText,
            metrics = result.metrics + mapOf(
                "batch_captured_images" to saved.size.toLong(),
                "batch_captured_bytes" to retainedBytes,
                "capture_budget_used_images" to budget.images.toLong(),
                "capture_budget_used_bytes" to budget.bytes,
                "capture_largest_observed_bytes" to budget.largestObservedCaptureBytes
            )
        )
    }
}

/** Decision frames arrive as JPEG; other captures may still be PNG. */
internal fun encodedImageMimeType(bytes: ByteArray): String =
    if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte())
        "image/jpeg" else "image/png"
