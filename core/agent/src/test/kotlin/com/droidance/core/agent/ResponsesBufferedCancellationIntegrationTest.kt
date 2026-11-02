// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.*
import com.droidance.core.model.llm.models.*
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ResponsesBufferedCancellationIntegrationTest {
    @Test fun completedIsolatedResponseDiscardedByDeadlineConsumerRebuildsOnlyExecutedHistory() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val rebuiltBody = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Ready\"}\n\n" + response("rebuilt")
        val bodies = listOf(response("R1", "C1"), response("isolated", "discarded"), rebuiltBody, response("next"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = bodies[requests.size]
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val router = LLMRouter(client, Configuration(), Usage(), Diagnostics())
        val session = router.createSession()
        val initial = listOf(ChatMessage(Role.USER, "Inspect source"))
        val settled = initial + ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("C1", "inspect", "{}"))) +
            ChatMessage(Role.TOOL, "settled", toolCallId = "C1")
        try {
            session.streamChat(initial).toList()
            val producerCompleted = CompletableDeferred<Unit>()
            val failure = runCatching {
                session.streamChat(listOf(ChatMessage(Role.USER, "Review")), isolatedContext = true)
                    .onCompletion { cause -> if (cause == null) producerCompleted.complete(Unit) }
                    .collectWithDeadlines(ModelStreamDeadlinePolicy(0, 0, 0), { false }) { event ->
                        if (event is LLMStreamEvent.ToolCallChunk) {
                            // The actual deadline collector uses a buffered producer. A response
                            // may finish while its consumer has not accepted the tool call yet.
                            withTimeout(5_000) { producerCompleted.await() }
                            throw CancellationException("discarded before tool execution")
                        }
                    }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            session.streamChat(settled).toList()
            session.streamChat(settled + ChatMessage(Role.ASSISTANT, "Ready") + ChatMessage(Role.USER, "Continue")).toList()
        } finally { session.close() }
        assertEquals(4, requests.size)
        val rebuilt = requests[2]
        assertNull(rebuilt["previous_response_id"])
        val input = rebuilt.getValue("input").jsonArray.map { it.jsonObject }
        for (type in listOf("function_call", "function_call_output")) {
            assertEquals(listOf(JsonPrimitive("C1")), input.filter { it["type"] == JsonPrimitive(type) }.map { it["call_id"] })
        }
        assertFalse(rebuilt.toString().contains("discarded"))
        assertEquals(JsonPrimitive("rebuilt"), requests[3]["previous_response_id"])
        assertFalse(requests[3].getValue("input").toString().contains("function_call_output"))
    }

    private fun response(id: String, call: String? = null): String =
        (call?.let { "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"$it\",\"name\":\"inspect\",\"arguments\":\"{}\"}}\n\n" } ?: "") +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"$id\"}}\n\n"

    private class Configuration : TransportConfiguration {
        private val provider = ProviderConfig(id = "test", name = "test", apiType = ApiType.OPENAI_RESPONSES,
            transportKind = ModelTransport.OPENAI_RESPONSES, baseUrl = "https://example.invalid/v1",
            apiKey = "test-key", defaultModel = "test-model")
        override fun resolveProvider() = provider
        override fun getCurrentModel(config: ProviderConfig) = config.defaultModel
        override fun advanceToNextModel(config: ProviderConfig): String? = null
        override fun resetModelFallback(configId: String) = Unit
        override fun reasoningCapability(config: ProviderConfig, model: String) = ReasoningCapability()
        override fun recordReasoningRejected(config: ProviderConfig, model: String) = Unit
        override fun imageDetailCandidates(config: ProviderConfig, model: String) = listOf(ImageDetailOption.HIGH)
        override fun imageDetailCapabilityStatus(config: ProviderConfig, model: String) =
            ImageDetailCapabilityStatus(ImageDetailOption.HIGH, 1L, null, false)
        override fun recordImageDetailAccepted(config: ProviderConfig, model: String, detail: ImageDetailOption, returnedModel: String?) = Unit
        override fun recordImageDetailRejected(config: ProviderConfig, model: String, detail: ImageDetailOption) = Unit
    }

    private class Usage : TransportUsageRecorder {
        override fun recordRequest(usage: com.droidance.core.model.llm.ModelRequestUsage) = Unit
    }

    private class Diagnostics : TransportDiagnostics {
        override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String, request: JsonObject) =
            object : TransportExchangeTrace {
                override fun response(status: Int, requestId: String?, retryAfter: String?) = Unit
                override fun timing(name: String, elapsedMs: Long) = Unit
                override fun line(value: String) = Unit
                override fun finish(error: Throwable?) = Unit
            }
        override fun requestStarted() = Unit
        override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) = Unit
        override fun warning(tag: String, message: String) = Unit
        override fun debug(tag: String, message: String) = Unit
    }
}
