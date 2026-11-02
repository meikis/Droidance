// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ImageDetailOption
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.toJsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class OpenAIResponsesTurnState {
    var computerFrameSize: Pair<Int, Int>? = null

    var previousResponseId: String? = null
        private set
    var pendingComputerCallId: String? = null
        private set

    private val announcedFunctionCalls = mutableSetOf<String>()
    private val functionCallsWithArgumentDeltas = mutableSetOf<Int>()
    private var submittedComputerCallId: String? = null

    /**
     * Digests of images the provider already holds in the previous_response_id chain. A
     * continuation sends only new input, so a historical image re-attached by the local
     * projection would otherwise be appended to the stored conversation again every round.
     */
    private val retainedImageDigests = mutableSetOf<String>()
    private val submittedImageDigests = mutableSetOf<String>()

    /** Historical images left out of the latest request because the chain already holds them. */
    var skippedRetainedImages: Int = 0
        private set

    internal fun beginRequest() {
        submittedImageDigests.clear()
        skippedRetainedImages = 0
    }

    internal fun isRetainedImage(digest: String): Boolean =
        previousResponseId != null && digest in retainedImageDigests

    internal fun markImageSubmitted(digest: String) {
        submittedImageDigests += digest
    }

    internal fun markRetainedImageSkipped() {
        skippedRetainedImages += 1
    }

    fun commitResponse(responseId: String?, retained: Boolean = true) {
        previousResponseId = if (retained) {
            responseId?.takeIf(String::isNotBlank) ?: previousResponseId
        } else null
        if (previousResponseId == null) retainedImageDigests.clear()
        else retainedImageDigests += submittedImageDigests
        submittedImageDigests.clear()
        if (pendingComputerCallId == submittedComputerCallId) {
            pendingComputerCallId = null
        }
        submittedComputerCallId = null
        resetStreamingParser()
    }

    fun rememberComputerCall(callId: String) {
        pendingComputerCallId = callId.takeIf(String::isNotBlank)
    }

    fun markComputerCallSubmitted(callId: String) {
        submittedComputerCallId = callId
    }

    fun announceFunctionCall(callId: String): Boolean = announcedFunctionCalls.add(callId)

    fun markFunctionArgumentsDelta(outputIndex: Int) {
        functionCallsWithArgumentDeltas += outputIndex
    }

    fun hasFunctionArgumentsDelta(outputIndex: Int): Boolean =
        outputIndex in functionCallsWithArgumentDeltas

    fun resetStreamingParser() {
        announcedFunctionCalls.clear()
        functionCallsWithArgumentDeltas.clear()
    }

    fun abortResponse(previousPendingComputerCallId: String?) {
        pendingComputerCallId = previousPendingComputerCallId
        submittedComputerCallId = null
        resetStreamingParser()
    }
}

class OpenAIResponsesIncompleteException(
    reason: String?,
    val outputTokens: Int? = null,
    val maxOutputTokens: Int? = null
) : IllegalStateException(
    "OpenAI Responses finished incomplete: " + when (reason) {
        "max_output_tokens", "max_tokens" -> "output_limit"
        "content_filter" -> "content_filter"
        else -> "unspecified"
    } + "; output_tokens=${outputTokens ?: "unknown"}; max_output_tokens=${maxOutputTokens ?: "unknown"}"
) {
    val outputLimitReached: Boolean = reason != "content_filter" &&
        (reason in setOf("max_output_tokens", "max_tokens") ||
            (outputTokens != null && maxOutputTokens != null && maxOutputTokens > 0 && outputTokens >= maxOutputTokens))

    fun expandedOutputBudget(current: Int, ceiling: Int): Int =
        if (outputLimitReached && current > 0 && ceiling > current) {
            (current.toLong() * 2).coerceAtMost(ceiling.toLong()).toInt()
        } else current
}

class OpenAIResponsesUnterminatedException : IllegalStateException(
    "OpenAI Responses stream ended without response.completed"
)

