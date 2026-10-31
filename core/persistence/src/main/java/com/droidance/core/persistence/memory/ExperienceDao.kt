// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*

import com.droidance.core.persistence.memory.*

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "experiences")
data class ExperienceEntity(@PrimaryKey val id: String, val revision: Long, val document: String,
    val title: String, val packageName: String, val enabled: Boolean, val deleted: Boolean, val updatedAt: Long)
@Entity(tableName = "experience_revisions", primaryKeys = ["id", "revision"])
data class ExperienceRevisionEntity(val id: String, val revision: Long, val document: String)
@Entity(tableName = "experience_receipts")
data class ExperienceReceiptEntity(@PrimaryKey val key: String, val turnId: String, val outcome: String, val recordedAt: Long)
@Fts4
@Entity(tableName = "experience_search")
data class ExperienceSearchEntity(val id: String, val tokens: String)
data class ExperienceSearchHit(@Embedded val row: ExperienceEntity, val searchTokens: String)
@Entity(tableName = "experience_uses", indices = [Index(value = ["experienceId", "updatedAt"])])
data class ExperienceUseEntity(@PrimaryKey val useId: String, val experienceId: String,
    val experienceRevision: Long, val threadId: String, val turnId: String, val document: String,
    val outcome: String, val updatedAt: Long)

