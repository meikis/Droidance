// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Production coordinator, durable commits and context reconstruction; model/tools are scripted. */
class InterimReplyIntegrationTest {
    @Test fun everyInterimReplyIsKeptWithoutExtraRoundsOrAClassificationParameter() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Review two supplied sources and summarize the findings"
        val started = fixture.startTurn("staged-delivery", goal, OperatorToolProfile.LOCAL_KNOWLEDGE)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("I will review the supplied sources."),
            call("first", "workspace", """{"op":"list"}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("First stage: the first source has been reviewed; one field remains unknown."),
            call("next", "workspace", """{"op":"read","path":"second.md"}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Second stage: both sources are retained with their separate scopes."),
            call("combine", "workspace", """{"op":"list"}"""))
        fixture.model.roundResponses += listOf(call("finish", "done",
            """{"status":"done","results":[{"text":"Overall result: both sources were reviewed; one field is still unknown."}],"unresolved_requirements":[]}"""))

        val result = fixture.coordinator.coordinateTask(
taskGoal = goal,
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = null,
executionStrategy = null,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { error("Stage delivery must not prepare a device") }
}
)
        fixture.complete(started, result)
        assertEquals(fixture.diagnostics.toString(), TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals(4, fixture.model.recordedRequests.size)
        val events = fixture.events(started)
        val replies = events.filter { it.eventKind == AgentEventKind.PUBLIC_PROGRESS }
            .map { Json.decodeFromString<PublicProgressEventPayload>(it.payloadJson).publicBlocks.single() }
        assertEquals(listOf("First stage: the first source has been reviewed; one field remains unknown.",
            "Second stage: both sources are retained with their separate scopes."), replies.takeLast(2))
        assertTrue(fixture.model.recordedRequests[2].any { it.content.contains(replies[replies.size - 2]) })
        assertTrue(fixture.model.recordedRequests.last().any { it.content.contains(replies.last()) })
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        val replay = AgentEventReadModelProjector.project(fixture.eventStore.eventsForThread(started.active.scope.threadId))
        assertEquals(replies, replay.activityByTurn.getValue(started.active.scope.turnId).publicProgressHistory.map { it.text })
        assertEquals(result.summary, replay.conversation.entries.last().text)
        assertTrue(fixture.model.recordedTools.first().none { "public_output_kind" in it.parameters })
    }

    private fun call(id: String, name: String, args: String) = LLMStreamEvent.ToolCallChunk(0, id, name, args)
}
