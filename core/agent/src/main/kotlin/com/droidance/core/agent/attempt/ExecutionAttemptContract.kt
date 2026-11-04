// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.attempt

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionAttemptState {
    CREATED,
    RUNNING,
    PAUSED_USER,
    PAUSED_RECOVERY_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELED,
    INTERRUPTED
}

object ExecutionAttemptTransitions {
    val terminalStates: Set<ExecutionAttemptState> = setOf(
        ExecutionAttemptState.COMPLETED,
        ExecutionAttemptState.FAILED,
        ExecutionAttemptState.CANCELED,
        ExecutionAttemptState.INTERRUPTED
    )

    fun allows(previous: ExecutionAttemptState, next: ExecutionAttemptState): Boolean = next in when (previous) {
        ExecutionAttemptState.CREATED -> setOf(
            ExecutionAttemptState.RUNNING,
            ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED,
            ExecutionAttemptState.FAILED,
            ExecutionAttemptState.CANCELED,
            ExecutionAttemptState.INTERRUPTED
        )
        ExecutionAttemptState.RUNNING -> setOf(
            ExecutionAttemptState.PAUSED_USER,
            ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED,
            ExecutionAttemptState.COMPLETED,
            ExecutionAttemptState.FAILED,
            ExecutionAttemptState.CANCELED,
            ExecutionAttemptState.INTERRUPTED
        )
        ExecutionAttemptState.PAUSED_USER -> setOf(
            ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED,
            ExecutionAttemptState.RUNNING,
            ExecutionAttemptState.FAILED,
            ExecutionAttemptState.CANCELED,
            ExecutionAttemptState.INTERRUPTED
        )
        ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED -> setOf(
            ExecutionAttemptState.RUNNING,
            ExecutionAttemptState.FAILED,
            ExecutionAttemptState.CANCELED,
            ExecutionAttemptState.INTERRUPTED
        )
        ExecutionAttemptState.COMPLETED,
        ExecutionAttemptState.FAILED,
        ExecutionAttemptState.CANCELED,
        ExecutionAttemptState.INTERRUPTED -> emptySet()
    }
}

@Serializable
enum class ExecutionBackendKind {
    // Retired identifiers are retained only for decoding stored execution evidence.
    AIR_BACKEND,
    AIR_SIDECAR,
    ROOT_BACKEND,
    ROOT_SIDECAR
}

@Serializable
enum class DeviceCapability {
    READ_DEVICE,
    OBSERVE_SCREEN,
    CLICK,
    SWIPE,
    TYPE_TEXT,
    PRESS_KEY,
    SCREEN_ACT_BATCH,
    OPEN_APP,
    RUN_SHELL,
    HUMANIZED_GESTURE
}

@Serializable
enum class ShellScopeEvidenceKind {
    OS_DISPLAY_SCOPED,
    COMMAND_LAYER_DISPLAY_SCOPED
}

data class ScopedShellCapability(
    val evidenceKind: ShellScopeEvidenceKind,
    val backendKind: ExecutionBackendKind,
    val backendSessionId: String,
    val displayId: Int,
    val proofRef: String,
    val proofHash: String
) {
    init {
        require(backendSessionId.isNotBlank()) { "Scoped shell backendSessionId cannot be blank" }
        require(displayId > 0) { "Scoped shell requires a virtual displayId" }
        require(proofRef.isNotBlank()) { "Scoped shell proofRef cannot be blank" }
        require(proofHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "Scoped shell proofHash must be a lowercase SHA-256 hash"
        }
    }
}

