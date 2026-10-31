// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.persistence.agent.event.*

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AgentEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AgentEventEntity)

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM agent_events")
    suspend fun maxSequence(): Long

    @Query("SELECT * FROM agent_events WHERE idempotency_key = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): AgentEventEntity?

    @Query("SELECT * FROM agent_events WHERE turn_terminal_key = :turnId LIMIT 1")
    suspend fun findTurnCompletion(turnId: String): AgentEventEntity?

    @Query("SELECT * FROM agent_events WHERE sequence = :sequence LIMIT 1")
    suspend fun findBySequence(sequence: Long): AgentEventEntity?

    @Query("SELECT * FROM agent_events WHERE round_start_key = :roundId LIMIT 1")
    suspend fun findRoundStart(roundId: String): AgentEventEntity?

    @Query("SELECT * FROM agent_events WHERE round_completion_key = :roundId LIMIT 1")
    suspend fun findRoundCompletion(roundId: String): AgentEventEntity?

    @Query("SELECT * FROM agent_events WHERE round_id = :roundId ORDER BY sequence")
    suspend fun eventsForRound(roundId: String): List<AgentEventEntity>

    @Query("SELECT * FROM agent_events WHERE call_id = :callId ORDER BY sequence")
    suspend fun eventsForCall(callId: String): List<AgentEventEntity>

    @Query("SELECT * FROM agent_events WHERE attempt_id = :attemptId ORDER BY sequence")
    suspend fun eventsForAttempt(attemptId: String): List<AgentEventEntity>

    @Query("SELECT * FROM agent_events WHERE attempt_creation_key = :attemptId LIMIT 1")
    suspend fun findAttemptCreation(attemptId: String): AgentEventEntity?

    @Query("SELECT * FROM agent_events WHERE execution_creation_key = :executionId LIMIT 1")
    suspend fun findExecutionCreation(executionId: String): AgentEventEntity?

    @Query("SELECT * FROM agent_events WHERE thread_id = :threadId ORDER BY sequence")
    suspend fun eventsForThread(threadId: String): List<AgentEventEntity>

    @Query("DELETE FROM agent_events WHERE thread_id = :threadId")
    suspend fun deleteEventsForThread(threadId: String): Int

    @Query("SELECT payload_json FROM agent_events")
    suspend fun allPayloads(): List<String>

    @Query(
        """
        SELECT thread_id
        FROM agent_events
        WHERE event_kind = 'THREAD_CREATED'
        ORDER BY sequence
        """
    )
    suspend fun createdThreadIds(): List<String>

    @Query(
        """
        SELECT entry.thread_id
        FROM agent_events AS entry
        WHERE entry.event_kind IN ('USER_MESSAGE', 'USER_STEER', 'ASSISTANT_MESSAGE', 'SYSTEM_NOTICE', 'TURN_COMPLETED')
          AND entry.thread_id IN (
              SELECT thread_id FROM agent_events WHERE event_kind = 'THREAD_CREATED'
          )
          AND entry.thread_id NOT IN (
              SELECT thread_id FROM agent_events WHERE event_kind = 'THREAD_TOMBSTONED'
          )
        GROUP BY entry.thread_id
        ORDER BY MAX(entry.sequence) DESC
        """
    )
    suspend fun conversationThreadIdsByRecency(): List<String>

    @Query(
        """
        SELECT thread_id
        FROM agent_events
        WHERE event_kind = 'THREAD_CREATED'
        ORDER BY sequence
        """
    )
    fun observeCreatedThreadIds(): Flow<List<String>>

    @Query("SELECT MAX(sequence) FROM agent_events WHERE thread_id = :threadId")
    fun observeThreadRevision(threadId: String): Flow<Long?>

    @Query("SELECT * FROM agent_events WHERE turn_id = :turnId ORDER BY sequence")
    suspend fun eventsForTurn(turnId: String): List<AgentEventEntity>

    @Query("SELECT * FROM agent_events WHERE event_kind = :eventKind ORDER BY sequence")
    suspend fun eventsByKind(eventKind: String): List<AgentEventEntity>

    @Query(
        """
        SELECT * FROM agent_events
        WHERE thread_id NOT IN (
            SELECT thread_id FROM agent_events WHERE event_kind = 'THREAD_TOMBSTONED'
        )
        ORDER BY sequence
        """
    )
    suspend fun exportSnapshot(): List<AgentEventEntity>

    @Query(
        """
        SELECT * FROM agent_events AS started
        WHERE started.event_kind = 'TURN_STARTED'
          AND NOT EXISTS (
              SELECT 1 FROM agent_events AS terminal
              WHERE terminal.turn_terminal_key = started.turn_id
          )
        ORDER BY started.sequence
        """
    )
    suspend fun unfinishedTurnStarts(): List<AgentEventEntity>

    @Query("SELECT COUNT(*) FROM agent_events")
    suspend fun count(): Int
}

@Database(
    entities = [AgentEventEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AgentEventDatabase : RoomDatabase() {
    internal abstract fun agentEventDao(): AgentEventDao
}
