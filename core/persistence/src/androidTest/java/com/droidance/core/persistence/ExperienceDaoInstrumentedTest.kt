// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.persistence.memory.ExperienceDao
import com.droidance.core.persistence.memory.ExperienceEntity
import com.droidance.core.knowledge.memory.ExperienceRecord
import com.droidance.core.knowledge.memory.ExperienceUpdate
import com.droidance.core.persistence.memory.ExperienceUseEntity
import com.droidance.core.persistence.memory.MemoryCandidateDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Room DAO and migration on Android's real SQLite implementation. */
@RunWith(AndroidJUnit4::class)
class ExperienceDaoInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: MemoryCandidateDatabase
    private lateinit var dao: ExperienceDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MemoryCandidateDatabase::class.java).build()
        dao = database.experienceDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun proposalReceiptIsIdempotentRevisionConflictIsRecordedAndTombstoneBlocksResurrection() = runBlocking {
        val first = entity(revision = 1)
        dao.apply("tool-call-1:0", "turn-1", first, "price_filter range", null, 10)
        dao.apply("tool-call-1:0", "turn-1", first, "price_filter range", null, 11)

        assertEquals(1, dao.clock())
        assertEquals("APPLIED", dao.receipt("tool-call-1:0")?.outcome)
        assertNotNull(dao.revision("price_filter", 1))
        assertEquals(listOf("price_filter"), dao.match("\"price_filter\"", 10).map { it.row.id })

        dao.apply("tool-call-2:0", "turn-2", entity(revision = 3), "price_filter range", null, 12)
        assertEquals("EXPERIENCE_REVISION_CONFLICT", dao.receipt("tool-call-2:0")?.outcome)
        assertEquals(1L, dao.get("price_filter")?.revision)

        dao.control("price_filter", expectedRevision = 1, enabled = false, deleted = true, now = 13)
        val tombstone = requireNotNull(dao.get("price_filter"))
        assertEquals(2, tombstone.revision)
        assertFalse(tombstone.enabled)
        assertTrue(tombstone.deleted)
        assertEquals("", tombstone.document)
        assertEquals("", tombstone.title)
        assertEquals("", tombstone.packageName)
        assertTrue(dao.match("\"price_filter\"", 10).isEmpty())
        assertNull(dao.revision("price_filter", 1))
        assertNull(dao.revision("price_filter", 2))

        dao.apply("queued-before-delete", "turn-3", entity(revision = 2), "price_filter range", null, 14)
        assertEquals("EXPERIENCE_DELETED", dao.receipt("queued-before-delete")?.outcome)
        assertEquals(2L, dao.get("price_filter")?.revision)
        assertTrue(requireNotNull(dao.get("price_filter")).deleted)
        assertEquals("", dao.get("price_filter")?.document)
    }

    @Test
    fun migrationFromVersionTwoPreservesExistingMemoryAndCreatesExperienceTables(): Unit = runBlocking {
        database.close()
        val name = "experience-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        createVersionTwoMemoryDatabase(file.absolutePath)

        database = Room.databaseBuilder(context, MemoryCandidateDatabase::class.java, name)
            .addMigrations(MemoryCandidateDatabase.MIGRATION_1_2, MemoryCandidateDatabase.MIGRATION_2_3, MemoryCandidateDatabase.MIGRATION_3_4)
            .build()
        dao = database.experienceDao()
        val migratedMemory = database.memoryCandidateDao().candidate("prior-candidate")
        assertNotNull(migratedMemory)
        assertEquals("prior-json", migratedMemory?.candidateJson)

        dao.record(com.droidance.core.persistence.memory.ExperienceReceiptEntity("migration-check", "turn", "APPLIED", 20))
        assertEquals("APPLIED", dao.receipt("migration-check")?.outcome)
        dao.apply("migrated-method", "turn", entity(1), "filter", null, 21)
        assertEquals("APPLIED", dao.beginUse(useEntity()))
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationFromVersionThreePreservesTheExistingMethodHistoryAndSearch(): Unit = runBlocking {
        database.close()
        val name = "experience-v3-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        createVersionTwoMemoryDatabase(file.absolutePath)
        val prior = entity(1)
        val legacyDocument = Json.encodeToString(JSON.decodeFromString<ExperienceRecord>(prior.document))
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE experiences (id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, document TEXT NOT NULL, title TEXT NOT NULL, packageName TEXT NOT NULL, enabled INTEGER NOT NULL, deleted INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE experience_revisions (id TEXT NOT NULL, revision INTEGER NOT NULL, document TEXT NOT NULL, PRIMARY KEY(id, revision))")
            db.execSQL("CREATE TABLE experience_receipts (`key` TEXT NOT NULL PRIMARY KEY, turnId TEXT NOT NULL, outcome TEXT NOT NULL, recordedAt INTEGER NOT NULL)")
            db.execSQL("CREATE VIRTUAL TABLE experience_search USING FTS4(id TEXT NOT NULL, tokens TEXT NOT NULL)")
            db.insertOrThrow("experiences", null, ContentValues().apply {
                put("id", prior.id); put("revision", prior.revision); put("document", legacyDocument)
                put("title", prior.title); put("packageName", prior.packageName)
                put("enabled", 1); put("deleted", 0); put("updatedAt", prior.updatedAt)
            })
            db.execSQL("INSERT INTO experience_revisions VALUES (?, ?, ?)", arrayOf(prior.id, prior.revision, legacyDocument))
            db.execSQL("INSERT INTO experience_search VALUES (?, ?)", arrayOf(prior.id, "filter"))
            db.execSQL("INSERT OR REPLACE INTO room_master_table VALUES (42, '5217a2c9ef6b745b2a459b20b598177f')")
            db.version = 3
        }
        database = Room.databaseBuilder(context, MemoryCandidateDatabase::class.java, name)
            .addMigrations(MemoryCandidateDatabase.MIGRATION_3_4).build()
        dao = database.experienceDao()
        assertEquals("prior-json", database.memoryCandidateDao().candidate("prior-candidate")?.candidateJson)
        assertEquals(legacyDocument, dao.get(prior.id)?.document)
        assertEquals(legacyDocument, dao.revision(prior.id, 1)?.document)
        assertEquals(prior.id, dao.match("\"filter\"", 10).single().row.id)
        assertNull(JSON.decodeFromString<ExperienceRecord>(requireNotNull(dao.get(prior.id)).document).sourceUseId)
        assertEquals("APPLIED", dao.beginUse(useEntity()))
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun adoptionAndAssessmentKeepTheExactRevisionEvenWhenTheMethodChanges() = runBlocking {
        dao.apply("save-1", "turn", entity(1), "filter", null, 10)
        val adopted = useEntity()
        assertEquals("APPLIED", dao.beginUse(adopted))
        assertEquals("APPLIED", dao.beginUse(adopted))
        assertEquals(1L, dao.clock())
        dao.apply("save-2", "turn", entity(2), "filter", null, 12)
        assertEquals("APPLIED", dao.assessUse("assess-1", adopted.copy(
            outcome = "failed", document = "checked failure", updatedAt = 13)))
        assertEquals("APPLIED", dao.assessUse("assess-1", adopted.copy(outcome = "failed", updatedAt = 13)))
        assertEquals(2L, dao.clock())
        assertEquals(1L, dao.use(adopted.useId)?.experienceRevision)
        assertEquals("failed", dao.use(adopted.useId)?.outcome)
        assertEquals(2L, dao.get("price_filter")?.revision)
        assertNotNull(dao.revision("price_filter", 1))
        assertEquals("EXPERIENCE_REVISION_CONFLICT", dao.beginUse(adopted.copy(useId = "stale-use")))
        assertEquals("EXPERIENCE_USE_ALREADY_ASSESSED", dao.assessUse("assess-again", adopted.copy(outcome = "succeeded")))
        assertEquals("failed", dao.uses("price_filter", 5).single().outcome)
    }

    @Test
    fun disabledAndDeletedMethodsRejectLearningAndDeletionPurgesUseDetails() = runBlocking {
        dao.apply("save-1", "turn", entity(1), "filter", null, 10)
        val adopted = useEntity()
        dao.beginUse(adopted)
        dao.control("price_filter", 1, enabled = false, deleted = false, now = 11)
        dao.apply("disabled-save", "turn", entity(3), "filter", null, 12)
        assertEquals("EXPERIENCE_DISABLED", dao.receipt("disabled-save")?.outcome)
        assertEquals("EXPERIENCE_DISABLED", dao.beginUse(adopted.copy(useId = "disabled-use", experienceRevision = 2)))
        assertEquals("EXPERIENCE_DISABLED", dao.assessUse("disabled-assess", adopted.copy(outcome = "unknown")))
        dao.control("price_filter", 2, enabled = false, deleted = true, now = 13)
        assertNull(dao.use(adopted.useId))
        assertTrue(dao.uses("price_filter", 5).isEmpty())
        assertEquals("EXPERIENCE_USE_NOT_FOUND", dao.assessUse("deleted-assess", adopted.copy(outcome = "failed")))
        assertNull(dao.revision("price_filter", 1))
    }

    @Test
    fun assessmentCannotChangeTheMethodOrTurnIdentityOfAnAdoption() = runBlocking {
        dao.apply("save-1", "turn", entity(1), "filter", null, 10)
        val adopted = useEntity(); dao.beginUse(adopted)
        assertEquals("EXPERIENCE_USE_MISMATCH", dao.assessUse("other-turn", adopted.copy(
            turnId = "other", outcome = "succeeded")))
        assertEquals("EXPERIENCE_USE_MISMATCH", dao.assessUse("other-version", adopted.copy(
            experienceRevision = 2, outcome = "succeeded")))
        assertEquals("adopted", dao.use(adopted.useId)?.outcome)
    }

    private fun useEntity() = ExperienceUseEntity("use-1", "price_filter", 1, "thread", "turn",
        "adopted method", "adopted", 11)

    private fun entity(revision: Long): ExperienceEntity {
        val record = ExperienceRecord(
            update = ExperienceUpdate(
                id = "price_filter",
                expected_revision = revision - 1,
                title = "Filter a result list by price",
                package_name = "example.app",
                parameters = listOf("minimum", "maximum"),
                preconditions = "A result list is visible",
                method = "Open the filter panel, enter the price range and apply it.",
                decision_boundaries = "Observe again after the panel opens.",
                expected_result = "The applied range is visible.",
                recovery = "Reobserve and decide whether to continue or replan.",
                requirement_id = "",
                outcome = "unknown",
                assessment = "Task-scoped assessment only.",
                evidence_refs = listOf("observation:current-read")
            ),
            revision = revision,
            threadId = "thread",
            turnId = "turn-$revision",
            throughSequence = revision,
            sourceEvents = listOf("source-event-$revision"),
            executionContexts = emptyList(),
            recordedAt = revision
        )
        return ExperienceEntity("price_filter", revision, JSON.encodeToString(record), record.update.title,
            record.update.package_name, true, false, revision)
    }

    private fun createVersionTwoMemoryDatabase(path: String) {
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS memory_candidates (candidate_hash TEXT NOT NULL, version INTEGER NOT NULL, candidate_json TEXT NOT NULL, search_text TEXT NOT NULL, status TEXT NOT NULL, occurrences INTEGER NOT NULL, first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, source_through_sequence INTEGER NOT NULL, has_verified_state_change INTEGER NOT NULL, PRIMARY KEY(candidate_hash))")
            db.execSQL("CREATE TABLE IF NOT EXISTS memory_candidate_sources (turn_id TEXT NOT NULL, candidate_hash TEXT NOT NULL, source_through_sequence INTEGER NOT NULL, PRIMARY KEY(turn_id))")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memory_search USING FTS4(ref TEXT NOT NULL, scope TEXT NOT NULL, tokens TEXT NOT NULL, revision TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '8e36312de1af74dcbc0cd8f78c0027c4')")
            db.execSQL("INSERT INTO memory_candidates (candidate_hash,version,candidate_json,search_text,status,occurrences,first_seen_at,last_seen_at,source_through_sequence,has_verified_state_change) VALUES ('prior-candidate',2,'prior-json','search','done',1,1,1,2,0)")
            db.version = 2
        }
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
