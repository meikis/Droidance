// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.os.Bundle
import com.droidance.core.agent.event.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class RuntimeEventServer(private val store: AgentEventStore) {
    suspend fun exchange(operation: String, args: Bundle): Bundle {
        val result = when (operation) {
            "append" -> Json.encodeToString<AgentEventEnvelope>(store.append(
                event = Json.decodeFromString<AgentEventDraft>(args.getString("event")!!)
            ))
            "appendAtomically" -> Json.encodeToString<List<AgentEventEnvelope>>(store.appendAtomically(
                events = Json.decodeFromString<List<AgentEventDraft>>(args.getString("events")!!)
            ))
            "recordLearningSessionStarted" -> Json.encodeToString<AgentEventEnvelope>(store.recordLearningSessionStarted(
                sessionId = Json.decodeFromString<String>(args.getString("sessionId")!!),
                captureMode = Json.decodeFromString<String>(args.getString("captureMode")!!),
                skillName = Json.decodeFromString<String?>(args.getString("skillName")!!),
                goal = Json.decodeFromString<String?>(args.getString("goal")!!),
                packageName = Json.decodeFromString<String?>(args.getString("packageName")!!),
                appLabel = Json.decodeFromString<String?>(args.getString("appLabel")!!)
            ))
            "recordLearningAction" -> Json.encodeToString<AgentEventEnvelope>(store.recordLearningAction(
                payload = Json.decodeFromString<LearningActionRecordedEventPayload>(args.getString("payload")!!)
            ))
            "recordLearningSessionCompleted" -> Json.encodeToString<AgentEventEnvelope>(store.recordLearningSessionCompleted(
                payload = Json.decodeFromString<LearningSessionCompletedEventPayload>(args.getString("payload")!!)
            ))
            "recordLearningSkillCompiled" -> Json.encodeToString<AgentEventEnvelope>(store.recordLearningSkillCompiled(
                payload = Json.decodeFromString<LearningSkillCompiledEventPayload>(args.getString("payload")!!)
            ))
            "recordCompiledSkillRunStarted" -> Json.encodeToString<AgentEventEnvelope>(store.recordCompiledSkillRunStarted(
                payload = Json.decodeFromString<CompiledSkillRunStartedEventPayload>(args.getString("payload")!!)
            ))
            "recordCompiledSkillRunCompleted" -> Json.encodeToString<AgentEventEnvelope>(store.recordCompiledSkillRunCompleted(
                payload = Json.decodeFromString<CompiledSkillRunCompletedEventPayload>(args.getString("payload")!!)
            ))
            "eventsByKind" -> Json.encodeToString<List<AgentEventEnvelope>>(store.eventsByKind(
                eventKind = Json.decodeFromString<AgentEventKind>(args.getString("eventKind")!!)
            ))
            "createThread" -> Json.encodeToString<AgentEventEnvelope>(store.createThread(
                threadId = Json.decodeFromString<String>(args.getString("threadId")!!),
                initialTitle = Json.decodeFromString<String>(args.getString("initialTitle")!!)
            ))
            "changeThreadMetadata" -> Json.encodeToString<AgentEventEnvelope>(store.changeThreadMetadata(
                threadId = Json.decodeFromString<String>(args.getString("threadId")!!),
                changeId = Json.decodeFromString<String>(args.getString("changeId")!!),
                title = Json.decodeFromString<String>(args.getString("title")!!),
                summary = Json.decodeFromString<String?>(args.getString("summary")!!)
            ))
            "changeThreadPin" -> Json.encodeToString<AgentEventEnvelope>(store.changeThreadPin(
                threadId = Json.decodeFromString<String>(args.getString("threadId")!!),
                changeId = Json.decodeFromString<String>(args.getString("changeId")!!),
                isPinned = Json.decodeFromString<Boolean>(args.getString("isPinned")!!)
            ))
            "tombstoneThread" -> Json.encodeToString<AgentEventEnvelope>(store.tombstoneThread(
                threadId = Json.decodeFromString<String>(args.getString("threadId")!!)
            ))
            "tombstoneAllThreads" -> Json.encodeToString<List<AgentEventEnvelope>>(store.tombstoneAllThreads(

            ))
            "allReferencedBlobRefs" -> Json.encodeToString<Set<String>>(store.allReferencedBlobRefs(

            ))
            "eventsForThread" -> Json.encodeToString<List<AgentEventEnvelope>>(store.eventsForThread(
                threadId = Json.decodeFromString<String>(args.getString("threadId")!!)
            ))
            "eventsForLearningSession" -> Json.encodeToString<List<AgentEventEnvelope>>(store.eventsForLearningSession(
                sessionId = Json.decodeFromString<String>(args.getString("sessionId")!!)
            ))
            "eventsForTurn" -> Json.encodeToString<List<AgentEventEnvelope>>(store.eventsForTurn(
                turnId = Json.decodeFromString<String>(args.getString("turnId")!!)
            ))
            "exportSnapshot" -> Json.encodeToString<List<AgentEventEnvelope>>(store.exportSnapshot(

            ))
            "submittedItemRangeForTurn" -> Json.encodeToString<SubmittedItemSequenceRange>(store.submittedItemRangeForTurn(
                turnId = Json.decodeFromString<String>(args.getString("turnId")!!)
            ))
            "latestConversationThreadId" -> Json.encodeToString<String?>(store.latestConversationThreadId(

            ))
            "count" -> Json.encodeToString<Int>(store.count(

            ))
            else -> error("Unsupported event operation: $operation")
        }
        return Bundle().apply { putString("result", result) }
    }
}
