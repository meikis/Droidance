// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import kotlinx.serialization.json.*

/** Explicit evidence reads are task-scoped and independent of context checkpoint eviction. */
object RetainedReviewSources {
    data class Loaded(val messages: List<ChatMessage>, val refs: Set<String>, val failures: Map<String, String>)

    data class CaptureHistory(val images: List<DetailAttachmentAddedEventPayload>, val totalImages: Int, val bytes: Long) {
        fun facts(): JsonObject = buildJsonObject {
            put("scope", "retained_captures_and_current_scroll_history")
            put("retained_images", totalImages)
            put("selected_images", images.size)
            put("omitted_images", totalImages - images.size)
            put("selected_model_bytes", bytes)
            put("order", "capture_sequence")
            put("current_screen", false)
        }
    }

    /** Keep the visual working set across calls, bounded by resources rather than capture-group count. */
    fun captureHistory(captures: List<DetailAttachmentAddedEventPayload>, maxImages: Int, maxBytes: Long): CaptureHistory {
        require(maxImages >= 0 && maxBytes >= 0)
        val images = captures.distinctBy { it.contentRef }
        val selected = mutableListOf<DetailAttachmentAddedEventPayload>()
        var bytes = 0L
        for (image in images.asReversed()) {
            val size = image.modelSizeBytes.takeIf { image.modelContentRef != null } ?: image.sizeBytes
            if (selected.size >= maxImages || size > maxBytes - bytes) break
            selected += image
            bytes += size
        }
        return CaptureHistory(selected.asReversed(), images.size, bytes)
    }

    /**
     * Screen batches whose explicit screenshots stay in the working set. An explicit screenshot
     * is the Agent asking to keep that view (a collected page, an item opened and left, a page
     * it will compare later); dropping it forces a separate round to fetch it again. The capture
     * history limits bound how many are resent.
     */
    fun collectionCaptureCalls(events: List<AgentEventEnvelope>): Set<String> = events
        .filter { it.eventKind == AgentEventKind.TOOL_CALL }
        .mapNotNull { event ->
            val call = Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
            if (call.name != com.droidance.core.agent.tools.DeviceToolNames.SCREEN_ACT) return@mapNotNull null
            val operations = runCatching {
                (Json.parseToJsonElement(call.argumentsJson).jsonObject["actions"] as? JsonArray).orEmpty()
                    .map { ((it as? JsonObject)?.get("op") as? JsonPrimitive)?.contentOrNull }
            }.getOrDefault(emptyList())
            event.callId.takeIf { operations.any { it in setOf("screenshot", "visit", "collect") } }
        }.toSet()

    /** Clean observations from the current uninterrupted viewport traversal, never control bindings. */
    fun scrollHistory(events: List<AgentEventEnvelope>): List<DetailAttachmentAddedEventPayload> {
        val calls = mutableSetOf<String>()
        val images = mutableListOf<DetailAttachmentAddedEventPayload>()
        for (event in events.sortedBy { it.sequence }) {
            when (event.eventKind) {
                AgentEventKind.TOOL_CALL -> {
                    val call = Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
                    val actions = runCatching {
                        Json.parseToJsonElement(call.argumentsJson).jsonObject["actions"] as? JsonArray
                    }.getOrNull()
                    val viewportOnly = call.name == com.droidance.core.agent.tools.DeviceToolNames.SCREEN_ACT &&
                        !actions.isNullOrEmpty() && actions.all {
                            ((it as? JsonObject)?.get("op") as? JsonPrimitive)?.contentOrNull in
                                setOf("scroll", "screenshot", "wait", "collect")
                        }
                    if (viewportOnly) {
                        val operations = checkNotNull(actions).map {
                            ((it as? JsonObject)?.get("op") as? JsonPrimitive)?.contentOrNull
                        }
                        // A leading screenshot already retains the starting view. A
                        // screenshot only at the end must not discard the clean start.
                        if ("scroll" in operations && operations.first() != "screenshot") event.callId?.let(calls::add)
                    } else if (call.effect == ToolEffect.STATE_CHANGING || call.effect == ToolEffect.USER_INTERVENTION) {
                        calls.clear()
                        images.clear()
                    }
                }
                AgentEventKind.DETAIL_ATTACHMENT_ADDED -> if (event.callId in calls) {
                    val image = Json.decodeFromString<DetailAttachmentAddedEventPayload>(event.payloadJson)
                    if (image.mimeType.startsWith("image/") && image.sourceContentRef == null &&
                        image.sourceObservationId == null && image.role in setOf(
                            DetailAttachmentRole.INPUT_OBSERVATION, DetailAttachmentRole.CURRENT_OBSERVATION,
                            DetailAttachmentRole.RESULT)) images += image
                }
                else -> Unit
            }
        }
        return images.distinctBy { it.contentRef }
    }

