// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.harness.DurableModelRoundCoordinator
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.model.llm.models.ToolCall

/** Per-turn owner of pending durable rounds and the committed public-start fact. */
class OperatorRoundSession(
    private val coordinator: DurableModelRoundCoordinator,
    private val scheduler: OperatorRoundScheduler,
    private val scope: TurnEventScope,
    initialStartCommitted: Boolean,
    private val modelParameters: Map<String, Map<String, com.droidance.core.model.llm.models.ToolParameter>>
) {
    private val committer = OperatorRoundCommitter(coordinator)
    var publicStartCommitted = initialStartCommitted
        private set
    var pending: DurableModelRoundCoordinator.RoundHandle? = null
        private set

    private fun markPublicStartCommitted() { publicStartCommitted = true }

    suspend fun begin(repairPurpose: RepairPurpose) {
        if (pending != null) return
        pending = coordinator.begin(
            turnScope = scope, roundId = coordinator.newRoundId(),
            startCommitted = publicStartCommitted, repairPurpose = repairPurpose
        )
    }

    suspend fun settle(diagnosticCode: String, requestMetrics: Map<String, String>) {
        val handle = pending ?: return
        val prepared = com.droidance.core.agent.harness.prepareInterruptedExecution(diagnosticCode)
        coordinator.commit(
            handle = handle,
            normalizedRound = NormalizedModelRound.create(
                publicBlocks = emptyList(), toolCalls = emptyList(),
                finishMetadata = requestMetrics + mapOf("diagnostic_code" to diagnosticCode)
            ),
            preparedExecution = prepared, presentationsByCallId = emptyMap()
        )
        pending = null
    }

    suspend fun commit(
        handle: DurableModelRoundCoordinator.RoundHandle,
        presentations: Map<String, ToolPresentationSnapshot?>,
        presentedToolNames: Set<String>, publicBlocks: List<String>,
        calls: List<ToolCall>, finishMetadata: Map<String, String>,
        actionTitlesByProviderOrder: Map<Int, String> = emptyMap(),
        mutationsRestricted: Boolean = false
    ): OperatorRoundCommitter.Commit {
        val result = committer.commit(
            handle, scheduler, presentations, presentedToolNames, publicBlocks, calls,
            finishMetadata, modelParameters, actionTitlesByProviderOrder,
            mutationsRestricted = mutationsRestricted
        )
        if (result.publicStartCommitted) markPublicStartCommitted()
        pending = null
        return result
    }
}
