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
import com.droidance.agent.OperationRecorder
import com.droidance.agent.blob.FileBlobStore
import com.droidance.memory.MarkdownFileManager
import com.droidance.core.agent.context.AgentItem
import com.droidance.core.agent.context.AssistantOutputAgentItem
import com.droidance.core.agent.context.ContextCheckpointDecision
import com.droidance.core.agent.context.ContextCheckpointPlanInput
import com.droidance.core.agent.context.ContextCheckpointPlanner
import com.droidance.core.agent.context.ContextCheckpointPayload
import com.droidance.core.agent.context.ContextDynamicRequestSuffix
import com.droidance.core.agent.context.ContextObservationProjection
import com.droidance.core.agent.context.ContextPolicySnapshot
import com.droidance.core.agent.context.ContextTokenEstimator
import com.droidance.core.agent.context.ContextTokenEstimatorCoverage
import com.droidance.core.agent.context.ContextTokenEstimatorIdentity
import com.droidance.core.agent.context.ContextUnit
import com.droidance.core.agent.context.ContextUnitBuilder
import com.droidance.core.agent.context.ObservationProjectionRef
import com.droidance.core.agent.context.UserInputAgentItem
import com.droidance.core.agent.context.UserInputKind
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.prompt.StablePrefixBuilder
import com.droidance.core.agent.llm.session.EpochAdvanceReason
import com.droidance.core.agent.llm.session.FrozenTurnModelSession
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.agent.llm.session.ModelSessionKey
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.core.agent.llm.session.ProviderHandleInvalidated
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentEventStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AgentEventDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "agent-events-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun purgeWaitsForDurableSettlementAndLeavesOtherThreadsUntouched() = runBlocking {
        val store = deterministicStore(database)
        val scope = TurnEventScope("thread", "turn")
        store.createThread("thread", "Delete after cancellation")
        store.startTurn(scope, modelSessionKey(0).identity, "snapshot")
        store.recordUserMessage(scope, "message", "Pending task")
        store.createThread("other", "Unrelated history")
        val original = store.eventsForThread("thread")
        val other = store.eventsForThread("other")

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking { store.purgeThreads(setOf("thread")) }
        }
        assertEquals(original, store.eventsForThread("thread"))
        assertTrue(store.activeThreadReadModels().any { it.metadata.threadId == "thread" })

        store.completeTurn("thread", "turn", TurnResult(status = "cancelled", assistantReply = "Stopped"))
        assertEquals(original.size + 1, store.purgeThreads(setOf("thread")))
        assertTrue(store.eventsForThread("thread").isEmpty())
        assertEquals(other, store.eventsForThread("other"))
        assertEquals(0, store.purgeThreads(setOf("thread")))
    }

    @Test fun bulkPurgeRollsBackAllSelectedThreadsUntilEveryTurnSettles() = runBlocking {
        val store = deterministicStore(database)
        store.createThread("settled", "Keep on failure")
        store.createThread("open", "Still settling")
        store.createThread("other", "Not selected")
        store.startTurn(TurnEventScope("open", "pending"), modelSessionKey(0).identity.copy(threadId = "open"), "snapshot")
        val before = store.eventsForThread("settled")
        assertThrows(AgentEventConflictException::class.java) {
            runBlocking { store.purgeThreads(setOf("settled", "open")) }
        }
        assertEquals(before, store.eventsForThread("settled"))
        store.completeTurn("open", "pending", TurnResult(status = "cancelled", assistantReply = "Stopped"))
        store.purgeThreads(setOf("settled", "open"))
        assertTrue(store.eventsForThread("settled").isEmpty())
        assertTrue(store.eventsForThread("open").isEmpty())
        assertEquals(1, store.eventsForThread("other").size)
    }

    @Test
    fun operationRecorderPersistsAnOrderedRedactedLearningTrace(): Unit = runBlocking {
        val store = deterministicStore(database)
        val blobRoot = java.io.File(context.cacheDir, "recorder-blobs-${UUID.randomUUID()}")
        val recorder = OperationRecorder(
            store,
            MarkdownFileManager(context),
            FileBlobStore(blobRoot)
        )

        recorder.startRecording()
        assertEquals(OperationRecorder.RecordingState.RECORDING, recorder.state.value)
        assertTrue(recorder.operations.value.isEmpty())
        recorder.recordToolExecution(
            sourceCallId = "call-1",
            toolName = "screen_act",
            argumentsJson = """{"actions":[{"op":"type_text","text":"private review","password":"secret"}]}""",
            result = ToolExecutionResult("Text delivery verified", ToolExecutionStatus.SUCCEEDED, ToolEvidenceStatus.CAPTURED, observationId = "obs-1")
        )
        recorder.stopRecording()
        recorder.awaitPendingWrites()
        assertEquals(OperationRecorder.RecordingState.IDLE, recorder.state.value)
        assertEquals(1, recorder.operations.value.size)
        assertEquals("{{input_text}}", recorder.operations.value.single().inputText)
        assertEquals(1L, recorder.completionNonce.value)

        val starts = store.eventsByKind(AgentEventKind.LEARNING_SESSION_STARTED)
        val actions = store.eventsByKind(AgentEventKind.LEARNING_ACTION_RECORDED)
        val completions = store.eventsByKind(AgentEventKind.LEARNING_SESSION_COMPLETED)
        assertEquals(1, starts.size)
        assertEquals(1, actions.size)
        assertEquals(1, completions.size)
        assertTrue(starts.single().sequence < actions.single().sequence)
        assertTrue(actions.single().sequence < completions.single().sequence)
        val payload = Json.decodeFromString<LearningActionRecordedEventPayload>(
            actions.single().payloadJson
        )
        assertTrue(payload.argumentsJson.contains("{{input_text}}"))
        assertTrue(payload.argumentsJson.contains("[REDACTED]"))
        assertFalse(payload.argumentsJson.contains("private review"))
        assertFalse(payload.argumentsJson.contains("secret"))
        assertEquals("observation:obs-1", payload.evidenceRef)
        assertTrue(payload.success)
        blobRoot.deleteRecursively()
    }

    @Test
    fun historicalLearningAndReuseEventsRetainTheirEvidence() = runBlocking {
        val store = deterministicStore(database)
        store.recordLearningSessionStarted("learn-1", "agent_tool_eventstore")
        store.recordLearningSessionCompleted(
            LearningSessionCompletedEventPayload(
                contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
                sessionId = "learn-1",
                actionCount = 9,
                durationMillis = 9_000L
            )
        )
        store.recordLearningSkillCompiled(
            LearningSkillCompiledEventPayload(
                contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
                sessionId = "learn-1",
                skillId = "review-skill",
                baselineDurationMillis = 9_000L,
                baselineActionCount = 9,
                baselineModelRounds = 4,
                baselinePromptTokens = 1_200,
                baselineCompletionTokens = 300
            )
        )
        store.recordCompiledSkillRunStarted(
            CompiledSkillRunStartedEventPayload(
                contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
                runId = "reuse-1",
                skillId = "review-skill",
                mode = CompiledSkillRunMode.REUSE,
                checkpointKey = "batch-1"
            )
        )
        store.recordCompiledSkillRunCompleted(
            CompiledSkillRunCompletedEventPayload(
                contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
                runId = "reuse-1",
                skillId = "review-skill",
                mode = CompiledSkillRunMode.REUSE,
                durationMillis = 1_500L,
                plannedSteps = 4,
                executedSteps = 3,
                divergenceCount = 1,
                semanticSuccess = true,
                localModelRounds = 0,
                localPromptTokens = 0,
                resultHashes = listOf("a".repeat(64)),
                verificationEvidenceRefs = listOf("skill-run:reuse-1:verify:3")
            )
        )

        val learning = Json.decodeFromString<LearningSkillCompiledEventPayload>(
            store.eventsByKind(AgentEventKind.LEARNING_SKILL_COMPILED).single().payloadJson
        )
        val reuse = Json.decodeFromString<CompiledSkillRunCompletedEventPayload>(
            store.eventsByKind(AgentEventKind.COMPILED_SKILL_RUN_COMPLETED).single().payloadJson
        )
        assertEquals("review-skill", learning.skillId)
        assertEquals(9_000L, learning.baselineDurationMillis)
        assertEquals(4, learning.baselineModelRounds)
        assertEquals(1_200, learning.baselinePromptTokens)
        assertEquals(300, learning.baselineCompletionTokens)
        assertEquals(learning.skillId, reuse.skillId)
        assertEquals(1_500L, reuse.durationMillis)
        assertEquals(4, reuse.plannedSteps)
        assertEquals(3, reuse.executedSteps)
        assertEquals(1, reuse.divergenceCount)
        assertEquals(true, reuse.semanticSuccess)
        assertEquals(listOf("a".repeat(64)), reuse.resultHashes)
        assertEquals(listOf("skill-run:reuse-1:verify:3"), reuse.verificationEvidenceRefs)
    }

    @Test
    fun sequence_and_events_survive_a_real_file_database_reopen() = runBlocking {
        val store = deterministicStore(database)
        val first = store.append(threadCreated("thread:created"))
        val second = store.append(turnStarted("turn:started"))

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        database.close()
        database = openDatabase()

        val restored = deterministicStore(database).eventsForThread("thread")
        assertEquals(listOf(1L, 2L), restored.map { it.sequence })
        assertEquals(listOf(AgentEventKind.THREAD_CREATED, AgentEventKind.TURN_STARTED), restored.map { it.eventKind })
    }

    @Test
    fun thread_read_models_are_replayed_from_event_store_and_tombstones_are_excluded() = runBlocking {
        val store = deterministicStore(database)
        store.createThread("thread-a", "A")
        store.createThread("thread-b", "B")
        store.changeThreadPin("thread-a", "pin-a", true)
        store.tombstoneThread("thread-b")

        val active = store.activeThreadReadModels()
        val observed = store.observeActiveThreadReadModels().first()

        assertEquals(listOf("thread-a"), active.map { it.metadata.threadId })
        assertEquals(listOf("thread-a"), observed.map { it.metadata.threadId })
        assertTrue(active.single().metadata.pinned)
    }

    @Test
    fun idempotent_retry_returns_the_same_event_and_rejects_changed_payload() = runBlocking {
        val store = deterministicStore(database)
        val draft = threadCreated("thread:created")
        val first = store.append(draft)
        val retry = store.append(draft.copy(payloadJson = """{ "title" : "Droidance" }"""))

        assertEquals(first.eventId, retry.eventId)
        assertEquals(1, store.count())

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.append(draft.copy(payloadJson = """{"title":"changed"}"""))
            }
        }
        Unit
    }

    @Test
    fun coordinated_round_plan_events_cannot_bypass_the_typed_store_contract() = runBlocking {
        val store = deterministicStore(database)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.append(
                    AgentEventDraft(
                        eventKind = AgentEventKind.TOOL_CALL_REJECTED,
                        threadId = "thread",
                        turnId = "turn",
                        roundId = "round",
                        callId = "call-1",
                        payloadJson = "{}",
                        idempotencyKey = "call-1:rejected"
                    )
                )
            }
        }
        assertEquals(0, store.count())
    }

    @Test
    fun concurrent_complete_turn_has_exactly_one_winner_and_one_fact() = runBlocking {
        val store = deterministicStore(database)
        store.createThread("thread", "Droidance")
        store.startTurn(
            scope = TurnEventScope("thread", "turn"),
            modelSessionIdentity = modelSessionKey(epoch = 0L).identity,
            providerConfigSnapshotRef = "provider-config"
        )
        val completions = withContext(Dispatchers.IO) {
            listOf(
                async {
                    runCatching {
                        store.completeTurn(
                            threadId = "thread",
                            turnId = "turn",
                            result = TurnResult(status = "DONE", assistantReply = "完成")
                        )
                    }
                },
                async {
                    runCatching {
                        store.completeTurn(
                            threadId = "thread",
                            turnId = "turn",
                            result = TurnResult(status = "FAILED", systemNotice = "失败")
                        )
                    }
                }
            ).awaitAll()
        }

        assertEquals(1, completions.count(Result<AgentEventEnvelope>::isSuccess))
        assertTrue(completions.single(Result<AgentEventEnvelope>::isFailure).exceptionOrNull() is AgentEventConflictException)
        val events = store.eventsForTurn("turn")
        assertEquals(1, events.count { it.eventKind == AgentEventKind.TURN_COMPLETED })
    }

    @Test
    fun memory_context_is_committed_once_per_accepted_user_input() = runBlocking {
        val store = deterministicStore(database)
        val scope = TurnEventScope("thread", "turn")
        store.createThread(scope.threadId, "Droidance")
        store.startTurn(
            scope = scope,
            modelSessionIdentity = modelSessionKey(epoch = 0L).identity,
            providerConfigSnapshotRef = "provider-config"
        )
        store.recordUserMessage(
            scope = scope,
            messageId = "message:user",
            text = "继续上次的设置"
        )
        val payload = MemoryContextLoadedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            queryHash = "a".repeat(64),
            items = listOf(
                MemoryContextItemPayload(
                    ref = "profile:language",
                    scope = MemoryContextScope.CORE_PROFILE,
                    summary = "用户偏好使用简体中文"
                ),
                MemoryContextItemPayload(
                    ref = "episode:abc123",
                    scope = MemoryContextScope.EPISODE,
                    summary = "上次完成了设置页检查",
                    sourceThroughSequence = 2L
                )
            )
        )

        val first = store.recordMemoryContextLoaded(scope, payload)
        val retry = store.recordMemoryContextLoaded(scope, payload)
        assertEquals(first.eventId, retry.eventId)

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.recordMemoryContextLoaded(
                    scope,
                    payload.copy(queryHash = "b".repeat(64))
                )
            }
        }

        val memoryEvent = store.eventsForTurn(scope.turnId).single {
            it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED
        }
        assertEquals(
            payload,
            Json.decodeFromString<MemoryContextLoadedEventPayload>(memoryEvent.payloadJson)
        )
        val steer = store.recordUserSteer(scope, "message:steer", "Use the updated preference")
        val refreshed = payload.copy(queryHash = "c".repeat(64))
        val refresh = store.recordMemoryContextLoaded(scope, refreshed, steer.sequence)
        assertEquals(refresh.eventId, store.recordMemoryContextLoaded(scope, refreshed, steer.sequence).eventId)
        assertEquals(2, store.eventsForTurn(scope.turnId).count { it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED })
        assertNotNull(store.threadReadModel(scope.threadId))
    }

    @Test
    fun context_checkpoint_after_android_activation_uses_activated_session_and_epoch() = runBlocking {
        val store = deterministicStore(database)
        prepareActivatedTurn(store)

        val commit = store.recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload(),
            frozenSession = frozenModelSession(epoch = 1L)
        )

        assertEquals(1L, commit.epochTransition.previous.epoch)
        assertEquals(2L, commit.epochTransition.current.epoch)
        assertEquals(2L, store.threadReadModel("thread")!!.turns.single().modelSessionEpoch)

        val retry = store.recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload(),
            frozenSession = frozenModelSession(epoch = 1L)
        )
        assertEquals(commit.checkpointEvent.eventId, retry.checkpointEvent.eventId)
        assertEquals(5, store.count())
    }

    @Test
    fun activated_session_epoch_continues_after_database_reopen_and_handle_invalidation() = runBlocking {
        val store = deterministicStore(database)
        prepareActivatedTurn(store)
        store.recordProviderHandleInvalidation(
            threadId = "thread",
            turnId = "turn",
            frozenSession = frozenModelSession(epoch = 1L),
            trigger = providerHandleInvalidated("a")
        )
        database.close()
        database = openDatabase()
        val reopened = deterministicStore(database, idPrefix = "reopened-")
        val commit = reopened.recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload(),
            frozenSession = frozenModelSession(epoch = 2L)
        )
        assertEquals(2L, commit.epochTransition.previous.epoch)
        assertEquals(3L, commit.epochTransition.current.epoch)
        assertEquals(3L, reopened.threadReadModel("thread")!!.turns.single().modelSessionEpoch)
    }

    @Test
    fun activated_session_rejects_stale_future_and_changed_frozen_sessions_without_writes() = runBlocking {
        val store = deterministicStore(database)
        prepareActivatedTurn(store)
        val active = frozenModelSession(epoch = 1L)
        val invalidSessions = listOf(
            frozenModelSession(epoch = 0L),
            frozenModelSession(epoch = 2L),
            active.copy(providerConfigSnapshotRef = "changed-ref"),
            active.copy(key = ModelSessionKey(
                identity = active.key.identity.copy(modelId = "changed"), epoch = 1L
            )),
            active.copy(key = ModelSessionKey(
                identity = active.key.identity.copy(stablePrefixHash = "3".repeat(64)), epoch = 1L
            ))
        )
        invalidSessions.forEach { session ->
            assertThrows(AgentEventConflictException::class.java) {
                runBlocking {
                    store.recordProviderHandleInvalidation(
                        threadId = "thread",
                        turnId = "turn",
                        frozenSession = session,
                        trigger = providerHandleInvalidated("b")
                    )
                }
            }
            assertEquals(3, store.count())
        }
    }

    @Test
    fun context_checkpoint_and_epoch_boundary_commit_atomically_with_durable_evidence() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)
        val checkpointPayload = checkpointPayload()

        val commit = store.recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload,
            frozenSession = frozenModelSession(epoch = 0L)
        )

        assertEquals(AgentEventKind.CONTEXT_CHECKPOINT, commit.checkpointEvent.eventKind)
        assertEquals(AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED, commit.epochEvent.eventKind)
        assertEquals(3L, commit.checkpointEvent.sequence)
        assertEquals(4L, commit.epochEvent.sequence)
        assertEquals(0L, commit.epochTransition.previous.epoch)
        assertEquals(1L, commit.epochTransition.current.epoch)
        assertEquals(EpochAdvanceReason.CONTEXT_CHECKPOINT, commit.epochTransition.reason)
        assertEquals(
            checkpointPayload,
            Json.decodeFromString<ContextCheckpointPayload>(commit.checkpointEvent.payloadJson)
        )

        val epochPayload = Json.parseToJsonElement(commit.epochEvent.payloadJson).jsonObject
        assertEquals(commit.checkpointEvent.eventId, epochPayload.getValue("evidenceRef").jsonPrimitive.content)
        assertEquals("CONTEXT_CHECKPOINT", epochPayload.getValue("reason").jsonPrimitive.content)
        assertEquals("0", epochPayload.getValue("previousEpoch").jsonPrimitive.content)
        assertEquals("1", epochPayload.getValue("currentEpoch").jsonPrimitive.content)
    }

    @Test
    fun context_checkpoint_rolls_back_when_epoch_event_cannot_be_inserted() = runBlocking {
        val store = deterministicStore(database, failAt = 1)
        prepareOpenTurn(store)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.recordContextCheckpoint(
                    threadId = "thread",
                    turnId = "turn",
                    checkpointPayload = checkpointPayload(),
                    frozenSession = frozenModelSession(epoch = 0L)
                )
            }
        }

        assertEquals(2, deterministicStore(database).count())
    }

    @Test
    fun context_checkpoint_retry_returns_the_same_checkpoint_and_epoch_facts() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)
        val session = frozenModelSession(epoch = 0L)

        val first = store.recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload(),
            frozenSession = session
        )
        val retry = store.recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload(),
            frozenSession = session
        )

        assertEquals(first.checkpointEvent.eventId, retry.checkpointEvent.eventId)
        assertEquals(first.epochEvent.eventId, retry.epochEvent.eventId)
        assertEquals(4, store.count())
    }

    @Test
    fun context_checkpoint_retry_cannot_change_the_turn_frozen_provider_snapshot() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)
        val session = frozenModelSession(epoch = 0L)
        store.recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload(),
            frozenSession = session
        )

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.recordContextCheckpoint(
                    threadId = "thread",
                    turnId = "turn",
                    checkpointPayload = checkpointPayload(),
                    frozenSession = session.copy(providerConfigSnapshotRef = "other-provider-config-ref")
                )
            }
        }
        assertEquals(4, store.count())
    }

    @Test
    fun invalid_context_checkpoint_is_rejected_before_any_epoch_fact_is_written() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)
        val valid = checkpointPayload()
        val invalid = valid.copy(
            tokenEvidence = valid.tokenEvidence.copy(hardWindowTokens = 99)
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.recordContextCheckpoint(
                    threadId = "thread",
                    turnId = "turn",
                    checkpointPayload = invalid,
                    frozenSession = frozenModelSession(epoch = 0L)
                )
            }
        }
        assertEquals(2, store.count())
    }

    @Test
    fun coordinated_events_cannot_bypass_the_store_contract() = runBlocking {
        val store = deterministicStore(database)

        listOf(
            AgentEventKind.CONTEXT_CHECKPOINT,
            AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED,
            AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
            AgentEventKind.TOOL_FINISHED,
            AgentEventKind.DETAIL_ATTACHMENT_ADDED
        ).forEach { kind ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    store.append(
                        AgentEventDraft(
                            eventKind = kind,
                            threadId = "thread",
                            turnId = "turn",
                            payloadJson = "{}",
                            idempotencyKey = "direct:${kind.name}"
                        )
                    )
                }
            }
        }

        assertEquals(0, store.count())
    }

    @Test
    fun provider_handle_invalidation_is_the_only_independent_epoch_transition() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)

        val commit = store.recordProviderHandleInvalidation(
            threadId = "thread",
            turnId = "turn",
            frozenSession = frozenModelSession(epoch = 0L),
            trigger = providerHandleInvalidated("a")
        )

        assertEquals(AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED, commit.epochEvent.eventKind)
        assertEquals(EpochAdvanceReason.PROVIDER_HANDLE_INVALIDATED, commit.epochTransition.reason)
        assertEquals(0L, commit.epochTransition.previous.epoch)
        assertEquals(1L, commit.epochTransition.current.epoch)
        val payload = Json.parseToJsonElement(commit.epochEvent.payloadJson).jsonObject
        assertEquals("PROVIDER_HANDLE_INVALIDATED", payload.getValue("reason").jsonPrimitive.content)
        assertEquals("sha256:${"a".repeat(64)}", payload.getValue("evidenceRef").jsonPrimitive.content)
        assertEquals("PROVIDER_HANDLE_EXPIRED", payload.getValue("diagnosticCode").jsonPrimitive.content)
        assertEquals("thread", payload.getValue("identity").jsonObject.getValue("threadId").jsonPrimitive.content)
        assertEquals(3, store.count())
    }

    @Test
    fun provider_handle_invalidation_retry_is_idempotent_and_changed_evidence_conflicts() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)
        val frozenSession = frozenModelSession(epoch = 0L)

        val first = store.recordProviderHandleInvalidation(
            threadId = "thread",
            turnId = "turn",
            frozenSession = frozenSession,
            trigger = providerHandleInvalidated("b")
        )
        val retry = store.recordProviderHandleInvalidation(
            threadId = "thread",
            turnId = "turn",
            frozenSession = frozenSession,
            trigger = providerHandleInvalidated("b")
        )

        assertEquals(first.epochEvent.eventId, retry.epochEvent.eventId)
        assertEquals(3, store.count())
        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.recordProviderHandleInvalidation(
                    threadId = "thread",
                    turnId = "turn",
                    frozenSession = frozenSession,
                    trigger = providerHandleInvalidated("c")
                )
            }
        }
        assertEquals(3, store.count())
    }

    @Test
    fun epoch_transition_rejects_a_session_from_another_thread() = runBlocking {
        val store = deterministicStore(database)
        val otherThreadSession = ModelSessionKey(
            identity = modelSessionKey(epoch = 0L).identity.copy(threadId = "other-thread"),
            epoch = 0L
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.recordContextCheckpoint(
                    threadId = "thread",
                    turnId = "turn",
                    checkpointPayload = checkpointPayload(),
                    frozenSession = FrozenTurnModelSession(
                        turnId = "turn",
                        providerConfigSnapshotRef = "provider-config-ref",
                        key = otherThreadSession
                    )
                )
            }
        }
        assertEquals(0, store.count())
    }

    @Test
    fun epoch_transition_rejects_a_session_frozen_for_another_turn() = runBlocking {
        val store = deterministicStore(database)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.recordProviderHandleInvalidation(
                    threadId = "thread",
                    turnId = "turn",
                    frozenSession = frozenModelSession(epoch = 0L, turnId = "another-turn"),
                    trigger = providerHandleInvalidated("4")
                )
            }
        }
        assertEquals(0, store.count())
    }

    @Test
    fun durable_epoch_rejects_skips_and_conflicting_transitions() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.recordProviderHandleInvalidation(
                    threadId = "thread",
                    turnId = "turn",
                    frozenSession = frozenModelSession(epoch = 3L),
                    trigger = providerHandleInvalidated("5")
                )
            }
        }
        assertEquals(2, store.count())

        store.recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload(),
            frozenSession = frozenModelSession(epoch = 0L)
        )
        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.recordProviderHandleInvalidation(
                    threadId = "thread",
                    turnId = "turn",
                    frozenSession = frozenModelSession(epoch = 0L),
                    trigger = providerHandleInvalidated("6")
                )
            }
        }
        assertEquals(4, store.count())
    }

    @Test
    fun task_compaction_summary_survives_reopen_and_is_not_loaded_for_another_turn() = runBlocking {
        val store = deterministicStore(database, idPrefix = "summary-")
        prepareOpenTurn(store)
        val expected = checkpointPayload()
        store.recordContextCheckpoint(
            threadId = "thread", turnId = "turn", checkpointPayload = expected,
            frozenSession = frozenModelSession(epoch = 0L)
        )
        database.close()
        database = openDatabase()
        val reopened = deterministicStore(database)
        assertEquals(expected, reopened.latestContextCheckpointForTurn("turn")?.payload)
        assertEquals("Model-authored task summary", reopened.latestContextCheckpointForTurn("turn")?.payload?.compactionSummary)
        assertEquals(null, reopened.latestContextCheckpointForTurn("another-turn"))
    }

    @Test
    fun checkpoint_rolls_back_after_a_real_sqlite_constraint_and_survives_reopen() = runBlocking {
        prepareOpenTurn(deterministicStore(database))
        val store = RoomAgentEventStore(
            database = database,
            nowMs = { 1_000L },
            eventIdFactory = { "duplicate-event-id" }
        )

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.recordContextCheckpoint(
                    threadId = "thread",
                    turnId = "turn",
                    checkpointPayload = checkpointPayload(),
                    frozenSession = frozenModelSession(epoch = 0L)
                )
            }
        }
        database.close()
        database = openDatabase()

        assertEquals(2, deterministicStore(database).count())
    }

    @Test
    fun durable_epoch_chain_is_recovered_after_database_reopen() = runBlocking {
        val beforeStore = deterministicStore(database, idPrefix = "before-")
        prepareOpenTurn(beforeStore)
        beforeStore.recordProviderHandleInvalidation(
            threadId = "thread",
            turnId = "turn",
            frozenSession = frozenModelSession(epoch = 0L),
            trigger = providerHandleInvalidated("8")
        )
        database.close()
        database = openDatabase()

        val commit = deterministicStore(database, idPrefix = "after-").recordContextCheckpoint(
            threadId = "thread",
            turnId = "turn",
            checkpointPayload = checkpointPayload(),
            frozenSession = frozenModelSession(epoch = 1L)
        )

        assertEquals(1L, commit.epochTransition.previous.epoch)
        assertEquals(2L, commit.epochTransition.current.epoch)
        assertEquals(5, deterministicStore(database).count())
    }

    @Test
    fun tombstoneAllThreadsHidesEveryClosedThreadInOneBatch() = runBlocking {
        val store = deterministicStore(database)
        store.createThread("thread-a", "A")
        store.createThread("thread-b", "B")

        val tombstones = store.tombstoneAllThreads()

        assertEquals(2, tombstones.size)
        assertTrue(tombstones.all { it.eventKind == AgentEventKind.THREAD_TOMBSTONED })
        assertTrue(store.activeThreadReadModels().isEmpty())
    }

    @Test
    fun tombstoneAllThreadsIsAtomicWhenAnyTurnIsOpen() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)
        store.createThread("closed-thread", "Closed")

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking { store.tombstoneAllThreads() }
        }

        assertTrue(store.eventsByKind(AgentEventKind.THREAD_TOMBSTONED).isEmpty())
    }

    @Test
    fun concurrent_conflicting_epoch_transitions_have_one_durable_winner() = runBlocking {
        val store = deterministicStore(database)
        prepareOpenTurn(store)
        val results = withContext(Dispatchers.IO) {
            listOf(
                async {
                    runCatching {
                        store.recordContextCheckpoint(
                            threadId = "thread",
                            turnId = "turn",
                            checkpointPayload = checkpointPayload(),
                            frozenSession = frozenModelSession(epoch = 0L)
                        )
                    }
                },
                async {
                    runCatching {
                        store.recordProviderHandleInvalidation(
                            threadId = "thread",
                            turnId = "turn",
                            frozenSession = frozenModelSession(epoch = 0L),
                            trigger = providerHandleInvalidated("7")
                        )
                    }
                }
            ).awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        val events = store.eventsForTurn("turn")
        assertEquals(1, events.count { it.eventKind == AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED })
        assertTrue(events.size in 2..3)
    }

    private fun openDatabase(): AgentEventDatabase = Room.databaseBuilder(
        context,
        AgentEventDatabase::class.java,
        databaseName
    ).build()

    private fun deterministicStore(
        db: AgentEventDatabase,
        failAt: Int? = null,
        idPrefix: String = ""
    ): RoomAgentEventStore {
        var id = 0
        var time = 1_000L
        return RoomAgentEventStore(
            database = db,
            nowMs = { time++ },
            eventIdFactory = { "${idPrefix}event-${id++}" },
            beforeInsert = { index, _ ->
                if (index == failAt) error("injected insert failure")
            }
        )
    }

    private fun threadCreated(key: String) = AgentEventDraft(
        eventKind = AgentEventKind.THREAD_CREATED,
        threadId = "thread",
        payloadJson = """{"title":"Droidance"}""",
        idempotencyKey = key
    )

    private fun turnStarted(key: String) = AgentEventDraft(
        eventKind = AgentEventKind.TURN_STARTED,
        threadId = "thread",
        turnId = "turn",
        payloadJson = "{}",
        idempotencyKey = key
    )

    private fun modelSessionKey(epoch: Long) = ModelSessionKey(
        identity = ModelSessionIdentity(
            threadId = "thread",
            providerId = "provider",
            transportKind = ModelTransport.OPENAI_RESPONSES,
            transportConfigHash = "1".repeat(64),
            authScopeHash = "2".repeat(64),
            modelId = "model",
            stablePrefixHash = checkpointStablePrefix.hash
        ),
        epoch = epoch
    )

    private fun frozenModelSession(epoch: Long, turnId: String = "turn") = FrozenTurnModelSession(
        turnId = turnId,
        providerConfigSnapshotRef = "provider-config-ref",
        key = modelSessionKey(epoch)
    )

    private fun providerHandleInvalidated(seed: String) = ProviderHandleInvalidated(
        diagnosticCode = "PROVIDER_HANDLE_EXPIRED",
        handleRef = "sha256:${seed.repeat(64)}"
    )

    private fun checkpointPayload(): ContextCheckpointPayload {
        val units = ContextUnitBuilder.build(
            (1..3).flatMap { index ->
                val roundId = "round-$index"
                val sequence = index * 2L - 1L
                listOf<AgentItem>(
                    UserInputAgentItem(
                        itemId = "item-$sequence",
                        sequence = sequence,
                        roundId = roundId,
                        kind = UserInputKind.MESSAGE,
                        text = "task-$index"
                    ),
                    AssistantOutputAgentItem(
                        itemId = "item-${sequence + 1L}",
                        sequence = sequence + 1L,
                        roundId = roundId,
                        kind = PublicMessageKind.START,
                        text = "reply-$index"
                    )
                )
            }
        )
        val policy = ContextPolicySnapshot(
            providerContextWindowTokens = 210,
            maxOutputTokens = 10,
            safetyMarginTokens = 0,
            compressionTriggerNumerator = 1,
            compressionTriggerDenominator = 2,
            recentScreenshotLimit = 3,
            tokenEstimatorIdentity = checkpointTokenEstimatorIdentity
        )
        return (ContextCheckpointPlanner(CheckpointTokenEstimator).plan(
            ContextCheckpointPlanInput(
                parentCheckpoint = null,
                stablePrefix = checkpointStablePrefix,
                dynamicRequestSuffix = ContextDynamicRequestSuffix.empty(),
                sortedUnits = units,
                currentObservationProjections = ContextObservationProjection.initial(units),
                contextPolicy = policy,
                compactionSummary = "Model-authored task summary"
            )
        ) as ContextCheckpointDecision.CheckpointReady).payload
    }

    private suspend fun prepareActivatedTurn(store: RoomAgentEventStore) {
        store.createThread("thread", "Droidance")
        store.startTurn(
            scope = TurnEventScope("thread", "turn"),
            modelSessionIdentity = modelSessionKey(epoch = 0L).identity.copy(
                stablePrefixHash = "3".repeat(64)
            ),
            providerConfigSnapshotRef = "provider-config-ref"
        )
        store.activateModelSession(
            scope = TurnEventScope("thread", "turn"),
            modelSessionIdentity = modelSessionKey(epoch = 1L).identity,
            providerConfigSnapshotRef = "provider-config-ref",
            previousEpoch = 0L,
            currentEpoch = 1L
        )
    }

    private suspend fun prepareOpenTurn(store: RoomAgentEventStore) {
        store.createThread("thread", "Droidance")
        store.startTurn(
            scope = TurnEventScope("thread", "turn"),
            modelSessionIdentity = modelSessionKey(epoch = 0L).identity,
            providerConfigSnapshotRef = "provider-config-ref"
        )
    }

    private object CheckpointTokenEstimator : ContextTokenEstimator {
        override val identity: ContextTokenEstimatorIdentity = checkpointTokenEstimatorIdentity

        override fun estimateStablePrefix(
            stablePrefix: com.droidance.core.agent.prompt.StablePrefix
        ): Int = 5

        override fun estimateDynamicSuffix(dynamicSuffix: ContextDynamicRequestSuffix): Int = 5

        override fun estimateUnit(
            unit: ContextUnit,
            observations: Map<Long, ObservationProjectionRef>
        ): Int = 30
    }

    private companion object {
        val checkpointStablePrefix = StablePrefixBuilder.build(
            systemPrompt = "Event checkpoint test.",
            tools = emptyList(),
            indexEntries = emptyList()
        )
        val checkpointTokenEstimatorIdentity = ContextTokenEstimatorIdentity(
            transportKind = ModelTransport.OPENAI_RESPONSES.name,
            adapterVersion = "adapter-v1",
            modelId = "model",
            requestSerializerVersion = "serializer-v1",
            tokenizerVersion = "tokenizer-v1",
            imageCostVersion = "image-v1",
            coverage = ContextTokenEstimatorCoverage(
                stablePrefix = true,
                dynamicSuffix = true,
                providerFraming = true,
                userAssistantItems = true,
                toolProtocol = true,
                observationText = true,
                observationImages = true
            )
        )
    }
}
