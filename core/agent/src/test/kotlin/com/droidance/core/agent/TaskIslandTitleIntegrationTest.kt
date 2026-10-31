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

/** Runs the production coordinator; title metadata is supplied by the same fake model response. */
class TaskIslandTitleIntegrationTest {
    @Test fun taskNameIsPublishedOnceAndActionsCannotRenameIt() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("fixed-island-title", "Record the supplied facts",
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE, activate = false)
        fixture.model.roundResponses += listOf(
            call("launch", "打开应用", "外卖搜索"))
        fixture.model.roundResponses += listOf(
            call("repeat", "执行一组动作", "点击页面"))
        fixture.model.roundResponses += listOf(fixture.completionCall("stop",
            """{"status":"need_more_info","deliverables":[],"results":[{"text":"Need clarification","evidence_refs":[]}],"unresolved_requirements":["Choose an option"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}""",
            started.active.scope))
        val titles = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val result = fixture.coordinator.coordinateTask(
taskGoal = "Record the supplied facts",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onTaskTitle: (String) -> Unit = { value ->
                assertTrue(fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
                    .any { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson).finishMetadata["task_title"] == value })
                titles += value
            }
    override val onActionUpdate: (String) -> Unit = { actions += it }
}
)
        assertEquals(listOf("外卖搜索"), titles)
        assertTrue(actions.toString(), actions.containsAll(listOf("打开应用", "执行一组动作")))
        assertEquals("$result diagnostics=${fixture.diagnostics}", TurnTerminal.NEED_MORE_INFO, result.terminal)
        assertEquals(3, fixture.model.recordedRequests.size)
        assertTrue(fixture.model.recordedTools.first().all { !it.parameters.getValue("task_title").required })
        assertEquals(fixture.model.recordedTools.first(), fixture.model.recordedTools[1])
        assertTrue(fixture.toolExecutor.localCalls.all { "task_title" !in it.arguments })
        assertEquals(listOf("外卖搜索"), fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .mapNotNull { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson).finishMetadata["task_title"] })
    }

    @Test fun everyNewTaskGetsItsOwnTitleEvenWithALockedConversation() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val received = mutableListOf<String>()
        for ((id, name) in listOf("first-title" to "外卖搜索", "second-title" to "行程规划")) {
            fixture.toolExecutor.clearCompletionRequest()
            val started = fixture.startTurn(id, "User goal $id", profile = OperatorToolProfile.LOCAL_KNOWLEDGE, activate = false)
            fixture.eventStore.changeThreadMetadata(started.active.scope.threadId, "manual-$id", "Locked conversation", null)
            fixture.model.roundResponses += listOf(call(id, "整理资料", name))
            fixture.model.roundResponses += listOf(fixture.completionCall("finish-$id",
                """{"status":"need_more_info","deliverables":[],"results":[{"text":"Need user input","evidence_refs":[]}],"unresolved_requirements":["Confirm the next step"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}""",
                started.active.scope))
            val result = fixture.coordinator.coordinateTask(
taskGoal = "User goal $id",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onTaskTitle: (String) -> Unit = { received += it }
}
)
            assertEquals(TurnTerminal.NEED_MORE_INFO, result.terminal)
            assertEquals("Locked conversation", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
            assertTrue(fixture.model.recordedRequests.last().any { it.content.contains("\"update_allowed\":false") })
        }
        assertEquals(listOf("外卖搜索", "行程规划"), received)
        assertEquals(4, fixture.model.recordedRequests.size)
    }

    @Test fun missingTitleDoesNotCreateANamingOnlyRetry() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("omitted-island-title", "Say hello", profile = OperatorToolProfile.LOCAL_KNOWLEDGE, activate = false)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Hello"))
        val titles = mutableListOf<String>()
        val result = fixture.coordinator.coordinateTask(
taskGoal = "Say hello",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onTaskTitle: (String) -> Unit = { titles += it }
}
)
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertTrue(titles.isEmpty())
        assertEquals(1, fixture.model.recordedRequests.size)
    }

    private fun call(id: String, label: String, title: String) = LLMStreamEvent.ToolCallChunk(0, id, "update_memory",
        """{"fact":"supplied fact $id","action_title":"$label","task_title":"$title"}""")
}
