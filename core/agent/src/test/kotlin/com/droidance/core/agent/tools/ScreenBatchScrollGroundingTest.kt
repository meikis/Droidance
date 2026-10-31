// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.LocalActionEvidence
import com.droidance.core.agent.ScreenSemanticState
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenBatchScrollGroundingTest {
    private val policy = ScreenActionBatchPolicy(true, ScreenActionBatchProtocol.operations,
        10, 1, 20, 40, 8, 1024, 64)
    private val actions = """{"actions":[{"op":"screenshot"},{"op":"scroll","direction":"down"},{"op":"screenshot"},{"op":"scroll","direction":"down"},{"op":"screenshot"}]}"""
    private val original = reference(0xff000000.toInt())
    private val next = reference(0xffffffff.toInt())

    @Test fun changedListPagesCanBeCollectedWithoutReusingTheFirstPageAsFreshnessReference() = runBlocking {
        val grounding = grounding()
        var current = original
        var captured = 0
        var scrolled = 0
        val result = ScreenActionBatchExecutor(policy) { operation, _, _ ->
            when (operation) {
                ScreenBatchOperation.SCREENSHOT -> {
                    grounding.captured(observation(), current, ++captured * 100L)
                    if (scrolled == 0) assertSame(current, grounding.checkpoint?.reference)
                    ToolProtocolResult.success("captured")
                }
                ScreenBatchOperation.SCROLL -> {
                    val expected = grounding.checkpoint?.reference ?: original
                    val comparison = ObservationPixelComparator.compare(expected, 1, 1, { _, _ -> current.colors.single() }, 0)
                    val receipt = if (comparison.changedPixels == 0) {
                        scrolled++
                        current = if (scrolled == 1) next else original
                        ToolProtocolResult.accepted("delivered")
                    } else ToolProtocolResult.notExecuted("PRE_ACTION_OBSERVATION_CHANGED", true)
                    grounding.scrollSettled(receipt)
                    receipt
                }
                else -> error("Unexpected operation")
            }
        }.execute(actions)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(2, scrolled)
        assertEquals(3, captured)
    }

    @Test fun consecutiveNavigationScrollsRefreshAfterDeliveryWithoutAnotherModelDecision() = runBlocking {
        val actions = """{"actions":[{"op":"scroll","direction":"up"},{"op":"scroll","direction":"up"},{"op":"scroll","direction":"up"}]}"""
        val grounding = grounding(actions)
        var current = original
        var injected = 0
        var refreshes = 0
        assertFalse(grounding.needsScrollRefresh)
        val result = ScreenActionBatchExecutor(policy) { operation, _, _ ->
            assertEquals(ScreenBatchOperation.SCROLL, operation)
            if (grounding.needsScrollRefresh) {
                grounding.captured(observation(), current, ++refreshes * 100L)
                assertNotNull(grounding.checkpoint)
            }
            val reference = grounding.checkpoint?.reference ?: original
            val receipt = if (reference.colors.contentEquals(current.colors)) {
                injected++
                current = if (injected % 2 == 1) next else original
                ToolProtocolResult.accepted("delivered")
            } else ToolProtocolResult.notExecuted("PRE_ACTION_OBSERVATION_CHANGED", true)
            receipt.also(grounding::scrollSettled)
        }.execute(actions)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(3, injected)
        assertEquals(2, refreshes)
        assertEquals(3L, result.metrics["batch_completed_actions"])
    }

    @Test fun aNewChangeAfterTheCheckpointStillFailsFreshnessAndStopsTheSuffix() = runBlocking {
        val grounding = grounding()
        var current = original
        var captures = 0
        var injected = 0
        val result = ScreenActionBatchExecutor(policy) { operation, _, _ ->
            when (operation) {
                ScreenBatchOperation.SCREENSHOT -> {
                    captures++
                    grounding.captured(observation(), current, captures * 100L)
                    if (captures == 2) current = original // External change after the saved page.
                    ToolProtocolResult.success("captured")
                }
                ScreenBatchOperation.SCROLL -> {
                    val expected = grounding.checkpoint?.reference ?: original
                    val receipt = if (expected.colors.contentEquals(current.colors)) {
                        injected++; current = next; ToolProtocolResult.accepted("delivered")
                    } else ToolProtocolResult.notExecuted("PRE_ACTION_OBSERVATION_CHANGED", true)
                    grounding.scrollSettled(receipt)
                    receipt
                }
                else -> error("Unexpected operation")
            }
        }.execute(actions)
        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertEquals(1, injected)
        assertEquals(2, captures)
        assertEquals(3L, result.metrics["batch_completed_actions"])
        assertEquals(1L, result.metrics["batch_unexecuted_actions"])
    }

    @Test fun aStartingScreenshotRefreshesOnlyAnAlreadyPlannedCollectionViewport() {
        val grounding = grounding()
        grounding.captured(observation(), next, 100)
        assertSame(next, grounding.checkpoint?.reference)
        val unavailable = ScreenBatchScrollGrounding(ScreenActionBatchPlanner.plan(actions, policy), null)
        unavailable.scrollSettled(ToolProtocolResult.accepted("delivered"))
        unavailable.captured(observation(), next, 200)
        assertNull(unavailable.checkpoint)
    }

    @Test fun changedFirstPageCanBeCollectedAndThenScrolledWithinOneBatch() = runBlocking {
        val grounding = grounding()
        var injected = 0
        val result = ScreenActionBatchExecutor(policy) { op, _, _ ->
            if (op == ScreenBatchOperation.SCREENSHOT) {
                grounding.captured(observation(), next, 100)
                ToolProtocolResult.success("saved")
            } else {
                assertSame(next, grounding.checkpoint?.reference)
                injected++
                ToolProtocolResult.accepted("delivered").also(grounding::scrollSettled)
            }
        }.execute(actions)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(2, injected)
    }

    @Test fun mixedTargetsAndDifferentScrollViewportsNeverReceiveCheckpointAuthority() {
        for (extra in listOf("""{"op":"click","x":500,"y":500}""",
            """{"op":"type_text","text":"x","clear":true}""",
            """{"op":"press_key","key":"BACK"}""",
            """{"op":"scroll","direction":"up"}""",
            """{"op":"scroll","direction":"down","region":{"left":1,"top":1,"right":900,"bottom":900}}""")) {
            val plan = """{"actions":[{"op":"scroll","direction":"down"},{"op":"screenshot"},$extra]}"""
            val grounding = grounding(plan)
            grounding.scrollSettled(ToolProtocolResult.accepted("delivered"))
            assertFalse(extra, grounding.needsScrollRefresh)
            grounding.captured(observation(), next, 100)
            assertNull(extra, grounding.checkpoint)
        }
    }

    @Test fun aCollectionAfterAnActionIsGroundedByItsOpeningScreenshotNotTheDecisionFrame() {
        val plan = """{"actions":[{"op":"click","x":500,"y":500},{"op":"wait","ms":10},{"op":"screenshot"},""" +
            """{"op":"scroll","direction":"down"},{"op":"screenshot"}]}"""
        val grounding = grounding(plan)
        grounding.before(1)
        assertFalse(grounding.isCollection)
        grounding.before(2)
        assertFalse(grounding.isCollection)
        grounding.before(3)
        assertTrue(grounding.isCollection)
        grounding.captured(observation("generic/SortedListActivity"), next, 100)
        assertSame(next, grounding.checkpoint?.reference)
        grounding.before(4)
        grounding.scrollSettled(ToolProtocolResult.accepted("delivered"))
        assertTrue(grounding.needsScrollRefresh)
        grounding.before(5)
        grounding.captured(observation("generic/SortedListActivity"), next, 200)
        assertSame(next, grounding.checkpoint?.reference)
        // Without an opening screenshot after the action, the decision frame still governs.
        val unopened = grounding("""{"actions":[{"op":"click","x":500,"y":500},{"op":"scroll","direction":"down"},{"op":"screenshot"}]}""")
        unopened.before(2)
        assertFalse(unopened.isCollection)
    }

    @Test fun missingEvidenceChangedSurfaceAndFailedScrollCannotRenewGrounding() {
        val changed = listOf(observation().copy(backendSessionId = "other"),
            observation().copy(displayId = 9), observation("different/Activity"), observation(null))
        for (observation in changed) {
            val grounding = grounding()
            grounding.scrollSettled(ToolProtocolResult.accepted("delivered"))
            grounding.captured(observation, next, 100)
            assertNull(grounding.checkpoint)
        }
        val grounding = grounding()
        grounding.scrollSettled(ToolProtocolResult.notExecuted("REJECTED", true))
        assertFalse(grounding.needsScrollRefresh)
        grounding.captured(observation(), next, 100)
        assertNull(grounding.checkpoint)
        grounding.scrollSettled(ToolProtocolResult.accepted("delivered"))
        grounding.captured(observation(), null, 200)
        assertNull(grounding.checkpoint)
        grounding.scrollSettled(ToolProtocolResult.accepted("delivered"))
        assertTrue(grounding.needsScrollRefresh)
        grounding.captured(observation(), next, 300)
        assertFalse(grounding.needsScrollRefresh)
        assertSame(next, grounding.checkpoint?.reference)
        grounding.scrollSettled(ToolProtocolResult.accepted("delivered"))
        assertNull(grounding.checkpoint) // Another capture is required after each scroll.
    }

    private fun grounding(plan: String = actions) = ScreenBatchScrollGrounding(
        ScreenActionBatchPlanner.plan(plan, policy), observation())
    private fun observation(component: String? = "generic/ListActivity") = LocalActionEvidence.ObservationLite(
        screenHash = 1, foregroundPackage = "generic", backendSessionId = "backend", displayId = 4,
        semanticState = ScreenSemanticState("generic", component, 1, null, "list"))
    private fun reference(color: Int) = ObservationPixelReference(
        listOf(ObservationMappedSample(500, 500, 0, 0)), intArrayOf(color))
}
