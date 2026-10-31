// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import org.junit.Assert.*
import org.junit.Test

class RetainedCoordinateResolverTest {
    private val policy = ScreenActionBatchPolicy(true, ScreenActionBatchProtocol.operations, 100, 1, 5000, 10000, 10, 8388608, 64)
    private val original = image("original", 'a', 1080, 2376)
    private val crop = image("crop", 'b', 648, 856).copy(
        sourceObservationId = "original", sourceContentRef = original.contentRef,
        sourceRegion = ImageSourceRegion(918, 831, 162, 214, 1080, 2376))

    @Test fun mapsTheObservedFilterCropWithoutReusingTheFailedFullScreenPoint() {
        val point = resolve("crop", 568, 406)
        assertEquals(ModelCoordinateMapper.Point(935, 386), point)
        val physical = ModelCoordinateMapper(1f, 1080 to 2376, 1080 to 2376).toPhysical(point!!.x, point.y)
        assertEquals(ModelCoordinateMapper.Point(1010, 917), physical)
        assertTrue(408 * 2376 / 1000 - physical.y > 45)
    }

    @Test fun mapsNestedViewsAndBoundariesWithoutIntermediateRounding() {
        val nested = image("nested", 'c', 1296, 1712).copy(
            sourceObservationId = "crop", sourceContentRef = crop.contentRef,
            sourceRegion = ImageSourceRegion(324, 0, 324, 428, 648, 856))
        assertEquals(ModelCoordinateMapper.Point(925, 395), resolve("nested", 0, 1000, listOf(original, crop, nested)))
        assertEquals(ModelCoordinateMapper.Point(1000, 440), resolve("crop", 1000, 1000))
    }

    @Test fun rejectsChangedOriginalOldObservationUnknownReferenceAndCycles() {
        assertEquals(com.droidance.core.agent.ToolRecovery.OBSERVE,
            com.droidance.core.agent.ToolRecovery.classify(ToolProtocolResult.notExecuted("COORDINATE_REFERENCE_STALE", true)))
        assertNull(RetainedCoordinateResolver.resolve("crop", 500, 500, "original", "sha256:" + "d".repeat(64), listOf(original, crop)))
        assertNull(RetainedCoordinateResolver.resolve("crop", 500, 500, "new-observation", original.contentRef, listOf(original, crop)))
        val old = crop.copy(sourceObservationId = "old-observation")
        assertNull(resolve("crop", 500, 500, listOf(original, old)))
        assertNull(resolve("missing", 500, 500))
        assertNull(resolve("crop", -1, 500))
        assertNull(resolve("crop", 500, 1001))
        assertNull(resolve("crop", 500, 500, listOf(original, crop.copy(sourceContentRef = crop.contentRef))))
    }

    @Test fun plannerPreservesExplicitCoordinateReferenceAndRejectsAmbiguousTargets() {
        val ready = ScreenActionBatchPlanner.plan("""{"actions":[{"op":"click","coordinate_ref":"crop","x":568,"y":406}]}""", policy)
            as ScreenActionBatchPlan.Ready
        assertTrue(ready.actions.single().argumentsJson.contains("\"coordinate_ref\":\"crop\""))
        for (field in listOf("\"target_id\":\"invented\",", "\"coordinate_ref\":2,")) {
            val body = if (field.startsWith("\"target")) "\"coordinate_ref\":\"crop\"," + field else field
            assertTrue(ScreenActionBatchPlanner.plan("""{"actions":[{"op":"click",${body}"x":500,"y":500}]}""", policy) is ScreenActionBatchPlan.Rejected)
        }
    }

    @Test fun changingToCropCoordinatesCannotResetAnExhaustedPhysicalTarget() {
        val guard = com.droidance.core.agent.ObservationRecovery(com.droidance.core.agent.ObservationRecoveryPolicy(
            3, 4, 20, 10, 30, emptySet(), emptySet(), mapOf("screen_act:click" to listOf("x", "y")), 2, emptySet(), 0.002, 24))
        val full = """{"actions":[{"op":"click","x":935,"y":408}]}"""
        guard.observe("execution", "full", "obs-0", 0, "hash")
        repeat(3) { i ->
            guard.executed("screen_act", full, ToolProtocolResult.accepted("submitted"))
            guard.observe("execution", "full", "obs-${i + 1}", 0, "hash")
        }
        guard.bindCoordinateSources("original", original.contentRef, listOf(original, crop))
        assertTrue(guard.blocked("screen_act", """{"actions":[{"op":"click","coordinate_ref":"crop","x":568,"y":646}]}"""))
        val other = crop.copy(observationId = "other", contentRef = "sha256:" + "e".repeat(64), contentHash = "e".repeat(64),
            sourceRegion = ImageSourceRegion(0, 0, 100, 100, 1080, 2376))
        guard.bindCoordinateSources("original", original.contentRef, listOf(original, crop, other))
        assertFalse(guard.blocked("screen_act", """{"actions":[{"op":"click","coordinate_ref":"other","x":568,"y":646}]}"""))
    }

    private fun resolve(ref: String, x: Int, y: Int, images: List<DetailAttachmentAddedEventPayload> = listOf(original, crop)) =
        RetainedCoordinateResolver.resolve(ref, x, y, "original", original.contentRef, images)

    @Test fun cropBudgetRequiresANewObservationAndDoesNotSpendFailedDerivations() {
        val budget = CurrentFrameCropBudget()
        assertTrue(budget.acquire("frame-a", 2))
        assertTrue(budget.acquire("frame-a", 2))
        assertFalse(budget.acquire("frame-a", 2))
        assertTrue(budget.acquire("frame-b", 2))
        budget.releaseFailed("frame-a")
        assertTrue(budget.acquire("frame-b", 2))
        assertFalse(budget.acquire("frame-b", 2))
        budget.releaseFailed("frame-b")
        assertTrue(budget.acquire("frame-b", 2))
        assertEquals(com.droidance.core.agent.ToolRecovery.OBSERVE,
            com.droidance.core.agent.ToolRecovery.classify(ToolProtocolResult.notExecuted("CROP_CURRENT_FRAME_BUDGET_EXHAUSTED", true)))
    }

    private fun image(id: String, hashChar: Char, width: Int, height: Int): DetailAttachmentAddedEventPayload {
        val hash = hashChar.toString().repeat(64)
        return DetailAttachmentAddedEventPayload(1, id, DetailAttachmentRole.RESULT, "sha256:$hash", hash,
            1, "image/png", width, height, id)
    }
}
