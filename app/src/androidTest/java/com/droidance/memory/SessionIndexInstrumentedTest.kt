// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.config.LanguageManager
import com.droidance.config.RuleConfigManager
import com.droidance.core.persistence.agent.event.AgentEventDatabase
import com.droidance.core.persistence.agent.event.RoomAgentEventStore
import com.droidance.core.agent.event.*
import com.droidance.core.agent.tools.ToolProtocolResult
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/FTS coverage for memory migration, unified search, and bounded pagination. */
@RunWith(AndroidJUnit4::class)
class SessionIndexInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: MemoryCandidateDatabase
    private lateinit var databaseName: String
    private lateinit var eventDatabase: AgentEventDatabase
    private lateinit var eventDatabaseName: String
    private lateinit var eventStore: RoomAgentEventStore
    private lateinit var experienceRepository: ExperienceRepository
    private lateinit var experienceScope: CoroutineScope
    private lateinit var memoryRoot: File

    @Before
    fun setUp() {
        databaseName = "memory-index-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, MemoryCandidateDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        eventDatabaseName = "memory-index-events-${UUID.randomUUID()}.db"
        eventDatabase = Room.databaseBuilder(context, AgentEventDatabase::class.java, eventDatabaseName)
            .allowMainThreadQueries()
            .build()
        eventStore = RoomAgentEventStore(eventDatabase)
        memoryRoot = File(context.cacheDir, "memory-index-test-${UUID.randomUUID()}").also { it.mkdirs() }
        experienceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        experienceRepository = ExperienceRepository(database, context,
            eventStore,
            RuleConfigManager(context, filesFor(memoryRoot), LanguageManager(context)),
            experienceScope)
    }

    @After
    fun tearDown() {
        database.close()
        eventDatabase.close()
        experienceScope.cancel()
        memoryRoot.deleteRecursively()
        context.deleteDatabase(databaseName)
        context.deleteDatabase(eventDatabaseName)
    }

    @Test
    fun migrationFromVersionOneCreatesTheFtsSearchIndex() {
        database.close()
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            raw.execSQL("CREATE TABLE memory_candidates (candidate_hash TEXT NOT NULL, version INTEGER NOT NULL, candidate_json TEXT NOT NULL, search_text TEXT NOT NULL, status TEXT NOT NULL, occurrences INTEGER NOT NULL, first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, source_through_sequence INTEGER NOT NULL, has_verified_state_change INTEGER NOT NULL, PRIMARY KEY(candidate_hash))")
            raw.execSQL("CREATE TABLE memory_candidate_sources (turn_id TEXT NOT NULL, candidate_hash TEXT NOT NULL, source_through_sequence INTEGER NOT NULL, PRIMARY KEY(turn_id))")
            raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            raw.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, '3c91600417acf1666e1edd153b68d295')")
            raw.version = 1
        } finally {
            raw.close()
        }

        database = Room.databaseBuilder(context, MemoryCandidateDatabase::class.java, databaseName)
            .addMigrations(MemoryCandidateDatabase.MIGRATION_1_2, MemoryCandidateDatabase.MIGRATION_2_3, MemoryCandidateDatabase.MIGRATION_3_4)
            .build()

        val searchIndex = database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'memory_search'")
        searchIndex.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("memory_search", cursor.getString(0))
        }
    }

    @Test
    fun unifiedFtsSearchPaginatesMoreThanTwoHundredEpisodesWithoutDuplicates() = runBlocking {
        val index = SessionIndex(database, durableMemoryStore(), eventStore, experienceRepository)
        val candidates = (0 until 205).map(::candidate)
        candidates.forEach { index.indexCandidate(it) }

        val json = Json { ignoreUnknownKeys = false }
        var cursor: String? = null
        val refs = linkedSetOf<String>()
        var pages = 0
        var lastPageHasMore = true
        while (lastPageHasMore) {
            val output = json.parseToJsonElement(
                index.formatSearch(query = "target", limit = 10, scope = "episode", cursor = cursor)
            ).jsonObject
            assertEquals(205, output.getValue("indexed_count").jsonPrimitive.int)
            assertEquals(205, output.getValue("matched_count").jsonPrimitive.int)
            output.getValue("items").jsonArray.forEach { item ->
                assertTrue(refs.add(item.jsonObject.getValue("ref").jsonPrimitive.content))
                assertEquals("episode", item.jsonObject.getValue("scope").jsonPrimitive.content)
            }
            lastPageHasMore = output.getValue("has_more").jsonPrimitive.boolean
            cursor = output["next_cursor"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            pages++
            assertTrue("Pagination must remain bounded", pages <= 21)
        }

        assertEquals(205, refs.size)
        assertTrue(pages > 20)
        assertFalse(lastPageHasMore)
        assertTrue(cursor == null)
    }

    @Test
    fun rebuildsCandidatesAndRawUserTextFromTheRealRoomEventStore() = runBlocking {
        val threadId = "indexed-thread"
        val turnId = "indexed-turn"
        val scope = TurnEventScope(threadId, turnId)
        val hash = "3".repeat(64)
        eventStore.createThread(threadId, "Indexed history")
        eventStore.startTurn(
            scope,
            ModelSessionIdentity(
                threadId,
                "fixture",
                ModelTransport.GENERIC_CHAT_COMPLETIONS,
                hash,
                hash,
                "fixture",
                hash
            ),
            "fixture"
        )
        eventStore.recordUserMessage(scope, "message-1", "historical target phrase")
        eventStore.completeTurn(
            threadId,
            turnId,
            TurnResult(status = "done", assistantReply = "historical answer")
        )

        val index = SessionIndex(database, durableMemoryStore(), eventStore, experienceRepository)
        val body = Json.parseToJsonElement(
            index.formatSearch("historical", limit = 5, scope = "episode")
        ).jsonObject

        assertEquals(1, body.getValue("original_history_records").jsonPrimitive.int)
        assertEquals(1, body.getValue("indexed_count").jsonPrimitive.int)
        assertEquals(1, body.getValue("matched_count").jsonPrimitive.int)
        assertEquals(1, body.getValue("items").jsonArray.size)
        assertTrue(body.getValue("items").jsonArray.single().jsonObject
            .getValue("summary").jsonPrimitive.content.contains("historical target phrase"))
    }

    @Test
    fun exactKeySearchReturnsItemRevisionForUpdateAndDeletionWithoutIndexRevisionConfusion() = runBlocking {
        val memories = durableMemoryStore()
        val update = DurableMemoryUpdate(DurableMemoryScope.LONG_TERM, DurableMemoryOperation.UPSERT,
            key = "test_morning_preference", category = "preference", content = "stretch seven minutes",
            sourceQuote = "stretch seven minutes", expectedRevision = "absent")
        assertEquals(1, memories.applyUpdates(listOf(update)) { listOf(update.sourceQuote) }.getOrThrow().changed)
        val index = SessionIndex(database, memories, eventStore, experienceRepository)
        fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        val found = parse(index.formatSearch(update.key, scope = "long_term"))
        assertTrue(found.containsKey("index_revision"))
        assertFalse(found.containsKey("revision"))
        val item = found.getValue("items").jsonArray.single().jsonObject
        assertEquals("long_term:${update.key}", item.getValue("ref").jsonPrimitive.content)
        val changed = update.copy(content = "stretch eleven minutes", sourceQuote = "stretch eleven minutes")
        for (wrongRevision in listOf("absent", found.getValue("index_revision").jsonPrimitive.content)) {
            assertEquals("MEMORY_REVISION_CONFLICT", memories.applyUpdates(listOf(changed.copy(
                expectedRevision = wrongRevision))) { listOf(changed.sourceQuote) }.getOrThrow().items.single().error)
        }
        assertEquals(1, memories.applyUpdates(listOf(changed.copy(
            expectedRevision = item.getValue("revision").jsonPrimitive.content))) {
            listOf(changed.sourceQuote)
        }.getOrThrow().changed)
        val reread = parse(index.formatSearch("long_term:${update.key}", scope = "long_term"))
            .getValue("items").jsonArray.single().jsonObject
        assertTrue(reread.getValue("summary").jsonPrimitive.content.contains("eleven"))
        assertTrue(parse(index.formatSearch(update.key, scope = "profile")).getValue("items").jsonArray.isEmpty())
        val deletion = update.copy(operation = DurableMemoryOperation.DELETE, content = "",
            sourceQuote = "forget the test preference", expectedRevision = reread.getValue("revision").jsonPrimitive.content)
        assertEquals(1, memories.applyUpdates(listOf(deletion)) { listOf(deletion.sourceQuote) }.getOrThrow().changed)
        assertTrue(parse(index.formatSearch(update.key, scope = "long_term")).getValue("items").jsonArray.isEmpty())
    }

    @Test
    fun expiredDurableMemoryIsExcludedFromUnifiedSearch() = runBlocking {
        val memories = durableMemoryStore()
        val expired = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "temporary_fact",
            category = "temporary",
            content = "expired target fact",
            sourceQuote = "expired target fact",
            expectedRevision = "absent",
            operationId = "expired-search",
            sourceRef = "turn:expiry",
            expiresAt = "2000-01-01T00:00:00Z"
        )
        assertEquals(1, memories.applyUpdates(listOf(expired)) {
            listOf(expired.sourceQuote)
        }.getOrThrow().changed)

        val body = Json.parseToJsonElement(
                SessionIndex(database, memories, eventStore, experienceRepository)
                .formatSearch("expired target", limit = 5, scope = "profile")
        ).jsonObject

        assertEquals(0, body.getValue("indexed_count").jsonPrimitive.int)
        assertEquals(0, body.getValue("matched_count").jsonPrimitive.int)
        assertTrue(body.getValue("items").jsonArray.isEmpty())
    }

    @Test
    fun deletedDurableMemoryIsReturnedOnlyAsForgottenRefAndNeverAsCurrentSearchHit() = runBlocking {
        val memories = durableMemoryStore()
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "old_preference",
            category = "preference",
            content = "target preference",
            sourceQuote = "target preference",
            expectedRevision = "absent",
            operationId = "forgotten-upsert",
            sourceRef = "turn:forgotten"
        )
        assertEquals(1, memories.applyUpdates(listOf(update)) {
            listOf(update.sourceQuote)
        }.getOrThrow().changed)

        val index = SessionIndex(database, memories, eventStore, experienceRepository)
        val beforeDelete = Json.parseToJsonElement(
            index.formatSearch("target preference", limit = 5, scope = "profile")
        ).jsonObject
        assertEquals(1, beforeDelete.getValue("matched_count").jsonPrimitive.int)
        assertEquals("profile:old_preference", beforeDelete.getValue("items").jsonArray.single()
            .jsonObject.getValue("ref").jsonPrimitive.content)

        val currentRevision = checkNotNull(memories.contextSnapshot().requireAvailable().entries.single().revision)
        val delete = update.copy(
            operation = DurableMemoryOperation.DELETE,
            content = "",
            sourceQuote = "forget target preference",
            expectedRevision = currentRevision,
            operationId = "forgotten-delete"
        )
        assertEquals(1, memories.applyUpdates(listOf(delete)) {
            listOf(delete.sourceQuote)
        }.getOrThrow().changed)

        val afterDelete = Json.parseToJsonElement(
            index.formatSearch("target preference", limit = 5, scope = "profile")
        ).jsonObject
        assertEquals(0, afterDelete.getValue("indexed_count").jsonPrimitive.int)
        assertEquals(0, afterDelete.getValue("matched_count").jsonPrimitive.int)
        assertTrue(afterDelete.getValue("items").jsonArray.isEmpty())
        assertTrue(afterDelete.getValue("forgotten_refs").jsonArray
            .any { it.jsonPrimitive.content == "profile:old_preference" })
        assertFalse(ProfileStore(filesFor(memoryRoot)).readForDisplay().contains("target preference"))
    }

    @Test
    fun allScopeReturnsAnIndependentBoundedExperiencePage() = runBlocking {
        val record = experienceRecord()
        database.experienceDao().apply(
            key = "done-call:0",
            turnId = record.turnId,
            row = ExperienceEntity(record.update.id, record.revision, Json.encodeToString(record),
                record.update.title, record.update.package_name, true, false, 50),
            tokens = "filter price range",
            error = null,
            now = 50
        )

        val output = Json.parseToJsonElement(
            SessionIndex(database, durableMemoryStore(), eventStore, experienceRepository)
                .formatSearch("price", limit = 5, scope = "all")
        ).jsonObject
        val page = output.getValue("experience_page").jsonObject

        assertEquals("experience", page.getValue("scope").jsonPrimitive.content)
        assertEquals("indexed_methods_require_current_verification", page.getValue("coverage").jsonPrimitive.content)
        assertEquals("experience:price_filter:1", page.getValue("items").jsonArray.single()
            .jsonObject.getValue("ref").jsonPrimitive.content)
        assertEquals(true, page.getValue("items").jsonArray.single().jsonObject
            .getValue("requires_current_verification").jsonPrimitive.boolean)
        assertTrue(output.getValue("items").jsonArray.none {
            it.jsonObject.getValue("scope").jsonPrimitive.content == "experience"
        })
    }

    @Test
    fun candidateFeedbackFiltersRevisionBeforeApplyingTheRecentWindow() = runBlocking {
        val original = experienceRecord()
        assertEquals("APPLIED", experienceRepository.save("original-method", original))
        repeat(6) { index ->
            val use = ExperienceUseRecord("old-use-$index", original.ref, original.threadId, original.turnId,
                1, "adopted", "Historical use", recordedAt = 1_000L + index)
            assertEquals("APPLIED", experienceRepository.beginUse(use))
            assertEquals("APPLIED", experienceRepository.assessUse("old-assessment-$index",
                use.copy(outcome = "succeeded", assessedAt = 2_000L + index)))
        }
        val current = original.copy(update = original.update.copy(expected_revision = 1,
            method = "Inspect the panel and confirm the applied filter."), revision = 2)
        assertEquals("APPLIED", experienceRepository.save("corrected-method", current))
        listOf("failed", "unknown").forEachIndexed { index, outcome ->
            val use = ExperienceUseRecord("current-use-$index", current.ref, current.threadId, current.turnId,
                1, "adopted", "Current revision use", recordedAt = 10L + index)
            assertEquals("APPLIED", experienceRepository.beginUse(use))
            assertEquals("APPLIED", experienceRepository.assessUse("current-assessment-$index",
                use.copy(outcome = outcome, summary = "Observed $outcome", assessedAt = 20L + index)))
        }

        // Old-version timestamps deliberately outrank the current records in the global audit.
        assertTrue(database.experienceDao().uses(current.update.id, 5).all { it.experienceRevision == 1L })
        val hint = experienceRepository.contextHints("filter", null, 3).single()
        assertEquals(current.ref, hint.ref)
        assertEquals(2, hint.feedback.recentCount)
        assertEquals(0, hint.feedback.succeeded)
        assertEquals(1, hint.feedback.failed)
        assertEquals(1, hint.feedback.unknown)
        val result = Json.parseToJsonElement(experienceRepository.formatSearch("filter", 3, null)).jsonObject
            .getValue("items").jsonArray.single().jsonObject.getValue("recent_feedback").jsonObject
        assertEquals(current.ref, result.getValue("revision").jsonPrimitive.content)
        assertEquals(2, result.getValue("recent_count").jsonPrimitive.int)
    }

    @Test
    fun finalizationPersistsCompletionLearningBeforeAdmittingItAndReplaysWithoutAnotherRevision() = runBlocking {
        val record = experienceRecord()
        val repository = repositoryWithHistory(completionLearningHistory(record))

        assertEquals(setOf(record.ref), repository.finalizeTurn(record.turnId))
        assertEquals(1L, database.experienceDao().get(record.update.id)!!.revision)
        assertEquals("TURN_PROJECTED", database.experienceDao().receipt("turn:${record.turnId}")!!.outcome)
        assertEquals(setOf(record.ref), repository.finalizeTurn(record.turnId))
        assertEquals(1L, database.experienceDao().get(record.update.id)!!.revision)
    }

    @Test
    fun completionLearningConflictDoesNotAdmitReflectionOrChangeTheExistingMethod() = runBlocking {
        val record = experienceRecord()
        assertEquals("APPLIED", experienceRepository.save("existing-method", record))
        val proposed = record.copy(update = record.update.copy(method = "A conflicting proposal."))
        val repository = repositoryWithHistory(completionLearningHistory(proposed))

        assertTrue(repository.finalizeTurn(record.turnId).isEmpty())
        assertEquals(record.update.method, repository.current(record.ref)!!.update.method)
        assertEquals("EXPERIENCE_REVISION_CONFLICT", database.experienceDao().receipt("learning-event-4:0")!!.outcome)
        assertFalse(repository.storageFailed.value)
    }

    private fun repositoryWithHistory(history: List<AgentEventEnvelope>): ExperienceRepository {
        val source = object : AgentEventStore by eventStore {
            override suspend fun eventsForTurn(turnId: String) = history.filter { it.turnId == turnId }
        }
        return ExperienceRepository(database, context, source,
            RuleConfigManager(context, filesFor(memoryRoot), LanguageManager(context)), experienceScope)
    }

    private fun completionLearningHistory(record: ExperienceRecord): List<AgentEventEnvelope> {
        val source = "learning-event-2"
        val proposal = record.update.copy(evidence_refs = listOf(source))
        fun call(name: String, callId: String, args: String, binding: ToolBinding) = ToolCallEventPayload(
            MODEL_ROUND_EVENT_CONTRACT_VERSION, 0, callId, name, args, ToolCallSource.MODEL_REQUESTED, binding,
            ToolEffect.READ_ONLY, "a".repeat(64), ToolPresentationSnapshot("tool.$name",
                ToolPresentationCategory.SUPPORTING, ResultImageRole.NONE), "detail")
        return listOf(
            learningEvent(1, AgentEventKind.TOOL_CALL, record, "read", call("android_read", "read", "{}", ToolBinding.DEVICE_BOUND)),
            learningEvent(2, AgentEventKind.TOOL_FINISHED, record, "read", ToolFinishedEventPayload(
                AGENT_READ_EVENT_CONTRACT_VERSION, ToolProtocolResult.success("Original current device data."), "round")),
            learningEvent(3, AgentEventKind.TOOL_CALL, record, "done", call("done", "done", buildJsonObject {
                put("experience_updates", Json.encodeToJsonElement(listOf(proposal)))
            }.toString(), ToolBinding.TURN_LOCAL)),
            learningEvent(4, AgentEventKind.TOOL_FINISHED, record, "done", ToolFinishedEventPayload(
                AGENT_READ_EVENT_CONTRACT_VERSION, ToolProtocolResult.success("Completion recorded."), "round")),
            learningEvent(5, AgentEventKind.TURN_COMPLETED, record, null, TurnCompletedEventPayload(
                AGENT_READ_EVENT_CONTRACT_VERSION, "done", systemNotice = "Complete.")))
    }

    private inline fun <reified T> learningEvent(sequence: Long, kind: AgentEventKind, record: ExperienceRecord,
                                                callId: String?, payload: T): AgentEventEnvelope = AgentEventEnvelope(
        AGENT_EVENT_SCHEMA_VERSION, "learning-event-$sequence", kind, sequence, sequence * 10,
        record.threadId, record.turnId, "round", null, null, callId, "a".repeat(64),
        Json.encodeToString(payload), "learning-key-$sequence")

    private fun durableMemoryStore(): DurableMemoryStore {
        val files = filesFor(memoryRoot)
        return DurableMemoryStore(ProfileStore(files), LongTermMemoryStore(files))
    }

    private fun filesFor(root: File): MarkdownFileManager = MarkdownFileManager(object : ContextWrapper(context) {
        override fun getExternalFilesDir(type: String?): File = File(root, "external").also { it.mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").also { it.mkdirs() }
        override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
    })

    private fun experienceRecord(): ExperienceRecord {
        val update = ExperienceUpdate(
            id = "price_filter",
            expected_revision = 0,
            title = "Filter a result list by price",
            package_name = "example.app",
            parameters = listOf("minimum", "maximum"),
            preconditions = "A result list is visible",
            method = "Open the filter panel and apply the requested range.",
            decision_boundaries = "Observe again after the panel opens.",
            expected_result = "The selected range appears in the active filters.",
            recovery = "Reobserve the page and replan if the filter differs.",
            requirement_id = "",
            outcome = "unknown",
            assessment = "The method is historical and requires fresh verification.",
            evidence_refs = listOf("observation:read-only-source")
        )
        return ExperienceRecord(update, 1, "experience-thread", "experience-turn", 12,
            listOf("read-only-source-event"), emptyList(), 50)
    }

    private fun candidate(index: Int): MemoryCandidate = MemoryCandidate(
        version = MEMORY_CANDIDATE_VERSION,
        candidateHash = index.toString(16).padStart(64, '0'),
        threadId = "thread-$index",
        turnId = "turn-$index",
        sourceThroughSequence = index.toLong() + 1,
        userInput = "历史记录 target item $index",
        status = "done",
        resultText = "bounded result $index",
        steps = listOf(
            MemoryCandidateStep(
                callId = "call-$index",
                toolName = "android_read",
                argumentsJson = "{}",
                effect = ToolEffect.READ_ONLY,
                executionStatus = "SUCCEEDED",
                evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                evidenceRef = null,
                outputSummary = "read $index",
                sourceCallSequence = index.toLong() + 1,
                settlementSequence = index.toLong() + 2
            )
        ),
        verificationEvidenceRefs = emptyList(),
        hasVerifiedStateChange = false,
        extractedAt = index.toLong() + 1
    )
}