class CapabilitySnapshot private constructor(
    val capabilitySnapshotRef: String,
    val capabilitySnapshotHash: String,
    val runtimeMode: RuntimeMode,
    val executionSpace: ExecutionSpace,
    val displayId: Int,
    val backendKind: ExecutionBackendKind,
    val backendSessionId: String,
    val backendAvailable: Boolean,
    capabilities: Set<DeviceCapability>,
    val scopedShellCapability: ScopedShellCapability?
) {
    private val frozenCapabilities: Set<DeviceCapability> =
        Collections.unmodifiableSet(LinkedHashSet(capabilities))

    val capabilities: Set<DeviceCapability>
        get() = frozenCapabilities

    fun supports(capability: DeviceCapability): Boolean = capability in frozenCapabilities

    companion object {
        fun create(
            capabilitySnapshotRef: String,
            runtimeMode: RuntimeMode,
            executionSpace: ExecutionSpace,
            displayId: Int,
            backendKind: ExecutionBackendKind,
            backendSessionId: String,
            backendAvailable: Boolean,
            capabilities: Set<DeviceCapability>,
            scopedShellCapability: ScopedShellCapability? = null
        ): CapabilitySnapshot {
            require(capabilitySnapshotRef.isNotBlank()) { "Capability snapshot ref cannot be blank" }
            require(!backendAvailable || backendSessionId.isNotBlank()) {
                "An available capability backend requires a session id"
            }
            require(displayId >= 0) { "Capability displayId cannot be negative" }
            val copiedCapabilities = LinkedHashSet(capabilities)
            if (scopedShellCapability != null) {
                require(executionSpace == ExecutionSpace.VIRTUAL_SPACE) {
                    "Scoped shell capability is only valid for a virtual execution space"
                }
                require(scopedShellCapability.backendKind == backendKind) {
                    "Scoped shell backend must match the capability snapshot"
                }
                require(
                    scopedShellCapability.backendKind ==
                        ExecutionRouteMatrix.expectedBackend(runtimeMode, executionSpace)
                ) {
                    "Scoped shell backend must match the runtime mode and execution space"
                }
                require(scopedShellCapability.backendSessionId == backendSessionId) {
                    "Scoped shell session must match the capability snapshot"
                }
                require(scopedShellCapability.displayId == displayId) {
                    "Scoped shell display must match the capability snapshot"
                }
            }
            val hash = snapshotHash(
                runtimeMode = runtimeMode,
                executionSpace = executionSpace,
                displayId = displayId,
                backendKind = backendKind,
                backendSessionId = backendSessionId,
                backendAvailable = backendAvailable,
                capabilities = copiedCapabilities,
                scopedShellCapability = scopedShellCapability
            )
            return CapabilitySnapshot(
                capabilitySnapshotRef = capabilitySnapshotRef,
                capabilitySnapshotHash = hash,
                runtimeMode = runtimeMode,
                executionSpace = executionSpace,
                displayId = displayId,
                backendKind = backendKind,
                backendSessionId = backendSessionId,
                backendAvailable = backendAvailable,
                capabilities = copiedCapabilities,
                scopedShellCapability = scopedShellCapability
            )
        }

        private fun snapshotHash(
            runtimeMode: RuntimeMode,
            executionSpace: ExecutionSpace,
            displayId: Int,
            backendKind: ExecutionBackendKind,
            backendSessionId: String,
            backendAvailable: Boolean,
            capabilities: Set<DeviceCapability>,
            scopedShellCapability: ScopedShellCapability?
        ): String {
            val scopedFields = scopedShellCapability?.let { capability ->
                listOf(
                    capability.evidenceKind.name,
                    capability.backendKind.name,
                    capability.backendSessionId,
                    capability.displayId.toString(),
                    capability.proofRef,
                    capability.proofHash
                )
            }.orEmpty()
            val fields = listOf(
                runtimeMode.eventIdentifier,
                executionSpace.name,
                displayId.toString(),
                backendKind.name,
                backendSessionId,
                backendAvailable.toString(),
                capabilities.map(DeviceCapability::name).sorted().joinToString(",")
            ) + scopedFields
            val canonical = fields.joinToString(separator = "") { field -> "${field.length}:$field" }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

class ExecutionContext private constructor(
    val executionId: String,
    val runtimeMode: RuntimeMode,
    val executionSpace: ExecutionSpace,
    val displayId: Int,
    val backendSessionId: String,
    val capabilitySnapshotRef: String,
    val capabilitySnapshotHash: String,
    val cancellationScopeId: String
) {
    companion object {
        fun freeze(
            executionId: String,
            cancellationScopeId: String,
            snapshot: CapabilitySnapshot
        ): ExecutionContext {
            require(executionId.isNotBlank()) { "Execution id cannot be blank" }
            require(cancellationScopeId.isNotBlank()) { "Cancellation scope id cannot be blank" }
            require(snapshot.backendAvailable) { "Cannot freeze an unavailable backend" }
            require(snapshot.backendSessionId.isNotBlank()) { "Cannot freeze a context without a backend session" }
            return ExecutionContext(
                executionId = executionId,
                runtimeMode = snapshot.runtimeMode,
                executionSpace = snapshot.executionSpace,
                displayId = snapshot.displayId,
                backendSessionId = snapshot.backendSessionId,
                capabilitySnapshotRef = snapshot.capabilitySnapshotRef,
                capabilitySnapshotHash = snapshot.capabilitySnapshotHash,
                cancellationScopeId = cancellationScopeId
            )
        }
    }
}

class ExecutionAttempt private constructor(
    val attemptId: String,
    val state: ExecutionAttemptState,
    val context: ExecutionContext
) {
    companion object {
        internal fun created(attemptId: String, context: ExecutionContext): ExecutionAttempt {
            require(attemptId.isNotBlank()) { "Attempt id cannot be blank" }
            return ExecutionAttempt(
                attemptId = attemptId,
                state = ExecutionAttemptState.CREATED,
                context = context
            )
        }

        fun restore(
            attemptId: String,
            state: ExecutionAttemptState,
            context: ExecutionContext
        ): ExecutionAttempt {
            require(attemptId.isNotBlank()) { "Attempt id cannot be blank" }
            return ExecutionAttempt(
                attemptId = attemptId,
                state = state,
                context = context
            )
        }
    }
}

object AttemptDiagnosticCode {
    const val MISSING_EXECUTION_CONTEXT = "MISSING_EXECUTION_CONTEXT"
    const val BACKEND_UNAVAILABLE = "BACKEND_UNAVAILABLE"
    const val AIR_CHANNEL_RECOVERY_REQUIRED = "AIR_CHANNEL_RECOVERY_REQUIRED"
    const val AIR_PAIRING_REQUIRED = "AIR_PAIRING_REQUIRED"
    const val INPUT_PERMISSION_DENIED = "INPUT_PERMISSION_DENIED"
    const val VIRTUAL_DISPLAY_UNSUPPORTED = "VIRTUAL_DISPLAY_UNSUPPORTED"
    const val BACKEND_BINDING_MISMATCH = "BACKEND_BINDING_MISMATCH"
    const val BACKEND_SESSION_MISMATCH = "BACKEND_SESSION_MISMATCH"
    const val DISPLAY_MISMATCH = "DISPLAY_MISMATCH"
    const val CAPABILITY_SNAPSHOT_MISMATCH = "CAPABILITY_SNAPSHOT_MISMATCH"
    const val CAPABILITY_UNAVAILABLE = "CAPABILITY_UNAVAILABLE"
    const val CANCELLATION_SCOPE_MISMATCH = "CANCELLATION_SCOPE_MISMATCH"
    const val EXECUTION_CANCELED = "EXECUTION_CANCELED"
    const val EXECUTION_NOT_RUNNING = "EXECUTION_NOT_RUNNING"
    const val UNSUPPORTED_IN_EXECUTION_SPACE = "UNSUPPORTED_IN_EXECUTION_SPACE"
}

sealed interface ExecutionAttemptCreation {
    data class Created(
        val attempt: ExecutionAttempt,
        val capabilitySnapshot: CapabilitySnapshot
    ) : ExecutionAttemptCreation

    data class Rejected(
        val diagnosticCode: String,
        val capabilitySnapshotRef: String,
        val capabilitySnapshotHash: String
    ) : ExecutionAttemptCreation
}

object ExecutionAttemptFactory {
    fun create(
        attemptId: String,
        executionId: String,
        cancellationScopeId: String,
        snapshot: CapabilitySnapshot
    ): ExecutionAttemptCreation {
        require(attemptId.isNotBlank()) { "Attempt id cannot be blank" }
        require(executionId.isNotBlank()) { "Execution id cannot be blank" }
        require(cancellationScopeId.isNotBlank()) { "Cancellation scope id cannot be blank" }
        val diagnostic = ExecutionRouteMatrix.validateCandidate(snapshot)
        if (diagnostic != null) {
            return ExecutionAttemptCreation.Rejected(
                diagnosticCode = diagnostic,
                capabilitySnapshotRef = snapshot.capabilitySnapshotRef,
                capabilitySnapshotHash = snapshot.capabilitySnapshotHash
            )
        }
        val context = ExecutionContext.freeze(executionId, cancellationScopeId, snapshot)
        return ExecutionAttemptCreation.Created(
            attempt = ExecutionAttempt.created(attemptId, context),
            capabilitySnapshot = snapshot
        )
    }
}

data class BackendEndpointSnapshot(
    val backendKind: ExecutionBackendKind,
    val backendSessionId: String,
    val displayId: Int,
    val available: Boolean
) {
    init {
        require(!available || backendSessionId.isNotBlank()) {
            "An available backend endpoint requires a session id"
        }
        require(displayId >= 0) { "Backend endpoint displayId cannot be negative" }
    }
}

class BackendInventory private constructor(
    endpoints: Collection<BackendEndpointSnapshot>
) {
    private val endpointsByKind: Map<ExecutionBackendKind, BackendEndpointSnapshot>

    init {
        require(endpoints.map(BackendEndpointSnapshot::backendKind).toSet().size == endpoints.size) {
            "Backend inventory cannot contain duplicate backend kinds"
        }
        endpointsByKind = endpoints.associateBy(BackendEndpointSnapshot::backendKind).toMap()
    }

    fun exact(backendKind: ExecutionBackendKind): BackendEndpointSnapshot? = endpointsByKind[backendKind]

    companion object {
        fun of(vararg endpoints: BackendEndpointSnapshot): BackendInventory = BackendInventory(endpoints.toList())
    }
}

sealed interface ExecutionRouteResolution {
    data class Bound(val endpoint: BackendEndpointSnapshot) : ExecutionRouteResolution
    data class Rejected(val diagnosticCode: String) : ExecutionRouteResolution
}

object ExecutionRouteMatrix {
    fun expectedBackend(
        runtimeMode: RuntimeMode,
        executionSpace: ExecutionSpace
    ): ExecutionBackendKind? = when (runtimeMode to executionSpace) {
        RuntimeMode.STANDARD to ExecutionSpace.VIRTUAL_SPACE -> ExecutionBackendKind.AIR_SIDECAR
        else -> null
    }

    fun validateCandidate(snapshot: CapabilitySnapshot): String? {
        if (expectedBackend(snapshot.runtimeMode, snapshot.executionSpace) == null) {
            return AttemptDiagnosticCode.UNSUPPORTED_IN_EXECUTION_SPACE
        }
        if (!snapshot.backendAvailable) return AttemptDiagnosticCode.BACKEND_UNAVAILABLE
        if (snapshot.backendKind != expectedBackend(snapshot.runtimeMode, snapshot.executionSpace)) {
            return AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH
        }
        return displayDiagnostic(snapshot.executionSpace, snapshot.displayId)
    }

    fun resolve(
        context: ExecutionContext,
        inventory: BackendInventory
    ): ExecutionRouteResolution {
        displayDiagnostic(context.executionSpace, context.displayId)?.let { diagnostic ->
            return ExecutionRouteResolution.Rejected(diagnostic)
        }
        val expected = expectedBackend(context.runtimeMode, context.executionSpace)
            ?: return ExecutionRouteResolution.Rejected(AttemptDiagnosticCode.UNSUPPORTED_IN_EXECUTION_SPACE)
        val endpoint = inventory.exact(expected)
            ?: return ExecutionRouteResolution.Rejected(AttemptDiagnosticCode.BACKEND_UNAVAILABLE)
        if (!endpoint.available) {
            return ExecutionRouteResolution.Rejected(AttemptDiagnosticCode.BACKEND_UNAVAILABLE)
        }
        if (endpoint.backendSessionId != context.backendSessionId) {
            return ExecutionRouteResolution.Rejected(AttemptDiagnosticCode.BACKEND_SESSION_MISMATCH)
        }
        if (endpoint.displayId != context.displayId) {
            return ExecutionRouteResolution.Rejected(AttemptDiagnosticCode.DISPLAY_MISMATCH)
        }
        return ExecutionRouteResolution.Bound(endpoint)
    }

    private fun displayDiagnostic(
        executionSpace: ExecutionSpace,
        displayId: Int
    ): String? = when (executionSpace) {
        ExecutionSpace.MAIN_SCREEN -> AttemptDiagnosticCode.UNSUPPORTED_IN_EXECUTION_SPACE
        ExecutionSpace.VIRTUAL_SPACE -> AttemptDiagnosticCode.DISPLAY_MISMATCH.takeIf { displayId <= 0 }
    }
}
