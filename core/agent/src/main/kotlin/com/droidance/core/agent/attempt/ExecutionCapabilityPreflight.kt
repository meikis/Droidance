// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.attempt

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.DurableExecutionAttemptSnapshot
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.PlannedToolCall
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RejectedToolCall
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.RoundExecutionPlan
import com.droidance.core.agent.harness.RoundExecutionPlanner
import java.util.Collections

data class DeviceToolCapabilityRequirement(
    val toolName: String,
    val capability: DeviceCapability
) {
    init {
        require(toolName.isNotBlank()) { "Device tool name cannot be blank" }
    }
}

data class CancellationScopeSnapshot(
    val cancellationScopeId: String,
    val isCanceled: Boolean
) {
    init {
        require(cancellationScopeId.isNotBlank()) { "Cancellation scope id cannot be blank" }
    }
}

data class FrozenAttemptBinding(
    val attempt: ExecutionAttempt,
    val capabilitySnapshot: CapabilitySnapshot,
    val backendInventory: BackendInventory,
    val cancellation: CancellationScopeSnapshot
)

sealed interface AttemptBindingResolution {
    data class Ready(
        val preparedAttempt: PreparedAttemptBinding
    ) : AttemptBindingResolution {
        val binding: FrozenAttemptBinding
            get() = preparedAttempt.binding
    }

    data class Rejected(
        val diagnosticCode: String,
        val capabilitySnapshotRef: String,
        val capabilitySnapshotHash: String
    ) : AttemptBindingResolution

    data object Missing : AttemptBindingResolution
}

sealed interface PreparedAttemptBinding {
    val binding: FrozenAttemptBinding

    data class New(
        override val binding: FrozenAttemptBinding
    ) : PreparedAttemptBinding

    data class Existing(
        override val binding: FrozenAttemptBinding,
        val expectedLifecycleSequence: Long
    ) : PreparedAttemptBinding {
        init {
            require(expectedLifecycleSequence > 0L) {
                "Existing attempt lifecycle sequence must be positive"
            }
        }
    }
}

object FrozenAttemptBindingFactory {
    fun createNew(
        attemptId: String,
        executionId: String,
        cancellationScopeId: String,
        capabilitySnapshot: CapabilitySnapshot,
        backendInventory: BackendInventory,
        isCanceled: Boolean = false
    ): AttemptBindingResolution = when (
        val creation = ExecutionAttemptFactory.create(
            attemptId = attemptId,
            executionId = executionId,
            cancellationScopeId = cancellationScopeId,
            snapshot = capabilitySnapshot
        )
    ) {
        is ExecutionAttemptCreation.Created -> AttemptBindingResolution.Ready(
            preparedAttempt = PreparedAttemptBinding.New(FrozenAttemptBinding(
                attempt = creation.attempt,
                capabilitySnapshot = creation.capabilitySnapshot,
                backendInventory = backendInventory,
                cancellation = CancellationScopeSnapshot(cancellationScopeId, isCanceled)
            ))
        )

        is ExecutionAttemptCreation.Rejected -> AttemptBindingResolution.Rejected(
            diagnosticCode = creation.diagnosticCode,
            capabilitySnapshotRef = creation.capabilitySnapshotRef,
            capabilitySnapshotHash = creation.capabilitySnapshotHash
        )
    }