    /** Exact retained image identity, scoped to the current task; never captures a device. */
    fun resolveImage(events: List<AgentEventEnvelope>, scope: TurnEventScope, reference: String?): DetailAttachmentAddedEventPayload? {
        val images = events.asSequence().filter {
            it.threadId == scope.threadId && it.turnId == scope.turnId && it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED
        }.sortedBy { it.sequence }.map { Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson) }
            .filter { it.mimeType.startsWith("image/") && it.role != DetailAttachmentRole.DIAGNOSTIC }.toList()
        if (reference == null) return images.lastOrNull { it.sourceObservationId == null }
        val pairedSources = images.filter { it.modelContentRef == reference }
        val matches = pairedSources.takeIf { it.isNotEmpty() }
            ?: images.filter { it.observationId == reference || it.contentRef == reference }
        return matches.takeIf { it.map { image -> image.contentRef }.distinct().size == 1 }?.lastOrNull()
    }

    fun load(events: List<AgentEventEnvelope>, scope: TurnEventScope, requested: Set<String>, blobs: BlobStore,
        currentImage: String? = null): Loaded {
        val attachments = events.asSequence().filter {
            it.threadId == scope.threadId && it.turnId == scope.turnId && it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED
        }.map { Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson) }
            .filter { (it.contentRef in requested || it.modelContentRef in requested) && it.mimeType.startsWith("image/") && it.role != DetailAttachmentRole.DIAGNOSTIC }
            .flatMap { image -> listOfNotNull(image.contentRef, image.modelContentRef).distinct().map { it to image } }
            .groupBy({ it.first }, { it.second })
        val messages = mutableListOf<ChatMessage>()
        val refs = linkedSetOf<String>()
        val failures = linkedMapOf<String, String>()
        requested.forEach { ref ->
            val sources = attachments[ref]
            if (sources.isNullOrEmpty()) {
                failures[ref] = "SOURCE_NOT_IN_TASK"
            } else {
                try {
                    val encoded = blobs.readBase64(ref, HistoricalObservationImages.MAX_IMAGE_BYTES)
                    messages += ChatMessage(Role.USER,
                        "[historical_observation ids=${sources.mapNotNull { it.observationId }.distinct().joinToString(",")} content_ref=$ref; " +
                            (if (sources.any { it.modelContentRef == ref }) "retained model representation of source_ref=${sources.first { it.modelContentRef == ref }.contentRef}" else "original retained source") +
                            ", not a current target binding" +
                            (if (encoded == currentImage) "; same image supplied as current screen]" else "]") +
                            sources.filter { it.sourceObservationId != null || it.sourceContentRef != null }.takeIf { it.isNotEmpty() }
                                ?.let { transforms -> "\n[retained_image_transforms] " + JsonArray(transforms.map { source -> buildJsonObject {
                                    source.sourceObservationId?.let { put("source_observation_id", it) }
                                    source.sourceContentRef?.let { put("source_content_ref", it) }
                                    source.sourceRegion?.let { put("source_region_px", Json.encodeToJsonElement(it)) }
                                } }.distinct()) }.orEmpty(),
                        imageBase64 = encoded.takeUnless { it == currentImage }, historicalImage = true)
                    refs += ref
                } catch (error: IllegalArgumentException) {
                    failures[ref] = error.javaClass.simpleName
                } catch (error: com.droidance.core.agent.blob.BlobStoreException) {
                    failures[ref] = error.javaClass.simpleName
                }
            }
        }
        return Loaded(messages, refs, failures)
    }
}
