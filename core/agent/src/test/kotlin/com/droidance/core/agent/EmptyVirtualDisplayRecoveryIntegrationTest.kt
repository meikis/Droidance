// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolCallRejectedEventPayload
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Exercises the real coordination loop: an empty display is a device fact, never a screen frame. */
class EmptyVirtualDisplayRecoveryIntegrationTest {
    @Test
    fun failedLaunchReturnsTheEmptyDisplayToTheModelAndAChangedLaunchRestoresTheFrame() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("failed-launch-empty-display", goal)
        var launches = 0
        var reads = 0
        var takeoverRequests = 0
        fixture.toolExecutor.resultOverride = { call, _ ->
            when (call.name) {
                "android_act" -> if (++launches == 1) launchFailure else ToolProtocolResult.success("Launcher accepted")
                "android_read" -> if (++reads == 1) emptyDisplay else fixture.toolExecutor.stageObservation(
                    newFrame, "recovered_frame_$reads", "Freshly launched application screen", reads.toLong() + 1)
                else -> null
            }
        }
        fixture.model.roundResponses += listOf(call("open-route", "android_act", openArguments))
        fixture.model.roundResponses += listOf(call("open-launcher", "android_act", launcherArguments))
        fixture.model.roundResponses += listOf(call("click-fresh-frame", "screen_act", clickArguments))

        run(fixture, started, oldFrame) { takeoverRequests++ }

        assertEquals(0, takeoverRequests)
        assertEquals(4, fixture.model.recordedRequests.size)
        assertEquals(listOf("android_act", "android_read", "android_act", "android_read", "screen_act"),
            fixture.toolExecutor.deviceCalls.take(5).map { it.name })
        val recoveryRequest = fixture.model.recordedRequests[1]
        val recoveryText = recoveryRequest.joinToString("\n") { it.content }
        assertTrue(recoveryText, recoveryText.contains("VIRTUAL_SPACE_LAUNCH_FAILED"))
        assertTrue(recoveryText, recoveryText.contains("VIRTUAL_SPACE_EMPTY"))
        assertTrue(recoveryText, recoveryText.contains(emptyCause))
        assertFalse("The stale frame must not be sent again", recoveryRequest.any {
            it.imageBase64 == encoded(oldFrame)
        })
        assertTrue("The recovered frame must be current in the next decision",
            fixture.model.recordedRequests[2].any { !it.historicalImage && it.imageBase64 == encoded(newFrame) })
        assertDiagnosticRetained(fixture, started, emptyCause)
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test
    fun emptyDisplayDoesNotAuthorizeCoordinatesFromThePreviousFrame() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("empty-display-stale-coordinates", goal)
        fixture.toolExecutor.resultOverride = { call, _ ->
            when (call.name) {
                "android_act" -> launchFailure
                "android_read" -> emptyDisplay
                else -> null
            }
        }
        fixture.model.roundResponses += listOf(call("failed-open", "android_act", openArguments))
        fixture.model.roundResponses += listOf(call("blind-click", "screen_act", clickArguments))
        var takeoverRequests = 0

        run(fixture, started, oldFrame) { takeoverRequests++ }

