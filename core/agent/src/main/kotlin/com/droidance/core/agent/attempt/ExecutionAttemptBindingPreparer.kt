// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.attempt

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.event.CapabilitySnapshotEventPayload
import com.droidance.core.agent.event.DurableExecutionAttemptSnapshot
import com.droidance.core.agent.event.ExecutionContextSnapshotPayload
import javax.inject.Inject

/**
 * A route selected before a model round is requested. It carries no mutable runtime state;
 * [ExecutionAttemptBackendProbe] is the sole source of the state that is frozen into an
 * execution attempt.
 */
data class ExecutionAttemptRoute(
    val runtimeMode: RuntimeMode,
    val executionSpace: ExecutionSpace
) {
    init {
        require(runtimeMode == RuntimeMode.STANDARD && executionSpace == ExecutionSpace.VIRTUAL_SPACE) {
            "Only Standard virtual-space routes can be prepared"
        }
    }

    val expectedBackend: ExecutionBackendKind
        get() = requireNotNull(ExecutionRouteMatrix.expectedBackend(runtimeMode, executionSpace)) {
            "Only Standard virtual-space routes are supported"
        }
}

/** Identifiers allocated by the event-sourced turn owner before it plans a model round. */
data class NewExecutionAttemptIdentity(
    val attemptId: String,
    val executionId: String,
    val cancellationScopeId: String,
    val capabilitySnapshotRef: String
) {
    init {
        require(attemptId.isNotBlank()) { "Attempt id cannot be blank" }
        require(executionId.isNotBlank()) { "Execution id cannot be blank" }
        require(cancellationScopeId.isNotBlank()) { "Cancellation scope id cannot be blank" }
        require(capabilitySnapshotRef.isNotBlank()) { "Capability snapshot ref cannot be blank" }
    }
}

/**
 * One observation of the backend that will service an attempt. The probe may initialize a
 * requested backend only from [ExecutionAttemptBackendProbe.prepare]; [inspect] must be a
 * passive post-commit check.
 */
data class ExecutionBackendObservation(
    val backendKind: ExecutionBackendKind,
    val displayId: Int,
    val backendSessionId: String,
    val backendAvailable: Boolean,
    val capabilities: Set<DeviceCapability>,
    val scopedShellCapability: ScopedShellCapability? = null,
    val unavailableDiagnosticCode: String? = null
) {
    init {
        require(displayId >= 0) { "Observed backend displayId cannot be negative" }
        require(!backendAvailable || backendSessionId.isNotBlank()) {
            "An available observed backend requires a session id"
        }
        require(!backendAvailable || unavailableDiagnosticCode == null) {
            "An available observed backend cannot carry an unavailable diagnostic"
        }
    }

    fun capabilitySnapshot(
        route: ExecutionAttemptRoute,
        capabilitySnapshotRef: String
    ): CapabilitySnapshot = CapabilitySnapshot.create(
        capabilitySnapshotRef = capabilitySnapshotRef,
        runtimeMode = route.runtimeMode,
        executionSpace = route.executionSpace,
        displayId = displayId,
        backendKind = backendKind,
        backendSessionId = backendSessionId,
        backendAvailable = backendAvailable,
        capabilities = capabilities,
        scopedShellCapability = scopedShellCapability
    )

    fun backendInventory(): BackendInventory = BackendInventory.of(
        BackendEndpointSnapshot(
            backendKind = backendKind,
            backendSessionId = backendSessionId,
            displayId = displayId,
            available = backendAvailable
        )
    )
}

interface ExecutionAttemptBackendProbe {
    /** May establish the exact requested backend before the model round is committed. */
    suspend fun prepare(route: ExecutionAttemptRoute): ExecutionBackendObservation

    /** Must not establish or replace a backend after the model round has been committed. */
    suspend fun inspect(route: ExecutionAttemptRoute): ExecutionBackendObservation
}

sealed interface ExecutionAttemptBindingVerification {
    data object Ready : ExecutionAttemptBindingVerification

    data class Rejected(
        val diagnosticCode: String
    ) : ExecutionAttemptBindingVerification
}

