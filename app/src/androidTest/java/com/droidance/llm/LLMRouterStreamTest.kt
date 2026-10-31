// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.llm

import com.droidance.core.model.llm.*

import com.droidance.llm.*

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.config.TokenUsageRepository
import com.droidance.settings.ProviderConfigManager
import com.droidance.core.device.debug.DiagnosticsCollector
import com.droidance.core.agent.debug.TaskLatencyTracker
import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.core.model.llm.ModelStreamPlan
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LLMRouterStreamTest {
    private lateinit var context: Context
    private lateinit var baseContext: Context
    private val preferenceNames = mutableSetOf<String>()

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
        val prefix = "router-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(baseContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = prefix + name
                preferenceNames += isolatedName
                return baseContext.getSharedPreferences(isolatedName, mode)
            }
        }
    }

    @After
    fun tearDown() {
        preferenceNames.forEach(baseContext::deleteSharedPreferences)
    }

    @Test
    fun ioFailureAfterToolChunkDoesNotRetryOrDuplicateOutput() = runBlocking {
        var requests = 0
        val router = router {
            requests++
            body(toolChunk + if (requests > 1) completed else "", failAtEnd = requests == 1)
        }
        val events = mutableListOf<LLMStreamEvent>()

        assertThrows(IOException::class.java) {
            runBlocking { router.streamChat(messages, requestPlan = plan).collect { events += it } }
        }
        assertEquals(1, requests)
        assertEquals(1, events.size)
        assertEquals("call-test", (events.single() as LLMStreamEvent.ToolCallChunk).id)
    }

    @Test
    fun ioFailureBeforeOutputRetainsOneBoundedRetry() = runBlocking {
        var requests = 0
        val router = router {
            requests++
            if (requests == 1) body("", failAtEnd = true) else body(toolChunk + completed)
        }
        val events = mutableListOf<LLMStreamEvent>()

        router.streamChat(messages, requestPlan = plan).collect { events += it }

        assertEquals(2, requests)
        assertEquals(1, events.filterIsInstance<LLMStreamEvent.ToolCallChunk>().size)
    }

    @Test
    fun incompleteStreamThrowsAndAccountsUsageOnce() = runBlocking {
        val usage = TokenUsageRepository(context)
        val diagnostics = DiagnosticsCollector(context)
        var requests = 0
        val router = router(usage, diagnostics) {
            requests++
            body("data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":25,\"output_tokens\":1024}}}\n\n")
        }

        assertThrows(OpenAIResponsesIncompleteException::class.java) {
            runBlocking { router.streamChat(messages, requestPlan = plan).collect {} }
        }
        assertEquals(1, requests)
        assertEquals(1L, usage.records.value.single().calls)
        assertEquals(1024L, usage.records.value.single().completionTokens)
        assertEquals(1024, diagnostics.tokenUsageSnapshot().completionTokens)
    }

    @Test
    fun emptyDataEventDoesNotEndAValidResponse() = runBlocking {
        val router = router { body("data: \n\n" + toolChunk + completed) }
        val events = mutableListOf<LLMStreamEvent>()

        router.streamChat(messages, requestPlan = plan).collect { events += it }

        assertEquals(1, events.filterIsInstance<LLMStreamEvent.ToolCallChunk>().size)
    }

    @Test
    fun eofAfterToolChunkCannotReturnASuccessfulPartialResponse() = runBlocking {
        var requests = 0
        val router = router { requests++; body(toolChunk) }

        assertThrows(OpenAIResponsesUnterminatedException::class.java) {
            runBlocking { router.streamChat(messages, requestPlan = plan).collect {} }
        }
        assertEquals(1, requests)
    }

    private fun router(
        usage: TokenUsageRepository = TokenUsageRepository(context),
        diagnostics: DiagnosticsCollector = DiagnosticsCollector(context),
        responseBody: () -> ResponseBody
    ): LLMRouter {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody()).build()
        }.build()
        return com.droidance.di.ModelTransportModule.provideRouter(
            client, ProviderConfigManager(context), com.droidance.settings.ReasoningCapabilityRepository(context, client),
            com.droidance.settings.ProviderRequestFieldsRepository(context), usage, TaskLatencyTracker(), diagnostics
        )
    }

    private fun body(text: String, failAtEnd: Boolean = false): ResponseBody = object : ResponseBody() {
        private val stream = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read == -1L && failAtEnd) throw IOException("Injected stream disconnect")
                return read
            }
        }.buffer()
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = stream
    }

    private val messages = listOf(ChatMessage(role = Role.USER, content = "Test stream completion."))
    private val plan = ModelStreamPlan.fromProvider(
        config = ProviderConfig(
            id = "router-test", name = "Router test", apiType = ApiType.OPENAI_RESPONSES,
            transportKind = ModelTransport.OPENAI_RESPONSES, baseUrl = "https://example.invalid",
            models = listOf("test-model"), defaultModel = "test-model"
        ),
        initialModel = "test-model", imageIncluded = false
    )
    private val toolChunk = "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call-test\",\"name\":\"android_read\"}}\n\n"
    private val completed = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"response-test\"}}\n\n"
}
