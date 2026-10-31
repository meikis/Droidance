// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.ScreenChangeSignature
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import com.droidance.core.agent.event.ToolExecutionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DragRepeatExecutorTest {
    private val arguments = """{"actions":[{"op":"drag","startX":500,"startY":700,"endX":500,"endY":800,"repeat":11}]}"""
    private val policy = ScreenActionBatchPolicy(true, ScreenActionBatchProtocol.operations, 10, 1, 20, 40, 8, 1024, 64)
    private fun frame(offset: Int, surface: String = "bound", stable: Boolean = true) = DragRepeatExecutor.Frame(
        ScreenChangeSignature(20, 100, ByteArray(2000) {
            if (it % 20 in 9..12 && it / 20 in 50..90) (((it / 20 - offset).mod(11)) * 20).toByte() else 0
        }), surface, stable)
    private fun count(result: com.droidance.core.agent.event.ToolExecutionResult, key: String) =
        result.data!!.jsonObject.getValue("drag_repeat").jsonObject.getValue(key).jsonPrimitive.content

    @Test fun elevenRepetitionsEachWaitForTheirOwnObservation() = runBlocking {
        var position = 0
        val events = mutableListOf<String>()
        val result = DragRepeatExecutor(capture = { events += "capture:$position"; frame(position) },
            drag = { position++; events += "drag:$position"; ToolProtocolResult.accepted("delivered") })
            .execute(arguments, 11)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("11", count(result, "changed"))
        assertEquals("11", count(result, "delivered"))
        assertEquals("completed", count(result, "stop_reason"))
        assertEquals(listOf("capture:0") + (1..11).flatMap { listOf("drag:$it", "capture:$it") }, events)
    }

    @Test fun noChangeOnThirdDragStopsRepetitionsAndTheRemainingBatch() = runBlocking {
        var drags = 0
        var clicks = 0
        val result = ScreenActionBatchExecutor(policy) { op, _ ->
            if (op == ScreenBatchOperation.DRAG) DragRepeatExecutor(
                capture = { frame(minOf(drags, 2)) },
                drag = { drags++; ToolProtocolResult.accepted("delivered") }).execute(arguments, 11)
            else { clicks++; ToolProtocolResult.accepted("click") }
        }.execute(arguments.dropLast(2) + ",{\"op\":\"click\",\"x\":500,\"y\":500}]}")
        assertEquals(3, drags)
        assertEquals(0, clicks)
        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertTrue(result.outputText.contains("no_change_at_3"))
        assertTrue(result.outputText.contains("\\\"changed\\\":2"))
        assertEquals(1L, result.metrics["batch_unexecuted_actions"])
    }

    @Test fun unavailableUnsettledOrDifferentSurfaceNeverAllowsTheNextDrag() = runBlocking {
        for (after in listOf(null, frame(1, stable = false), frame(1, surface = "other"))) {
            var drags = 0
            val result = DragRepeatExecutor(capture = { if (drags == 0) frame(0) else after },
                drag = { drags++; ToolProtocolResult.accepted("delivered") }).execute(arguments, 11)
            assertEquals(1, drags)
            assertEquals(ToolExecutionStatus.INTERRUPTED, result.executionStatus)
            assertEquals("1", count(result, "delivered"))
            assertEquals("0", count(result, "changed"))
        }
    }

    @Test fun anUnsettledInitialFrameExecutesNothing() = runBlocking {
        val result = DragRepeatExecutor(capture = { frame(0, stable = false) },
            drag = { error("must not inject") }).execute(arguments, 11)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
        assertEquals("0", count(result, "attempted"))
    }

    @Test fun aLaterDeliveryRejectionKeepsTheEarlierDeliveredPrefix() = runBlocking {
        var drags = 0
        val result = DragRepeatExecutor(capture = { frame(drags) }, drag = {
            if (++drags == 3) ToolProtocolResult.notExecuted("CHANNEL_DOWN", true)
            else ToolProtocolResult.accepted("delivered")
        }).execute(arguments, 11)
        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertEquals("3", count(result, "attempted"))
        assertEquals("2", count(result, "delivered"))
        assertEquals("2", count(result, "changed"))
    }

    @Test fun cancellationDuringObservationRetainsChangedAndUnobservedDeliveryCounts() = runBlocking {
        var drags = 0
        val progress = ScreenActionBatchProgress()
        val cancellation = CancellationException("stop")
        val thrown = withContext(progress) { runCatching {
            ScreenActionBatchExecutor(policy) { _, _ ->
                DragRepeatExecutor(capture = { if (drags == 3) throw cancellation else frame(drags) },
                    drag = { drags++; ToolProtocolResult.accepted("delivered") },
                    onProgress = progress::updateAttemptOutput).execute(arguments, 11)
            }.execute(arguments)
        }.exceptionOrNull() }
        assertSame(cancellation, thrown)
        assertEquals(3, drags)
        val receipt = thrown!!.screenActionBatchCancellationReceiptOrNull()!!.attempted!!.outputPreview!!
        assertTrue(receipt.contains("\"delivered\":3"))
        assertTrue(receipt.contains("\"changed\":2"))
        assertTrue(receipt.contains("cancelled_effect_may_be_unobserved"))
    }

    @Test fun repeatIsValidatedBeforeAnyActionAndOnlyAllowedOnDrag() {
        for (repeat in listOf("0", "-1", "65", "1.5", "\"2\"", "null", "true")) {
            val bad = arguments.replace("\"repeat\":11", "\"repeat\":$repeat")
            assertTrue(repeat, ScreenActionBatchPlanner.plan(bad, policy) is ScreenActionBatchPlan.Rejected)
        }
        assertTrue(ScreenActionBatchPlanner.plan(arguments.replace("drag", "swipe"), policy) is ScreenActionBatchPlan.Rejected)
        val ready = ScreenActionBatchPlanner.plan(arguments, policy) as ScreenActionBatchPlan.Ready
        assertTrue(ready.actions.single().argumentsJson.contains("\"repeat\":11"))
        assertTrue(ScreenActionBatchPlanner.plan(arguments.replace(",\"repeat\":11", ""), policy) is ScreenActionBatchPlan.Ready)
    }
}