/**
 * Creates a frozen binding before model-plan persistence and verifies that exact binding before
 * a committed device call. It never resolves a second route and therefore cannot fall back
 * to a different shell session or display. Only Standard virtual-space routes can be prepared.
 */
class ExecutionAttemptBindingPreparer @Inject constructor(
    private val backendProbe: ExecutionAttemptBackendProbe
) {
    suspend fun prepareNew(
        identity: NewExecutionAttemptIdentity,
        route: ExecutionAttemptRoute,
        isCanceled: Boolean = false,
        expectedBackend: ExecutionBackendObservation? = null
    ): AttemptBindingResolution {
        val observation = if (expectedBackend == null) {
            backendProbe.prepare(route)
        } else {
            backendProbe.inspect(route)
        }
        val snapshot = observation.capabilitySnapshot(route, identity.capabilitySnapshotRef)
        observation.unavailableDiagnosticCode?.takeIf { !observation.backendAvailable }?.let { diagnostic ->
            return AttemptBindingResolution.Rejected(
                diagnosticCode = diagnostic,
                capabilitySnapshotRef = snapshot.capabilitySnapshotRef,
                capabilitySnapshotHash = snapshot.capabilitySnapshotHash
            )
        }
        if (expectedBackend != null && observation != expectedBackend) {
            return AttemptBindingResolution.Rejected(
                diagnosticCode = AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH,
                capabilitySnapshotRef = snapshot.capabilitySnapshotRef,
                capabilitySnapshotHash = snapshot.capabilitySnapshotHash
            )
        }
        return FrozenAttemptBindingFactory.createNew(
            attemptId = identity.attemptId,
            executionId = identity.executionId,
            cancellationScopeId = identity.cancellationScopeId,
            capabilitySnapshot = snapshot,
            backendInventory = observation.backendInventory(),
            isCanceled = isCanceled
        )
    }

    /**
     * Reuses the immutable attempt already owned by this Turn. A continuation round may inspect
     * the frozen route, but it must not prepare another backend or allocate another execution.
     */
    suspend fun restoreExisting(
        snapshot: DurableExecutionAttemptSnapshot,
        route: ExecutionAttemptRoute,
        isCanceled: Boolean = false
    ): AttemptBindingResolution {
        val context = snapshot.context
        val durableCapability = snapshot.capabilitySnapshot
        if (restoreCapabilitySnapshot(durableCapability) == null) {
            return AttemptBindingResolution.Rejected(
                diagnosticCode = AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH,
                capabilitySnapshotRef = durableCapability.capabilitySnapshotRef,
                capabilitySnapshotHash = durableCapability.capabilitySnapshotHash
            )
        }
        if (context.runtimeMode != route.runtimeMode ||
            context.executionSpace != route.executionSpace
        ) {
            return AttemptBindingResolution.Rejected(
                diagnosticCode = AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH,
                capabilitySnapshotRef = durableCapability.capabilitySnapshotRef,
                capabilitySnapshotHash = durableCapability.capabilitySnapshotHash
            )
        }

        val observation = backendProbe.inspect(route)
        val restored = FrozenAttemptBindingFactory.restoreExisting(
            snapshot = snapshot,
            backendInventory = observation.backendInventory(),
            isCanceled = isCanceled
        )
        return when (val verification = verifyCommitted(restored.binding, observation)) {
            ExecutionAttemptBindingVerification.Ready -> restored
            is ExecutionAttemptBindingVerification.Rejected -> AttemptBindingResolution.Rejected(
                diagnosticCode = verification.diagnosticCode,
                capabilitySnapshotRef = durableCapability.capabilitySnapshotRef,
                capabilitySnapshotHash = durableCapability.capabilitySnapshotHash
            )
        }
    }

    suspend fun verifyCommitted(
        binding: FrozenAttemptBinding
    ): ExecutionAttemptBindingVerification {
        if (ExecutionRouteMatrix.expectedBackend(
                binding.attempt.context.runtimeMode, binding.attempt.context.executionSpace
            ) == null) {
            return ExecutionAttemptBindingVerification.Rejected(AttemptDiagnosticCode.UNSUPPORTED_IN_EXECUTION_SPACE)
        }
        val route = ExecutionAttemptRoute(
            binding.attempt.context.runtimeMode,
            binding.attempt.context.executionSpace
        )
        if (!frozenSnapshotMatchesContext(
                route = route,
                snapshot = binding.capabilitySnapshot,
                displayId = binding.attempt.context.displayId,
                backendSessionId = binding.attempt.context.backendSessionId,
                capabilitySnapshotRef = binding.attempt.context.capabilitySnapshotRef,
                capabilitySnapshotHash = binding.attempt.context.capabilitySnapshotHash
            )
        ) {
            return ExecutionAttemptBindingVerification.Rejected(
                AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH
            )
        }
        return verifyCommitted(
            binding = binding,
            observation = backendProbe.inspect(route)
        )
    }

    private fun verifyCommitted(
        binding: FrozenAttemptBinding,
        observation: ExecutionBackendObservation
    ): ExecutionAttemptBindingVerification = verifyCommitted(
        route = ExecutionAttemptRoute(
            binding.attempt.context.runtimeMode,
            binding.attempt.context.executionSpace
        ),
        frozenSnapshot = binding.capabilitySnapshot,
        displayId = binding.attempt.context.displayId,
        backendSessionId = binding.attempt.context.backendSessionId,
        capabilitySnapshotRef = binding.attempt.context.capabilitySnapshotRef,
        capabilitySnapshotHash = binding.attempt.context.capabilitySnapshotHash,
        observation = observation
    )

    /**
     * Verifies the immutable attempt payload materialized from the event store. Invalid event
     * payloads are treated as an unavailable frozen binding instead of being reconstructed from
     * current settings.
     */
    suspend fun verifyCommitted(
        context: ExecutionContextSnapshotPayload,
        capabilitySnapshot: CapabilitySnapshotEventPayload
    ): ExecutionAttemptBindingVerification {
        if (ExecutionRouteMatrix.expectedBackend(context.runtimeMode, context.executionSpace) == null) {
            return ExecutionAttemptBindingVerification.Rejected(AttemptDiagnosticCode.UNSUPPORTED_IN_EXECUTION_SPACE)
        }
        val snapshot = restoreCapabilitySnapshot(capabilitySnapshot)
            ?: return ExecutionAttemptBindingVerification.Rejected(
                AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH
            )
        val route = ExecutionAttemptRoute(context.runtimeMode, context.executionSpace)
        if (!frozenSnapshotMatchesContext(
                route = route,
                snapshot = snapshot,
                displayId = context.displayId,
                backendSessionId = context.backendSessionId,
                capabilitySnapshotRef = context.capabilitySnapshotRef,
                capabilitySnapshotHash = context.capabilitySnapshotHash
            )
        ) {
            return ExecutionAttemptBindingVerification.Rejected(
                AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH
            )
        }
        return verifyCommitted(
            route = route,
            frozenSnapshot = snapshot,
            displayId = context.displayId,
            backendSessionId = context.backendSessionId,
            capabilitySnapshotRef = context.capabilitySnapshotRef,
            capabilitySnapshotHash = context.capabilitySnapshotHash,
            observation = backendProbe.inspect(route)
        )
    }

    private fun verifyCommitted(
        route: ExecutionAttemptRoute,
        frozenSnapshot: CapabilitySnapshot,
        displayId: Int,
        backendSessionId: String,
        capabilitySnapshotRef: String,
        capabilitySnapshotHash: String,
        observation: ExecutionBackendObservation
    ): ExecutionAttemptBindingVerification {
        if (!frozenSnapshotMatchesContext(
                route = route,
                snapshot = frozenSnapshot,
                displayId = displayId,
                backendSessionId = backendSessionId,
                capabilitySnapshotRef = capabilitySnapshotRef,
                capabilitySnapshotHash = capabilitySnapshotHash
            )
        ) {
            return ExecutionAttemptBindingVerification.Rejected(
                AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH
            )
        }

        if (observation.backendKind != route.expectedBackend) {
            return ExecutionAttemptBindingVerification.Rejected(
                AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH
            )
        }
        if (!observation.backendAvailable) {
            return ExecutionAttemptBindingVerification.Rejected(
                observation.unavailableDiagnosticCode ?: AttemptDiagnosticCode.BACKEND_UNAVAILABLE
            )
        }
        val currentSnapshot = observation.capabilitySnapshot(
            route = route,
            capabilitySnapshotRef = frozenSnapshot.capabilitySnapshotRef
        )
        ExecutionRouteMatrix.validateCandidate(currentSnapshot)?.let { diagnostic ->
            return ExecutionAttemptBindingVerification.Rejected(diagnostic)
        }
        when (val resolution = ExecutionRouteMatrix.resolve(
            context = frozenExecutionContext(
                route = route,
                displayId = displayId,
                backendSessionId = backendSessionId,
                capabilitySnapshotRef = capabilitySnapshotRef,
                capabilitySnapshotHash = capabilitySnapshotHash
            ),
            inventory = observation.backendInventory()
        )) {
            is ExecutionRouteResolution.Rejected -> {
                return ExecutionAttemptBindingVerification.Rejected(resolution.diagnosticCode)
            }

            is ExecutionRouteResolution.Bound -> Unit
        }
        if (currentSnapshot.capabilitySnapshotHash != frozenSnapshot.capabilitySnapshotHash) {
            return ExecutionAttemptBindingVerification.Rejected(
                AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH
            )
        }
        return ExecutionAttemptBindingVerification.Ready
    }

    private fun frozenSnapshotMatchesContext(
        route: ExecutionAttemptRoute,
        snapshot: CapabilitySnapshot,
        displayId: Int,
        backendSessionId: String,
        capabilitySnapshotRef: String,
        capabilitySnapshotHash: String
    ): Boolean = capabilitySnapshotRef == snapshot.capabilitySnapshotRef &&
        capabilitySnapshotHash == snapshot.capabilitySnapshotHash &&
        route.runtimeMode == snapshot.runtimeMode &&
        route.executionSpace == snapshot.executionSpace &&
        displayId == snapshot.displayId &&
        backendSessionId == snapshot.backendSessionId &&
        snapshot.backendKind == ExecutionRouteMatrix.expectedBackend(
            route.runtimeMode,
            route.executionSpace
        )

    private fun frozenExecutionContext(
        route: ExecutionAttemptRoute,
        displayId: Int,
        backendSessionId: String,
        capabilitySnapshotRef: String,
        capabilitySnapshotHash: String
    ): ExecutionContext = ExecutionContext.freeze(
        executionId = "verification-only",
        cancellationScopeId = "verification-only",
        snapshot = CapabilitySnapshot.create(
            capabilitySnapshotRef = capabilitySnapshotRef,
            runtimeMode = route.runtimeMode,
            executionSpace = route.executionSpace,
            displayId = displayId,
            backendKind = route.expectedBackend,
            backendSessionId = backendSessionId,
            backendAvailable = true,
            capabilities = emptySet()
        )
    )

    private fun restoreCapabilitySnapshot(
        payload: CapabilitySnapshotEventPayload
    ): CapabilitySnapshot? = runCatching {
        require(payload.capabilities.toSet().size == payload.capabilities.size) {
            "Durable capability snapshot contains duplicate capabilities"
        }
        val restored = CapabilitySnapshot.create(
            capabilitySnapshotRef = payload.capabilitySnapshotRef,
            runtimeMode = payload.runtimeMode,
            executionSpace = payload.executionSpace,
            displayId = payload.displayId,
            backendKind = payload.backendKind,
            backendSessionId = payload.backendSessionId,
            backendAvailable = payload.backendAvailable,
            capabilities = payload.capabilities.mapTo(LinkedHashSet()) { name ->
                DeviceCapability.valueOf(name)
            },
            scopedShellCapability = payload.scopedShellCapability?.let { scoped ->
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
        require(restored.capabilitySnapshotHash == payload.capabilitySnapshotHash) {
            "Durable capability snapshot hash does not match its immutable fields"
        }
        restored
    }.getOrNull()
}
