// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIResponsesProtocolTest {
    @Test fun explicitHistoryBoundaryPreservesToolOutputImagesAndCurrentTail() {
        val history = listOf(
            ChatMessage(Role.USER, "goal", cacheablePrefix = true),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("call-1", "read", "{}")), cacheablePrefix = true),
            ChatMessage(Role.TOOL, "original evidence", toolCallId = "call-1", cacheablePrefix = true),
            ChatMessage(Role.USER, "history image", historicalImage = true, imageBase64 = "AAAA"),
            ChatMessage(Role.USER, "current image", imageBase64 = "BBBB")
        )
        fun request(cache: Boolean) = OpenAIResponsesProtocol.buildRequest(
            model = "anthropic/example", messages = history, tools = emptyList(), systemPrompt = "rules",
            supportsVision = true, state = OpenAIResponsesTurnState(), maxOutputTokens = 4096, cacheStableHistory = cache
        )
        val normal = request(false)
        val cached = request(true)
        val before = normal.getValue("input").jsonArray
        val after = cached.getValue("input").jsonArray
        assertEquals(before.size, after.size)
        val toolIndex = before.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
        val tool = after[toolIndex].jsonObject
        assertEquals(before[toolIndex].jsonObject["call_id"], tool["call_id"])
        val output = tool.getValue("output").jsonArray.single().jsonObject
        assertEquals("original evidence", output.getValue("text").jsonPrimitive.content)
        assertEquals("explicit", output.getValue("prompt_cache_breakpoint").jsonObject.getValue("mode").jsonPrimitive.content)
        before.indices.filter { it != toolIndex }.forEach { assertEquals(before[it], after[it]) }
        assertEquals(normal["instructions"], cached["instructions"])
        assertFalse(normal.toString().contains("prompt_cache_breakpoint"))
    }

    @Test fun cacheWriteUsageRemainsNullable() {
        val usage = OpenAIResponsesProtocol.usage(json("""{"type":"response.completed","response":{"usage":{
            "input_tokens":100,"output_tokens":10,"input_tokens_details":{"cached_tokens":60,"cache_write_tokens":30}}}}"""))!!
        assertEquals(60, usage.cachedInputTokens)
        assertEquals(30, usage.cacheWriteTokens)
        assertNull(OpenAIResponsesProtocol.usage(json("""{"type":"response.completed","response":{"usage":{"input_tokens":100}}}"""))!!.cacheWriteTokens)
    }

    @Test fun nativeComputerActionsTranslateToTheOnePublicMobileVocabulary() {
        val state = OpenAIResponsesTurnState()
        val events = OpenAIResponsesProtocol.parseEvent(json("""
            {"type":"response.output_item.done","output_index":0,
             "item":{"type":"computer_call","call_id":"native",
             "actions":[{"type":"type","text":"query"},{"type":"keypress","keys":["ENTER"]},
             {"type":"scroll","scroll_y":500,"scroll_x":0},{"type":"wait"},{"type":"screenshot"}]}}
        """), state)
        val payload = json((events.single() as LLMStreamEvent.ToolCallChunk).arguments)
        val actions = payload.getValue("actions").jsonArray
        assertEquals(listOf("type_text", "press_key", "scroll", "wait", "screenshot"),
            actions.map { it.jsonObject.getValue("op").jsonPrimitive.content })
        assertEquals(false, actions[0].jsonObject.getValue("clear").jsonPrimitive.boolean)
        assertEquals("ENTER", actions[1].jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals("down", actions[2].jsonObject.getValue("direction").jsonPrimitive.content)
        assertTrue(actions.all { "type" !in it.jsonObject })
    }

    @Test fun reasoningActivityReportsTimingSignalsWithoutPublishingOrRetainingReasoningText() {
        val state = OpenAIResponsesTurnState()
        val start = OpenAIResponsesProtocol.parseEvent(json("""{"type":"response.created"}"""), state)
        assertEquals(LLMStreamEvent.Activity(LLMStreamEvent.Activity.Phase.RESPONSE_STARTED), start.single())
        val events = OpenAIResponsesProtocol.parseEvent(json("""{"type":"response.reasoning_summary_text.delta","delta":"private reasoning"}"""), state)
        assertEquals(LLMStreamEvent.Activity(LLMStreamEvent.Activity.Phase.REASONING, 17), events.single())
        assertFalse(events.any { it is LLMStreamEvent.Content || it is LLMStreamEvent.ReasoningChunk })
        val usage = OpenAIResponsesProtocol.usage(json("""{"type":"response.completed","response":{"usage":{"input_tokens":100,"output_tokens":30,"output_tokens_details":{"reasoning_tokens":20}}}}"""))
        assertEquals(20, usage?.reasoningTokens)
        assertEquals(30, usage?.outputTokens)
    }

    @Test
    fun outputBudgetRecoveryIsBoundedAndRequiresEvidence() {
        assertEquals(65536, OpenAIResponsesIncompleteException("max_output_tokens").expandedOutputBudget(32768, 65536))
        assertEquals(65536, OpenAIResponsesIncompleteException("max_output_tokens").expandedOutputBudget(65536, 65536))
        assertEquals(2048, OpenAIResponsesIncompleteException(null, 1024, 1024).expandedOutputBudget(1024, 4096))
        assertEquals(3000, OpenAIResponsesIncompleteException("max_output_tokens").expandedOutputBudget(2048, 3000))
        assertEquals(4096, OpenAIResponsesIncompleteException("max_output_tokens").expandedOutputBudget(4096, 4096))
        assertEquals(1024, OpenAIResponsesIncompleteException(null).expandedOutputBudget(1024, 4096))
        assertEquals(1024, OpenAIResponsesIncompleteException("content_filter", 1024, 1024).expandedOutputBudget(1024, 4096))
        assertEquals(1024, OpenAIResponsesIncompleteException(null, 512, 1024).expandedOutputBudget(1024, 4096))
    }

    @Test
    fun incompleteResponseRetainsNumericBudgetDiagnostics() {
        val failure = assertThrows(OpenAIResponsesIncompleteException::class.java) {
            OpenAIResponsesProtocol.parseEvent(
                json("""{"type":"response.incomplete","response":{
                    "incomplete_details":{"reason":"unknown-provider-text"},
                    "max_output_tokens":1024,"usage":{"output_tokens":1024}}}"""),
                OpenAIResponsesTurnState()
            )
        }
        assertEquals(1024, failure.outputTokens)
        assertEquals(1024, failure.maxOutputTokens)
        assertFalse(failure.message.orEmpty().contains("unknown-provider-text"))
        assertTrue(failure.message.orEmpty().contains("output_tokens=1024"))
    }

    @Test
    fun missingCompletedBoundaryRejectsTruncatedStream() {
        assertThrows(OpenAIResponsesUnterminatedException::class.java) {
            OpenAIResponsesProtocol.requireCompleted(false)
        }
        OpenAIResponsesProtocol.requireCompleted(true)
    }

    @Test
    fun abortedResponseRestoresComputerCallAndParserStateForRetry() {
        val state = OpenAIResponsesTurnState()
        state.commitResponse("previous-completed")
        state.rememberComputerCall("previous-call")
        state.markComputerCallSubmitted("previous-call")
        state.announceFunctionCall("partial-function")
        state.markFunctionArgumentsDelta(0)
        state.rememberComputerCall("unexecuted-call")

        state.abortResponse("previous-call")

        assertEquals("previous-completed", state.previousResponseId)
        assertEquals("previous-call", state.pendingComputerCallId)
        assertTrue(state.announceFunctionCall("partial-function"))
        assertFalse(state.hasFunctionArgumentsDelta(0))
        state.markComputerCallSubmitted("previous-call")
        state.commitResponse("retry-completed")
        assertNull(state.pendingComputerCallId)
    }

    @Test
    fun incompleteResponseDiagnosticsDoNotCopyUntrustedProviderText() {
        val failure = assertThrows(OpenAIResponsesIncompleteException::class.java) {
            OpenAIResponsesProtocol.parseEvent(json("""
                {"type":"response.incomplete","response":{
                 "incomplete_details":{"reason":"secret-user-content"},
                 "output":[{"text":"secret-user-content"}]}}
            """), OpenAIResponsesTurnState())
        }

        assertFalse(failure.message.orEmpty().contains("secret-user-content"))
        assertTrue(failure.message.orEmpty().contains("unspecified"))
    }

    @Test
    fun incompleteResponseCannotBeAcceptedAsAnEmptySuccessfulStream() {
        val state = OpenAIResponsesTurnState()
        state.commitResponse("previous-completed")

        assertThrows(OpenAIResponsesIncompleteException::class.java) {
            OpenAIResponsesProtocol.parseEvent(json("""
                {"type":"response.incomplete","response":{"id":"unfinished",
                 "incomplete_details":{"reason":"max_output_tokens"},"output":[]}}
            """), state)
        }
        assertEquals("previous-completed", state.previousResponseId)
    }

    @Test
    fun incompleteAndFailedResponsesRetainReportedUsage() {
        listOf("response.incomplete", "response.failed").forEach { type ->
            val usage = OpenAIResponsesProtocol.usage(json("""
                {"type":"$type","response":{"usage":{
                  "input_tokens":150,"output_tokens":1024,
                  "input_tokens_details":{"cached_tokens":100}
                }}}
            """))!!

            assertEquals(150, usage.inputTokens)
            assertEquals(1024, usage.outputTokens)
            assertEquals(100, usage.cachedInputTokens)
        }
    }

    @Test
    fun firstRequestUsesResponsesFunctionAndVisionShapes() {
        val request = OpenAIResponsesProtocol.buildRequest(
            model = "gpt-test",
            messages = listOf(ChatMessage(
                role = Role.USER,
                content = "inspect",
                imageBase64 = "iVBORframe"
            )),
            tools = listOf(ToolDefinition(
                name = "observe_screen",
                description = "observe",
                parameters = mapOf(
                    "mode" to ToolParameter("string", "mode", false),
                    "items" to ToolParameter(
                        "array",
                        "structured items",
                        false,
                        items = ToolParameter("object", "one item", false)
                    )
                )
            )),
            systemPrompt = "system",
            supportsVision = true,
            maxOutputTokens = 512,
            state = OpenAIResponsesTurnState()
        )

        assertEquals("gpt-test", request["model"]?.jsonPrimitive?.content)
        assertEquals(512, request["max_output_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(request["previous_response_id"])
        val tool = request["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        assertEquals("observe_screen", tool["name"]?.jsonPrimitive?.content)
        assertEquals(
            "object",
            tool["parameters"]!!.jsonObject["properties"]!!.jsonObject["items"]!!
                .jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content
        )
        val content = request["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertTrue(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" })
        val image = content.single { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }.jsonObject
        assertEquals("high", image["detail"]?.jsonPrimitive?.content)
        assertEquals("data:image/png;base64,iVBORframe", image["image_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun unretainedCompletedResponseClearsThePreviousAnchor() {
        for (cache in listOf("", ",\"caching\":{\"type\":\"disabled\"}")) {
            val state = OpenAIResponsesTurnState().apply { commitResponse("old-response") }
            OpenAIResponsesProtocol.parseEvent(json("""
                {"type":"response.completed","response":{"id":"not-stored","store":false$cache}}
            """), state)
            assertNull(state.previousResponseId)
        }
    }

    @Test
    fun storedUnspecifiedAndExplicitlyCachedResponsesKeepTheirAnchor() {
        for (fields in listOf("", ",\"store\":true", ",\"store\":false,\"caching\":{\"type\":\"enabled\"}",
            ",\"store\":false,\"caching\":{\"type\":\"provider-mode\"}")) {
            val state = OpenAIResponsesTurnState()
            OpenAIResponsesProtocol.parseEvent(json("""
                {"type":"response.completed","response":{"id":"retained-response"$fields}}
            """), state)
            assertEquals("retained-response", state.previousResponseId)
        }
    }

    @Test
    fun continuationUsesPreviousResponseAndTypedToolOutputs() {
        val state = OpenAIResponsesTurnState().apply {
            commitResponse("resp_1")
            rememberComputerCall("computer_1")
        }
        val request = OpenAIResponsesProtocol.buildRequest(
            model = "gpt-test",
            messages = listOf(
                ChatMessage(role = Role.ASSISTANT, content = ""),
                ChatMessage(role = Role.TOOL, content = "ok", toolCallId = "function_1"),
                ChatMessage(role = Role.USER, content = "current frame", imageBase64 = "iVBORnext")
            ),
            tools = emptyList(),
            systemPrompt = "system",
            supportsVision = true,
            maxOutputTokens = null,
            state = state
        )

        assertEquals("resp_1", request["previous_response_id"]?.jsonPrimitive?.content)
        val inputTypes = request["input"]!!.jsonArray.mapNotNull {
            it.jsonObject["type"]?.jsonPrimitive?.content
        }
        assertTrue("computer_call_output" in inputTypes)
        assertTrue("function_call_output" in inputTypes)
        assertFalse(request.toString().contains("\"type\":\"input_image\""))
        assertEquals("computer_1", state.pendingComputerCallId)
        OpenAIResponsesProtocol.parseEvent(json("""
            {"type":"response.completed","response":{"id":"resp_2"}}
        """), state)
        assertNull(state.pendingComputerCallId)
    }

    @Test
    fun continuationSendsEachHistoricalImageOnceWhileTheChainHoldsIt() {
        val state = OpenAIResponsesTurnState()
        fun request(vararg messages: ChatMessage) = OpenAIResponsesProtocol.buildRequest(
            model = "gpt-test", messages = messages.toList(), tools = emptyList(), systemPrompt = "system",
            supportsVision = true, maxOutputTokens = null, state = state)
        fun images(request: kotlinx.serialization.json.JsonObject) = request["input"]!!.jsonArray.flatMap { item ->
            item.jsonObject["content"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["image_url"]?.jsonPrimitive?.content }
        }
        fun complete(id: String, store: Boolean = true) = OpenAIResponsesProtocol.parseEvent(
            json("""{"type":"response.completed","response":{"id":"$id","store":$store}}"""), state)
        val first = request(ChatMessage(Role.USER, "task"),
            ChatMessage(Role.USER, "history a", historicalImage = true, imageBase64 = "iVBORa"),
            ChatMessage(Role.USER, "current", imageBase64 = "iVBORb"))
        assertEquals(listOf("data:image/png;base64,iVBORa", "data:image/png;base64,iVBORb"), images(first))
        complete("resp_1")
        // A failed attempt never marks its images as held by the provider.
        request(ChatMessage(Role.ASSISTANT, ""), ChatMessage(Role.USER, "history c", historicalImage = true, imageBase64 = "iVBORc"))
        val next = request(ChatMessage(Role.ASSISTANT, ""),
            ChatMessage(Role.USER, "history a", historicalImage = true, imageBase64 = "iVBORa"),
            ChatMessage(Role.USER, "history b", historicalImage = true, imageBase64 = "iVBORb"),
            ChatMessage(Role.USER, "history c", historicalImage = true, imageBase64 = "iVBORc"),
            ChatMessage(Role.USER, "current", imageBase64 = "iVBORa"))
        assertEquals("resp_1", next["previous_response_id"]?.jsonPrimitive?.content)
        assertEquals(listOf("data:image/png;base64,iVBORc", "data:image/png;base64,iVBORa"), images(next))
        assertEquals(2, state.skippedRetainedImages)
        complete("resp_2", store = false)
        val stateless = request(ChatMessage(Role.USER, "history a", historicalImage = true, imageBase64 = "iVBORa"))
        assertNull(stateless["previous_response_id"])
        assertEquals(listOf("data:image/png;base64,iVBORa"), images(stateless))
    }

    @Test
    fun continuationOmitsRuntimeAutoObservationReceipt() {
        val state = OpenAIResponsesTurnState().apply { commitResponse("resp_1") }
        val request = OpenAIResponsesProtocol.buildRequest(
            model = "gpt-test",
            messages = listOf(
                ChatMessage(role = Role.TOOL, content = "open ok", toolCallId = "call_open"),
                ChatMessage(role = Role.TOOL, content = "fresh screen", toolCallId = "auto_observe_call_open"),
                ChatMessage(role = Role.USER, content = "continue", imageBase64 = "iVBORnext")
            ),
            tools = emptyList(),
            systemPrompt = "system",
            supportsVision = true,
            maxOutputTokens = null,
            state = state
        )

        val input = request["input"]!!.jsonArray
        val functionOutputs = input.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertEquals(1, functionOutputs.size)
        assertEquals("call_open", functionOutputs.single().jsonObject["call_id"]?.jsonPrimitive?.content)
        val frame = input.single { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
            .jsonObject["content"]!!.jsonArray.single {
                it.jsonObject["type"]?.jsonPrimitive?.content == "input_image"
            }.jsonObject
        assertEquals("high", frame["detail"]?.jsonPrimitive?.content)
        assertEquals("data:image/png;base64,iVBORnext", frame["image_url"]?.jsonPrimitive?.content)
        assertTrue(input.none {
            it.jsonObject["call_id"]?.jsonPrimitive?.content == "auto_observe_call_open"
        })
    }

    @Test
    fun statelessContinuationProjectsDeclaredFunctionCallAndOutput() {
        val request = OpenAIResponsesProtocol.buildRequest(
            model = "gpt-test",
            messages = listOf(
                ChatMessage(
                    role = Role.ASSISTANT,
                    content = "Opening the app.",
                    toolCalls = listOf(ToolCall("call_open", "android_act", "{\"op\":\"open\"}"))
                ),
                ChatMessage(role = Role.TOOL, content = "opened", toolCallId = "call_open"),
                ChatMessage(role = Role.TOOL, content = "fresh screen", toolCallId = "auto_observe_call_open"),
                ChatMessage(role = Role.USER, content = "continue")
            ),
            tools = emptyList(),
            systemPrompt = "system",
            supportsVision = false,
            maxOutputTokens = null,
            state = OpenAIResponsesTurnState()
        )

        val input = request["input"]!!.jsonArray
        assertTrue(input.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" })
        assertTrue(input.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
        assertTrue(input.none {
            it.jsonObject["call_id"]?.jsonPrimitive?.content == "auto_observe_call_open"
        })
        assertEquals(
            "call_open",
            input.first { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }
                .jsonObject["call_id"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun streamingEventsProduceFunctionAndComputerCallsAndCommitResponseId() {
        val state = OpenAIResponsesTurnState()
        val added = OpenAIResponsesProtocol.parseEvent(json("""
            {"type":"response.output_item.added","output_index":0,
             "item":{"type":"function_call","call_id":"call_f","name":"click"}}
        """), state)
        val delta = OpenAIResponsesProtocol.parseEvent(json("""
            {"type":"response.function_call_arguments.delta","output_index":0,
             "call_id":"call_f","delta":"{\"x\":10}"}
        """), state)
        val done = OpenAIResponsesProtocol.parseEvent(json("""
            {"type":"response.output_item.done","output_index":0,
             "item":{"type":"function_call","call_id":"call_f","name":"click",
             "arguments":"{\"x\":10}"}}
        """), state)
        state.computerFrameSize = 100 to 200
        val screenAct = OpenAIResponsesProtocol.parseEvent(json("""
            {"type":"response.output_item.done","output_index":1,
             "item":{"type":"computer_call","call_id":"call_c",
             "actions":[{"type":"click","x":10,"y":20}]}}
        """), state)
        OpenAIResponsesProtocol.parseEvent(json("""
            {"type":"response.completed","response":{"id":"resp_2",
             "usage":{"input_tokens":3,"output_tokens":2}}}
        """), state)

        val initial = added.single() as LLMStreamEvent.ToolCallChunk
        assertEquals("call_f", initial.id)
        assertEquals("click", initial.name)
        assertEquals("{\"x\":10}", (delta.single() as LLMStreamEvent.ToolCallChunk).arguments)
        assertTrue(done.isEmpty())
        val screenActCall = screenAct.single() as LLMStreamEvent.ToolCallChunk
        assertEquals("screen_act", screenActCall.name)
        assertTrue(screenActCall.arguments.contains("\"x\":100"))
        assertTrue(screenActCall.arguments.contains("\"y\":100"))
        assertFalse(Json.parseToJsonElement(screenActCall.arguments).jsonObject.containsKey("op"))
        assertEquals("call_c", state.pendingComputerCallId)
        assertEquals("resp_2", state.previousResponseId)

        val nextDone = OpenAIResponsesProtocol.parseEvent(json("""
            {"type":"response.output_item.done","output_index":0,
             "item":{"type":"function_call","call_id":"call_next","name":"wait",
             "arguments":"{\"ms\":100}"}}
        """), state)
        assertTrue(nextDone.any {
            it is LLMStreamEvent.ToolCallChunk && it.arguments == "{\"ms\":100}"
        })
    }

    @Test
    fun completed_response_exposes_input_output_and_cached_tokens() {
        val usage = OpenAIResponsesProtocol.usage(json("""
            {"type":"response.completed","response":{"usage":{
              "input_tokens":150,"output_tokens":25,
              "input_tokens_details":{"cached_tokens":100}
            }}}
        """))!!

        assertEquals(150, usage.inputTokens)
        assertEquals(25, usage.outputTokens)
        assertEquals(100, usage.cachedInputTokens)
        assertTrue(usage.cacheFieldsReported)
    }

    @Test
    fun routerDispatchesByConfirmedTransportInsteadOfApiNameOnly() {
        val source = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()

        assertTrue(source.contains("when (transport)"))
        assertTrue(source.contains("ModelTransport.OPENAI_RESPONSES -> streamOpenAIResponses"))
        assertTrue(source.contains("ModelTransport.GENERIC_CHAT_COMPLETIONS -> streamOpenAICompatible"))
        assertTrue(source.contains("has no HTTP implementation"))
    }

    @Test
    fun enabledComputerToolUsesBuiltInSchemaAndHidesInternalBatchFunction() {
        val request = OpenAIResponsesProtocol.buildRequest(
            model = "gpt-test",
            messages = listOf(ChatMessage(role = Role.USER, content = "operate")),
            tools = listOf(ToolDefinition("screen_act", "logical", emptyMap())),
            systemPrompt = "system",
            supportsVision = true,
            maxOutputTokens = null,
            state = OpenAIResponsesTurnState(),
            includeComputerTool = true
        )

        val tools = request["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, tools.size)
        assertEquals("computer", tools.single()["type"]?.jsonPrimitive?.content)
        assertFalse(request.toString().contains("\"name\":\"screen_act\""))
    }

    @Test
    fun buildRequestIncludesReasoningEffortAndMergesExtraBodyJson() {
        val requestWithHigh = OpenAIResponsesProtocol.buildRequest(
            model = "gpt-test",
            messages = listOf(ChatMessage(role = Role.USER, content = "plan")),
            tools = emptyList(),
            systemPrompt = "system",
            supportsVision = false,
            maxOutputTokens = null,
            state = OpenAIResponsesTurnState(),
            reasoningEffort = com.droidance.core.model.llm.models.ReasoningEffort.HIGH
        )
        assertEquals("high", requestWithHigh["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)

        val requestWithDefault = OpenAIResponsesProtocol.buildRequest(
            model = "gpt-test",
            messages = listOf(ChatMessage(role = Role.USER, content = "plan")),
            tools = emptyList(),
            systemPrompt = "system",
            supportsVision = false,
            maxOutputTokens = null,
            state = OpenAIResponsesTurnState(),
            reasoningEffort = com.droidance.core.model.llm.models.ReasoningEffort.DEFAULT
        )
        assertNull(requestWithDefault["reasoning"])

    }

    private fun json(value: String) = Json.parseToJsonElement(value.trimIndent()).jsonObject

    private fun projectFile(path: String): java.io.File {
        var current = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            java.io.File(current, path).takeIf { it.exists() }?.let { return it }
            current = current.parentFile ?: error("Cannot locate $path")
        }
    }
}