@Dao
abstract class ExperienceDao {
    @Query("SELECT * FROM experiences WHERE id = :id") abstract suspend fun get(id: String): ExperienceEntity?
    @Query("SELECT * FROM experience_revisions WHERE id = :id AND revision = :revision") abstract suspend fun revision(id: String, revision: Long): ExperienceRevisionEntity?
    @Query("SELECT * FROM experiences WHERE deleted = 0 ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    abstract fun watch(limit: Int, offset: Int): Flow<List<ExperienceEntity>>
    @Query("SELECT * FROM experience_receipts WHERE outcome != 'APPLIED' AND outcome != 'TURN_PROJECTED' ORDER BY recordedAt DESC LIMIT :limit")
    abstract fun failures(limit: Int): Flow<List<ExperienceReceiptEntity>>
    @Query("SELECT * FROM experience_receipts WHERE `key` = :key") abstract suspend fun receipt(key: String): ExperienceReceiptEntity?
    @Query("SELECT COALESCE(SUM(revision), 0) FROM experiences") abstract suspend fun clock(): Long
    @Query("SELECT e.*, s.tokens AS searchTokens FROM experiences e JOIN experience_search s ON e.id = s.id WHERE s.tokens MATCH :query AND e.enabled = 1 AND e.deleted = 0 ORDER BY e.updatedAt DESC, e.id LIMIT :limit")
    abstract suspend fun match(query: String, limit: Int): List<ExperienceSearchHit>
    @Query("SELECT * FROM experiences WHERE deleted = 0") abstract suspend fun indexed(): List<ExperienceEntity>
    @Query("SELECT * FROM experiences WHERE enabled = 1 AND deleted = 0 ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    abstract suspend fun recent(limit: Int, offset: Int): List<ExperienceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun put(row: ExperienceEntity)
    @Insert protected abstract suspend fun insertRevision(row: ExperienceRevisionEntity)
    @Insert protected abstract suspend fun insertSearch(row: ExperienceSearchEntity)
    @Query("DELETE FROM experience_revisions WHERE id = :id") protected abstract suspend fun removeRevisions(id: String)
    @Query("DELETE FROM experience_search WHERE id = :id") protected abstract suspend fun removeSearch(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun record(row: ExperienceReceiptEntity)
    @Query("SELECT * FROM experience_uses WHERE useId = :useId") abstract suspend fun use(useId: String): ExperienceUseEntity?
    @Query("SELECT * FROM experience_uses WHERE experienceId = :id ORDER BY updatedAt DESC, useId LIMIT :limit")
    abstract suspend fun uses(id: String, limit: Int): List<ExperienceUseEntity>
    @Query("SELECT * FROM experience_uses WHERE experienceId = :id AND experienceRevision = :revision ORDER BY updatedAt DESC, useId LIMIT :limit")
    abstract suspend fun usesAtRevision(id: String, revision: Long, limit: Int): List<ExperienceUseEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertUse(row: ExperienceUseEntity)
    @Query("UPDATE experience_uses SET document = :document, outcome = :outcome, updatedAt = :now WHERE useId = :useId AND outcome = 'adopted'")
    protected abstract suspend fun finishUse(useId: String, document: String, outcome: String, now: Long): Int
    @Query("DELETE FROM experience_uses WHERE experienceId = :id") protected abstract suspend fun removeUses(id: String)

    @Transaction
    open suspend fun apply(key: String, turnId: String, row: ExperienceEntity?, tokens: String, error: String?, now: Long) {
        if (receipt(key) != null) return
        val old = row?.let { get(it.id) }
        val result = when {
            row == null -> error ?: "EXPERIENCE_INVALID"
            old?.deleted == true -> "EXPERIENCE_DELETED"
            old?.enabled == false -> "EXPERIENCE_DISABLED"
            (old?.revision ?: 0L) != row.revision - 1 -> "EXPERIENCE_REVISION_CONFLICT"
            else -> {
                put(row.copy(enabled = old?.enabled ?: true))
                insertRevision(ExperienceRevisionEntity(row.id, row.revision, row.document))
                removeSearch(row.id); insertSearch(ExperienceSearchEntity(row.id, tokens))
                "APPLIED"
            }
        }
        record(ExperienceReceiptEntity(key, turnId, result, now))
    }

    /** Rebuilds derived search terms; method documents and revisions stay unchanged. */
    @Transaction
    open suspend fun reindex(id: String, tokens: String) {
        removeSearch(id); insertSearch(ExperienceSearchEntity(id, tokens))
    }

    @Transaction
    open suspend fun beginUse(row: ExperienceUseEntity): String {
        receipt(row.useId)?.let { return it.outcome }
        val current = get(row.experienceId)
        val result = when {
            current == null || current.deleted -> "EXPERIENCE_REFERENCE_UNAVAILABLE"
            !current.enabled -> "EXPERIENCE_DISABLED"
            current.revision != row.experienceRevision -> "EXPERIENCE_REVISION_CONFLICT"
            else -> { insertUse(row); "APPLIED" }
        }
        record(ExperienceReceiptEntity(row.useId, row.turnId, result, row.updatedAt))
        return result
    }

    @Transaction
    open suspend fun assessUse(key: String, row: ExperienceUseEntity): String {
        receipt(key)?.let { return it.outcome }
        val old = use(row.useId)
        val current = get(row.experienceId)
        val result = when {
            old == null || current == null || current.deleted -> "EXPERIENCE_USE_NOT_FOUND"
            !current.enabled -> "EXPERIENCE_DISABLED"
            old.experienceId != row.experienceId || old.experienceRevision != row.experienceRevision ||
                old.threadId != row.threadId || old.turnId != row.turnId -> "EXPERIENCE_USE_MISMATCH"
            old.outcome != "adopted" -> "EXPERIENCE_USE_ALREADY_ASSESSED"
            else -> { check(finishUse(row.useId, row.document, row.outcome, row.updatedAt) == 1); "APPLIED" }
        }
        record(ExperienceReceiptEntity(key, row.turnId, result, row.updatedAt))
        return result
    }

    @Transaction
    open suspend fun control(id: String, expectedRevision: Long, enabled: Boolean, deleted: Boolean, now: Long) {
        val old = requireNotNull(get(id))
        require(old.revision == expectedRevision && !old.deleted) { "EXPERIENCE_REVISION_CONFLICT" }
        val revised = kotlinx.serialization.json.Json.decodeFromString<ExperienceRecord>(old.document).copy(revision = old.revision + 1)
        val document = kotlinx.serialization.json.Json.encodeToString(ExperienceRecord.serializer(), revised)
        put(old.copy(revision = revised.revision, document = document, enabled = enabled && !deleted, deleted = deleted, updatedAt = now))
        insertRevision(ExperienceRevisionEntity(id, revised.revision, document))
        record(ExperienceReceiptEntity("control:$id:${old.revision + 1}", "", "APPLIED", now))
        if (deleted) {
            removeUses(id)
            removeSearch(id)
            removeRevisions(id)
            put(old.copy(revision = revised.revision, document = "", title = "", packageName = "", enabled = false, deleted = true, updatedAt = now))
        }
    }
}
