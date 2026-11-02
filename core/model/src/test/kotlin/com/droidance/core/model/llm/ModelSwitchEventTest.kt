// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.ImageDetailCapabilityStatus
import com.droidance.core.model.llm.models.ImageDetailOption
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ReasoningCapability
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.session.ModelTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSwitchEventTest {
    @Test
    fun fallbackPublishesTypedEventToEachSubscriber() = runBlocking {
        withTimeout(5_000) {
            val requests = AtomicInteger()
            val router = router(requests)
            val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { router.modelSwitches.first() }
            val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { router.modelSwitches.first() }
            router.streamChat(listOf(ChatMessage(Role.USER, "hello"))).toList()
            assertEquals(first.await(), second.await())
            assertEquals("first-model", first.await().fromModel)
            assertEquals("next-model", first.await().toModel)
            assertEquals(2, requests.get())
        }
    }

    @Test
    fun fallbackDoesNotRequireAUiSubscriber() = runBlocking {
        withTimeout(5_000) {
            val requests = AtomicInteger()
            router(requests).streamChat(listOf(ChatMessage(Role.USER, "hello"))).toList()
            assertEquals(2, requests.get())
        }
    }

    private fun router(requests: AtomicInteger): LLMRouter {
        val provider = ProviderConfig(
            id = "test", name = "test", apiType = ApiType.OPENAI_COMPATIBLE,
            transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
            baseUrl = "https://example.invalid/v1", apiKey = "test-key", defaultModel = "first-model"
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val first = requests.incrementAndGet() == 1
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (first) 429 else 200).message(if (first) "Rate limited" else "OK")
                .body((if (first) "{}" else "data: [DONE]\n\n")
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return LLMRouter(client, object : TransportConfiguration {
            override fun resolveProvider() = provider
            override fun getCurrentModel(config: ProviderConfig) = config.defaultModel
            override fun advanceToNextModel(config: ProviderConfig) = "next-model"
            override fun resetModelFallback(configId: String) = Unit
            override fun reasoningCapability(config: ProviderConfig, model: String) =
                ReasoningCapability(ReasoningEffort.entries.filter { it.wireValue != null })
            override fun recordReasoningRejected(config: ProviderConfig, model: String) = Unit
            override fun imageDetailCandidates(config: ProviderConfig, model: String) =
                listOf(ImageDetailOption.HIGH)
            override fun imageDetailCapabilityStatus(config: ProviderConfig, model: String) =
                ImageDetailCapabilityStatus(ImageDetailOption.HIGH, 1L, null, false)
            override fun recordImageDetailAccepted(config: ProviderConfig, model: String,
                detail: ImageDetailOption, returnedModel: String?) = Unit
            override fun recordImageDetailRejected(config: ProviderConfig, model: String,
                detail: ImageDetailOption) = Unit
        }, object : TransportUsageRecorder {
            override fun recordRequest(usage: com.droidance.core.model.llm.ModelRequestUsage) = Unit
        }, object : TransportDiagnostics {
            override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String,
                request: kotlinx.serialization.json.JsonObject) = TransportExchangeTrace.None
            override fun requestStarted() = Unit
            override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) = Unit
            override fun warning(tag: String, message: String) = Unit
            override fun debug(tag: String, message: String) = Unit
        })
    }
}