    fun restoreExisting(
        snapshot: DurableExecutionAttemptSnapshot,
        backendInventory: BackendInventory,
        isCanceled: Boolean = false
    ): AttemptBindingResolution.Ready {
        require(snapshot.state == ExecutionAttemptState.RUNNING) {
            "Only a RUNNING durable attempt can be restored for planning"
        }
        val durableCapability = snapshot.capabilitySnapshot
        val capability = CapabilitySnapshot.create(
            capabilitySnapshotRef = durableCapability.capabilitySnapshotRef,
            runtimeMode = durableCapability.runtimeMode,
            executionSpace = durableCapability.executionSpace,
            displayId = durableCapability.displayId,
            backendKind = durableCapability.backendKind,
            backendSessionId = durableCapability.backendSessionId,
            backendAvailable = durableCapability.backendAvailable,
            capabilities = durableCapability.capabilities.mapTo(LinkedHashSet()) {
                DeviceCapability.valueOf(it)
            },
            scopedShellCapability = durableCapability.scopedShellCapability?.let { scoped ->
                ScopedShellCapability(
                    evidenceKind = ShellScopeEvidenceKind.valueOf(scoped.evidenceKind),
                    backendKind = scoped.backendKind,
                    backendSessionId = scoped.backendSessionId,
                    displayId = scoped.displayId,
                    proofRef = scoped.proofRef,
                    proofHash = scoped.proofHash
                )
            }
        )
        require(capability.capabilitySnapshotHash == durableCapability.capabilitySnapshotHash) {
            "Durable capability snapshot hash does not match its immutable fields"
        }
        val context = snapshot.context
        val creation = ExecutionAttemptFactory.create(
            attemptId = snapshot.scope.attemptId,
            executionId = context.executionId,
            cancellationScopeId = context.cancellationScopeId,
            snapshot = capability
        ) as? ExecutionAttemptCreation.Created
            ?: error("A durable executable attempt cannot restore an unavailable Context")
        require(creation.attempt.context.runtimeMode == context.runtimeMode &&
            creation.attempt.context.executionSpace == context.executionSpace &&
            creation.attempt.context.displayId == context.displayId &&
            creation.attempt.context.backendSessionId == context.backendSessionId &&
            creation.attempt.context.capabilitySnapshotRef == context.capabilitySnapshotRef &&
            creation.attempt.context.capabilitySnapshotHash == context.capabilitySnapshotHash
        ) {
            "Durable ExecutionAttempt changed its immutable Context"
        }
        return AttemptBindingResolution.Ready(
            preparedAttempt = PreparedAttemptBinding.Existing(
                binding = FrozenAttemptBinding(
                attempt = ExecutionAttempt.restore(
                    attemptId = snapshot.scope.attemptId,
                    state = snapshot.state,
                    context = creation.attempt.context
                ),
                capabilitySnapshot = capability,
                backendInventory = backendInventory,
                cancellation = CancellationScopeSnapshot(
                    cancellationScopeId = context.cancellationScopeId,
                    isCanceled = isCanceled
                )
                ),
                expectedLifecycleSequence = snapshot.lifecycleSequence
            )
        )
    }
}

class ExecutionCapabilityPreflight(
    private val binding: FrozenAttemptBinding,
    requirements: Collection<DeviceToolCapabilityRequirement>
) {
    private val requirementsByName: Map<String, DeviceToolCapabilityRequirement>

    init {
        require(requirements.map(DeviceToolCapabilityRequirement::toolName).toSet().size == requirements.size) {
            "Device capability requirements must use unique tool names"
        }
        requirementsByName = requirements.associateBy(DeviceToolCapabilityRequirement::toolName).toMap()
    }

    fun diagnosticFor(call: PlannedToolCall): String? {
        if (call.contract.binding == ToolBinding.TURN_LOCAL) return null

        val context = binding.attempt.context
        val snapshot = binding.capabilitySnapshot
        if (!snapshotMatches(context, snapshot)) {
            return AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH
        }
        when (val route = ExecutionRouteMatrix.resolve(context, binding.backendInventory)) {
            is ExecutionRouteResolution.Rejected -> return route.diagnosticCode
            is ExecutionRouteResolution.Bound -> Unit
        }
        if (binding.cancellation.cancellationScopeId != context.cancellationScopeId) {
            return AttemptDiagnosticCode.CANCELLATION_SCOPE_MISMATCH
        }
        if (binding.cancellation.isCanceled) {
            return AttemptDiagnosticCode.EXECUTION_CANCELED
        }

        val requirement = checkNotNull(requirementsByName[call.providerCall.name]) {
            "Every DEVICE_BOUND tool must declare a capability requirement before planning"
        }
        // RUN_SHELL is device-wide authority on the frozen backend. It does not
        // claim display-scoped command execution; normal capability/session checks apply.
        if (!snapshot.supports(requirement.capability)) {
            return AttemptDiagnosticCode.CAPABILITY_UNAVAILABLE
        }
        if (com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.isScreenObservation(
                call.providerCall.name, call.providerCall.argumentsJson
            ) && !snapshot.supports(DeviceCapability.OBSERVE_SCREEN)) {
            return AttemptDiagnosticCode.CAPABILITY_UNAVAILABLE
        }
        return null
    }

    private fun snapshotMatches(
        context: ExecutionContext,
        snapshot: CapabilitySnapshot
    ): Boolean = context.capabilitySnapshotRef == snapshot.capabilitySnapshotRef &&
        context.capabilitySnapshotHash == snapshot.capabilitySnapshotHash &&
        context.runtimeMode == snapshot.runtimeMode &&
        context.executionSpace == snapshot.executionSpace &&
        context.displayId == snapshot.displayId &&
        context.backendSessionId == snapshot.backendSessionId &&
        snapshot.backendKind == ExecutionRouteMatrix.expectedBackend(
            context.runtimeMode,
            context.executionSpace
        )


}

