// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.knowledge.memory.DurableMemoryApplyResult
import com.droidance.core.knowledge.memory.DurableMemoryOperation
import com.droidance.core.knowledge.memory.DurableMemoryScope
import com.droidance.core.knowledge.memory.DurableMemoryUpdate
import com.droidance.core.device.permission.PendingPermissionRequest
import com.droidance.core.device.permission.PermissionRequestOutcome
import com.droidance.core.agent.TurnSessionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTurnServicesTest {
    private val history = ToolHistoryPort { ToolProtocolResult.success(it) }
    private val completion = ToolCompletionPort { candidate, state ->
        if (state.record(candidate)) {
            ToolProtocolResult.success("recorded")
        } else {
            ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }
    }
    @Test
    fun requireCurrentRejectsBeforeAnyTurnIsBound() {
        val services = ToolTurnServices()

        assertThrows(IllegalStateException::class.java) { services.requireCurrent() }
    }

    @Test
    fun bindingForwardsProgressMemoryAndInteractionPorts() = runBlocking {
        val services = ToolTurnServices()
        val ports = fakePorts("active")
        val binding = services.bind(TurnSessionContext("turn-active", "thread-active"), ports.progress, ports.memory, ports.interaction, history, completion)
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "language",
            category = "preference",
            content = "Reply in English",
            sourceQuote = "Reply in English"
        )
        val permission = PendingPermissionRequest(
            requestId = "permission-1",
            turnId = "turn-active",
            callId = "call-permission-1",
            permissions = listOf("android.permission.POST_NOTIFICATIONS"),
            reason = "show result",
            requestedAtMs = 123L
        )

        assertSame(ports.progress, binding.progress)
        assertSame(ports.memory, binding.memory)
        assertSame(ports.interaction, binding.interaction)
        assertSame(history, binding.history)
        assertSame(completion, binding.completion)
        assertEquals("exact-ref", binding.history.read("exact-ref").outputText)

        binding.progress.updateCurrentToolDetail("session_search", "{}")
        val memoryResult = binding.memory.applyMemoryUpdates(listOf(update))
        binding.interaction.requestHighRiskConfirmation("confirm action")
        binding.interaction.requestManualAssistance("need help", "follow the prompt")
        binding.interaction.beginRuntimePermissionRequest(permission)
        binding.interaction.resolveRuntimePermissionRequest(permission, PermissionRequestOutcome.GRANTED)

        assertEquals(listOf("active:session_search:{}"), ports.progressEvents)
        assertEquals(listOf(listOf(update)), ports.memoryUpdates)
        assertEquals(DurableMemoryApplyResult(1, 1, 1), memoryResult.getOrThrow())
        assertEquals(
            listOf(
                "active:high-risk:confirm action",
                "active:manual:need help:follow the prompt",
                "active:permission-begin:permission-1",
                "active:permission-resolve:permission-1:GRANTED"
            ),
            ports.interactionEvents
        )
    }

    @Test
    fun duplicateBindingIsRejectedAndTheFirstBindingRemainsCurrent() {
        val services = ToolTurnServices()
        val first = fakePorts("first")
        val second = fakePorts("second")
        val firstBinding = services.bind(TurnSessionContext("turn-first", "thread-first"), first.progress, first.memory, first.interaction, history, completion)

        assertThrows(IllegalStateException::class.java) {
            services.bind(TurnSessionContext("turn-second", "thread-second"), second.progress, second.memory, second.interaction, history, completion)
        }
        assertSame(firstBinding, services.requireCurrent())
        assertSame(first.progress, services.requireCurrent().progress)
    }

    @Test
    fun releaseClearsTheCurrentBinding() {
        val services = ToolTurnServices()
        val ports = fakePorts("released")
        val binding = services.bind(TurnSessionContext("turn-released", "thread-released"), ports.progress, ports.memory, ports.interaction, history, completion)

        services.release(binding)

        assertThrows(IllegalStateException::class.java) { services.requireCurrent() }
    }

    @Test
    fun releasingAnOldBindingDoesNotClearANewerBinding() {
        val services = ToolTurnServices()
        val oldPorts = fakePorts("old")
        val oldBinding = services.bind(TurnSessionContext("turn-old", "thread-old"), oldPorts.progress, oldPorts.memory, oldPorts.interaction, history, completion)
        services.release(oldBinding)

        val newPorts = fakePorts("new")
        val newBinding = services.bind(TurnSessionContext("turn-new", "thread-new"), newPorts.progress, newPorts.memory, newPorts.interaction, history, completion)
        services.release(oldBinding)

        assertSame(newBinding, services.requireCurrent())
        assertSame(newPorts.progress, services.requireCurrent().progress)
        services.release(newBinding)
    }

    @Test
    fun releaseUnbindsButOwnerClearReleasesTaskState() {
        val services = ToolTurnServices()
        val firstPorts = fakePorts("first-turn")
        val firstContext = TurnSessionContext("turn-1", "thread-1")
        val firstBinding = services.bind(
            firstContext,
            firstPorts.progress,
            firstPorts.memory,
            firstPorts.interaction,
            history,
            completion
        )

        val firstState = firstContext.getOrPutTyped { FakeTaskState() }.apply {
            completionSummary = "first completion"
            pendingScreenState = "first observation"
        }
        firstState.actionDeliveryByToolCall["call-1"] = mutableListOf(true)
        services.release(firstBinding)

        // ToolTurnServices only releases its binding. The TaskLifecycle-owned context remains
        // valid until its owner explicitly disposes it.
        assertSame(firstState, firstContext.getTyped<FakeTaskState>())
        assertTrue(firstState.actionDeliveryByToolCall.isNotEmpty())
        firstContext.clear()
        assertEquals(null, firstContext.getTyped<FakeTaskState>())
        assertThrows(IllegalStateException::class.java) {
            firstContext.getOrPutTyped { FakeTaskState() }
        }

        val secondPorts = fakePorts("second-turn")
        val secondContext = TurnSessionContext("turn-2", "thread-2")
        val secondBinding = services.bind(
            secondContext,
            secondPorts.progress,
            secondPorts.memory,
            secondPorts.interaction,
            history,
            completion
        )

        assertEquals(null, secondContext.getTyped<FakeTaskState>())
        val secondState = secondContext.getOrPutTyped { FakeTaskState() }
        assertTrue(secondState.actionDeliveryByToolCall.isEmpty())
        assertNotSame(firstState, secondState)
        assertNotSame(firstContext, secondBinding.sessionContext)

        services.release(secondBinding)
    }

    private data class FakeTaskState(
        var completionSummary: String? = null,
        var pendingScreenState: String? = null,
        val actionDeliveryByToolCall: MutableMap<String, MutableList<Boolean>> = mutableMapOf()
    )

    private data class FakePorts(
        val progress: ToolProgressPort,
        val memory: ToolMemoryPort,
        val interaction: ToolInteractionPort,
        val progressEvents: MutableList<String>,
        val memoryUpdates: MutableList<List<DurableMemoryUpdate>>,
        val interactionEvents: MutableList<String>
    )

    private fun fakePorts(label: String): FakePorts {
        val progressEvents = mutableListOf<String>()
        val memoryUpdates = mutableListOf<List<DurableMemoryUpdate>>()
        val interactionEvents = mutableListOf<String>()
        val progress = ToolProgressPort { toolName, arguments ->
            progressEvents += "$label:$toolName:$arguments"
        }
        val memory = ToolMemoryPort { updates ->
            memoryUpdates += updates
            Result.success(
                DurableMemoryApplyResult(
                    requested = updates.size,
                    grounded = updates.size,
                    changed = 1
                )
            )
        }
        val interaction = object : ToolInteractionPort {
            override fun requestHighRiskConfirmation(reason: String) {
                interactionEvents += "$label:high-risk:$reason"
            }

            override fun requestManualAssistance(
                reason: String,
                instruction: String?
            ) {
                interactionEvents += "$label:manual:$reason:$instruction"
            }

            override suspend fun beginRuntimePermissionRequest(request: PendingPermissionRequest) {
                interactionEvents += "$label:permission-begin:${request.requestId}"
            }

            override suspend fun resolveRuntimePermissionRequest(
                request: PendingPermissionRequest,
                outcome: PermissionRequestOutcome
            ) {
                interactionEvents += "$label:permission-resolve:${request.requestId}:$outcome"
            }
        }
        return FakePorts(
            progress = progress,
            memory = memory,
            interaction = interaction,
            progressEvents = progressEvents,
            memoryUpdates = memoryUpdates,
            interactionEvents = interactionEvents
        )
    }
}
