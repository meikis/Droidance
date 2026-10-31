// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.persistence.agent.event.*

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.droidance.core.agent.attempt.PreparedRoundExecutionPlan
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.ToolArgumentSchemaDiagnosticCode
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomModelRoundPlanStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AgentEventDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "model-round-plan-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun three_call_plan_rolls_back_when_the_second_rejection_insert_fails() = runBlocking {
        val setupStore = store(idPrefix = "setup-")
        val seed = seedRound(setupStore, suffix = "three-call-fault")
        val plan = localThreeCallPlan(seed)
        var rejectedInsertCount = 0
        val failingStore = store(
            idPrefix = "failing-",
            beforeInsert = { _, draft ->
                if (draft.eventKind == AgentEventKind.TOOL_CALL_REJECTED) {
                    rejectedInsertCount += 1
                    if (rejectedInsertCount == 2) error("injected second rejection failure")
                }
            }
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { failingStore.recordModelRoundPlan(plan) }
        }

        assertEquals(2, rejectedInsertCount)
        assertEquals(
            listOf(
                AgentEventKind.TURN_STARTED,
                AgentEventKind.USER_MESSAGE,
                AgentEventKind.MODEL_ROUND_STARTED
            ),
            setupStore.eventsForTurn(seed.scope.turnId).map(AgentEventEnvelope::eventKind)
        )
    }

    @Test
    fun exact_start_and_plan_retry_survive_database_reopen_without_new_facts() = runBlocking {
        val firstStore = store(idPrefix = "first-")
        val seed = seedRound(firstStore, suffix = "retry")
        val retriedStart = firstStore.recordModelRoundStarted(seed.start)
        assertEquals(seed.startEvent.eventId, retriedStart.eventId)

        val plan = localThreeCallPlan(seed)
        val firstResult = firstStore.recordModelRoundPlan(plan)
        assertTrue(firstResult is NewlyCommittedModelRoundPlan)
        val first = firstResult.recorded
        val firstEvents = firstStore.eventsForTurn(seed.scope.turnId)
        val retryResult = firstStore.recordModelRoundPlan(plan)
        assertTrue(retryResult is AlreadyCommittedModelRoundPlan)
        val retry = retryResult.recorded

        assertEquals(first.completed.eventId, retry.completed.eventId)
        assertEquals(firstEvents, firstStore.eventsForTurn(seed.scope.turnId))

        database.close()
        database = openDatabase()
        val reopenedStore = store(idPrefix = "reopened-")
        val reopenedResult = reopenedStore.recordModelRoundPlan(plan)
        assertTrue(reopenedResult is AlreadyCommittedModelRoundPlan)
        val reopened = reopenedResult.recorded

        assertEquals(first.completed.eventId, reopened.completed.eventId)
        assertEquals(firstEvents, reopenedStore.eventsForTurn(seed.scope.turnId))
    }

    @Test
    fun concurrent_plan_commits_publish_exactly_one_fresh_result() = runBlocking {
        val setupStore = store(idPrefix = "concurrent-setup-")
        val seed = seedRound(setupStore, suffix = "concurrent")
        val plan = localThreeCallPlan(seed)
        val stores = listOf(
            store(idPrefix = "concurrent-a-"),
            store(idPrefix = "concurrent-b-")
        )

        val results = stores.map { eventStore ->
            async(Dispatchers.Default) {
                eventStore.recordModelRoundPlan(plan)
            }
        }.awaitAll()

        assertEquals(1, results.count { it is NewlyCommittedModelRoundPlan })
        assertEquals(1, results.count { it is AlreadyCommittedModelRoundPlan })
        assertEquals(1, results.map { it.recorded.completed.eventId }.distinct().size)
    }

    @Test
    fun newly_committed_plan_execution_can_be_claimed_only_once_concurrently() = runBlocking {
        val eventStore = store(idPrefix = "claim-")
        val seed = seedRound(eventStore, suffix = "claim")
        val fresh = eventStore.recordModelRoundPlan(localThreeCallPlan(seed))
        assertTrue(fresh is NewlyCommittedModelRoundPlan)
        fresh as NewlyCommittedModelRoundPlan

        val claims = (0 until 32).map {
            async(Dispatchers.Default) {
                runCatching { fresh.claimForExecution() }
            }
        }.awaitAll()

        assertEquals(1, claims.count { it.isSuccess })
        assertEquals(
            fresh.recorded.completed.eventId,
            claims.single { it.isSuccess }.getOrThrow().completed.eventId
        )
        assertTrue(
            claims.filter { it.isFailure }.all {
                it.exceptionOrNull() is IllegalStateException
            }
        )
    }

    @Test
    fun every_start_response_insert_point_rolls_back_without_public_attempt_or_started_facts() =
        runBlocking {
            (0 until START_RESPONSE_EVENT_COUNT).forEach { failAt ->
                val suffix = "start-fault-$failAt"
                val setupStore = store(idPrefix = "$suffix-setup-")
                val seed = seedRound(setupStore, suffix)
                val plan = devicePlan(
                    seed = seed,
                    attemptId = "$ATTEMPT_ID-$failAt",
                    executionId = "$EXECUTION_ID-$failAt",
                    cancellationScopeId = "$CANCELLATION_SCOPE_ID-$failAt"
                )
                assertNotNull(plan.preparedExecution.attempt)
                assertEquals(1, plan.preparedExecution.toolStarts.size)
                val failingStore = store(
                    idPrefix = "$suffix-failing-",
                    beforeInsert = { index, _ ->
                        if (index == failAt) error("injected plan insert failure at $index")
                    }
                )

                assertThrows(IllegalStateException::class.java) {
                    runBlocking { failingStore.recordModelRoundPlan(plan) }
                }

                val events = setupStore.eventsForTurn(seed.scope.turnId)
                assertEquals(
                    listOf(
                        AgentEventKind.TURN_STARTED,
                        AgentEventKind.USER_MESSAGE,
                        AgentEventKind.MODEL_ROUND_STARTED
                    ),
                    events.map(AgentEventEnvelope::eventKind)
                )
                assertFalse(events.any { it.eventKind == AgentEventKind.ASSISTANT_MESSAGE })
                assertFalse(events.any { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
                assertFalse(events.any { it.eventKind == AgentEventKind.TOOL_STARTED })
                assertTrue(
                    setupStore.recordModelRoundPlan(plan) is NewlyCommittedModelRoundPlan
                )
            }
        }

    @Test
    fun device_plan_persists_complete_attempt_scope_before_started() = runBlocking {
        val eventStore = store(idPrefix = "device-")
        val seed = seedRound(eventStore, suffix = "device")
        val plan = devicePlan(seed)

        val result = eventStore.recordModelRoundPlan(plan)
        assertTrue(result is NewlyCommittedModelRoundPlan)
        val recorded = result.recorded
        val attemptEvent = checkNotNull(recorded.attemptCreated)
        val startedEvent = recorded.starts.single().event
        val payload = Json.decodeFromString<ExecutionAttemptCreatedEventPayload>(
            attemptEvent.payloadJson
        )

        assertTrue(attemptEvent.sequence < startedEvent.sequence)
        assertEquals(seed.scope.threadId, attemptEvent.threadId)
        assertEquals(seed.scope.turnId, attemptEvent.turnId)
        assertEquals(seed.scope.roundId, attemptEvent.roundId)
        assertEquals(ATTEMPT_ID, attemptEvent.attemptId)
        assertEquals(EXECUTION_ID, attemptEvent.executionId)
        assertEquals(ATTEMPT_ID, startedEvent.attemptId)
        assertEquals(EXECUTION_ID, startedEvent.executionId)
        assertEquals(EXECUTION_ID, payload.context.executionId)
        assertEquals(RuntimeMode.ULTRA, payload.context.runtimeMode)
        assertEquals(ExecutionSpace.MAIN_SCREEN, payload.context.executionSpace)
        assertEquals(0, payload.context.displayId)
        assertEquals(BACKEND_SESSION_ID, payload.context.backendSessionId)
        assertEquals(CAPABILITY_SNAPSHOT_REF, payload.context.capabilitySnapshotRef)
        assertEquals(CANCELLATION_SCOPE_ID, payload.context.cancellationScopeId)
        assertEquals(
            payload.capabilitySnapshot.capabilitySnapshotHash,
            payload.context.capabilitySnapshotHash
        )
        assertEquals(ExecutionBackendKind.ROOT_BACKEND, payload.capabilitySnapshot.backendKind)
        assertEquals(listOf(DeviceCapability.CLICK.name), payload.capabilitySnapshot.capabilities)
        assertTrue(recorded.outcomes.none { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED })
    }

    @Test
    fun second_round_reuses_the_same_running_attempt_without_new_creation() = runBlocking {
        val eventStore = store(idPrefix = "reuse-")
        val fixture = seedRunningDeviceAttempt(eventStore, "reuse")
        val continuation = seedContinuationRound(eventStore, fixture, "reuse-next")
        val plan = devicePlan(
            continuation,
            FrozenAttemptBindingFactory.restoreExisting(fixture.snapshot, backendInventory())
        )

        val result = eventStore.recordModelRoundPlan(plan)
        assertTrue(result is NewlyCommittedModelRoundPlan)
        val recorded = result.recorded
        val started = recorded.starts.single().event
        val completion = Json.decodeFromString<ModelRoundCompletedEventPayload>(
            recorded.completed.payloadJson
        )

        assertNull(recorded.attemptCreated)
        assertEquals(fixture.attemptScope.attemptId, started.attemptId)
        assertEquals(fixture.attemptScope.executionId, started.executionId)
        assertEquals(fixture.snapshot.lifecycleSequence, completion.attemptLifecycleSequence)
        assertEquals(
            1,
            eventStore.eventsForTurn(fixture.turnScope.turnId)
                .count { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED }
        )
    }

    @Test
    fun existing_attempt_plan_rejects_non_running_and_stale_running_lifecycle() = runBlocking {
        listOf(false, true).forEach { resumeBeforeCommit ->
            val suffix = if (resumeBeforeCommit) "stale-running" else "not-running"
            val eventStore = store(idPrefix = "$suffix-")
            val fixture = seedRunningDeviceAttempt(eventStore, suffix)
            val continuation = seedContinuationRound(eventStore, fixture, "$suffix-next")
            val plan = devicePlan(
                continuation,
                FrozenAttemptBindingFactory.restoreExisting(fixture.snapshot, backendInventory())
            )

            eventStore.recordExecutionAttemptState(
                scope = fixture.attemptScope,
                transitionId = "$suffix-pause",
                state = ExecutionAttemptState.PAUSED_USER
            )
            if (resumeBeforeCommit) {
                eventStore.recordExecutionAttemptState(
                    scope = fixture.attemptScope,
                    transitionId = "$suffix-resume",
                    state = ExecutionAttemptState.RUNNING
                )
            }

            assertThrows(AgentEventConflictException::class.java) {
                runBlocking { eventStore.recordModelRoundPlan(plan) }
            }
            assertRoundHasOnlyRequestBoundary(eventStore, continuation.scope.roundId)
        }
    }

    @Test
    fun existing_attempt_plan_rejects_multiple_active_and_non_latest_active_topologies() =
        runBlocking {
            listOf(false, true).forEach { makeInjectedAttemptTerminal ->
                val suffix = if (makeInjectedAttemptTerminal) "non-latest" else "multi-active"
                val eventStore = store(idPrefix = "$suffix-")
                val fixture = seedRunningDeviceAttempt(eventStore, suffix)
                val continuation = seedContinuationRound(eventStore, fixture, "$suffix-next")
                val plan = devicePlan(
                    continuation,
                    FrozenAttemptBindingFactory.restoreExisting(fixture.snapshot, backendInventory())
                )
                insertRawAttempt(
                    sourceAttempt = fixture.attemptScope,
                    suffix = "$suffix-injected",
                    terminal = makeInjectedAttemptTerminal
                )

                assertThrows(AgentEventConflictException::class.java) {
                    runBlocking { eventStore.recordModelRoundPlan(plan) }
                }
                assertRoundHasOnlyRequestBoundary(eventStore, continuation.scope.roundId)
            }
        }

    @Test
    fun turn_local_plan_has_no_attempt_and_rejections_materialize_not_executed_in_provider_order() =
        runBlocking {
            val eventStore = store(idPrefix = "local-")
            val seed = seedRound(eventStore, suffix = "local")
            val result = eventStore.recordModelRoundPlan(localThreeCallPlan(seed))
            assertTrue(result is NewlyCommittedModelRoundPlan)
            val recorded = result.recorded

            assertNull(recorded.attemptCreated)
            assertEquals(
                listOf("${seed.scope.roundId}-call-0", "${seed.scope.roundId}-call-1", "${seed.scope.roundId}-call-2"),
                recorded.toolCalls.map { it.callId }
            )
            assertEquals(
                listOf(
                    AgentEventKind.TOOL_STARTED,
                    AgentEventKind.TOOL_CALL_REJECTED,
                    AgentEventKind.TOOL_CALL_REJECTED
                ),
                recorded.outcomes.map(AgentEventEnvelope::eventKind)
            )
            assertTrue(recorded.toolCalls.all { it.attemptId == null && it.executionId == null })
            val rejectedPayloads = recorded.outcomes
                .filter { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }
                .map { Json.decodeFromString<ToolCallRejectedEventPayload>(it.payloadJson) }
            rejectedPayloads.forEach { payload ->
                assertEquals(ToolExecutionStatus.NOT_EXECUTED, payload.result.executionStatus)
                assertEquals(ToolEvidenceStatus.NOT_REQUIRED, payload.result.evidenceStatus)
                assertEquals(ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH, payload.result.diagnosticCode)
                assertNull(payload.result.observationId)
            }
        }

    private fun openDatabase(): AgentEventDatabase = Room.databaseBuilder(
        context,
        AgentEventDatabase::class.java,
        databaseName
    ).build()

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

    private suspend fun seedRound(store: RoomAgentEventStore, suffix: String): SeedRound {
        val scope = ModelRoundScope(
            threadId = "thread-$suffix",
            turnId = "turn-$suffix",
            roundId = "round-$suffix"
        )
        store.createThread(scope.threadId, "Droidance")
        store.startTurn(
            scope = TurnEventScope(scope.threadId, scope.turnId),
            modelSessionIdentity = ModelSessionIdentity(
                threadId = scope.threadId,
                providerId = "provider",
                transportKind = ModelTransport.OPENAI_RESPONSES,
                transportConfigHash = "1".repeat(64),
                authScopeHash = "2".repeat(64),
                modelId = "model",
                stablePrefixHash = "3".repeat(64)
            ),
            providerConfigSnapshotRef = "provider-config"
        )
        val submittedItem = store.recordUserMessage(
            scope = TurnEventScope(scope.threadId, scope.turnId),
            messageId = "message:${scope.turnId}:user",
            text = "request"
        )
        val submittedItems = SubmittedItemSequenceRange(
            firstSequence = submittedItem.sequence,
            lastSequence = submittedItem.sequence
        )
        val start = PreparedModelRoundStart.create(
            scope = scope,
            repairPurpose = RepairPurpose.NONE,
            startCommitted = false,
            submittedItems = submittedItems,
            roundDetailRef = "detail:${scope.roundId}"
        )
        val startEvent = store.recordModelRoundStarted(start)
        return SeedRound(scope, submittedItems, start, startEvent)
    }

    private fun localThreeCallPlan(seed: SeedRound): PreparedModelRoundCommit {
        val normalized = NormalizedModelRound.create(
            publicBlocks = listOf("Starting"),
            toolCalls = List(3) { index ->
                ProviderToolCall(
                    providerCallId = "provider-${seed.scope.roundId}-$index",
                    name = LOCAL_ACTION_NAME,
                    argumentsJson = if (index == 0) "{}" else "[]"
                )
            }
        )
        val prepared = ExecutionRoundPlanner(
            registrations = listOf(
                RegisteredToolContract(
                    name = LOCAL_ACTION_NAME,
                    description = "Update local test state",
                    binding = ToolBinding.TURN_LOCAL,
                    effect = ToolEffect.STATE_CHANGING,
                    parameters = emptyMap()
                )
            ),
            capabilityRequirements = emptyList(),
            callIdFactory = { index, _ -> "${seed.scope.roundId}-call-$index" }
        ).plan(
            round = normalized,
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )
        return roundCommit(seed, normalized, prepared)
    }

    private fun devicePlan(
        seed: SeedRound,
        attemptId: String = ATTEMPT_ID,
        executionId: String = EXECUTION_ID,
        cancellationScopeId: String = CANCELLATION_SCOPE_ID
    ): PreparedModelRoundCommit = devicePlan(
        seed = seed,
        binding = FrozenAttemptBindingFactory.createNew(
            attemptId = attemptId,
            executionId = executionId,
            cancellationScopeId = cancellationScopeId,
            capabilitySnapshot = capabilitySnapshot(),
            backendInventory = backendInventory()
        ) as AttemptBindingResolution.Ready
    )

    private fun devicePlan(
        seed: SeedRound,
        binding: AttemptBindingResolution.Ready
    ): PreparedModelRoundCommit {
        val normalized = NormalizedModelRound.create(
            publicBlocks = listOf("Starting"),
            toolCalls = listOf(
                ProviderToolCall(
                    providerCallId = "provider-${seed.scope.roundId}",
                    name = DEVICE_ACTION_NAME,
                    argumentsJson = "{\"x\":1,\"y\":2}"
                )
            )
        )
        val prepared = ExecutionRoundPlanner(
            registrations = listOf(
                RegisteredToolContract(
                    name = DEVICE_ACTION_NAME,
                    description = "Click a test coordinate",
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING,
                    parameters = emptyMap()
                )
            ),
            capabilityRequirements = listOf(
                DeviceToolCapabilityRequirement(DEVICE_ACTION_NAME, DeviceCapability.CLICK)
            ),
            callIdFactory = { index, _ -> "${seed.scope.roundId}-call-$index" }
        ).plan(
            round = normalized,
            startCommitted = seed.start.startCommitted,
            repairPurpose = RepairPurpose.NONE,
            attemptBindingProvider = { binding }
        )
        return roundCommit(
            seed,
            normalized,
            prepared,
            publicMessageId = "message:${seed.scope.roundId}:start"
                .takeIf { !seed.start.startCommitted }
        )
    }

    private fun roundCommit(
        seed: SeedRound,
        normalized: NormalizedModelRound,
        prepared: PreparedRoundExecutionPlan,
        publicMessageId: String? = null
    ): PreparedModelRoundCommit {
        val calls = prepared.roundPlan.selectedCalls +
            prepared.roundPlan.rejectedCalls.map { it.call }
        val presentations = calls.associate { call ->
            call.callId to ToolPresentationSnapshot(
                labelResourceKey = "tool.${call.providerCall.name}",
                category = ToolPresentationCategory.PRIMARY,
                resultImageRole = ResultImageRole.NONE
            )
        }
        return PreparedModelRoundCommit.create(
            scope = seed.scope,
            expectedStartCommitted = seed.start.startCommitted,
            repairPurpose = RepairPurpose.NONE,
            submittedItems = seed.submittedItems,
            roundDetailRef = "detail:${seed.scope.roundId}",
            normalizedRound = normalized,
            preparedExecution = prepared,
            presentationsByCallId = presentations,
            publicMessageId = publicMessageId
        )
    }

    private suspend fun seedRunningDeviceAttempt(
        store: RoomAgentEventStore,
        suffix: String
    ): RunningAttemptFixture {
        val seed = seedRound(store, suffix)
        val binding = FrozenAttemptBindingFactory.createNew(
            attemptId = "attempt-$suffix",
            executionId = "execution-$suffix",
            cancellationScopeId = "cancel-$suffix",
            capabilitySnapshot = capabilitySnapshot(),
            backendInventory = backendInventory()
        ) as AttemptBindingResolution.Ready
        val recorded = store.recordModelRoundPlan(devicePlan(seed, binding)).recorded
        val creation = checkNotNull(recorded.attemptCreated)
        val attemptScope = ExecutionAttemptEventScope(
            threadId = seed.scope.threadId,
            turnId = seed.scope.turnId,
            attemptId = checkNotNull(creation.attemptId),
            executionId = checkNotNull(creation.executionId)
        )
        val created = store.requireExecutionAttemptSnapshot(attemptScope)
        val running = store.ensureExecutionAttemptRunning(
            scope = attemptScope,
            transitionId = "round:${seed.scope.roundId}:running",
            expectedLifecycleSequence = created.lifecycleSequence
        )
        val finish = store.finishTool(
            scope = ToolLifecycleEventScope(
                threadId = seed.scope.threadId,
                turnId = seed.scope.turnId,
                roundId = seed.scope.roundId,
                callId = "${seed.scope.roundId}-call-0",
                attemptId = attemptScope.attemptId,
                executionId = attemptScope.executionId
            ),
            result = ToolExecutionResult(
                outputText = "ok",
                executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
            ),
            roundDetailRef = "detail:${seed.scope.roundId}"
        )
        return RunningAttemptFixture(
            turnScope = TurnEventScope(seed.scope.threadId, seed.scope.turnId),
            attemptScope = attemptScope,
            snapshot = running,
            submittedSequence = finish.sequence
        )
    }

    private suspend fun seedContinuationRound(
        store: RoomAgentEventStore,
        fixture: RunningAttemptFixture,
        suffix: String
    ): SeedRound {
        val scope = ModelRoundScope(
            threadId = fixture.turnScope.threadId,
            turnId = fixture.turnScope.turnId,
            roundId = "round-$suffix"
        )
        val range = SubmittedItemSequenceRange(
            firstSequence = fixture.submittedSequence,
            lastSequence = fixture.submittedSequence
        )
        val start = PreparedModelRoundStart.create(
            scope = scope,
            repairPurpose = RepairPurpose.NONE,
            startCommitted = true,
            submittedItems = range,
            roundDetailRef = "detail:${scope.roundId}"
        )
        return SeedRound(scope, range, start, store.recordModelRoundStarted(start))
    }

    private suspend fun insertRawAttempt(
        sourceAttempt: ExecutionAttemptEventScope,
        suffix: String,
        terminal: Boolean
    ) {
        val dao = database.agentEventDao()
        val sourcePayload = dao.findAttemptCreation(sourceAttempt.attemptId)
            ?.let { Json.decodeFromString<ExecutionAttemptCreatedEventPayload>(it.payloadJson) }
            ?: error("Missing source attempt")
        val attemptId = "attempt-$suffix"
        val executionId = "execution-$suffix"
        val payload = sourcePayload.copy(
            context = sourcePayload.context.copy(
                executionId = executionId,
                cancellationScopeId = "cancel-$suffix"
            )
        )
        var sequence = dao.maxSequence() + 1L
        dao.insert(
            rawAttemptEntity(
                eventId = "raw-$suffix-created",
                sequence = sequence++,
                threadId = sourceAttempt.threadId,
                turnId = sourceAttempt.turnId,
                attemptId = attemptId,
                executionId = executionId,
                kind = AgentEventKind.EXECUTION_ATTEMPT_CREATED,
                payloadJson = Json.encodeToString(payload),
                idempotencyKey = "attempt:$attemptId:created"
            )
        )
        if (terminal) {
            dao.insert(
                rawAttemptEntity(
                    eventId = "raw-$suffix-canceled",
                    sequence = sequence,
                    threadId = sourceAttempt.threadId,
                    turnId = sourceAttempt.turnId,
                    attemptId = attemptId,
                    executionId = executionId,
                    kind = AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
                    payloadJson = Json.encodeToString(
                        ExecutionAttemptStateChangedEventPayload(
                            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                            state = ExecutionAttemptState.CANCELED,
                            diagnosticCode = null
                        )
                    ),
                    idempotencyKey = "attempt:$attemptId:state:raw-canceled"
                )
            )
        }
    }

    private fun rawAttemptEntity(
        eventId: String,
        sequence: Long,
        threadId: String,
        turnId: String,
        attemptId: String,
        executionId: String,
        kind: AgentEventKind,
        payloadJson: String,
        idempotencyKey: String
    ): AgentEventEntity {
        val canonicalPayload = canonicalizeJson(payloadJson)
        return AgentEventEntity(
            eventId = eventId,
            schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
            eventKind = kind.name,
            sequence = sequence,
            occurredAt = sequence,
            threadId = threadId,
            turnId = turnId,
            roundId = null,
            attemptId = attemptId,
            executionId = executionId,
            callId = null,
            payloadHash = sha256(canonicalPayload),
            payloadJson = canonicalPayload,
            idempotencyKey = idempotencyKey,
            turnTerminalKey = null,
            repairKey = null,
            roundStartKey = null,
            roundCompletionKey = null,
            callDeclarationKey = null,
            callDispositionKey = null,
            attemptCreationKey = attemptId.takeIf {
                kind == AgentEventKind.EXECUTION_ATTEMPT_CREATED
            },
            executionCreationKey = executionId.takeIf {
                kind == AgentEventKind.EXECUTION_ATTEMPT_CREATED
            }
        )
    }

    private suspend fun assertRoundHasOnlyRequestBoundary(
        store: RoomAgentEventStore,
        roundId: String
    ) {
        assertEquals(
            listOf(AgentEventKind.MODEL_ROUND_STARTED),
            store.eventsForTurn(checkNotNull(database.agentEventDao().findRoundStart(roundId)?.turnId))
                .filter { it.roundId == roundId }
                .map(AgentEventEnvelope::eventKind)
        )
    }

    private fun capabilitySnapshot() = CapabilitySnapshot.create(
        capabilitySnapshotRef = CAPABILITY_SNAPSHOT_REF,
        runtimeMode = RuntimeMode.ULTRA,
        executionSpace = ExecutionSpace.MAIN_SCREEN,
        displayId = 0,
        backendKind = ExecutionBackendKind.ROOT_BACKEND,
        backendSessionId = BACKEND_SESSION_ID,
        backendAvailable = true,
        capabilities = setOf(DeviceCapability.CLICK)
    )

    private fun backendInventory() = BackendInventory.of(
        BackendEndpointSnapshot(
            backendKind = ExecutionBackendKind.ROOT_BACKEND,
            backendSessionId = BACKEND_SESSION_ID,
            displayId = 0,
            available = true
        )
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class SeedRound(
        val scope: ModelRoundScope,
        val submittedItems: SubmittedItemSequenceRange,
        val start: PreparedModelRoundStart,
        val startEvent: AgentEventEnvelope
    )

    private data class RunningAttemptFixture(
        val turnScope: TurnEventScope,
        val attemptScope: ExecutionAttemptEventScope,
        val snapshot: DurableExecutionAttemptSnapshot,
        val submittedSequence: Long
    )

    private companion object {
        const val LOCAL_ACTION_NAME = "update_local_state"
        const val DEVICE_ACTION_NAME = "click"
        const val ATTEMPT_ID = "attempt-device"
        const val EXECUTION_ID = "execution-device"
        const val CANCELLATION_SCOPE_ID = "cancel-device"
        const val CAPABILITY_SNAPSHOT_REF = "capability-device"
        const val BACKEND_SESSION_ID = "root-main-session"
        const val START_RESPONSE_EVENT_COUNT = 5
    }
}
