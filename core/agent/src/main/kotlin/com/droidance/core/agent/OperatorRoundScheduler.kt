// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.RoundExecutionMode
import com.droidance.core.agent.harness.RoundExecutionPlan
import com.droidance.core.agent.harness.RoundExecutionPlanner
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.attempt.AttemptBindingResolution
import com.droidance.core.agent.attempt.DeviceToolCapabilityRequirement
import com.droidance.core.agent.attempt.ExecutionRoundPlanner
import com.droidance.core.agent.attempt.PreparedRoundExecutionPlan
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.tools.ToolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Production-side adapter for the provider-independent round planner.
 *
 * Planning always finishes before this class is allowed to enter a tool handler. Only a round
 * classified entirely as TURN_LOCAL + READ_ONLY can reach [executeParallel].
 */
class OperatorRoundScheduler(
    registrations: Collection<RegisteredToolContract>,
    capabilityRequirements: Collection<DeviceToolCapabilityRequirement> = emptyList()
) {
    private val registrations = registrations.toList()
    private val capabilityRequirements = capabilityRequirements.toList()

    fun plan(
        publicBlocks: List<String>,
        toolCalls: List<ToolCall>,
        startCommitted: Boolean,
        presentedToolNames: Set<String>,
        repairPurpose: RepairPurpose = RepairPurpose.NONE,
        preflightDiagnostics: Map<Int, String> = emptyMap()
    ): RoundExecutionPlan {
        val planner = RoundExecutionPlanner(
            registrations = registrations,
            presentedToolNames = presentedToolNames,
            callIdFactory = { providerOrder, _ -> "operator-call-$providerOrder" },
            preflight = { call -> preflightDiagnostics[call.providerOrder] }
        )
        return planner.plan(
            round = NormalizedModelRound.create(
                publicBlocks = publicBlocks,
                toolCalls = toolCalls.map { call ->
                    ProviderToolCall(
                        providerCallId = call.id.takeIf(String::isNotBlank),
                        name = call.name,
                        argumentsJson = ToolJson.sanitizeArgumentsJson(call.arguments)
                    )
                }
            ),
            startCommitted = startCommitted,
            repairPurpose = repairPurpose
        )
    }

    /**
     * Plans the Provider response against one frozen execution binding. The round id is part of
     * every Harness call id so a retry or a later round can never claim another round's call.
     */
    fun planPrepared(
        roundId: String,
        publicBlocks: List<String>,
        toolCalls: List<ToolCall>,
        startCommitted: Boolean,
        repairPurpose: RepairPurpose,
        presentedToolNames: Set<String>,
        attemptBindingProvider: () -> AttemptBindingResolution,
        preflightResults: Map<Int, ToolExecutionResult> = emptyMap(),
        mutationsRestricted: Boolean = false,
        finishMetadata: Map<String, String> = emptyMap()
    ): PreparedRoundExecutionPlan {
        require(roundId.isNotBlank()) { "ModelRound id cannot be blank" }
        val round = NormalizedModelRound.create(
            publicBlocks = publicBlocks,
            finishMetadata = finishMetadata,
            toolCalls = toolCalls.map { call ->
                ProviderToolCall(
                    providerCallId = call.id.takeIf(String::isNotBlank),
                    name = call.name,
                    argumentsJson = ToolJson.sanitizeArgumentsJson(call.arguments)
                )
            }
        )
        return ExecutionRoundPlanner(
            registrations = registrations,
            capabilityRequirements = capabilityRequirements,
            presentedToolNames = presentedToolNames,
            callIdFactory = { providerOrder, _ -> "$roundId:call:$providerOrder" },
            preflight = { call -> preflightResults[call.providerOrder] },
            mutationsRestricted = mutationsRestricted
        ).plan(
            round = round,
            startCommitted = startCommitted,
            repairPurpose = repairPurpose,
            attemptBindingProvider = attemptBindingProvider
        )
    }

    /**
     * Starts the selected pure-read handlers together, isolates non-cancellation failures, and
     * returns outcomes in Provider order regardless of physical completion order.
     */
    suspend fun executeParallel(
        plan: RoundExecutionPlan,
        toolCalls: List<ToolCall>,
        execute: suspend (ToolCall) -> ToolExecutionResult
    ): List<ParallelToolOutcome> {
        if (plan.executionMode != RoundExecutionMode.PARALLEL) return emptyList()
        val callsByOrder = toolCalls.withIndex().associate { it.index to it.value }
        return coroutineScope {
            plan.selectedCalls
                .sortedBy { it.providerOrder }
                .map { planned ->
                    async(Dispatchers.IO) {
                        val toolCall = checkNotNull(callsByOrder[planned.providerOrder])
                        try {
                            ParallelToolOutcome(
                                providerOrder = planned.providerOrder,
                                result = execute(toolCall)
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            ParallelToolOutcome(
                                providerOrder = planned.providerOrder,
                                error = error
                            )
                        }
                    }
                }
                .awaitAll()
                .sortedBy(ParallelToolOutcome::providerOrder)
        }
    }
}

data class ParallelToolOutcome(
    val providerOrder: Int,
    val result: ToolExecutionResult? = null,
    val error: Exception? = null
) {
    init {
        require(providerOrder >= 0) { "Provider order cannot be negative" }
        require((result == null) != (error == null)) {
            "Parallel tool outcome must contain exactly one result or error"
        }
    }
}

/** Only an accepted DEVICE_BOUND call commits the turn to phone execution. */
fun RoundExecutionPlan.firstDeviceExecutionStartCall() =
    selectedCalls.firstOrNull { it.contract.binding == ToolBinding.DEVICE_BOUND }
