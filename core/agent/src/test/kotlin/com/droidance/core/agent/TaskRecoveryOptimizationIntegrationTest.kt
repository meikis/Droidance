// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskRecoveryOptimizationIntegrationTest {
    @Test fun identicalReadResultsProduceFeedbackWithoutBlockingTheTask() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("read-repeat", "Check stored information", profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        repeat(4) { index -> fixture.model.roundResponses.add(listOf(
            LLMStreamEvent.ToolCallChunk(0, "lookup-$index", "session_search", "{}"))) }
        fixture.model.roundResponses.add(listOf(fixture.completionCall("finish",
            completion("No new information is available."))))
        val result = fixture.coordinator.coordinateTask("Check stored information", "", started.active, "",
            started.expectedBackend, started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(4, fixture.toolExecutor.localCalls.count { it.name == "session_search" })
        assertTrue(fixture.model.recordedRequests.last().any { it.role == Role.SYSTEM &&
            it.content.contains("same_arguments_and_result_count") })
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals(5, fixture.model.recordedRequests.size)
    }

    @Test fun emptyReplyGetsOneTargetedRecoveryWithoutAnyDeviceAction() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("empty-recovery", "Explain the stored fact", profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        fixture.model.roundResponses.add(emptyList())
        fixture.model.roundResponses.add(listOf(LLMStreamEvent.Content("The fact is available.")))
        val result = fixture.coordinator.coordinateTask("Explain the stored fact", "", started.active, "",
            started.expectedBackend, started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(2, fixture.model.recordedRequests.size)
        assertTrue(fixture.model.recordedRequests[1].any { it.role == Role.SYSTEM && it.content.contains("non-empty final answer") })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }

    @Test fun repeatedEmptyRepliesStopAfterTheSingleNudge() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("empty-stop", "Explain", profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        repeat(3) { fixture.model.roundResponses.add(emptyList()) }
        val result = fixture.coordinator.coordinateTask("Explain", "", started.active, "", started.expectedBackend,
            started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(2, fixture.model.recordedRequests.size)
        assertTrue(fixture.toolExecutor.localCalls.isEmpty())
    }

    @Test fun userSteerRefreshesOneMemoryIndexWithoutDroppingTheOriginalGoal() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Compare options without changing any settings."
        val started = fixture.startTurn("memory-refresh", goal, profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        fun memory(text: String) = MemoryContextLoadedEventPayload(1, "a".repeat(64),
            listOf(MemoryContextItemPayload("profile:preference", MemoryContextScope.CORE_PROFILE, text)))
        fixture.eventStore.recordMemoryContextLoaded(started.active.scope, memory("Original preference"))
        fixture.model.roundResponses.add(listOf(LLMStreamEvent.ToolCallChunk(0, "lookup", "session_search", "{}")))
        fixture.model.roundResponses.add(listOf(fixture.completionCall("finish",
            completion("Compared using the updated preference."))))
        var updated = false
        var query = ""
        val result = fixture.coordinator.coordinateTask(
goal,
"",
started.active,
"",
started.expectedBackend,
started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val takeManualResumeInstruction: () -> String? = {
                if (!updated && fixture.toolExecutor.localCalls.isNotEmpty()) {
                    updated = true
                    runBlocking { fixture.eventStore.recordUserSteer(started.active.scope, "new-preference", "Use my saved preference.") }
                    "Continue with the user update."
                } else null
            }
    override val refreshMemoryContext: suspend (String, String?) -> com.droidance.core.agent.event.MemoryContextLoadedEventPayload? = { text, _ -> query = text; memory("Updated preference") }
}
)
        fixture.complete(started, result)
        assertTrue(query.contains(goal) && query.contains("Use my saved preference."))
        val request = fixture.model.recordedRequests.last()
        assertEquals(2, request.count { it.role == Role.SYSTEM && it.content.contains("<memory_index>") })
        assertTrue(request.any { it.content.contains("Updated preference") })
        assertTrue(request.last { it.role == Role.SYSTEM && it.content.contains("<memory_index>") }.content.contains("Updated preference"))
        val first = fixture.model.recordedRequests.first()
        val immutablePrefix = first.takeWhile { !it.content.contains("<active_task>") }
        assertEquals(immutablePrefix, request.take(immutablePrefix.size))
        assertEquals(1, request.count { it.content.contains("<active_task>") })
        assertTrue(request.last { it.content.contains("<active_task>") }.content.contains("Use my saved preference."))
        assertEquals(2, fixture.eventStore.eventsForTurn(started.active.scope.turnId).count { it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals(2, fixture.model.recordedRequests.size)
    }

    @Test fun observedForegroundPackageAddsUnseenMethodsOnceWithoutRequeryingTheSameApp() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Find the item in the shop."
        val started = fixture.startTurn("package-refresh", goal, profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        fun methods(vararg refs: String) = MemoryContextLoadedEventPayload(1, "a".repeat(64),
            refs.map { MemoryContextItemPayload(it, MemoryContextScope.EXPERIENCE, "Historical method; $it") })
        fixture.eventStore.recordMemoryContextLoaded(started.active.scope, methods("experience:generic-search:1"))
        fixture.evidenceLedger.recordObservation(observationId = "shop-home", toolName = "read",
            rawText = "Shop home", sourceKind = EvidenceSourceKind.OBSERVED_STATE, foregroundPackage = "com.example.shop")
        repeat(2) { index -> fixture.model.roundResponses.add(listOf(
            LLMStreamEvent.ToolCallChunk(0, "lookup-$index", "session_search", "{}"))) }
        fixture.model.roundResponses.add(listOf(fixture.completionCall("finish", completion("Found."))))
        val packages = mutableListOf<String?>()
        val result = fixture.coordinator.coordinateTask(goal, "", started.active, "", started.expectedBackend,
            started.executionStrategy, toolProfile = started.profile, mode = started.mode,
            host = object : com.droidance.core.agent.TurnHost {
                override val refreshMemoryContext: suspend (String, String?) -> MemoryContextLoadedEventPayload? = { query, pkg ->
                    assertTrue(query.contains(goal))
                    packages += pkg
                    methods("experience:shop-search:2", "experience:generic-search:1")
                }
            })
        fixture.complete(started, result)
        assertEquals(listOf<String?>("com.example.shop"), packages)
        val loaded = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
            .filter { it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED }
        assertEquals(2, loaded.size)
        assertTrue(loaded.last().payloadJson.contains("experience:shop-search:2"))
        assertTrue(fixture.model.recordedRequests.last().last { it.role == Role.SYSTEM && it.content.contains("<memory_index>") }
            .content.contains("experience:shop-search:2"))
    }

    @Test fun foregroundPackageWithoutNewMethodsKeepsTheExistingMemoryContext() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("package-no-refresh", "Read the page", profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        val payload = MemoryContextLoadedEventPayload(1, "a".repeat(64),
            listOf(MemoryContextItemPayload("experience:read-page:1", MemoryContextScope.EXPERIENCE, "Historical method")))
        fixture.eventStore.recordMemoryContextLoaded(started.active.scope, payload)
        fixture.evidenceLedger.recordObservation(observationId = "page", toolName = "read",
            rawText = "Page", sourceKind = EvidenceSourceKind.OBSERVED_STATE, foregroundPackage = "com.example.reader")
        fixture.model.roundResponses.add(listOf(fixture.completionCall("finish", completion("Read."))))
        val result = fixture.coordinator.coordinateTask("Read the page", "", started.active, "", started.expectedBackend,
            started.executionStrategy, toolProfile = started.profile, mode = started.mode,
            host = object : com.droidance.core.agent.TurnHost {
                override val refreshMemoryContext: suspend (String, String?) -> MemoryContextLoadedEventPayload? = { _, _ -> payload }
            })
        fixture.complete(started, result)
        assertEquals(1, fixture.eventStore.eventsForTurn(started.active.scope.turnId)
            .count { it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED })
    }

    private fun completion(text: String) =
        """{"status":"done","deliverables":[],"results":[{"text":"$text","evidence_refs":[]}],"unresolved_requirements":[],"requirement_checks":[]}"""
}
