// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.prompt.sha256Hex
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleIntegrationTest {
    @Test fun chatKeepsAFittingTitleAndUpdatesTheMainTaskInTheNormalRequest() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val lifecycle = fixture.createLifecycle()
        val streamed = StringBuilder()
        val history = mutableListOf<ChatMessage>()
        suspend fun chat(id: String, user: String, title: String?, answer: String) {
            val wire = block(title) + answer
            fixture.model.roundResponses += wire.map { LLMStreamEvent.Content(it.toString()) }
            lifecycle.run("title-chat", request(id, user), fixture::operatorPrefix,
                unfinishedNotice = { _, _ -> TaskLifecycle.FailureNotice("failed", "Failed", "FAILED") },
                releaseResources = {}) { session ->
                val result = fixture.runOperator(session, user) { streamed.append(it.text) }
                assertEquals(answer, result.summary)
                session.complete("done", result.summary)
            }
            history += ChatMessage(Role.USER, user)
            history += ChatMessage(Role.ASSISTANT, answer)
        }
        chat("greeting", "你好", null, "你好。")
        assertEquals("你好", fixture.eventStore.threadReadModel("title-chat")!!.metadata.title)
        chat("first-goal", "帮我比较耳机", "耳机选购对比", "我会比较价格和续航。")
        chat("refinement", "预算改成500元", "耳机选购对比", "好的，按这个预算比较。")
        assertEquals("耳机选购对比", fixture.eventStore.threadReadModel("title-chat")!!.metadata.title)
        chat("focus", "主要对比降噪效果", "对比耳机降噪效果", "好的，重点比较降噪。")
        assertEquals("对比耳机降噪效果", fixture.eventStore.threadReadModel("title-chat")!!.metadata.title)
        chat("new-goal", "接着帮我规划杭州行程", "杭州行程规划", "我会先确认行程。")
        assertEquals("杭州行程规划", fixture.eventStore.threadReadModel("title-chat")!!.metadata.title)
        assertEquals(5, fixture.model.recordedRequests.size)
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertEquals(3, fixture.eventStore.eventsForThread("title-chat")
            .count { it.eventKind == AgentEventKind.THREAD_METADATA_CHANGED })
        assertFalse(streamed.contains("conversation_title"))
        assertFalse(streamed.contains("耳机选购对比"))
        assertTrue(fixture.eventStore.threadReadModel("title-chat")!!.conversation.entries
            .none { "conversation_title" in it.text })
        assertTrue(fixture.model.recordedRequests[2].any { "耳机选购对比" in it.content && "generated" in it.content })
    }

    @Test fun activatedLoopHandlesAUserGoalChangeOnceBeforeContinuingTools() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-active-steer", "Compare headphones", activate = false)
        fixture.model.roundResponses += listOf(named(LLMStreamEvent.ToolCallChunk(0, "write", "workspace", "{\"op\":\"write\",\"path\":\"note.md\",\"text\":\"fact\"}"), "耳机选购对比"))
        fixture.toolExecutor.beforeLocalExecute = { call ->
            if (call.name == "workspace") fixture.eventStore.recordUserSteer(started.active.scope, "changed-goal", "Now plan Hangzhou")
        }
        fixture.model.roundResponses += listOf(named(fixture.completionCall("finish", completion(), started.active.scope), "杭州行程规划"))
        val result = fixture.coordinator.coordinateTask("Compare headphones", "", started.active, "", null,
            started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(2, fixture.model.recordedRequests.size)
        val history = fixture.eventStore.eventsForThread(started.active.scope.threadId)
        assertEquals(2, history.count { it.eventKind == AgentEventKind.THREAD_METADATA_CHANGED })
        assertEquals("杭州行程规划", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
        assertFalse(result.summary.contains("conversation_title"))
        val rounds = history.filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .map { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson) }
        assertEquals(listOf("耳机选购对比", "杭州行程规划"), rounds.map { it.finishMetadata["conversation_title"] })
    }

    @Test fun anOmittedInitialTitleIsFilledByTheNextNormalResponseWithNoNamingRequest() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-omitted", "Compare headphones", activate = false)
        assertTrue(started.active.stablePrefix.systemPrompt.contains("conversation_title.md#policy"))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("I will collect the comparison."),
            LLMStreamEvent.ToolCallChunk(0, "write", "workspace", "{\"op\":\"write\",\"path\":\"note.md\",\"text\":\"fact\"}"))
        fixture.model.roundResponses += listOf(named(fixture.completionCall("finish", completion(), started.active.scope), "耳机选购对比"))
        val result = fixture.coordinator.coordinateTask("Compare headphones", "", started.active, "", null,
            started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(2, fixture.model.recordedRequests.size)
        assertTrue(fixture.model.recordedRequests.all { request ->
            request.any { "\"update_allowed\":true" in it.content && "\"title_state\":\"initial\"" in it.content }
        })
        assertEquals("耳机选购对比", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
        assertEquals(1, fixture.eventStore.eventsForThread(started.active.scope.threadId)
            .count { it.eventKind == AgentEventKind.THREAD_METADATA_CHANGED })
        val recovered = ConversationTitleSession(fixture.eventStore, started.active.scope)
        assertFalse(recovered.request().updateAllowed)
    }

    @Test fun toolResponsesUseNativeTitleAndNeverForwardNamingToExecution() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-native-only", "Read device facts", activate = false)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content(block("Wrong prose title")),
            named(LLMStreamEvent.ToolCallChunk(0, "write", "workspace",
                "{\"op\":\"write\",\"path\":\"note.md\",\"text\":\"fact\"}"), "设备状态查询"))
        fixture.toolExecutor.beforeLocalExecute = { call ->
            assertFalse(call.arguments.contains("conversation_title"))
            assertEquals("设备状态查询", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
        }
        fixture.model.roundResponses += listOf(named(fixture.completionCall("finish", completion(), started.active.scope), "Unrequested rename"))
        val result = fixture.coordinator.coordinateTask("Read device facts", "", started.active, "", null,
            started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals("设备状态查询", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
        assertTrue(fixture.model.recordedTools.first().all {
            !it.parameters.getValue(ConversationTitleProtocol.ARGUMENT).required
        })
        assertTrue(fixture.model.recordedTools.last().all {
            ConversationTitleProtocol.ARGUMENT in it.parameters
        })
        assertEquals(2, fixture.model.recordedRequests.size)
    }

    private fun named(call: LLMStreamEvent.ToolCallChunk, title: String) = call.copy(arguments =
        kotlinx.serialization.json.JsonObject(Json.parseToJsonElement(call.arguments).let {
            it as kotlinx.serialization.json.JsonObject
        } + (ConversationTitleProtocol.ARGUMENT to kotlinx.serialization.json.JsonPrimitive(title))).toString())

    private fun block(title: String?) = title?.let {
        "<conversation_title>{\"title\":${kotlinx.serialization.json.JsonPrimitive(it)}}</conversation_title>\n"
    }.orEmpty()

    private fun request(id: String, text: String) = TurnRequest(id, MessageSource.DIRECT, text, emptyList(), sha256Hex(text))

    private fun completion() = """{"status":"need_more_info","deliverables":[],"results":[{"text":"Need user input","evidence_refs":[]}],"unresolved_requirements":["Confirm the next step"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}"""
}
