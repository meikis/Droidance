// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillLearningSessionProjectorTest {
    @Test
    fun rebuildsLiveAndCompletedStateOnlyFromPersistedEvents() {
        val started = LearningSessionStartedEventPayload(
            contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
            sessionId = SESSION_ID,
            captureMode = "user_virtual_space",
            skillName = "Example Skill",
            goal = "Complete the example flow"
        )
        val action = LearningActionRecordedEventPayload(
            contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
            sessionId = SESSION_ID,
            actionIndex = 1,
            source = LearningActionSource.USER_DEMONSTRATION,
            sourceCallId = null,
            toolName = "user_demonstration",
            argumentsJson = "{\"op\":\"tap\"}",
            resultSummary = "Recorded action",
            success = true,
            evidenceRef = "learning/$SESSION_ID/frame.jpg",
            packageName = "com.example"
        )
        val live = SkillLearningSessionProjector.project(
            listOf(
                event(1, AgentEventKind.LEARNING_SESSION_STARTED, Json.encodeToString(started)),
                event(2, AgentEventKind.LEARNING_ACTION_RECORDED, Json.encodeToString(action))
            )
        )

        assertEquals("Example Skill", live.started.skillName)
        assertEquals(20L, live.actions.single().occurredAt)
        assertEquals(action, live.actions.single().payload)
        assertFalse(live.completed != null)

        val completed = SkillLearningSessionProjector.project(
            listOf(
                event(1, AgentEventKind.LEARNING_SESSION_STARTED, Json.encodeToString(started)),
                event(2, AgentEventKind.LEARNING_ACTION_RECORDED, Json.encodeToString(action)),
                event(
                    3,
                    AgentEventKind.LEARNING_SESSION_COMPLETED,
                    Json.encodeToString(
                        LearningSessionCompletedEventPayload(
                            contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
                            sessionId = SESSION_ID,
                            actionCount = 1,
                            durationMillis = 500L
                        )
                    )
                )
            )
        )

        assertTrue(completed.completed != null)
        assertEquals(500L, completed.completed?.durationMillis)
    }

    @Test
    fun rejectsACompletionThatDoesNotMatchPersistedActions() {
        val started = LearningSessionStartedEventPayload(
            contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
            sessionId = SESSION_ID,
            captureMode = "user_virtual_space"
        )
        val completed = LearningSessionCompletedEventPayload(
            contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
            sessionId = SESSION_ID,
            actionCount = 1,
            durationMillis = 10L
        )

        assertThrows(IllegalArgumentException::class.java) {
            SkillLearningSessionProjector.project(
                listOf(
                    event(1, AgentEventKind.LEARNING_SESSION_STARTED, Json.encodeToString(started)),
                    event(2, AgentEventKind.LEARNING_SESSION_COMPLETED, Json.encodeToString(completed))
                )
            )
        }
    }

    private fun event(
        sequence: Long,
        kind: AgentEventKind,
        payloadJson: String
    ) = AgentEventEnvelope(
        schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
        eventId = "event-$sequence",
        eventKind = kind,
        sequence = sequence,
        occurredAt = sequence * 10L,
        threadId = "skill-learning:$SESSION_ID",
        turnId = null,
        roundId = null,
        attemptId = null,
        executionId = null,
        callId = null,
        payloadHash = "a".repeat(64),
        payloadJson = payloadJson,
        idempotencyKey = "event:$sequence"
    )

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