data class PlannedToolStart(
    val call: PlannedToolCall,
    val attemptId: String?,
    val executionId: String?,
    val executionContext: ExecutionContext?
) {
    init {
        if (call.contract.binding == ToolBinding.DEVICE_BOUND) {
            require(!attemptId.isNullOrBlank()) { "A device ToolStarted requires attemptId" }
            require(!executionId.isNullOrBlank()) { "A device ToolStarted requires executionId" }
            requireNotNull(executionContext) { "A device ToolStarted requires frozen ExecutionContext" }
            require(executionContext.executionId == executionId) {
                "ToolStarted executionId must match its frozen ExecutionContext"
            }
        } else {
            require(attemptId == null && executionId == null && executionContext == null) {
                "A turn-local ToolStarted cannot carry an execution attempt"
            }
        }
    }

    val eventKind: AgentEventKind
        get() = AgentEventKind.TOOL_STARTED
}

data class PlannedToolRejection(
    val call: PlannedToolCall,
    val result: ToolExecutionResult,
    val attemptId: String?,
    val executionId: String?,
    val executionContext: ExecutionContext?
) {
    init {
        require(result.executionStatus == ToolExecutionStatus.NOT_EXECUTED) {
            "A planned tool rejection must materialize a NOT_EXECUTED result"
        }
        require(result.evidenceStatus == ToolEvidenceStatus.NOT_REQUIRED) {
            "A tool rejected before execution cannot carry execution evidence"
        }
        require(result.observationId == null) {
            "A tool rejected before execution cannot carry an observation"
        }
        if (executionContext == null) {
            require(attemptId == null && executionId == null) {
                "A rejection without frozen Context cannot carry attempt identifiers"
            }
        } else {
            require(call.contract.binding == ToolBinding.DEVICE_BOUND) {
                "Only a DEVICE_BOUND rejection can reference an ExecutionAttempt"
            }
            require(!attemptId.isNullOrBlank()) { "A Context-bound rejection requires attemptId" }
            require(executionContext.executionId == executionId) {
                "Rejected executionId must match its frozen ExecutionContext"
            }
        }
    }

    val eventKind: AgentEventKind
        get() = AgentEventKind.TOOL_CALL_REJECTED
}