        assertEquals(0, takeoverRequests)
        assertEquals(3, fixture.model.recordedRequests.size)
        assertFalse(fixture.toolExecutor.deviceCalls.any { it.name == "screen_act" })
        assertFalse(fixture.model.recordedRequests[1].any { !it.historicalImage && it.imageBase64 != null })
        val events = fixture.events(started)
        val clickCallId = events.single {
            it.eventKind == AgentEventKind.TOOL_CALL &&
                Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == "blind-click"
        }.callId
        val rejection = events.filter { it.callId == clickCallId }.mapNotNull {
            when (it.eventKind) {
                AgentEventKind.TOOL_FINISHED -> Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result
                AgentEventKind.TOOL_CALL_REJECTED -> Json.decodeFromString<ToolCallRejectedEventPayload>(it.payloadJson).result
                else -> null
            }
        }.single()
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejection.executionStatus)
        assertEquals("CURRENT_SCREEN_REQUIRED", rejection.diagnosticCode)
        assertEveryDeclaredToolSettled(events)
    }

    @Test
    fun observationChannelFailureStillRequestsHumanAssistanceBeforeAnotherLaunch() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("unavailable-display-channel", goal)
        fixture.toolExecutor.resultOverride = { call, _ ->
            when (call.name) {
                "android_act" -> launchFailure
                "android_read" -> ToolProtocolResult.failed("OBSERVATION_UNAVAILABLE", false,
                    details = buildJsonObject { put("cause", "Frame transport unavailable") })
                else -> null
            }
        }
        fixture.model.roundResponses += listOf(call("failed-open", "android_act", openArguments))
        fixture.model.roundResponses += listOf(call("must-not-launch", "android_act", launcherArguments))
        var takeoverRequests = 0

        run(fixture, started) { takeoverRequests++ }

        assertEquals(1, takeoverRequests)
        assertEquals(1, fixture.model.recordedRequests.size)
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "android_act" })
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test
    fun explicitEmptyObservationClearsTheOldFrameAndAllowsANativeLaunch() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("explicit-empty-observation", goal)
        var reads = 0
        var takeoverRequests = 0
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "android_read") {
                if (++reads == 1) emptyDisplay else fixture.toolExecutor.stageObservation(
                    newFrame, "explicit_recovery_$reads", "Application entry is now visible", reads.toLong() + 1)
            } else null
        }
        fixture.model.roundResponses += listOf(call("inspect", "android_read", """{"op":"screen"}"""))
        fixture.model.roundResponses += listOf(call("launcher", "android_act", launcherArguments))
        fixture.model.roundResponses += listOf(call("fresh-click", "screen_act", clickArguments))

        run(fixture, started, oldFrame) { takeoverRequests++ }

        assertEquals(0, takeoverRequests)
        assertEquals(4, fixture.model.recordedRequests.size)
        assertEquals(listOf("android_read", "android_act", "android_read", "screen_act"),
            fixture.toolExecutor.deviceCalls.take(4).map { it.name })
        assertFalse(fixture.model.recordedRequests[1].any { it.imageBase64 == encoded(oldFrame) })
        assertTrue(fixture.model.recordedRequests[1].any { it.content.contains(emptyCause) })
        assertTrue(fixture.model.recordedRequests[2].any { !it.historicalImage && it.imageBase64 == encoded(newFrame) })
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test
    fun acceptedLaunchWithAnEmptyPostActionObservationDoesNotForceAnotherCaptureBeforeTheModel() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("accepted-launch-empty-display", goal)
        var launches = 0
        var reads = 0
        var takeoverRequests = 0
        fixture.toolExecutor.resultOverride = { call, _ ->
            when (call.name) {
                "android_act" -> if (++launches == 1) ToolProtocolResult.accepted("Route launch accepted")
                    else ToolProtocolResult.success("Launcher accepted")
                "android_read" -> if (++reads == 1) emptyDisplay else fixture.toolExecutor.stageObservation(
                    newFrame, "accepted_recovery_$reads", "Application entry is visible", reads.toLong())
                else -> null
            }
        }
        fixture.model.roundResponses += listOf(call("accepted-route", "android_act", openArguments))
        fixture.model.roundResponses += listOf(call("changed-launch", "android_act", launcherArguments))

        run(fixture, started) { takeoverRequests++ }

        assertEquals(0, takeoverRequests)
        assertEquals(3, fixture.model.recordedRequests.size)
        assertEquals(listOf("android_act", "android_read", "android_act", "android_read"),
            fixture.toolExecutor.deviceCalls.map { it.name })
        assertTrue(fixture.model.recordedRequests[1].any { it.content.contains(emptyCause) })
        assertFalse(fixture.model.recordedRequests[1].any { !it.historicalImage && it.imageBase64 != null })
        assertDiagnosticRetained(fixture, started, emptyCause)
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test
    fun requiredInitialFrameCannotBeReplacedByAnEmptyDisplayDiagnostic() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("required-initial-frame-empty", goal)
        fixture.toolExecutor.resultOverride = { call, _ ->
            emptyDisplay.takeIf { call.name == "android_read" }
        }
        fixture.model.roundResponses += listOf(call("must-not-launch", "android_act", launcherArguments))

        val result = fixture.coordinator.coordinateTask(
            taskGoal = goal, additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile,
            mode = started.mode, initialScreenRequired = true)
        fixture.complete(started, result)

        assertEquals(TurnTerminal.SYSTEM_ERROR, result.terminal)
        assertEquals(TurnFailureReason.CHANNEL_DOWN, result.failureReason)
        assertTrue(fixture.model.recordedRequests.isEmpty())
        assertEquals(listOf("android_read"), fixture.toolExecutor.deviceCalls.map { it.name })
        assertFalse(fixture.events(started).any { it.eventKind == AgentEventKind.TOOL_CALL })
    }

    private suspend fun run(
        fixture: TaskCoordinatorTestFixture,
        started: TaskCoordinatorTestFixture.StartedTurn,
        initialFrame: ByteArray? = null,
        onTakeover: () -> Unit
    ) {
        val result = fixture.coordinator.coordinateTask(
taskGoal = goal,
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
initialScreenRequired = initialFrame != null,
initialScreenFrame = initialFrame,
host = object : com.droidance.core.agent.TurnHost {
    override val onManualTakeoverNeeded: (reason: String) -> Unit = { onTakeover() }
    override val waitIfPaused: suspend () -> Unit = {
                // End this observation-only script with an explicit transport failure.
                if (fixture.model.roundResponses.isEmpty()) {
                    fixture.model.nextStreamFailure = IllegalStateException("Fixture script ended")
                }
            }
}
)
        fixture.complete(started, result)
    }

    private fun assertDiagnosticRetained(
        fixture: TaskCoordinatorTestFixture,
        started: TaskCoordinatorTestFixture.StartedTurn,
        expected: String
    ) {
        val diagnosticContents = fixture.events(started)
            .filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
            .map { Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson) }
            .filter { it.role == DetailAttachmentRole.DIAGNOSTIC }
            .map { fixture.blobStore.read(it.contentRef, it.sizeBytes).toString(Charsets.UTF_8) }
        assertTrue("Empty observation must be retained as diagnostic content: $diagnosticContents",
            diagnosticContents.any { it.contains("VIRTUAL_SPACE_EMPTY") && it.contains(expected) })
    }

    private fun assertEveryDeclaredToolSettled(events: List<AgentEventEnvelope>) {
        val declarations = events.filter { it.eventKind == AgentEventKind.TOOL_CALL }
        assertTrue("The production loop must declare the model's tool calls", declarations.isNotEmpty())
        declarations.forEach { declaration ->
            val lifecycle = events.filter { it.callId == declaration.callId }
            val starts = lifecycle.count { it.eventKind == AgentEventKind.TOOL_STARTED }
            val rejects = lifecycle.count { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }
            val finishes = lifecycle.count { it.eventKind == AgentEventKind.TOOL_FINISHED }
            assertTrue("Tool ${declaration.callId} must have one settled lifecycle",
                (starts == 1 && rejects == 0 && finishes == 1) ||
                    (starts == 0 && rejects == 1 && finishes == 0))
            lifecycle.filter { it.eventKind == AgentEventKind.TOOL_FINISHED }.forEach {
                assertNotNull(Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result)
            }
        }
    }

    private fun call(id: String, name: String, arguments: String) =
        LLMStreamEvent.ToolCallChunk(0, id, name, arguments)

    private fun encoded(frame: ByteArray): String = Base64.getEncoder().encodeToString(frame)

    private companion object {
        const val goal = "Open the requested application"
        const val openArguments = """{"op":"open","uri":"example://requested-route","package_name":"example.target"}"""
        const val launcherArguments = """{"op":"launch","package_name":"example.target"}"""
        const val clickArguments = """{"actions":[{"op":"click","x":500,"y":500}]}"""
        const val emptyCause = "The frozen virtual display has no activity after the launch attempt"
        val oldFrame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0)
        val newFrame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)
        val launchFailure: ToolExecutionResult = ToolProtocolResult.failed("VIRTUAL_SPACE_LAUNCH_FAILED", false,
            details = buildJsonObject { put("cause", "Requested activity is not exported") })
        val emptyDisplay: ToolExecutionResult = ToolProtocolResult.failed("VIRTUAL_SPACE_EMPTY", true,
            details = buildJsonObject { put("display_id", 7); put("cause", emptyCause) })
    }
}
