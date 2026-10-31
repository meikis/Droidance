// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.event

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*

import com.droidance.agent.event.*

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.agent.AgentProcessRecoveryCoordinator
import com.droidance.core.agent.attempt.AttemptBindingResolution
import com.droidance.core.agent.attempt.BackendEndpointSnapshot
import com.droidance.core.agent.attempt.BackendInventory
import com.droidance.core.agent.attempt.CapabilitySnapshot
import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.attempt.DeviceToolCapabilityRequirement
import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionBackendKind
import com.droidance.core.agent.attempt.ExecutionRoundPlanner
import com.droidance.core.agent.attempt.FrozenAttemptBindingFactory
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.agent.llm.session.FrozenTurnModelSession
import com.droidance.core.agent.llm.session.ModelSessionKey
import com.droidance.core.model.llm.session.ModelTransport
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentEventLifecycleStoreTest {
    @Test fun interimRepliesSurviveDatabaseReopenAndInterruptedTaskSettlementWithoutDuplication() = runBlocking {
        val events = store("stage-")
        val scope = TurnEventScope("stage-thread", "stage-turn")
        events.createThread(scope.threadId, "Staged task")
        events.startTurn(scope, modelIdentity(scope.threadId), "provider-config")
        events.recordUserMessage(scope, "stage-input", "Review two sources")
        suspend fun commitRound(id: String, text: String, started: Boolean): ToolLifecycleEventScope {
            val modelScope = ModelRoundScope(scope.threadId, scope.turnId, id)
            val range = events.submittedItemRangeForTurn(scope.turnId)
            val detail = "model-round:$id"
            events.recordModelRoundStarted(PreparedModelRoundStart.create(modelScope, RepairPurpose.NONE, started, range, detail))
            val normalized = NormalizedModelRound.create(listOf(text), listOf(ProviderToolCall("provider:$id", "read", "{}")))
            val execution = ExecutionRoundPlanner(listOf(RegisteredToolContract("read", "Read local source",
                ToolBinding.TURN_LOCAL, ToolEffect.READ_ONLY, emptyMap())), emptyList(),
                callIdFactory = { _, _ -> "call:$id" }).plan(normalized, started, RepairPurpose.NONE)
            val plan = PreparedModelRoundCommit.create(modelScope, started, RepairPurpose.NONE, range, detail, normalized, execution,
                mapOf("call:$id" to ToolPresentationSnapshot("tool.read", ToolPresentationCategory.PRIMARY, ResultImageRole.NONE)),
                publicMessageId = "assistant:$id".takeIf { !started })
            events.recordModelRoundPlan(plan)
            events.recordModelRoundPlan(plan)
            return ToolLifecycleEventScope(scope.threadId, scope.turnId, id, callId = "call:$id")
        }
        val first = commitRound("opening", "I will review the sources.", false)
        events.finishTool(first, successfulResult(), "model-round:opening")
        commitRound("first-stage", "First source findings; one value remains unknown.", true)
        database.close()
        database = openDatabase()
        val reopened = store("stage-reopened-")
        val beforeRecovery = reopened.threadReadModel(scope.threadId)!!
        assertEquals(listOf("First source findings; one value remains unknown."),
            beforeRecovery.activityByTurn.getValue(scope.turnId).publicProgressHistory.map { it.text })
        reopened.recoverInterruptedTurn(scope, "stage-process")
        reopened.terminateInterruptedTurn(scope, "stage-terminal", "Stopped after retained findings")
        val restored = reopened.threadReadModel(scope.threadId)!!
        assertEquals(listOf("First source findings; one value remains unknown."),
            restored.activityByTurn.getValue(scope.turnId).publicProgressHistory.map { it.text })
        assertEquals(TurnReadModelState.TERMINAL, restored.turns.single().state)
        assertEquals("Stopped after retained findings", restored.conversation.entries.last().text)
    }

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AgentEventDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "agent-lifecycle-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun guidanceIdentityAndActiveTimeSurviveDatabaseReopen() = runBlocking {
        val events = store("guidance-")
        val scope = TurnEventScope("guidance-thread", "guidance-turn")
        events.createThread(scope.threadId, "Guidance")
        events.startTurn(scope, modelIdentity(scope.threadId), "provider-config")
        events.recordUserMessage(scope, "original", "Inspect records")
        events.recordUserSteer(scope, "supplement", "Only recent records", activeDurationMillis = 1_200L)
        database.close()
        database = openDatabase()
        val restored = store("reopened-").threadReadModel(scope.threadId)!!.conversation.entries
        assertEquals(listOf("original", "supplement"), restored.map { it.itemId })
        assertEquals(ConversationEntryKind.USER_STEER, restored.last().kind)
        assertEquals(1_200L, restored.last().activeDurationMillis)
        assertEquals(scope.turnId, restored.last().turnId)
    }

    @Test
    fun workspace_versions_survive_database_reopen_and_task_interruption() = runBlocking {
        val eventStore = store("workspace-")
        val scope = TurnEventScope("workspace-thread", "workspace-turn")
        eventStore.createThread(scope.threadId, "Workspace")
        eventStore.startTurn(scope, modelIdentity(scope.threadId), "provider-config")
        val root = java.io.File(context.cacheDir, "workspace-${UUID.randomUUID()}")
        val policy = com.droidance.core.agent.workspace.WorkspacePolicy(4, 128, 1024, 32)
        try {
            val files = com.droidance.core.agent.workspace.TaskWorkspace(eventStore, com.droidance.agent.blob.FileBlobStore(root))
            files.write(scope, "notes.md", "保留资料", false, null, "write", policy)
            eventStore.terminateInterruptedTurn(scope, "process", "interrupted")
            database.close()
            database = openDatabase()
            val restored = com.droidance.core.agent.workspace.TaskWorkspace(store("restored-"), com.droidance.agent.blob.FileBlobStore(root))
            assertEquals("保留资料", restored.read(scope, "notes.md", policy))
            assertTrue(restored.list(TurnEventScope(scope.threadId, "another-turn")).isEmpty())
            assertEquals(1, restored.list(scope).size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun withheld_invocation_closes_durably_without_becoming_an_executed_failure() = runBlocking {
        val eventStore = store("withheld-")
        val fixture = seedLocalTool(eventStore, "withheld")
        val result = com.droidance.core.agent.tools.ToolProtocolResult.notExecuted(
            "OBSERVATION_RECOVERY_REQUIRED", false
        )
        eventStore.finishTool(fixture.toolScope, result, fixture.roundDetailRef)
        database.close()
        database = openDatabase()
        val events = store("withheld-reopened-").eventsForTurn(fixture.turnScope.turnId)
        val restored = Json.decodeFromString<ToolFinishedEventPayload>(events.single {
            it.eventKind == AgentEventKind.TOOL_FINISHED
        }.payloadJson).result
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, restored.executionStatus)
        assertEquals(result.outputText, restored.outputText)
        val facts = com.droidance.debug.TaskAnalysisMetrics.project(events)
        assertEquals(1, facts.rejectedCalls)
        assertEquals(0, facts.failedCalls)
    }

    @Test
    fun round_session_preserves_pending_on_commit_failure_and_reopens_once() = runBlocking {
        var failCommit = true
        val eventStore = store("round-session-") { _, draft ->
            if (failCommit && draft.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED) {
                error("injected round commit failure")
            }
        }
        val scope = TurnEventScope("session-thread", "session-turn")
        eventStore.createThread(scope.threadId, "Session transaction")
        eventStore.startTurn(scope, modelIdentity(scope.threadId), "provider-config")
        eventStore.recordUserMessage(scope, "session-user", "request")
        val probe = object : com.droidance.core.agent.attempt.ExecutionAttemptBackendProbe {
            override suspend fun prepare(route: com.droidance.core.agent.attempt.ExecutionAttemptRoute):
                com.droidance.core.agent.attempt.ExecutionBackendObservation = error("Unexpected device activation")
            override suspend fun inspect(route: com.droidance.core.agent.attempt.ExecutionAttemptRoute):
                com.droidance.core.agent.attempt.ExecutionBackendObservation = error("Unexpected device inspection")
        }
        val coordinator = com.droidance.core.agent.harness.DurableModelRoundCoordinator(
            eventStore, com.droidance.core.agent.attempt.ExecutionAttemptBindingPreparer(probe)
        )
        val session = com.droidance.core.agent.OperatorRoundSession(
            coordinator, com.droidance.core.agent.OperatorRoundScheduler(emptyList()), scope, false, emptyMap()
        )
        session.begin(RepairPurpose.NONE)
        val handle = requireNotNull(session.pending)
        session.begin(RepairPurpose.NONE)
        assertTrue(handle === session.pending)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.settle("MODEL_REQUEST_FAILED", emptyMap()) }
        }
        assertTrue(handle === session.pending)
        assertTrue(!session.publicStartCommitted)
        assertTrue(eventStore.eventsForTurn(scope.turnId).none {
            it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED
        })
        failCommit = false
        session.settle("MODEL_REQUEST_FAILED", emptyMap())
        assertEquals(null, session.pending)
        session.settle("MUST_NOT_CREATE_ANOTHER_ROUND", emptyMap())
        database.close()
        database = openDatabase()
        val events = store("session-reopened-").eventsForTurn(scope.turnId)
        assertEquals(1, events.count { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED })
        val completed = events.single { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
        val payload = Json.decodeFromString<ModelRoundCompletedEventPayload>(completed.payloadJson)
        assertEquals("MODEL_REQUEST_FAILED", payload.finishMetadata["diagnostic_code"])
        assertTrue(events.none { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
    }

    @Test
    fun malformed_arguments_replay_repair_details_after_database_reopen() = runBlocking {
        val fixture = seedLocalTool(store("malformed-"), "malformed", "{broken xml=parameter}")
        database.close()
        database = openDatabase()
        val events = store("reopened-malformed-").eventsForTurn(fixture.turnScope.turnId)
        assertTrue(events.none { it.eventKind == AgentEventKind.TOOL_STARTED })
        val rejected = Json.decodeFromString<ToolCallRejectedEventPayload>(
            events.single { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }.payloadJson)
        val projection = requireNotNull(com.droidance.core.agent.context.DurableContextProjector.project(events))
        val messages = com.droidance.core.agent.context.DurableContextMessageProjector.build(projection, "request")
        val result = messages.single { it.role == com.droidance.core.model.llm.models.Role.TOOL }
        assertEquals(rejected.result.outputText, result.content)
        assertTrue(result.content.contains("arguments contain invalid JSON") || result.content.contains("arguments expected object"))
        assertTrue(result.content.contains("NOT_EXECUTED"))
        assertTrue(result.content.contains("expected"))
    }

    @Test
    fun malformed_completion_replays_the_public_schema_after_database_reopen() = runBlocking {
        val eventStore = store("public-completion-")
        val scope = TurnEventScope("completion-thread", "completion-turn")
        eventStore.createThread(scope.threadId, "Completion schema")
        eventStore.startTurn(scope, modelIdentity(scope.threadId), "provider-config")
        eventStore.recordUserMessage(scope, "completion-user", "request")
        val registered = mutableListOf<com.droidance.core.agent.tools.Tool>()
        com.droidance.tools.CompletionToolRegistrar({ registered.add(it) },
            { com.droidance.tools.CompletionState() }, "Public result", "Completion status", { emptyMap() }) { candidate, state ->
            if (state.record(candidate)) com.droidance.core.agent.tools.ToolProtocolResult.success("recorded")
            else com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        val base = registered.single().definition
        val publicTool = com.droidance.core.agent.ActionControlProtocol.addControlParameters(listOf(base),
            com.droidance.core.agent.ActionControlProtocol.Descriptions("refs", "terminal", "results", "text", "gaps", "checks"),
            setOf("done")).single()
        val contract = RegisteredToolContract(base.name, base.description,
            ToolBinding.TURN_LOCAL, ToolEffect.COMPLETION, base.parameters)
        val probe = object : com.droidance.core.agent.attempt.ExecutionAttemptBackendProbe {
            override suspend fun prepare(route: com.droidance.core.agent.attempt.ExecutionAttemptRoute):
                com.droidance.core.agent.attempt.ExecutionBackendObservation = error("Unexpected device activation")
            override suspend fun inspect(route: com.droidance.core.agent.attempt.ExecutionAttemptRoute):
                com.droidance.core.agent.attempt.ExecutionBackendObservation = error("Unexpected device inspection")
        }
        val coordinator = com.droidance.core.agent.harness.DurableModelRoundCoordinator(
            eventStore, com.droidance.core.agent.attempt.ExecutionAttemptBindingPreparer(probe))
        val session = com.droidance.core.agent.OperatorRoundSession(coordinator,
            com.droidance.core.agent.OperatorRoundScheduler(listOf(contract)), scope, false,
            mapOf("done" to publicTool.parameters))
        session.begin(RepairPurpose.NONE)
        session.commit(requireNotNull(session.pending), emptyMap(), setOf("done"), emptyList(),
            listOf(com.droidance.core.model.llm.models.ToolCall("broken-done", "done", "{\"status\":\"done\",\"results\":[")), emptyMap())
        database.close()
        database = openDatabase()
        val events = store("public-completion-reopened-").eventsForTurn(scope.turnId)
        val rejected = Json.decodeFromString<ToolCallRejectedEventPayload>(
            events.single { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }.payloadJson)
        val projection = requireNotNull(com.droidance.core.agent.context.DurableContextProjector.project(events))
        val messages = com.droidance.core.agent.context.DurableContextMessageProjector.build(projection, "request")
        val replayed = messages.single { it.role == com.droidance.core.model.llm.models.Role.TOOL }.content
        assertEquals(rejected.result.outputText, replayed)
        assertTrue(replayed, replayed.contains("requirement_checks"))
        assertTrue(replayed.contains("unresolved_requirements"))
        assertTrue(replayed.contains("expected_schema"))
        assertTrue(!replayed.contains("summary"))
        assertTrue(events.none { it.eventKind == AgentEventKind.TOOL_STARTED ||
            it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
    }

    @Test
    fun zero_tool_failure_persists_the_host_diagnostic_without_device_binding() = runBlocking {
        val store = store("zero-tool-failure-")
        val scope = TurnEventScope("zero-tool-thread", "zero-tool-turn")
        store.createThread(scope.threadId, "Failure diagnostic")
        store.startTurn(scope, modelIdentity(scope.threadId), "provider-config")
        store.recordUserMessage(scope, "zero-tool-user", "Hello")
        val probe = object : com.droidance.core.agent.attempt.ExecutionAttemptBackendProbe {
            override suspend fun prepare(route: com.droidance.core.agent.attempt.ExecutionAttemptRoute):
                com.droidance.core.agent.attempt.ExecutionBackendObservation = error("Unexpected device activation")
            override suspend fun inspect(route: com.droidance.core.agent.attempt.ExecutionAttemptRoute):
                com.droidance.core.agent.attempt.ExecutionBackendObservation = error("Unexpected device inspection")
        }
        val coordinator = com.droidance.core.agent.harness.DurableModelRoundCoordinator(
            store, com.droidance.core.agent.attempt.ExecutionAttemptBindingPreparer(probe)
        )
        val handle = coordinator.begin(scope, "zero-tool-round", startCommitted = false)
        coordinator.commitZeroToolResponse(
            handle, providerOutput = null,
            decision = com.droidance.core.agent.harness.ZeroToolRoundDecision.FAILED,
            diagnosticCode = "PHASE_A_REQUEST_FAILED",
            failure = java.io.IOException("private request data")
        )
        database.close()
        database = openDatabase()
        val events = store("zero-tool-reopened-").eventsForTurn(scope.turnId)
        val completed = events.single { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
        val payload = Json.decodeFromString<ModelRoundCompletedEventPayload>(completed.payloadJson)
        assertEquals("PHASE_A_REQUEST_FAILED", payload.terminalDiagnosticCode)
        assertEquals("java.io.IOException", payload.finishMetadata["exception_type"])
        assertTrue(!completed.payloadJson.contains("private request data"))
        assertTrue(events.none { it.eventKind in setOf(
            AgentEventKind.TOOL_CALL, AgentEventKind.EXECUTION_ATTEMPT_CREATED,
            AgentEventKind.MODEL_SESSION_ACTIVATED
        ) })
    }

    @Test
    fun typed_settlement_replays_to_one_activity_and_survives_reopen() = runBlocking {
        val store = store("first-")
        val fixture = seedLocalTool(store, "settlement")
        val result = successfulResult()

        val finish = store.finishTool(
            scope = fixture.toolScope,
            result = result,
            roundDetailRef = fixture.roundDetailRef,
            exclusiveDurationMillis = 12L
        )
        val retry = store.finishTool(
            scope = fixture.toolScope,
            result = result,
            roundDetailRef = fixture.roundDetailRef,
            exclusiveDurationMillis = 12L
        )
        store.completeTurn(
            threadId = fixture.turnScope.threadId,
            turnId = fixture.turnScope.turnId,
            result = TurnResult(
                status = "COMPLETED",
                assistantReply = "已完成",
                totalDurationMillis = 42L,
                activeDurationMillis = 12L
            )
        )

        assertEquals(finish.eventId, retry.eventId)
        val beforeReopen = store.eventsForThread(fixture.turnScope.threadId)
        val projected = AgentEventReadModelProjector.project(beforeReopen)
        assertEquals(12L, projected.activityByTurn.getValue(fixture.turnScope.turnId).activeDurationMillis)
        assertEquals(0, projected.activityByTurn.getValue(fixture.turnScope.turnId).operationCount)
        assertEquals(
            ToolActivityState.VERIFIED,
            projected.activityByTurn.getValue(fixture.turnScope.turnId)
                .entries.filterIsInstance<ToolActivityReadModel>().single().state
        )

        database.close()
        database = openDatabase()
        assertEquals(
            beforeReopen,
            store("reopened-").eventsForThread(fixture.turnScope.threadId)
        )
    }

    @Test
    fun changed_finish_retry_and_cross_scope_finish_are_rejected() = runBlocking {
        val store = store("scope-")
        val fixture = seedLocalTool(store, "scope")
        store.finishTool(
            scope = fixture.toolScope,
            result = successfulResult(),
            roundDetailRef = fixture.roundDetailRef
        )

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.finishTool(
                    scope = fixture.toolScope,
                    result = ToolExecutionResult(
                        outputText = "different",
                        executionStatus = ToolExecutionStatus.FAILED,
                        evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                        diagnosticCode = "BACKEND_FAILURE"
                    ),
                    roundDetailRef = fixture.roundDetailRef
                )
            }
        }
        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.finishTool(
                    scope = fixture.toolScope.copy(turnId = "another-turn"),
                    result = successfulResult(),
                    roundDetailRef = fixture.roundDetailRef
                )
            }
        }
        assertEquals(
            1,
            store.eventsForTurn(fixture.turnScope.turnId)
                .count { it.eventKind == AgentEventKind.TOOL_FINISHED }
        )
    }

    @Test
    fun turn_cannot_complete_until_started_tool_is_settled() = runBlocking {
        val store = store("open-")
        val fixture = seedLocalTool(store, "open")

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.completeTurn(
                    threadId = fixture.turnScope.threadId,
                    turnId = fixture.turnScope.turnId,
                    result = TurnResult(status = "COMPLETED", assistantReply = "过早完成")
                )
            }
        }
        assertTrue(
            store.eventsForTurn(fixture.turnScope.turnId)
                .none { it.eventKind == AgentEventKind.TURN_COMPLETED }
        )
    }

    @Test
    fun multiple_late_attachments_append_after_terminal_without_rewriting_finish() = runBlocking {
        val store = store("attachment-")
        val fixture = seedLocalTool(store, "attachment")
        val finish = store.finishTool(
            scope = fixture.toolScope,
            result = successfulResult(),
            roundDetailRef = fixture.roundDetailRef
        )
        store.completeTurn(
            threadId = fixture.turnScope.threadId,
            turnId = fixture.turnScope.turnId,
            result = TurnResult(status = "COMPLETED", assistantReply = "已完成")
        )

        listOf("before", "after").forEachIndexed { index, attachmentId ->
            store.addDetailAttachment(
                scope = fixture.toolScope,
                attachment = DetailAttachmentAddedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    attachmentId = attachmentId,
                    role = if (index == 0) {
                        DetailAttachmentRole.INPUT_OBSERVATION
                    } else {
                        DetailAttachmentRole.RESULT
                    },
                    contentHash = (if (index == 0) "a" else "b").repeat(64),
                    contentRef = "sha256:${(if (index == 0) "a" else "b").repeat(64)}",
                    sizeBytes = 1_024L + index,
                    mimeType = "image/png",
                    width = 1080,
                    height = 2400,
                    observationId = "observation-$index"
                )
            )
        }

        val events = store.eventsForThread(fixture.turnScope.threadId)
        assertEquals(
            finish.payloadHash,
            events.single { it.eventId == finish.eventId }.payloadHash
        )
        assertEquals(
            listOf("before", "after"),
            AgentEventReadModelProjector.project(events)
                .toolDetails.getValue(fixture.toolScope.callId)
                .attachments.map(DetailAttachmentReadModel::attachmentId)
        )
    }

    @Test
    fun finish_with_attachments_is_ordered_idempotent_and_survives_reopen() = runBlocking {
        val initialStore = store("atomic-")
        val fixture = seedLocalTool(initialStore, "atomic")
        val result = capturedResult("observation-atomic-result")
        val attachments = listOf(
            attachment("atomic-input", "a", DetailAttachmentRole.INPUT_OBSERVATION, 101L),
            attachment("atomic-result", "b", DetailAttachmentRole.RESULT, 202L)
        )

        val first = initialStore.finishToolWithAttachments(
            scope = fixture.toolScope,
            result = result,
            roundDetailRef = fixture.roundDetailRef,
            exclusiveDurationMillis = 12L,
            attachments = attachments
        )
        val retry = initialStore.finishToolWithAttachments(
            scope = fixture.toolScope,
            result = result,
            roundDetailRef = fixture.roundDetailRef,
            exclusiveDurationMillis = 12L,
            attachments = attachments
        )

        assertEquals(first, retry)
        assertEquals(
            listOf(
                AgentEventKind.TOOL_FINISHED,
                AgentEventKind.DETAIL_ATTACHMENT_ADDED,
                AgentEventKind.DETAIL_ATTACHMENT_ADDED
            ),
            initialStore.eventsForTurn(fixture.turnScope.turnId)
                .filter {
                    it.eventKind == AgentEventKind.TOOL_FINISHED ||
                        it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED
                }
                .map(AgentEventEnvelope::eventKind)
        )
        assertEquals(
            listOf(first.finishEvent.sequence + 1L, first.finishEvent.sequence + 2L),
            first.attachmentEvents.map(AgentEventEnvelope::sequence)
        )

        database.close()
        database = openDatabase()
        val reopened = store("reopened-").finishToolWithAttachments(
            scope = fixture.toolScope,
            result = result,
            roundDetailRef = fixture.roundDetailRef,
            exclusiveDurationMillis = 12L,
            attachments = attachments
        )

        assertEquals(first, reopened)
        assertEquals(
            listOf(101L, 202L),
            AgentEventReadModelProjector.project(
                store("projection-").eventsForThread(fixture.turnScope.threadId)
            ).toolDetails.getValue(fixture.toolScope.callId)
                .attachments.map(DetailAttachmentReadModel::sizeBytes)
        )
    }

    @Test
    fun every_settlement_insert_failure_rolls_back_finish_and_attachments() = runBlocking {
        (0..2).forEach { failAt ->
            val setupStore = store("setup-$failAt-")
            val fixture = seedLocalTool(setupStore, "rollback-$failAt")
            val failingStore = store("failing-$failAt-") { index, _ ->
                if (index == failAt) error("injected settlement failure at $failAt")
            }

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    failingStore.finishToolWithAttachments(
                        scope = fixture.toolScope,
                        result = successfulResult(),
                        roundDetailRef = fixture.roundDetailRef,
                        attachments = listOf(
                            attachment("rollback-$failAt-a", "a"),
                            attachment("rollback-$failAt-b", "b")
                        )
                    )
                }
            }
            assertTrue(
                setupStore.eventsForTurn(fixture.turnScope.turnId).none {
                    it.eventKind == AgentEventKind.TOOL_FINISHED ||
                        it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED
                }
            )
        }
    }

    @Test
    fun detail_attachment_requires_a_settled_tool_call() = runBlocking {
        val store = store("unsettled-")
        val fixture = seedLocalTool(store, "unsettled")

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.addDetailAttachment(
                    scope = fixture.toolScope,
                    attachment = attachment("unsettled", "a")
                )
            }
        }
        assertTrue(
            store.eventsForTurn(fixture.turnScope.turnId)
                .none { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
        )
    }

    @Test
    fun settlement_rejects_duplicate_attachment_ids_without_writing_finish() = runBlocking {
        val store = store("duplicate-attachment-")
        val fixture = seedLocalTool(store, "duplicate-attachment")
        val duplicate = attachment("duplicate", "a")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.finishToolWithAttachments(
                    scope = fixture.toolScope,
                    result = successfulResult(),
                    roundDetailRef = fixture.roundDetailRef,
                    attachments = listOf(duplicate, duplicate)
                )
            }
        }

        assertTrue(
            store.eventsForTurn(fixture.turnScope.turnId).none {
                it.eventKind == AgentEventKind.TOOL_FINISHED ||
                    it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED
            }
        )
    }

    @Test
    fun coordinated_lifecycle_facts_require_typed_store_apis() = runBlocking {
        val store = store("coordinated-gate-")
        val drafts = listOf(
            AgentEventDraft(
                eventKind = AgentEventKind.TURN_RECOVERY_REQUIRED,
                threadId = "thread",
                turnId = "turn",
                payloadJson = "{}",
                idempotencyKey = "turn:recovery"
            ),
            AgentEventDraft(
                eventKind = AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
                threadId = "thread",
                turnId = "turn",
                attemptId = "attempt",
                executionId = "execution",
                payloadJson = "{}",
                idempotencyKey = "attempt:state"
            ),
            AgentEventDraft(
                eventKind = AgentEventKind.TOOL_FINISHED,
                threadId = "thread",
                turnId = "turn",
                roundId = "round",
                callId = "call-finished",
                payloadJson = "{}",
                idempotencyKey = "call:finished"
            ),
            AgentEventDraft(
                eventKind = AgentEventKind.DETAIL_ATTACHMENT_ADDED,
                threadId = "thread",
                turnId = "turn",
                roundId = "round",
                callId = "call-attachment",
                payloadJson = "{}",
                idempotencyKey = "attachment:added"
            ),
            AgentEventDraft(
                eventKind = AgentEventKind.TURN_COMPLETED,
                threadId = "thread",
                turnId = "turn",
                payloadJson = "{}",
                idempotencyKey = "turn:completed"
            )
        )

        drafts.forEach { draft ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.append(draft) }
            }
            assertTrue(
                error.message.orEmpty().contains(
                    "Coordinated lifecycle events require their typed store API"
                )
            )
        }
    }

    @Test
    fun injected_finish_failure_leaves_no_partial_settlement() = runBlocking {
        val setup = store("setup-")
        val fixture = seedLocalTool(setup, "fault")
        val failing = store("failing-") { _, draft ->
            if (draft.eventKind == AgentEventKind.TOOL_FINISHED) {
                error("injected finish failure")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                failing.finishTool(
                    scope = fixture.toolScope,
                    result = successfulResult(),
                    roundDetailRef = fixture.roundDetailRef
                )
            }
        }
        assertTrue(
            setup.eventsForTurn(fixture.turnScope.turnId)
                .none { it.eventKind == AgentEventKind.TOOL_FINISHED }
        )
    }

    @Test
    fun attempt_transitions_pause_resume_and_terminal_state_are_strict() = runBlocking {
        val store = store("attempt-")
        val fixture = seedDeviceTool(store, "attempt")
        val attemptScope = checkNotNull(fixture.attemptScope)

        store.recordExecutionAttemptState(
            scope = attemptScope,
            transitionId = "running-1",
            state = ExecutionAttemptState.RUNNING
        )
        store.recordExecutionAttemptState(
            scope = attemptScope,
            transitionId = "recovery-pause",
            state = ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED,
            diagnosticCode = "PROCESS_RESTARTED"
        )
        store.recordExecutionAttemptState(
            scope = attemptScope,
            transitionId = "running-2",
            state = ExecutionAttemptState.RUNNING
        )
        store.finishTool(
            scope = fixture.toolScope,
            result = successfulResult(),
            roundDetailRef = fixture.roundDetailRef
        )
        store.recordExecutionAttemptState(
            scope = attemptScope,
            transitionId = "completed",
            state = ExecutionAttemptState.COMPLETED
        )
        store.completeTurn(
            threadId = fixture.turnScope.threadId,
            turnId = fixture.turnScope.turnId,
            result = TurnResult(status = "COMPLETED", assistantReply = "已完成")
        )

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.recordExecutionAttemptState(
                    scope = attemptScope,
                    transitionId = "illegal-resume",
                    state = ExecutionAttemptState.RUNNING
                )
            }
        }
        val projection = AgentEventReadModelProjector.project(
            store.eventsForThread(fixture.turnScope.threadId)
        )
        assertEquals(TurnReadModelState.TERMINAL, projection.turns.single().state)
        assertEquals(
            listOf(
                LifecycleActivityKind.ATTEMPT_PAUSED_RECOVERY_REQUIRED,
                LifecycleActivityKind.ATTEMPT_RESUMED
            ),
            projection.activityByTurn.getValue(fixture.turnScope.turnId)
                .entries.filterIsInstance<LifecycleActivityReadModel>()
                .map(LifecycleActivityReadModel::kind)
        )
    }

    @Test
    fun ensure_running_honors_expected_sequence_and_is_idempotent() {
        runBlocking {
            val store = store("ensure-running-")
            val fixture = seedDeviceTool(store, "ensure-running")
            val attemptScope = checkNotNull(fixture.attemptScope)
            val created = store.requireExecutionAttemptSnapshot(attemptScope)

            val first = store.ensureExecutionAttemptRunning(
                scope = attemptScope,
                transitionId = "round:ensure-running:running",
                expectedLifecycleSequence = created.lifecycleSequence
            )
            val retry = store.ensureExecutionAttemptRunning(
                scope = attemptScope,
                transitionId = "round:ensure-running:running",
                expectedLifecycleSequence = created.lifecycleSequence
            )

            assertEquals(ExecutionAttemptState.RUNNING, first.state)
            assertEquals(first.lifecycleSequence, retry.lifecycleSequence)
            assertEquals(
                1,
                store.eventsForTurn(fixture.turnScope.turnId)
                    .count { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED }
            )
            assertThrows(AgentEventConflictException::class.java) {
                runBlocking {
                    store.ensureExecutionAttemptRunning(
                        scope = attemptScope,
                        transitionId = "round:ensure-running:stale",
                        expectedLifecycleSequence = first.lifecycleSequence + 1L
                    )
                }
            }
        }
    }

    @Test
    fun process_recovery_ends_task_and_settles_started_call_without_replay() = runBlocking {
        val initial = store("recovery-")
        val fixture = seedDeviceTool(initial, "recovery")
        val attemptScope = checkNotNull(fixture.attemptScope)
        initial.recordExecutionAttemptState(
            scope = attemptScope,
            transitionId = "running",
            state = ExecutionAttemptState.RUNNING
        )
        initial.recordExecutionAttemptState(
            scope = attemptScope,
            transitionId = "paused-user",
            state = ExecutionAttemptState.PAUSED_USER
        )

        val before = initial.requireTurnRecoverySnapshot(fixture.turnScope)
        assertEquals(listOf(fixture.turnScope), initial.unfinishedTurnScopesForRecovery())
        assertEquals(ExecutionAttemptState.PAUSED_USER, before.activeAttempts.single().state)
        assertEquals(fixture.toolScope.callId, before.pendingTools.single().scope.callId)

        database.close()
        database = openDatabase()
        val reopened = store("reopened-recovery-")
        val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AgentProcessRecoveryCoordinator(reopened, recoveryScope) {
            context.getString(com.droidance.R.string.execution_interrupted_result)
        }.awaitReady()

        val recoveredEvents = reopened.eventsForTurn(fixture.turnScope.turnId)
        val recoveryEvent = recoveredEvents.single {
            it.eventKind == AgentEventKind.TURN_RECOVERY_REQUIRED
        }
        val attemptStateEvent = recoveredEvents.last {
            it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED
        }
        val toolFinishEvent = recoveredEvents.single {
            it.eventKind == AgentEventKind.TOOL_FINISHED
        }
        assertTrue(
            recoveryEvent.sequence < toolFinishEvent.sequence &&
                toolFinishEvent.sequence < attemptStateEvent.sequence
        )
        val finished = Json.decodeFromString<ToolFinishedEventPayload>(
            toolFinishEvent.payloadJson
        )
        assertEquals(ToolExecutionStatus.INTERRUPTED, finished.result.executionStatus)
        assertEquals(ToolEvidenceStatus.UNKNOWN, finished.result.evidenceStatus)
        assertEquals(AgentRecoveryDiagnosticCode.PROCESS_RECOVERY, finished.result.diagnosticCode)
        assertEquals(ExecutionAttemptState.INTERRUPTED,
            reopened.requireExecutionAttemptSnapshot(attemptScope).state)
        assertTrue(reopened.unfinishedTurnScopesForRecovery().isEmpty())
        val projection = AgentEventReadModelProjector.project(reopened.eventsForThread(fixture.turnScope.threadId))
        assertEquals(TurnReadModelState.TERMINAL, projection.turns.single().state)
        assertTrue(projection.conversation.entries.any {
            it.kind == ConversationEntryKind.TURN_SYSTEM_NOTICE &&
                it.text == context.getString(com.droidance.R.string.execution_interrupted_result)
        })

        val eventCount = reopened.count()
        recoveryScope.cancel()
        val nextProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AgentProcessRecoveryCoordinator(reopened, nextProcessScope) {
            context.getString(com.droidance.R.string.execution_interrupted_result)
        }.awaitReady()
        nextProcessScope.cancel()
        assertEquals(eventCount, reopened.count())
    }

    @Test
    fun process_recovery_transaction_rolls_back_pause_and_finish_together() = runBlocking {
        val initial = store("recovery-rollback-")
        val fixture = seedDeviceTool(initial, "recovery-rollback")
        val attemptScope = checkNotNull(fixture.attemptScope)
        initial.recordExecutionAttemptState(
            scope = attemptScope,
            transitionId = "running",
            state = ExecutionAttemptState.RUNNING
        )
        val failing = store("failing-recovery-") { index, _ ->
            if (index == 2) error("injected recovery finish failure")
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                failing.recoverInterruptedTurn(
                    scope = fixture.turnScope,
                    recoveryId = "process-failed"
                )
            }
        }

        val durable = initial.requireTurnRecoverySnapshot(fixture.turnScope)
        assertEquals(ExecutionAttemptState.RUNNING, durable.activeAttempts.single().state)
        assertEquals(1, durable.pendingTools.size)
        assertTrue(
            initial.eventsForTurn(fixture.turnScope.turnId).none { event ->
                event.eventKind == AgentEventKind.TOOL_FINISHED ||
                    event.eventKind == AgentEventKind.TURN_RECOVERY_REQUIRED ||
                    event.idempotencyKey.endsWith(":recovery:process-failed")
            }
        )
    }

    @Test
    fun process_recovery_settles_turn_local_call_without_fabricating_attempt() = runBlocking {
        val store = store("local-recovery-")
        val fixture = seedLocalTool(store, "local-recovery")

        val commit = store.recoverInterruptedTurn(
            scope = fixture.turnScope,
            recoveryId = "local-process"
        )

        assertTrue(commit.attemptStateEvents.isEmpty())
        assertEquals(1, commit.toolFinishEvents.size)
        assertEquals(null, commit.toolFinishEvents.single().attemptId)
        assertEquals(null, commit.toolFinishEvents.single().executionId)
        assertTrue(store.requireTurnRecoverySnapshot(fixture.turnScope).pendingTools.isEmpty())
        val projection = AgentEventReadModelProjector.project(
            store.eventsForThread(fixture.turnScope.threadId)
        )
        assertEquals(TurnReadModelState.PAUSED_RECOVERY_REQUIRED, projection.turns.single().state)
    }

    @Test
    fun startup_finishes_an_already_paused_local_task_once() = runBlocking {
        val store = store("old-pause-")
        val fixture = seedLocalTool(store, "old-pause")
        store.recoverInterruptedTurn(fixture.turnScope, "previous-process")
        repeat(2) {
            val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                AgentProcessRecoveryCoordinator(store, processScope) {
                    context.getString(com.droidance.R.string.execution_interrupted_result)
                }.awaitReady()
            } finally { processScope.cancel() }
        }
        val events = store.eventsForTurn(fixture.turnScope.turnId)
        assertEquals(1, events.count { it.eventKind == AgentEventKind.TURN_COMPLETED })
        assertEquals(1, events.count { it.eventKind == AgentEventKind.TOOL_FINISHED })
        assertTrue(store.unfinishedTurnScopesForRecovery().isEmpty())
    }

    private fun openDatabase(): AgentEventDatabase = Room.databaseBuilder(
        context,
        AgentEventDatabase::class.java,
        databaseName
    ).build()

    @Test
    fun interruption_completion_failure_rolls_back_all_settlement() = runBlocking {
        val initial = store("terminal-rollback-")
        val fixture = seedDeviceTool(initial, "terminal-rollback")
        val failing = store("terminal-failing-") { _, event ->
            if (event.eventKind == AgentEventKind.TURN_COMPLETED) error("injected terminal failure")
        }
        val before = initial.count()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { failing.terminateInterruptedTurn(fixture.turnScope, "process", "interrupted") }
        }
        assertEquals(before, initial.count())
        assertEquals(1, initial.requireTurnRecoverySnapshot(fixture.turnScope).pendingTools.size)
        initial.terminateInterruptedTurn(fixture.turnScope, "process", "interrupted")
        val after = initial.count()
        initial.terminateInterruptedTurn(fixture.turnScope, "process", "interrupted")
        assertEquals(after, initial.count())
        assertTrue(initial.unfinishedTurnScopesForRecovery().isEmpty())
    }

    private fun store(
        idPrefix: String,
        beforeInsert: (Int, AgentEventDraft) -> Unit = { _, _ -> }
    ): RoomAgentEventStore {
        var id = 0
        var time = 1_000L
        return RoomAgentEventStore(
            database = database,
            nowMs = { time++ },
            eventIdFactory = { "$idPrefix${id++}" },
            beforeInsert = beforeInsert
        )
    }

    private suspend fun seedLocalTool(
        store: RoomAgentEventStore,
        suffix: String,
        argumentsJson: String = "{\"value\":1}"
    ): Fixture {
        val turnScope = TurnEventScope("thread-$suffix", "turn-$suffix")
        val roundId = "round-$suffix"
        val roundDetailRef = "round-detail:$roundId"
        store.createThread(turnScope.threadId, "Droidance")
        store.startTurn(
            scope = turnScope,
            modelSessionIdentity = modelIdentity(turnScope.threadId),
            providerConfigSnapshotRef = "provider-config"
        )
        val user = store.recordUserMessage(
            scope = turnScope,
            messageId = "message-$suffix",
            text = "request"
        )
        val range = SubmittedItemSequenceRange(user.sequence, user.sequence)
        val modelScope = ModelRoundScope(turnScope.threadId, turnScope.turnId, roundId)
        store.recordModelRoundStarted(
            PreparedModelRoundStart.create(
                scope = modelScope,
                repairPurpose = RepairPurpose.NONE,
                startCommitted = false,
                submittedItems = range,
                roundDetailRef = roundDetailRef
            )
        )
        val normalized = NormalizedModelRound.create(
            publicBlocks = listOf("开始处理"),
            toolCalls = listOf(
                ProviderToolCall(
                    providerCallId = "provider-call-$suffix",
                    name = TOOL_NAME,
                    argumentsJson = argumentsJson
                )
            )
        )
        val prepared = ExecutionRoundPlanner(
            registrations = listOf(
                RegisteredToolContract(
                    name = TOOL_NAME,
                    description = "Update local test state",
                    binding = ToolBinding.TURN_LOCAL,
                    effect = ToolEffect.STATE_CHANGING,
                    parameters = emptyMap()
                )
            ),
            capabilityRequirements = emptyList(),
            callIdFactory = { _, _ -> "call-$suffix" }
        ).plan(normalized, startCommitted = false, repairPurpose = RepairPurpose.NONE)
        store.recordModelRoundPlan(
            PreparedModelRoundCommit.create(
                scope = modelScope,
                expectedStartCommitted = false,
                repairPurpose = RepairPurpose.NONE,
                submittedItems = range,
                roundDetailRef = roundDetailRef,
                normalizedRound = normalized,
                preparedExecution = prepared,
                presentationsByCallId = mapOf(
                    "call-$suffix" to ToolPresentationSnapshot(
                        labelResourceKey = "tool.local_write",
                        category = ToolPresentationCategory.PRIMARY,
                        resultImageRole = ResultImageRole.BEFORE_AND_AFTER
                    )
                )
            )
        )
        return Fixture(
            turnScope = turnScope,
            toolScope = ToolLifecycleEventScope(
                threadId = turnScope.threadId,
                turnId = turnScope.turnId,
                roundId = roundId,
                callId = "call-$suffix"
            ),
            roundDetailRef = roundDetailRef
        )
    }

    private suspend fun seedDeviceTool(
        store: RoomAgentEventStore,
        suffix: String,
        argumentsJson: String = "{\"value\":1}"
    ): Fixture {
        val turnScope = TurnEventScope("thread-$suffix", "turn-$suffix")
        val roundId = "round-$suffix"
        val roundDetailRef = "round-detail:$roundId"
        val attemptId = "attempt-$suffix"
        val executionId = "execution-$suffix"
        store.createThread(turnScope.threadId, "Droidance")
        store.startTurn(
            scope = turnScope,
            modelSessionIdentity = modelIdentity(turnScope.threadId),
            providerConfigSnapshotRef = "provider-config"
        )
        val user = store.recordUserMessage(
            scope = turnScope,
            messageId = "message-$suffix",
            text = "request"
        )
        val range = SubmittedItemSequenceRange(user.sequence, user.sequence)
        val modelScope = ModelRoundScope(turnScope.threadId, turnScope.turnId, roundId)
        store.recordModelRoundStarted(
            PreparedModelRoundStart.create(
                scope = modelScope,
                repairPurpose = RepairPurpose.NONE,
                startCommitted = false,
                submittedItems = range,
                roundDetailRef = roundDetailRef
            )
        )
        val normalized = NormalizedModelRound.create(
            publicBlocks = listOf("开始点击"),
            toolCalls = listOf(
                ProviderToolCall(
                    providerCallId = "provider-call-$suffix",
                    name = DEVICE_TOOL_NAME,
                    argumentsJson = "{\"x\":10,\"y\":20}"
                )
            )
        )
        val snapshot = CapabilitySnapshot.create(
            capabilitySnapshotRef = "capability-$suffix",
            runtimeMode = RuntimeMode.ULTRA,
            executionSpace = ExecutionSpace.MAIN_SCREEN,
            displayId = 0,
            backendKind = ExecutionBackendKind.ROOT_BACKEND,
            backendSessionId = "backend-$suffix",
            backendAvailable = true,
            capabilities = setOf(DeviceCapability.CLICK)
        )
        val binding = FrozenAttemptBindingFactory.createNew(
            attemptId = attemptId,
            executionId = executionId,
            cancellationScopeId = "cancel-$suffix",
            capabilitySnapshot = snapshot,
            backendInventory = BackendInventory.of(
                BackendEndpointSnapshot(
                    backendKind = ExecutionBackendKind.ROOT_BACKEND,
                    backendSessionId = "backend-$suffix",
                    displayId = 0,
                    available = true
                )
            )
        ) as AttemptBindingResolution.Ready
        val prepared = ExecutionRoundPlanner(
            registrations = listOf(
                RegisteredToolContract(
                    name = DEVICE_TOOL_NAME,
                    description = "Click a test coordinate",
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING,
                    parameters = emptyMap()
                )
            ),
            capabilityRequirements = listOf(
                DeviceToolCapabilityRequirement(DEVICE_TOOL_NAME, DeviceCapability.CLICK)
            ),
            callIdFactory = { _, _ -> "call-$suffix" }
        ).plan(
            normalized,
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = { binding }
        )
        store.recordModelRoundPlan(
            PreparedModelRoundCommit.create(
                scope = modelScope,
                expectedStartCommitted = false,
                repairPurpose = RepairPurpose.NONE,
                submittedItems = range,
                roundDetailRef = roundDetailRef,
                normalizedRound = normalized,
                preparedExecution = prepared,
                presentationsByCallId = mapOf(
                    "call-$suffix" to ToolPresentationSnapshot(
                        labelResourceKey = "tool.click",
                        category = ToolPresentationCategory.PRIMARY,
                        resultImageRole = ResultImageRole.BEFORE_AND_AFTER
                    )
                ),
                publicMessageId = "assistant-start-$suffix"
            )
        )
        return Fixture(
            turnScope = turnScope,
            toolScope = ToolLifecycleEventScope(
                threadId = turnScope.threadId,
                turnId = turnScope.turnId,
                roundId = roundId,
                callId = "call-$suffix",
                attemptId = attemptId,
                executionId = executionId
            ),
            roundDetailRef = roundDetailRef,
            attemptScope = ExecutionAttemptEventScope(
                threadId = turnScope.threadId,
                turnId = turnScope.turnId,
                attemptId = attemptId,
                executionId = executionId
            )
        )
    }

    private fun modelIdentity(threadId: String) = ModelSessionIdentity(
        threadId = threadId,
        providerId = "provider",
        transportKind = ModelTransport.OPENAI_RESPONSES,
        transportConfigHash = "1".repeat(64),
        authScopeHash = "2".repeat(64),
        modelId = "model",
        stablePrefixHash = "3".repeat(64)
    )

    private fun successfulResult() = ToolExecutionResult(
        outputText = "ok",
        executionStatus = ToolExecutionStatus.SUCCEEDED,
        evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
    )

    private fun capturedResult(observationId: String) = ToolExecutionResult(
        outputText = "ok",
        executionStatus = ToolExecutionStatus.SUCCEEDED,
        evidenceStatus = ToolEvidenceStatus.CAPTURED,
        observationId = observationId
    )

    private fun attachment(
        attachmentId: String,
        hashDigit: String,
        role: DetailAttachmentRole = DetailAttachmentRole.RESULT,
        sizeBytes: Long = 1L
    ): DetailAttachmentAddedEventPayload {
        val contentHash = hashDigit.repeat(64)
        return DetailAttachmentAddedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            attachmentId = attachmentId,
            role = role,
            contentRef = "sha256:$contentHash",
            contentHash = contentHash,
            sizeBytes = sizeBytes,
            mimeType = "image/png",
            width = 1080,
            height = 2400,
            observationId = "observation-$attachmentId"
        )
    }

    private data class Fixture(
        val turnScope: TurnEventScope,
        val toolScope: ToolLifecycleEventScope,
        val roundDetailRef: String,
        val attemptScope: ExecutionAttemptEventScope? = null
    )

    private companion object {
        const val TOOL_NAME = "local_write"
        const val DEVICE_TOOL_NAME = "click"
    }
}
