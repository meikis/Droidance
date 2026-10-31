// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.attempt

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RepairPurpose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionCapabilityPreflightTest {
    @Test fun nativeReadsDoNotRequireScreenObservationCapability() {
        val route = routeCases().first { it.space == ExecutionSpace.VIRTUAL_SPACE }
        val snapshot = capabilitySnapshot(route, capabilities = setOf(DeviceCapability.READ_DEVICE))
        val resolution = readyBinding(snapshot, inventoryFor(snapshot))
        val preflight = ExecutionCapabilityPreflight(resolution.binding,
            listOf(DeviceToolCapabilityRequirement("android_read", DeviceCapability.READ_DEVICE)))
        val contract = RegisteredToolContract("android_read", "read", ToolBinding.DEVICE_BOUND, ToolEffect.READ_ONLY, emptyMap())
        fun call(op: String) = com.droidance.core.agent.harness.PlannedToolCall(
            providerOrder = 0, callId = "call", providerCall = ProviderToolCall("provider", "android_read", """{"op":"$op"}"""),
            contract = contract)
        for (op in listOf("device", "system", "capabilities", "content")) assertNull(preflight.diagnosticFor(call(op)))
        for (op in listOf("screen")) assertEquals(AttemptDiagnosticCode.CAPABILITY_UNAVAILABLE, preflight.diagnosticFor(call(op)))
    }
    @Test
    fun malformed_arguments_keep_repair_details_in_the_persistable_rejection() {
        val route = routeCases().first()
        val snapshot = capabilitySnapshot(route)
        val prepared = plan(
            toolName = deviceCapabilityRequirements.first().toolName,
            resolution = readyBinding(snapshot, inventoryFor(snapshot)),
            argumentsJson = "{invalid xml=parameter}"
        )
        assertTrue(prepared.toolStarts.isEmpty())
        val result = prepared.toolRejections.single().result
        assertRejected(prepared.toolRejections.single(), "ARGUMENT_SCHEMA_MISMATCH")
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        assertTrue(details.getValue("issue").jsonPrimitive.content.startsWith("arguments contain invalid JSON:"))
        assertEquals("{invalid xml=parameter}", details.getValue("received").jsonPrimitive.content)
        assertTrue(result.outputText.contains("expected"))
        assertTrue(result.outputText.contains("retryable"))
        assertTrue(result.outputText.contains("NOT_EXECUTED"))
    }

    @Test
    fun all_device_tools_accept_only_the_exact_frozen_route_on_standard_virtual_space() {
        routeCases().forEach { route ->
            deviceCapabilityRequirements.forEach { requirement ->
                val snapshot = capabilitySnapshot(
                    route = route,
                    withScopedShell = route.space == ExecutionSpace.VIRTUAL_SPACE
                )
                val prepared = plan(
                    toolName = requirement.toolName,
                    resolution = readyBinding(snapshot, inventoryFor(snapshot))
                )

                assertEquals(emptyList<PlannedToolRejection>(), prepared.toolRejections)
                assertEquals(1, prepared.toolStarts.size)
                assertEquals(AgentEventKind.TOOL_STARTED, prepared.toolStarts.single().eventKind)
                assertNotNull(prepared.attempt)
                assertEquals(snapshot.capabilitySnapshotHash, prepared.capabilitySnapshot?.capabilitySnapshotHash)
                assertNull(prepared.candidateCapabilitySnapshotRef)
                assertNull(prepared.candidateCapabilitySnapshotHash)
                assertTrue(prepared.toolStarts.single().executionContext === prepared.attempt?.context)
                assertEquals(route.mode, prepared.attempt?.context?.runtimeMode)
                assertEquals(route.space, prepared.attempt?.context?.executionSpace)
                assertEquals(route.displayId, prepared.attempt?.context?.displayId)
                assertEquals(sessionId(route), prepared.attempt?.context?.backendSessionId)
                assertEquals(route.backend, snapshot.backendKind)
            }
        }
    }

    @Test
    fun device_shell_uses_the_frozen_backend_without_claiming_display_scoping() {
        routeCases().filter { it.space == ExecutionSpace.VIRTUAL_SPACE }.forEach { route ->
            val snapshot = capabilitySnapshot(route, withScopedShell = false)
            val prepared = plan(
                toolName = "run_shell",
                argumentsJson = "{\"cmd\":\"id\"}",
                resolution = readyBinding(snapshot, inventoryFor(snapshot))
            )
            assertTrue(prepared.toolRejections.isEmpty())
            val start = prepared.toolStarts.single()
            assertEquals(sessionId(route), start.executionContext?.backendSessionId)
            assertEquals(route.displayId, start.executionContext?.displayId)
        }
    }

    @Test
    fun scoped_shell_proof_must_be_sha256_and_match_mode_session_and_display() {
        val route = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        val valid = scopedShell(route)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(proofHash = "not-a-sha256")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilitySnapshot.create(
                capabilitySnapshotRef = "snapshot-ref",
                runtimeMode = RuntimeMode.ULTRA,
                executionSpace = ExecutionSpace.VIRTUAL_SPACE,
                displayId = route.displayId,
                backendKind = route.backend,
                backendSessionId = sessionId(route),
                backendAvailable = true,
                capabilities = DeviceCapability.entries.toSet(),
                scopedShellCapability = valid
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            capabilitySnapshot(route, scopedShellCapability = valid.copy(backendSessionId = "other-session"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            capabilitySnapshot(route, scopedShellCapability = valid.copy(displayId = route.displayId + 1))
        }
    }

    @Test
    fun missing_capability_rejects_every_device_tool_without_cross_mode_fallback() {
        val route = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        deviceCapabilityRequirements.forEach { requirement ->
            val snapshot = capabilitySnapshot(
                route = route,
                capabilities = DeviceCapability.entries.toSet() - requirement.capability
            )
            val prepared = plan(
                toolName = requirement.toolName,
                resolution = readyBinding(snapshot, inventoryFor(snapshot))
            )

            assertNotNull(prepared.attempt)
            val attempt = checkNotNull(prepared.attempt)
            assertNull(prepared.candidateCapabilitySnapshotRef)
            assertNull(prepared.candidateCapabilitySnapshotHash)
            assertTrue(prepared.toolStarts.isEmpty())
            val rejection = prepared.toolRejections.single()
            assertTrue(rejection.executionContext === attempt.context)
            assertEquals(attempt.attemptId, rejection.attemptId)
            assertEquals(attempt.context.executionId, rejection.executionId)
            assertRejected(
                rejection,
                AttemptDiagnosticCode.CAPABILITY_UNAVAILABLE
            )
        }
    }

    @Test
    fun mixed_static_and_capability_rejections_bind_only_the_post_freeze_rejection() {
        val route = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        val snapshot = capabilitySnapshot(
            route = route,
            capabilities = DeviceCapability.entries.toSet() - DeviceCapability.RUN_SHELL
        )
        val prepared = ExecutionRoundPlanner(
            registrations = deviceRegistrations,
            capabilityRequirements = deviceCapabilityRequirements,
            callIdFactory = { index, _ -> "call-$index" }
        )
            .plan(
                round = NormalizedModelRound.create(
                    publicBlocks = listOf("Starting"),
                    toolCalls = listOf(
                        ProviderToolCall("provider-shell", "run_shell", "{}"),
                        ProviderToolCall("provider-click", "click", "{}")
                    )
                ),
                startCommitted = false,
                repairPurpose = RepairPurpose.NONE,
                attemptBindingProvider = { readyBinding(snapshot, inventoryFor(snapshot)) }
            )

        val attempt = checkNotNull(prepared.attempt)
        assertTrue(prepared.toolStarts.isEmpty())
        assertEquals(listOf("run_shell", "click"), prepared.toolRejections.map { it.call.providerCall.name })
        val shellRejection = prepared.toolRejections[0]
        val staticClickRejection = prepared.toolRejections[1]
        assertRejected(shellRejection, AttemptDiagnosticCode.CAPABILITY_UNAVAILABLE)
        assertTrue(shellRejection.executionContext === attempt.context)
        assertEquals(attempt.attemptId, shellRejection.attemptId)
        assertRejected(staticClickRejection, "PRIOR_CALL_REJECTED")
        assertNull(staticClickRejection.attemptId)
        assertNull(staticClickRejection.executionId)
        assertNull(staticClickRejection.executionContext)
    }

    @Test
    fun capability_snapshot_change_after_freeze_is_rejected_without_mutating_attempt() {
        val route = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        val frozenSnapshot = capabilitySnapshot(route)
        val frozen = readyBinding(frozenSnapshot, inventoryFor(frozenSnapshot)).binding
        val changedSnapshot = capabilitySnapshot(
            route = route,
            capabilities = DeviceCapability.entries.toSet() - DeviceCapability.SWIPE
        )
        val prepared = plan(
            toolName = "click",
            resolution = AttemptBindingResolution.Ready(
                PreparedAttemptBinding.New(
                    frozen.copy(capabilitySnapshot = changedSnapshot)
                )
            )
        )

        assertEquals(frozenSnapshot.capabilitySnapshotHash, frozen.attempt.context.capabilitySnapshotHash)
        assertFalse(frozenSnapshot.capabilitySnapshotHash == changedSnapshot.capabilitySnapshotHash)
        assertNull(prepared.attempt)
        assertEquals(changedSnapshot.capabilitySnapshotRef, prepared.candidateCapabilitySnapshotRef)
        assertEquals(changedSnapshot.capabilitySnapshotHash, prepared.candidateCapabilitySnapshotHash)
        assertTrue(prepared.toolStarts.isEmpty())
        assertRejected(
            prepared.toolRejections.single(),
            AttemptDiagnosticCode.CAPABILITY_SNAPSHOT_MISMATCH
        )
    }

    @Test
    fun unavailable_expected_backend_does_not_fall_back_to_other_available_backends() {
        val route = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        val snapshot = capabilitySnapshot(route)
        val endpoints = routeCases().map { candidate ->
            BackendEndpointSnapshot(
                backendKind = candidate.backend,
                backendSessionId = if (candidate == route) "" else sessionId(candidate),
                displayId = candidate.displayId,
                available = candidate != route
            )
        }
        val prepared = plan(
            toolName = "click",
            resolution = readyBinding(snapshot, BackendInventory.of(*endpoints.toTypedArray()))
        )

        assertNull(prepared.attempt)
        assertEquals(snapshot.capabilitySnapshotRef, prepared.candidateCapabilitySnapshotRef)
        assertEquals(snapshot.capabilitySnapshotHash, prepared.candidateCapabilitySnapshotHash)
        assertTrue(prepared.toolStarts.isEmpty())
        assertRejected(prepared.toolRejections.single(), AttemptDiagnosticCode.BACKEND_UNAVAILABLE)
    }

    @Test
    fun backend_session_display_and_cancellation_fail_closed_before_start() {
        val route = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        val snapshot = capabilitySnapshot(route)
        val ready = readyBinding(snapshot, inventoryFor(snapshot)).binding
        val cases = listOf(
            AttemptBindingResolution.Ready(
                PreparedAttemptBinding.New(
                    ready.copy(
                        backendInventory = BackendInventory.of(
                            BackendEndpointSnapshot(route.backend, "different-session", route.displayId, true)
                        )
                    )
                )
            ) to AttemptDiagnosticCode.BACKEND_SESSION_MISMATCH,
            AttemptBindingResolution.Ready(
                PreparedAttemptBinding.New(
                    ready.copy(
                        backendInventory = BackendInventory.of(
                            BackendEndpointSnapshot(route.backend, sessionId(route), route.displayId + 1, true)
                        )
                    )
                )
            ) to AttemptDiagnosticCode.DISPLAY_MISMATCH,
            AttemptBindingResolution.Ready(
                PreparedAttemptBinding.New(
                    ready.copy(cancellation = CancellationScopeSnapshot("different-cancel", false))
                )
            ) to AttemptDiagnosticCode.CANCELLATION_SCOPE_MISMATCH,
            AttemptBindingResolution.Ready(
                PreparedAttemptBinding.New(
                    ready.copy(cancellation = CancellationScopeSnapshot(CANCELLATION_SCOPE_ID, true))
                )
            ) to AttemptDiagnosticCode.EXECUTION_CANCELED
        )

        cases.forEach { (resolution, expectedDiagnostic) ->
            val prepared = plan("click", resolution = resolution)
            assertNull(prepared.attempt)
            assertEquals(snapshot.capabilitySnapshotRef, prepared.candidateCapabilitySnapshotRef)
            assertEquals(snapshot.capabilitySnapshotHash, prepared.candidateCapabilitySnapshotHash)
            assertTrue(prepared.toolStarts.isEmpty())
            assertRejected(prepared.toolRejections.single(), expectedDiagnostic)
        }
    }

    @Test
    fun candidate_without_backend_session_is_rejected_without_placeholder_attempt() {
        val route = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        val unavailableSnapshot = CapabilitySnapshot.create(
            capabilitySnapshotRef = "unavailable-snapshot",
            runtimeMode = route.mode,
            executionSpace = route.space,
            displayId = route.displayId,
            backendKind = route.backend,
            backendSessionId = "",
            backendAvailable = false,
            capabilities = DeviceCapability.entries.toSet()
        )
        val resolution = FrozenAttemptBindingFactory.createNew(
            attemptId = ATTEMPT_ID,
            executionId = EXECUTION_ID,
            cancellationScopeId = CANCELLATION_SCOPE_ID,
            capabilitySnapshot = unavailableSnapshot,
            backendInventory = BackendInventory.of()
        ) as AttemptBindingResolution.Rejected
        val prepared = plan(toolName = "click", resolution = resolution)

        assertEquals(AttemptDiagnosticCode.BACKEND_UNAVAILABLE, resolution.diagnosticCode)
        assertNull(prepared.attempt)
        assertEquals(unavailableSnapshot.capabilitySnapshotRef, prepared.candidateCapabilitySnapshotRef)
        assertEquals(unavailableSnapshot.capabilitySnapshotHash, prepared.candidateCapabilitySnapshotHash)
        assertTrue(prepared.toolStarts.isEmpty())
        assertRejected(prepared.toolRejections.single(), AttemptDiagnosticCode.BACKEND_UNAVAILABLE)
    }

    @Test
    fun attempt_provider_is_lazy_for_chat_but_resolves_for_a_silent_device_action() {
        var providerInvocationCount = 0
        val planner = ExecutionRoundPlanner(
            registrations = deviceRegistrations,
            capabilityRequirements = deviceCapabilityRequirements,
            callIdFactory = { index, _ -> "call-$index" }
        )
        val chat = planner.plan(
            round = NormalizedModelRound.create(listOf("Hello"), emptyList()),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = {
                providerInvocationCount += 1
                AttemptBindingResolution.Missing
            }
        )
        val missingBinding = planner.plan(
            round = NormalizedModelRound.create(
                publicBlocks = emptyList(),
                toolCalls = listOf(ProviderToolCall("provider-call", "click", "{}"))
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = {
                providerInvocationCount += 1
                AttemptBindingResolution.Missing
            }
        )

        assertEquals(1, providerInvocationCount)
        assertNull(chat.attempt)
        assertNull(chat.candidateCapabilitySnapshotRef)
        assertNull(chat.candidateCapabilitySnapshotHash)
        assertTrue(chat.toolStarts.isEmpty())
        assertNull(missingBinding.attempt)
        assertNull(missingBinding.candidateCapabilitySnapshotRef)
        assertNull(missingBinding.candidateCapabilitySnapshotHash)
        assertTrue(missingBinding.toolStarts.isEmpty())
        val missingResult = missingBinding.toolRejections.single().result
        assertEquals(AttemptDiagnosticCode.MISSING_EXECUTION_CONTEXT, missingResult.diagnosticCode)
        assertTrue("A frozen task without a runtime must not invite retries",
            missingResult.outputText.contains("\"retryable\":false"))
        assertTrue(missingResult.outputText.contains("device tools cannot run in this task"))
    }

    @Test
    fun turn_local_tool_never_creates_an_execution_attempt() {
        var providerInvocationCount = 0
        val localRegistration = RegisteredToolContract(
            name = "read_local_state",
            description = "Read local test state",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY,
            parameters = emptyMap()
        )
        val planner = ExecutionRoundPlanner(
            registrations = listOf(localRegistration),
            capabilityRequirements = emptyList(),
            callIdFactory = { index, _ -> "call-$index" }
        )
        val prepared = planner.plan(
            round = NormalizedModelRound.create(
                publicBlocks = listOf("Checking"),
                toolCalls = listOf(ProviderToolCall("provider-call", localRegistration.name, "{}"))
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = {
                providerInvocationCount += 1
                AttemptBindingResolution.Missing
            }
        )

        assertEquals(0, providerInvocationCount)
        assertNull(prepared.attempt)
        assertNull(prepared.candidateCapabilitySnapshotRef)
        assertNull(prepared.candidateCapabilitySnapshotHash)
        assertEquals(1, prepared.toolStarts.size)
        assertNull(prepared.toolStarts.single().attemptId)
        assertNull(prepared.toolStarts.single().executionId)
        assertNull(prepared.toolStarts.single().executionContext)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (prepared.toolStarts as MutableList<PlannedToolStart>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (prepared.toolRejections as MutableList<PlannedToolRejection>).clear()
        }
    }

    @Test
    fun known_device_tool_cannot_be_registered_as_turn_local() {
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionRoundPlanner(
                registrations = listOf(
                    RegisteredToolContract(
                        name = "run_shell",
                        description = "Run a test shell command",
                        binding = ToolBinding.TURN_LOCAL,
                        effect = ToolEffect.STATE_CHANGING,
                        parameters = emptyMap()
                    )
                ),
                capabilityRequirements = listOf(
                    DeviceToolCapabilityRequirement("run_shell", DeviceCapability.RUN_SHELL)
                ),
                callIdFactory = { index, _ -> "call-$index" }
            )
        }
    }

    @Test
    fun device_tool_without_a_static_capability_requirement_is_a_registration_error() {
        var attemptProviderInvocationCount = 0

        assertThrows(IllegalArgumentException::class.java) {
            ExecutionRoundPlanner(
                registrations = listOf(
                    RegisteredToolContract(
                        name = "undeclared_device_tool",
                        description = "Use an undeclared test capability",
                        binding = ToolBinding.DEVICE_BOUND,
                        effect = ToolEffect.STATE_CHANGING,
                        parameters = emptyMap()
                    )
                ),
                capabilityRequirements = emptyList(),
                callIdFactory = { index, _ -> "call-$index" }
            ).plan(
                round = NormalizedModelRound.create(
                    publicBlocks = listOf("Starting"),
                    toolCalls = listOf(ProviderToolCall("provider-call", "undeclared_device_tool", "{}"))
                ),
                startCommitted = false,
                repairPurpose = RepairPurpose.NONE,
                attemptBindingProvider = {
                    attemptProviderInvocationCount += 1
                    AttemptBindingResolution.Missing
                }
            )
        }

        assertEquals(0, attemptProviderInvocationCount)
    }

    @Test
    fun localPreflightRejectsBeforeResolvingOrStartingAnExecutionAttempt() {
        var attemptProviderInvocationCount = 0
        val planner = ExecutionRoundPlanner(
            registrations = deviceRegistrations,
            capabilityRequirements = deviceCapabilityRequirements,
            callIdFactory = { index, _ -> "call-$index" },
            preflight = { call ->
                ToolExecutionResult(
                    outputText = "Local policy rejected this call",
                    executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                    diagnosticCode = "POLICY_REJECTED"
                ).takeIf { call.providerCall.name == "open_app" }
            }
        )

        val prepared = planner.plan(
            round = NormalizedModelRound.create(
                publicBlocks = listOf("Starting"),
                toolCalls = listOf(ProviderToolCall("provider-call", "open_app", "{}"))
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = {
                attemptProviderInvocationCount += 1
                AttemptBindingResolution.Missing
            }
        )

        assertEquals(0, attemptProviderInvocationCount)
        assertNull(prepared.attempt)
        assertTrue(prepared.toolStarts.isEmpty())
        assertEquals("POLICY_REJECTED", prepared.toolRejections.single().result.diagnosticCode)
        assertEquals(
            "Local policy rejected this call",
            prepared.toolRejections.single().result.outputText
        )
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, prepared.toolRejections.single().result.executionStatus)
    }

    @Test
    fun prepared_plan_rejects_incomplete_event_lists_and_non_not_executed_rejections() {
        val route = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        val snapshot = capabilitySnapshot(
            route = route,
            capabilities = DeviceCapability.entries.toSet() - DeviceCapability.RUN_SHELL
        )
        val prepared = plan(
            toolName = "run_shell",
            resolution = readyBinding(snapshot, inventoryFor(snapshot))
        )
        val rejection = prepared.toolRejections.single()

        assertThrows(IllegalArgumentException::class.java) {
            rejection.copy(
                result = rejection.result.copy(
                    executionStatus = ToolExecutionStatus.FAILED
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreparedRoundExecutionPlan(
                roundPlan = prepared.roundPlan,
                attemptBinding = prepared.attemptBinding,
                candidateCapabilitySnapshotRef = null,
                candidateCapabilitySnapshotHash = null,
                toolStarts = emptyList(),
                toolRejections = emptyList()
            )
        }

        val mainRoute = routeCases().single {
            it.mode == RuntimeMode.STANDARD && it.space == ExecutionSpace.VIRTUAL_SPACE
        }
        val mainSnapshot = capabilitySnapshot(mainRoute)
        val accepted = plan(
            toolName = "click",
            resolution = readyBinding(mainSnapshot, inventoryFor(mainSnapshot))
        )
        val originalStart = accepted.toolStarts.single()
        val changedCallWithSameId = originalStart.call.copy(
            providerCall = originalStart.call.providerCall.copy(argumentsJson = "{\"changed\":true}")
        )
        assertThrows(IllegalArgumentException::class.java) {
            PreparedRoundExecutionPlan(
                roundPlan = accepted.roundPlan,
                attemptBinding = accepted.attemptBinding,
                candidateCapabilitySnapshotRef = null,
                candidateCapabilitySnapshotHash = null,
                toolStarts = listOf(originalStart.copy(call = changedCallWithSameId)),
                toolRejections = emptyList()
            )
        }
    }

    private fun plan(
        toolName: String,
        resolution: AttemptBindingResolution,
        argumentsJson: String = "{}"
    ): PreparedRoundExecutionPlan {
        val planner = ExecutionRoundPlanner(
            registrations = deviceRegistrations,
            capabilityRequirements = deviceCapabilityRequirements,
            callIdFactory = { index, _ -> "call-$index" }
        )
        return planner.plan(
            round = NormalizedModelRound.create(
                publicBlocks = listOf("Starting"),
                toolCalls = listOf(ProviderToolCall("provider-call", toolName, argumentsJson))
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = { resolution }
        )
    }

    private fun readyBinding(
        snapshot: CapabilitySnapshot,
        inventory: BackendInventory
    ): AttemptBindingResolution.Ready = FrozenAttemptBindingFactory.createNew(
        attemptId = ATTEMPT_ID,
        executionId = EXECUTION_ID,
        cancellationScopeId = CANCELLATION_SCOPE_ID,
        capabilitySnapshot = snapshot,
        backendInventory = inventory
    ) as AttemptBindingResolution.Ready

    private fun capabilitySnapshot(
        route: RouteCase,
        capabilities: Set<DeviceCapability> = DeviceCapability.entries.toSet(),
        withScopedShell: Boolean = false,
        scopedShellCapability: ScopedShellCapability? = null
    ): CapabilitySnapshot = CapabilitySnapshot.create(
        capabilitySnapshotRef = "snapshot-ref",
        runtimeMode = route.mode,
        executionSpace = route.space,
        displayId = route.displayId,
        backendKind = route.backend,
        backendSessionId = sessionId(route),
        backendAvailable = true,
        capabilities = capabilities,
        scopedShellCapability = scopedShellCapability ?: if (withScopedShell) scopedShell(route) else null
    )

    private fun inventoryFor(snapshot: CapabilitySnapshot): BackendInventory {
        val endpoints = routeCases().map { route ->
            BackendEndpointSnapshot(
                backendKind = route.backend,
                backendSessionId = if (route.backend == snapshot.backendKind) {
                    snapshot.backendSessionId
                } else {
                    sessionId(route)
                },
                displayId = if (route.backend == snapshot.backendKind) snapshot.displayId else route.displayId,
                available = true
            )
        }
        return BackendInventory.of(*endpoints.toTypedArray())
    }

    private fun scopedShell(route: RouteCase): ScopedShellCapability = ScopedShellCapability(
        evidenceKind = ShellScopeEvidenceKind.OS_DISPLAY_SCOPED,
        backendKind = route.backend,
        backendSessionId = sessionId(route),
        displayId = route.displayId,
        proofRef = "scoped-shell-proof",
        proofHash = PROOF_HASH
    )

    private fun sessionId(route: RouteCase): String =
        "${route.backend.name.lowercase()}-session-${route.displayId}"

    private fun assertRejected(rejection: PlannedToolRejection, expectedDiagnostic: String) {
        assertEquals(AgentEventKind.TOOL_CALL_REJECTED, rejection.eventKind)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejection.result.executionStatus)
        assertEquals(ToolEvidenceStatus.NOT_REQUIRED, rejection.result.evidenceStatus)
        assertEquals(expectedDiagnostic, rejection.result.diagnosticCode)
    }

    private val deviceCapabilityRequirements: List<DeviceToolCapabilityRequirement> = listOf(
        DeviceToolCapabilityRequirement("observe_screen", DeviceCapability.OBSERVE_SCREEN),
        DeviceToolCapabilityRequirement("crop_zoom", DeviceCapability.OBSERVE_SCREEN),
        DeviceToolCapabilityRequirement("click", DeviceCapability.CLICK),
        DeviceToolCapabilityRequirement("swipe", DeviceCapability.SWIPE),
        DeviceToolCapabilityRequirement("type_text", DeviceCapability.TYPE_TEXT),
        DeviceToolCapabilityRequirement("press_key", DeviceCapability.PRESS_KEY),
        DeviceToolCapabilityRequirement("open_app", DeviceCapability.OPEN_APP),
        DeviceToolCapabilityRequirement("run_shell", DeviceCapability.RUN_SHELL)
    )

    private val deviceRegistrations: List<RegisteredToolContract> =
        deviceCapabilityRequirements.map { requirement ->
            RegisteredToolContract(
                name = requirement.toolName,
                description = "${requirement.toolName} test tool",
                binding = ToolBinding.DEVICE_BOUND,
                effect = if (requirement.capability == DeviceCapability.OBSERVE_SCREEN) {
                    ToolEffect.READ_ONLY
                } else {
                    ToolEffect.STATE_CHANGING
                },
                parameters = emptyMap()
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

    private companion object {
        const val ATTEMPT_ID = "attempt-1"
        const val EXECUTION_ID = "execution-1"
        const val CANCELLATION_SCOPE_ID = "cancel-1"
        val PROOF_HASH: String = "a".repeat(64)
    }
}
