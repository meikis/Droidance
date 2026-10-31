// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.attempt.AttemptBindingResolution
import com.droidance.core.agent.attempt.AttemptDiagnosticCode
import com.droidance.core.agent.attempt.ExecutionBackendObservation
import com.droidance.core.agent.attempt.ExecutionAttemptBindingPreparer
import com.droidance.core.agent.attempt.ExecutionAttemptRoute
import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionRoundPlanner
import com.droidance.core.agent.attempt.NewExecutionAttemptIdentity
import com.droidance.core.agent.attempt.PreparedRoundExecutionPlan
import com.droidance.core.agent.device.DeviceExecutionStrategy
import com.droidance.core.agent.event.ModelRoundScope
import com.droidance.core.agent.event.ModelRoundPlanRecordResult
import com.droidance.core.agent.event.DurableExecutionAttemptSnapshot
import com.droidance.core.agent.event.PreparedModelRoundCommit
import com.droidance.core.agent.event.PreparedModelRoundStart
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.SubmittedItemSequenceRange
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.TurnEventScope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the durable boundary around one Provider request.
 *
 * The request boundary and complete response plan are intentionally created by one production
 * object. A device binding is prepared only after the response contains a DEVICE_BOUND call;
 * pure chat therefore never initializes an Android backend. TaskCoordinator may observe and execute
 * the returned plan, but it cannot call a handler before [commit] has persisted every public
 * block, tool call and disposition in one EventStore batch.
 */
