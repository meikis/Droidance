// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.PublicAssistantText
import com.droidance.core.agent.event.NoticeActivityReadModel
import com.droidance.core.agent.event.PublicProgressReadModel
import com.droidance.core.agent.event.ToolActivityReadModel
import com.droidance.core.agent.event.ToolDetailReadModel
import com.droidance.core.agent.event.TurnActivityReadModel

/** A public update owns receipts from its actual model rounds, never from text similarity. */
data class ExecutionProgressNode(
    val id: String,
    val sequence: Long,
    val text: String,
    val calls: List<ToolActivityReadModel>,
    val roundIds: List<String>
)

object EventStoreExecutionTimelinePresenter {
    fun hasHistory(activity: TurnActivityReadModel, progressHistory: List<PublicProgressReadModel>): Boolean =
        progressHistory.any { timelineActionTitle(it.text) != null } || activity.entries.any {
            it.turnId == activity.turnId && it.visible && (it is ToolActivityReadModel ||
                it is NoticeActivityReadModel && timelineActionTitle(it.text) != null)
        }

    fun present(
        activity: TurnActivityReadModel,
        progressHistory: List<PublicProgressReadModel> = activity.publicProgressHistory
    ): List<ExecutionProgressNode> {
        val callsByRound = activity.entries.filterIsInstance<ToolActivityReadModel>()
            .filter { it.visible && it.turnId == activity.turnId }
            .distinctBy { it.callId }.groupBy { it.roundId }
        val progress = progressHistory.mapNotNull { update ->
            val text = timelineActionTitle(update.text) ?: return@mapNotNull null
            ExecutionProgressNode(update.eventId, update.sequence, text,
                update.roundIds.distinct().flatMap { callsByRound[it].orEmpty() }.sortedBy { it.sequence },
                update.roundIds.distinct())
        }
        val notices = activity.entries.filterIsInstance<NoticeActivityReadModel>()
            .filter { it.visible && it.turnId == activity.turnId }
            .mapNotNull { notice -> timelineActionTitle(notice.text)?.let {
                ExecutionProgressNode(notice.itemId, notice.sequence, it, emptyList(), emptyList())
            } }
        val narratedCalls = progress.flatMap { it.calls }.mapTo(mutableSetOf()) { it.callId }
        // Receipts exist independently of optional model narration, including the live round.
        val receipts = callsByRound.mapNotNull { (roundId, round) ->
            val unnarrated = round.filterNot { it.callId in narratedCalls }.sortedBy { it.sequence }
            unnarrated.firstOrNull()?.let { first ->
                ExecutionProgressNode("call:${first.callId}", first.sequence, "", unnarrated, listOf(roundId))
            }
        }
        return (progress + notices + receipts).sortedBy { it.sequence }
    }
}

internal data class ExecutionCallRecord(
    val activity: ToolActivityReadModel,
    val detail: ToolDetailReadModel?,
    val commands: List<ExecutionCommandRecord> = emptyList(),
    val invocation: ExecutionInvocationRecord? = null
)

internal data class ExecutionInvocationRecord(val call: String, val output: String?)

/** Keep the retained payload intact unless an existing secret rule requires redaction. */
private fun redactExecutionPayload(payload: String): String {
    val redact = com.droidance.core.knowledge.memory.MemorySecretRedactor::redact
    fun redactJson(text: String): String? {
        val data = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull() ?: return null
        val redacted = com.droidance.core.knowledge.memory.MemorySecretRedactor.redactJson(text)
        return if (redacted == data.toString()) text else redacted
    }
    redactJson(payload)?.let { return it }
    val delimiter = payload.indexOf('\n')
    if (delimiter >= 0) {
        redactJson(payload.substring(delimiter + 1))?.let {
            return redact(payload.substring(0, delimiter + 1)) + it
        }
    }
    return redact(payload)
}

internal data class ExecutionCommandRecord(
    val id: String, val command: String, val stdout: String, val stderr: String,
    val status: com.droidance.core.agent.event.ToolExecutionStatus?, val exitCode: Int?,
    val durationMs: Long?, val stdoutTruncated: Boolean, val stderrTruncated: Boolean
)

