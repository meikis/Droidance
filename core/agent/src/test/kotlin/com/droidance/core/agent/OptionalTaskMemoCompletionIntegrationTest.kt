// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Uses the production coordinator and completion protocol, with fake model and device ports. */
class OptionalTaskMemoCompletionIntegrationTest {
    @Test fun unexecutedDeviceCallSettlesBeforeDataCompletionWithoutInventingAnImage() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Read current device facts after an unavailable launch"
        val started = fixture.startTurn("not-executed-settlement", goal)
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "android_act") com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("TARGET_UNAVAILABLE", false) else null
        }
        fixture.model.roundResponses += listOf(call("unavailable", "android_act", """{"op":"open","package_name":"fixture.unavailable"}"""))
        fixture.model.roundResponses += listOf(call("native", "android_read", """{"op":"device"}"""))
        fixture.model.roundResponses += listOf(call("finish", "done", completion("done", "The native facts were read.", "obs_read")))
        val result = run(fixture, started, goal)
        assertEquals(fixture.diagnostics.toString(), TurnTerminal.DONE, result.terminal)
        val events = fixture.events(started)
        assertFalse(events.any { it.payloadJson.contains("COMPLETION_EVIDENCE_REQUIRED") })
        val callId = events.single { it.eventKind == AgentEventKind.TOOL_CALL &&
            Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == "unavailable" }.callId
        val receipt = events.single { it.callId == callId && it.eventKind == AgentEventKind.TOOL_FINISHED }
        val settled = Json.decodeFromString<ToolFinishedEventPayload>(receipt.payloadJson).result
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, settled.executionStatus)
        assertEquals(2L, settled.metrics["screenshot_attachments_planned"])
        assertEquals(0L, settled.metrics["screenshot_attachments_expected"])
        assertEquals(0L, settled.metrics["screenshot_attachments_missing"])
        assertEveryCallSettled(events)
    }

    @Test fun initialLaunchWithoutSourcePixelsIsNotAnAttachmentPersistenceFailure() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Open the target and read its current state"
        val started = fixture.startTurn("launch-without-pixels", goal)
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "android_act") com.droidance.core.agent.tools.ToolProtocolResult.success("Launch delivered") else null
        }
        fixture.model.roundResponses += listOf(call("launch", "android_act", """{"op":"open","package_name":"fixture.target"}"""))
        fixture.model.roundResponses += listOf(call("observe", "android_read", """{"op":"screen"}"""))
        fixture.model.roundResponses += listOf(call("finish", "done", completion("done", "The target state was observed.", "obs_read")))
        val result = run(fixture, started, goal)
        assertEquals(fixture.diagnostics.toString(), TurnTerminal.DONE, result.terminal)
        val events = fixture.events(started)
        val launch = events.single { it.eventKind == AgentEventKind.TOOL_CALL &&
            Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == "launch" }
        val receipt = Json.decodeFromString<ToolFinishedEventPayload>(events.single {
            it.eventKind == AgentEventKind.TOOL_FINISHED && it.callId == launch.callId }.payloadJson).result
        assertEquals(ToolExecutionStatus.SUCCEEDED, receipt.executionStatus)
        assertEquals(2L, receipt.metrics["screenshot_attachments_planned"])
        assertEquals(0L, receipt.metrics["screenshot_attachments_expected"])
        assertEquals(0L, receipt.metrics["screenshot_attachments_missing"])
        assertTrue(events.any { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED })
        assertEveryCallSettled(events)
    }

    @Test fun minimalUncitedAnswerCompletesWithoutPerPageBookkeepingOrReview() = runBlocking {
        for (declared in listOf(false, true)) {
            val fixture = TaskCoordinatorTestFixture(reviewEnabled = false)
            val goal = "Read the current state"
            val started = fixture.startTurn("minimal-answer-$declared", goal)
            fixture.model.roundResponses += listOfNotNull(
                call("scope", "task_memo", memo()).takeIf { declared },
                call("read", "android_read", """{"op":"screen"}"""))
            fixture.model.roundResponses += listOf(call("answer", "done",
                """{"status":"done","results":[{"text":"The current state is login complete."}],"unresolved_requirements":[]}"""))

            val result = run(fixture, started, goal)
            fixture.complete(started, result)

            assertEquals(fixture.diagnostics.joinToString("\n"), TurnTerminal.DONE, result.terminal)
            assertEquals(2, fixture.model.recordedRequests.size)
            assertEquals(listOf("android_read"), fixture.toolExecutor.deviceCalls.map { it.name })
            assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
            assertTrue(fixture.toolExecutor.localCalls.none { it.name == "workspace" })
            assertTrue(result.summary.contains("login complete"))
            assertEveryCallSettled(fixture.events(started))
        }
    }

    @Test fun localAndDeviceOutcomesCanReachDoneOrCardWithoutMemoIncludingOptionalReview() = runBlocking {
        for (local in listOf(true, false)) {
            for (tool in listOf("done", "present_result_card")) {
                for (review in listOf(false, true)) {
                    val fixture = TaskCoordinatorTestFixture(reviewEnabled = review)
                    // The knowledge-only catalog has done, while result cards use the
                    // default catalog without preparing or executing a device attempt.
                    val profile = if (local && tool == "done") OperatorToolProfile.LOCAL_KNOWLEDGE else OperatorToolProfile.DEFAULT
                    val goal = if (local) "Remember the supplied preference" else "Read the current state"
                    val started = fixture.startTurn("optional-memo-$local-$tool-$review", goal, profile, activate = !local)
                    fixture.model.roundResponses += listOf(if (local)
                        call("remember", "update_memory", """{"fact":"supplied preference"}""")
                    else call("read", "android_read", """{"op":"screen"}"""))
                    val body = completion(tool, if (local) "The supplied preference was recorded." else "The current state is login complete.",
                        if (local) "" else "obs_read")
                    fixture.model.roundResponses += listOf(call("draft", tool, body))
                    if (review) {
                        fixture.model.roundResponses += listOf(call("confirm", tool, body))
                    }

                    val result = run(fixture, started, goal, localOnly = local)
                    fixture.complete(started, result)

                    val expectedTerminal = if (local && tool == "done") TurnTerminal.DIRECT_REPLY else TurnTerminal.DONE
                    assertEquals("local=$local/$tool/review=$review: ${fixture.diagnostics}", expectedTerminal, result.terminal)
                    if (tool == "done") {
                        assertTrue(result.taskDoneByModel)
                        assertEquals("done", result.taskDoneStatus)
                    }
                    assertTrue(result.summary.contains(if (local) "supplied preference" else "login complete"))
                    assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == tool })
                    assertTrue(fixture.toolExecutor.localCalls.none { it.name == "task_memo" })
                    assertEquals(if (review) 3 else 2, fixture.model.recordedRequests.size)
                    assertEquals(if (local) 0 else 1, fixture.toolExecutor.deviceCalls.size)
                    if (local) assertTrue(fixture.events(started).none { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
                    assertNull(TaskRequirementPlan.current(fixture.events(started), started.active.scope.threadId, started.active.scope.turnId))
                    assertFalse(fixture.events(started).any { it.payloadJson.contains("TASK_REQUIREMENTS_REQUIRED") })
                    if (tool == "present_result_card") assertNotNull(fixture.toolExecutor.currentResultCard())
                    val schema = fixture.model.recordedTools.first().single { it.name == tool }.parameters
                    assertFalse(schema.getValue("requirement_checks").required)
                    assertFalse(schema.getValue("deliverables").required)
                    assertEveryCallSettled(fixture.events(started))
                }
            }
        }
    }

    @Test fun emptyChecksDoNotBypassDeviceResultSourcesOrPublishARejectedCard() = runBlocking {
        for (tool in listOf("done", "present_result_card")) {
            val fixture = TaskCoordinatorTestFixture()
            val goal = "Read the current state"
            val started = fixture.startTurn("optional-memo-source-$tool", goal)
            fixture.model.roundResponses += listOf(call("read", "android_read", """{"op":"screen"}"""))
            fixture.model.roundResponses += listOf(call("unsupported", tool,
                completion(tool, "The current state is login complete.", "invented_source")))
            var rejectedCandidateWasPublished = false
            fixture.toolExecutor.beforeLocalExecute = { call ->
                if (call.name == tool) {
                    rejectedCandidateWasPublished = fixture.toolExecutor.currentResultCard() != null ||
                        fixture.toolExecutor.localCalls.count { it.name == tool } != 1
                }
            }
            fixture.model.roundResponses += listOf(call("supported", tool,
                completion(tool, "The current state is login complete.", "obs_read")))

            val result = run(fixture, started, goal)
            fixture.complete(started, result)

            assertEquals(fixture.diagnostics.joinToString("\n"), TurnTerminal.DONE, result.terminal)
            assertFalse(rejectedCandidateWasPublished)
            assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == tool })
            assertEquals(listOf("android_read"), fixture.toolExecutor.deviceCalls.map { it.name })
            assertTrue(fixture.events(started).any { it.payloadJson.contains("COMPLETION_RESULTS_REQUIRED") })
            assertTrue(fixture.events(started).any { it.payloadJson.contains("invented_source") })
            assertEveryCallSettled(fixture.events(started))
        }
    }

    @Test fun aDeclaredMemoDoesNotForceCompletionCoverage() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Read the state without disclosing identifiers"
        val started = fixture.startTurn("declared-scope-coverage", goal)
        fixture.model.roundResponses += listOf(call("scope", "task_memo", memo()),
            call("read", "android_read", """{"op":"screen"}"""))
        fixture.model.roundResponses += listOf(call("missing-coverage", "done",
            completion("done", "The current state was read.", "obs_read")))

        val result = run(fixture, started, goal)
        fixture.complete(started, result)

        assertEquals(TurnTerminal.DONE, result.terminal)
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        assertFalse(fixture.events(started).any { it.payloadJson.contains("TASK_REQUIREMENTS_UNCOVERED") })
        assertEquals(2, fixture.model.recordedRequests.size)
        val retained = TaskRequirementPlan.current(fixture.events(started), started.active.scope.threadId, started.active.scope.turnId)!!
        assertEquals(listOf("r1", "c1"), retained.requirementIds)
        assertEquals(listOf("omit identifiers"), retained.constraints!!.output_constraints)
        assertEveryCallSettled(fixture.events(started))
    }

    @Test fun userUpdatesDoNotRequireMemoRefreshForUnindexedDelivery() = runBlocking {
        for (declared in listOf(false, true)) {
            val fixture = TaskCoordinatorTestFixture()
            val goal = "Read the state without disclosing identifiers"
            val started = fixture.startTurn("optional-memo-updated-$declared", goal)
            fixture.model.roundResponses += listOfNotNull(
                call("scope", "task_memo", memo()).takeIf { declared },
                call("read", "android_read", """{"op":"screen"}"""))
            // Inject ordinary user messages while a turn-local read is running.
            // This does not enter manual takeover or demand a device screenshot.
            var updateRecorded = false
            fixture.toolExecutor.beforeLocalExecute = { call ->
                if (call.name == "workspace" && !updateRecorded) {
                    fixture.eventStore.recordUserSteer(started.active.scope, "update-1", "Keep the identifiers private.")
                    fixture.eventStore.recordUserSteer(started.active.scope, "update-2", "Report the same requested state.")
                    updateRecorded = true
                }
            }
            fixture.model.roundResponses += listOf(call("read-note", "workspace", """{"op":"read","path":"work.md"}"""))
            fixture.model.roundResponses += listOf(
                call("without-refresh", "done", completion("done", "The current state was read without disclosing identifiers.", "obs_read")))
            val result = run(fixture, started, goal)
            fixture.complete(started, result)

            val latestReceipts = fixture.events(started).filter {
                it.eventKind in setOf(AgentEventKind.TOOL_FINISHED, AgentEventKind.TOOL_CALL_REJECTED)
            }.takeLast(2).map { it.payloadJson }
            assertEquals("declared=$declared, reason=${result.failureReason}, stop=${result.stopDiagnostic}, receipts=$latestReceipts: ${fixture.diagnostics}", TurnTerminal.DONE, result.terminal)
            assertTrue(updateRecorded)
            assertEquals(if (declared) 1 else 0, fixture.toolExecutor.localCalls.count { it.name == "task_memo" })
            assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
            assertEquals(1, fixture.toolExecutor.deviceCalls.size)
            assertFalse(fixture.events(started).any { it.payloadJson.contains("TASK_REQUIREMENTS_REFRESH_REQUIRED") })
            assertFalse(fixture.events(started).any { it.payloadJson.contains("COMPLETION_WITH_ACTION") })
            assertEquals(declared, TaskRequirementPlan.pendingRefresh(fixture.events(started), started.active.scope.threadId, started.active.scope.turnId) != null)
            assertEveryCallSettled(fixture.events(started))
        }
    }

    private suspend fun run(fixture: TaskCoordinatorTestFixture, started: TaskCoordinatorTestFixture.StartedTurn,
                            goal: String, localOnly: Boolean = false): TurnResult =
        fixture.coordinator.coordinateTask(
taskGoal = goal,
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = if (localOnly) null else started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { check(!localOnly) { "Local completion must not prepare Android" }; null }
    override val onExecutionStarted: suspend (firstToolName: String) -> Unit = { check(!localOnly) { "Local completion must not start a device attempt" } }
}
)

    private fun completion(tool: String, text: String, source: String, ids: List<String> = emptyList()): String = buildJsonObject {
        if (tool == "done") put("status", "done") else put("title", "Requested result")
        putJsonArray("deliverables") { }
        putJsonArray(if (tool == "done") "results" else "items") {
            addJsonObject { put("text", text); put("evidence_refs", JsonArray(source.split(',').map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive))) }
        }
        putJsonArray("unresolved_requirements") { }
        putJsonArray("requirement_checks") {
            ids.forEach { id -> addJsonObject {
                put("requirement_id", id); put("status", "supported"); put("unresolved_index", -1)
                putJsonArray("result_indices") { add(0) }
            } }
        }
    }.toString()

    private fun memo() = """{"record_path":"work.md","state":"Retain the requested scope.","requirements":["read current state"],"output_constraints":["omit identifiers"],"requested_deliverables":[],"protected_values":[],"steps":[]}"""

    private fun call(id: String, name: String, arguments: String) =
        LLMStreamEvent.ToolCallChunk(index = id.hashCode().and(Int.MAX_VALUE), id = id, name = name, arguments = arguments)

    private fun assertEveryCallSettled(events: List<AgentEventEnvelope>) {
        events.filter { it.eventKind == AgentEventKind.TOOL_CALL }.forEach { declaration ->
            val lifecycle = events.filter { it.callId == declaration.callId }
            assertTrue("Call ${declaration.callId} did not settle exactly once",
                (lifecycle.count { it.eventKind == AgentEventKind.TOOL_STARTED } == 1 &&
                    lifecycle.count { it.eventKind == AgentEventKind.TOOL_FINISHED } == 1 &&
                    lifecycle.none { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }) ||
                    (lifecycle.none { it.eventKind == AgentEventKind.TOOL_STARTED || it.eventKind == AgentEventKind.TOOL_FINISHED } &&
                        lifecycle.count { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED } == 1))
        }
    }
}
