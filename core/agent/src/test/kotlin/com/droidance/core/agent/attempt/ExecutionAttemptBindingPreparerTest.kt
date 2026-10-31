// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.attempt

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.event.CapabilitySnapshotEventPayload
import com.droidance.core.agent.event.DurableExecutionAttemptSnapshot
import com.droidance.core.agent.event.ExecutionAttemptEventScope
import com.droidance.core.agent.event.ExecutionContextSnapshotPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionAttemptBindingPreparerTest {
    @Test
    fun retiredRoutesCannotReachBackendPreparation() {
        listOf(
            RuntimeMode.STANDARD to ExecutionSpace.MAIN_SCREEN,
            RuntimeMode.ULTRA to ExecutionSpace.MAIN_SCREEN,
            RuntimeMode.ULTRA to ExecutionSpace.VIRTUAL_SPACE
        ).forEach { (mode, space) ->
            assertThrows(IllegalArgumentException::class.java) { ExecutionAttemptRoute(mode, space) }
        }
    }

    @Test
    fun prepares_only_the_exact_backend_for_each_mode_and_space() = runBlocking {
        routeCases().forEach { routeCase ->
            val probe = FakeBackendProbe(
                prepared = { observationFor(routeCase.route) },
                inspected = { observationFor(routeCase.route) }
            )
            val resolution = ExecutionAttemptBindingPreparer(probe).prepareNew(
                identity = identity(routeCase.route),
                route = routeCase.route
            ) as AttemptBindingResolution.Ready

            assertEquals(routeCase.backend, resolution.binding.capabilitySnapshot.backendKind)
            assertEquals(routeCase.displayId, resolution.binding.attempt.context.displayId)
            assertEquals(routeCase.backend, resolution.binding.backendInventory.exact(routeCase.backend)?.backendKind)
            assertEquals(1, probe.prepareCalls)
            assertEquals(0, probe.inspectCalls)
        }
    }

    @Test
    fun expected_backend_uses_passive_inspection_without_preparing_again() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val expected = observationFor(route)
        val probe = FakeBackendProbe(
            prepared = { error("expected backend path must not prepare") },
            inspected = { expected }
        )

        val resolution = ExecutionAttemptBindingPreparer(probe).prepareNew(
            identity = identity(route),
            route = route,
            expectedBackend = expected
        )

        assertTrue(resolution is AttemptBindingResolution.Ready)
        assertEquals(0, probe.prepareCalls)
        assertEquals(1, probe.inspectCalls)
    }

    @Test
    fun expected_backend_drift_rejects_without_creating_a_binding() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val expected = observationFor(route)
        val probe = FakeBackendProbe(
            prepared = { error("drift must not prepare a replacement backend") },
            inspected = { expected.copy(backendSessionId = "drifted-session") }
        )

        val resolution = ExecutionAttemptBindingPreparer(probe).prepareNew(
            identity = identity(route),
            route = route,
            expectedBackend = expected
        )

        assertEquals(
            AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH,
            (resolution as AttemptBindingResolution.Rejected).diagnosticCode
        )
        assertEquals(0, probe.prepareCalls)
        assertEquals(1, probe.inspectCalls)
    }

    @Test
    fun expected_backend_preserves_unavailable_reason_and_rejects_changed_display_or_capabilities() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val expected = observationFor(route)
        val unavailable = expected.copy(backendAvailable = false, backendSessionId = "",
            capabilities = emptySet(), unavailableDiagnosticCode = AttemptDiagnosticCode.AIR_CHANNEL_RECOVERY_REQUIRED)
        val cases = listOf(
            unavailable to AttemptDiagnosticCode.AIR_CHANNEL_RECOVERY_REQUIRED,
            expected.copy(displayId = expected.displayId + 1) to AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH,
            expected.copy(capabilities = emptySet()) to AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH
        )
        cases.forEach { (observed, diagnostic) ->
            val probe = FakeBackendProbe(prepared = { error("must not prepare") }, inspected = { observed })
            val result = ExecutionAttemptBindingPreparer(probe).prepareNew(identity(route), route, expectedBackend = expected)
            assertEquals(diagnostic, (result as AttemptBindingResolution.Rejected).diagnosticCode)
            assertEquals(observed.capabilitySnapshot(route, identity(route).capabilitySnapshotRef).capabilitySnapshotHash,
                result.capabilitySnapshotHash)
            assertEquals(0, probe.prepareCalls)
            assertEquals(1, probe.inspectCalls)
        }
    }

    @Test
    fun preparation_rejects_a_backend_that_does_not_match_the_requested_route() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val probe = FakeBackendProbe(
            prepared = {
                observationFor(route).copy(backendKind = ExecutionBackendKind.ROOT_BACKEND)
            },
            inspected = { observationFor(route) }
        )

        val resolution = ExecutionAttemptBindingPreparer(probe).prepareNew(identity(route), route)

        assertEquals(AttemptDiagnosticCode.BACKEND_BINDING_MISMATCH, (resolution as AttemptBindingResolution.Rejected).diagnosticCode)
        assertEquals(1, probe.prepareCalls)
        assertEquals(0, probe.inspectCalls)
    }

    @Test
    fun preparation_preserves_a_typed_air_recovery_diagnostic() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val probe = FakeBackendProbe(
            prepared = {
                observationFor(route).copy(
                    backendSessionId = "",
                    backendAvailable = false,
                    capabilities = emptySet(),
                    unavailableDiagnosticCode = AttemptDiagnosticCode.AIR_CHANNEL_RECOVERY_REQUIRED
                )
            },
            inspected = { observationFor(route) }
        )

        val resolution = ExecutionAttemptBindingPreparer(probe).prepareNew(identity(route), route)

        assertEquals(
            AttemptDiagnosticCode.AIR_CHANNEL_RECOVERY_REQUIRED,
            (resolution as AttemptBindingResolution.Rejected).diagnosticCode
        )
    }

    @Test
    fun committed_binding_rejects_session_drift_without_preparing_a_replacement_backend() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val probe = FakeBackendProbe(
            prepared = { observationFor(route) },
            inspected = { observationFor(route).copy(backendSessionId = "different-session") }
        )
        val preparer = ExecutionAttemptBindingPreparer(probe)
        val binding = (preparer.prepareNew(identity(route), route) as AttemptBindingResolution.Ready).binding

        val verification = preparer.verifyCommitted(binding)

        assertEquals(
            AttemptDiagnosticCode.BACKEND_SESSION_MISMATCH,
            (verification as ExecutionAttemptBindingVerification.Rejected).diagnosticCode
        )
        assertEquals(1, probe.prepareCalls)
        assertEquals(1, probe.inspectCalls)
    }

    @Test
    fun committed_binding_preserves_a_typed_air_pairing_diagnostic() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val probe = FakeBackendProbe(
            prepared = { observationFor(route) },
            inspected = {
                observationFor(route).copy(
                    backendSessionId = "",
                    backendAvailable = false,
                    capabilities = emptySet(),
                    unavailableDiagnosticCode = AttemptDiagnosticCode.AIR_PAIRING_REQUIRED
                )
            }
        )
        val preparer = ExecutionAttemptBindingPreparer(probe)
        val binding = (preparer.prepareNew(identity(route), route) as AttemptBindingResolution.Ready).binding

        val verification = preparer.verifyCommitted(binding)

        assertEquals(
            AttemptDiagnosticCode.AIR_PAIRING_REQUIRED,
            (verification as ExecutionAttemptBindingVerification.Rejected).diagnosticCode
        )
    }

    @Test
    fun committed_binding_rejects_capability_change_after_the_model_round() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val probe = FakeBackendProbe(
            prepared = { observationFor(route) },
            inspected = {
                observationFor(route).copy(
                    capabilities = DeviceCapability.entries.toSet() - DeviceCapability.TYPE_TEXT
                )
            }
        )
        val preparer = ExecutionAttemptBindingPreparer(probe)
        val binding = (preparer.prepareNew(identity(route), route) as AttemptBindingResolution.Ready).binding

        val verification = preparer.verifyCommitted(binding)

        assertEquals(
            AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH,
            (verification as ExecutionAttemptBindingVerification.Rejected).diagnosticCode
        )
        assertEquals(1, probe.prepareCalls)
        assertEquals(1, probe.inspectCalls)
    }

    @Test
    fun committed_binding_accepts_an_unchanged_observation() = runBlocking {
        val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        val probe = FakeBackendProbe(
            prepared = { observationFor(route) },
            inspected = { observationFor(route) }
        )
        val preparer = ExecutionAttemptBindingPreparer(probe)
        val binding = (preparer.prepareNew(identity(route), route) as AttemptBindingResolution.Ready).binding

        assertTrue(preparer.verifyCommitted(binding) is ExecutionAttemptBindingVerification.Ready)
    }

    @Test
    fun continuation_round_restores_the_running_attempt_without_preparing_another_backend() =
        runBlocking {
            val route = ExecutionAttemptRoute(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
            val probe = FakeBackendProbe(
                prepared = { observationFor(route) },
                inspected = { observationFor(route) }
            )
            val preparer = ExecutionAttemptBindingPreparer(probe)
            val initial = preparer.prepareNew(identity(route), route) as AttemptBindingResolution.Ready
            val snapshot = durableSnapshot(initial.binding)

            val restored = preparer.restoreExisting(snapshot, route) as AttemptBindingResolution.Ready

            assertTrue(restored.preparedAttempt is PreparedAttemptBinding.Existing)
            assertEquals(initial.binding.attempt.attemptId, restored.binding.attempt.attemptId)
            assertEquals(
                initial.binding.attempt.context.executionId,
                restored.binding.attempt.context.executionId
            )
            assertEquals(1, probe.prepareCalls)
            assertEquals(1, probe.inspectCalls)
        }

    private fun identity(route: ExecutionAttemptRoute): NewExecutionAttemptIdentity =
        NewExecutionAttemptIdentity(
            attemptId = "attempt-${route.runtimeMode}-${route.executionSpace}",
            executionId = "execution-${route.runtimeMode}-${route.executionSpace}",
            cancellationScopeId = "cancel-${route.runtimeMode}-${route.executionSpace}",
            capabilitySnapshotRef = "snapshot-${route.runtimeMode}-${route.executionSpace}"
        )

    private fun observationFor(route: ExecutionAttemptRoute): ExecutionBackendObservation =
        ExecutionBackendObservation(
            backendKind = route.expectedBackend,
            displayId = if (route.executionSpace == ExecutionSpace.MAIN_SCREEN) 0 else 41,
            backendSessionId = "${route.expectedBackend.name.lowercase()}-session",
            backendAvailable = true,
            capabilities = DeviceCapability.entries.toSet()
        )

    private fun durableSnapshot(binding: FrozenAttemptBinding): DurableExecutionAttemptSnapshot {
        val context = binding.attempt.context
        val capability = binding.capabilitySnapshot
        return DurableExecutionAttemptSnapshot(
            scope = ExecutionAttemptEventScope(
                threadId = "thread-1",
                turnId = "turn-1",
                attemptId = binding.attempt.attemptId,
                executionId = context.executionId
            ),
            state = ExecutionAttemptState.RUNNING,
            creationSequence = 4L,
            lifecycleSequence = 5L,
            context = ExecutionContextSnapshotPayload(
                executionId = context.executionId,
                runtimeMode = context.runtimeMode,
                executionSpace = context.executionSpace,
                displayId = context.displayId,
                backendSessionId = context.backendSessionId,
                capabilitySnapshotRef = context.capabilitySnapshotRef,
                capabilitySnapshotHash = context.capabilitySnapshotHash,
                cancellationScopeId = context.cancellationScopeId
            ),
            capabilitySnapshot = CapabilitySnapshotEventPayload(
                capabilitySnapshotRef = capability.capabilitySnapshotRef,
                capabilitySnapshotHash = capability.capabilitySnapshotHash,
                runtimeMode = capability.runtimeMode,
                executionSpace = capability.executionSpace,
                displayId = capability.displayId,
                backendKind = capability.backendKind,
                backendSessionId = capability.backendSessionId,
                backendAvailable = capability.backendAvailable,
                capabilities = capability.capabilities.map(DeviceCapability::name),
                scopedShellCapability = null
            )
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
    ) {
        val route: ExecutionAttemptRoute
            get() = ExecutionAttemptRoute(mode, space)
    }

    private class FakeBackendProbe(
        private val prepared: (ExecutionAttemptRoute) -> ExecutionBackendObservation,
        private val inspected: (ExecutionAttemptRoute) -> ExecutionBackendObservation
    ) : ExecutionAttemptBackendProbe {
        var prepareCalls: Int = 0
            private set
        var inspectCalls: Int = 0
            private set

        override suspend fun prepare(route: ExecutionAttemptRoute): ExecutionBackendObservation {
            prepareCalls += 1
            return prepared(route)
        }

        override suspend fun inspect(route: ExecutionAttemptRoute): ExecutionBackendObservation {
            inspectCalls += 1
            return inspected(route)
        }
    }
}