object OpenAIResponsesProtocol {
    /**
     * Runtime may capture a fresh screen after a state-changing call without the model having
     * declared another function call. Responses only accepts function_call_output for call IDs
     * emitted by the previous response, so these local observation receipts must stay out of the
     * continuation input; the current frame/text is appended separately by the operator.
     */
    private const val SYNTHETIC_TOOL_CALL_PREFIX = "auto_observe_"

    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int,
        val cacheFieldsReported: Boolean,
        val reasoningTokens: Int? = null,
        val cacheWriteTokens: Int? = null,
        val inputTokensReported: Boolean = false,
        val outputTokensReported: Boolean = false
    )

    fun buildRequest(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        supportsVision: Boolean,
        maxOutputTokens: Int?,
        state: OpenAIResponsesTurnState,
        includeComputerTool: Boolean = false,
        computerFrameSize: Pair<Int, Int>? = null,
        imageDetail: ImageDetailOption = ImageDetailOption.HIGH,
        reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
        forceDefaultReasoning: Boolean = false,
        cacheStableHistory: Boolean = false
    ): JsonObject = buildJsonObject {
        state.computerFrameSize = computerFrameSize
        state.beginRequest()
        put("model", model)
        put("stream", true)
        systemPrompt?.takeIf(String::isNotBlank)?.let { put("instructions", it) }
        maxOutputTokens?.takeIf { it > 0 }?.let { put("max_output_tokens", it) }
        state.previousResponseId?.let { put("previous_response_id", it) }
        put("input", buildInput(messages, supportsVision, state, imageDetail, cacheStableHistory))

        val exposedFunctionTools = if (includeComputerTool) {
            tools.filterNot { it.name == "screen_act" }
        } else {
            tools
        }
        if (exposedFunctionTools.isNotEmpty() || includeComputerTool) {
            putJsonArray("tools") {
                if (includeComputerTool) {
                    add(buildJsonObject { put("type", "computer") })
                }
                exposedFunctionTools.forEach { tool -> add(functionTool(tool)) }
            }
        }
    }.let { request ->
        ReasoningRequestPolicy.apply(request, reasoningEffort,
            com.droidance.core.model.llm.models.ReasoningParameter.REASONING_OBJECT, forceDefaultReasoning)
    }

    private fun buildInput(
        messages: List<ChatMessage>,
        supportsVision: Boolean,
        state: OpenAIResponsesTurnState,
        imageDetail: ImageDetailOption,
        cacheStableHistory: Boolean
    ): JsonArray {
        val source = ProviderMessageProjection.forOpenAiCompatible(if (state.previousResponseId == null) {
            messages
        } else {
            messages.asReversed().takeWhile { it.role != Role.ASSISTANT }.asReversed()
        })
        val pendingComputerCallId = state.pendingComputerCallId
        val latestImage = source.asReversed()
            .filterNot { it.historicalImage }
            .firstNotNullOfOrNull { message ->
                message.imageBase64List.lastOrNull() ?: message.imageBase64
            }
            ?.takeIf { supportsVision }
        val declaredFunctionCallIds = source
            .asSequence()
            .filter { it.role == Role.ASSISTANT }
            .flatMap { it.toolCalls.orEmpty().asSequence() }
            .map(ToolCall::id)
            .filter(String::isNotBlank)
            .toSet()
        var computerScreenshotAdded = false
        val cacheEnd = if (cacheStableHistory) source.indexOfLast {
            it.cacheablePrefix && it.content.isNotBlank() &&
                (it.role == Role.USER || (it.role == Role.TOOL && !it.toolCallId.isNullOrBlank() &&
                    !it.toolCallId.startsWith(SYNTHETIC_TOOL_CALL_PREFIX) &&
                    (state.previousResponseId != null || it.toolCallId in declaredFunctionCallIds)))
        } else -1

        return buildJsonArray {
            if (latestImage != null) state.markImageSubmitted(imageDigest(latestImage))
            if (pendingComputerCallId != null && latestImage != null) {
                add(buildJsonObject {
                    put("type", "computer_call_output")
                    put("call_id", pendingComputerCallId)
                    putJsonObject("output") {
                        put("type", "computer_screenshot")
                        put("image_url", dataImageUrl(latestImage))
                        imageDetail.wireValue?.let { put("detail", it) }
                    }
                })
                state.markComputerCallSubmitted(pendingComputerCallId)
                computerScreenshotAdded = true
            }

            source.forEachIndexed { index, message ->
                if (
                    message.role == Role.TOOL &&
                    !message.toolCallId.isNullOrBlank() &&
                    !message.toolCallId.startsWith(SYNTHETIC_TOOL_CALL_PREFIX) &&
                    (state.previousResponseId != null || message.toolCallId in declaredFunctionCallIds)
                ) {
                    add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", message.toolCallId)
                        if (index == cacheEnd) put("output", buildJsonArray { add(cachedInputText(message.content)) })
                        else put("output", message.content)
                    })
                } else if (message.role == Role.ASSISTANT && !message.toolCalls.isNullOrEmpty()) {
                    if (message.content.isNotBlank()) {
                        add(buildMessage("assistant", message.content))
                    }
                    message.toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", call.id)
                            put("name", call.name)
                            put("arguments", call.arguments)
                        })
                    }
                } else if (message.role == Role.USER || message.role == Role.ASSISTANT) {
                    val role = if (message.role == Role.ASSISTANT) "assistant" else "user"
                    val images = message.imageBase64List.ifEmpty {
                        message.imageBase64?.let(::listOf).orEmpty()
                    }
                    val digests = if (role == "user" && supportsVision) images.map(::imageDigest) else emptyList()
                    // The stored chain already holds this historical image with its label.
                    if (message.historicalImage && digests.isNotEmpty() && digests.all(state::isRetainedImage)) {
                        state.markRetainedImageSkipped()
                        return@forEachIndexed
                    }
                    digests.forEach(state::markImageSubmitted)
                    add(buildJsonObject {
                        put("role", role)
                        putJsonArray("content") {
                            if (message.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", if (role == "assistant") "output_text" else "input_text")
                                    put("text", message.content)
                                    if (index == cacheEnd) put("prompt_cache_breakpoint", buildJsonObject { put("mode", "explicit") })
                                })
                            }
                            if (role == "user" && supportsVision) {
                                images.filterNot { computerScreenshotAdded && !message.historicalImage && it == latestImage }.forEach { image ->
                                    add(buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", dataImageUrl(image))
                                        imageDetail.wireValue?.let { put("detail", it) }
                                    })
                                }
                            }
                        }
                    })
                }
            }
        }
    }

    private fun imageDigest(base64: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(base64.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }

    private fun cachedInputText(text: String): JsonObject = buildJsonObject {
        put("type", "input_text"); put("text", text)
        put("prompt_cache_breakpoint", buildJsonObject { put("mode", "explicit") })
    }

    private fun buildMessage(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", if (role == "assistant") "output_text" else "input_text")
                put("text", content)
            })
        }
    }

    private fun functionTool(tool: ToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", tool.name)
        put("description", tool.description)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                tool.parameters.forEach { (name, parameter) ->
                    put(name, parameter.toJsonSchema())
                }
            }
            putJsonArray("required") {
                tool.parameters.filterValues { it.required }.keys
                    .forEach { add(JsonPrimitive(it)) }
            }
            put("additionalProperties", false)
        }
        put("strict", false)
    }

    fun parseEvent(json: JsonObject, state: OpenAIResponsesTurnState): List<LLMStreamEvent> {
        val type = json["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (type) {
            "response.created" -> listOf(LLMStreamEvent.Activity(LLMStreamEvent.Activity.Phase.RESPONSE_STARTED))
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                listOf(LLMStreamEvent.Activity(LLMStreamEvent.Activity.Phase.REASONING,
                    json["delta"]?.jsonPrimitive?.contentOrNull.orEmpty().length))
            "response.reasoning_summary_text.done", "response.reasoning_text.done" ->
                listOf(LLMStreamEvent.Activity(LLMStreamEvent.Activity.Phase.REASONING_FINISHED))
            "response.output_text.delta" -> {
                val delta = json["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                listOfNotNull(delta.takeIf(String::isNotEmpty)?.let(LLMStreamEvent::Content))
            }
            "response.output_item.added" -> parseAddedItem(json, state)
            "response.function_call_arguments.delta" -> {
                val outputIndex = json["output_index"]?.jsonPrimitive?.intOrNull ?: 0
                state.markFunctionArgumentsDelta(outputIndex)
                listOf(LLMStreamEvent.ToolCallChunk(
                    index = outputIndex,
                    arguments = json["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ))
            }
            "response.output_item.done" -> parseDoneItem(json, state)
            "response.completed" -> {
                val response = json["response"] as? JsonObject
                val stored = (response?.get("store") as? JsonPrimitive)?.booleanOrNull
                val cachingType = ((response?.get("caching") as? JsonObject)?.get("type") as? JsonPrimitive)
                    ?.contentOrNull
                // An explicitly unretained response cannot anchor the next request. Do not
                // infer this for an enabled or unrecognized explicit cache mode.
                val retained = stored != false || (cachingType != null && cachingType != "disabled")
                state.commitResponse(response?.get("id")?.jsonPrimitive?.contentOrNull, retained)
                emptyList()
            }
            "response.incomplete" -> {
                val response = json["response"] as? JsonObject
                val details = response?.get("incomplete_details") as? JsonObject
                throw OpenAIResponsesIncompleteException(
                    (details?.get("reason") as? JsonPrimitive)?.contentOrNull,
                    outputTokens = ((response?.get("usage") as? JsonObject)
                        ?.get("output_tokens") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 },
                    maxOutputTokens = (response?.get("max_output_tokens") as? JsonPrimitive)
                        ?.intOrNull?.takeIf { it > 0 }
                )
            }
            "error", "response.failed" -> {
                val error = (json["error"] as? JsonObject)
                    ?: (json["response"] as? JsonObject)?.get("error") as? JsonObject
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "OpenAI Responses returned $type"
                throw IllegalStateException(message)
            }
            else -> emptyList()
        }
    }

    private fun parseAddedItem(
        json: JsonObject,
        state: OpenAIResponsesTurnState
    ): List<LLMStreamEvent> {
        val item = json["item"] as? JsonObject ?: return emptyList()
        if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call") return emptyList()
        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
            ?: item["id"]?.jsonPrimitive?.contentOrNull
            ?: return emptyList()
        if (!state.announceFunctionCall(callId)) return emptyList()
        return listOf(LLMStreamEvent.ToolCallChunk(
            index = json["output_index"]?.jsonPrimitive?.intOrNull ?: 0,
            id = callId,
            name = item["name"]?.jsonPrimitive?.contentOrNull,
            arguments = ""
        ))
    }

    private fun parseDoneItem(
        json: JsonObject,
        state: OpenAIResponsesTurnState
    ): List<LLMStreamEvent> {
        val item = json["item"] as? JsonObject ?: return emptyList()
        val index = json["output_index"]?.jsonPrimitive?.intOrNull ?: 0
        return when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "function_call" -> {
                val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: item["id"]?.jsonPrimitive?.contentOrNull
                    ?: return emptyList()
                val initial = if (state.announceFunctionCall(callId)) {
                    listOf(LLMStreamEvent.ToolCallChunk(
                        index = index,
                        id = callId,
                        name = item["name"]?.jsonPrimitive?.contentOrNull,
                        arguments = ""
                    ))
                } else emptyList()
                val arguments = item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (arguments.isBlank() || state.hasFunctionArgumentsDelta(index)) initial
                else initial + LLMStreamEvent.ToolCallChunk(index = index, arguments = arguments)
            }
            "computer_call" -> {
                val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: item["id"]?.jsonPrimitive?.contentOrNull
                    ?: return emptyList()
                state.rememberComputerCall(callId)
                val actions = (item["actions"] as? JsonArray ?: JsonArray(emptyList())).map { value ->
                    normalizeComputerAction(value.jsonObject, state.computerFrameSize)
                }
                listOf(LLMStreamEvent.ToolCallChunk(
                    index = index,
                    id = callId,
                    name = "screen_act",
                    arguments = buildJsonObject {
                        put("actions", JsonArray(actions))
                    }.toString()
                ))
            }
            else -> emptyList()
        }
    }

    /** Translate the provider's native computer protocol at the transport boundary. */
    private fun normalizeComputerAction(action: JsonObject, frameSize: Pair<Int, Int>?): JsonObject = buildJsonObject {
        val type = requireNotNull(action["type"]?.jsonPrimitive?.contentOrNull)
        when (type) {
            "click" -> {
                require(action["button"]?.jsonPrimitive?.contentOrNull in listOf(null, "left")) { "Only primary clicks are supported" }
                val size = requireNotNull(frameSize) { "Computer click requires the submitted frame dimensions" }
                val x = requireNotNull(action["x"]?.jsonPrimitive?.intOrNull)
                val y = requireNotNull(action["y"]?.jsonPrimitive?.intOrNull)
                require(size.first > 0 && size.second > 0 && x in 0 until size.first && y in 0 until size.second)
                put("op", "click")
                put("x", kotlin.math.round(x * 1000.0 / size.first).toInt())
                put("y", kotlin.math.round(y * 1000.0 / size.second).toInt())
            }
            "type" -> {
                put("op", "type_text")
                put("text", requireNotNull(action["text"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull))
                // Native computer type inserts into the current selection; it never clears the editor.
                put("clear", false)
            }
            "keypress" -> {
                val keys = requireNotNull(action["keys"] as? JsonArray)
                val key = requireNotNull(keys.singleOrNull()?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull)
                put("op", "press_key")
                put("key", when (key.uppercase()) { "BACKSPACE" -> "DEL"; "RETURN" -> "ENTER"; else -> key.uppercase() })
            }
            "scroll" -> {
                val x = action["scroll_x"]?.jsonPrimitive?.intOrNull ?: 0
                val y = action["scroll_y"]?.jsonPrimitive?.intOrNull ?: 0
                require(x != 0 || y != 0) { "Computer scroll requires a nonzero direction" }
                put("op", "scroll")
                put("direction", if (kotlin.math.abs(y.toLong()) >= kotlin.math.abs(x.toLong())) {
                    if (y > 0) "down" else "up"
                } else if (x > 0) "right" else "left")
                put("amount", "medium")
            }
            "wait", "screenshot" -> put("op", type)
            else -> error("Unsupported computer action: $type")
        }
    }

    fun usage(json: JsonObject): Usage? {
        if (json["type"]?.jsonPrimitive?.contentOrNull !in
            setOf("response.completed", "response.incomplete", "response.failed")
        ) return null
        val usage = (json["response"] as? JsonObject)?.get("usage") as? JsonObject ?: return null
        val input = (usage["input_tokens"] as? JsonPrimitive)?.intOrNull
        val output = (usage["output_tokens"] as? JsonPrimitive)?.intOrNull
        val inputDetails = usage["input_tokens_details"] as? JsonObject
        val cached = inputDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull
        return Usage(
            inputTokens = input?.coerceAtLeast(0) ?: 0,
            outputTokens = output?.coerceAtLeast(0) ?: 0,
            inputTokensReported = input != null,
            outputTokensReported = output != null,
            cachedInputTokens = cached?.coerceAtLeast(0) ?: 0,
            cacheFieldsReported = cached != null,
            cacheWriteTokens = inputDetails?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull?.coerceAtLeast(0),
            reasoningTokens = (usage["output_tokens_details"] as? JsonObject)
                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull?.coerceAtLeast(0)
        )
    }

    fun requireCompleted(responseCompleted: Boolean) {
        if (!responseCompleted) {
            throw OpenAIResponsesUnterminatedException()
        }
    }

    private fun dataImageUrl(base64: String): String {
        val mime = if (base64.startsWith("iVBOR")) "image/png" else "image/jpeg"
        return "data:$mime;base64,$base64"
    }
}
