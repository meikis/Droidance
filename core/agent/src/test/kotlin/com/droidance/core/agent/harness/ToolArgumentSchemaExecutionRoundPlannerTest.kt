// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.attempt.AttemptBindingResolution
import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.attempt.DeviceToolCapabilityRequirement
import com.droidance.core.agent.attempt.ExecutionRoundPlanner
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.model.llm.models.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentSchemaExecutionRoundPlannerTest {
    @Test
    fun invalid_device_arguments_do_not_resolve_an_execution_attempt() {
        var attemptProviderCalls = 0
        val planner = ExecutionRoundPlanner(
            registrations = listOf(
                RegisteredToolContract(
                    name = "click",
                    description = "Click a test coordinate",
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING,
                    parameters = mapOf(
                        "x" to ToolParameter("integer", "X coordinate", required = true),
                        "y" to ToolParameter("integer", "Y coordinate", required = true)
                    )
                )
            ),
            capabilityRequirements = listOf(
                DeviceToolCapabilityRequirement("click", DeviceCapability.CLICK)
            ),
            callIdFactory = { index, _ -> "call-$index" }
        )

        val plan = planner.plan(
            round = NormalizedModelRound.create(
                publicBlocks = listOf("我先处理"),
                toolCalls = listOf(
                    ProviderToolCall("provider-0", "click", "{\"x\":\"10\",\"y\":20}")
                )
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = {
                attemptProviderCalls += 1
                AttemptBindingResolution.Missing
            }
        )

        assertTrue(plan.roundPlan.selectedCalls.isEmpty())
        assertEquals(
            ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
            plan.roundPlan.rejectedCalls.single().diagnosticCode
        )
        assertEquals(0, attemptProviderCalls)
        assertNull(plan.attemptBinding)
        assertNull(plan.candidateCapabilitySnapshotRef)
        assertNull(plan.candidateCapabilitySnapshotHash)
        assertTrue(plan.toolStarts.isEmpty())
        assertEquals(
            ToolExecutionStatus.NOT_EXECUTED,
            plan.toolRejections.single().result.executionStatus
        )
    }

    @Test
    fun invalid_declared_local_query_closes_the_action_barrier_without_preparing_device() {
        var preflightCalls = 0
        var attemptProviderCalls = 0
        val planner = ExecutionRoundPlanner(
            registrations = listOf(
                RegisteredToolContract(
                    name = "query",
                    description = "Read local evidence",
                    binding = ToolBinding.TURN_LOCAL,
                    effect = ToolEffect.READ_ONLY,
                    parameters = mapOf(
                        "scope" to ToolParameter("string", "Query scope", required = true)
                    ),
                    requiresResultBeforeAction = true
                ),
                RegisteredToolContract(
                    name = "click",
                    description = "Click a test coordinate",
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING,
                    parameters = emptyMap()
                )
            ),
            capabilityRequirements = listOf(
                DeviceToolCapabilityRequirement("click", DeviceCapability.CLICK)
            ),
            callIdFactory = { index, _ -> "call-$index" },
            preflight = { call ->
                preflightCalls += 1
                error("The invalid query and the rejected action must not reach preflight")
            }
        )

        val plan = planner.plan(
            round = NormalizedModelRound.create(
                publicBlocks = listOf("先读取依据"),
                toolCalls = listOf(
                    ProviderToolCall("provider-0", "query", "{\"scope\":7}"),
                    ProviderToolCall("provider-1", "click", "{}")
                )
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = {
                attemptProviderCalls += 1
                AttemptBindingResolution.Missing
            }
        )

        assertTrue(plan.roundPlan.selectedCalls.isEmpty())
        assertEquals(
            listOf(
                ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
                RoundDiagnosticCode.QUERY_RESULT_REQUIRED
            ),
            plan.roundPlan.rejectedCalls.map { it.diagnosticCode }
        )
        assertEquals(listOf("query", "click"), plan.toolRejections.map { it.call.providerCall.name })
        assertTrue(plan.toolStarts.isEmpty())
        assertEquals(
            listOf(ToolExecutionStatus.NOT_EXECUTED, ToolExecutionStatus.NOT_EXECUTED),
            plan.toolRejections.map { it.result.executionStatus }
        )
        assertEquals(0, preflightCalls)
        assertEquals(0, attemptProviderCalls)
        assertNull(plan.attemptBinding)
        assertEquals(setOf("call-0", "call-1"), plan.roundPlan.allCallIds)
    }

    @Test
    fun failed_declared_local_query_preserves_result_and_does_not_preflight_or_prepare_action() {
        var preflightCalls = 0
        var attemptProviderCalls = 0
        val planner = ExecutionRoundPlanner(
            registrations = listOf(
                RegisteredToolContract(
                    name = "query",
                    description = "Read local evidence",
                    binding = ToolBinding.TURN_LOCAL,
                    effect = ToolEffect.READ_ONLY,
                    parameters = emptyMap(),
                    requiresResultBeforeAction = true
                ),
                RegisteredToolContract(
                    name = "click",
                    description = "Click a test coordinate",
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING,
                    parameters = emptyMap()
                )
            ),
            capabilityRequirements = listOf(
                DeviceToolCapabilityRequirement("click", DeviceCapability.CLICK)
            ),
            callIdFactory = { index, _ -> "call-$index" },
            preflight = { call ->
                preflightCalls += 1
                assertEquals("query", call.providerCall.name)
                ToolExecutionResult(
                    outputText = "query failed",
                    executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                    diagnosticCode = "QUERY_FAILED"
                )
            }
        )

        val plan = planner.plan(
            round = NormalizedModelRound.create(
                publicBlocks = listOf("先读取依据"),
                toolCalls = listOf(
                    ProviderToolCall("provider-0", "query", "{}"),
                    ProviderToolCall("provider-1", "click", "{}")
                )
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = {
                attemptProviderCalls += 1
                AttemptBindingResolution.Missing
            }
        )

        assertTrue(plan.roundPlan.selectedCalls.isEmpty())
        assertEquals(
            listOf("QUERY_FAILED", "QUERY_RESULT_REQUIRED"),
            plan.roundPlan.rejectedCalls.map { it.diagnosticCode }
        )
        assertEquals(listOf("query", "click"), plan.toolRejections.map { it.call.providerCall.name })
        assertEquals("query failed", plan.toolRejections[0].result.outputText)
        assertEquals("QUERY_FAILED", plan.toolRejections[0].result.diagnosticCode)
        assertEquals(
            listOf(ToolExecutionStatus.NOT_EXECUTED, ToolExecutionStatus.NOT_EXECUTED),
            plan.toolRejections.map { it.result.executionStatus }
        )
        assertTrue(plan.toolStarts.isEmpty())
        assertEquals(1, preflightCalls)
        assertEquals(0, attemptProviderCalls)
        assertNull(plan.attemptBinding)
    }
}
