// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ObservationBootstrapIntegrationTest {
    @Test fun postActionSourceIsPersistedBeforeItsFirstRequestAndSentReceiptRemainsAnImmutablePrefix() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Inspect the requested record in the target application"
        val started = fixture.startTurn("post-action-prefix", goal, activate = false)
        val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 2, 2)
        fixture.toolExecutor.postActionCaptureTools = setOf("android_act")
        fixture.toolExecutor.resultOverride = { call, _ ->
            when (call.name) {
                "android_act" -> ToolProtocolResult.success("Target launch delivered")
                // The existing bootstrap read supplies the first actual post-action frame.
                // The runtime persists it as an independent CURRENT_OBSERVATION source.
                "android_read" -> fixture.toolExecutor.stageObservation(frame, "post-launch-frame",
                    "Observed target record", 2L)
                else -> null
            }
        }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "launch", "android_act",
            """{"op":"open","package_name":"example.target"}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "read-note", "workspace",
            """{"op":"read","path":"work.md"}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish",
            """{"status":"need_more_info","results":[{"text":"The target record was observed."}],"unresolved_requirements":["Fixture ends after inspecting the source"]}"""))

        fixture.coordinator.coordinateTask(taskGoal = goal, additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = fixture.expectedBackend, executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode)

        assertEquals(fixture.diagnostics.joinToString("\n"), 3, fixture.model.recordedRequests.size)
        val firstSourceRequest = fixture.model.recordedRequests[1]
        val laterRequest = fixture.model.recordedRequests[2]
        val firstReceipt = firstSourceRequest.single { it.role == Role.TOOL && it.toolCallId == "launch" }
        val laterReceipt = laterRequest.single { it.role == Role.TOOL && it.toolCallId == "launch" }
        assertTrue("The first request must already contain the durable source reference", firstReceipt.content
            .contains("observation id=post-launch-frame"))
        assertEquals(firstReceipt.copy(timestamp = 0), laterReceipt.copy(timestamp = 0))
        val encodedFrame = Base64.getEncoder().encodeToString(frame)
        for (request in listOf(firstSourceRequest, laterRequest)) {
            assertEquals("The selected frame must not be duplicated by history projection", 1,
                request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }.count { it == encodedFrame })
        }
        val immutablePrefix = firstSourceRequest.takeWhile {
            !it.historicalImage && !it.content.startsWith("[image_source") && !it.content.startsWith("<active_task>")
        }.map { it.copy(timestamp = 0) }
        assertFalse(immutablePrefix.isEmpty())
        assertEquals(immutablePrefix, laterRequest.take(immutablePrefix.size).map { it.copy(timestamp = 0) })
        val events = fixture.events(started)
        val attachmentEvent = events.single { event -> event.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED &&
            Json.decodeFromString<DetailAttachmentAddedEventPayload>(event.payloadJson).observationId == "post-launch-frame" }
        val attachment = Json.decodeFromString<DetailAttachmentAddedEventPayload>(attachmentEvent.payloadJson)
        assertArrayEquals(frame, fixture.blobStore.read(attachment.contentRef, 100))
        assertTrue(attachmentEvent.sequence < events.filter { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED }[1].sequence)
        assertEquals(listOf("android_act", "android_read"), fixture.toolExecutor.deviceCalls.map { it.name })
        assertEquals("""{"op":"screen"}""", fixture.toolExecutor.deviceCalls.single {
            it.name == "android_read"
        }.arguments)
    }

    @Test fun shellResultCanBeDeliveredWithoutCapturingTheAssistantScreen() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("shell-native", "Read the filesystem", activate = false)
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "android_read") ToolProtocolResult.notExecuted("FOREGROUND_IS_DROIDANCE", true)
            else null
        }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "read", "shell",
            """{"command":"df /data","expect_screen_change":false}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", """{"status":"need_more_info","deliverables":[],"results":[],"unresolved_requirements":["Fixture stops after reading"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}"""))
        fixture.coordinator.coordinateTask(taskGoal = "Read the filesystem", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = fixture.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        assertEquals(2, fixture.model.recordedRequests.size)
        assertEquals(listOf("shell"), fixture.toolExecutor.deviceCalls.map { it.name })
        assertTrue(fixture.model.recordedRequests.last().any { it.content.contains("Executed shell") })
    }

    @Test fun invalidFirstScreenCallCanRepairItsSchemaBeforeAnyScreenExists() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("screen-bootstrap", "Open a target", activate = false)
        fixture.toolExecutor.definitionOverride = { name ->
            if (name == "screen_act") ToolDefinition(name, "Screen operation", mapOf(
                "op" to ToolParameter("string", "Operation", true, enumValues = listOf("click", "press_key"))))
            else null
        }
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "android_read") ToolProtocolResult.notExecuted("FOREGROUND_IS_DROIDANCE", true)
            else null
        }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "invalid", "screen_act",
            """{"actions":[{"op":"screenshot"}]}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "repair", "android_act",
            """{"op":"open","package":"com.example.target","expect_screen_change":false}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", """{"status":"need_more_info","deliverables":[],"results":[],"unresolved_requirements":["Fixture stops after launching"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}"""))
        fixture.coordinator.coordinateTask(taskGoal = "Open a target", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = fixture.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        assertEquals(fixture.diagnostics.joinToString("\n") + "\n" + fixture.toolExecutor.deviceCalls.toString(), 3, fixture.model.recordedRequests.size)
        assertEquals(listOf("android_act"), fixture.toolExecutor.deviceCalls.map { it.name })
        assertTrue(fixture.eventStore.eventsForTurn(started.active.scope.turnId).any {
            it.eventKind == AgentEventKind.TOOL_CALL_REJECTED && it.payloadJson.contains("ARGUMENT_SCHEMA_MISMATCH") })
    }
}
