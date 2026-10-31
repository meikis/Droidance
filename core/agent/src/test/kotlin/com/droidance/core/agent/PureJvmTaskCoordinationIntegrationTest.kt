// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventDraft
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.ToolCallRejectedEventPayload
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolStartedEventPayload
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolLifecycleEventScope
import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.attempt.DeviceToolCapabilityRequirement
import com.droidance.core.agent.attempt.ExecutionAttemptBackendProbe
import com.droidance.core.agent.attempt.ExecutionAttemptBindingPreparer
import com.droidance.core.agent.attempt.ExecutionAttemptRoute
import com.droidance.core.agent.attempt.ExecutionBackendObservation
import com.droidance.core.agent.attempt.ExecutionRoundPlanner
import com.droidance.core.agent.harness.DurableModelRoundCoordinator
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.context.ContextCheckpointDecision
import com.droidance.core.agent.context.ContextCheckpointPlanInput
import com.droidance.core.agent.context.ContextCheckpointPlanner
import com.droidance.core.agent.context.ContextDynamicRequestSuffix
import com.droidance.core.agent.context.ContextPolicySnapshot
import com.droidance.core.agent.context.DurableContextProjector
import com.droidance.core.agent.context.StructuralContextTokenEstimator
import com.droidance.core.agent.config.HarnessPolicy
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.tools.OperatorToolProfile
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM behavior coverage for the migrated production TaskCoordinator loop. */
class PureJvmTaskCoordinationIntegrationTest {
    @Test fun mixedDeviceAndLocalCallsKeepTheirDeclaredLifecycleScopeInEitherOrder() = runBlocking {
        for (deviceFirst in listOf(true, false)) {
            val fixture = TaskCoordinatorTestFixture()
            val started = fixture.startTurn("mixed-scope-$deviceFirst", "Collect and retain visible findings")
            fixture.model.roundResponses += listOf(call("scope", "task_memo", memoPayload("Collect and retain visible findings")))
            val device = call("read", "android_read", """{"op":"screen"}""")
            val local = call("record", "workspace", """{"op":"append","path":"work.md","text":"Observed finding"}""")
            fixture.model.roundResponses += if (deviceFirst) listOf(device, local) else listOf(local, device)
            val partial = JsonObject(Json.parseToJsonElement(needMoreInfoPayload("More sources required")).jsonObject +
                ("results" to JsonArray(emptyList()))).toString()
            fixture.model.roundResponses += listOf(fixture.completionCall("finish", partial))

            val result = fixture.coordinator.coordinateTask(taskGoal = "Collect and retain visible findings", additionalContext = "",
                durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
                executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
            fixture.complete(started, result)

            assertEquals("$result; receipts=${fixture.events(started).filter { it.eventKind == AgentEventKind.TOOL_FINISHED || it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }.map { it.payloadJson }}",
                TurnTerminal.NEED_MORE_INFO, result.terminal)
            assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "android_read" })
            assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "workspace" })
            val events = fixture.events(started)
            val declarations = events.filter { it.eventKind == AgentEventKind.TOOL_CALL }.associateBy { it.callId }
            events.filter { it.eventKind == AgentEventKind.TOOL_FINISHED }.forEach { finished ->
                val declared = declarations.getValue(finished.callId)
                assertEquals("attempt scope for ${finished.callId}", declared.attemptId, finished.attemptId)
                assertEquals("execution scope for ${finished.callId}", declared.executionId, finished.executionId)
            }
            assertEveryDeclaredToolSettled(events)
        }
    }

    @Test fun expandedOutputBudgetSurvivesLocalToolSuccessAndResetsForTheNextTurn() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("output-limit", "Report the blocker", activate = false)
        fixture.model.nextStreamFailure = com.droidance.core.model.llm.OpenAIResponsesIncompleteException("max_output_tokens")
        fixture.model.roundResponses += listOf(call("scope", "task_memo", memoPayload("Report the blocker")))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", needMoreInfoPayload("Source unavailable")))

        val result = fixture.coordinator.coordinateTask(
taskGoal = "Report the blocker",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = null,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { error("Output-limit recovery must not prepare Android") }
}
)
        fixture.complete(started, result)

        assertEquals(TurnTerminal.NEED_MORE_INFO, result.terminal)
        assertEquals(3, fixture.model.recordedMaxOutputTokens.size)
        val initialBudget = requireNotNull(fixture.model.recordedMaxOutputTokens[0])
        val retryBudget = requireNotNull(fixture.model.recordedMaxOutputTokens[1])
        assertTrue("Output truncation must expand the retry budget", retryBudget > initialBudget)
        assertTrue(fixture.toolExecutor.localCalls.any { it.name == "task_memo" })
        assertEquals("The request after successful task_memo must retain the expanded budget",
            retryBudget, fixture.model.recordedMaxOutputTokens[2])
        assertEveryDeclaredToolSettled(fixture.events(started))

        val next = fixture.startTurn("after-output-limit", "Say hello", activate = false)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Hello."))
        val nextResult = fixture.coordinator.coordinateTask(
taskGoal = "Say hello",
additionalContext = "",
durableTurn = next.active,
turnContext = "",
expectedBackend = null,
executionStrategy = next.executionStrategy,
toolProfile = next.profile,
mode = next.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { error("A direct reply must not prepare Android") }
}
)
        fixture.complete(next, nextResult)
        assertEquals(TurnTerminal.DIRECT_REPLY, nextResult.terminal)
        assertEquals("Each new Turn must start with the configured initial budget",
            initialBudget, fixture.model.recordedMaxOutputTokens.last())
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }

    @Test fun localEffectExecutesDirectlyWithoutPriorScopeMemo() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("local-scope", "Record this fact", activate = false)
        val write = call("unscoped", "update_memory", "{\"fact\":\"local only\"}")
        fixture.model.roundResponses += listOf(write)
        val scope = memoPayload("Record this fact").substringBefore("\"steps\":") + "\"steps\":[]}"
        fixture.model.roundResponses += listOf(call("scope", "task_memo", scope),
            call("scoped", "update_memory", "{\"fact\":\"local only\"}"))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Unapproved parallel answer"),
            fixture.completionCall("finish", needMoreInfoPayload("Still requires confirmation")))
        val result = fixture.coordinator.coordinateTask(taskGoal = "Record this fact", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = null,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(TurnTerminal.NEED_MORE_INFO, result.terminal)
        assertEquals("Need user input", result.summary)
        assertEquals(2, fixture.toolExecutor.localCalls.count { it.name == "update_memory" })
        val rounds = fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
        assertTrue(rounds.none { Json.parseToJsonElement(it.payloadJson).jsonObject["publicBlocks"].toString().contains("Unapproved parallel answer") })
    }

    @Test fun stoppedReplyReceivesAuthoredFindingsInsteadOfRejectedCompletionDraft(): Unit = runBlocking {
        val fixture = TaskCoordinatorTestFixture(stoppedReplyEnabled = true)
        val started = fixture.startTurn("stopped-records", "Retain these findings", activate = false)
        val workspace = TaskWorkspace(fixture.eventStore, fixture.blobStore)
        val policy = WorkspacePolicy(8, 8192, 65536, 128)
        workspace.write(started.active.scope, "notes.md", "observations A and B", false, null, "notes", policy)
        workspace.write(started.active.scope, "findings.md", "findings A and B", false, null, "findings", policy)
        workspace.write(started.active.scope, ".completion/done.json", "rejected draft ".repeat(350), false,
            null, "draft", policy, runtimeOwned = true)
        fixture.model.roundResponses += listOf(call("scope", "task_memo", memoPayload("Retain these findings")
            .replace("execution/record.md", "notes.md")))
        repeat(3) { fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Missing structured completion.")) }
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("I retained partial findings and could not complete the task."))

        val result = fixture.coordinator.coordinateTask(taskGoal = "Retain these findings", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = null,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)

        val closingContext = Json.parseToJsonElement(fixture.model.recordedRequests.last().single().content).jsonObject
        val excerpt = closingContext.getValue("unverified_workspace_excerpt").jsonPrimitive.content
        assertTrue(excerpt, excerpt.contains("observations A and B"))
        assertTrue(excerpt, excerpt.contains("findings A and B"))
        assertFalse(excerpt.contains("rejected draft"))
        assertTrue(excerpt.length <= 1000 / 3)
        assertEquals(TurnFailureReason.MODEL_PROTOCOL_ERROR, result.failureReason)
        assertFalse(result.taskDoneByModel)
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertTrue(fixture.events(started).any { it.payloadJson.contains("stopped_reply_context") })
        assertTrue("Closing must use its configured low reasoning effort", fixture.events(started).any {
            it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED &&
                it.payloadJson.contains("stopped_task_reply") &&
                it.payloadJson.contains("\"requested_reasoning_effort\":\"low\"")
        })
    }

    @Test fun stoppedReplyCannotPublishRestrictedValuesOrUnapprovedLocalFiles() = runBlocking {
        for (closing in listOf("The identifier is login complete", "[Report](droidance://deliverable?turn=x&path=y&ref=z)")) {
            val fixture = TaskCoordinatorTestFixture(stoppedReplyEnabled = true)
            val started = fixture.startTurn("stopped-publication", "Read without disclosure")
            val baseline = memoPayload("read current state").substringBefore("\"steps\":") + "\"steps\":[]}"
            fixture.model.roundResponses += listOf(call("scope", "task_memo", baseline), call("read", "android_read", "{\"op\":\"screen\"}"))
            val protected = baseline.replace("\"protected_values\":[]", """"protected_values":[{"requirement_id":"r1","source_ref":"obs_read","value":"login complete","policy":"omit","visible_prefix":0,"visible_suffix":0}]""")
            fixture.model.roundResponses += listOf(call("protect", "task_memo", protected))
            repeat(3) { fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Missing structured completion.")) }
            fixture.model.roundResponses += listOf(LLMStreamEvent.Content(closing))
            val result = fixture.coordinator.coordinateTask(taskGoal = "Read without disclosure", additionalContext = "",
                durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
                executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
            fixture.complete(started, result)
            assertFalse(result.summary.contains(closing))
            assertTrue(fixture.events(started).any { it.payloadJson.contains("STOPPED_REPLY_INVALID") })
            assertTrue(fixture.model.recordedRequests.last().any { it.content.contains("retained_task_contract") })
        }
    }
    @Test fun registeredDisclosureRestrictionBlocksALeakingCandidateInTheProductionLoop() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("restricted-output", "Read the state and omit its identifier")
        val baseline = memoPayload("read current state").replace("\"read current state\"]", "\"read current state\",\"omit identifier\"]")
            .replace("\"output_constraints\":[]", "\"output_constraints\":[\"omit identifier\"]")
        fixture.model.roundResponses += listOf(call("scope", "task_memo", baseline), call("read", "android_read", "{\"op\":\"screen\"}"))
        val restricted = baseline.replace("\"protected_values\":[]", """"protected_values":[{"requirement_id":"r2","source_ref":"obs_read","value":"login complete","policy":"omit","visible_prefix":0,"visible_suffix":0}]""")
        fixture.model.roundResponses += listOf(call("protect", "task_memo", restricted))
        fun body(text: String) = donePayload(text, "obs_read").replace("\"requirement_checks\":[", """"requirement_checks":[{"requirement_id":"r2","status":"supported","evidence_refs":["obs_read"],"unresolved_index":-1,"result_indices":[]},{"requirement_id":"c1","status":"supported","evidence_refs":["obs_read"],"unresolved_index":-1,"result_indices":[]},""")
        fixture.model.roundResponses += listOf(fixture.completionCall("leak", body("login complete")))
        fixture.model.roundResponses += listOf(fixture.completionCall("fixed", body("Requested state")))
        val public = StringBuilder()
        val result = fixture.coordinator.coordinateTask(
taskGoal = "Read the state and omit its identifier",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onStreamContent: suspend (AssistantStreamChunk) -> Unit = { public.append(it.text) }
}
)
        fixture.complete(started, result)
        assertEquals(TurnTerminal.DONE, result.terminal)
        assertFalse(result.summary.contains("login complete"))
        assertFalse(public.contains("login complete"))
        assertTrue(fixture.events(started).any { it.payloadJson.contains("COMPLETION_OUTPUT_CONSTRAINT_VIOLATION") })
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
    }
    @Test fun declaredConstraintMustBeCoveredInBothDraftAndConfirmation() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(reviewEnabled = true)
        val started = fixture.startTurn("constraint-coverage", "Read state without disclosure")
        val memo = memoPayload("Read state without disclosure")
            .replace("\"output_constraints\":[]", "\"output_constraints\":[\"omit identifiers\"]")
        fixture.model.roundResponses += listOf(call("scope", "task_memo", memo), call("read", "android_read", "{\"op\":\"screen\"}"))
        val incomplete = donePayload("Requested state", "obs_read")
        fixture.model.roundResponses += listOf(fixture.completionCall("missing-constraint", incomplete))
        val body = incomplete.replace("\"requirement_checks\":[", """"requirement_checks":[{"requirement_id":"c1","status":"supported","evidence_refs":["obs_read"],"unresolved_index":-1,"result_indices":[]},""")
        fixture.model.roundResponses += listOf(fixture.completionCall("complete-draft", body))
        val candidate = CompletionReviewCandidate.from("done", kotlinx.serialization.json.JsonObject(Json.parseToJsonElement(body).jsonObject - "deliverables").toString())
        fixture.model.roundResponses += listOf(call("partial-confirmation", "done",
            body.toString()))
        fixture.model.roundResponses += listOf(call("full-confirmation", "done",
            body.toString()))
        val result = fixture.coordinator.coordinateTask(taskGoal = "Read state without disclosure", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(TurnTerminal.DONE, result.terminal)
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        assertTrue(fixture.events(started).any { it.payloadJson.contains("TASK_REQUIREMENTS_UNCOVERED") })
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test fun localWriteWithoutImagesCanBeConfirmedWithoutReapplyingIt() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(reviewEnabled = true)
        val started = fixture.startTurn("local-reviewed", "Record this fact", activate = false)
        val scope = memoPayload("Record this fact").substringBefore("\"steps\":") + "\"steps\":[]}"
        fixture.model.roundResponses += listOf(call("scope", "task_memo", scope), call("write", "update_memory", "{\"fact\":\"local only\"}"))
        val body = donePayload("Fact recorded", "")
        fixture.model.roundResponses += listOf(fixture.completionCall("draft", body))
        val candidate = CompletionReviewCandidate.from("done", kotlinx.serialization.json.JsonObject(Json.parseToJsonElement(body).jsonObject - "deliverables").toString())
        fixture.model.roundResponses += listOf(call("confirm", "done",
            body.toString()))
        val result = fixture.coordinator.coordinateTask(taskGoal = "Record this fact", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = null,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals("Fact recorded is supported", result.summary)
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "update_memory" })
        assertTrue(fixture.events(started).any { it.payloadJson.contains(CompletionReviewGate.CODE) })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test fun localUpdatesDoNotForceRepeatedUnchangedCompletionReviews() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(reviewEnabled = true)
        val started = fixture.startTurn("local-review-loop", "Record this fact", activate = false)
        val scope = memoPayload("Record this fact").substringBefore("\"steps\":") + "\"steps\":[]}"
        fixture.model.roundResponses += listOf(call("scope", "task_memo", scope))
        repeat(6) { index ->
            fixture.model.roundResponses += listOf(call("write-$index", "update_memory", "{\"fact\":\"local value $index\"}"))
            fixture.model.roundResponses += listOf(fixture.completionCall("draft-$index", donePayload("Fact recorded", "")))
        }
        val result = fixture.coordinator.coordinateTask(taskGoal = "Record this fact", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = null,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals(fixture.events(started).filter { it.eventKind == AgentEventKind.TOOL_FINISHED }.joinToString("\n") { it.payloadJson },
            2, fixture.toolExecutor.localCalls.count { it.name == "update_memory" })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test
    fun directTextUsesConfiguredStablePrefixWithoutDeviceAttempt() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("direct", "Explain the current task")
        fixture.model.cannedStreamEvents = listOf(LLMStreamEvent.Content("A direct answer."))

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Explain the current task", additionalContext = "",
            durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode
        )
        fixture.complete(started, result)

        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals("A direct answer.", result.summary)
        assertFalse(result.hasToolCalls)
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertTrue(fixture.toolExecutor.localCalls.isEmpty())
        assertTrue(started.active.stablePrefix.systemPrompt.contains("Configured fixture operator rules."))
        val events = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
        assertTrue(events.none { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
        assertTrue(events.any { it.eventKind == AgentEventKind.TURN_COMPLETED })
    }

    @Test fun guidanceDuringObservationPreparationClosesTheOldRoundBeforeRequestingAgain() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("preparation-steer", "Inspect the current page")
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", """{"status":"need_more_info","deliverables":[],"results":[{"text":"Updated answer","evidence_refs":[]}],"unresolved_requirements":["Additional detail needed"],"requirement_checks":[]}"""))
        val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "android_read") fixture.toolExecutor.stageObservation(frame, "obs_after_guide", "Updated screen", 2L)
            else null
        }
        var applied = false
        val result = fixture.coordinator.coordinateTask(
taskGoal = "Inspect the current page",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
initialScreenRequired = true,
initialScreenFrame = frame,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val takeManualResumeInstruction: () -> String? = {
                val prepared = runBlocking { fixture.events(started).any { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED } }
                if (!applied && prepared && fixture.model.recordedRequests.isEmpty()) {
                    runBlocking { fixture.turnLedger.recordUserSteer(started.active, "preparation-guide", "Explain the current page") }
                    applied = true
                    "Use the supplement and observe again."
                } else null
            }
}
)
        fixture.complete(started, result)
        assertTrue("Expected guidance during preparation: ${result.summary}", applied)
        val events = fixture.events(started)
        val guide = events.single { it.eventKind == AgentEventKind.USER_STEER }
        val oldStart = events.first { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED }
        assertTrue(oldStart.sequence < guide.sequence)
        assertTrue(events.any { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED && it.roundId == oldStart.roundId })
        assertTrue(events.any { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED && it.sequence > guide.sequence })
        assertTrue(fixture.model.recordedRequests.isNotEmpty())
        assertTrue(fixture.model.recordedRequests.last().any { it.content.contains("Explain the current page") })
        assertEveryDeclaredToolSettled(events)
    }

    @Test fun userSteerDuringAChatRequestDiscardsTheOldReplyAndKeepsTheNewReplyToolFree() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("chat-steer", "Say hello", activate = false)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Obsolete answer"))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Updated answer"))
        var steered = false
        val public = StringBuilder()
        val result = fixture.coordinator.coordinateTask(
taskGoal = "Say hello",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = null,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { error("Chat must not prepare Android") }
    override val onStreamContent: suspend (AssistantStreamChunk) -> Unit = { public.append(it.text) }
    override val takeManualResumeInstruction: () -> String? = {
                if (!steered && fixture.model.recordedRequests.size == 1) {
                    steered = true
                    "Use the updated wording"
                } else null
            }
}
)
        fixture.complete(started, result)
        assertTrue(steered)
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals("Updated answer", result.summary)
        assertFalse(public.contains("Obsolete"))
        assertTrue(fixture.toolExecutor.localCalls.isEmpty())
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertTrue(fixture.events(started).none { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
    }

    @Test fun userSteerExposesMissingScopeUntilModelRegistersItBeforeTheNextEffect() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Record the agreed fact"
        val started = fixture.startTurn("scope-steer", goal, activate = false)
        val scope = memoPayload(goal).substringBefore("\"steps\":") + "\"steps\":[]}"
        fixture.model.roundResponses += listOf(call("original-scope", "task_memo", scope))
        fixture.model.roundResponses += listOf(call("unrefreshed-effect", "update_memory", "{\"fact\":\"agreed\"}"))
        fixture.model.roundResponses += listOf(call("refreshed-scope", "task_memo", scope),
            call("refreshed-effect", "update_memory", "{\"fact\":\"agreed\"}"))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", needMoreInfoPayload("Confirm the result")))
        var update: AgentEventEnvelope? = null

        val result = fixture.coordinator.coordinateTask(
taskGoal = goal,
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = null,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val takeManualResumeInstruction: () -> String? = {
                if (update == null && fixture.toolExecutor.localCalls.any { it.name == "task_memo" }) {
                    update = runBlocking {
                        fixture.eventStore.recordUserSteer(started.active.scope, "scope-update", "Continue the agreed work.")
                    }
                    "Read the latest user update and continue the same task."
                } else null
            }
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { error("Local scope repair must not prepare Android") }
}
)
        fixture.complete(started, result)

        assertEquals(TurnTerminal.NEED_MORE_INFO, result.terminal)
        assertEquals(2, fixture.toolExecutor.localCalls.count { it.name == "update_memory" })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        val inputs = fixture.model.recordedRequests
        val pending = inputs[1].last { it.content.contains("model_derived_task_requirements") }
        val pendingContext = Json.parseToJsonElement(Json.parseToJsonElement(pending.content).jsonObject
            .getValue("model_derived_task_requirements").jsonPrimitive.content).jsonObject
        assertEquals("false", pendingContext.getValue("scope_current").jsonPrimitive.content)
        assertEquals(update!!.eventId, pendingContext.getValue("latest_user_update_ref").jsonPrimitive.content)
        assertFalse(pendingContext.containsKey("requirements"))
        val registered = inputs.last().last { it.content.contains("model_derived_task_requirements") }
        assertTrue(registered.content.contains(goal))
        assertFalse(registered.content.contains("scope_current"))
        assertEquals(0, fixture.events(started).count { it.eventKind == AgentEventKind.TOOL_FINISHED &&
            Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result.diagnosticCode == "TASK_REQUIREMENTS_REQUIRED" })
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test fun badCompletionReferenceCanBeRepairedWithoutDeviceWork() = runBlocking {
        for (raw in listOf("{", "{\"draft\":{}}")) {
            val fixture = TaskCoordinatorTestFixture()
            val started = fixture.startTurn("repair-reference", "Report the blocker", activate = false)
            fixture.model.roundResponses += listOf(call("scope", "task_memo", memoPayload("Report the blocker")))
            fixture.model.roundResponses += listOf(call("bad", "done", raw))
            fixture.model.roundResponses += listOf(fixture.completionCall("fixed", needMoreInfoPayload("Source unavailable")))
            val result = fixture.coordinator.coordinateTask(
taskGoal = "Report the blocker",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = null,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { error("Reference repair must not prepare Android") }
}
)
            fixture.complete(started, result)
            assertEquals("result=$result events=${fixture.events(started).filter { it.eventKind == AgentEventKind.TOOL_FINISHED || it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }}", TurnTerminal.NEED_MORE_INFO, result.terminal)
            val events = fixture.events(started)
            assertTrue(events.any { it.payloadJson.contains("tool_arguments") })
            assertTrue(events.none { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
            assertEveryDeclaredToolSettled(events)
        }
    }

    @Test
    fun taskLocalToolsNeverTouchDevicePort() = runBlocking {
        for (toolName in listOf("update_memory", "workspace", "automation")) {
            val fixture = TaskCoordinatorTestFixture()
            val started = fixture.startTurn(
                requestId = "local-memory", goal = "Record this task fact",
                profile = OperatorToolProfile.DEFAULT, activate = false
            )
            fixture.model.roundResponses += listOf(
                call("scope", "task_memo", memoPayload("Record this task fact")),
                call("memory-call", toolName, "{\"fact\":\"local only\"}")
            )
            fixture.model.roundResponses += listOf(
                fixture.completionCall("local-stop", needMoreInfoPayload("Please confirm"))
            )

            val result = fixture.coordinator.coordinateTask(
taskGoal = "Record this task fact",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = null,
executionStrategy = started.executionStrategy,
toolProfile = OperatorToolProfile.DEFAULT,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { error("Local tools must not prepare Android") }
    override val onExecutionStarted: suspend (firstToolName: String) -> Unit = { error("Local tools must not start device execution") }
}
)
            fixture.complete(started, result)

            assertEquals(TurnTerminal.NEED_MORE_INFO, result.terminal)
            assertTrue(fixture.toolExecutor.localCalls.map { it.name }.contains(toolName))
            assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
            val events = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
            assertTrue(events.none { it.eventKind == AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED })
            assertTrue(events.none { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
            assertEveryDeclaredToolSettled(events)
        }
    }

    @Test
    fun structuredCandidateRequiresExplicitReviewInTheProductionLoop() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(reviewEnabled = true)
        val started = fixture.startTurn("reviewed-done", "Read the current state")
        fixture.model.roundResponses += listOf(call("scope", "task_memo", memoPayload("read current state")), call("read", "android_read", "{\"op\":\"screen\"}"))
        val body = donePayload("read current state", "obs_read")
        fixture.model.roundResponses += listOf(fixture.completionCall("draft", body))
        fixture.model.roundResponses += listOf(fixture.completionCall("repeated-draft", body))
        val candidate = CompletionReviewCandidate.from("done", kotlinx.serialization.json.JsonObject(Json.parseToJsonElement(body).jsonObject - "deliverables").toString())
        fixture.model.roundResponses += listOf(call("confirm", "done",
            body.toString()))
        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Read the current state", additionalContext = "", durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend, executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(TurnTerminal.DONE, result.terminal)
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        val initialSchema = fixture.model.recordedTools.first().single { it.name == "done" }.parameters
        assertTrue(initialSchema.getValue("results").required)
        assertFalse(initialSchema.containsKey("candidate_id"))
        val reviewSchema = fixture.model.recordedTools[2].single { it.name == "done" }.parameters
        assertEquals(initialSchema, reviewSchema)
        assertEquals(1, fixture.model.recordedTools.distinct().size)
        assertFalse(reviewSchema.containsKey("replacement"))
        val events = fixture.events(started)
        val rejections = events.filter { it.eventKind == AgentEventKind.TOOL_FINISHED }.map {
            Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result.diagnosticCode }
        assertTrue(rejections.contains(CompletionReviewGate.CODE))
        assertEveryDeclaredToolSettled(events)
    }

    @Test fun malformedContentRepairDoesNotRepeatCompletedDeviceWork() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(reviewEnabled = true)
        val started = fixture.startTurn("content-repair", "Read current state")
        fixture.model.roundResponses += listOf(call("read", "android_read", """{"op":"screen"}"""))
        val body = donePayload("read current state", "obs_read")
        fixture.model.roundResponses += listOf(fixture.completionCall("draft", body))
        fixture.model.roundResponses += listOf(call("invalid", "done", """{"status":"done","results":"wrong"}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("fixed", body))
        val result = fixture.coordinator.coordinateTask(taskGoal = "Read current state", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        assertEquals(fixture.diagnostics.joinToString("\n"), TurnTerminal.DONE, result.terminal)
        assertEquals(1, fixture.toolExecutor.deviceCalls.size)
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        assertEveryDeclaredToolSettled(fixture.events(started))
    }

    @Test
    fun groundedDeviceReadThenDoneCompletesAndEveryToolFinishes() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("grounded-done", "Read the current state")
        fixture.model.roundResponses += listOf(
            call("memo-call", "task_memo", memoPayload("read current state"))
        )
        fixture.model.roundResponses += listOf(
            call("read-call", "android_read", "{\"op\":\"screen\"}")
        )
        fixture.model.roundResponses += listOf(
            call("complete-memo-call", "task_memo", completeMemoPayload("read current state", "obs_read"))
        )
        fixture.model.roundResponses += listOf(
            fixture.completionCall("done-call", donePayload("read current state", "obs_read"))
        )

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Read the current state", additionalContext = "",
            durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode
        )
        fixture.complete(started, result)

        assertEquals(TurnTerminal.DONE, result.terminal)
        assertTrue(result.taskDoneByModel)
        assertEquals("done", result.taskDoneStatus)
        assertTrue(fixture.toolExecutor.localCalls.any { it.name == "task_memo" })
        assertTrue(fixture.toolExecutor.localCalls.any { it.name == "done" })
        assertTrue(fixture.toolExecutor.deviceCalls.any { it.name == "android_read" })
        val events = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
        assertEveryDeclaredToolSettled(events)
        assertTrue(events.any { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
    }

    @Test
    fun browsingHistoryWithoutUpdatingTheWorkingRecordDoesNotBlockTheNextBrowse() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("browse-without-record", "Browse the next page")
        val historyCoordinator = DurableModelRoundCoordinator(fixture.eventStore,
            ExecutionAttemptBindingPreparer(object : ExecutionAttemptBackendProbe {
                override suspend fun prepare(route: ExecutionAttemptRoute): ExecutionBackendObservation =
                    fixture.expectedBackend.copy(backendKind = route.expectedBackend)
                override suspend fun inspect(route: ExecutionAttemptRoute): ExecutionBackendObservation =
                    fixture.expectedBackend.copy(backendKind = route.expectedBackend)
            }))
        val planner = ExecutionRoundPlanner(
            listOf(RegisteredToolContract("screen_act", "Browse", ToolBinding.DEVICE_BOUND,
                ToolEffect.STATE_CHANGING, emptyMap()),
                RegisteredToolContract("task_memo", "Retain task scope", ToolBinding.TURN_LOCAL,
                    ToolEffect.STATE_CHANGING, emptyMap())),
            listOf(DeviceToolCapabilityRequirement("screen_act", DeviceCapability.SWIPE)),
            callIdFactory = { _, provider -> requireNotNull(provider.providerCallId) })
        val scopeOnly = memoPayload("Browse the next page").substringBefore("\"steps\":") + "\"steps\":[]}"
        val scopeHandle = historyCoordinator.begin(started.active.scope, "scope-round", startCommitted = true)
        val scopeRound = NormalizedModelRound.create(emptyList(),
            listOf(ProviderToolCall("scope", "task_memo", scopeOnly)))
        val scopeExecution = planner.plan(scopeRound, startCommitted = true, repairPurpose = RepairPurpose.NONE)
        historyCoordinator.commit(scopeHandle, scopeRound, scopeExecution, mapOf("scope" to
            ToolPresentationSnapshot("tool.task_memo", ToolPresentationCategory.SUPPORTING, ResultImageRole.NONE)))
        val scopeResult = fixture.toolExecutor.executeTurnLocal(ToolCall("scope", "task_memo", scopeOnly))
        fixture.eventStore.finishTool(ToolLifecycleEventScope(started.active.scope.threadId,
            started.active.scope.turnId, "scope-round", "scope"), scopeResult, scopeHandle.roundDetailRef)
        repeat(12) { index ->
            val callId = "earlier-browse-$index"
            val roundId = "earlier-round-$index"
            val handle = historyCoordinator.begin(started.active.scope, roundId, startCommitted = true)
            val binding = historyCoordinator.prepareDeviceBinding(handle, fixture.strategy, fixture.expectedBackend)
            val round = NormalizedModelRound.create(emptyList(), listOf(ProviderToolCall(callId, "screen_act",
                "{\"actions\":[{\"op\":\"scroll\",\"direction\":\"down\"}]}")))
            val execution = planner.plan(round, startCommitted = true, repairPurpose = RepairPurpose.NONE) { binding }
            historyCoordinator.commit(handle, round, execution, mapOf(callId to
                ToolPresentationSnapshot("tool.screen_act", ToolPresentationCategory.SUPPORTING,
                    ResultImageRole.NONE)))
            val toolStart = execution.toolStarts.single()
            val result = ToolExecutionResult("Earlier page $index", ToolExecutionStatus.SUCCEEDED,
                ToolEvidenceStatus.CAPTURED, observationId = "earlier-page-$index")
            fixture.eventStore.finishTool(ToolLifecycleEventScope(started.active.scope.threadId,
                started.active.scope.turnId, roundId, callId, toolStart.attemptId, toolStart.executionId),
                result, handle.roundDetailRef)
        }
        fixture.model.roundResponses += listOf(call("next-browse", "screen_act",
            "{\"actions\":[{\"op\":\"scroll\",\"direction\":\"up\"}]}"))
        val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

        val result = fixture.coordinator.coordinateTask(taskGoal = "Browse the next page", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, initialScreenRequired = true,
            initialScreenFrame = frame, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)

        val events = fixture.events(started)
        val browseCallId = events.single { it.eventKind == AgentEventKind.TOOL_CALL &&
            Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == "next-browse" }.callId
        val receipts = events.filter { it.callId == browseCallId &&
            it.eventKind in setOf(AgentEventKind.TOOL_CALL_REJECTED, AgentEventKind.TOOL_FINISHED) }
            .joinToString("\n") { it.payloadJson }
        assertTrue(fixture.diagnostics.joinToString("\n") + "\n" + receipts,
            fixture.toolExecutor.deviceCalls.any { it.id == browseCallId && it.name == "screen_act" })
        assertEquals(listOf("scope"), fixture.toolExecutor.localCalls.filter { it.name == "task_memo" }.map { it.id })
        assertFalse(fixture.toolExecutor.localCalls.any { it.name == "workspace" })
        assertTrue(events.any { it.callId == browseCallId && it.eventKind == AgentEventKind.TOOL_FINISHED &&
            Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result.executionStatus == ToolExecutionStatus.SUCCEEDED })
        // Scope bootstrap is the only file write; none of the twelve page findings was saved.
        assertEquals(1, events.count { it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN })
        val record = TaskWorkspace(fixture.eventStore, fixture.blobStore).read(started.active.scope,
            "execution/record.md", WorkspacePolicy(8, 4096, 16384, 64))
        assertFalse(record.contains("Earlier page"))
        assertFalse(fixture.model.recordedRequests.flatten().any {
            "<task_observation_journal>" in it.content || "<task_research_note>" in it.content
        })
        assertEveryDeclaredToolSettled(events)
    }

    @Test
    fun uncitedOriginalImageDoesNotBecomeAMandatoryCompletionReviewSource() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(reviewEnabled = true)
        val started = fixture.startTurn("file-original-review", "Read the current state")
        val workspace = TaskWorkspace(fixture.eventStore, fixture.blobStore)
        val file = workspace.write(started.active.scope, "notes.md",
            "# Findings\nInspect the current source before completion.\n",
            false, null, "retained-notes", WorkspacePolicy(8, 8192, 65536, 128), activate = false)
        val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        fixture.model.roundResponses += listOf(
            call("scope", "task_memo", memoPayload("read current state")),
            call("read", "android_read", "{\"op\":\"screen\"}"))
        val draft = donePayload("read current state", "obs_read")
            .replace("\"deliverables\":[]", "\"deliverables\":[{\"path\":\"notes.md\",\"result_index\":0}]")
        fixture.model.roundResponses += listOf(fixture.completionCall("draft", draft))
        val body = Json.parseToJsonElement(draft).jsonObject - "deliverables"
        val entry = (body.getValue("results") as JsonArray).single().jsonObject
        val link = WorkspaceDeliverable.link(started.active.scope.turnId, file.path, file.contentRef)
        val versionedBody = JsonObject(body + ("results" to JsonArray(listOf(JsonObject(entry +
            ("text" to JsonPrimitive(entry.getValue("text").jsonPrimitive.content + "\n\n[notes.md]($link)")))))))
        val candidate = CompletionReviewCandidate.from("done", versionedBody.toString())
        fixture.model.roundResponses += listOf(call("confirm", "done",
            draft.toString()))

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Read the current state", additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, initialScreenRequired = true,
            initialScreenFrame = frame, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)

        assertEquals(TurnTerminal.DONE, result.terminal)
        val events = fixture.events(started)
        val feedback = events.filter { it.eventKind == AgentEventKind.TOOL_FINISHED }
            .map { Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result }
            .single { it.diagnosticCode == CompletionReviewGate.CODE }.outputText
        val requiredImages = Json.parseToJsonElement(feedback).jsonObject.getValue("details").jsonObject
            .getValue("required_source_images") as JsonArray
        assertTrue("A text-only candidate must not require an uncited earlier image: $feedback",
            requiredImages.isEmpty())
        val reviewRequest = fixture.model.recordedRequests[2]
        // Its retained source index remains readable even though this candidate does not require the bytes.
        assertTrue(reviewRequest.any { it.content.contains("obs_initial") })
        assertTrue(events.any { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED &&
            Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson).observationId == "obs_initial" })
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        assertEveryDeclaredToolSettled(events)
    }

    @Test
    fun reviewAfterCheckpointDeliversOnlyCandidateReferencedImages() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(reviewEnabled = true, retainedImageLimit = 0)
        val started = fixture.startTurn("uncited-original-batches", "Read the current state")
        val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val originals = (0 until 10).associate { index ->
            val id = "retained_original_$index"
            val bytes = frame + index.toByte()
            val blob = fixture.blobStore.put(bytes)
            fixture.eventStore.append(AgentEventDraft(
                eventKind = AgentEventKind.DETAIL_ATTACHMENT_ADDED,
                threadId = started.active.scope.threadId, turnId = started.active.scope.turnId,
                roundId = "retained-round-$index", callId = "retained-call-$index",
                payloadJson = Json.encodeToString(DetailAttachmentAddedEventPayload(
                    contractVersion = 1, attachmentId = id, role = DetailAttachmentRole.INPUT_OBSERVATION,
                    mimeType = "image/png", contentRef = blob.contentRef, contentHash = blob.contentHash,
                    sizeBytes = blob.sizeBytes, observationId = id)), idempotencyKey = id))
            id to java.util.Base64.getEncoder().encodeToString(bytes)
        }
        // A completed long task has already frozen its retention policy before review
        // requests introduce more explicit source images than the retained-image limit.
        repeat(6) { index ->
            val text = if (index == 0 || index == 5) "Retained task state" else "retained data ".repeat(9000)
            fixture.recordClosedHistory(started.active.scope, "history-$index", text)
        }
        val thresholds = Json.decodeFromString<HarnessPolicy>(checkNotNull(javaClass.getResourceAsStream(
            "/com/droidance/agent/harness_policy.json")).bufferedReader().use { it.readText() }).runtimeThresholds
        val identity = started.active.session.key.identity
        val estimator = StructuralContextTokenEstimator.forSession(identity.transportKind.name,
            "llm-router-v1", identity.modelId)
        val contextPolicy = ContextPolicySnapshot(fixture.model.currentContextWindowTokens(),
            thresholds.llmActionMaxOutputTokens, fixture.model.currentContextWindowTokens() / 16,
            thresholds.contextBudgetPercent, 100, 0, estimator.identity)
        val projection = requireNotNull(DurableContextProjector.project(fixture.events(started)))
        val checkpoint = ContextCheckpointPlanner(estimator).plan(ContextCheckpointPlanInput(
            parentCheckpoint = null, stablePrefix = started.active.stablePrefix,
            dynamicRequestSuffix = ContextDynamicRequestSuffix.empty(), sortedUnits = projection.units,
            currentObservationProjections = projection.observations, contextPolicy = contextPolicy,
            compactionSummary = "Earlier raw rounds compacted; retained originals remain in the event store."
        )) as ContextCheckpointDecision.CheckpointReady
        assertTrue(checkpoint.payload.droppedUnits.isNotEmpty())
        val commit = fixture.eventStore.recordContextCheckpoint(started.active.scope.threadId,
            started.active.scope.turnId, checkpoint.payload, started.active.session)
        started.active.session = started.active.session.copy(key = commit.epochTransition.current)
        fixture.model.roundResponses += listOf(call("scope", "task_memo", memoPayload("read current state")),
            call("read", "android_read", "{\"op\":\"screen\"}"))
        val draft = donePayload("read current state", "obs_read,obs_initial")
        fixture.model.roundResponses += listOf(fixture.completionCall("draft", draft))
        val candidate = CompletionReviewCandidate.from("done",
            JsonObject(Json.parseToJsonElement(draft).jsonObject - "deliverables").toString())
        fixture.model.roundResponses += listOf(call("confirm", "done",
            draft.toString()))

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Read the current state", additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, initialScreenRequired = true,
            initialScreenFrame = frame, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)

        assertEquals(fixture.diagnostics.joinToString("\n"), TurnTerminal.DONE, result.terminal)
        val events = fixture.events(started)
        val feedback = events.filter { it.eventKind == AgentEventKind.TOOL_FINISHED }
            .map { Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result }
        val reviewFeedback = feedback.single { it.diagnosticCode == CompletionReviewGate.CODE }
        val requiredImages = Json.parseToJsonElement(reviewFeedback.outputText).jsonObject
            .getValue("details").jsonObject.getValue("required_source_images") as JsonArray
        assertEquals(listOf("obs_initial"), requiredImages.map { it.jsonPrimitive.content })
        assertFalse(feedback.any { it.diagnosticCode == CompletionReviewGate.SOURCE_CODE })
        val encodedInitial = java.util.Base64.getEncoder().encodeToString(frame)
        assertTrue(fixture.model.recordedRequests.drop(2).any { request -> request.any {
            encodedInitial in it.imageBase64List || it.imageBase64 == encodedInitial
        } })
        fixture.model.recordedRequests.drop(2).forEach { request ->
            val present = originals.filterValues { encoded -> request.any {
                encoded in it.imageBase64List || it.imageBase64 == encoded
            } }.keys
            assertTrue("Uncited retained images must not occupy review deliveries: $present", present.isEmpty())
        }
        assertEquals(3, fixture.model.recordedRequests.size)
        assertEquals(originals.size, events.count { event -> event.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED &&
            Json.decodeFromString<DetailAttachmentAddedEventPayload>(event.payloadJson).observationId in originals.keys })
        assertEquals(contextPolicy.hash, fixture.eventStore.latestContextCheckpointForTurn(
            started.active.scope.turnId)!!.payload.contextPolicyHash)
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        assertEveryDeclaredToolSettled(events)
    }

    @Test
    fun malformedCompletionAfterDeviceReadIsRejectedAndCanRecoverWithoutRuntimeException() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("malformed-after-device", "Read the current state")
        fixture.model.roundResponses += listOf(call("scope", "task_memo", memoPayload("read current state")), call("read", "android_read", "{\"op\":\"screen\"}"))
        // The generic ToolJson sanitizer removes Markdown fences, while file submission
        // requires strict JSON. A missing completion control must never reach source review.
        val malformed = "```json\n" + donePayload("read current state", "obs_read") + "\n```"
        fixture.model.roundResponses += listOf(call("malformed", "done", malformed))
        fixture.model.roundResponses += listOf(fixture.completionCall("fixed", donePayload("read current state", "obs_read")))
        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Read the current state", additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = started.expectedBackend, executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(TurnTerminal.DONE, result.terminal)
        val events = fixture.events(started)
        assertTrue(events.any { it.eventKind == AgentEventKind.TOOL_FINISHED &&
            it.payloadJson.contains("tool_arguments") && it.payloadJson.contains("ARGUMENT_SCHEMA_MISMATCH") })
        assertEveryDeclaredToolSettled(events)
    }

    @Test
    fun directDeviceCallCanCompleteWithoutAnOptionalMemo() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("direct-device-call", "Read screen directly")
        fixture.model.roundResponses += listOf(
            call("read-call", "android_read", "{\"op\":\"screen\"}")
        )
        fixture.model.roundResponses += listOf(
            fixture.completionCall("done-call", """{"status":"done","deliverables":[],"results":[{"text":"Screen read done","evidence_refs":["obs_read"]}],"unresolved_requirements":[],"requirement_checks":[]}""")
        )

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Read screen directly", additionalContext = "",
            durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode
        )
        fixture.complete(started, result)

        assertEquals(TurnTerminal.DONE, result.terminal)
        assertTrue(result.taskDoneByModel)
        assertFalse(fixture.toolExecutor.localCalls.any { it.name == "task_memo" })
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "done" })
        assertFalse(fixture.events(started).any { it.payloadJson.contains("TASK_REQUIREMENTS_REQUIRED") })
        assertTrue(fixture.toolExecutor.deviceCalls.any { it.name == "android_read" })
    }

    @Test
    fun failedDoneFollowedByPlainTextCannotBecomeSuccessfulCompletion() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("failed-done", "Finish the task")
        fixture.toolExecutor.resultOverride = { toolCall, _ ->
            if (toolCall.name == "done") {
                com.droidance.core.agent.event.ToolExecutionResult(
                    outputText = "completion verification failed",
                    executionStatus = ToolExecutionStatus.FAILED,
                    evidenceStatus = com.droidance.core.agent.event.ToolEvidenceStatus.NOT_REQUIRED,
                    diagnosticCode = "COMPLETION_FAILED"
                )
            } else null
        }
        val selectedFile = fixture.completionCall("failed-done-call", donePayload("task", "obs_missing"))
        fixture.model.roundResponses += listOf(call("scope", "task_memo", memoPayload("Finish the task")))
        fixture.model.roundResponses += listOf(selectedFile)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Candidate plain text."))

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Finish the task", additionalContext = "",
            durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode
        )
        fixture.complete(started, result)

        assertFalse(result.terminal == TurnTerminal.DONE)
        assertFalse(result.terminal == TurnTerminal.DIRECT_REPLY)
        assertFalse(result.taskDoneByModel)
        val events = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
        val replayedCall = fixture.model.recordedRequests[2].flatMap { it.toolCalls.orEmpty() }
            .single { it.id == "failed-done-call" }
        assertEquals(selectedFile.arguments, replayedCall.arguments)
        val projection = requireNotNull(com.droidance.core.agent.context.DurableContextProjector.project(events))
        val restored = com.droidance.core.agent.context.DurableContextMessageProjector.build(projection, "Finish the task")
        assertEquals(selectedFile.arguments, restored.flatMap { it.toolCalls.orEmpty() }
            .single { it.id == "failed-done-call" }.arguments)
        val doneDeclaration = events.first { it.eventKind == AgentEventKind.TOOL_CALL && Json.decodeFromString<com.droidance.core.agent.event.ToolCallEventPayload>(it.payloadJson).name == "done" }
        val finish = events.single { it.eventKind == AgentEventKind.TOOL_FINISHED && it.callId == doneDeclaration.callId }
        assertEquals(ToolExecutionStatus.FAILED,
            Json.decodeFromString<ToolFinishedEventPayload>(finish.payloadJson).result.executionStatus)
        assertEveryDeclaredToolSettled(events)
    }

    @Test
    fun unknownToolIsRejectedWithoutDispatch() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("unknown-tool", "Use the available tools")
        fixture.model.roundResponses += listOf(call("unknown-call", "tool_not_declared", "{}"))

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Use the available tools", additionalContext = "",
            durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy,
            toolProfile = started.profile, mode = started.mode
        )
        fixture.complete(started, result)

        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertTrue(fixture.toolExecutor.localCalls.isEmpty())
        val events = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
        val rejection = events.single { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }
        assertEquals("UNKNOWN_TOOL",
            Json.decodeFromString<ToolCallRejectedEventPayload>(rejection.payloadJson).result.diagnosticCode)
        assertEveryDeclaredToolSettled(events)
    }

    @Test
    fun askHumanStopsLaterDeviceActionInSameRound() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("human-boundary", "Ask for user verification")
        var paused = false
        fixture.model.roundResponses += listOf(
            call("human-call", "ask_human", "{\"boundary_type\":\"live_verification\",\"reason\":\"Verification required\"}"),
            call("action-after-human", "android_act", "{\"op\":\"open\"}")
        )

        val result = fixture.coordinator.coordinateTask(
taskGoal = "Ask for user verification",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onManualTakeoverNeeded: (reason: String) -> Unit = { paused = true }
    override val isPaused: () -> Boolean = { paused }
    override val takeManualResumeInstruction: () -> String? = { null }
}
)
        fixture.complete(started, result)

        assertFalse(fixture.toolExecutor.deviceCalls.any { it.name == "android_act" })
        assertTrue(fixture.toolExecutor.localCalls.any { it.name == "ask_human" })
        val events = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
        assertTrue(events.any { it.eventKind == AgentEventKind.USER_INTERVENTION_REQUESTED })
        assertTrue(events.none { it.eventKind == AgentEventKind.TOOL_STARTED && it.callId == "action-after-human" })
        assertEveryDeclaredToolSettled(events)
    }

    @Test fun invalidBoundaryReturnsToSameAgentWithoutHandoff() = runBlocking {
        rejectedHandoffContinues(rejectAtDispatch = false)
    }

    @Test fun rejectedToolReceiptDoesNotCreateRuntimePause() = runBlocking {
        rejectedHandoffContinues(rejectAtDispatch = true)
    }

    private suspend fun rejectedHandoffContinues(rejectAtDispatch: Boolean) {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("rejected-human-$rejectAtDispatch", "Read the current state")
        if (rejectAtDispatch) fixture.toolExecutor.resultOverride = { call, _ ->
            com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("HUMAN_BOUNDARY_REJECTED", retryable = true)
                .takeIf { call.name == "ask_human" }
        }
        fixture.model.roundResponses += listOf(
            call("memo-call", "task_memo", memoPayload("read current state")))
        val boundary = if (rejectAtDispatch) "user_judgment" else "ordinary_navigation"
        fixture.model.roundResponses += listOf(
            call("rejected-human", "ask_human", """{"boundary_type":"$boundary","reason":"Close the overlay"}"""),
            call("excluded-action", "android_act", """{"op":"open"}"""))
        fixture.model.roundResponses += listOf(
            call("fresh-read", "android_read", """{"op":"screen"}"""))
        fixture.model.roundResponses += listOf(
            call("complete-memo-call", "task_memo", completeMemoPayload("read current state", "obs_read")))
        fixture.model.roundResponses += listOf(
            fixture.completionCall("done-call", donePayload("read current state", "obs_read")))
        var takeoverRequests = 0
        val streamed = StringBuilder()
        val result = fixture.coordinator.coordinateTask(
taskGoal = "Read the current state",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onManualTakeoverNeeded: (reason: String) -> Unit = { takeoverRequests++ }
    override val onStreamContent: suspend (AssistantStreamChunk) -> Unit = { streamed.append(it.text) }
}
)
        fixture.complete(started, result)
        assertEquals(TurnTerminal.DONE, result.terminal)
        assertEquals(0, takeoverRequests)
        assertFalse(streamed.contains("Close the overlay"))
        assertTrue(fixture.toolExecutor.deviceCalls.any { it.name == "android_read" })
        assertFalse(fixture.toolExecutor.deviceCalls.any { it.name == "android_act" })
        assertEquals(rejectAtDispatch, fixture.toolExecutor.localCalls.any { it.name == "ask_human" })
        val events = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
        if (!rejectAtDispatch) {
            assertFalse(events.any { it.eventKind == AgentEventKind.USER_INTERVENTION_REQUESTED })
            assertFalse(requireNotNull(fixture.eventStore.threadReadModel(started.active.scope.threadId)).conversation.entries
                .any { it.kind == com.droidance.core.agent.event.ConversationEntryKind.ASSISTANT_USER_INTERVENTION })
        }
        assertEveryDeclaredToolSettled(events)
    }

    @Test
    fun preparedImageIsSentWithConfiguredRulesToFirstModelRound() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("prepared-image", "Inspect the prepared screen")
        fixture.model.cannedStreamEvents = listOf(LLMStreamEvent.Content("Screen received."))
        val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Inspect the prepared screen",
            additionalContext = "Use the configured operator rules.",
            durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy,
            initialScreenRequired = true, initialScreenFrame = frame,
            toolProfile = started.profile, mode = started.mode
        )
        fixture.complete(started, result)

        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertTrue(fixture.model.recordedRequests.flatten().any { !it.imageBase64.isNullOrBlank() })
        assertTrue(started.active.stablePrefix.systemPrompt.contains("Configured fixture operator rules."))
    }

    private fun assertEveryDeclaredToolSettled(events: List<AgentEventEnvelope>) {
        events.filter { it.eventKind == AgentEventKind.TOOL_CALL }.forEach { declaration ->
            val lifecycle = events.filter { it.callId == declaration.callId }
            val starts = lifecycle.count { it.eventKind == AgentEventKind.TOOL_STARTED }
            val rejects = lifecycle.count { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }
            val finishes = lifecycle.count { it.eventKind == AgentEventKind.TOOL_FINISHED }
            assertTrue("tool ${declaration.callId} must have one settled lifecycle",
                (starts == 1 && rejects == 0 && finishes == 1) ||
                    (starts == 0 && rejects == 1 && finishes == 0))
            lifecycle.filter { it.eventKind == AgentEventKind.TOOL_FINISHED }.forEach { finish ->
                assertNotNull(Json.decodeFromString<ToolFinishedEventPayload>(finish.payloadJson).result)
            }
            lifecycle.filter { it.eventKind == AgentEventKind.TOOL_STARTED }.forEach { start ->
                assertNotNull(Json.decodeFromString<ToolStartedEventPayload>(start.payloadJson))
            }
        }
    }

    private companion object {
        fun call(id: String, name: String, arguments: String): LLMStreamEvent.ToolCallChunk =
            LLMStreamEvent.ToolCallChunk(index = id.hashCode().and(Int.MAX_VALUE), id = id, name = name, arguments = arguments)

        fun donePayload(requirement: String, evidenceRef: String): String =
            """{"status":"done","deliverables":[],"results":[{"text":"$requirement is supported","evidence_refs":${kotlinx.serialization.json.JsonArray(evidenceRef.split(',').map(String::trim).filter(String::isNotBlank).map { kotlinx.serialization.json.JsonPrimitive(it) })}}],"unresolved_requirements":[],"requirement_checks":[{"requirement_id":"r1","status":"supported","unresolved_index":-1,"result_indices":[0]}]}"""

        fun memoPayload(requirement: String): String =
            """{"record_path":"execution/record.md","state":"working","output_constraints":[],"requested_deliverables":[],"protected_values":[],"requirements":["$requirement"],"steps":[{"id":"inspect","objective":"Inspect the requested state","acceptance":"The current source contains the requested state.","status":"active","evidence_refs":[]}]}"""

        fun completeMemoPayload(requirement: String, evidenceRef: String): String =
            """{"record_path":"execution/record.md","state":"verified","output_constraints":[],"requested_deliverables":[],"protected_values":[],"requirements":["$requirement"],"steps":[{"id":"inspect","objective":"Inspect the requested state","acceptance":"The current source contains the requested state.","status":"complete","evidence_refs":${kotlinx.serialization.json.JsonArray(evidenceRef.split(',').map(String::trim).filter(String::isNotBlank).map { kotlinx.serialization.json.JsonPrimitive(it) })}}]}"""

        fun needMoreInfoPayload(unresolved: String): String =
            """{"status":"need_more_info","deliverables":[],"results":[{"text":"Need user input","evidence_refs":[]}],"unresolved_requirements":["$unresolved"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}"""
    }
}
