// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*

import com.droidance.core.persistence.memory.*

import androidx.room.Fts4
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "memory_candidates")
data class MemoryCandidateEntity(
    @PrimaryKey
    @ColumnInfo(name = "candidate_hash")
    val candidateHash: String,
    val version: Int,
    @ColumnInfo(name = "candidate_json")
    val candidateJson: String,
    @ColumnInfo(name = "search_text")
    val searchText: String,
    val status: String,
    val occurrences: Int,
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
    @ColumnInfo(name = "source_through_sequence")
    val sourceThroughSequence: Long,
    @ColumnInfo(name = "has_verified_state_change")
    val hasVerifiedStateChange: Boolean
)

@Entity(tableName = "memory_candidate_sources")
data class MemoryCandidateSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "turn_id")
    val turnId: String,
    @ColumnInfo(name = "candidate_hash")
    val candidateHash: String,
    @ColumnInfo(name = "source_through_sequence")
    val sourceThroughSequence: Long
)

/** Rebuildable token index. References point to canonical memory documents or EventStore-derived episodes. */
@Fts4
@Entity(tableName = "memory_search")
data class MemorySearchEntity(val ref: String, val scope: String, val tokens: String, val revision: String)

@Dao
abstract class MemoryCandidateDao {
    @Query("SELECT * FROM memory_candidate_sources WHERE turn_id = :turnId LIMIT 1")
    protected abstract suspend fun sourceForTurn(turnId: String): MemoryCandidateSourceEntity?

    @Query("SELECT * FROM memory_candidate_sources WHERE candidate_hash = :candidateHash ORDER BY source_through_sequence")
    abstract suspend fun sourcesForCandidate(candidateHash: String): List<MemoryCandidateSourceEntity>

    @Query("SELECT * FROM memory_candidates WHERE candidate_hash = :candidateHash LIMIT 1")
    abstract suspend fun candidate(candidateHash: String): MemoryCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidate(candidate: MemoryCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSource(source: MemoryCandidateSourceEntity)

    @Query(
        """
        UPDATE memory_candidates
        SET candidate_json = :candidateJson,
            search_text = :searchText,
            status = :status,
            occurrences = occurrences + 1,
            last_seen_at = :lastSeenAt,
            source_through_sequence = :sourceThroughSequence,
            has_verified_state_change = has_verified_state_change OR :hasVerifiedStateChange
        WHERE candidate_hash = :candidateHash
        """
    )
    protected abstract suspend fun incrementCandidate(
        candidateHash: String,
        candidateJson: String,
        searchText: String,
        status: String,
        lastSeenAt: Long,
        sourceThroughSequence: Long,
        hasVerifiedStateChange: Boolean
    )

    @Query("SELECT * FROM memory_candidates ORDER BY last_seen_at DESC LIMIT :limit")
    abstract suspend fun recent(limit: Int): List<MemoryCandidateEntity>

    @Query("SELECT * FROM memory_candidates ORDER BY candidate_hash")
    abstract suspend fun allCandidates(): List<MemoryCandidateEntity>

    @Query("SELECT * FROM memory_search")
    abstract suspend fun searchIndex(): List<MemorySearchEntity>

    @Query("SELECT * FROM memory_search WHERE tokens MATCH :query")
    abstract suspend fun match(query: String): List<MemorySearchEntity>

    @Query("DELETE FROM memory_search WHERE ref = :ref")
    abstract suspend fun removeSearch(ref: String)

    @Insert
    abstract suspend fun insertSearch(entry: MemorySearchEntity)

    @Transaction
    open suspend fun synchronizeSearch(entries: List<MemorySearchEntity>) {
        val expected = entries.associateBy { it.ref }
        val existing = searchIndex().associateBy { it.ref }
        existing.keys.filterNot(expected::containsKey).forEach { removeSearch(it) }
        expected.forEach { (ref, entry) ->
            if (existing[ref] != entry) { removeSearch(ref); insertSearch(entry) }
        }
    }

    @Transaction
    open suspend fun consolidate(
        candidate: MemoryCandidateEntity,
        source: MemoryCandidateSourceEntity
    ): MemoryCandidateEntity {
        val existingSource = sourceForTurn(source.turnId)
        if (existingSource != null) {
            require(existingSource.candidateHash == source.candidateHash &&
                existingSource.sourceThroughSequence == source.sourceThroughSequence
            ) {
                "Memory candidate source turn changed after projection"
            }
            return requireNotNull(candidate(existingSource.candidateHash))
        }
        val existing = candidate(candidate.candidateHash)
        if (existing == null) {
            insertCandidate(candidate)
        } else {
            val mergedCandidateJson = MemoryCandidateJsonConsolidator.merge(
                existing.candidateJson,
                candidate.candidateJson
            )
            incrementCandidate(
                candidateHash = candidate.candidateHash,
                candidateJson = mergedCandidateJson,
                searchText = candidate.searchText,
                status = candidate.status,
                lastSeenAt = candidate.lastSeenAt,
                sourceThroughSequence = maxOf(existing.sourceThroughSequence, candidate.sourceThroughSequence),
                hasVerifiedStateChange = candidate.hasVerifiedStateChange
            )
        }
        insertSource(source)
        return requireNotNull(candidate(candidate.candidateHash))
    }
}

@Database(
    entities = [MemoryCandidateEntity::class, MemoryCandidateSourceEntity::class, MemorySearchEntity::class, ExperienceEntity::class, ExperienceRevisionEntity::class, ExperienceReceiptEntity::class, ExperienceSearchEntity::class, ExperienceUseEntity::class],
    version = 4,
    exportSchema = true
)
abstract class MemoryCandidateDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS experience_uses (useId TEXT NOT NULL PRIMARY KEY, experienceId TEXT NOT NULL, experienceRevision INTEGER NOT NULL, threadId TEXT NOT NULL, turnId TEXT NOT NULL, document TEXT NOT NULL, outcome TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_experience_uses_experienceId_updatedAt ON experience_uses (experienceId, updatedAt)")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS experiences (id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, document TEXT NOT NULL, title TEXT NOT NULL, packageName TEXT NOT NULL, enabled INTEGER NOT NULL, deleted INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS experience_revisions (id TEXT NOT NULL, revision INTEGER NOT NULL, document TEXT NOT NULL, PRIMARY KEY(id, revision))")
                db.execSQL("CREATE TABLE IF NOT EXISTS experience_receipts (`key` TEXT NOT NULL PRIMARY KEY, turnId TEXT NOT NULL, outcome TEXT NOT NULL, recordedAt INTEGER NOT NULL)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS experience_search USING FTS4(id TEXT NOT NULL, tokens TEXT NOT NULL)")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `memory_search` USING FTS4(`ref` TEXT NOT NULL, `scope` TEXT NOT NULL, `tokens` TEXT NOT NULL, `revision` TEXT NOT NULL)")
            }
        }
    }
    abstract fun experienceDao(): ExperienceDao
    abstract fun memoryCandidateDao(): MemoryCandidateDao
}
