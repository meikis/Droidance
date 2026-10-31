// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AgentEventReadModelProjector
import com.droidance.core.agent.event.AgentEventStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentEventReplayArtifacts(
    val turnId: String,
    val userInput: String,
    val analysisTraceJsonl: String,
    val threadContextJson: String,
    val eventsJsonl: String,
    val readModelJson: String,
    val providerHarnessJson: String,
    val replayMarkdown: String,
    val attachments: List<AgentEventReplayAttachment>
)

data class AgentEventReplayAttachment(
    val archivePath: String,
    val contentRef: String,
    val sizeBytes: Long
)

/** Reads retained history from the one durable fact source and emits per-Turn replay projections. */
@Singleton
class AgentEventReplayExporter @Inject constructor(
    private val eventStore: AgentEventStore
) {
    suspend fun exportSnapshot(): List<AgentEventEnvelope> = eventStore.exportSnapshot()

    /** The caller may inspect a running or interrupted Turn without waiting for completion. */
    suspend fun turnArtifacts(turnId: String): AgentEventReplayArtifacts? {
        val snapshot = eventStore.exportSnapshot()
        val threadId = snapshot.firstOrNull { it.turnId == turnId }?.threadId ?: return null
        return AgentEventReplayProjection.project(snapshot.filter { it.threadId == threadId }, turnId)
    }

}

