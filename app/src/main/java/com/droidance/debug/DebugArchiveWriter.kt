// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.AgentEventEnvelope
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Writes one frozen EventStore snapshot; supplementary operational files have their own boundaries. */
internal class DebugArchiveWriter(private val blobs: BlobStore) {
    data class Supplement(val path: String, val source: File, val sizeAtSnapshot: Long = source.length())
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val lineJson = Json { encodeDefaults = true }

    suspend fun write(
        zip: ZipOutputStream,
        events: List<AgentEventEnvelope>,
        metadata: JsonObject,
        supplements: List<Supplement> = emptyList()
    ) {
        val snapshot = events.sortedBy { it.sequence }
        val tasks = mutableListOf<JsonObject>()
        val files = mutableListOf<JsonObject>()
        val issues = mutableListOf<JsonObject>()
        val recordingGaps = mutableListOf<JsonObject>()
        val blobPaths = linkedMapOf<String, String>()
        val blobEvents = linkedMapOf<String, MutableSet<Long>>()
        val refPattern = Regex("sha256:[0-9a-f]{64}")
        suspend fun entry(path: String, block: suspend () -> Unit) {
            currentCoroutineContext().ensureActive()
            zip.putNextEntry(ZipEntry(path))
            files += buildJsonObject { put("path", path) }
            try { block() } finally { zip.closeEntry() }
        }
        suspend fun text(path: String, value: String) = entry(path) { zip.write(value.toByteArray()) }
        fun problem(path: String, error: Exception) {
            if (error is CancellationException) throw error
            issues += buildJsonObject {
                put("path", path); put("reason", error.javaClass.simpleName)
                put("detail", error.message.orEmpty())
            }
        }
        // Raw facts remain available even if a historical read-model projection is malformed.
        entry("eventstore/agent-events.jsonl") {
            snapshot.forEach {
                currentCoroutineContext().ensureActive()
                zip.write((lineJson.encodeToString(it) + "\n").toByteArray())
                fun discover(value: JsonElement) {
                    if (value is JsonObject) {
                        val ref = (value["contentRef"] ?: value["content_ref"])?.jsonPrimitive?.contentOrNull
                        val mime = (value["mimeType"] ?: value["mime_type"])?.jsonPrimitive?.contentOrNull
                        if (ref != null && refPattern.matches(ref) && mime != null) {
                            val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "blob" }
                            val path = "attachments/${ref.removePrefix("sha256:")}.$extension"
                            if (mime.startsWith("image/")) blobPaths[ref] = path else blobPaths.putIfAbsent(ref, path)
                        }
                        value.values.forEach(::discover)
                    } else if (value is JsonArray) value.forEach(::discover)
                }
                runCatching {
                    val payload = lineJson.parseToJsonElement(it.payloadJson)
                    discover(payload)
                    val result = (payload as? JsonObject)?.get("result") as? JsonObject
                    val metrics = result?.get("metrics") as? JsonObject
                    val missing = metrics?.get("screenshot_attachments_missing")?.jsonPrimitive?.longOrNull ?: 0
                    if (missing > 0) recordingGaps += buildJsonObject {
                        put("sequence", it.sequence); put("turn_id", it.turnId); put("call_id", it.callId)
                        put("reason", "screenshot_not_captured_or_persisted"); put("missing_count", missing)
                    }
                }
                refPattern.findAll(it.payloadJson).forEach { ref ->
                    blobEvents.getOrPut(ref.value) { linkedSetOf() }.add(it.sequence)
                }
            }
        }
        snapshot.groupBy { it.threadId }.values.forEach { thread ->
            thread.filter { it.turnId != null }.groupBy { requireNotNull(it.turnId) }.forEach { (turnId, turn) ->
                currentCoroutineContext().ensureActive()
                val root = "tasks/turn-${turn.first().sequence}"
                var projectionError: String? = null
                var state = "unavailable"
                var userInput = ""
                var terminal: String? = null
                val replay = try {
                    AgentEventReplayProjection.project(
                        thread.filter { it.sequence <= turn.last().sequence }, turnId, "../../../")
                } catch (error: Exception) {
                    projectionError = error.javaClass.simpleName
                    problem(root, error)
                    null
                }
                if (replay != null) {
                    val summary = json.parseToJsonElement(replay.readModelJson).jsonObject
                    state = summary["state"]?.jsonPrimitive?.contentOrNull ?: state
                    terminal = summary["terminalStatus"]?.jsonPrimitive?.contentOrNull
                    userInput = replay.userInput
                    text("$root/analysis/task-summary.json", replay.readModelJson)
                    text("$root/analysis/trace.jsonl", replay.analysisTraceJsonl)
                    text("$root/analysis/thread-context.json", replay.threadContextJson)
                    text("$root/eventstore/agent-events.jsonl", replay.eventsJsonl)
                    text("$root/eventstore/provider-harness.json", replay.providerHarnessJson)
                    text("$root/debug/replay.md", replay.replayMarkdown)
                    replay.attachments.forEach { attachment ->
                        blobPaths.putIfAbsent(attachment.contentRef, attachment.archivePath)
                    }
                }
                tasks += buildJsonObject {
                    put("thread_id", thread.first().threadId); put("turn_id", turnId)
                    put("path", root); put("user_input", userInput); put("state", state)
                    put("model_diagnostics_path", "diagnostics/model/${ProviderExchangeRecorder.scopeDirectory(turnId)}")
                    put("terminal_status", terminal); put("event_count", turn.size)
                    put("first_sequence", turn.first().sequence); put("source_through_sequence", turn.last().sequence)
                    put("projection_error", projectionError)
                    put("raw_events_path", "eventstore/agent-events.jsonl")
                }
            }
        }
        // Includes screenshots, user images, checkpoints, workspace versions and program artifacts,
        // including references not exposed by the tool-only read model.
        blobEvents.keys.forEach { blobPaths.putIfAbsent(it, "attachments/${it.removePrefix("sha256:")}.blob") }
        val attachmentIndex = mutableListOf<JsonObject>()
        blobPaths.forEach { (ref, path) ->
            var count: Long? = null
            var status = "exported"
            try {
                val context = currentCoroutineContext()
                entry(path) {
                    count = blobs.copyTo(ref, object : java.io.OutputStream() {
                        override fun write(value: Int) { zip.write(value) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            context.ensureActive()
                            zip.write(bytes, offset, length)
                        }
                    })
                }
            } catch (error: com.droidance.core.agent.blob.BlobStoreException) {
                status = "unavailable"
                problem(path, error)
            }
            attachmentIndex += buildJsonObject {
                put("content_ref", ref); put("path", path); put("status", status)
                put("size_bytes", count)
                put("event_sequences", JsonArray(blobEvents[ref].orEmpty().map(::JsonPrimitive)))
            }
        }
        val supplementalIndex = mutableListOf<JsonObject>()
        supplements.forEach { extra ->
            var status = "exported"
            try {
                entry(extra.path) {
                    extra.source.inputStream().use { input ->
                        var remaining = extra.sizeAtSnapshot
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (count < 0) throw IOException("Supplement was truncated during export")
                            zip.write(buffer, 0, count); remaining -= count
                        }
                    }
                }
            } catch (error: IOException) {
                status = "unavailable"
                problem(extra.path, error)
            }
            supplementalIndex += buildJsonObject {
                put("path", extra.path); put("status", status); put("size_at_snapshot", extra.sizeAtSnapshot)
                put("size_after_copy", extra.source.length())
            }
        }
        text("attachments/index.json", json.encodeToString(JsonArray(attachmentIndex)))
        text("index.json", json.encodeToString(buildJsonObject {
            put("schema_version", 2); put("scope", "all_retained_history")
            put("event_count", snapshot.size); put("thread_count", snapshot.map { it.threadId }.distinct().size)
            put("task_count", tasks.size); put("tasks", JsonArray(tasks))
        }))
        text("README.md", buildString {
            appendLine("# Droidance diagnostic archive")
            appendLine()
            appendLine("Scope: all retained, non-deleted conversations and tasks, including running and interrupted tasks.")
            appendLine("Start with index.json, then tasks/<task>/analysis/task-summary.json and debug/replay.md.")
            appendLine("eventstore/agent-events.jsonl contains the complete frozen raw event snapshot, including thread and learning events.")
            appendLine("Each task includes its full trace, conversation context and Provider/Harness round projection.")
            appendLine("attachments/index.json maps retained content and screenshots to their source event sequences. Shared images are stored once.")
            appendLine("Raw events provide call IDs, before/after image roles, observation IDs and workspace paths.")
            appendLine("diagnostics/ and logs/ contain retained operational evidence; current/ is export-time Markdown, not historical model input.")
            appendLine("Provider request/response recording applies to new requests after this build. Older missing data cannot be reconstructed.")
            appendLine("Consult manifest.json issues and attachment status before relying on completeness. An unavailable entry may contain partial bytes and must not be used as evidence.")
            appendLine("Supplementary files are copied through their individual captured byte lengths; they are not an atomic EventStore snapshot.")
            appendLine("Task status and tool receipts do not establish semantic success. No automatic semantic assessment is added.")
        })
        // Write last so the manifest describes actual entries and every known export failure.
        text("manifest.json", json.encodeToString(buildJsonObject {
            metadata.forEach { (key, value) -> put(key, value) }
            put("schema_version", 2); put("scope", "all_retained_history")
            put("source_through_sequence", snapshot.lastOrNull()?.sequence)
            put("event_count", snapshot.size); put("task_count", tasks.size)
            put("complete", issues.isEmpty()); put("issues", JsonArray(issues))
            put("completeness_scope", "retained_data_at_export")
            put("recording_gaps", JsonArray(recordingGaps))
            put("files", JsonArray(files + buildJsonObject { put("path", "manifest.json") }))
            put("supplementary_files", JsonArray(supplementalIndex))
        }))
    }
}