internal fun executionCommandRecord(id: String, command: String, output: String?,
    status: com.droidance.core.agent.event.ToolExecutionStatus?, durationMs: Long?): ExecutionCommandRecord {
    val data = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(output.orEmpty())
        as? kotlinx.serialization.json.JsonObject }.getOrNull()
    fun value(key: String) = data?.get(key) as? kotlinx.serialization.json.JsonPrimitive
    val redact = com.droidance.core.knowledge.memory.MemorySecretRedactor::redact
    val stdout = value("stdout")?.content ?: if (data == null || "code" in data || "error" in data) {
        redactExecutionPayload(output.orEmpty())
    } else ""
    return ExecutionCommandRecord(id, redact(command), redact(stdout),
        redact(value("stderr")?.content.orEmpty()), status,
        value("exit_code")?.content?.toIntOrNull()?.takeIf { it >= 0 }, durationMs ?: value("duration_ms")?.content?.toLongOrNull(),
        value("stdout_truncated")?.content == "true", value("stderr_truncated")?.content == "true")
}

/** Direct call lookup avoids rescanning the thread and excludes receipts from other turns/rounds. */
internal fun executionRecords(
    node: ExecutionProgressNode,
    toolDetails: Map<String, ToolDetailReadModel>,
    programShellCalls: List<com.droidance.core.agent.event.ProgramShellCallReadModel> = emptyList()
): List<ExecutionCallRecord> {
    return node.calls.map { call ->
        val detail = toolDetails[call.callId]?.takeIf {
            it.callId == call.callId && it.turnId == call.turnId && it.roundId == call.roundId
        }?.let { detail ->
            detail.copy(attachments = orderedExecutionImages(detail.attachments))
        }
        val commands = when (detail?.technicalName) {
            "shell" -> {
                val data = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(detail.outputText.orEmpty())
                    as? kotlinx.serialization.json.JsonObject }.getOrNull()
                val command = detail.commandSource ?: (data?.get("command") as? kotlinx.serialization.json.JsonPrimitive)?.content
                listOfNotNull(command?.let { executionCommandRecord(call.callId, it, detail.outputText,
                    detail.executionStatus, detail.exclusiveDurationMillis) })
            }
            "program" -> {
                val childCalls = programShellCalls.filter { it.parentCallId == call.callId }.sortedBy { it.sequence }.map { child ->
                    executionCommandRecord(child.childId, child.command, child.result?.outputText,
                        child.result?.executionStatus, child.finishedAt?.minus(child.startedAt))
                }
                val data = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(detail.outputText.orEmpty())
                    as? kotlinx.serialization.json.JsonObject }.getOrNull()
                val stdout = ((data?.get("stdout") as? kotlinx.serialization.json.JsonObject)?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (data?.get("stdout") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val stderr = (data?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val redact = com.droidance.core.knowledge.memory.MemorySecretRedactor::redact
                val programRecord = detail.commandSource?.let { src ->
                    ExecutionCommandRecord(
                        id = call.callId,
                        command = redact(src),
                        stdout = redact(stdout),
                        stderr = redact(stderr),
                        status = detail.executionStatus,
                        exitCode = null,
                        durationMs = detail.exclusiveDurationMillis,
                        stdoutTruncated = ((data?.get("stdout") as? kotlinx.serialization.json.JsonObject)
                            ?.get("has_more") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                        stderrTruncated = false
                    )
                }
                listOfNotNull(programRecord) + childCalls
            }
            "workspace" -> {
                val cmd = detail.commandSource
                if (cmd != null) {
                    val redact = com.droidance.core.knowledge.memory.MemorySecretRedactor::redact
                    listOf(
                        ExecutionCommandRecord(
                            id = call.callId,
                            command = redact(cmd),
                            stdout = redact(detail.outputText.orEmpty()),
                            stderr = "",
                            status = detail.executionStatus,
                            exitCode = null,
                            durationMs = detail.exclusiveDurationMillis,
                            stdoutTruncated = false,
                            stderrTruncated = false
                        )
                    )
                } else emptyList()
            }
            else -> programShellCalls.filter { it.parentCallId == call.callId }.sortedBy { it.sequence }.map { child ->
                executionCommandRecord(child.childId, child.command, child.result?.outputText,
                    child.result?.executionStatus, child.finishedAt?.minus(child.startedAt))
            }
        }
        val invocation = detail?.takeIf {
            when (it.technicalName) {
                "android_read" -> !com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.isScreenObservation(it.technicalName, it.argumentsJson)
                "android_act" -> com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.operation(it.technicalName, it.argumentsJson) != "open"
                else -> false
            }
        }?.let {
            ExecutionInvocationRecord(
                call = "${it.technicalName}(${redactExecutionPayload(it.argumentsJson)})",
                output = it.outputText?.let(::redactExecutionPayload)
            )
        }
        ExecutionCallRecord(call, detail, commands, invocation)
    }
}

/** Preserve source language and identifiers while removing runtime-only content. */
internal fun timelineActionTitle(title: String?): String? =
    title?.let(PublicAssistantText::sanitize)?.takeIf(String::isNotBlank)
