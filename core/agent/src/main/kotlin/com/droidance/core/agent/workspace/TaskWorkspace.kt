// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WorkspacePolicy(
    val maxFiles: Int,
    val maxFileChars: Int,
    val maxRetainedBytes: Long,
    val pageChars: Int
) {
    fun validate() {
        require(maxFiles in 1..128 && maxFileChars in 1..262144)
        require(maxRetainedBytes in 1..33554432 && pageChars in 2..16000)
    }
}

@Serializable
data class WorkspaceFileVersion(
    val path: String,
    val contentRef: String,
    val sizeBytes: Long,
    val chars: Int,
    val requestHash: String
)

@Serializable
data class WorkspaceFocus(val path: String)

@Serializable
data class WorkspaceContextPolicy(
    val maxActiveChars: Int,
    val deviceChangesBeforeReview: Int,
    val browsingOperations: Map<String, List<String>>
) {
    fun validate() {
        require(maxActiveChars in 2..65536 && deviceChangesBeforeReview > 0)
        require(browsingOperations.isNotEmpty() && browsingOperations.values.all { it.isNotEmpty() })
    }
}

@Serializable
data class ActiveWorkspaceContext(
    val path: String,
    val contentRef: String,
    val text: String,
    val totalChars: Int,
    val nextOffset: Int,
    val hasMore: Boolean
) {
    fun render(): String = "<model_authored_active_work_file>\n" + Json.encodeToString(this) +
        "\n</model_authored_active_work_file>"
}

/** Files are committed events and immutable blobs; no process-owned directory is authoritative. */
@Singleton
class TaskWorkspace @Inject constructor(private val events: AgentEventStore, private val blobs: BlobStore) {
    private val writes = Mutex()

    suspend fun objects(scope: TurnEventScope, recordIds: List<String>, throughSequence: Long?,
                        offset: Int, pageChars: Int): kotlinx.serialization.json.JsonObject {
        val history = events.eventsForTurn(scope.turnId).filter { it.threadId == scope.threadId }
        val latest = history.maxOfOrNull { it.sequence } ?: 0L
        val through = throughSequence ?: latest
        require(through in 0..latest) { "INVALID_OBJECT_SNAPSHOT" }
        return com.droidance.core.agent.event.ModelObjectRecords.project(history, scope.threadId, scope.turnId, through)
            .page(recordIds, offset, pageChars)
    }

    suspend fun journal(scope: TurnEventScope, throughSequence: Long?, offset: Int, pageChars: Int): kotlinx.serialization.json.JsonObject {
        val history = events.eventsForTurn(scope.turnId)
        val latest = history.maxOfOrNull { it.sequence } ?: 0L
        val through = throughSequence ?: latest
        require(through in 0..latest) { "INVALID_JOURNAL_SNAPSHOT" }
        return TaskObservationJournal.page(TaskObservationJournal.text(history, scope, through), through, offset, pageChars)
    }

    suspend fun list(scope: TurnEventScope): List<WorkspaceFileVersion> =
        current(events.eventsForTurn(scope.turnId), scope)

    suspend fun activeFile(scope: TurnEventScope): WorkspaceFileVersion? =
        activeFile(events.eventsForTurn(scope.turnId), scope)

    suspend fun readScope(current: TurnEventScope, sourceTurnId: String?): TurnEventScope {
        if (sourceTurnId == null || sourceTurnId == current.turnId) return current
        val history = events.eventsForTurn(sourceTurnId)
        check(history.any { it.threadId == current.threadId && it.turnId == sourceTurnId } &&
            history.none { it.threadId != current.threadId }) { "WORKSPACE_SOURCE_NOT_IN_CONVERSATION" }
        return TurnEventScope(current.threadId, sourceTurnId)
    }

    suspend fun read(scope: TurnEventScope, path: String, policy: WorkspacePolicy): String {
        policy.validate()
        validatePath(path)
        val version = list(scope).firstOrNull { it.path == path } ?: error("WORKSPACE_FILE_NOT_FOUND")
        return readVersion(version)
    }

    fun readVersion(version: WorkspaceFileVersion): String =
        blobs.read(version.contentRef, version.sizeBytes).toString(Charsets.UTF_8).also {
            check(it.length == version.chars) { "WORKSPACE_CONTENT_MISMATCH" }
        }