/** Pure projection used by UI/read-model replay and Mock Provider/Harness fixtures. */
object AgentEventReplayProjection {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }
    private val jsonLine = Json { encodeDefaults = true }

    fun project(
        threadEvents: List<AgentEventEnvelope>,
        turnId: String,
        attachmentRoot: String = "../"
    ): AgentEventReplayArtifacts {
        val readModel = AgentEventReadModelProjector.project(threadEvents)
        val turn = readModel.turns.singleOrNull { it.turnId == turnId }
            ?: error("Replay Turn is missing from the projected read model")
        val turnEvents = threadEvents.filter { it.turnId == turnId }
        val activity = readModel.activityByTurn[turnId]
        val toolDetails = activity?.entries.orEmpty()
            .filterIsInstance<com.droidance.core.agent.event.ToolActivityReadModel>()
            .mapNotNull { readModel.toolDetails[it.callId] }
        val replayAttachments = toolDetails.flatMap { detail ->
            detail.attachments.flatMap { attachment ->
                listOf(AgentEventReplayAttachment(
                    archivePath = attachmentArchivePath(attachment.contentRef, attachment.mimeType),
                    contentRef = attachment.contentRef,
                    sizeBytes = attachment.sizeBytes
                )) + listOfNotNull(attachment.modelContentRef?.takeUnless { it == attachment.contentRef }?.let { ref ->
                    AgentEventReplayAttachment("attachments/${com.droidance.core.agent.blob.hashFromContentRef(ref)}.blob",
                        ref, checkNotNull(attachment.modelSizeBytes))
                })
            }
        }
        val programAttachments = com.droidance.core.agent.event.ProgramEvidence.events(turnEvents, turn.threadId, turnId)
            .flatMap { event -> com.droidance.core.agent.event.ProgramEvidence.artifacts(event).map { artifact ->
                val ref = artifact.getValue("content_ref").jsonPrimitive.content
                AgentEventReplayAttachment("program/" + ref.removePrefix("sha256:") + ".blob", ref,
                    artifact.getValue("size_bytes").jsonPrimitive.long)
            } }.distinctBy { it.contentRef }
        val workspaceAttachments = com.droidance.core.agent.workspace.TaskWorkspace.versions(
            turnEvents, com.droidance.core.agent.event.TurnEventScope(turn.threadId, turnId)).map {
            AgentEventReplayAttachment("workspace/" + it.contentRef.removePrefix("sha256:") + ".blob", it.contentRef, it.sizeBytes)
        }.distinctBy { it.contentRef }
        val sourceThroughSequence = turnEvents.maxOf(AgentEventEnvelope::sequence)
        val readSnapshot = ReplayReadModelSnapshot(
            schemaVersion = 1,
            threadId = turn.threadId,
            turnId = turnId,
            state = turn.state.name,
            terminalStatus = turn.terminalStatus,
            cancellationOrigin = turnEvents.lastOrNull { it.eventKind == AgentEventKind.TURN_COMPLETED }
                ?.let { Json.decodeFromString<com.droidance.core.agent.event.TurnCompletedEventPayload>(it.payloadJson).cancellationOrigin },
            sourceThroughSequence = sourceThroughSequence,
            conversation = readModel.conversation.entries
                .filter { it.turnId == turnId }
                .map { entry ->
                    ReplayConversationEntry(
                        sequence = entry.sequence,
                        kind = entry.kind.name,
                        text = entry.text,
                        diagnosticCode = entry.diagnosticCode
                    )
                },
            analysis = TaskAnalysisMetrics.project(turnEvents),
            operationCount = activity?.operationCount ?: 0,
            publicProgress = activity?.publicProgress.orEmpty(),
            resultCardCount = readModel.resultCards.count { it.turnId == turnId }
        )
        val roundBatches = turnEvents
            .filter { it.roundId != null }
            .groupBy { requireNotNull(it.roundId) }
            .map { (roundId, events) -> ProviderHarnessRound(roundId, events) }
        val harnessSnapshot = ProviderHarnessReplaySnapshot(
            schemaVersion = 1,
            turnId = turnId,
            rounds = roundBatches,
            turnResult = turnEvents.singleOrNull { it.eventKind == AgentEventKind.TURN_COMPLETED }
        )
        return AgentEventReplayArtifacts(
            turnId = turnId,
            userInput = readModel.conversation.entries
                .firstOrNull { it.turnId == turnId }
                ?.text
                .orEmpty(),
            analysisTraceJsonl = turnEvents.joinToString("\n", postfix = "\n") { event ->
                jsonLine.encodeToString(
                    buildJsonObject {
                        put("sequence", event.sequence)
                        put("occurred_at", event.occurredAt)
                        put("kind", event.eventKind.name)
                        event.roundId?.let { put("round_id", it) }
                        event.attemptId?.let { put("attempt_id", it) }
                        event.executionId?.let { put("execution_id", it) }
                        event.callId?.let { put("call_id", it) }
                        put("payload", jsonLine.parseToJsonElement(event.payloadJson))
                    }
                )
            },
            threadContextJson = json.encodeToString(
                ReplayThreadContextSnapshot(
                    schemaVersion = 1,
                    threadId = turn.threadId,
                    turnId = turnId,
                    sourceThroughSequence = sourceThroughSequence,
                    conversation = readModel.conversation.entries
                        .filter { it.sequence <= sourceThroughSequence }
                        .map { entry ->
                            ReplayConversationEntry(
                                sequence = entry.sequence,
                                kind = entry.kind.name,
                                text = entry.text,
                                diagnosticCode = entry.diagnosticCode
                            )
                        }
                )
            ),
            eventsJsonl = turnEvents.joinToString("\n", postfix = "\n") {
                jsonLine.encodeToString(it)
            },
            readModelJson = json.encodeToString(readSnapshot),
            providerHarnessJson = json.encodeToString(harnessSnapshot),
            replayMarkdown = replayMarkdown(turnId, toolDetails, attachmentRoot),
            attachments = (replayAttachments + programAttachments + workspaceAttachments).distinctBy { it.archivePath }
        )
    }

    private fun replayMarkdown(
        turnId: String,
        tools: List<com.droidance.core.agent.event.ToolDetailReadModel>,
        attachmentRoot: String
    ): String = buildString {
        appendLine("# EventStore Tool Replay")
        appendLine()
        appendLine("Turn: `$turnId`")
        appendLine()
        tools.forEachIndexed { index, tool ->
            appendLine("## ${index + 1}. ${tool.presentation.labelResourceKey}")
            appendLine()
            appendLine("- Tool: `${tool.technicalName}`")
            appendLine("- Status: `${tool.executionStatus?.name ?: "NOT_SETTLED"}`")
            tool.diagnosticCode?.let { appendLine("- Diagnostic: `$it`") }
            appendLine("- Call: `${tool.callId}`; round: `${tool.roundId}`")
            appendLine("- Observation: `${tool.observationId ?: "unavailable"}`; display: `${tool.displayId}`")
            appendLine("- Started: ${tool.startedAt}; finished: ${tool.finishedAt}; duration: ${tool.exclusiveDurationMillis} ms")
            appendLine()
            appendLine("### Arguments")
            appendLine("````json")
            appendLine(tool.argumentsJson)
            appendLine("````")
            tool.outputText?.takeIf(String::isNotBlank)?.let {
                appendLine("### Result")
                appendLine("````text")
                appendLine(it)
                appendLine("````")
            }
            tool.attachments.forEach { attachment ->
                val path = attachmentArchivePath(
                    attachment.contentRef,
                    attachment.mimeType
                )
                appendLine()
                appendLine("![${attachment.role.name.lowercase()}]($attachmentRoot$path)")
            }
            appendLine()
        }
    }

    private fun attachmentArchivePath(contentRef: String, mimeType: String): String {
        val extension = when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "bin"
        }
        return "attachments/${com.droidance.core.agent.blob.hashFromContentRef(contentRef)}.$extension"
    }
}

@Serializable
private data class ReplayReadModelSnapshot(
    val schemaVersion: Int,
    val threadId: String,
    val turnId: String,
    val state: String,
    val terminalStatus: String?,
    val cancellationOrigin: String?,
    val sourceThroughSequence: Long,
    val conversation: List<ReplayConversationEntry>,
    val analysis: TaskAnalysisMetrics,
    val operationCount: Int,
    val publicProgress: String,
    val resultCardCount: Int
)

@Serializable
private data class ReplayConversationEntry(
    val sequence: Long,
    val kind: String,
    val text: String,
    val diagnosticCode: String?
)

@Serializable
private data class ReplayThreadContextSnapshot(
    val schemaVersion: Int,
    val threadId: String,
    val turnId: String,
    val sourceThroughSequence: Long,
    val conversation: List<ReplayConversationEntry>
)

@Serializable
private data class ProviderHarnessReplaySnapshot(
    val schemaVersion: Int,
    val turnId: String,
    val rounds: List<ProviderHarnessRound>,
    val turnResult: AgentEventEnvelope?
)

@Serializable
private data class ProviderHarnessRound(
    val roundId: String,
    val events: List<AgentEventEnvelope>
)
