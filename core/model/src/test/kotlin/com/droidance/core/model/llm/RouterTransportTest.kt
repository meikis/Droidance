// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ImageDetailOption
import com.droidance.core.model.llm.models.ImageDetailCapabilityStatus
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.model.llm.models.toJsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTransportTest {
    @Test fun retryAfterHeaderIsPreservedByBothTransports() = runBlocking {
        for ((api, transport) in listOf(
            ApiType.OPENAI_COMPATIBLE to ModelTransport.GENERIC_CHAT_COMPLETIONS,
            ApiType.OPENAI_RESPONSES to ModelTransport.OPENAI_RESPONSES)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(429).message("Rate limited").header("Retry-After", "7")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val router = LLMRouter(client, StaticConfiguration(provider(api, transport)), RecordingUsage(), RecordingDiagnostics())
            val error = runCatching { router.streamChat(listOf(ChatMessage(Role.USER, "hello"))).toList() }
                .exceptionOrNull()?.providerHttpException()
            assertEquals(7000L, error?.retryAfterMs)
        }
    }
    @Test fun toolRequirementReachesBothTransportsOnlyWithinTheActivatedRequestScope() = runBlocking {
        for ((api, transport, body) in listOf(
            Triple(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS, "data: [DONE]\n\n"),
            Triple(ApiType.OPENAI_RESPONSES, ModelTransport.OPENAI_RESPONSES, responsesBody("r1")))) {
            val requests = mutableListOf<Request>()
            val router = router(provider(api, transport), body, requests = requests,
                usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
            kotlinx.coroutines.withContext(ToolResponseRequirement()) {
                router.streamChat(listOf(ChatMessage(Role.USER, "Finish the active task")),
                    tools = listOf(ToolDefinition("done", "Submit the answer", emptyMap()))).toList()
            }
            assertEquals(JsonPrimitive("required"), requestBody(requests.single())["tool_choice"])
            requests.clear()
            router.streamChat(listOf(ChatMessage(Role.USER, "Ordinary chat"))).toList()
            assertTrue(requestBody(requests.single())["tool_choice"] != JsonPrimitive("required"))
        }
    }
    @Test fun chatContentFollowedByEofWithoutATerminalEventFails() = runBlocking {
        for (usage in listOf("", "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}\n\n")) {
            val diagnostics = RecordingDiagnostics()
            val router = router(provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS),
                body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n" + usage,
                requests = mutableListOf(), usage = RecordingUsage(), diagnostics = diagnostics)
            val failure = runCatching { router.streamChat(listOf(ChatMessage(Role.USER, "test"))).toList() }.exceptionOrNull()
            assertTrue("Unterminated Chat content must fail instead of appearing complete", failure is java.io.IOException)
            assertTrue(diagnostics.failures.single() is java.io.IOException)
            assertEquals(failure?.message, diagnostics.failures.single()?.message)
        }
    }

    @Test fun chatExplicitDoneOrFinishReasonAllowsEofAndOptionalDataSpace() = runBlocking {
        for (prefix in listOf("data: ", "data:")) {
            for (terminal in listOf("[DONE]", "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")) {
                val router = router(provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS),
                    body = prefix + "{\"choices\":[{\"delta\":{\"content\":\"complete\"}}]}\n\n" + prefix + terminal + "\n\n",
                    requests = mutableListOf(), usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
                assertEquals("complete", router.streamChat(listOf(ChatMessage(Role.USER, "test"))).toList()
                    .filterIsInstance<LLMStreamEvent.Content>().joinToString("") { it.text })
            }
        }
    }

    @Test fun chatNoSpaceTerminalMetadataIsRetainedThroughUsage() = runBlocking {
        val router = router(provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS),
            body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" +
                "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" +
                "data:{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2048}}\n\ndata:[DONE]\n\n",
            requests = mutableListOf(), usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
        val metadata = router.streamChat(listOf(ChatMessage(Role.USER, "test"))).toList()
            .filterIsInstance<LLMStreamEvent.ResponseMetadata>()
        assertEquals(listOf("length", "length"), metadata.map { it.finishReason })
        assertEquals(2048, metadata.last().outputTokens)
    }

    @Test fun responsesStillRequiresCompletedRatherThanDoneOrEof() = runBlocking {
        for (terminal in listOf("", "data:[DONE]\n\n", "data:{\"type\":\"response.completed\",\"response\":{\"id\":\"r1\"}}\n\n")) {
            val router = router(provider(ApiType.OPENAI_RESPONSES, ModelTransport.OPENAI_RESPONSES),
                body = "data:{\"type\":\"response.output_text.delta\",\"delta\":\"summary\"}\n\n" + terminal,
                requests = mutableListOf(), usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
            val result = runCatching { router.streamChat(listOf(ChatMessage(Role.USER, "test"))).toList() }
            if (terminal.contains("response.completed")) assertTrue(result.isSuccess)
            else assertTrue(result.exceptionOrNull() is OpenAIResponsesUnterminatedException)
        }
    }

    @Test fun onlyExplicitFinishAllowsTheFinalUsageGraceTimeout() = runBlocking {
        for (finished in listOf(false, true)) {
            val release = java.util.concurrent.CountDownLatch(1)
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1") { exchange ->
                try {
                    exchange.requestBody.readBytes()
                    exchange.sendResponseHeaders(200, 0)
                    val terminal = if (finished) "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" else ""
                    exchange.responseBody.write(("data:{\"choices\":[{\"delta\":{\"content\":\"ready\"}}]}\n\n" + terminal).toByteArray())
                    exchange.responseBody.flush()
                    release.await(4, java.util.concurrent.TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            server.start()
            try {
                val provider = provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS)
                    .copy(baseUrl = "http://127.0.0.1:${server.address.port}/v1")
                val router = LLMRouter(OkHttpClient(), StaticConfiguration(provider), RecordingUsage(), RecordingDiagnostics())
                val result = runCatching {
                    kotlinx.coroutines.withTimeout(3000) {
                        router.streamChat(listOf(ChatMessage(Role.USER, "test")), requestTimeoutMs = if (finished) 0 else 200).toList()
                    }
                }
                if (finished) assertEquals("ready", result.getOrThrow().filterIsInstance<LLMStreamEvent.Content>().single().text)
                else assertTrue(result.exceptionOrNull() is java.io.IOException)
            } finally { release.countDown(); server.stop(0) }
        }
    }

    @Test fun slowSilentStreamsAreNotCutOffByTheSharedHttpClientTimeouts() = runBlocking {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.readBytes()
            Thread.sleep(80)
            val body = if (exchange.requestURI.path.endsWith("responses"))
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ready\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\"}}\n\n"
            else "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n"
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            for ((api, transport) in listOf(ApiType.OPENAI_COMPATIBLE to ModelTransport.GENERIC_CHAT_COMPLETIONS,
                ApiType.OPENAI_RESPONSES to ModelTransport.OPENAI_RESPONSES)) {
                val provider = provider(api, transport).copy(baseUrl = "http://127.0.0.1:${server.address.port}/v1")
                val router = LLMRouter(OkHttpClient.Builder().readTimeout(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .callTimeout(10, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
                    StaticConfiguration(provider), RecordingUsage(), RecordingDiagnostics())
                val events = router.streamChat(listOf(ChatMessage(Role.USER, "test")), requestTimeoutMs = 0).toList()
                assertTrue(events.contains(LLMStreamEvent.Content("ready")))
            }
            assertEquals(2, requests.get())
        } finally { server.stop(0) }
    }

    @Test fun cancellingAnUnboundedStreamClosesItsSocketWithoutAnotherRequest() = runBlocking {
        val received = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1") { exchange ->
            try {
                requests.incrementAndGet()
                exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(": waiting\n\n".toByteArray())
                exchange.responseBody.flush()
                received.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val provider = provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS)
                .copy(baseUrl = "http://127.0.0.1:${server.address.port}/v1")
            val router = LLMRouter(OkHttpClient(), StaticConfiguration(provider), RecordingUsage(), RecordingDiagnostics())
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                router.streamChat(listOf(ChatMessage(Role.USER, "test")), requestTimeoutMs = 0).toList()
            }
            try {
                assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    received.await(2, java.util.concurrent.TimeUnit.SECONDS)
                })
                kotlinx.coroutines.withTimeout(1000) { job.cancel(); job.join() }
                assertEquals(1, requests.get())
            } finally { job.cancel(); release.countDown() }
        } finally { server.stop(0) }
    }

    @Test fun cacheSessionIsStableWithinAConversationAndOnlySentToTheSupportedHost() {
        fun request(host: String, thread: String, round: String) = Request.Builder().url("https://$host/v1")
            .withCacheSession(ModelDiagnosticScope(thread, "turn", round)).build()
        val first = request("openrouter.ai", "one", "r1").header("x-session-id")
        assertTrue(first != null && first.length <= 256)
        assertEquals(first, request("openrouter.ai", "one", "r2").header("x-session-id"))
        assertTrue(first != request("openrouter.ai", "two", "r1").header("x-session-id"))
        assertEquals(null, request("example.invalid", "one", "r1").header("x-session-id"))
    }

    @Test fun imageRejectionDoesNotReplaceTheFrozenSelectedModel() = runBlocking {
        val provider = provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS)
        val configuration = StaticConfiguration(provider, nextModel = "another-model")
        val requests = mutableListOf<Request>()
        val router = router(
            provider, """{"error":{"code":"unsupported_image","message":"Image input is unavailable"}}""",
            statusCode = 400, contentType = "application/json".toMediaType(),
            requests = requests, usage = RecordingUsage(), diagnostics = RecordingDiagnostics(), configuration = configuration
        )
        val session = router.createSession(ModelStreamPlan.fromProvider(provider, provider.defaultModel, false))
        try {
            val error = runCatching {
                session.streamChat(listOf(ChatMessage(Role.USER, "inspect", imageBase64 = "dGVzdA==")), hasImage = true).toList()
            }.exceptionOrNull()
            assertTrue(error != null)
            assertEquals(0, configuration.fallbackAttempts)
            assertEquals(1, requests.size)
            assertTrue(!session.canSendVisionMessages())
            assertEquals(provider.defaultModel, session.planStreamChat(emptyList(), false).initialModel)
        } finally { session.close() }
    }

    @Test fun explicitImageRejectionIsConfinedToTheCurrentTurn() = runBlocking {
        val provider = provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS)
        val requests = mutableListOf<Request>()
        val router = router(provider, """{"error":{"code":"unsupported_image"}}""",
            statusCode = 400, contentType = "application/json".toMediaType(), requests = requests,
            usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
        val first = router.createSession()
        try {
            assertTrue(runCatching {
                first.streamChat(listOf(ChatMessage(Role.USER, "inspect", imageBase64 = "dGVzdA==")), hasImage = true).toList()
            }.isFailure)
            assertTrue(!first.canSendVisionMessages())
        } finally { first.close() }
        val next = router.createSession()
        try {
            assertTrue("A previous turn's capability rejection must not silently remove this turn's images", next.canSendVisionMessages())
            assertTrue(next.planStreamChat(emptyList(), true).imageIncluded)
        } finally { next.close() }
    }

    @Test fun lateImageRejectionCannotPoisonAReplacementTurn() = runBlocking {
        for (useSession in listOf(false, true)) {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val diagnostics = RecordingDiagnostics().apply { beforeFinish = { error ->
                if (error is ProviderHttpException) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            } }
            val provider = provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS)
            val router = router(provider, """{"error":{"code":"unsupported_image"}}""", statusCode = 400,
                contentType = "application/json".toMediaType(), requests = mutableListOf(),
                usage = RecordingUsage(), diagnostics = diagnostics)
            var session: ModelSession? = if (useSession) router.createSession() else null
            if (!useSession) router.beginTurn()
            val originalSession = session
            val old = launch(kotlinx.coroutines.Dispatchers.Default) {
                assertTrue(runCatching {
                    val messages = listOf(ChatMessage(Role.USER, "inspect", imageBase64 = "dGVzdA=="))
                    if (originalSession != null) originalSession.streamChat(messages, hasImage = true).toList()
                    else router.streamChat(messages, hasImage = true).toList()
                }.isFailure)
            }
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val cancellationBeforeBoundary = router.currentCancellationGeneration()
                if (useSession) { session!!.close(); session = router.createSession() }
                else { router.endTurn(); router.beginTurn() }
                if (!useSession) assertEquals("Direct turn boundaries do not advance request cancellation",
                    cancellationBeforeBoundary, router.currentCancellationGeneration())
                release.countDown()
                kotlinx.coroutines.withTimeout(5_000) { old.join() }
                assertTrue("A late old failure must not remove new-turn vision", router.canSendVisionMessages())
                assertTrue(router.planStreamChat(emptyList(), true).imageIncluded)
            } finally {
                release.countDown()
                old.cancel(); old.join()
                if (useSession) session?.close() else router.endTurn()
            }
        }
    }

    @Test fun unavailableImageEndpointDoesNotDemoteOrReplaceTheSelectedModel() = runBlocking {
        val provider = provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS)
        val configuration = StaticConfiguration(provider, nextModel = "another-model")
        val requests = mutableListOf<Request>()
        val router = router(provider, """{"error":{"code":404,"message":"No endpoints found that support image input"}}""",
            statusCode = 404, contentType = "application/json".toMediaType(), requests = requests,
            usage = RecordingUsage(), diagnostics = RecordingDiagnostics(), configuration = configuration)
        val session = router.createSession()
        try {
            val failure = runCatching {
                session.streamChat(listOf(ChatMessage(Role.USER, "inspect", imageBase64 = "dGVzdA==")), hasImage = true).toList()
            }.exceptionOrNull()
            assertEquals("IMAGE_INPUT_UNAVAILABLE", failure?.providerHttpException()?.category?.name)
            assertEquals(1, requests.size)
            assertEquals(0, configuration.fallbackAttempts)
            assertTrue(session.canSendVisionMessages())
            assertTrue(session.planStreamChat(emptyList(), true).imageIncluded)
        } finally { session.close() }
    }

    @Test fun explicitSessionPlanControlsTransportAndDoesNotLeakIntoTheNextTurn() = runBlocking {
        val primary = provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS)
        val selected = primary.copy(
            id = "selected-provider", baseUrl = "https://selected.invalid/v1", defaultModel = "selected-model"
        )
        val requests = mutableListOf<Request>()
        val router = router(primary, "data: [DONE]\n\n", requests = requests, usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
        val plan = ModelStreamPlan.fromProvider(selected, "selected-model", false)
        val session = router.createSession(plan)
        try {
            val round = session.planStreamChat(emptyList(), hasImage = true)
            assertEquals("selected-provider", round.providerId)
            assertEquals("selected-model", round.initialModel)
            assertTrue(round.imageIncluded)
            assertTrue(session.canSendVisionMessages())
            session.streamChat(listOf(ChatMessage(Role.USER, "hello"))).toList()
        } finally { session.close() }
        assertEquals("selected.invalid", requests.single().url.host)
        assertRequestContains(requests.single(), "\"model\":\"selected-model\"")
        val selectedRequest = Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8()
        assertTrue(!selectedRequest.contains("reasoning_effort"))
        val next = router.createSession()
        try {
            assertEquals(primary.id, next.planStreamChat(emptyList(), false).providerId)
            assertEquals(primary.defaultModel, next.planStreamChat(emptyList(), false).initialModel)
        } finally { next.close() }
    }

    @Test fun diagnosticScopeAndRedactedBodyReachTransportRecorder() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val requests = mutableListOf<Request>()
        val router = router(provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS),
            body = "data: [DONE]\n\n", requests = requests, usage = RecordingUsage(), diagnostics = diagnostics)
        val scope = ModelDiagnosticScope("thread", "turn", "round")
        kotlinx.coroutines.withContext(scope) {
            router.streamChat(listOf(ChatMessage(Role.USER, "a test-key value"))).toList()
        }
        assertEquals(listOf(scope), diagnostics.scopes)
        assertTrue(diagnostics.requests.single().toString().contains("[REDACTED]"))
        assertTrue(!diagnostics.requests.single().toString().contains("test-key"))
        assertRequestContains(requests.single(), "test-key")
        assertEquals(listOf(200), diagnostics.statuses)
    }

    @Test
    fun chatHistoryRetainsProgressAlongsideToolCalls() = runBlocking {
        val requests = mutableListOf<Request>()
        val router = router(
            provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS),
            body = "data: [DONE]\n\n", requests = requests,
            usage = RecordingUsage(), diagnostics = RecordingDiagnostics()
        )
        val calls = listOf(com.droidance.core.model.llm.models.ToolCall("call-1", "inspect", "{}"))
        router.streamChat(listOf(
            ChatMessage(Role.USER, "continue"),
            ChatMessage(Role.ASSISTANT, "The first source is verified; the second is still missing.", toolCalls = calls),
            ChatMessage(Role.TOOL, "observed", toolCallId = "call-1")
        )).toList()
        assertRequestContains(requests.single(), "The first source is verified; the second is still missing.")
        assertRequestContains(requests.single(), "\"tool_calls\":[")
        assertRequestContains(requests.single(), "\"tool_call_id\":\"call-1\"")
    }

    @Test
    fun chatCompletionsStreamPassesContentAndUsageThroughTransportInterfaces() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"role":"assistant","content":"Hello "},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"chat"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":3}}}

            data: [DONE]

        """.trimIndent()
        val requests = mutableListOf<Request>()
        val usage = RecordingUsage()
        val diagnostics = RecordingDiagnostics()
        val router = router(
            provider = provider(
                apiType = ApiType.OPENAI_COMPATIBLE,
                transport = ModelTransport.GENERIC_CHAT_COMPLETIONS
            ),
            body = body,
            requests = requests,
            usage = usage,
            diagnostics = diagnostics
        )

        val events = router.streamChat(listOf(ChatMessage(Role.USER, "say hello"))).toList()
            .filterNot { it is LLMStreamEvent.ReasoningConfiguration || it is LLMStreamEvent.RequestConfiguration }

        assertEquals(
            listOf(LLMStreamEvent.Content("Hello "), LLMStreamEvent.Content("chat"),
                LLMStreamEvent.ResponseMetadata(null, null, null, finishReason = "stop"),
                LLMStreamEvent.ResponseMetadata(null, 12, 4, 3, finishReason = "stop")),
            events
        )
        assertEquals(1, diagnostics.requests.size)
        assertTrue(diagnostics.lines.isNotEmpty())
        assertEquals(1, diagnostics.failures.size)
        assertEquals(1, diagnostics.started)
        assertEquals(listOf(12 to 4), diagnostics.tokenUsages)
        assertEquals(
            listOf(RequestUsage("provider", "test-model", 12, 4, 3, true)),
            usage.records
        )
        assertEquals("https://example.invalid/v1/chat/completions", requests.single().url.toString())
        assertEquals("Bearer test-key", requests.single().header("Authorization"))
        assertRequestContains(requests.single(), "\"model\":\"test-model\"")
        assertRequestContains(requests.single(), "\"stream\":true")
    }

    @Test
    fun chatCompletionsStreamNormalizesEndpointWhenBaseUrlAlreadyIncludesPath() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"role":"assistant","content":"Hello "},"finish_reason":null}]}

            data: [DONE]

        """.trimIndent()
        val requests = mutableListOf<Request>()
        val router = router(
            provider = provider(
                apiType = ApiType.OPENAI_COMPATIBLE,
                transport = ModelTransport.GENERIC_CHAT_COMPLETIONS
            ).copy(baseUrl = "https://openrouter.ai/api/v1/chat/completions"),
            body = body,
            requests = requests,
            usage = RecordingUsage(),
            diagnostics = RecordingDiagnostics()
        )

        router.streamChat(listOf(ChatMessage(Role.USER, "say hello"))).toList()

        assertEquals("https://openrouter.ai/api/v1/chat/completions", requests.single().url.toString())
    }

    @Test
    fun responsesStreamNormalizesEndpointWhenBaseUrlAlreadyIncludesPath() = runBlocking {
        val body = """
            data: {"type":"response.output_text.delta","delta":"Hello"}

            data: {"type":"response.completed","response":{"id":"response-1","model":"wire-model","usage":{"input_tokens":1,"output_tokens":1}}}

        """.trimIndent()
        val requests = mutableListOf<Request>()
        val router = router(
            provider = provider(
                apiType = ApiType.OPENAI_RESPONSES,
                transport = ModelTransport.OPENAI_RESPONSES
            ).copy(baseUrl = "https://relay.invalid/team/v1/responses"),
            body = body,
            requests = requests,
            usage = RecordingUsage(),
            diagnostics = RecordingDiagnostics()
        )

        router.streamChat(listOf(ChatMessage(Role.USER, "say hello"))).toList()

        assertEquals("https://relay.invalid/team/v1/responses", requests.single().url.toString())
    }

    @Test
    fun responsesStreamPassesContentAndUsageThroughTransportInterfaces() = runBlocking {
        val body = """
            data: {"type":"response.output_text.delta","delta":"Hello "}

            data: {"type":"response.output_text.delta","delta":"responses"}

            data: {"type":"response.completed","response":{"id":"response-1","model":"wire-model","usage":{"input_tokens":20,"output_tokens":5,"input_tokens_details":{"cached_tokens":7}}}}

        """.trimIndent()
        val requests = mutableListOf<Request>()
        val usage = RecordingUsage()
        val diagnostics = RecordingDiagnostics()
        val router = router(
            provider = provider(
                apiType = ApiType.OPENAI_RESPONSES,
                transport = ModelTransport.OPENAI_RESPONSES
            ),
            body = body,
            requests = requests,
            usage = usage,
            diagnostics = diagnostics
        )

        val events = router.streamChat(listOf(ChatMessage(Role.USER, "say hello"))).toList()
            .filterNot { it is LLMStreamEvent.ReasoningConfiguration || it is LLMStreamEvent.RequestConfiguration }

        assertEquals(
            listOf(LLMStreamEvent.Content("Hello "), LLMStreamEvent.Content("responses"),
                LLMStreamEvent.ResponseMetadata("wire-model", 20, 5, 7)),
            events
        )
        assertEquals(1, diagnostics.requests.size)
        assertTrue(diagnostics.lines.isNotEmpty())
        assertEquals(1, diagnostics.failures.size)
        assertEquals(1, diagnostics.started)
        assertEquals(listOf(20 to 5), diagnostics.tokenUsages)
        assertEquals(
            listOf(RequestUsage("provider", "test-model", 20, 5, 7, true)),
            usage.records
        )
        assertEquals("https://example.invalid/v1/responses", requests.single().url.toString())
        assertEquals("Bearer test-key", requests.single().header("Authorization"))
        assertRequestContains(requests.single(), "\"model\":\"test-model\"")
        assertRequestContains(requests.single(), "\"stream\":true")
    }

    @Test
    fun httpFailureStillRecordsOneZeroUsageRequestWithoutEmittingContent() = runBlocking {
        val requests = mutableListOf<Request>()
        val usage = RecordingUsage()
        val diagnostics = RecordingDiagnostics()
        val router = router(
            provider = provider(
                apiType = ApiType.OPENAI_COMPATIBLE,
                transport = ModelTransport.GENERIC_CHAT_COMPLETIONS
            ),
            body = """{"error":{"message":"request rejected"}}""",
            statusCode = 400,
            contentType = "application/json".toMediaType(),
            requests = requests,
            usage = usage,
            diagnostics = diagnostics
        )

        assertThrows(ProviderHttpException::class.java) {
            runBlocking {
                router.streamChat(listOf(ChatMessage(Role.USER, "fail"))).toList()
            }
        }

        assertEquals(1, requests.size)
        assertEquals(
            listOf(RequestUsage("provider", "test-model", 0, 0, 0, false)),
            usage.records
        )
        assertTrue(diagnostics.tokenUsages.isEmpty())
    }

    @Test
    fun chatCompletionsStreamErrorInChunkThrowsProviderHttpExceptionWithServerStatus() = runBlocking {
        val requests = mutableListOf<Request>()
        val usage = RecordingUsage()
        val diagnostics = RecordingDiagnostics()
        val router = router(
            provider = provider(
                apiType = ApiType.OPENAI_COMPATIBLE,
                transport = ModelTransport.GENERIC_CHAT_COMPLETIONS
            ),
            body = "data: {\"error\":{\"code\":503,\"message\":\"Upstream error from Nvidia: Service temporarily overloaded\"}}\n\n",
            statusCode = 200,
            contentType = "text/event-stream".toMediaType(),
            requests = requests,
            usage = usage,
            diagnostics = diagnostics
        )

        val exception = assertThrows(ProviderHttpException::class.java) {
            runBlocking {
                router.streamChat(listOf(ChatMessage(Role.USER, "prompt"))).toList()
            }
        }
        assertEquals(503, exception.statusCode)
        assertEquals("503", exception.providerCode)
        assertEquals(ProviderErrorCategory.SERVER, exception.category)
        assertTrue(exception.message!!.contains("Service temporarily overloaded"))
    }

    @Test
    fun responsesStreamPassesReasoningEffortInRequestBody() = runBlocking {
        val requests = mutableListOf<Request>()
        val router = router(
            provider = provider(
                apiType = ApiType.OPENAI_RESPONSES,
                transport = ModelTransport.OPENAI_RESPONSES
            ),
            body = """
                data: {"type":"response.completed","response":{"id":"response-1","model":"wire-model","usage":{"input_tokens":10,"output_tokens":5}}}

            """.trimIndent(),
            requests = requests,
            usage = RecordingUsage(),
            diagnostics = RecordingDiagnostics()
        )

        router.streamChat(
            messages = listOf(ChatMessage(Role.USER, "plan")),
            reasoningEffort = com.droidance.core.model.llm.models.ReasoningEffort.HIGH
        ).toList()

        assertRequestContains(requests.single(), "\"reasoning\":{\"effort\":\"high\"}")
    }

    @Test fun terminalChoiceWithoutModelOrUsageStillReportsItsFinishReason() = runBlocking {
        val router = router(provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS),
            body = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n",
            requests = mutableListOf(), usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
        val metadata = router.streamChat(listOf(ChatMessage(Role.USER, "summarize"))).toList()
            .filterIsInstance<LLMStreamEvent.ResponseMetadata>().single()
        assertEquals("length", metadata.finishReason)
        assertEquals(null, metadata.outputTokens)
    }

    @Test fun reasoningIsReadFromEitherGatewayFieldWithoutDuplicates() = runBlocking {
        val body = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"deep\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"reasoning\":\"open\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"open\"}]},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"both\",\"reasoning\":\"both\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"reasoning\":null,\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val router = router(provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS),
            body = body, requests = mutableListOf(), usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
        val events = router.streamChat(listOf(ChatMessage(Role.USER, "think"))).toList()
        assertEquals(listOf("deep", "open", "both"), events.filterIsInstance<LLMStreamEvent.ReasoningChunk>().map { it.text })
        assertEquals(listOf("answer"), events.filterIsInstance<LLMStreamEvent.Content>().map { it.text })
    }

    @Test fun finishReasonSurvivesALaterUsageOnlyChunk() = runBlocking {
        for (reason in listOf("length", "stop", "content_filter")) {
            val router = router(provider(ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS),
                body = "data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2048}}\n\ndata: [DONE]\n\n",
                requests = mutableListOf(), usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
            val metadata = router.streamChat(listOf(ChatMessage(Role.USER, "summarize"))).toList()
                .filterIsInstance<LLMStreamEvent.ResponseMetadata>()
            assertEquals(null, metadata.first().finishReason)
            assertEquals(listOf(reason, reason), metadata.drop(1).map { it.finishReason })
            assertEquals(2048, metadata.last().outputTokens)
        }
    }

    @Test fun isolatedSummaryRequestsRetainHistoricalImagesAndTheirAuthorityMarker() = runBlocking {
        for (transport in listOf(ModelTransport.GENERIC_CHAT_COMPLETIONS, ModelTransport.OPENAI_RESPONSES)) {
            val api = if (transport == ModelTransport.OPENAI_RESPONSES) ApiType.OPENAI_RESPONSES else ApiType.OPENAI_COMPATIBLE
            val requests = mutableListOf<Request>()
            val body = if (transport == ModelTransport.OPENAI_RESPONSES)
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"summary-1\"}}\n\n"
            else "data: [DONE]\n\n"
            val router = router(provider(api, transport), body = body, requests = requests,
                usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
            val image = ChatMessage(Role.USER,
                "[historical_observation id=observation-1 source_sequence=2 current_screen=false]",
                imageBase64 = "aGlzdG9yaWNhbC1pbWFnZQ==", historicalImage = true)
            router.streamChat(listOf(ChatMessage(Role.USER, "Summarize retained evidence."), image),
                hasImage = true, isolatedContext = true).toList()
            assertRequestContains(requests.single(), "aGlzdG9yaWNhbC1pbWFnZQ==")
            assertRequestContains(requests.single(), "current_screen=false")
            val buffer = Buffer()
            requests.single().body!!.writeTo(buffer)
            val wire = buffer.readUtf8()
            assertTrue(!wire.contains("current_screen=true"))
            assertTrue(!wire.contains("previous_response_id"))
        }
    }

    @Test fun toolWireExplicitlyPreservesOptionalFieldsAndTheirDeclaredConstraints() = runBlocking {
        val tool = ToolDefinition("record_note", "Record a note", linkedMapOf(
            "label" to ToolParameter("string", "Label"),
            "review" to ToolParameter("object", "Optional review", required = false, properties = linkedMapOf(
                "finding" to ToolParameter("string", "Finding", minLength = 1),
                "evidence" to ToolParameter("array", "Evidence", minItems = 1,
                    items = ToolParameter("string", "Reference", minLength = 1))
            ))
        ))
        for (transport in listOf(ModelTransport.GENERIC_CHAT_COMPLETIONS, ModelTransport.OPENAI_RESPONSES)) {
            val api = if (transport == ModelTransport.OPENAI_RESPONSES) ApiType.OPENAI_RESPONSES else ApiType.OPENAI_COMPATIBLE
            val requests = mutableListOf<Request>()
            val body = if (transport == ModelTransport.OPENAI_RESPONSES)
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"tool-schema-1\"}}\n\n"
            else "data: [DONE]\n\n"
            val router = router(provider(api, transport), body = body, requests = requests,
                usage = RecordingUsage(), diagnostics = RecordingDiagnostics())
            router.streamChat(listOf(ChatMessage(Role.USER, "Record a label.")), tools = listOf(tool)).toList()
            val buffer = Buffer()
            requests.single().body!!.writeTo(buffer)
            val definition = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("tools").jsonArray.single().jsonObject
            val function = if (transport == ModelTransport.OPENAI_RESPONSES) definition else definition.getValue("function").jsonObject
            assertEquals(JsonPrimitive(false), function["strict"])
            val schema = function.getValue("parameters").jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive("label"))), schema["required"])
            assertEquals(tool.parameters.getValue("review").toJsonSchema(), schema.getValue("properties").jsonObject["review"])
        }
    }

    @Test fun completedIsolatedToolRoundRebuildsAllCallsThenResumesIncrementalContinuation() = runBlocking {
        for (isolatedCalls in listOf(listOf("C2"), listOf("C2", "C3"))) {
            val (router, requests) = queuedResponses(listOf(
                responsesBody("R1", "C1"), responsesBody("R2", *isolatedCalls.toTypedArray()),
                responsesBody("R3", "C4"), responsesBody("R4")))
            val session = router.createSession()
            val initial = listOf(ChatMessage(Role.USER, "Inspect source"))
            try {
                session.streamChat(initial).toList()
                session.streamChat(listOf(ChatMessage(Role.USER, "Review source")), isolatedContext = true).toList()
                val complete = settledCalls(settledCalls(initial, "C1"), *isolatedCalls.toTypedArray())
                session.streamChat(complete).toList()
                session.streamChat(settledCalls(complete, "C4")).toList()
            } finally { session.close() }
            assertEquals(4, requests.size)
            assertEquals(null, requestBody(requests[1])["previous_response_id"])
            val rebuilt = requestBody(requests[2])
            assertEquals(null, rebuilt["previous_response_id"])
            assertEquals(listOf("C1") + isolatedCalls, inputCallIds(rebuilt, "function_call"))
            assertEquals(listOf("C1") + isolatedCalls, inputCallIds(rebuilt, "function_call_output"))
            val incremental = requestBody(requests[3])
            assertEquals(JsonPrimitive("R3"), incremental["previous_response_id"])
            assertEquals(emptyList<String>(), inputCallIds(incremental, "function_call"))
            assertEquals(listOf("C4"), inputCallIds(incremental, "function_call_output"))
        }
    }

    @Test fun textOnlyIsolatedSummaryPreservesTheNormalResponseChain() = runBlocking {
        val summaryBody = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Retained source facts.\"}\n\n" + responsesBody("summary")
        val (router, requests) = queuedResponses(listOf(responsesBody("R1", "C1"), summaryBody, responsesBody("R3")))
        val session = router.createSession()
        val initial = listOf(ChatMessage(Role.USER, "Inspect source"))
        try {
            session.streamChat(initial).toList()
            val summary = session.streamChat(listOf(ChatMessage(Role.USER, "Summarize")), isolatedContext = true).toList()
            assertEquals("Retained source facts.", summary.filterIsInstance<LLMStreamEvent.Content>().single().text)
            session.streamChat(settledCalls(initial, "C1")).toList()
        } finally { session.close() }
        assertEquals(3, requests.size)
        val resumed = requestBody(requests[2])
        assertEquals(JsonPrimitive("R1"), resumed["previous_response_id"])
        assertEquals(listOf("C1"), inputCallIds(resumed, "function_call_output"))
    }

    @Test fun incompleteFailedAndCancelledIsolatedToolsCannotInvalidateTheNormalChain() = runBlocking {
        for (ending in listOf("incomplete", "failed", "eof", "cancel")) {
            val partial = responseCall("partial") + when (ending) {
                "incomplete" -> "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n"
                "failed" -> "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"rejected\"}}}\n\n"
                // Keep the terminal event beyond both flowOn buffers so collection stops
                // while the provider response is still incomplete.
                "cancel" -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"pending\"}\n\n".repeat(1024) +
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"discarded\"}}\n\n"
                else -> ""
            }
            val (router, requests) = queuedResponses(listOf(responsesBody("R1", "C1"), partial, responsesBody("R3")))
            val session = router.createSession()
            val initial = listOf(ChatMessage(Role.USER, "Inspect source"))
            try {
                session.streamChat(initial).toList()
                val failure = runCatching {
                    session.streamChat(listOf(ChatMessage(Role.USER, "Review")), isolatedContext = true).collect {
                        if (ending == "cancel" && it is LLMStreamEvent.ToolCallChunk)
                            throw kotlinx.coroutines.CancellationException("caller stopped before settlement")
                    }
                }.exceptionOrNull()
                assertTrue("$ending must not complete", failure != null)
                if (ending == "cancel") assertTrue(failure is kotlinx.coroutines.CancellationException)
                // Failed collection cannot execute its partial tool call or append a result.
                session.streamChat(settledCalls(initial, "C1")).toList()
            } finally { session.close() }
            assertEquals(3, requests.size)
            val resumed = requestBody(requests[2])
            assertEquals(ending, JsonPrimitive("R1"), resumed["previous_response_id"])
            assertEquals(listOf("C1"), inputCallIds(resumed, "function_call_output"))
        }
    }

    @Test fun lateIsolatedCompletionCannotEraseAReplacementSessionsProviderChain() = runBlocking {
        val releaseIsolated = java.util.concurrent.CountDownLatch(1)
        val terminalCount = java.util.concurrent.atomic.AtomicInteger()
        val (router, requests) = queuedResponses(listOf(
            responsesBody("old-response", "old-call"), responsesBody("isolated-response", "isolated-call"),
            responsesBody("new-response", "new-call"), responsesBody("new-followup"))) {
            if (terminalCount.incrementAndGet() == 2)
                assertTrue(releaseIsolated.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val old = router.createSession()
        var replacement: ModelSession? = null
        val initial = listOf(ChatMessage(Role.USER, "Inspect source"))
        try {
            old.streamChat(initial).toList()
            assertThrows(IllegalStateException::class.java) { router.createSession() }
            old.streamChat(listOf(ChatMessage(Role.USER, "Review")), isolatedContext = true).collect { event ->
                if (event is LLMStreamEvent.ResponseMetadata) {
                    old.close()
                    val other = provider(ApiType.OPENAI_RESPONSES, ModelTransport.OPENAI_RESPONSES)
                        .copy(id = "another-provider", defaultModel = "another-model", baseUrl = "https://another.invalid/v1")
                    replacement = router.createSession(ModelStreamPlan.fromProvider(other, other.defaultModel, false))
                    replacement!!.streamChat(initial).toList()
                    releaseIsolated.countDown()
                }
            }
            replacement!!.streamChat(settledCalls(initial, "new-call")).toList()
        } finally { releaseIsolated.countDown(); old.close(); replacement?.close() }
        assertEquals(4, requests.size)
        assertEquals("another.invalid", requests[2].url.host)
        assertEquals("another.invalid", requests[3].url.host)
        assertEquals(JsonPrimitive("another-model"), requestBody(requests[3])["model"])
        assertEquals(JsonPrimitive("new-response"), requestBody(requests[3])["previous_response_id"])
        assertEquals(listOf("new-call"), inputCallIds(requestBody(requests[3]), "function_call_output"))
    }

    @Test fun lateIsolatedCompletionCannotEraseAChainRebuiltAfterCheckpoint() = runBlocking {
        val releaseIsolated = java.util.concurrent.CountDownLatch(1)
        val terminalCount = java.util.concurrent.atomic.AtomicInteger()
        val (router, requests) = queuedResponses(listOf(
            responsesBody("R1", "C1"), responsesBody("isolated", "isolated-call"),
            responsesBody("rebuilt", "C2"), responsesBody("followup"))) {
            if (terminalCount.incrementAndGet() == 2)
                assertTrue(releaseIsolated.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val session = router.createSession()
        val initial = listOf(ChatMessage(Role.USER, "Inspect source"))
        try {
            session.streamChat(initial).toList()
            session.streamChat(listOf(ChatMessage(Role.USER, "Review")), isolatedContext = true).collect { event ->
                if (event is LLMStreamEvent.ResponseMetadata) {
                    session.resetResponsesSessionAfterCheckpoint()
                    session.streamChat(initial).toList()
                    releaseIsolated.countDown()
                }
            }
            session.streamChat(settledCalls(initial, "C2")).toList()
        } finally { releaseIsolated.countDown(); session.close() }
        assertEquals(4, requests.size)
        assertEquals(null, requestBody(requests[2])["previous_response_id"])
        assertEquals(JsonPrimitive("rebuilt"), requestBody(requests[3])["previous_response_id"])
    }

    private fun settledCalls(history: List<ChatMessage>, vararg ids: String): List<ChatMessage> = history +
        ChatMessage(Role.ASSISTANT, "", toolCalls = ids.map { com.droidance.core.model.llm.models.ToolCall(it, "inspect", "{}") }) +
        ids.map { ChatMessage(Role.TOOL, "settled", toolCallId = it) }

    private fun responseCall(id: String, index: Int = 0): String =
        "data: {\"type\":\"response.output_item.done\",\"output_index\":$index,\"item\":{\"type\":\"function_call\",\"call_id\":\"$id\",\"name\":\"inspect\",\"arguments\":\"{}\"}}\n\n"

    private fun responsesBody(id: String, vararg calls: String): String =
        calls.mapIndexed { index, call -> responseCall(call, index) }.joinToString("") +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"$id\"}}\n\n"

    private fun requestBody(request: Request): kotlinx.serialization.json.JsonObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    @Test fun unretainedResponseUsesFullLocalHistoryWithoutAnUnusableContinuationProbe() = runBlocking {
        val unretained = responseCall("C1") +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"not-stored\",\"store\":false,\"caching\":{\"type\":\"disabled\"}}}\n\n"
        val (router, requests) = queuedResponses(listOf(unretained, responsesBody("R2", "C2")))
        val session = router.createSession()
        val initial = listOf(ChatMessage(Role.USER, "Inspect source"))
        try {
            session.streamChat(initial).toList()
            session.streamChat(settledCalls(initial, "C1")).toList()
        } finally { session.close() }
        assertEquals(2, requests.size)
        val rebuilt = requestBody(requests[1])
        assertNull(rebuilt["previous_response_id"])
        assertEquals(listOf("C1"), inputCallIds(rebuilt, "function_call"))
        assertEquals(listOf("C1"), inputCallIds(rebuilt, "function_call_output"))
        assertTrue(rebuilt.getValue("input").toString().contains("Inspect source"))
    }

    @Test fun storedOrUnspecifiedResponsesContinueWithOnlyTheNewToolOutputs() = runBlocking {
        for (store in listOf("", ",\"store\":true")) {
            val retained = responseCall("C1") +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"retained\"$store}}\n\n"
            val (router, requests) = queuedResponses(listOf(retained, responsesBody("R2")))
            val session = router.createSession()
            val initial = listOf(ChatMessage(Role.USER, "Inspect source"))
            try {
                session.streamChat(initial).toList()
                session.streamChat(settledCalls(initial, "C1")).toList()
            } finally { session.close() }
            assertEquals(2, requests.size)
            val next = requestBody(requests[1])
            assertEquals(JsonPrimitive("retained"), next["previous_response_id"])
            assertEquals(emptyList<String>(), inputCallIds(next, "function_call"))
            assertEquals(listOf("C1"), inputCallIds(next, "function_call_output"))
        }
    }

    @Test fun unretainedHistoryIsRememberedAcrossTurnsButNotAcrossConfigurationScopes() = runBlocking {
        val requests = mutableListOf<Request>()
        var missing = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val request = requestBody(chain.request())
            val lost = request["previous_response_id"] != null
            if (lost) missing++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (lost) 400 else 200).message(if (lost) "Bad Request" else "OK")
                .body((if (lost) """{"error":{"code":"previous_response_not_found","message":"previous response not found"}}"""
                    else responsesBody("response-${requests.size}")).toResponseBody(
                        (if (lost) "application/json" else "text/event-stream").toMediaType())).build()
        }.build()
        val config = provider(ApiType.OPENAI_RESPONSES, ModelTransport.OPENAI_RESPONSES)
        val router = LLMRouter(client, StaticConfiguration(config), RecordingUsage(), RecordingDiagnostics())
        suspend fun turn(plan: ModelStreamPlan? = null) {
            val session = router.createSession(plan)
            try {
                session.streamChat(listOf(ChatMessage(Role.USER, "first"))).toList()
                session.streamChat(listOf(ChatMessage(Role.USER, "first"), ChatMessage(Role.ASSISTANT, "observed"),
                    ChatMessage(Role.USER, "next"))).toList()
            } finally { session.close() }
        }
        turn(); assertEquals(1, missing)
        turn(); assertEquals(1, missing)
        assertNull(requestBody(requests.last())["previous_response_id"])
        turn(ModelStreamPlan.fromProvider(config.copy(apiKey = "new-auth"), config.defaultModel, false))
        assertEquals(2, missing)
    }

    private fun inputCallIds(request: kotlinx.serialization.json.JsonObject, type: String): List<String> =
        request.getValue("input").jsonArray.map { it.jsonObject }.filter { it["type"] == JsonPrimitive(type) }
            .map { (it.getValue("call_id") as JsonPrimitive).content }

    private fun queuedResponses(bodies: List<String>, onTerminal: () -> Unit = {}): Pair<LLMRouter, MutableList<Request>> {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = bodies[requests.size]
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return LLMRouter(client, StaticConfiguration(provider(ApiType.OPENAI_RESPONSES, ModelTransport.OPENAI_RESPONSES)),
            RecordingUsage(), RecordingDiagnostics { tag -> if (tag == "ResponsesTerminal") onTerminal() }) to requests
    }

    private fun router(
        provider: ProviderConfig,
        body: String,
        statusCode: Int = 200,
        contentType: okhttp3.MediaType = "text/event-stream".toMediaType(),
        requests: MutableList<Request>,
        usage: RecordingUsage,
        diagnostics: RecordingDiagnostics,
        configuration: TransportConfiguration = StaticConfiguration(provider)
    ): LLMRouter = LLMRouter(
        httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode in 200..299) "OK" else "Bad Request")
                    .body(body.toResponseBody(contentType))
                    .build()
            }
            .build(),
        configManager = configuration,
        tokenUsageRepository = usage,
        diagnostics = diagnostics
    )

    private fun provider(
        apiType: ApiType,
        transport: ModelTransport
    ): ProviderConfig = ProviderConfig(
        id = "provider",
        name = "provider",
        apiType = apiType,
        transportKind = transport,
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        defaultModel = "test-model"
    )

    private fun assertRequestContains(request: Request, expected: String) {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        assertTrue(buffer.readUtf8().contains(expected))
    }

    private class StaticConfiguration(
        private val provider: ProviderConfig,
        private val nextModel: String? = null
    ) : TransportConfiguration {
        var fallbackAttempts = 0
        override fun reasoningCapability(config: ProviderConfig, model: String) =
                com.droidance.core.model.llm.models.ReasoningCapability(com.droidance.core.model.llm.models.ReasoningEffort.entries.filter { it.wireValue != null })
            override fun recordReasoningRejected(config: ProviderConfig, model: String) = Unit
            override fun resolveProvider(): ProviderConfig = provider
        override fun getCurrentModel(config: ProviderConfig): String = config.defaultModel
        override fun advanceToNextModel(config: ProviderConfig): String? { fallbackAttempts++; return nextModel }
        override fun resetModelFallback(configId: String) = Unit
        override fun imageDetailCandidates(config: ProviderConfig, model: String) =
            listOf(ImageDetailOption.HIGH)
        override fun imageDetailCapabilityStatus(config: ProviderConfig, model: String) =
            ImageDetailCapabilityStatus(ImageDetailOption.HIGH, 1L, null, false)
        override fun recordImageDetailAccepted(
            config: ProviderConfig, model: String, detail: ImageDetailOption, returnedModel: String?
        ) = Unit
        override fun recordImageDetailRejected(
            config: ProviderConfig, model: String, detail: ImageDetailOption
        ) = Unit
    }

    private data class RequestUsage(
        val provider: String,
        val model: String,
        val promptTokens: Long,
        val completionTokens: Long,
        val cachedInputTokens: Long,
        val cacheFieldsReported: Boolean
    )

    private class RecordingUsage : TransportUsageRecorder {
        val records = mutableListOf<RequestUsage>()

        override fun recordRequest(
            provider: String,
            model: String,
            promptTokens: Long,
            completionTokens: Long,
            cachedInputTokens: Long,
            cacheFieldsReported: Boolean
        ) {
            records += RequestUsage(
                provider,
                model,
                promptTokens,
                completionTokens,
                cachedInputTokens,
                cacheFieldsReported
            )
        }
    }

    private class RecordingDiagnostics(private val onDebug: (String) -> Unit = {}) : TransportDiagnostics {
        val requests = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val scopes = mutableListOf<ModelDiagnosticScope?>()
        val lines = mutableListOf<String>()
        val statuses = mutableListOf<Int>()
        val failures = mutableListOf<Throwable?>()
        var beforeFinish: (Throwable?) -> Unit = {}
        var started = 0
        val tokenUsages = mutableListOf<Pair<Int, Int>>()

        override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String,
            request: kotlinx.serialization.json.JsonObject): TransportExchangeTrace {
            requests += request
            scopes += scope
            return object : TransportExchangeTrace {
                override fun response(status: Int, requestId: String?, retryAfter: String?) { statuses += status }
                override fun timing(name: String, elapsedMs: Long) = Unit
                override fun line(value: String) { lines += value }
                override fun finish(error: Throwable?) { beforeFinish(error); failures += error }
            }
        }
        override fun requestStarted() {
            started += 1
        }

        override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) {
            tokenUsages += inputTokens to outputTokens
        }

        override fun warning(tag: String, message: String) = Unit
        override fun debug(tag: String, message: String) = onDebug(tag)
    }
    @Test fun cancellingBeforeResponseHeadersProducesCancellationInBothTransports() = runBlocking {
        for ((api, transport) in listOf(
            ApiType.OPENAI_COMPATIBLE to ModelTransport.GENERIC_CHAT_COMPLETIONS,
            ApiType.OPENAI_RESPONSES to ModelTransport.OPENAI_RESPONSES)) {
            val received = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val failure = kotlinx.coroutines.CompletableDeferred<Throwable>()
            val requests = java.util.concurrent.atomic.AtomicInteger()
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1") { exchange ->
                try {
                    requests.incrementAndGet(); exchange.requestBody.readBytes(); received.countDown()
                    release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            server.start()
            try {
                val config = provider(api, transport).copy(baseUrl = "http://127.0.0.1:${server.address.port}/v1")
                val router = LLMRouter(OkHttpClient(), StaticConfiguration(config), RecordingUsage(), RecordingDiagnostics())
                val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { router.streamChat(listOf(ChatMessage(Role.USER, "test")), requestTimeoutMs = 0).toList() }
                    catch (error: Throwable) { failure.complete(error) }
                }
                try {
                    assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        received.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    })
                    kotlinx.coroutines.withTimeout(1500) {
                        job.cancel(); job.join()
                        assertTrue(failure.await().toString(), failure.await() is kotlinx.coroutines.CancellationException)
                    }
                    assertEquals(1, requests.get())
                } finally { job.cancel(); release.countDown() }
            } finally { server.stop(0) }
        }
    }

}
