// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class ScreenBatchCapturesTest {
    private fun policy(count: Int = 3, bytes: Long = 100) = ScreenActionBatchPolicy(
        true, ScreenActionBatchProtocol.operations, 10, 1, 20, 20, count, bytes, 64)

    @Test fun aggregateBudgetCanHoldSeveralFullSizeImagesWhileEachBlobRemainsReadable() = runBlocking {
        val configured = policy(10, 32 * 1024 * 1024L)
        configured.validate()
        val budget = ScreenBatchCaptureBudget(configured)
        val collector = ScreenBatchCaptures("large-group", budget, InMemoryBlobStore())
        // Retained native + model sizes from the first six pages of the archived task.
        listOf(1561490L, 1806666L, 1713437L, 1828019L, 1778329L, 1701247L).forEach(budget::record)
        assertEquals(6, budget.images)
        assertTrue(budget.bytes > 8 * 1024 * 1024L)
        assertNull(collector.beforeCollectionScroll())
        val oversized = ByteArray(com.droidance.core.agent.HistoricalObservationImages.MAX_IMAGE_BYTES.toInt() + 1)
        val result = collector.save(1, 1, "execution", "backend", 1, 0, oversized, 1, 1)
        assertEquals("CAPTURE_IMAGE_EXCEEDS_LIMIT", result.diagnosticCode)
        assertEquals("CAPTURE_IMAGE_EXCEEDS_LIMIT", collector.save(1, 1, "execution", "backend", 1, 0,
            byteArrayOf(1), 1, 1, modelImageBytes = oversized).diagnosticCode)
        assertEquals(6, budget.images)
        assertTrue(collector.screenshots.isEmpty())
    }

    @Test fun nativeAndModelBlobsKeepIndependentRefsAndShareOneCaptureBudget() = runBlocking {
        val budget = ScreenBatchCaptureBudget(policy(3, 10))
        val blobs = InMemoryBlobStore()
        val collector = ScreenBatchCaptures("call", budget, blobs)
        val native = byteArrayOf(1, 2, 3, 4)
        val model = byteArrayOf(5, 6, 7)
        assertTrue(collector.save(1, 1000, "execution", "backend", 41, 0,
            native, 1080, 2376, modelImageBytes = model).isVerifiedSuccess)
        val attachment = collector.screenshots.single().attachment
        assertArrayEquals(native, blobs.read(attachment.contentRef, 10))
        assertArrayEquals(model, blobs.read(checkNotNull(attachment.modelContentRef), 10))
        assertEquals("image/png", attachment.mimeType)
        assertEquals(1080, attachment.width); assertEquals(2376, attachment.height)
        assertEquals(1, budget.images); assertEquals(7L, budget.bytes)
        assertEquals(7L, collector.attach(ToolProtocolResult.success("ok")).metrics["batch_captured_bytes"])
        assertEquals("BATCH_CAPTURE_CAPACITY", collector.save(2, 1001, "execution", "backend", 41, 0,
            native, 1080, 2376, modelImageBytes = model).diagnosticCode)
        assertEquals(1, budget.images); assertEquals(7L, budget.bytes)
    }

    @Test fun identicalNativeAndModelBlobConsumesRetainedBytesOnce() = runBlocking {
        val budget = ScreenBatchCaptureBudget(policy(1, 3))
        val blobs = InMemoryBlobStore()
        val collector = ScreenBatchCaptures("call", budget, blobs)
        val png = byteArrayOf(1, 2, 3)
        assertTrue(collector.save(1, 1000, "execution", "backend", 41, 0,
            png, 2, 2, modelImageBytes = png.copyOf()).isVerifiedSuccess)
        assertEquals(3L, budget.bytes)
        val attachment = collector.screenshots.single().attachment
        assertNull(attachment.modelContentRef)
        assertNull(attachment.modelSizeBytes)
        assertEquals(setOf(attachment.contentRef), blobs.listAllRefs())
    }

    @Test fun capacityIsSharedAcrossCallsAndAnOversizeImageDoesNotConsumeIt() = runBlocking {
        val budget = ScreenBatchCaptureBudget(policy(3, 10))
        val blobs = InMemoryBlobStore()
        val first = ScreenBatchCaptures("first", budget, blobs)
        assertTrue(save(first, 1, 4).isVerifiedSuccess)
        assertEquals("BATCH_CAPTURE_CAPACITY", save(first, 3, 7).diagnosticCode)
        assertEquals(1, budget.images)
        assertEquals(4L, budget.bytes)
        val second = ScreenBatchCaptures("second", budget, blobs)
        assertTrue(save(second, 1, 3).isVerifiedSuccess)
        assertTrue(save(second, 3, 3).isVerifiedSuccess)
        assertFalse(first.hasCapacity())
        assertFalse(second.hasCapacity())
        assertEquals("BATCH_CAPTURE_CAPACITY", save(second, 5, 1).diagnosticCode)
        assertEquals(listOf("first:capture:1"), first.screenshots.map { it.attachment.observationId })
        assertEquals(listOf("second:capture:1", "second:capture:3"), second.screenshots.map { it.attachment.observationId })
    }

    @Test fun countLimitStopsEvenWhenBytesRemainAndFailedReceiptKeepsTypedEvidence() = runBlocking {
        val budget = ScreenBatchCaptureBudget(policy(2))
        val collector = ScreenBatchCaptures("call", budget, InMemoryBlobStore())
        assertTrue(save(collector, 1, 1).isVerifiedSuccess)
        assertTrue(save(collector, 3, 1).isVerifiedSuccess)
        assertFalse(collector.hasCapacity())
        val failed = collector.attach(ToolProtocolResult.failed("LATER_ACTION_FAILED", true))
        assertEquals(ToolExecutionStatus.FAILED, failed.executionStatus)
        assertEquals(listOf(1, 3), failed.batchScreenshots.map { it.actionIndex })
        assertEquals(failed, collector.attach(failed))
        assertEquals(2L, failed.metrics["batch_captured_images"])
        assertFalse(failed.outputText.contains(java.util.Base64.getEncoder().encodeToString(ByteArray(99))))
    }

    @Test fun byteBudgetReceiptsIncludeRefusedImageSizeAndNextResponseStartsFresh() = runBlocking {
        val budget = ScreenBatchCaptureBudget(policy(10, 10))
        val collector = ScreenBatchCaptures("call", budget, InMemoryBlobStore())
        assertTrue(save(collector, 1, 6).isVerifiedSuccess)
        val refusal = save(collector, 3, 7)
        assertEquals("BATCH_CAPTURE_CAPACITY", refusal.diagnosticCode)
        assertTrue(refusal.outputText.contains("\"requested_capture_bytes\":7"))
        val receipt = collector.attach(refusal)
        assertTrue(receipt.outputText.contains("\"remaining_bytes\":4"))
        assertTrue(receipt.outputText.contains("\"remaining_images\":9"))
        assertEquals(7L, receipt.metrics["capture_largest_observed_bytes"])
        assertEquals(receipt, collector.attach(receipt))
        val next = ScreenBatchCaptureBudget.nextResponseFacts(policy(10, 10), 7)
        assertEquals(10L, next.getValue("remaining_bytes").jsonPrimitive.long)
        assertEquals(1L, next.getValue("estimated_images_fitting").jsonPrimitive.long)
        assertFalse(next.getValue("estimate_is_guarantee").jsonPrimitive.boolean)
        val empty = ScreenBatchCaptures("empty", ScreenBatchCaptureBudget(policy()), InMemoryBlobStore())
            .attach(ToolProtocolResult.success("No capture requested"))
        assertFalse(empty.outputText.contains("capture_batch_budget"))
        assertEquals(0L, empty.metrics["batch_captured_images"])
    }

    @Test fun savedBudgetFailureStopsBeforeTheFourthScrollAndKeepsTheCompletedPrefix() = runBlocking {
        // Replay the retained sizes from the 2026-10-15 event 272, without device access.
        val p = policy(10, 8 * 1024 * 1024L)
        val budget = ScreenBatchCaptureBudget(p)
        val captures = ScreenBatchCaptures("replay", budget, InMemoryBlobStore())
        val sizes = ArrayDeque(listOf(2742461L, 2741676L, 2279216L))
        var scrolls = 0
        val progress = ScreenActionBatchProgress()
        val observation = com.droidance.core.agent.LocalActionEvidence.ObservationLite(screenHash = 1, foregroundPackage = "generic")
        val executor = ScreenActionBatchExecutor(p) { operation, _ ->
            when (operation) {
                ScreenBatchOperation.SCROLL -> captures.beforeCollectionScroll()
                    ?: ToolProtocolResult.accepted("delivered").also { scrolls++ }
                ScreenBatchOperation.SCREENSHOT -> {
                    budget.record(sizes.removeFirst())
                    progress.finalObservation = observation
                    progress.finalObservationWaitSatisfied = true
                    ToolProtocolResult.success("saved")
                }
                else -> error("unexpected action")
            }
        }
        val result = kotlinx.coroutines.withContext(progress) {
            executor.execute("""{"actions":[{"op":"scroll","direction":"down"},{"op":"screenshot"},
                {"op":"scroll","direction":"down"},{"op":"screenshot"},
                {"op":"scroll","direction":"down"},{"op":"screenshot"},
                {"op":"scroll","direction":"down"},{"op":"screenshot"}]}""")
        }
        assertSame(observation, progress.finalObservation)
        assertTrue(progress.finalObservationWaitSatisfied)
        assertEquals(3, scrolls)
        assertEquals(7763353L, budget.bytes)
        assertEquals("BATCH_CAPTURE_CAPACITY", result.diagnosticCode)
        assertEquals(6L, result.metrics["batch_completed_actions"])
        assertEquals(7L, result.metrics["batch_attempted_actions"])
        assertTrue(result.outputText.contains("next_capture_estimate_exceeds_remaining_budget"))
        assertTrue(result.outputText.contains("NOT_EXECUTED"))
        val fresh = ScreenBatchCaptures("next-response", ScreenBatchCaptureBudget(p), InMemoryBlobStore())
        assertNull(fresh.beforeCollectionScroll())
    }

    @Test fun anEstimateIsNotAnExactReservationAndActualSizesStillEnforceTheLimit() = runBlocking {
        val budget = ScreenBatchCaptureBudget(policy(10, 10))
        val captures = ScreenBatchCaptures("call", budget, InMemoryBlobStore())
        assertTrue(save(captures, 1, 4).isVerifiedSuccess)
        assertNull(captures.beforeCollectionScroll())
        assertEquals("BATCH_CAPTURE_CAPACITY", save(captures, 3, 7).diagnosticCode)
        assertEquals(4L, budget.bytes)
        assertNotNull(captures.beforeCollectionScroll())
    }

    private suspend fun save(captures: ScreenBatchCaptures, index: Int, bytes: Int) = captures.save(
        index, 1000L + index, "execution", "backend", 41, 0, ByteArray(bytes) { index.toByte() }, 2, 2)
}
