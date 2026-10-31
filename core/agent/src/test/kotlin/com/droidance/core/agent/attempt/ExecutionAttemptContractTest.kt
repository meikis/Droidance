// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.attempt

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionAttemptContractTest {
    @Test
    fun freezes_all_execution_context_dimensions_and_capability_content() {
        val mutableCapabilities = mutableSetOf(DeviceCapability.OBSERVE_SCREEN)
        val snapshot = capabilitySnapshot(
            mode = RuntimeMode.STANDARD,
            space = ExecutionSpace.VIRTUAL_SPACE,
            capabilities = mutableCapabilities
        )
        val creation = ExecutionAttemptFactory.create(
            attemptId = "attempt-1",
            executionId = "execution-1",
            cancellationScopeId = "cancel-1",
            snapshot = snapshot
        ) as ExecutionAttemptCreation.Created
        mutableCapabilities += DeviceCapability.CLICK

        val context = creation.attempt.context
        assertEquals(ExecutionAttemptState.CREATED, creation.attempt.state)
        assertEquals("attempt-1", creation.attempt.attemptId)
        assertEquals("execution-1", context.executionId)
        assertEquals(RuntimeMode.STANDARD, context.runtimeMode)
        assertEquals(ExecutionSpace.VIRTUAL_SPACE, context.executionSpace)
        assertEquals(41, context.displayId)
        assertEquals("air_sidecar-session-41", context.backendSessionId)
        assertEquals(snapshot.capabilitySnapshotRef, context.capabilitySnapshotRef)
        assertEquals(snapshot.capabilitySnapshotHash, context.capabilitySnapshotHash)
        assertEquals("cancel-1", context.cancellationScopeId)
        assertTrue(snapshot.supports(DeviceCapability.OBSERVE_SCREEN))
        assertFalse(snapshot.supports(DeviceCapability.CLICK))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.capabilities as MutableSet<DeviceCapability>).add(DeviceCapability.CLICK)
        }
    }

    @Test
    fun capability_hash_is_deterministic_and_changes_with_execution_binding() {
        val first = capabilitySnapshot(
            mode = RuntimeMode.STANDARD,
            space = ExecutionSpace.VIRTUAL_SPACE,
            capabilities = linkedSetOf(DeviceCapability.CLICK, DeviceCapability.OBSERVE_SCREEN)
        )
        val reordered = capabilitySnapshot(
            mode = RuntimeMode.STANDARD,
            space = ExecutionSpace.VIRTUAL_SPACE,
            capabilities = linkedSetOf(DeviceCapability.OBSERVE_SCREEN, DeviceCapability.CLICK)
        )
        val changedSession = CapabilitySnapshot.create(
            capabilitySnapshotRef = "snapshot-ref",
            runtimeMode = RuntimeMode.STANDARD,
            executionSpace = ExecutionSpace.VIRTUAL_SPACE,
            displayId = 41,
            backendKind = ExecutionBackendKind.AIR_SIDECAR,
            backendSessionId = "changed-session",
            backendAvailable = true,
            capabilities = setOf(DeviceCapability.CLICK, DeviceCapability.OBSERVE_SCREEN)
        )

        assertEquals(first.capabilitySnapshotHash, reordered.capabilitySnapshotHash)
        assertTrue(first.capabilitySnapshotHash.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals(first.capabilitySnapshotHash, changedSession.capabilitySnapshotHash)
    }

    @Test
    fun humanizedGestureIsAnExplicitFrozenCapabilityInsteadOfAModeAlias() {
        val without = capabilitySnapshot(
            mode = RuntimeMode.STANDARD,
            space = ExecutionSpace.VIRTUAL_SPACE,
            capabilities = setOf(DeviceCapability.CLICK)
        )
        val with = capabilitySnapshot(
            mode = RuntimeMode.STANDARD,
            space = ExecutionSpace.VIRTUAL_SPACE,
            capabilities = setOf(DeviceCapability.CLICK, DeviceCapability.HUMANIZED_GESTURE)
        )

        assertFalse(without.supports(DeviceCapability.HUMANIZED_GESTURE))
        assertTrue(with.supports(DeviceCapability.HUMANIZED_GESTURE))
        assertNotEquals(without.capabilitySnapshotHash, with.capabilitySnapshotHash)
    }

    @Test
    fun unavailable_backend_snapshot_can_report_no_session_and_never_freezes_a_context() {
        val snapshot = CapabilitySnapshot.create(
            capabilitySnapshotRef = "snapshot-unavailable",
            runtimeMode = RuntimeMode.STANDARD,
            executionSpace = ExecutionSpace.VIRTUAL_SPACE,
            displayId = 0,
            backendKind = ExecutionBackendKind.AIR_BACKEND,
            backendSessionId = "",
            backendAvailable = false,
            capabilities = emptySet()
        )

        assertTrue(snapshot.backendSessionId.isEmpty())
        assertRejected(snapshot, AttemptDiagnosticCode.BACKEND_UNAVAILABLE)
    }

    @Test
    fun invalid_candidate_binding_is_rejected_without_an_attempt() {
        val wrongBackend = CapabilitySnapshot.create(
            capabilitySnapshotRef = "snapshot-wrong-backend",
            runtimeMode = RuntimeMode.STANDARD,
            executionSpace = ExecutionSpace.VIRTUAL_SPACE,
            displayId = 0,
            backendKind = ExecutionBackendKind.ROOT_BACKEND,
            backendSessionId = "root-main",
            backendAvailable = true,
            capabilities = setOf(DeviceCapability.CLICK)
        )
        val wrongDisplay = CapabilitySnapshot.create(
            capabilitySnapshotRef = "snapshot-wrong-display",
            runtimeMode = RuntimeMode.STANDARD,
            executionSpace = ExecutionSpace.VIRTUAL_SPACE,
            displayId = 0,
            backendKind = ExecutionBackendKind.AIR_SIDECAR,
            backendSessionId = "root-sidecar",
            backendAvailable = true,
            capabilities = setOf(DeviceCapability.CLICK)
        )
        val unavailable = CapabilitySnapshot.create(
            capabilitySnapshotRef = "snapshot-unavailable",
            runtimeMode = RuntimeMode.STANDARD,
            executionSpace = ExecutionSpace.VIRTUAL_SPACE,
            displayId = 0,
            backendKind = ExecutionBackendKind.ROOT_BACKEND,
            backendSessionId = "root-main",
            backendAvailable = false,
            capabilities = setOf(DeviceCapability.CLICK)
        )

        assertRejected(wrongBackend, AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH)
        assertRejected(wrongDisplay, AttemptDiagnosticCode.DISPLAY_MISMATCH)
        assertRejected(unavailable, AttemptDiagnosticCode.BACKEND_UNAVAILABLE)
    }

    @Test
    fun virtual_space_route_resolves_only_the_exact_backend_and_display() {
        routeCases().forEach { case ->
            val snapshot = capabilitySnapshot(case.mode, case.space)
            val attempt = (ExecutionAttemptFactory.create(
                attemptId = "attempt-${case.mode}-${case.space}",
                executionId = "execution-${case.mode}-${case.space}",
                cancellationScopeId = "cancel-${case.mode}-${case.space}",
                snapshot = snapshot
            ) as ExecutionAttemptCreation.Created).attempt
            val resolution = ExecutionRouteMatrix.resolve(
                attempt.context,
                BackendInventory.of(
                    BackendEndpointSnapshot(
                        backendKind = case.backend,
                        backendSessionId = snapshot.backendSessionId,
                        displayId = case.displayId,
                        available = true
                    )
                )
            ) as ExecutionRouteResolution.Bound

            assertEquals(case.backend, resolution.endpoint.backendKind)
            assertEquals(case.displayId, resolution.endpoint.displayId)
            assertEquals(snapshot.backendSessionId, resolution.endpoint.backendSessionId)
        }
    }

    @Test
    fun unavailable_expected_backend_never_falls_back_to_an_available_other_mode() {
        val snapshot = capabilitySnapshot(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val attempt = (ExecutionAttemptFactory.create(
            attemptId = "attempt-air-main",
            executionId = "execution-air-main",
            cancellationScopeId = "cancel-air-main",
            snapshot = snapshot
        ) as ExecutionAttemptCreation.Created).attempt
        val resolution = ExecutionRouteMatrix.resolve(
            attempt.context,
            BackendInventory.of(
                BackendEndpointSnapshot(
                    backendKind = ExecutionBackendKind.AIR_SIDECAR,
                    backendSessionId = snapshot.backendSessionId,
                    displayId = 41,
                    available = false
                ),
                BackendEndpointSnapshot(
                    backendKind = ExecutionBackendKind.ROOT_BACKEND,
                    backendSessionId = "root-is-available",
                    displayId = 0,
                    available = true
                ),
                BackendEndpointSnapshot(
                    backendKind = ExecutionBackendKind.AIR_BACKEND,
                    backendSessionId = "retired-endpoint",
                    displayId = 0,
                    available = true
                )
            )
        ) as ExecutionRouteResolution.Rejected

        assertEquals(AttemptDiagnosticCode.BACKEND_UNAVAILABLE, resolution.diagnosticCode)
    }

    @Test
    fun retiredRoutesCannotCreateAnAttemptEvenWithAvailableEndpoints() {
        listOf(
            Triple(RuntimeMode.STANDARD, ExecutionSpace.MAIN_SCREEN, ExecutionBackendKind.AIR_BACKEND),
            Triple(RuntimeMode.ULTRA, ExecutionSpace.MAIN_SCREEN, ExecutionBackendKind.ROOT_BACKEND),
            Triple(RuntimeMode.ULTRA, ExecutionSpace.VIRTUAL_SPACE, ExecutionBackendKind.ROOT_SIDECAR)
        ).forEach { (mode, space, backend) ->
            val snapshot = CapabilitySnapshot.create(
                capabilitySnapshotRef = "retired-snapshot",
                runtimeMode = mode,
                executionSpace = space,
                displayId = if (space == ExecutionSpace.VIRTUAL_SPACE) 41 else 0,
                backendKind = backend,
                backendSessionId = "retired-session",
                backendAvailable = true,
                capabilities = DeviceCapability.entries.toSet()
            )
            assertEquals(null, ExecutionRouteMatrix.expectedBackend(mode, space))
            assertRejected(snapshot, AttemptDiagnosticCode.UNSUPPORTED_IN_EXECUTION_SPACE)
        }
    }

    private fun assertRejected(snapshot: CapabilitySnapshot, expectedDiagnostic: String) {
        val creation = ExecutionAttemptFactory.create(
            attemptId = "attempt",
            executionId = "execution",
            cancellationScopeId = "cancel",
            snapshot = snapshot
        ) as ExecutionAttemptCreation.Rejected
        assertEquals(expectedDiagnostic, creation.diagnosticCode)
        assertEquals(snapshot.capabilitySnapshotRef, creation.capabilitySnapshotRef)
        assertEquals(snapshot.capabilitySnapshotHash, creation.capabilitySnapshotHash)
    }

    private fun capabilitySnapshot(
        mode: RuntimeMode,
        space: ExecutionSpace,
        capabilities: Set<DeviceCapability> = DeviceCapability.entries.toSet()
    ): CapabilitySnapshot {
        val route = routeCases().single { it.mode == mode && it.space == space }
        return CapabilitySnapshot.create(
            capabilitySnapshotRef = "snapshot-ref",
            runtimeMode = mode,
            executionSpace = space,
            displayId = route.displayId,
            backendKind = route.backend,
            backendSessionId = "${route.backend.name.lowercase()}-session-${route.displayId}",
            backendAvailable = true,
            capabilities = capabilities
        )
    }

    private fun routeCases(): List<RouteCase> = listOf(
        RouteCase(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE, ExecutionBackendKind.AIR_SIDECAR, 41)
    )

    private data class RouteCase(
        val mode: RuntimeMode,
        val space: ExecutionSpace,
        val backend: ExecutionBackendKind,
        val displayId: Int
    )
}
