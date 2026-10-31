// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.blob.BlobStoreException
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionReadService @Inject constructor(private val events: AgentEventStore, private val blobs: BlobStore) {
    suspend fun read(threadId: String, turnId: String, arguments: String, imagesSupported: Boolean): ToolExecutionResult {
        val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val mode = args["mode"]?.let { value ->
            runCatching { value.jsonPrimitive.content }
                .getOrElse { return ToolProtocolResult.invalidArgument("mode", "expected_string") }
        } ?: "list"
        val after = args["after"]?.let { value ->
            runCatching { value.jsonPrimitive.longOrNull }
                .getOrElse { return ToolProtocolResult.invalidArgument("after", "expected_integer") }
                ?: return ToolProtocolResult.invalidArgument("after", "expected_integer")
        } ?: 0L
        val limit = args["limit"]?.let { value ->
            runCatching { value.jsonPrimitive.intOrNull }
                .getOrElse { return ToolProtocolResult.invalidArgument("limit", "expected_integer") }
                ?: return ToolProtocolResult.invalidArgument("limit", "expected_integer")
        } ?: 8
        if (after < 0 || limit !in 1..32) return ToolProtocolResult.invalidArguments()
        val sourceTurnId = args["source_turn_id"]?.jsonPrimitive?.contentOrNull ?: turnId
        val history = events.eventsForTurn(sourceTurnId)
        if (sourceTurnId != turnId && (history.none { it.threadId == threadId } || history.any { it.threadId != threadId })) {
            return ToolProtocolResult.notExecuted("HISTORY_SOURCE_NOT_IN_CONVERSATION", false)
        }
        if (mode == "programs") return ToolProtocolResult.success(
            ProgramEvidence.page(history, threadId, sourceTurnId, after, limit).toString())
        if (mode == "artifact") {
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull ?: return ToolProtocolResult.missingArgument("ref")
            val workspaceFile = com.droidance.core.agent.workspace.TaskWorkspace.versions(
                history, TurnEventScope(threadId, sourceTurnId)).firstOrNull { it.contentRef == ref }
            val steps = SessionEvidenceProjector.steps(history, threadId, sourceTurnId)
            val attachment = steps.flatMap { it.attachments }.firstOrNull {
                it.contentRef == ref || it.modelContentRef == ref || it.attachmentId == ref || it.observationId == ref
            }
            val toolData = steps.firstOrNull { it.result.dataRef == ref }
                ?.takeIf { sourceTurnId == turnId }
            val manifest = ProgramEvidence.manifest(history, threadId, sourceTurnId, ref)
                ?: workspaceFile?.let { buildJsonObject {
                    put("mime_type", "text/plain"); put("content_ref", it.contentRef)
                } }
                ?: attachment?.let { buildJsonObject {
                    put("mime_type", it.mimeType)
                    put("content_ref", if (ref == it.modelContentRef) ref else it.contentRef)
                    put("observation_id", it.observationId)
                } }
                ?: toolData?.let { buildJsonObject {
                    put("mime_type", "application/json"); put("content_ref", ref)
                } }
            if (manifest == null) {
                val step = SessionEvidenceProjector.find(steps, ref)
                    ?: return ToolProtocolResult.notExecuted("HISTORY_REFERENCE_NOT_FOUND", false)
                val text = SessionEvidenceProjector.describe(step, true, TurnEventScope(threadId, turnId)).toString()
                val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
                if (offset !in 0..text.length) return ToolProtocolResult.invalidArgument("offset", "invalid_cursor")
                val end = minOf(text.length, offset + 12000)
                return ToolProtocolResult.success(buildJsonObject {
                    put("source_ref", ref); put("text", text.substring(offset, end)); put("next_offset", end)
                    put("has_more", end < text.length); put("total_chars", text.length)
                }.toString())
            }
            val contentRef = manifest.getValue("content_ref").jsonPrimitive.content
            val bytes = try { withContext(Dispatchers.IO) { blobs.read(contentRef, 8L * 1024 * 1024) } }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (error: Exception) { return ToolProtocolResult.notExecuted("HISTORY_ARTIFACT_UNAVAILABLE", false) }
            val modelRepresentation = attachment?.takeIf { contentRef == it.modelContentRef }
            val mime = if (modelRepresentation == null) manifest.getValue("mime_type").jsonPrimitive.content else when {
                bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
                bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
                bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                    bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
                else -> return ToolProtocolResult.notExecuted("HISTORY_ARTIFACT_MIME_UNAVAILABLE", false)
            }
            if (mime.startsWith("image/")) {
                if (!imagesSupported) return ToolProtocolResult.notExecuted("HISTORY_IMAGE_UNSUPPORTED", false)
                val receipt = buildJsonObject {
                    put("source_kind", "retained_image_bundle")
                    put("requested_ref", ref); put("displayed_image_ref", contentRef)
                    put("displayed_mime_type", mime); put("displayed_size_bytes", bytes.size)
                    put("native_source_ref", attachment?.contentRef ?: contentRef)
                    put("source_thread_id", threadId); put("source_turn_id", sourceTurnId)
                    put("source_observation_id", attachment?.observationId)
                    put("current_screen", false)
                }
                return ToolProtocolResult.success(receipt.toString()).copy(
                    historicalAttachment = if (attachment != null) attachment.copy(
                        attachmentId = "retained_image_" + contentRef.removePrefix("sha256:"),
                        role = DetailAttachmentRole.INPUT_OBSERVATION,
                        observationId = "retained_image_" + contentRef.removePrefix("sha256:"),
                        modelContentRef = modelRepresentation?.modelContentRef,
                        modelSizeBytes = modelRepresentation?.let { bytes.size.toLong() }
                    ) else DetailAttachmentAddedEventPayload(
                        AGENT_READ_EVENT_CONTRACT_VERSION, "retained_image_" + contentRef.removePrefix("sha256:"),
                        DetailAttachmentRole.INPUT_OBSERVATION, contentRef, contentRef.removePrefix("sha256:"), bytes.size.toLong(), mime,
                        observationId = manifest["observation_id"]?.jsonPrimitive?.contentOrNull))
            }
            val text = bytes.toString(Charsets.UTF_8)
            val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
            if (offset !in 0..text.length) return ToolProtocolResult.invalidArgument("offset", "invalid_cursor")
            val end = minOf(text.length, offset + 12000)
            return ToolProtocolResult.success(buildJsonObject {
                put("content_ref", ref); put("text", text.substring(offset, end)); put("next_offset", end)
                put("has_more", end < text.length); put("total_chars", text.length)
            }.toString())
        }
        if (mode == "incidents") return ToolProtocolResult.success(
            ExecutionIncidentProjector.page(history, threadId, sourceTurnId, after, limit).toString())
        if (mode !in setOf("list", "step", "image")) {
            return ToolProtocolResult.invalidArgument("mode", "unsupported_mode")
        }
        val steps = SessionEvidenceProjector.steps(history, threadId, sourceTurnId)
        if (mode == "list") {
            val eligible = steps.filter { it.event.sequence > after }
            val page = eligible.take(limit)
            return ToolProtocolResult.success(buildJsonObject {
                put("steps", JsonArray(page.map { SessionEvidenceProjector.describe(it, false) }))
                put("next_after", if (eligible.size > page.size) JsonPrimitive(page.last().event.sequence) else JsonNull)
            }.toString())
        }
        val ref = args["ref"]?.let { value ->
            runCatching { value.jsonPrimitive.content }
                .getOrElse { return ToolProtocolResult.invalidArgument("ref", "expected_string") }
        } ?: return ToolProtocolResult.missingArgument("ref")
        val step = SessionEvidenceProjector.find(steps, ref)
            ?: return ToolProtocolResult.notExecuted("HISTORY_REFERENCE_NOT_FOUND", false)
        if (mode == "step") {
            val text = SessionEvidenceProjector.describe(step, true, TurnEventScope(threadId, turnId)).toString()
            if (text.length > 32_000) return ToolProtocolResult.notExecuted("HISTORY_STEP_EXCEEDS_BUDGET", false)
            return ToolProtocolResult.success(text)
        }
        if (!imagesSupported) return ToolProtocolResult.notExecuted("HISTORY_IMAGE_UNSUPPORTED", false)
        val role = args["role"]?.let { value ->
            runCatching { value.jsonPrimitive.content }
                .getOrElse { return ToolProtocolResult.invalidArgument("role", "expected_string") }
        } ?: "pre"
        val wanted = when (role) {
            "pre" -> DetailAttachmentRole.INPUT_OBSERVATION
            "post" -> DetailAttachmentRole.RESULT
            else -> return ToolProtocolResult.invalidArgument("role", "unsupported_role")
        }
        val exactAttachment = step.attachments.firstOrNull {
            it.attachmentId == ref || it.observationId == ref
        }
        val attachment = when {
            exactAttachment != null -> exactAttachment.takeIf { it.mimeType.startsWith("image/") }
            ref == step.event.eventId || ref == step.event.callId ->
                step.attachments.firstOrNull { it.role == wanted && it.mimeType.startsWith("image/") }
            else -> null
        }
            ?: return ToolProtocolResult.notExecuted("HISTORY_IMAGE_NOT_FOUND", false)
        try {
            withContext(Dispatchers.IO) { blobs.read(attachment.contentRef, 8L * 1024 * 1024) }
        } catch (failure: BlobStoreException) {
            return ToolProtocolResult.notExecuted("HISTORY_IMAGE_UNAVAILABLE", false)
        } catch (failure: IllegalArgumentException) {
            return ToolProtocolResult.notExecuted("HISTORY_IMAGE_UNAVAILABLE", false)
        }
        return ToolProtocolResult.success(buildJsonObject {
            put("historical", true)
            put("current_screen", false)
            put("source", SessionEvidenceProjector.describe(step, false))
            put("attachment_ref", attachment.attachmentId)
            put("content_ref", attachment.contentRef)
            put("sha256", attachment.contentHash)
        }.toString()).copy(historicalAttachment = attachment)
    }
}
