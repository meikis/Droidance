// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.context.ContextCompactionExecutionPolicy
import com.droidance.core.agent.context.ContextCompactionUnavailable
import com.droidance.core.agent.context.ContextPolicySnapshot
import com.droidance.core.agent.context.ContextTokenEstimatorCoverage
import com.droidance.core.agent.context.ContextTokenEstimatorIdentity
import com.droidance.core.agent.context.DurableContextProjection
import com.droidance.core.agent.ports.RuleSource
import com.droidance.core.agent.debug.DebugLogAppender
import com.droidance.core.model.llm.LLMRouter
import com.droidance.core.model.llm.ModelDiagnosticScope
import com.droidance.core.model.llm.TransportConfiguration
import com.droidance.core.model.llm.TransportDiagnostics
import com.droidance.core.model.llm.TransportExchangeTrace
import com.droidance.core.model.llm.TransportUsageRecorder
import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ImageDetailCapabilityStatus
import com.droidance.core.model.llm.models.ImageDetailOption
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ReasoningCapability
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.Buffer
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the production HTTP/SSE adapter and compactor, with only the HTTP peer replaced. */
class TaskContextCompactorTransportTest {
    @Test fun summaryForkKeepsTheParentProviderCacheSession() = runBlocking {
        val fixture = Fixture(listOf(content("complete summary") + "data:[DONE]\n\n"))
        assertEquals("complete summary", withContext(ModelDiagnosticScope("parent-thread", "turn", "summary")) { fixture.compact() })
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("parent-thread".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("droidance-$hash", fixture.cacheSession)
        assertTrue(fixture.request!!.containsKey("cache_control"))
    }
    @Test fun partialChatAtEofCannotProduceASummaryForACheckpoint() = runBlocking {
        val fixture = Fixture(listOf(content("partial summary")))
        val failure = runCatching { fixture.compact() }.exceptionOrNull()
        assertTrue("Partial HTTP content must never become a successful summary", failure is ContextCompactionUnavailable)
        assertEquals("COMPACTION_REQUEST_IO", (failure as ContextCompactionUnavailable).diagnostic)
        assertEquals(1, fixture.requests)
    }

    @Test fun retryDiscardsUnterminatedPartialTextBeforeSummarizingAgain() = runBlocking {
        val fixture = Fixture(listOf(content("discarded partial"), content("complete summary") + "data:[DONE]\n\n"), retries = 1)
        assertEquals("complete summary", fixture.compact())
        assertEquals(2, fixture.requests)
    }

    @Test fun explicitChatTerminationsStillProduceSummaries() = runBlocking {
        for (terminal in listOf("data:[DONE]\n\n", "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")) {
            val fixture = Fixture(listOf(content("complete summary") + terminal))
            assertEquals("complete summary", fixture.compact())
            assertEquals(1, fixture.requests)
        }
    }

    @Test fun noSpaceLengthTerminationCannotHideBehindDone() = runBlocking {
        val fixture = Fixture(listOf(content("truncated summary") +
            "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata:[DONE]\n\n"))
        val failure = runCatching { fixture.compact() }.exceptionOrNull()
        assertTrue(failure is ContextCompactionUnavailable)
        assertEquals("COMPACTION_OUTPUT_LIMIT", (failure as ContextCompactionUnavailable).diagnostic)
    }

    private fun content(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n"

    private class Fixture(bodies: List<String>, retries: Int = 0) {
        var requests = 0
        var cacheSession: String? = null
        var request: JsonObject? = null
        private val config = ProviderConfig(id = "test", name = "test", apiType = ApiType.OPENAI_COMPATIBLE,
            transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS, baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "synthetic-test-key", defaultModel = "anthropic/example")
        private val configuration = object : TransportConfiguration {
            override fun reasoningCapability(config: ProviderConfig, model: String) = ReasoningCapability()
            override fun recordReasoningRejected(config: ProviderConfig, model: String) = Unit
            override fun resolveProvider() = config
            override fun getCurrentModel(config: ProviderConfig) = config.defaultModel
            override fun advanceToNextModel(config: ProviderConfig): String? = null
            override fun resetModelFallback(configId: String) = Unit
            override fun imageDetailCandidates(config: ProviderConfig, model: String) = listOf(ImageDetailOption.SERVICE_DEFAULT)
            override fun imageDetailCapabilityStatus(config: ProviderConfig, model: String) =
                ImageDetailCapabilityStatus(ImageDetailOption.HIGH, 1L, null, false)
            override fun recordImageDetailAccepted(config: ProviderConfig, model: String, detail: ImageDetailOption, returnedModel: String?) = Unit
            override fun recordImageDetailRejected(config: ProviderConfig, model: String, detail: ImageDetailOption) = Unit
        }
        private val client = LLMRouter(
            OkHttpClient.Builder().addInterceptor { chain ->
                cacheSession = chain.request().header("x-session-id")
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                request = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                val body = bodies[minOf(requests++, bodies.lastIndex)]
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("text/event-stream".toMediaType())).build()
            }.build(), configuration,
            object : TransportUsageRecorder {
                override fun recordRequest(provider: String, model: String, promptTokens: Long, completionTokens: Long,
                    cachedInputTokens: Long, cacheFieldsReported: Boolean) = Unit
            },
            object : TransportDiagnostics {
                override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String, request: JsonObject) = TransportExchangeTrace.None
                override fun requestStarted() = Unit
                override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) = Unit
                override fun warning(tag: String, message: String) = Unit
                override fun debug(tag: String, message: String) = Unit
            }
        )
        private val compactor = TaskContextCompactor(client, RuleSource { _, _ -> "Summarize retained evidence." }, InMemoryBlobStore(),
            object : DebugLogAppender {
                override fun appendEvent(event: String) = Unit
                override fun appendPersistentEvent(stream: String, event: String) = Unit
            })
        private val policy = ContextPolicySnapshot(128000, 32768, 8000, 9, 10, 3,
            ContextTokenEstimatorIdentity("generic_chat_completions", "adapter-v1", "model-v1", "serializer-v1",
                "tokenizer-v1", "image-v1", ContextTokenEstimatorCoverage(true, true, true, true, true, true, true)))
        private val execution = ContextCompactionExecutionPolicy(0, 0, retries, 2048, 4096, 24000, 1, 4096)
        suspend fun compact() = compactor.compact(DurableContextProjection(emptyList(), emptyList()), policy, "task", execution,
            "Frozen operator rules", emptyList(), listOf(ChatMessage(Role.USER, "task")))
    }
}