    suspend fun focus(scope: TurnEventScope, path: String, writeId: String): WorkspaceFocus = writes.withLock {
        validatePath(path)
        require(!path.startsWith(".completion/")) { "WORKSPACE_RESERVED_RUNTIME_PATH" }
        require(writeId.isNotBlank()) { "WORKSPACE_WRITE_ID_REQUIRED" }
        val history = events.eventsForTurn(scope.turnId).filter { it.threadId == scope.threadId }
        val key = "workspace-focus:${scope.turnId}:$writeId"
        history.firstOrNull { it.idempotencyKey == key }?.let {
            return@withLock Json.decodeFromString<WorkspaceFocus>(it.payloadJson).also {
                check(it.path == path) { "WORKSPACE_WRITE_CONFLICT" }
            }
        }
        check(current(history, scope).any { it.path == path }) { "WORKSPACE_FILE_NOT_FOUND" }
        val selected = WorkspaceFocus(path)
        events.append(focusEvent(scope, selected, key))
        selected
    }

    suspend fun activeContext(history: List<AgentEventEnvelope>, scope: TurnEventScope,
                              maxChars: Int): ActiveWorkspaceContext? {
        require(maxChars >= 2)
        val version = activeFile(history, scope) ?: return null
        // Read the exact committed version from this context snapshot, never a mutable parallel cache.
        val content = readVersion(version)
        var end = minOf(content.length, maxChars)
        if (end in 1 until content.length && content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end--
        return ActiveWorkspaceContext(version.path, version.contentRef, content.substring(0, end),
            content.length, end, end < content.length)
    }

    fun stoppedReplyExcerpt(history: List<AgentEventEnvelope>, scope: TurnEventScope,
                            maxChars: Int): String {
        require(maxChars >= 0)
        return buildString {
            // Runtime completion drafts are rejected candidates, not cumulative authored findings.
            for (file in current(history, scope).asReversed().filterNot { it.path.startsWith(".completion/") }.take(3)) {
                val heading = (if (isEmpty()) "" else "\n\n") + file.path + "\n"
                val remaining = maxChars - length - heading.length
                if (remaining <= 0) break
                val text = try { readVersion(file) }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { "[retained content unavailable]" }
                append(heading)
                if (text.length <= remaining) append(text)
                else {
                    val marker = "\n[excerpt truncated: middle omitted; not a complete record]\n"
                    val available = (remaining - marker.length).coerceAtLeast(0)
                    var end = available / 2
                    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                    var start = text.length - (available - end)
                    if (start in 1 until text.length && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) start++
                    append(text.substring(0, end))
                    append(marker.take(remaining - end))
                    if (remaining >= marker.length) append(text.substring(start))
                }
            }
        }
    }

    suspend fun write(scope: TurnEventScope, path: String, text: String, append: Boolean,
                      expectedRef: String?, writeId: String, policy: WorkspacePolicy,
                      activate: Boolean = false, oldText: String? = null, runtimeOwned: Boolean = false): WorkspaceFileVersion = writes.withLock {
        policy.validate()
        validatePath(path)
        require(runtimeOwned || !path.startsWith(".completion/")) { "WORKSPACE_RESERVED_RUNTIME_PATH" }
        require(writeId.isNotBlank()) { "WORKSPACE_WRITE_ID_REQUIRED" }
        require(text.length <= policy.maxFileChars) { "WORKSPACE_FILE_LIMIT" }
        val requestHash = digest(Json.encodeToString(listOf(path, text, append.toString(), expectedRef, activate.toString(), oldText)))
        val history = events.eventsForTurn(scope.turnId).filter { it.threadId == scope.threadId }
        val key = "workspace:${scope.turnId}:$writeId"
        history.firstOrNull { it.idempotencyKey == key }?.let {
            val committed = Json.decodeFromString<WorkspaceFileVersion>(it.payloadJson)
            check(committed.requestHash == requestHash) { "WORKSPACE_WRITE_CONFLICT" }
            return@withLock committed
        }
        val files = current(history, scope)
        val previous = files.firstOrNull { it.path == path }
        check(expectedRef == null || previous?.contentRef == expectedRef) { "WORKSPACE_VERSION_CHANGED" }
        require(previous != null || files.size < policy.maxFiles) { "WORKSPACE_FILE_COUNT_LIMIT" }
        val content = if (oldText != null) {
            require(!append && !expectedRef.isNullOrBlank() && oldText.isNotEmpty()) { "WORKSPACE_EDIT_REQUIRES_VERSION_AND_NONEMPTY_MATCH" }
            val original = readVersion(checkNotNull(previous) { "WORKSPACE_FILE_NOT_FOUND" })
            val index = original.indexOf(oldText)
            require(index >= 0 && original.indexOf(oldText, index + 1) < 0) { "WORKSPACE_EDIT_MATCH_NOT_UNIQUE" }
            original.replaceRange(index, index + oldText.length, text)
        } else (if (append && previous != null) readVersion(previous) else "") + text
        require(content.length <= policy.maxFileChars) { "WORKSPACE_FILE_LIMIT" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val ref = "sha256:" + digest(content)
        val versions = versions(history, scope).distinctBy { it.contentRef }
        val retainedBytes = versions.sumOf { it.sizeBytes } + if (versions.any { it.contentRef == ref }) 0 else bytes.size
        require(retainedBytes <= policy.maxRetainedBytes) { "WORKSPACE_STORAGE_LIMIT" }
        val blob = blobs.put(bytes)
        val version = WorkspaceFileVersion(path, blob.contentRef, blob.sizeBytes, content.length, requestHash)
        val committedEvents = mutableListOf(AgentEventDraft(
            eventKind = AgentEventKind.WORKSPACE_FILE_WRITTEN,
            threadId = scope.threadId, turnId = scope.turnId,
            payloadJson = Json.encodeToString(version), idempotencyKey = key
        ))
        if (activate) committedEvents.add(focusEvent(scope, WorkspaceFocus(path), "workspace-focus:${scope.turnId}:$writeId"))
        events.appendAtomically(committedEvents)
        version
    }

    private fun focusEvent(scope: TurnEventScope, focus: WorkspaceFocus, key: String) = AgentEventDraft(
        eventKind = AgentEventKind.WORKSPACE_FOCUS_CHANGED,
        threadId = scope.threadId, turnId = scope.turnId,
        payloadJson = Json.encodeToString(focus), idempotencyKey = key
    )

    companion object {
        fun activeFile(history: List<AgentEventEnvelope>, scope: TurnEventScope): WorkspaceFileVersion? {
            val selected = history.filter { it.threadId == scope.threadId && it.turnId == scope.turnId &&
                it.eventKind == AgentEventKind.WORKSPACE_FOCUS_CHANGED }.maxByOrNull { it.sequence }
                ?.let { Json.decodeFromString<WorkspaceFocus>(it.payloadJson) } ?: return null
            return checkNotNull(current(history, scope).firstOrNull { it.path == selected.path }) {
                "WORKSPACE_ACTIVE_FILE_MISSING"
            }
        }

        fun versions(history: List<AgentEventEnvelope>, scope: TurnEventScope): List<WorkspaceFileVersion> =
            history.asSequence().filter { it.threadId == scope.threadId && it.turnId == scope.turnId &&
                it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN }.sortedBy { it.sequence }
                .map { Json.decodeFromString<WorkspaceFileVersion>(it.payloadJson) }.toList()

        fun current(history: List<AgentEventEnvelope>, scope: TurnEventScope): List<WorkspaceFileVersion> =
            versions(history, scope).asReversed().distinctBy { it.path }.asReversed()

        fun overview(history: List<AgentEventEnvelope>, scope: TurnEventScope, maxChars: Int): String {
            // An initialized empty working file has nothing for the Agent to read.
            val files = current(history, scope).filter { it.chars > 0 }
            if (files.isEmpty()) return ""
            return buildString {
                append("Task workspace (model-authored files, not independent evidence):\n")
                files.asReversed().forEach { file ->
                    val line = Json.encodeToString(file.copy(requestHash = "")) + "\n"
                    if (length + line.length <= maxChars) append(line)
                }
                append("Use workspace list/read for complete retained files.\n")
            }.take(maxChars)
        }

        fun validatePath(path: String) {
            require(path.length in 1..512 && '\\' !in path && '\u0000' !in path &&
                path.split('/').size <= 16 && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
                "INVALID_WORKSPACE_PATH"
            }
        }

        private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
