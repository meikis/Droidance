// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ActionDeliveryEvidence
import com.droidance.core.agent.event.GoalProgressEvidence
import com.droidance.core.agent.event.StateTransitionEvidence
import com.droidance.core.agent.event.ToolActionEvidence
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import com.droidance.core.model.llm.models.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class ScreenActionBatchTest {
    private val policy = ScreenActionBatchPolicy(
        enabled = true,
        allowedOperations = ScreenActionBatchProtocol.operations,
        defaultWaitMs = 20,
        minWaitMs = 10,
        maxWaitMs = 100,
        maxTotalWaitMs = 100, maxCaptureImages = 10, maxCaptureBytes = 8 * 1024 * 1024L, maxDragRepeat = 64
    )

    @Test fun visitAndCollectExpandIntoCheckedPrimitivesWithSourceLabels() {
        val plan = ScreenActionBatchPlanner.plan("""{"actions":[{"op":"visit","x":250,"y":420},""" +
            """{"op":"collect","direction":"down","amount":"medium","pages":3}]}""", policy) as ScreenActionBatchPlan.Ready
        assertEquals(listOf(ScreenBatchOperation.CLICK, ScreenBatchOperation.SCREENSHOT, ScreenBatchOperation.PRESS_KEY,
            ScreenBatchOperation.SCREENSHOT, ScreenBatchOperation.SCROLL, ScreenBatchOperation.SCREENSHOT,
            ScreenBatchOperation.SCROLL, ScreenBatchOperation.SCREENSHOT), plan.actions.map { it.operation })
        assertEquals(listOf("visit[1].click", "visit[1].screenshot", "visit[1].back", "collect[2].screenshot",
            "collect[2].scroll", "collect[2].screenshot", "collect[2].scroll", "collect[2].screenshot"),
            plan.actions.map { it.sourceOperation })
        assertEquals((1..8).toList(), plan.actions.map { it.actionIndex })
        assertEquals(250 to 420, plan.actions.first().targetX to plan.actions.first().targetY)
        assertEquals("BACK", Json.parseToJsonElement(plan.actions[2].argumentsJson).jsonObject.getValue("key").jsonPrimitive.content)
        // A default collect captures three views; bounds and missing targets are rejected before any action.
        assertEquals(5, (ScreenActionBatchPlanner.plan("""{"actions":[{"op":"collect","direction":"down"}]}""", policy)
            as ScreenActionBatchPlan.Ready).actions.size)
        assertTrue(ScreenActionBatchPlanner.plan("""{"actions":[{"op":"collect","direction":"down","pages":11}]}""", policy)
            is ScreenActionBatchPlan.Rejected)
        assertTrue(ScreenActionBatchPlanner.plan("""{"actions":[{"op":"visit","x":250}]}""", policy) is ScreenActionBatchPlan.Rejected)
        assertTrue(ScreenActionBatchPlanner.plan("""{"actions":[{"op":"visit","x":250,"y":420,"key":"BACK"}]}""", policy)
            is ScreenActionBatchPlan.Rejected)
    }

    @Test fun aClickRightAfterAnotherClickIsRecognizedForPressFeedbackRecheck() {
        val actions = (ScreenActionBatchPlanner.plan("""{"actions":[{"op":"click","x":625,"y":592},""" +
            """{"op":"click","x":862,"y":592},{"op":"press_key","key":"ENTER"},{"op":"click","x":862,"y":592},""" +
            """{"op":"type_text","text":"5","clear":false}]}""", policy) as ScreenActionBatchPlan.Ready).actions
        assertFalse(actions[0].followsClick(null))
        assertTrue("A neighbouring key may still show the previous press", actions[1].followsClick(actions[0]))
        assertFalse("A key press between clicks is not press feedback", actions[3].followsClick(actions[2]))
        assertFalse("Only clicks recheck their target", actions[4].followsClick(actions[3]))
    }

    @Test fun digitKeyNamesAreAcceptedAndRawCodesRejected() {
        assertTrue(ScreenActionBatchPlanner.plan("""{"actions":[{"op":"press_key","key":"KEYCODE_4"},""" +
            """{"op":"press_key","key":"KEYCODE_STAR"},{"op":"press_key","key":"KEYCODE_0"}]}""", policy)
            is ScreenActionBatchPlan.Ready)
        assertTrue(ScreenActionBatchPlanner.plan("""{"actions":[{"op":"press_key","key":"66"}]}""", policy)
            is ScreenActionBatchPlan.Rejected)
    }

    @Test fun inputMethodIsPreservedThroughTheBatchPlan() = runBlocking {
        val seen = mutableListOf<String>()
        val executor = ScreenActionBatchExecutor(policy) { _: ScreenBatchOperation, args: String ->
            seen += Json.parseToJsonElement(args).jsonObject.getValue("method").jsonPrimitive.content
            ToolProtocolResult.accepted("injected only")
        }
        executor.execute("""{"actions":[{"op":"type_text","text":"sample","clear":false,"method":"paste_key"}]}""")
        assertEquals(listOf("paste_key"), seen)
        assertTrue(ScreenActionBatchPlanner.plan("""{"actions":[{"op":"type_text","text":"sample","clear":false,"method":"unknown"}]}""", policy) is ScreenActionBatchPlan.Rejected)
        assertEquals(279, KeyEventPolicy.resolveKeyCode("PASTE"))
    }

    @Test fun oneOrSeveralActionsUseOneShapeAndAllGesturesRemainAvailable() = runBlocking {
        val operations = mutableListOf<ScreenBatchOperation>()
        val executor = ScreenActionBatchExecutor(policy) { op: ScreenBatchOperation, _: String ->
            operations += op
            ToolProtocolResult.accepted("delivered")
        }
        assertEquals(ToolExecutionStatus.SUCCEEDED, executor.execute(
            """{"actions":[{"op":"swipe","startX":1,"startY":2,"endX":3,"endY":4}]}""").executionStatus)
        assertEquals(ToolExecutionStatus.SUCCEEDED, executor.execute(
            """{"actions":[{"op":"drag","startX":1,"startY":2,"endX":3,"endY":4},{"op":"path","points":[{"x":1,"y":2},{"x":3,"y":4}]}]}""").executionStatus)
        assertEquals(listOf(ScreenBatchOperation.SWIPE, ScreenBatchOperation.DRAG, ScreenBatchOperation.PATH), operations)
        assertTrue(ScreenActionBatchPlanner.plan("""{"op":"batch","actions":[{"op":"wait","ms":10}]}""", policy) is ScreenActionBatchPlan.Rejected)
        assertTrue(ScreenActionBatchPlanner.plan("""{"op":"wait","ms":10}""", policy) is ScreenActionBatchPlan.Rejected)
    }

    @Test fun checkpointsExecuteAtTheirActualPositionsAndFailureStopsTheSuffix() = runBlocking {
        val seen = mutableListOf<String>()
        val result = ScreenActionBatchExecutor(policy, executeAction = { operation, _, mapped ->
            seen += "${mapped.actionIndex}:$operation"
            if (mapped.actionIndex == 3) ToolProtocolResult.failed("CAPTURE_FAILED", true)
            else ToolProtocolResult.success("Saved or delivered")
        }).execute("""{"actions":[{"op":"screenshot"},{"op":"scroll","direction":"down"},{"op":"screenshot"},{"op":"scroll","direction":"down"}]}""")
        assertEquals(listOf("1:SCREENSHOT", "2:SCROLL", "3:SCREENSHOT"), seen)
        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertEquals(2L, result.metrics["batch_completed_actions"])
        assertEquals(1L, result.metrics["batch_unexecuted_actions"])
    }

    @Test fun finalObservationTimeoutRetainsCompletedBatchWithoutInventingAnAttempt() = runBlocking {
        val progress = ScreenActionBatchProgress()
        var effects = 0
        val executor = ScreenActionBatchExecutor(policy) { _, _ ->
            effects++
            ToolProtocolResult.accepted("delivered")
        }
        val error = try {
            kotlinx.coroutines.withContext(progress) {
                executor.execute("""{"actions":[{"op":"press_key","key":"1"},{"op":"press_key","key":"2"}]}""")
                // All effects finished; the enclosing tool times out waiting for its final frame.
                kotlinx.coroutines.withTimeout(20) { kotlinx.coroutines.awaitCancellation() }
            }
        } catch (cancelled: CancellationException) { cancelled }
        progress.attachTo(error)
        progress.attachTo(error)
        val receipt = checkNotNull(error.screenActionBatchCancellationReceiptOrNull())
        assertEquals(2, effects)
        assertEquals(2, receipt.completedPrefix.size)
        assertTrue(receipt.completedPrefix.all { !it.effectUnknown })
        assertNull(receipt.attempted)
        assertTrue(receipt.unexecutedSuffix.isEmpty())
        assertEquals(1, error.suppressed.filterIsInstance<ScreenActionBatchCancellationMarker>().size)
    }

    @Test fun cancellationMetricsPreserveCompletedAttemptedAndUnexecutedActions() {
        val receipt = ScreenActionBatchCancellationReceipt(
            listOf(ScreenActionBatchSubActionReceipt(1, "type_text", ToolExecutionStatus.SUCCEEDED)),
            ScreenActionBatchSubActionReceipt(2, "click", ToolExecutionStatus.CANCELED, effectUnknown = true),
            listOf(ScreenActionBatchSubActionReceipt(3, "press_key", ToolExecutionStatus.NOT_EXECUTED)))
        assertEquals(3L, receipt.progressMetrics()["batch_requested_actions"])
        assertEquals(1L, receipt.progressMetrics()["batch_completed_actions"])
        assertEquals(2L, receipt.progressMetrics()["batch_attempted_actions"])
        assertEquals(1L, receipt.progressMetrics()["batch_unexecuted_actions"])
        assertTrue(receipt.toJsonDetails().toString().contains("\"op\":\"type_text\""))
    }

    @Test fun suppliedBatchExamplesPassTheSamePublicSchemaAndPlanner() {
        var root = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (!java.io.File(root, "app/src/main/assets/rules/completion_recovery.md").exists())
            root = requireNotNull(root.parentFile)
        val rules = java.io.File(root, "app/src/main/assets/rules/completion_recovery.md").readText()
        val examples = Regex("""screen_act\(actions=(\[\{.*?\}\])\)""").findAll(rules)
            .map { """{"actions":${it.groupValues[1]}}""" }.toList()
        // Examples are optional; any supplied example must pass the public schema and planner.
        assertTrue(rules.contains("screen_act(actions=[...])"))
        val schema = mapOf("actions" to com.droidance.core.model.llm.models.ToolParameter("array", "", items =
                com.droidance.core.model.llm.models.ToolParameter("object", "", properties = ScreenActionBatchProtocol.parameters("Clear"), additionalProperties = false)))
        examples.forEach { example ->
            assertNull(com.droidance.core.agent.harness.ToolArgumentSchemaValidator.mismatchDetail(schema, example))
            val payload = example
            assertTrue(ScreenActionBatchPlanner.plan(payload, policy) is ScreenActionBatchPlan.Ready)
        }
    }

    @Test fun scrollUsesScalarFieldsAndRejectsInvalidRegionBeforeAnyEffect() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = ScreenActionBatchExecutor(policy) { _: ScreenBatchOperation, payload: String ->
            calls += payload
            ToolProtocolResult.success("delivered")
        }
        val accepted = executor.execute("""{"actions":[{"op":"scroll","direction":"down","amount":"small",
            "speed":"slow","region":{"left":100,"top":100,"right":500,"bottom":900}}]}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, accepted.executionStatus)
        assertTrue(calls.single().contains("\"amount\":\"small\""))
        calls.clear()
        executor.execute("""{"actions":[{"op":"click","x":100,"y":100},{"op":"scroll","direction":"down",
            "region":{"left":500,"top":100,"right":100,"bottom":900}}]}""")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun invalidBatchReturnsPolicyWithoutExecutingAnyAction() = runBlocking {
        val executor = ScreenActionBatchExecutor(policy) { _, _ -> error("Invalid batch executed") }
        val result = executor.execute("""{"actions":[{"op":"type_text","text":"30","clear":true},{"op":"click","x":1001,"y":485}]}""")
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        val disclosed = Json.decodeFromJsonElement(ScreenActionBatchPolicy.serializer(), details.getValue("policy"))
        assertEquals(policy, disclosed)
        assertEquals("action[1] op=click x=1001 is outside the normalized range 0..1000", details.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun deliveredIntermediateActionWithFrameDoesNotRequireGoalCompletion() = runBlocking {
        var calls = 0
        val executor = ScreenActionBatchExecutor(policy) { _, _ ->
            calls++
            ToolProtocolResult.accepted("delivered without a goal claim").copy(metrics = mapOf("post_action_frame_captured" to 1L))
        }
        val result = executor.execute("""{"actions":[{"op":"press_key","key":"1"},{"op":"press_key","key":"2"}]}""")
        assertEquals(2, calls)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(!result.isVerifiedSuccess)
    }

    @Test
    fun deliveredActionWithoutFrameContinuesWithoutClaimingVisualSuccess() = runBlocking {
        var calls = 0
        val executor = ScreenActionBatchExecutor(policy) { _, _ ->
            calls++
            ToolProtocolResult.accepted("delivered")
        }
        val result = executor.execute("""{"actions":[{"op":"press_key","key":"1"},{"op":"press_key","key":"2"}]}""")
        assertEquals(2, calls)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(!result.isVerifiedSuccess)
    }

    @Test
    fun clickThenTypePreservesNormalizedCoordinates() {
        val plan = ScreenActionBatchPlanner.plan(
            argumentsJson = """{"actions":[
                {"op":"click","x":500,"y":100},
                {"op":"type_text","text":"hello","clear":false}
            ]}""",
            policy = policy
        ) as ScreenActionBatchPlan.Ready

        assertEquals(
            listOf(ScreenBatchOperation.CLICK, ScreenBatchOperation.TYPE_TEXT),
            plan.actions.map { it.operation }
        )
        val click = Json.parseToJsonElement(plan.actions.first().argumentsJson).jsonObject
        assertEquals(500, click["x"]?.jsonPrimitive?.content?.toInt())
        assertEquals(100, click["y"]?.jsonPrimitive?.content?.toInt())
        assertTrue(plan.actions.last().argumentsJson.contains("\"clear\":false"))
    }

    @Test
    fun canonicalTextAndKeypressActionsAreAccepted() {
        val plan = ScreenActionBatchPlanner.plan(
            argumentsJson = """{"actions":[
                {"op":"type_text","text":"5000","x":100,"y":200,"clear":true},
                {"op":"press_key","key":"ENTER"}
            ]}""",
            policy = policy
        ) as ScreenActionBatchPlan.Ready

        assertEquals(
            listOf(ScreenBatchOperation.TYPE_TEXT, ScreenBatchOperation.PRESS_KEY),
            plan.actions.map { it.operation }
        )
        assertTrue(plan.actions.first().argumentsJson.contains("\"text\":\"5000\""))
        assertEquals(
            Json.parseToJsonElement("""{"key":"ENTER"}""").jsonObject,
            Json.parseToJsonElement(plan.actions.last().argumentsJson).jsonObject
        )
    }

    @Test
    fun deprecatedAliasesAndNonCanonicalFieldsAreRejectedBeforeExecution() = runBlocking {
        val batches = listOf(
            """{"actions":[{"type":"type_text","text":"5000","clear":true}]}""",
            """{"actions":[{"type":"press_key","key":"ENTER"}]}""",
            """{"actions":[{"op":"keypress","key":"ENTER"}]}""",
            """{"actions":[{"op":"press_key","keys":["ENTER"]}]}""",
            """{"actions":[{"op":"type_text","text":"5000","clear":"true"}]}"""
        )
        batches.forEach { arguments ->
            val plan = ScreenActionBatchPlanner.plan(arguments, policy)
            assertTrue("Expected canonical batch rejection for $arguments, got $plan", plan is ScreenActionBatchPlan.Rejected)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(allowedOperations = policy.allowedOperations + "type_text").validate()
        }
        Unit
    }

    @Test
    fun sharedProtocolExposesTheSameCanonicalFieldsAndScalarOperations() {
        val parameters = ScreenActionBatchProtocol.parameters("Explicitly replace existing text")
        assertEquals(ScreenActionBatchProtocol.operations, parameters.getValue("op").enumValues)
        assertEquals(
            setOf("click", "type_text", "press_key", "scroll", "swipe", "drag", "path", "wait", "screenshot", "visit", "collect"),
            ScreenActionBatchProtocol.actionFields.keys
        )
        assertEquals(
            Json.parseToJsonElement("""{"op":"type_text","text":"5000","clear":true}""").jsonObject,
            ScreenActionBatchProtocol.primitive(Json.parseToJsonElement(
                """{"op":"type_text","text":"5000","clear":true}"""
            ).jsonObject)
        )
        assertEquals(
            Json.parseToJsonElement("""{"op":"press_key","key":"ENTER"}""").jsonObject,
            ScreenActionBatchProtocol.primitive(Json.parseToJsonElement(
                """{"op":"press_key","key":"ENTER"}"""
            ).jsonObject)
        )
    }

    @Test
    fun orderedActionsDoNotRequireATransitionWhitelist() {
        val plan = ScreenActionBatchPlanner.plan(
            argumentsJson = """{"actions":[
                {"op":"click","x":10,"y":10},
                {"op":"scroll","direction":"down","amount":"medium"}
            ]}""",
            policy = policy
        )

        assertTrue(plan is ScreenActionBatchPlan.Ready)
    }

    @Test
    fun malformedLaterKeyOrScrollParameterIsRejectedBeforeAnySideEffect() = runBlocking {
        val batches = listOf(
            """{"actions":[{"op":"press_key","key":"ENTER"},{"op":"press_key","key":"NO_SUCH_KEY"}]}""",
            """{"actions":[{"op":"scroll","direction":"down"},{"op":"scroll","direction":"diagonal"}]}""",
            """{"actions":[{"op":"click","x":"10","y":10}]}""",
            """{"actions":[{"op":"type_text","text":"q","clear":"false"}]}""",
            """{"actions":[{"op":"type_text","text":"q"}]}"""
        )
        batches.forEach { arguments ->
            var calls = 0
            val result = ScreenActionBatchExecutor(policy) { _, _ ->
                calls++
                ToolProtocolResult.success("must not run")
            }.execute(arguments)
            assertEquals(0, calls)
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
            assertEquals("SCREEN_ACT_BATCH_REJECTED", result.diagnosticCode)
        }
    }

    @Test
    fun waitUsesConfiguredIntervalAndRejectsExcessiveBatchTotal() = runBlocking {
        val arguments = """{"actions":[{"op":"wait","ms":35},{"op":"screenshot"}]}"""
        val plan = ScreenActionBatchPlanner.plan(arguments, policy) as ScreenActionBatchPlan.Ready
        assertEquals(35L, plan.actions.first().waitMs)
        assertEquals(35L, Json.parseToJsonElement(plan.actions.first().argumentsJson).jsonObject
            .getValue("ms").jsonPrimitive.content.toLong())

        val startedAt = System.nanoTime()
        val result = ScreenActionBatchExecutor(policy) { operation, _ ->
            assertEquals(ScreenBatchOperation.SCREENSHOT, operation)
            ToolProtocolResult.success("Checkpoint saved")
        }.execute(arguments)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue("wait interval was ignored: " + elapsedMs + "ms", elapsedMs >= 25)

        val totalPolicy = policy.copy(
            maxTotalWaitMs = 40, maxCaptureImages = 10, maxCaptureBytes = 8 * 1024 * 1024L, maxDragRepeat = 64
        )
        val rejected = ScreenActionBatchPlanner.plan(
            """{"actions":[{"op":"wait","ms":35},{"op":"wait","ms":35}]}""",
            totalPolicy
        )
        assertTrue(rejected is ScreenActionBatchPlan.Rejected)
        assertTrue((rejected as ScreenActionBatchPlan.Rejected).reason.contains("max_total_wait_ms"))
    }

    @Test
    fun failedSubActionInterruptsRemainingActions() = runBlocking {
        val calls = mutableListOf<String>()
        val tools = mapOf(
            "click" to fakeTool("click") {
                calls += "click"
                ToolProtocolResult.failed("CLICK_FAILED", retryable = true)
            },
            "type_text" to fakeTool("type_text") {
                calls += "type_text"
                ToolProtocolResult.success("typed")
            }
        )
        val executor = batchExecutor(tools)

        val result = executor.execute("""{"actions":[
            {"op":"click","x":10,"y":10},
            {"op":"type_text","text":"hello","clear":true}
        ]}""")

        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertEquals("CLICK_FAILED", result.diagnosticCode)
        assertEquals(listOf("click"), calls)
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        assertEquals(0, details.getValue("completed").jsonPrimitive.content.toInt())
        assertEquals(1, details.getValue("attempted").jsonPrimitive.content.toInt())
        assertEquals(1, details.getValue("unexecuted_suffix").jsonArray.size)
    }

    @Test
    fun visibleNaturalLanguageDoesNotControlBatchExecution() = runBlocking {
        val calls = mutableListOf<String>()
        val tools = mapOf(
            "click" to fakeTool("click") {
                calls += "click"
                ToolProtocolResult.success("Visible page text includes: click failed")
            },
            "type_text" to fakeTool("type_text") {
                calls += "type_text"
                ToolProtocolResult.success("typed")
            }
        )
        val executor = batchExecutor(tools)

        val result = executor.execute("""{"actions":[
            {"op":"click","x":10,"y":10},
            {"op":"type_text","text":"hello","clear":true}
        ]}""")

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(result.outputText.startsWith("[SCREEN_ACT_BATCH_COMPLETED]"))
        assertEquals(listOf("click", "type_text"), calls)
    }

    @Test
    fun cancellationPropagatesWithTypedPrefixAndSuffixWithoutExecutingTheRest() = runBlocking {
        val cancellation = CancellationException("stop")
        val tools = mapOf(
            "click" to fakeTool("click") { ToolProtocolResult.success("clicked") },
            "type_text" to fakeTool("type_text") { throw cancellation },
            "press_key" to fakeTool("press_key") { ToolProtocolResult.success("pressed") }
        )
        val executor = ScreenActionBatchExecutor(
            policy = policy,
            executeAction = { operation, arguments ->
                tools.getValue(operation.testToolName()).execute(arguments)
            }
        )

        try {
            executor.execute("""{"actions":[
                {"op":"click","x":10,"y":10},
                {"op":"type_text","text":"hello","clear":true},
                {"op":"press_key","key":"ENTER"}
            ]}""")
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
            val receipt = requireNotNull(actual.screenActionBatchCancellationReceiptOrNull())
            assertEquals(
                "CANCELED",
                receipt.toJsonDetails().getValue("status").jsonPrimitive.content
            )
            assertEquals(
                listOf(3),
                receipt.toJsonDetails().getValue("unexecuted_suffix").jsonArray.map {
                    it.jsonObject.getValue("index").jsonPrimitive.content.toInt()
                }
            )
            assertEquals(3, receipt.toJsonDetails().getValue("total").jsonPrimitive.content.toInt())
            assertEquals(1, receipt.completedPrefix.single().index)
            assertEquals(2, receipt.attempted?.index)
            assertEquals(ToolExecutionStatus.CANCELED, receipt.attempted?.executionStatus)
            assertEquals(listOf(3), receipt.unexecutedSuffix.map { it.index })
        }
    }

    @Test
    fun returnedCanceledSubActionPreservesPrefixAndSuffixWithoutReplay() = runBlocking {
        val calls = mutableListOf<ScreenBatchOperation>()
        val result = ScreenActionBatchExecutor(policy) { operation, _ ->
            calls += operation
            ToolProtocolResult.canceled("CLICK_CANCELLED")
        }.execute("""{"actions":[{"op":"click","x":10,"y":10},{"op":"type_text","text":"hello","clear":true}]}""")

        assertEquals(listOf(ScreenBatchOperation.CLICK), calls)
        assertEquals(ToolExecutionStatus.CANCELED, result.executionStatus)
        assertEquals("SCREEN_ACT_BATCH_CANCELLED", result.diagnosticCode)
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        assertEquals(0, details.getValue("completed").jsonPrimitive.content.toInt())
        assertEquals(1, details.getValue("attempted").jsonPrimitive.content.toInt())
        assertEquals(listOf(2), details.getValue("unexecuted_suffix").jsonArray.map {
            it.jsonObject.getValue("index").jsonPrimitive.content.toInt()
        })
    }

    @Test
    fun exceptionFromSubActionStopsWithUnknownEffectReceiptAndKeepsPrefix() = runBlocking {
        val calls = mutableListOf<ScreenBatchOperation>()
        val result = ScreenActionBatchExecutor(policy) { operation, _ ->
            calls += operation
            if (operation == ScreenBatchOperation.CLICK) ToolProtocolResult.success("clicked")
            else throw IllegalStateException("backend unavailable")
        }.execute("""{"actions":[{"op":"click","x":10,"y":10},{"op":"type_text","text":"hello","clear":true},{"op":"press_key","key":"ENTER"}]}""")

        assertEquals(listOf(ScreenBatchOperation.CLICK, ScreenBatchOperation.TYPE_TEXT), calls)
        assertEquals(ToolExecutionStatus.INTERRUPTED, result.executionStatus)
        assertEquals("SCREEN_ACT_BATCH_INTERRUPTED", result.diagnosticCode)
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        assertEquals(1, details.getValue("completed").jsonPrimitive.content.toInt())
        assertEquals(2, details.getValue("attempted").jsonPrimitive.content.toInt())
        assertEquals(
            "UNKNOWN",
            details.getValue("attempted_action").jsonObject.getValue("effect").jsonPrimitive.content
        )
        assertEquals(
            "INTERRUPTED",
            details.getValue("attempted_action").jsonObject.getValue("execution_status").jsonPrimitive.content
        )
        assertEquals(listOf(3), details.getValue("unexecuted_suffix").jsonArray.map {
            it.jsonObject.getValue("index").jsonPrimitive.content.toInt()
        })
    }

    @Test
    fun returnedUnknownSubActionStopsAsInterruptedWithoutReplay() = runBlocking {
        val calls = mutableListOf<ScreenBatchOperation>()
        val result = ScreenActionBatchExecutor(policy) { operation, _ ->
            calls += operation
            ToolExecutionResult(
                outputText = "effect uncertain",
                executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                actionEvidence = ToolActionEvidence(
                    delivery = ActionDeliveryEvidence.UNKNOWN,
                    transition = StateTransitionEvidence.UNKNOWN,
                    goalProgress = GoalProgressEvidence.NOT_PROVEN
                )
            )
        }.execute("""{"actions":[{"op":"click","x":10,"y":10},{"op":"type_text","text":"hello","clear":true}]}""")

        assertEquals(listOf(ScreenBatchOperation.CLICK), calls)
        assertEquals(ToolExecutionStatus.INTERRUPTED, result.executionStatus)
        assertEquals("SCREEN_ACT_BATCH_INTERRUPTED", result.diagnosticCode)
        assertEquals(ActionDeliveryEvidence.UNKNOWN, result.actionEvidence?.delivery)
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        assertEquals("EFFECT_UNKNOWN", details.getValue("status").jsonPrimitive.content)
        assertEquals("UNKNOWN", details.getValue("attempted_action").jsonObject
            .getValue("effect").jsonPrimitive.content)
        assertEquals(listOf(2), details.getValue("unexecuted_suffix").jsonArray.map {
            it.jsonObject.getValue("index").jsonPrimitive.content.toInt()
        })
    }

    @Test
    fun longBatchResultsPreserveFocusAndTextControlReceiptsAfterPreviewTruncation() = runBlocking {
        val tools = mapOf(
            "click" to fakeTool("click") {
                ToolProtocolResult.success("x".repeat(700) +
                    "\n[LOCAL_VALIDATION] state=CHANGED\n" +
                    "[INPUT_FOCUS] editable=true; displayId=36")
            },
            "type_text" to fakeTool("type_text") {
                ToolProtocolResult.success(
                    "y".repeat(700) +
                        "\n[TEXT_INPUT] accepted=false; delivery_verified=false; outcome=NO_FOCUSED_INPUT_WINDOW"
                )
            }
        )
        val executor = batchExecutor(tools)

        val result = executor.execute("""{"actions":[
            {"op":"click","x":10,"y":10},
            {"op":"type_text","text":"hello","clear":true}
        ]}""")

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(result.outputText.contains("[INPUT_FOCUS] editable=true"))
        assertTrue(result.outputText.contains("[TEXT_INPUT] accepted=false"))
    }

    @Test
    fun deliveryCanContinueWithoutClaimingDestinationVerification() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = ScreenActionBatchExecutor(policy) { operation, _ ->
            calls += operation.name
            if (operation == ScreenBatchOperation.TYPE_TEXT) ToolProtocolResult.accepted("typed but not verified")
            else ToolProtocolResult.success("observed")
        }
        val result = executor.execute("""{"actions":[{"op":"click","x":100,"y":100},
            {"op":"type_text","text":"query","clear":true},{"op":"press_key","key":"ENTER"}]}""")
        assertEquals(listOf("CLICK", "TYPE_TEXT", "PRESS_KEY"), calls)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
    }

    @Test
    fun focusTypeAndSubmitExecuteInOrderOnVerifiedOutcomes() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ScreenActionBatchExecutor(policy) { operation, _ ->
            calls += operation.name
            ToolProtocolResult.success("observed")
        }.execute("""{"actions":[{"op":"click","x":100,"y":100},
            {"op":"type_text","text":"query","clear":true},{"op":"press_key","key":"ENTER"}]}""")
        assertEquals(listOf("CLICK", "TYPE_TEXT", "PRESS_KEY"), calls)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
    }

    @Test
    fun malformedLaterActionPreventsAllSideEffects() = runBlocking {
        var calls = 0
        val result = ScreenActionBatchExecutor(policy) { _, _ -> calls++; ToolProtocolResult.success("ok") }
            .execute("""{"actions":[{"op":"click","x":1001,"y":100},{"op":"type_text","text":"q","clear":true}]}""")
        assertEquals(0, calls)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
    }

    @Test
    fun sixGroundedActionsShareOneCallAndLateInvalidInputExecutesNothing() = runBlocking {
        val expanded = policy
        val arguments = """{"actions":[{"op":"click","x":100,"y":100},
            {"op":"type_text","text":"first","clear":true},{"op":"click","x":100,"y":200},
            {"op":"type_text","text":"second","clear":true},{"op":"press_key","key":"TAB"},
            {"op":"type_text","text":"third","clear":true}]}"""
        val calls = mutableListOf<ScreenBatchOperation>()
        val executor = ScreenActionBatchExecutor(expanded) { operation, _ ->
            calls += operation
            ToolProtocolResult.success("verified")
        }
        assertEquals(ToolExecutionStatus.SUCCEEDED, executor.execute(arguments).executionStatus)
        assertEquals(listOf(ScreenBatchOperation.CLICK, ScreenBatchOperation.TYPE_TEXT,
            ScreenBatchOperation.CLICK, ScreenBatchOperation.TYPE_TEXT,
            ScreenBatchOperation.PRESS_KEY, ScreenBatchOperation.TYPE_TEXT), calls)
        calls.clear()
        assertEquals(ToolExecutionStatus.NOT_EXECUTED,
            executor.execute(arguments.replace("\"text\":\"third\"", "\"text\":false")).executionStatus)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun independentlyTargetedInputsHaveNoArbitraryCountLimit() {
        for (count in listOf(2, 7, 9, 32)) {
            val actions = (0 until count).joinToString(",") {
                """{"op":"type_text","x":${it * 20},"y":400,"text":"value-$it","clear":true}"""
            }
            val plan = ScreenActionBatchPlanner.plan("""{"actions":[$actions]}""", policy)
                as ScreenActionBatchPlan.Ready
            assertEquals(count, plan.actions.size)
            plan.actions.forEachIndexed { index, action ->
                val payload = Json.parseToJsonElement(action.argumentsJson).jsonObject
                assertEquals((index * 20).toString(), payload.getValue("x").jsonPrimitive.content)
                assertEquals("400", payload.getValue("y").jsonPrimitive.content)
                assertEquals("true", payload.getValue("clear").jsonPrimitive.content)
            }
        }
        for (target in listOf("\"x\":10", "\"x\":10,\"y\":1001", "\"x\":\"10\",\"y\":20")) {
            assertTrue(ScreenActionBatchPlanner.plan(
                """{"actions":[{"op":"type_text","text":"a","clear":true,$target}]}""", policy
            ) is ScreenActionBatchPlan.Rejected)
        }
    }

    @Test
    fun planCarriesObservationIdAndTargetReferences() {
        val plan = ScreenActionBatchPlanner.plan(
            """{
                "observation_id": "obs_12345",
                "actions": [
                    {"op":"click", "target_id": "btn_submit"},
                    {"op":"type_text", "text": "100", "x": 300, "y": 400, "clear": true}
                ]
            }""",
            policy
        )
        assertTrue(plan is ScreenActionBatchPlan.Ready)
        val ready = plan as ScreenActionBatchPlan.Ready
        assertEquals("obs_12345", ready.observationId)
        assertEquals(2, ready.actions.size)
        assertEquals("btn_submit", ready.actions[0].targetId)
        assertNull(ready.actions[0].targetX)
        assertEquals(300, ready.actions[1].targetX)
        assertEquals(400, ready.actions[1].targetY)
    }

    @Test
    fun batchExecutionCarriesTargetReferencesToReceipts() = runBlocking {
        val mappedActionsReceived = mutableListOf<MappedScreenAction>()
        val executor = ScreenActionBatchExecutor(policy) { _, _, mapped ->
            mappedActionsReceived.add(mapped)
            if (mapped.targetId == "fail_me") {
                ToolProtocolResult.failed("TARGET_OCCLUDED", false)
            } else {
                ToolProtocolResult.accepted("ok")
            }
        }
        val result = executor.execute(
            """{
                "actions": [
                    {"op":"click", "target_id": "btn_1"},
                    {"op":"click", "target_id": "fail_me"},
                    {"op":"type_text", "text": "abc", "x": 100, "y": 200, "clear": true}
                ]
            }"""
        )
        assertEquals(2, mappedActionsReceived.size)
        assertEquals("btn_1", mappedActionsReceived[0].targetId)
        assertEquals("fail_me", mappedActionsReceived[1].targetId)

        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        val completed = details.getValue("completed_prefix").jsonArray
        assertEquals(1, completed.size)
        assertEquals("btn_1", completed[0].jsonObject.getValue("target").jsonPrimitive.content)

        val attempted = details.getValue("attempted_action").jsonObject
        assertEquals("fail_me", attempted.getValue("target").jsonPrimitive.content)

        val unexecuted = details.getValue("unexecuted_suffix").jsonArray
        assertEquals(1, unexecuted.size)
        assertEquals("(100,200)", unexecuted[0].jsonObject.getValue("target").jsonPrimitive.content)
    }

    private fun fakeTool(name: String, execute: suspend () -> ToolExecutionResult): Tool = object : Tool {
        override val name: String = name
        override val definition = ToolDefinition(name, name, emptyMap())
        override suspend fun execute(arguments: String): ToolExecutionResult = execute()
    }

    private fun batchExecutor(tools: Map<String, Tool>) = ScreenActionBatchExecutor(
        policy = policy,
        executeAction = { operation, arguments ->
            tools.getValue(operation.testToolName()).execute(arguments)
        }
    )

    private fun ScreenBatchOperation.testToolName(): String = when (this) {
        ScreenBatchOperation.CLICK -> "click"
        ScreenBatchOperation.TYPE_TEXT -> "type_text"
        ScreenBatchOperation.PRESS_KEY -> "press_key"
        ScreenBatchOperation.SCROLL -> "scroll"
        ScreenBatchOperation.SWIPE -> "swipe"
        ScreenBatchOperation.DRAG -> "drag"
        ScreenBatchOperation.PATH -> "path"
        ScreenBatchOperation.WAIT -> "wait"
        ScreenBatchOperation.SCREENSHOT -> "screenshot"
    }
}