class PreparedRoundExecutionPlan(
    val roundPlan: RoundExecutionPlan,
    val attemptBinding: PreparedAttemptBinding?,
    val candidateCapabilitySnapshotRef: String?,
    val candidateCapabilitySnapshotHash: String?,
    toolStarts: List<PlannedToolStart>,
    toolRejections: List<PlannedToolRejection>
) {
    val attempt: ExecutionAttempt?
        get() = attemptBinding?.binding?.attempt
    val capabilitySnapshot: CapabilitySnapshot?
        get() = attemptBinding?.binding?.capabilitySnapshot
    val toolStarts: List<PlannedToolStart> =
        Collections.unmodifiableList(ArrayList(toolStarts))
    val toolRejections: List<PlannedToolRejection> =
        Collections.unmodifiableList(ArrayList(toolRejections))

    init {
        require(
            (candidateCapabilitySnapshotRef == null) == (candidateCapabilitySnapshotHash == null)
        ) {
            "Candidate capability snapshot ref and hash must be present together"
        }
        require(attempt == null || candidateCapabilitySnapshotRef == null) {
            "A frozen attempt supersedes candidate-only capability evidence"
        }
        candidateCapabilitySnapshotRef?.let { ref ->
            require(ref.isNotBlank()) { "Candidate capability snapshot ref cannot be blank" }
        }
        candidateCapabilitySnapshotHash?.let { hash ->
            require(hash.matches(Regex("^[0-9a-f]{64}$"))) {
                "Candidate capability snapshot hash must be a lowercase SHA-256 hash"
            }
        }

        require(this.toolStarts.map(PlannedToolStart::call) == roundPlan.selectedCalls) {
            "Tool starts must exactly match the selected round calls in Provider order"
        }
        require(this.toolRejections.map(PlannedToolRejection::call) ==
            roundPlan.rejectedCalls.map(RejectedToolCall::call)
        ) {
            "Tool rejections must exactly match the rejected round calls in Provider order"
        }

        val frozenAttempt = attemptBinding?.binding?.attempt
        val frozenSnapshot = attemptBinding?.binding?.capabilitySnapshot
        if (frozenAttempt == null) {
            require(this.toolStarts.none { it.executionContext != null }) {
                "A plan without an attempt cannot contain a Context-bound start"
            }
            require(this.toolRejections.none { it.executionContext != null }) {
                "A plan without an attempt cannot contain a Context-bound rejection"
            }
        } else {
            checkNotNull(frozenSnapshot)
            val context = frozenAttempt.context
            require(context.capabilitySnapshotRef == frozenSnapshot.capabilitySnapshotRef &&
                context.capabilitySnapshotHash == frozenSnapshot.capabilitySnapshotHash &&
                context.runtimeMode == frozenSnapshot.runtimeMode &&
                context.executionSpace == frozenSnapshot.executionSpace &&
                context.displayId == frozenSnapshot.displayId &&
                context.backendSessionId == frozenSnapshot.backendSessionId
            ) {
                "The prepared capability snapshot must be the frozen attempt snapshot"
            }
            val boundStarts = this.toolStarts.filter { it.executionContext != null }
            val boundRejections = this.toolRejections.filter { it.executionContext != null }
            require(boundStarts.isNotEmpty() || boundRejections.isNotEmpty()) {
                "A prepared attempt must be referenced by a device start or capability rejection"
            }
            boundStarts.forEach { start ->
                require(start.attemptId == frozenAttempt.attemptId &&
                    start.executionId == context.executionId &&
                    start.executionContext === context
                ) {
                    "Every Context-bound plan item must reference the prepared attempt"
                }
            }
            boundRejections.forEach { rejection ->
                require(rejection.attemptId == frozenAttempt.attemptId &&
                    rejection.executionId == context.executionId &&
                    rejection.executionContext === context
                ) {
                    "Every Context-bound plan item must reference the prepared attempt"
                }
            }
        }

        this.toolRejections.forEach { rejection ->
            val isContextBoundCapabilityRejection =
                rejection.result.diagnosticCode in contextBoundCapabilityDiagnostics
            require((rejection.executionContext != null) == isContextBoundCapabilityRejection) {
                "Only post-freeze capability rejections may reference the prepared attempt"
            }
        }
    }
}

