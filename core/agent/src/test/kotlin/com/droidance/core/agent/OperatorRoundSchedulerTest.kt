// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RoundDiagnosticCode
import com.droidance.core.agent.harness.RoundExecutionMode
import com.droidance.core.agent.harness.RoundFollowUp
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorRoundSchedulerTest {
    private val scheduler = OperatorRoundScheduler(
        listOf(
            contract("read_a", ToolBinding.TURN_LOCAL, ToolEffect.READ_ONLY),
            contract("read_b", ToolBinding.TURN_LOCAL, ToolEffect.READ_ONLY),
            contract("verify_deep_link", ToolBinding.TURN_LOCAL, ToolEffect.READ_ONLY),
            contract("click", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING),
            contract("ask_human", ToolBinding.TURN_LOCAL, ToolEffect.USER_INTERVENTION),
            contract("present_result_card", ToolBinding.TURN_LOCAL, ToolEffect.COMPLETION),
            contract("done", ToolBinding.TURN_LOCAL, ToolEffect.COMPLETION)
        )
    )

    @Test
    fun onlyPureLocalReadsBecomeAParallelBatch() {
        val plan = scheduler.plan(
            publicBlocks = listOf("读取两项本地信息"),
            toolCalls = listOf(call("a", "read_a"), call("b", "read_b")),
            startCommitted = false,
            presentedToolNames = setOf("read_a", "read_b")
        )

        assertEquals(RoundExecutionMode.PARALLEL, plan.executionMode)
        assertEquals(listOf(0, 1), plan.selectedCalls.map { it.providerOrder })
        assertTrue(plan.rejectedCalls.isEmpty())
    }

    @Test
    fun parallelFailuresAreIsolatedAndOutcomesReturnInProviderOrder() = runBlocking {
        val calls = listOf(call("a", "read_a"), call("b", "read_b"))
        val plan = scheduler.plan(
            listOf("读取两项本地信息"),
            calls,
            startCommitted = false,
            presentedToolNames = setOf("read_a", "read_b")
        )
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val outcomes = withTimeout(2_000L) {
            scheduler.executeParallel(plan, calls) { call ->
                when (call.name) {
                    "read_a" -> {
                        firstStarted.complete(Unit)
                        secondStarted.await()
                        ToolProtocolResult.success("first result")
                    }
                    else -> {
                        secondStarted.complete(Unit)
                        firstStarted.await()
                        error("second failed")
                    }
                }
            }
        }

        assertEquals(listOf(0, 1), outcomes.map { it.providerOrder })
        assertEquals(ToolExecutionStatus.SUCCEEDED, outcomes[0].result?.executionStatus)
        assertEquals("first result", outcomes[0].result?.outputText)
        assertEquals(null, outcomes[0].error)
        assertEquals(null, outcomes[1].result)
        assertEquals("second failed", outcomes[1].error?.message)
    }

    @Test
    fun userInterventionIsExclusiveAndRejectsOtherCallsBeforeExecution() {
        val plan = scheduler.plan(
            publicBlocks = listOf("请确认后继续"),
            toolCalls = listOf(
                call("click", "click"),
                call("approval", "ask_human"),
                call("done", "done")
            ),
            startCommitted = true,
            presentedToolNames = setOf("click", "ask_human", "done")
        )

        assertEquals(listOf("ask_human"), plan.selectedCalls.map { it.providerCall.name })
        assertEquals(RoundExecutionMode.SERIAL, plan.executionMode)
        assertEquals(RoundFollowUp.WAIT_FOR_USER, plan.followUp)
        assertEquals(listOf(0, 2), plan.rejectedCalls.map { it.call.providerOrder })
        assertTrue(plan.rejectedCalls.all {
            it.diagnosticCode == RoundDiagnosticCode.INTERVENTION_EXCLUSIVE
        })
    }

    @Test
    fun localRouteVerificationCanPrecedeOneDeviceActionInTheSameRound() {
        val plan = scheduler.plan(
            publicBlocks = listOf("确认落地页后点击明确的入口"),
            toolCalls = listOf(call("verify", "verify_deep_link"), call("click", "click")),
            startCommitted = true,
            presentedToolNames = setOf("verify_deep_link", "click")
        )

        assertEquals(RoundExecutionMode.SERIAL, plan.executionMode)
        assertEquals(
            listOf("verify_deep_link", "click"),
            plan.selectedCalls.map { it.providerCall.name }
        )
        assertTrue(plan.rejectedCalls.isEmpty())
    }

    @Test
    fun aRegisteredButUnpresentedToolIsRejectedAsRestrictedBeforeExecution() {
        val plan = scheduler.plan(
            publicBlocks = listOf("继续"),
            toolCalls = listOf(call("click", "click")),
            startCommitted = true,
            presentedToolNames = setOf("done")
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(
            RoundDiagnosticCode.TOOL_NOT_PRESENTED,
            plan.rejectedCalls.single().diagnosticCode
        )
    }

    @Test
    fun aTrulyUnregisteredToolIsRejectedAsUnknownTool() {
        val plan = scheduler.plan(
            publicBlocks = listOf("继续"),
            toolCalls = listOf(call("foo", "unknown_custom_tool")),
            startCommitted = true,
            presentedToolNames = setOf("done")
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(
            RoundDiagnosticCode.UNKNOWN_TOOL,
            plan.rejectedCalls.single().diagnosticCode
        )
    }

    @Test
    fun completionOnlyRoundDoesNotStartDeviceExecution() {
        listOf("done", "present_result_card").forEach { completionTool ->
            val plan = scheduler.plan(
                publicBlocks = listOf("请告诉我具体需要什么帮助"),
                toolCalls = listOf(call(completionTool, completionTool)),
                startCommitted = false,
                presentedToolNames = setOf(completionTool)
            )

            assertEquals(
                listOf(completionTool),
                plan.selectedCalls.map { it.providerCall.name }
            )
            assertNull(plan.firstDeviceExecutionStartCall())
        }
    }

    @Test
    fun onlyDeviceBoundToolStartsDeviceExecution() {
        val localPlan = scheduler.plan(
            publicBlocks = listOf("读取本地资料"),
            toolCalls = listOf(call("read", "read_a")),
            startCommitted = false,
            presentedToolNames = setOf("read_a")
        )
        val plan = scheduler.plan(
            publicBlocks = listOf("开始执行"),
            toolCalls = listOf(call("click", "click")),
            startCommitted = false,
            presentedToolNames = setOf("click")
        )

        assertNull(localPlan.firstDeviceExecutionStartCall())
        assertEquals("click", plan.firstDeviceExecutionStartCall()?.providerCall?.name)
    }

    @Test
    fun genericPreflightRejectsBeforeADeviceToolCanStart() {
        val plan = scheduler.plan(
            publicBlocks = listOf("先打开目标页面"),
            toolCalls = listOf(call("open", "click")),
            startCommitted = false,
            presentedToolNames = setOf("click"),
            preflightDiagnostics = mapOf(0 to "POLICY_REJECTED")
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(RoundExecutionMode.NONE, plan.executionMode)
        assertEquals("POLICY_REJECTED", plan.rejectedCalls.single().diagnosticCode)
        assertNull(plan.firstDeviceExecutionStartCall())
    }

    private fun contract(
        name: String,
        binding: ToolBinding,
        effect: ToolEffect
    ) = RegisteredToolContract(name, "$name description", binding, effect, emptyMap())

    private fun call(id: String, name: String) = ToolCall(id = id, name = name, arguments = "{}")
}
