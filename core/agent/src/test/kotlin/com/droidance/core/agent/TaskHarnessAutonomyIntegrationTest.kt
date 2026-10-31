// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Exercises production coordination with fake model/device ports, without a device connection. */
class TaskHarnessAutonomyIntegrationTest {
    @Test fun sameRoundQueryDeferralPreservesTheExecutedReadAndTheUndeliveredActionReceipt() = runBlocking {
        val fixture = inventoryScopeFixture()
        val goal = "Inspect the original lookup and action receipts"
        val started = fixture.startTurn("same-round-query-facts", goal)
        fixture.model.roundResponses += listOf(
            call("wire-query", "android_read", """{"op":"apps","query":"Unmatched label"}"""),
            LLMStreamEvent.ToolCallChunk(1, "wire-action", "android_act",
                """{"op":"open","app_name":"Unmatched label"}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
        val result = run(fixture, started, goal)
        assertEquals(fixture.diagnostics.joinToString("\n"), TurnTerminal.FAILED, result.terminal)
        val executed = fixture.toolExecutor.localCalls + fixture.toolExecutor.deviceCalls
        assertEquals(1, executed.count { it.name == "android_read" })
        assertTrue(executed.none { it.name == "android_act" })

        val events = fixture.events(started)
        val queryCallId = requireNotNull(events.single {
            it.eventKind == AgentEventKind.TOOL_CALL &&
                Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == "wire-query"
        }.callId)
        val actionCallId = requireNotNull(events.single {
            it.eventKind == AgentEventKind.TOOL_CALL &&
                Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == "wire-action"
        }.callId)
        assertNotEquals("wire-query", queryCallId)
        assertNotEquals("wire-action", actionCallId)
        assertTrue(events.any { it.eventKind == AgentEventKind.TOOL_STARTED && it.callId == queryCallId })
        assertTrue(events.none { it.eventKind == AgentEventKind.TOOL_STARTED && it.callId == actionCallId })
        val queryResult = Json.decodeFromString<ToolFinishedEventPayload>(events.single {
            it.eventKind == AgentEventKind.TOOL_FINISHED && it.callId == queryCallId
        }.payloadJson).result
        assertEquals(ToolExecutionStatus.SUCCEEDED, queryResult.executionStatus)
        val actionResult = Json.decodeFromString<ToolCallRejectedEventPayload>(events.single {
            it.eventKind == AgentEventKind.TOOL_CALL_REJECTED && it.callId == actionCallId
        }.payloadJson).result
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, actionResult.executionStatus)
        assertEquals("QUERY_RESULT_REQUIRED", actionResult.diagnosticCode)
        val details = Json.parseToJsonElement(actionResult.outputText).jsonObject.getValue("details").jsonObject
        assertEquals("planning", details.getValue("stage").jsonPrimitive.content)
        assertEquals("before_dispatch", details.getValue("dispatch_stage").jsonPrimitive.content)
        assertFalse(details.getValue("operation_invoked").jsonPrimitive.boolean)
        assertEquals(JsonArray(listOf(JsonPrimitive("wire-query"))), details.getValue("prerequisite_provider_call_ids"))

        val nextRequest = fixture.model.recordedRequests.last()
        val visibleQuery = nextRequest.single { it.role == Role.TOOL && it.toolCallId == "wire-query" }
        val visibleAction = nextRequest.single { it.role == Role.TOOL && it.toolCallId == "wire-action" }
        assertTrue(visibleQuery.content.contains(queryResult.asReceiptText()))
        assertEquals(actionResult.asReceiptText(), visibleAction.content)
    }

    @Test fun explicitInventoryQueryAndFullReadStaySeparateFromHistoricalRouteTargets() = runBlocking {
        val fixture = inventoryScopeFixture()
        val goal = "Inspect application facts for an unrelated user-supplied label"
        val started = fixture.startTurn("inventory-context-scope", goal)
        fixture.model.roundResponses += listOf(call("open", "android_act",
            """{"op":"open","package_name":"example.previous","app_name":"Previous reader"}"""))
        fixture.model.roundResponses += listOf(call("search", "android_read",
            """{"op":"apps","query":"Unmatched label"}"""))
        fixture.model.roundResponses += listOf(call("all", "android_read", """{"op":"apps"}"""))
        fixture.model.roundResponses += listOf(call("next", "android_act",
            """{"op":"open","package_name":"example.next"}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
        val result = run(fixture, started, goal)
        assertEquals(fixture.diagnostics.joinToString("\n"), TurnTerminal.FAILED, result.terminal)

        val requests = fixture.toolExecutor.capabilityContextRequests
        assertEquals(fixture.diagnostics.joinToString("\n"), 5, requests.size)
        assertNull(requests[0].packageName)
        assertNull(requests[0].appName)
        assertEquals("", requests[0].inventoryQuery)
        assertEquals("example.previous", requests[1].inventoryPackageName)
        assertEquals("Previous reader", requests[1].inventoryQuery)
        assertEquals("example.previous", requests[2].packageName)
        assertEquals("Previous reader", requests[2].appName)
        assertEquals("", requests[2].inventoryPackageName)
        assertEquals("Unmatched label", requests[2].inventoryQuery)
        assertEquals("example.previous", requests[3].packageName)
        assertEquals("Previous reader", requests[3].appName)
        assertEquals("", requests[3].inventoryPackageName)
        assertEquals("", requests[3].inventoryQuery)
        assertEquals("example.next", requests[4].packageName)
        assertEquals("example.next", requests[4].inventoryPackageName)
        assertEquals("", requests[4].inventoryQuery)
    }

    @Test fun explicitPackageAndLabelSearchProjectTheirOwnInventoryScopeIntoDiagnostics() = runBlocking {
        val fixture = inventoryScopeFixture()
        val goal = "Inspect an exact application query"
        val started = fixture.startTurn("inventory-context-intersection", goal)
        fixture.model.roundResponses += listOf(call("open", "android_act",
            """{"op":"open","package_name":"example.previous"}"""))
        fixture.model.roundResponses += listOf(call("search", "android_read",
            """{"op":"apps","package_name":"example.queried","query":"Reader"}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
        val result = run(fixture, started, goal)
        assertEquals(fixture.diagnostics.joinToString("\n"), TurnTerminal.FAILED, result.terminal)

        val request = fixture.toolExecutor.capabilityContextRequests.last()
        assertEquals("example.previous", request.packageName)
        assertNull(request.appName)
        assertEquals("example.queried", request.inventoryPackageName)
        assertEquals("Reader", request.inventoryQuery)
        val metadata = Json.parseToJsonElement(fixture.events(started).last {
            it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED
        }.payloadJson).jsonObject.getValue("finishMetadata").jsonObject
        assertEquals("example.previous", metadata.getValue("capability_context_target").jsonPrimitive.content)
        assertEquals("example.queried", metadata.getValue("inventory_context_package").jsonPrimitive.content)
        assertEquals("Reader", metadata.getValue("inventory_context_query").jsonPrimitive.content)
    }

    @Test fun failedOptionalRecordsDoNotSkipAnIndependentNativeActionInTheSameResponse() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("records-and-launch", "Read the requested values in another application")
        fixture.toolExecutor.resultOverride = { tool, _ ->
            if (tool.name == "workspace") com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("WORKSPACE_VERSION_CHANGED", true)
            else null
        }
        fixture.model.roundResponses += listOf(
            call("note", "workspace", """{"op":"records","path":"records.json","records":[]}"""),
            LLMStreamEvent.ToolCallChunk(1, "launch", "android_act", """{"op":"launch","package_name":"example.target"}"""))
        run(fixture, started, "Read the requested values in another application")
        assertTrue(fixture.toolExecutor.localCalls.any { it.name == "workspace" })
        assertTrue(fixture.diagnostics.joinToString("\n"), fixture.toolExecutor.deviceCalls.any { it.name == "android_act" })
        assertTrue(fixture.events(started).any { it.payloadJson.contains("WORKSPACE_VERSION_CHANGED") })
    }
    @Test fun authorizedNativeActionRunsBeforeAnOptionalMemo() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("native-without-memo", "Open the requested application")
        fixture.model.roundResponses += listOf(call("launch", "android_act",
            """{"op":"launch","package_name":"example.target"}"""))
        run(fixture, started, "Open the requested application")

        assertTrue(fixture.diagnostics.joinToString("\n"),
            fixture.toolExecutor.deviceCalls.any { it.name == "android_act" })
        assertTrue(fixture.toolExecutor.localCalls.none { it.name == "task_memo" })
        assertFalse(fixture.events(started).any { it.payloadJson.contains("TASK_REQUIREMENTS_REQUIRED") })
        assertTrue(fixture.events(started).any { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
    }

    @Test fun localMutationNeedsNoMemoOrAndroidPreparation() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("local-without-memo", "Remember the supplied preference",
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        fixture.model.roundResponses += listOf(call("remember", "update_memory", """{"fact":"supplied preference"}"""))
        run(fixture, started, "Remember the supplied preference")

        assertTrue(fixture.toolExecutor.localCalls.any { it.name == "update_memory" })
        assertTrue(fixture.toolExecutor.localCalls.none { it.name == "task_memo" })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertFalse(fixture.events(started).any { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
    }

    @Test fun sourceLinkedFindingsReachTheNextOrdinaryDecisionWithoutAFileWrite() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("retained-findings", "Read the requested values across views")
        val finding = "The second editor shows a caret; its value is still empty."
        fixture.evidenceLedger.recordObservation(observationId = "source-a", toolName = "read",
            rawText = "Original editor state", sourceKind = EvidenceSourceKind.OBSERVED_STATE)
        fixture.model.roundResponses += listOf(call("next", "screen_act",
            """{"actions":[{"op":"click","x":500,"y":500}],"screen_evidence":[{"record_id":"editor-2","fields":{"value":{"value":"$finding","source_ref":"source-a"}},"uncertainties":["value unread"]}]}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Further original evidence is needed."))
        run(fixture, started, "Read the requested values across views", frame = frame)

        assertTrue(fixture.model.recordedRequests.size >= 2)
        fixture.model.recordedRequests.forEach { request ->
            assertEquals("Only the final capacity fact belongs to this request", 1,
                request.sumOf { "<context_capacity".toRegex().findAll(it.content).count() })
        }
        val nextContext = fixture.model.recordedRequests[1].joinToString("\n") { it.content }
        assertTrue(nextContext, nextContext.contains("model_authored_objects"))
        assertTrue(nextContext.contains(finding))
        assertTrue(nextContext.contains("source-a"))
        assertTrue(nextContext.contains("editor-2"))
        assertFalse(nextContext.contains("<task_working_memory>"))
        assertFalse(nextContext.contains("<task_research_note>"))
        assertTrue(nextContext.contains("model_authored_object_index"))
        assertTrue(nextContext.contains("[model_record_receipt]"))
        assertTrue(nextContext.contains("\"status\":\"saved\""))
        assertTrue(fixture.toolExecutor.localCalls.none { it.name == "workspace" || it.name == "task_memo" })
    }

    @Test fun fullCollectionReachesNextDecisionBeyondFormerCharacterLimits() = runBlocking {
        val count = 20
        val records = JsonArray((1..count).map { index -> buildJsonObject {
            put("record_id", "row-$index")
            put("fields", buildJsonObject { put("name", buildJsonObject {
                put("value", "Object $index " + "x".repeat(1500)); put("source_ref", "source-a")
            }) })
        } })
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("complete-context", "Read the collection")
        fixture.evidenceLedger.recordObservation(observationId = "source-a", toolName = "read",
            rawText = "Original collection", sourceKind = EvidenceSourceKind.OBSERVED_STATE)
        fixture.model.roundResponses += listOf(call("save", "screen_act",
            """{"actions":[{"op":"click","x":500,"y":500}],"screen_evidence":$records}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Further original evidence is needed."))
        run(fixture, started, "Read the collection", frame = frame)
        val contextText = fixture.model.recordedRequests[1].joinToString("\n") { it.content }
            .substringAfter("<task_continuation_context>\n").substringBefore("\n</task_continuation_context>")
        assertTrue(contextText.length > 16000)
        val context = Json.parseToJsonElement(contextText).jsonObject
        assertEquals(records, context.getValue("model_authored_objects"))
        val index = context.getValue("model_authored_object_index").jsonObject
        assertFalse(index.getValue("has_more").jsonPrimitive.boolean)
        assertEquals(count, Json.parseToJsonElement(index.getValue("text").jsonPrimitive.content).jsonArray.size)
        assertEquals(count, ModelObjectRecords.project(fixture.events(started), started.active.scope.threadId,
            started.active.scope.turnId).objects.size)
        assertTrue(fixture.toolExecutor.localCalls.none { it.name == "workspace" })
    }

    @Test fun optionalWorkingFileFailureIsReportedWithoutBlockingAChatReply() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(eventStore = failingWorkspaceStore { IOException("fixture storage unavailable") })
        val started = fixture.startTurn("storage-failure", "Explain the available options", activate = false)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("These options are available."))
        val result = run(fixture, started, "Explain the available options")

        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals("These options are available.", result.summary)
        assertTrue(fixture.model.recordedRequests.flatten().any {
            it.content.contains("WORKING_RECORD_INITIALIZATION_FAILED") && it.content.contains("IOException")
        })
        assertFalse(fixture.events(started).any { it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }

    @Test fun methodRecoveryRetainsIncrementalObjectsWithoutReplayingTheFailedProposal() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val goal = "Read the requested values across views"
        val started = fixture.startTurn("collection-recovery", goal)
        fixture.evidenceLedger.recordObservation(observationId = "source-a", toolName = "read",
            rawText = "Original entry", sourceKind = EvidenceSourceKind.OBSERVED_STATE)
        fixture.toolExecutor.resultOverride = { tool, _ ->
            when (tool.name) {
                "screen_act" -> com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("OBSERVATION_RECOVERY_REQUIRED", false)
                "android_read" -> fixture.toolExecutor.stageObservation(frame, "recovered", "Current viewport after rejection", 2, tool.id)
                else -> null
            }
        }
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Unsupported claim that all entries were collected."),
            call("failed-scroll", "screen_act", """{"actions":[{"op":"scroll","direction":"down"}],"screen_evidence":[{"record_id":"entry-1","fields":{"name":{"value":"Entry A","source_ref":"source-a","region":"first row"}},"uncertainties":["amount missing"]}]}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
        val result = run(fixture, started, goal, frame = frame)
        assertTrue("${result.terminal}: ${fixture.diagnostics.joinToString("\n")}", fixture.model.recordedRequests.size >= 2)
        val recovery = fixture.model.recordedRequests[1].joinToString("\n") { it.content }
        assertTrue(recovery, recovery.contains("recovery_feedback"))
        assertTrue(recovery.contains("model_authored_objects"))
        assertTrue(recovery.contains("Entry A"))
        assertTrue(recovery.contains("source-a"))
        assertTrue(recovery.contains("amount missing"))
        assertFalse(recovery.contains("Unsupported claim that all entries were collected."))
        assertTrue(fixture.toolExecutor.localCalls.none { it.name == "workspace" || it.name == "task_memo" })
    }

    @Test fun changedFrameRejectionContinuesInTheSameCacheableHistory() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val goal = "Open the requested entry"
        val started = fixture.startTurn("changed-frame", goal)
        fixture.toolExecutor.resultOverride = { tool, _ ->
            when (tool.name) {
                "screen_act" -> com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("PRE_ACTION_OBSERVATION_CHANGED", true)
                "android_read" -> fixture.toolExecutor.stageObservation(frame, "after-change", "Current page after the dismissal", 2, tool.id)
                else -> null
            }
        }
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Dismissing the notice first."),
            call("dismiss", "screen_act", """{"actions":[{"op":"click","x":500,"y":760},{"op":"click","x":300,"y":120}]}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
        run(fixture, started, goal, frame = frame)
        assertTrue(fixture.model.recordedRequests.size >= 2)
        val next = fixture.model.recordedRequests[1]
        val text = next.joinToString("\n") { it.content }
        assertTrue(text, text.contains("PRE_ACTION_OBSERVATION_CHANGED"))
        assertTrue("The earlier proposal stays in ordinary history", text.contains("Dismissing the notice first."))
        assertFalse(text.contains("recovery_feedback"))
        assertTrue(next.any { it.cacheablePrefix })
        val metadata = fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .map { Json.decodeFromString<com.droidance.core.agent.event.ModelRoundCompletedEventPayload>(it.payloadJson).finishMetadata }[1]
        assertEquals("execution_history", metadata["observation_replan_context"])
        assertEquals("false", metadata["source_context_isolated"])
    }

    @Test fun rejectedRecordProducesIndependentFeedbackWithoutBlockingOrRepeatingTheAction() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Retain the observed entry"
        val started = fixture.startTurn("rejected-record-receipt", goal)
        fixture.model.roundResponses += listOf(call("next", "screen_act",
            """{"actions":[{"op":"scroll","direction":"down"}],"screen_evidence":[{"record_id":"row-1","fields":{"value":{"value":"Unverified reading","source_ref":"invented"}}}]}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
        run(fixture, started, goal, frame = frame)
        val next = fixture.model.recordedRequests[1].joinToString("\n") { it.content }
        assertTrue(next, next.contains("[model_record_receipt]"))
        assertTrue(next.contains("\"status\":\"rejected\""))
        assertTrue(next.contains("RECORD_SOURCE_UNAVAILABLE"))
        assertFalse(next.contains("model_authored_objects"))
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "screen_act" })
        val receipt = fixture.events(started).filter { it.eventKind == AgentEventKind.TOOL_FINISHED }
            .map { Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result }
            .first { it.outputText.contains("[model_record_receipt]") }
        assertEquals(ToolExecutionStatus.SUCCEEDED, receipt.executionStatus)
        assertTrue(receipt.outputText.contains("RECORD_SOURCE_UNAVAILABLE"))
    }

    @Test fun plannerRejectionStillReportsThatTheIndependentNoteWasSaved() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("saved-note-rejected-action", "Retain the observed entry")
        fixture.evidenceLedger.recordObservation(observationId = "source-a", toolName = "read",
            rawText = "Original entry", sourceKind = EvidenceSourceKind.OBSERVED_STATE)
        fixture.toolExecutor.definitionOverride = { name -> if (name == "screen_act")
            com.droidance.core.model.llm.models.ToolDefinition(name, "Fixture required argument", mapOf(
                "required_argument" to com.droidance.core.model.llm.models.ToolParameter("string", "Required test value", true))) else null }
        fixture.toolExecutor.resultOverride = { tool, _ ->
            if (tool.name == "android_read") fixture.toolExecutor.stageObservation(frame, "recovered", "Current frame", 2, tool.id) else null
        }
        fixture.model.roundResponses += listOf(call("bad-action", "screen_act",
            """{"actions":[{"op":"scroll","direction":"down"}],"screen_evidence":[{"record_id":"row-1","fields":{"value":{"value":"Retained value","source_ref":"source-a"}}}]}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
        val result = run(fixture, started, "Retain the observed entry", frame = frame)
        assertTrue("${result.terminal}: ${fixture.diagnostics.joinToString("\n")}", fixture.model.recordedRequests.size >= 2)
        val next = fixture.model.recordedRequests[1].joinToString("\n") { it.content }
        assertTrue(next, next.contains("ARGUMENT_SCHEMA_MISMATCH"))
        assertTrue(next.contains("[model_record_receipt]"))
        assertTrue(next.contains("\"status\":\"saved\""))
        val rejected = fixture.events(started).filter { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }
            .map { Json.decodeFromString<ToolCallRejectedEventPayload>(it.payloadJson).result }
            .first { it.outputText.contains("[model_record_receipt]") }
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejected.executionStatus)
        assertTrue(rejected.outputText.contains("\"status\":\"saved\""))
        assertFalse(fixture.toolExecutor.deviceCalls.any { it.name == "screen_act" })
    }

    @Test fun oversizedNativeOutputCannotHideARejectedRecordAcknowledgement() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("large-native-record-receipt", "Read and retain the requested state")
        fixture.toolExecutor.resultOverride = { tool, _ -> if (tool.name == "android_read")
            com.droidance.core.agent.tools.ToolProtocolResult.success("large-native-payload".repeat(4000)) else null }
        fixture.model.roundResponses += listOf(call("native", "android_read",
            """{"op":"device","screen_evidence":[{"record_id":"row-1","fields":{"value":{"value":"Reading","source_ref":"invented"}}}]}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
        run(fixture, started, "Read and retain the requested state")
        val next = fixture.model.recordedRequests[1].joinToString("\n") { it.content }
        assertFalse(next.contains("large-native-payload".repeat(100)))
        assertTrue(next, next.contains("latest_model_record_receipts"))
        assertTrue(next.contains("\"status\":\"rejected\""))
        assertTrue(next.contains("RECORD_SOURCE_UNAVAILABLE"))
        assertTrue(next.contains("receipt_source_ref"))
        val receiptRef = fixture.events(started).single { it.eventKind == AgentEventKind.TOOL_FINISHED &&
            it.payloadJson.contains("large-native-payload") }.eventId
        assertTrue(next.contains(receiptRef))
    }

    @Test fun cancellationDuringWorkingFileInitializationStillPropagates() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(eventStore = failingWorkspaceStore { CancellationException("fixture cancelled") })
        val started = fixture.startTurn("storage-cancellation", "Say hello", activate = false)
        try {
            run(fixture, started, "Say hello")
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("fixture cancelled", expected.message)
        }
        assertTrue(fixture.model.recordedRequests.isEmpty())
    }

    @Test fun shortTaskTitleStaysSeparateFromDetailedPublicProgress() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("short-island-title", "Open the requested application")
        val progress = "I am opening the requested application and will inspect its current page."
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content(progress),
            call("launch", "android_act", """{"op":"launch","package_name":"example.target","action_title":"打开应用"}"""))
        val labels = mutableListOf<String>()
        fixture.coordinator.coordinateTask(
taskGoal = "Open the requested application",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onActionUpdate: (String) -> Unit = { labels += it }
}
)
        assertTrue(labels.toString(), labels.contains("打开应用"))
        assertFalse(labels.contains(progress))
        assertTrue("Detailed progress stays in the durable conversation",
            fixture.events(started).any { it.payloadJson.contains(progress) })
        assertFalse(fixture.toolExecutor.deviceCalls.single { it.name == "android_act" }.arguments.contains("action_title"))
    }

    private fun inventoryScopeFixture() = TaskCoordinatorTestFixture().also { fixture ->
        // Native apps receipts have no current-screen identity. The general fixture's
        // shared obs_read screen is deliberately unsuitable for distinct inventory queries.
        fixture.toolExecutor.resultOverride = { tool, _ ->
            if (tool.name != "android_read") null else {
                val args = Json.parseToJsonElement(tool.arguments).jsonObject
                com.droidance.core.agent.tools.ToolProtocolResult.success(buildJsonObject {
                    put("launcher_search", buildJsonObject {
                        put("scope", "launcher_label_or_package_search")
                        put("query", args["query"]?.jsonPrimitive?.content.orEmpty())
                        put("package_filter", args["package_name"]?.jsonPrimitive?.content.orEmpty())
                        put("match_count", 0)
                    })
                    put("apps", JsonArray(emptyList()))
                    put("next_offset", JsonNull)
                }.toString())
            }
        }
    }

    private val INVENTORY_SCOPE_TEST_END = """{"status":"failed","results":[{"text":"Context projection inspected in a scripted test."}],"unresolved_requirements":["No real device verification is performed by this test."]}"""

    private suspend fun run(fixture: TaskCoordinatorTestFixture, started: TaskCoordinatorTestFixture.StartedTurn,
                            goal: String, frame: ByteArray? = null): TurnResult =
        fixture.coordinator.coordinateTask(taskGoal = goal, additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = started.expectedBackend, executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode,
            initialScreenRequired = frame != null, initialScreenFrame = frame)

    private fun failingWorkspaceStore(failure: () -> Exception): AgentEventStore {
        val delegate = InMemoryAgentEventStore()
        return object : AgentEventStore by delegate {
            override suspend fun append(event: AgentEventDraft): AgentEventEnvelope {
                if (event.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN) throw failure()
                return delegate.append(event)
            }
            override suspend fun appendAtomically(events: List<AgentEventDraft>): List<AgentEventEnvelope> {
                if (events.any { it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN }) throw failure()
                return delegate.appendAtomically(events)
            }
        }
    }

    private fun call(id: String, name: String, args: String) = LLMStreamEvent.ToolCallChunk(0, id, name, args)
    private val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    @Test fun rejectedNoteAllowsContinuationAndCanBeRepairedWithoutReplayingItsScroll() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Retain the observed object"
        val started = fixture.startTurn("record-repair-loop", goal)
        fixture.evidenceLedger.recordObservation(observationId = "source-a", toolName = "read",
            rawText = "Original value", sourceKind = EvidenceSourceKind.OBSERVED_STATE)
        fixture.model.roundResponses += listOf(call("collect", "screen_act",
            """{"actions":[{"op":"scroll","direction":"down"}],"screen_evidence":[{"fields":{"value":{"value":"Original value","source_ref":"source-a"}}}]}"""))
        fixture.toolExecutor.beforeDeviceExecute = { tool ->
            if (tool.name == "screen_act") {
                val repairRef = ModelRecordRepairs.project(fixture.events(started), started.active.scope.threadId,
                    started.active.scope.turnId).pending.keys.single()
                fixture.model.roundResponses += listOf(call("continue", "android_act",
                    """{"op":"open","package_name":"example.target"}"""))
                fixture.model.roundResponses += listOf(call("repair", "workspace",
                    """{"op":"objects","record_repair_ref":"$repairRef","screen_evidence":[{"record_id":"row-1","fields":{"value":{"value":"Original value","source_ref":"source-a"}}}]}"""))
                fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))
            }
        }
        run(fixture, started, goal, frame = frame)
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "screen_act" })
        assertEquals(fixture.diagnostics.joinToString("\n"), 1, fixture.toolExecutor.localCalls.count { it.name == "workspace" })
        val events = fixture.events(started)
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "android_act" })
        assertFalse(events.any { it.payloadJson.contains("MODEL_RECORD_REPAIR_REQUIRED") })
        assertTrue(ModelRecordRepairs.project(events, started.active.scope.threadId, started.active.scope.turnId).pending.isEmpty())
        assertEquals(setOf("row-1"), ModelObjectRecords.project(events, started.active.scope.threadId, started.active.scope.turnId).objects.keys)
    }

    @Test fun unrepairedOptionalNoteDoesNotBlockFurtherDeviceWorkOrHonestCompletion() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Inspect the visible content and continue"
        val started = fixture.startTurn("optional-note-continuation", goal)
        fixture.model.roundResponses += listOf(call("collect", "screen_act",
            """{"actions":[{"op":"scroll","direction":"down"}],"screen_evidence":[{"record_id":"row-1","fields":{"name":{"value":"Reading","source_ref":"unavailable"}}}]}"""))
        fixture.model.roundResponses += listOf(call("continue", "android_act",
            """{"op":"open","package_name":"example.target"}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", INVENTORY_SCOPE_TEST_END))

        val result = run(fixture, started, goal, frame = frame)

        assertEquals(listOf("screen_act", "android_act"), fixture.toolExecutor.deviceCalls
            .filter { it.name != "android_read" }.map { it.name })
        val events = fixture.events(started)
        assertTrue(ModelRecordRepairs.project(events, started.active.scope.threadId, started.active.scope.turnId).pending.isNotEmpty())
        assertFalse(events.any { it.payloadJson.contains("MODEL_RECORD_REPAIR_REQUIRED") })
        assertEquals(TurnTerminal.FAILED, result.terminal)
        assertTrue(fixture.model.recordedRequests.drop(1).any { request ->
            request.any { it.content.contains("pending_model_record_repairs") }
        })
    }

}