class ExecutionRoundPlanner(
    registrations: Collection<RegisteredToolContract>,
    capabilityRequirements: Collection<DeviceToolCapabilityRequirement>,
    private val presentedToolNames: Set<String>? = null,
    private val callIdFactory: (Int, ProviderToolCall) -> String,
    private val preflight: (PlannedToolCall) -> ToolExecutionResult? = { null },
    private val mutationsRestricted: Boolean = false
) {
    private val registrations = registrations.toList()
    private val capabilityRequirements = capabilityRequirements.toList()

    init {
        require(this.registrations.map(RegisteredToolContract::name).toSet().size == this.registrations.size) {
            "Tool registrations must use unique names"
        }
        require(
            this.capabilityRequirements.map(DeviceToolCapabilityRequirement::toolName).toSet().size ==
                this.capabilityRequirements.size
        ) {
            "Device capability requirements must use unique tool names"
        }
        val deviceToolNames = this.capabilityRequirements
            .map(DeviceToolCapabilityRequirement::toolName)
            .toSet()
        require(
            this.registrations
                .filter { it.name in deviceToolNames }
                .all { it.binding == ToolBinding.DEVICE_BOUND }
        ) {
            "Device tools must use DEVICE_BOUND registration"
        }
        require(
            this.registrations
                .filter { it.binding == ToolBinding.DEVICE_BOUND }
                .all { it.name in deviceToolNames }
        ) {
            "Every DEVICE_BOUND tool must declare a capability requirement before planning"
        }
    }

    fun plan(
        round: NormalizedModelRound,
        startCommitted: Boolean,
        repairPurpose: RepairPurpose,
        attemptBindingProvider: () -> AttemptBindingResolution = { AttemptBindingResolution.Missing }
    ): PreparedRoundExecutionPlan {
        var bindingResolved = false
        var bindingResolution: AttemptBindingResolution = AttemptBindingResolution.Missing
        val localPreflightResults = mutableMapOf<String, ToolExecutionResult>()

        fun resolveBinding(): AttemptBindingResolution {
            if (!bindingResolved) {
                bindingResolution = attemptBindingProvider()
                bindingResolved = true
            }
            return bindingResolution
        }

        val planner = RoundExecutionPlanner(
            registrations = registrations,
            presentedToolNames = presentedToolNames,
            callIdFactory = callIdFactory,
            mutationsRestricted = mutationsRestricted,
            preflight = { call ->
                val localResult = preflight(call)
                if (localResult != null) {
                    require(localResult.executionStatus == ToolExecutionStatus.NOT_EXECUTED) {
                        "Local preflight must reject as NOT_EXECUTED"
                    }
                    require(localResult.evidenceStatus == ToolEvidenceStatus.NOT_REQUIRED) {
                        "Local preflight cannot claim execution evidence"
                    }
                    require(localResult.outputText.isNotBlank()) {
                        "Local preflight must preserve actionable model feedback"
                    }
                    val diagnostic = requireNotNull(localResult.diagnosticCode) {
                        "Local preflight requires a diagnostic code"
                    }
                    localPreflightResults[call.callId] = localResult.copy(
                        metrics = localResult.metrics.toMap()
                    )
                    diagnostic
                } else if (call.contract.binding == ToolBinding.TURN_LOCAL) {
                    null
                } else {
                    when (val resolution = resolveBinding()) {
                        is AttemptBindingResolution.Ready ->
                            ExecutionCapabilityPreflight(
                                binding = resolution.binding,
                                requirements = capabilityRequirements
                            ).diagnosticFor(call)
                        is AttemptBindingResolution.Rejected -> resolution.diagnosticCode
                        AttemptBindingResolution.Missing -> AttemptDiagnosticCode.MISSING_EXECUTION_CONTEXT
                    }
                }
            }
        )
        val plan = planner.plan(round, startCommitted, repairPurpose)
        val ready = bindingResolution as? AttemptBindingResolution.Ready
        val binding = ready?.binding
        val acceptedDeviceCallExists = plan.selectedCalls.any {
            it.contract.binding == ToolBinding.DEVICE_BOUND
        }
        val contextBoundCapabilityRejectionExists = binding != null && plan.rejectedCalls.any {
            it.call.contract.binding == ToolBinding.DEVICE_BOUND &&
                it.diagnosticCode in contextBoundCapabilityDiagnostics
        }
        val preparedBinding = ready?.preparedAttempt?.takeIf {
            acceptedDeviceCallExists || contextBoundCapabilityRejectionExists
        }
        val starts = plan.selectedCalls.map { call ->
            if (call.contract.binding == ToolBinding.DEVICE_BOUND) {
                checkNotNull(binding) { "A DEVICE_BOUND ToolStarted requires a frozen attempt" }
                PlannedToolStart(
                    call = call,
                    attemptId = binding.attempt.attemptId,
                    executionId = binding.attempt.context.executionId,
                    executionContext = binding.attempt.context
                )
            } else {
                PlannedToolStart(
                    call = call,
                    attemptId = null,
                    executionId = null,
                    executionContext = null
                )
            }
        }
        val rejections = plan.rejectedCalls.map { rejected ->
            toPlannedRejection(
                rejected = rejected,
                binding = preparedBinding?.binding?.takeIf {
                    rejected.call.contract.binding == ToolBinding.DEVICE_BOUND &&
                        rejected.diagnosticCode in contextBoundCapabilityDiagnostics
                },
                localPreflightResult = localPreflightResults[rejected.call.callId]
            )
        }
        val candidateEvidence = if (preparedBinding == null && bindingResolved) {
            when (val resolution = bindingResolution) {
                is AttemptBindingResolution.Ready ->
                    resolution.binding.capabilitySnapshot.capabilitySnapshotRef to
                        resolution.binding.capabilitySnapshot.capabilitySnapshotHash
                is AttemptBindingResolution.Rejected ->
                    resolution.capabilitySnapshotRef to resolution.capabilitySnapshotHash
                AttemptBindingResolution.Missing -> null
            }
        } else {
            null
        }
        return PreparedRoundExecutionPlan(
            roundPlan = plan,
            attemptBinding = preparedBinding,
            candidateCapabilitySnapshotRef = candidateEvidence?.first,
            candidateCapabilitySnapshotHash = candidateEvidence?.second,
            toolStarts = starts,
            toolRejections = rejections
        )
    }

    private fun toPlannedRejection(
        rejected: RejectedToolCall,
        binding: FrozenAttemptBinding?,
        localPreflightResult: ToolExecutionResult?
    ): PlannedToolRejection {
        require(localPreflightResult == null ||
            localPreflightResult.diagnosticCode == rejected.diagnosticCode
        ) {
            "Local preflight result must match the immutable RoundExecutionPlan diagnostic"
        }
        return PlannedToolRejection(
            call = rejected.call,
            result = localPreflightResult ?: if (rejected.diagnosticCode ==
                com.droidance.core.agent.harness.ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH
            ) com.droidance.core.agent.harness.ToolArgumentSchemaFeedback.rejectionResult(
                rejected.call.contract, rejected.call.providerCall.argumentsJson
            ) else if (rejected.diagnosticCode == com.droidance.core.agent.harness.RoundDiagnosticCode.TASK_EXECUTION_BUDGET_EXHAUSTED) {
                com.droidance.core.agent.tools.ToolProtocolResult.notExecuted(
                    code = rejected.diagnosticCode,
                    retryable = false,
                    details = kotlinx.serialization.json.buildJsonObject {
                        put("tool", kotlinx.serialization.json.JsonPrimitive(rejected.call.providerCall.name))
                        put("reason", kotlinx.serialization.json.JsonPrimitive("The turn's device mutation budget is exhausted. Use retained evidence or read-only sources for an honest partial result. Plan edits and reviews do not renew this budget."))
                    }
                )
            } else if (rejected.diagnosticCode == AttemptDiagnosticCode.MISSING_EXECUTION_CONTEXT) {
                // The device strategy is frozen when the task starts, so no later call in this task
                // can obtain a binding. Say so instead of inviting retries through other device tools.
                com.droidance.core.agent.tools.ToolProtocolResult.notExecuted(
                    code = rejected.diagnosticCode,
                    retryable = false,
                    details = kotlinx.serialization.json.buildJsonObject {
                        put("tool", kotlinx.serialization.json.JsonPrimitive(rejected.call.providerCall.name))
                        put("operation_invoked", kotlinx.serialization.json.JsonPrimitive(false))
                        put("reason", kotlinx.serialization.json.JsonPrimitive("No device execution runtime was available when this task started, so device tools cannot run in this task. Retrying, changing arguments or switching to another device tool will not obtain one. Report the blocker with any facts already returned."))
                    }
                )
            } else if (rejected.diagnosticCode == com.droidance.core.agent.harness.RoundDiagnosticCode.TOOL_NOT_PRESENTED) {
                com.droidance.core.agent.tools.ToolProtocolResult.notExecuted(
                    code = rejected.diagnosticCode, retryable = true,
                    details = kotlinx.serialization.json.buildJsonObject {
                        put("reason", kotlinx.serialization.json.JsonPrimitive("This tool is not available in the current request. Choose from the presented tools; a plan or review does not unlock unavailable tools."))
                    }
                )
            } else com.droidance.core.agent.tools.ToolProtocolResult.notExecuted(
                code = rejected.diagnosticCode,
                retryable = true,
                details = kotlinx.serialization.json.buildJsonObject {
                    put("stage", kotlinx.serialization.json.JsonPrimitive("planning"))
                    put("dispatch_stage", kotlinx.serialization.json.JsonPrimitive("before_dispatch"))
                    put("operation_invoked", kotlinx.serialization.json.JsonPrimitive(false))
                    put("prerequisite_provider_call_ids", kotlinx.serialization.json.JsonArray(
                        rejected.prerequisiteProviderCallIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    if (rejected.diagnosticCode == com.droidance.core.agent.harness.RoundDiagnosticCode.QUERY_RESULT_REQUIRED) {
                        put("reason", kotlinx.serialization.json.JsonPrimitive(
                            "This operation was not dispatched because a preceding result in the same model response had not yet been consumed by the Agent. This receipt records no outcome from the target operation."))
                    }
                }
            ),
            attemptId = binding?.attempt?.attemptId,
            executionId = binding?.attempt?.context?.executionId,
            executionContext = binding?.attempt?.context
        )
    }

}

private val contextBoundCapabilityDiagnostics = setOf(
    AttemptDiagnosticCode.CAPABILITY_UNAVAILABLE,
    AttemptDiagnosticCode.UNSUPPORTED_IN_EXECUTION_SPACE
)
