// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class SkillLearningSessionProjection(
    val started: LearningSessionStartedEventPayload,
    val actions: List<SkillLearningActionProjection>,
    val completed: LearningSessionCompletedEventPayload?
)

data class SkillLearningActionProjection(
    val occurredAt: Long,
    val payload: LearningActionRecordedEventPayload
)

/** Deterministically rebuilds the live Skill-learning view from persisted EventStore facts. */
object SkillLearningSessionProjector {
    fun project(events: List<AgentEventEnvelope>): SkillLearningSessionProjection {
        require(events.isNotEmpty()) { "A Skill learning projection requires events" }
        require(events.zipWithNext().all { (left, right) -> left.sequence < right.sequence }) {
            "Skill learning events must be strictly ordered"
        }
        val startedEvents = events.filter { it.eventKind == AgentEventKind.LEARNING_SESSION_STARTED }
        require(startedEvents.size == 1) { "A Skill learning session requires exactly one start" }
        val started = Json.decodeFromString<LearningSessionStartedEventPayload>(
            startedEvents.single().payloadJson
        )
        require(events.all { it.threadId == "skill-learning:${started.sessionId}" }) {
            "Skill learning events must belong to their session thread"
        }
        val actions = events
            .filter { it.eventKind == AgentEventKind.LEARNING_ACTION_RECORDED }
            .map {
                SkillLearningActionProjection(
                    occurredAt = it.occurredAt,
                    payload = Json.decodeFromString(it.payloadJson)
                )
            }
        require(actions.map { it.payload.actionIndex } == (1..actions.size).toList()) {
            "Skill learning actions must form a contiguous ordered sequence"
        }
        require(actions.all { it.payload.sessionId == started.sessionId }) {
            "Skill learning actions must match the started session"
        }
        val completedEvents = events.filter {
            it.eventKind == AgentEventKind.LEARNING_SESSION_COMPLETED
        }
        require(completedEvents.size <= 1) { "A Skill learning session can complete only once" }
        val completed = completedEvents.singleOrNull()?.let {
            Json.decodeFromString<LearningSessionCompletedEventPayload>(it.payloadJson)
        }
        require(completed == null || completed.sessionId == started.sessionId) {
            "Skill learning completion must match the started session"
        }
        require(completed == null || completed.actionCount == actions.size) {
            "Skill learning completion action count must match recorded actions"
        }
        return SkillLearningSessionProjection(
            started = started,
            actions = actions,
            completed = completed
        )
    }
}