@Singleton
class DurableModelRoundCoordinator @Inject constructor(
    private val eventStore: AgentEventStore,
    private val bindingPreparer: ExecutionAttemptBindingPreparer
) {
    data class RoundHandle(
        val scope: ModelRoundScope,
        val repairPurpose: RepairPurpose,
        val startCommitted: Boolean,
        val submittedItems: SubmittedItemSequenceRange,
        val roundDetailRef: String,
        val cancellationScopeId: String,
        var bindingResolution: AttemptBindingResolution
    )

    suspend fun begin(
        turnScope: TurnEventScope,
        roundId: String,
        startCommitted: Boolean,
        repairPurpose: RepairPurpose = RepairPurpose.NONE,
        cancellationScopeId: String = turnScope.turnId
    ): RoundHandle {
        require(roundId.isNotBlank()) { "ModelRound id cannot be blank" }
        val scope = ModelRoundScope(
            threadId = turnScope.threadId,
            turnId = turnScope.turnId,
            roundId = roundId
        )
        val roundDetailRef = "model-round:$roundId"
        val submittedItems = eventStore.submittedItemRangeForTurn(turnScope.turnId)
        eventStore.recordModelRoundStarted(
            PreparedModelRoundStart.create(
                scope = scope,
                repairPurpose = repairPurpose,
                startCommitted = startCommitted,
                submittedItems = submittedItems,
                roundDetailRef = roundDetailRef
            )
        )
        return RoundHandle(
            scope = scope,
            repairPurpose = repairPurpose,
            startCommitted = startCommitted,
            submittedItems = submittedItems,
            roundDetailRef = roundDetailRef,
            cancellationScopeId = cancellationScopeId,
            bindingResolution = AttemptBindingResolution.Missing
        )
    }

    /**
     * Prepares the exact backend only after the model selected a DEVICE_BOUND tool. The result is
     * cached on the round handle so retries and planning cannot allocate a second binding.
     */
    suspend fun prepareDeviceBinding(
        handle: RoundHandle,
        strategy: DeviceExecutionStrategy,
        expectedBackend: ExecutionBackendObservation? = null
    ): AttemptBindingResolution {
        val current = handle.bindingResolution
        if (current != AttemptBindingResolution.Missing) return current

        // Native App-UID operations may enter without a prepared shell backend.
        // A later device call receives the normal missing-binding preflight receipt.
        val runtimeMode = strategy.selection.effectiveMode ?: return AttemptBindingResolution.Missing
        val route = ExecutionAttemptRoute(
            runtimeMode = runtimeMode,
            executionSpace = strategy.spaceSelection.effectiveSpace
        )
        val turnScope = TurnEventScope(
            threadId = handle.scope.threadId,
            turnId = handle.scope.turnId
        )
        val existingAttempt = eventStore.latestExecutionAttemptForTurn(turnScope)
        val replacingRecovery = existingAttempt?.let { eventStore.canReplaceRecoveredAttempt(it) } == true
        val resolution = when (existingAttempt?.state?.takeUnless { replacingRecovery }) {
            null -> bindingPreparer.prepareNew(
                identity = NewExecutionAttemptIdentity(
                    attemptId = "attempt:${handle.scope.roundId}",
                    executionId = "execution:${handle.scope.roundId}",
                    cancellationScopeId = handle.cancellationScopeId,
                    capabilitySnapshotRef = "capability:${handle.scope.roundId}"
                ),
                route = route,
                expectedBackend = expectedBackend
            )

            ExecutionAttemptState.CREATED -> {
                if (!existingAttempt.matches(route)) {
                    existingAttempt.rejected(AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH)
                } else {
                    val runningAttempt = eventStore.ensureExecutionAttemptRunning(
                        scope = existingAttempt.scope,
                        transitionId = "round:${handle.scope.roundId}:resume-created",
                        expectedLifecycleSequence = existingAttempt.lifecycleSequence
                    )
                    bindingPreparer.restoreExisting(
                        snapshot = runningAttempt,
                        route = route
                    )
                }
            }

            ExecutionAttemptState.RUNNING -> bindingPreparer.restoreExisting(
                snapshot = existingAttempt,
                route = route
            )

            else -> existingAttempt.rejected(AttemptDiagnosticCode.EXECUTION_NOT_RUNNING)
        }
        val verifiedResolution = if (replacingRecovery && resolution is AttemptBindingResolution.Ready) {
            val context = resolution.binding.attempt.context
            val previous = checkNotNull(existingAttempt).context
            if (context.runtimeMode != previous.runtimeMode || context.executionSpace != previous.executionSpace ||
                context.displayId != previous.displayId
            ) existingAttempt.rejected(AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH) else resolution
        } else resolution
        handle.bindingResolution = verifiedResolution
        return verifiedResolution
    }

    suspend fun commit(
        handle: RoundHandle,
        normalizedRound: NormalizedModelRound,
        preparedExecution: PreparedRoundExecutionPlan,
        presentationsByCallId: Map<String, ToolPresentationSnapshot>,
        publicMessageId: String? = null
    ): ModelRoundPlanRecordResult = eventStore.recordModelRoundPlan(
        PreparedModelRoundCommit.create(
            scope = handle.scope,
            expectedStartCommitted = handle.startCommitted,
            repairPurpose = handle.repairPurpose,
            submittedItems = handle.submittedItems,
            roundDetailRef = handle.roundDetailRef,
            normalizedRound = normalizedRound,
            preparedExecution = preparedExecution,
            presentationsByCallId = presentationsByCallId,
            publicMessageId = publicMessageId
        )
    )

    /**
     * Closes the same durable Provider boundary for Phase A without registering or resolving
     * any tool or Android capability. providerOutput is validated public body text, never the
     * wire envelope. Activation commits its START in the same atomic batch as the closed round.
     */
    suspend fun commitZeroToolResponse(
        handle: RoundHandle,
        providerOutput: String?,
        decision: ZeroToolRoundDecision,
        diagnosticCode: String? = null,
        failure: Exception? = null,
        requestMetadata: Map<String, String> = emptyMap()
    ): ModelRoundPlanRecordResult {
        require(handle.bindingResolution == AttemptBindingResolution.Missing) {
            "A zero-tool ModelRound cannot own an execution binding"
        }
        require(decision == ZeroToolRoundDecision.FAILED || !providerOutput.isNullOrBlank()) {
            "A successful zero-tool ModelRound requires Provider output"
        }
        require(decision != ZeroToolRoundDecision.FAILED || providerOutput.isNullOrBlank()) {
            "A failed zero-tool ModelRound cannot publish partial Provider output"
        }
        require((decision == ZeroToolRoundDecision.FAILED) == !diagnosticCode.isNullOrBlank()) {
            "Only a failed zero-tool ModelRound requires a diagnostic code"
        }

        val normalizedRound = NormalizedModelRound.create(
            publicBlocks = listOfNotNull(providerOutput),
            toolCalls = emptyList(),
            finishMetadata = requestMetadata + zeroToolFinishMetadata(decision, diagnosticCode, failure)
        )
        require(!normalizedRound.isZeroToolActivation() || !handle.startCommitted) {
            "Activation cannot commit a second START"
        }
        val preparedExecution = prepareZeroToolExecution(normalizedRound)
        return commit(
            handle = handle,
            normalizedRound = normalizedRound,
            preparedExecution = preparedExecution,
            presentationsByCallId = emptyMap(),
            publicMessageId = if (normalizedRound.isZeroToolActivation()) "assistant:${handle.scope.roundId}" else null
        )
    }

    fun newRoundId(): String = UUID.randomUUID().toString()

    private fun DurableExecutionAttemptSnapshot.matches(route: ExecutionAttemptRoute): Boolean =
        context.runtimeMode == route.runtimeMode && context.executionSpace == route.executionSpace

    private fun DurableExecutionAttemptSnapshot.rejected(
        diagnosticCode: String
    ): AttemptBindingResolution.Rejected = AttemptBindingResolution.Rejected(
        diagnosticCode = diagnosticCode,
        capabilitySnapshotRef = capabilitySnapshot.capabilitySnapshotRef,
        capabilitySnapshotHash = capabilitySnapshot.capabilitySnapshotHash
    )
}
