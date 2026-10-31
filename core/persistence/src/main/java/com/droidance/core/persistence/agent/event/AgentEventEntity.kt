// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.persistence.agent.event.*

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_events",
    indices = [
        Index(value = ["sequence"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["turn_terminal_key"], unique = true),
        Index(value = ["repair_key"], unique = true),
        Index(value = ["round_start_key"], unique = true),
        Index(value = ["round_completion_key"], unique = true),
        Index(value = ["call_declaration_key"], unique = true),
        Index(value = ["call_disposition_key"], unique = true),
        Index(value = ["attempt_creation_key"], unique = true),
        Index(value = ["execution_creation_key"], unique = true),
        Index(value = ["thread_id", "sequence"]),
        Index(value = ["turn_id", "sequence"]),
        Index(value = ["round_id", "sequence"]),
        Index(value = ["call_id", "sequence"])
    ]
)
internal data class AgentEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "event_kind")
    val eventKind: String,
    val sequence: Long,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
    @ColumnInfo(name = "thread_id")
    val threadId: String,
    @ColumnInfo(name = "turn_id")
    val turnId: String?,
    @ColumnInfo(name = "round_id")
    val roundId: String?,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String?,
    @ColumnInfo(name = "execution_id")
    val executionId: String?,
    @ColumnInfo(name = "call_id")
    val callId: String?,
    @ColumnInfo(name = "payload_hash")
    val payloadHash: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "turn_terminal_key")
    val turnTerminalKey: String?,
    @ColumnInfo(name = "repair_key")
    val repairKey: String?,
    @ColumnInfo(name = "round_start_key")
    val roundStartKey: String?,
    @ColumnInfo(name = "round_completion_key")
    val roundCompletionKey: String?,
    @ColumnInfo(name = "call_declaration_key")
    val callDeclarationKey: String?,
    @ColumnInfo(name = "call_disposition_key")
    val callDispositionKey: String?,
    @ColumnInfo(name = "attempt_creation_key")
    val attemptCreationKey: String?,
    @ColumnInfo(name = "execution_creation_key")
    val executionCreationKey: String?
)

internal fun AgentEventEntity.toEnvelope(): AgentEventEnvelope = AgentEventEnvelope(
    schemaVersion = schemaVersion,
    eventId = eventId,
    eventKind = AgentEventKind.valueOf(eventKind),
    sequence = sequence,
    occurredAt = occurredAt,
    threadId = threadId,
    turnId = turnId,
    roundId = roundId,
    attemptId = attemptId,
    executionId = executionId,
    callId = callId,
    payloadHash = payloadHash,
    payloadJson = payloadJson,
    idempotencyKey = idempotencyKey
)
