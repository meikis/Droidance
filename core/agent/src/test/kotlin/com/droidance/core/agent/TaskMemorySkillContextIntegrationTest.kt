// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.MemoryContextItemPayload
import com.droidance.core.agent.event.MemoryContextLoadedEventPayload
import com.droidance.core.agent.event.MemoryContextScope
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs production request assembly over durable stores without Android preparation. */
class TaskMemorySkillContextIntegrationTest {
    @Test
    fun firstAndFollowingRoundsKeepSkillDirectivesAndOneCommittedMemoryIndex() = runBlocking {
        checkContextLifecycle(withClosedHistory = false)
    }

    @Test
    fun alreadyProjectedMemoryIsNotDuplicatedOnEntryOrFollowingRounds() = runBlocking {
        checkContextLifecycle(withClosedHistory = true)
    }

    @Test
    fun methodRecoveryKeepsSkillAndEnvironmentDirectivesWithOneMemoryIndex() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Inspect stored context using a relevant method."
        val started = fixture.startTurn("memory-skill-recovery", goal,
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        fixture.eventStore.recordMemoryContextLoaded(started.active.scope, MemoryContextLoadedEventPayload(
            contractVersion = 1, queryHash = "b".repeat(64),
            items = listOf(MemoryContextItemPayload("profile:response-language", MemoryContextScope.CORE_PROFILE,
                "Respond in Simplified Chinese."))
        ))
        val turnContext = "<available_skills>stored-context</available_skills>\nruntime_environment=local_knowledge"
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "workspace") com.droidance.core.agent.tools.ToolProtocolResult.notExecuted(
                "WORKSPACE_REVIEW_REQUIRED", retryable = true) else null
        }
        fixture.model.roundResponses += listOf(
            LLMStreamEvent.ToolCallChunk(0, "stale-record", "workspace",
                """{"op":"read","path":"notes.md"}"""),
            LLMStreamEvent.ToolCallChunk(1, "skipped-read", "session_read", "{}")
        )
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "find-original", "session_search", "{}"))

        fixture.coordinator.coordinateTask(
taskGoal = goal,
additionalContext = "",
durableTurn = started.active,
turnContext = turnContext,
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val waitIfPaused: suspend () -> Unit = { endScript(fixture) }
}
)

        assertEquals(3, fixture.model.recordedRequests.size)
        val recoveryRequest = fixture.model.recordedRequests[1]
        assertTrue(recoveryRequest.any { it.content.contains("recovery_feedback") })
        assertTrue(recoveryRequest.joinToString("\n"), recoveryRequest.any { it.role == Role.USER &&
            it.content.contains("settled_tool_result") && it.content.contains("PRIOR_CALL_FAILED") })
        val declaredCallIds = mutableSetOf<String>()
        recoveryRequest.forEach { message ->
            if (message.role == Role.ASSISTANT) declaredCallIds += message.toolCalls.orEmpty().map { it.id }
            if (message.role == Role.TOOL) assertTrue(message.toolCallId in declaredCallIds)
        }
        assertEquals(1, recoveryRequest.count { it.role == Role.SYSTEM && it.content == turnContext })
        assertEquals(1, recoveryRequest.count { it.role == Role.SYSTEM && it.content.contains("<memory_index>") })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }

    @Test
    fun requestLocalMemoryIsIncludedInTheContextCapacityEstimate() = runBlocking {
        suspend fun estimate(withMemory: Boolean): Int {
            val fixture = TaskCoordinatorTestFixture()
            val goal = "Inspect the stored context."
            val started = fixture.startTurn("memory-budget", goal, profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
            if (withMemory) fixture.eventStore.recordMemoryContextLoaded(started.active.scope,
                MemoryContextLoadedEventPayload(contractVersion = 1, queryHash = "c".repeat(64),
                    items = (1..48).map { index -> MemoryContextItemPayload("profile:preference-$index",
                        MemoryContextScope.CORE_PROFILE,
                        "A stored communication preference with details to preserve during this task. ".repeat(3)) }))
            fixture.recordClosedHistory(started.active.scope, "previous-round", "An earlier lookup completed.")
            fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Stored context inspected."))
            fixture.coordinator.coordinateTask(
taskGoal = goal,
additionalContext = "",
durableTurn = started.active,
turnContext = "runtime_environment=local_knowledge",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val waitIfPaused: suspend () -> Unit = { endScript(fixture) }
}
)
            val request = fixture.model.recordedRequests.single()
            assertEquals(if (withMemory) 1 else 0,
                request.count { it.role == Role.SYSTEM && it.content.contains("<memory_index>") })
            return Regex("estimated_input_tokens=(\\d+)").find(request.joinToString("\n") { it.content })!!
                .groupValues[1].toInt()
        }

        assertTrue("The actual memory index must consume the planned context budget",
            estimate(withMemory = true) > estimate(withMemory = false) + 500)
    }

    private fun endScript(fixture: TaskCoordinatorTestFixture) {
        if (fixture.model.roundResponses.isEmpty()) {
            fixture.model.nextStreamFailure = IllegalStateException("Fixture script ended")
        }
    }

    private suspend fun checkContextLifecycle(withClosedHistory: Boolean) {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Inspect stored context using a relevant method."
        val started = fixture.startTurn("memory-skill-$withClosedHistory", goal,
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        val memorySummary = "Respond in Simplified Chinese."
        fixture.eventStore.recordMemoryContextLoaded(started.active.scope, MemoryContextLoadedEventPayload(
            contractVersion = 1,
            queryHash = "a".repeat(64),
            items = listOf(MemoryContextItemPayload("profile:response-language", MemoryContextScope.CORE_PROFILE,
                memorySummary))
        ))
        if (withClosedHistory) {
            fixture.recordClosedHistory(started.active.scope, "previous-round", "An earlier local lookup completed.")
        }
        val turnContext = """
            Read only relevant Skill instructions before using their method.
            <available_skills>
            - name=stored-context; version=1: Inspect stored context using current sources.
            </available_skills>
            runtime_environment=local_knowledge
        """.trimIndent()
        listOf("session_search", "recall_skill", "session_read").forEachIndexed { index, name ->
            fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "read-$index", name, "{}"))
        }

        fixture.coordinator.coordinateTask(
taskGoal = goal,
additionalContext = "",
durableTurn = started.active,
turnContext = turnContext,
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val waitIfPaused: suspend () -> Unit = { endScript(fixture) }
}
)

        assertEquals(4, fixture.model.recordedRequests.size)
        fixture.model.recordedRequests.forEachIndexed { index, request ->
            val text = request.joinToString("\n") { it.content }
            assertEquals("round $index duplicated or lost memory", 1, Regex("<memory_index>").findAll(text).count())
            assertEquals("round $index duplicated or lost Skills", 1, Regex("<available_skills>").findAll(text).count())
            assertEquals(1, request.count { it.role == Role.SYSTEM && it.content == turnContext })
            assertTrue(text.contains(memorySummary))
            val memoryMessage = request.single { it.role == Role.SYSTEM && it.content.contains("<memory_index>") }
            assertFalse(memoryMessage.content.contains("<available_skills>"))
            assertFalse(memoryMessage.content.contains("runtime_environment="))
        }
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }
}
