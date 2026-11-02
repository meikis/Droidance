// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.*
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ReasoningFallbackTest {
    @Test fun fixedProviderPreferenceOverridesCallerAndUnknownSupportIsReportedAsDefault() = runBlocking {
        for (transport in listOf(ModelTransport.GENERIC_CHAT_COMPLETIONS, ModelTransport.OPENAI_RESPONSES)) {
            val requests = mutableListOf<JsonObject>()
            val fixed = config(transport).copy(reasoningPreference = ReasoningEffort.HIGH)
            val configuration = Configuration(fixed, ReasoningCapability(listOf(ReasoningEffort.HIGH)))
            val router = router(configuration, requests) {
                200 to if (transport == ModelTransport.OPENAI_RESPONSES)
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}\n\n"
                else "data: [DONE]\n\n"
            }
            assertEquals(ReasoningEffort.HIGH, router.planStreamChat(emptyList(), false).reasoningPreference)
            val first = router.streamChat(listOf(ChatMessage(Role.USER, "start")), reasoningEffort = ReasoningEffort.LOW)
                .toList().filterIsInstance<LLMStreamEvent.ReasoningConfiguration>().single()
            assertEquals(ReasoningEffort.HIGH, first.requested)
            assertEquals(ReasoningEffort.HIGH, first.sent)
            configuration.capability = ReasoningCapability()
            val fallback = router.streamChat(listOf(ChatMessage(Role.USER, "continue")))
                .toList().filterIsInstance<LLMStreamEvent.ReasoningConfiguration>().single()
            assertEquals(ReasoningEffort.DEFAULT, fallback.sent)
            assertEquals("unsupported_or_unknown_capability", fallback.fallbackReason)
            assertNull(requests.last()["reasoning_effort"])
            assertNull((requests.last()["reasoning"] as? JsonObject)?.get("effort"))
        }
    }

    private class Configuration(val config: ProviderConfig, var capability: ReasoningCapability) : TransportConfiguration {
        var rejected = 0
        var modelFallbacks = 0
        override fun reasoningCapability(config: ProviderConfig, model: String) = capability
        override fun recordReasoningRejected(config: ProviderConfig, model: String) { rejected++; capability = ReasoningCapability(forceDefault = true) }
        override fun resolveProvider() = config
        override fun getCurrentModel(config: ProviderConfig) = config.defaultModel
        override fun advanceToNextModel(config: ProviderConfig): String? { modelFallbacks++; return null }
        override fun resetModelFallback(configId: String) = Unit
        override fun imageDetailCandidates(config: ProviderConfig, model: String) = listOf(ImageDetailOption.HIGH)
        override fun imageDetailCapabilityStatus(config: ProviderConfig, model: String) = ImageDetailCapabilityStatus(ImageDetailOption.HIGH, 1, null, false)
        override fun recordImageDetailAccepted(config: ProviderConfig, model: String, detail: ImageDetailOption, returnedModel: String?) = Unit
        override fun recordImageDetailRejected(config: ProviderConfig, model: String, detail: ImageDetailOption) = Unit
    }

    private fun router(configuration: Configuration, requests: MutableList<JsonObject>, reply: (Int) -> Pair<Int,String>): LLMRouter {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val (code, body) = reply(requests.size)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return LLMRouter(http, configuration, object : TransportUsageRecorder {
            override fun recordRequest(usage: com.droidance.core.model.llm.ModelRequestUsage) = Unit
        }, object : TransportDiagnostics {
            override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String, request: JsonObject) = TransportExchangeTrace.None
            override fun requestStarted() = Unit
            override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) = Unit
            override fun warning(tag: String, message: String) = Unit
            override fun debug(tag: String, message: String) = Unit
        })
    }

    private fun config(transport: ModelTransport = ModelTransport.GENERIC_CHAT_COMPLETIONS) = ProviderConfig(
        "p", "p", if (transport == ModelTransport.OPENAI_RESPONSES) ApiType.OPENAI_RESPONSES else ApiType.OPENAI_COMPATIBLE,
        transport, baseUrl = "https://provider.invalid/v1", defaultModel = "selected"
    )

    @Test fun modelDefaultsOmitEffortInInitialAndExecutionRequestsForBothTransports() = runBlocking {
        for (transport in ModelTransport.entries) {
            val requests = mutableListOf<JsonObject>()
            val configuration = Configuration(config(transport),
                ReasoningCapability(listOf(ReasoningEffort.HIGH)))
            val router = router(configuration, requests) {
                200 to if (transport == ModelTransport.OPENAI_RESPONSES)
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}\n\n"
                    else "data: [DONE]\n\n"
            }
            val plan = router.planStreamChat(emptyList(), false)
            assertEquals(ReasoningEffort.DEFAULT, plan.reasoningEffort)
            router.streamChat(listOf(ChatMessage(Role.USER, "start")), reasoningEffort = plan.reasoningEffort).toList()
            router.streamChat(listOf(ChatMessage(Role.USER, "continue"))).toList()
            assertEquals(2, requests.size)
            requests.forEach { request ->
                assertNull(request["reasoning_effort"])
                assertNull((request["reasoning"] as? JsonObject)?.get("effort"))
                assertNull(request["custom_parameter"])
            }
            assertEquals(0, configuration.rejected)
        }
    }

    @Test fun removedCustomBodyCannotOverrideRequestsFromStoredConfigurations() = runBlocking {
        for (transport in ModelTransport.entries) {
            val saved = Json.encodeToJsonElement(config(transport)).jsonObject.toMutableMap().apply {
                put("reasoningEffort", JsonPrimitive("HIGH"))
                put("extraBodyJson", JsonPrimitive("""{"model":"injected-model","stream":false,"enable_thinking":false}"""))
            }
            val restored = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderConfig>(JsonObject(saved).toString())
            val requests = mutableListOf<JsonObject>()
            val configuration = Configuration(restored, ReasoningCapability(listOf(ReasoningEffort.HIGH)))
            router(configuration, requests) {
                200 to if (transport == ModelTransport.OPENAI_RESPONSES)
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}\n\n"
                    else "data: [DONE]\n\n"
            }.streamChat(listOf(ChatMessage(Role.USER, "start"))).toList()
            val request = requests.single()
            assertEquals("selected", request["model"]!!.jsonPrimitive.content)
            assertEquals(JsonPrimitive(true), request["stream"])
            assertNull(request["enable_thinking"])
            assertNull(request["reasoning"])
            assertNull(request["reasoning_effort"])
        }
    }

    @Test fun bothTransportsRetryOnceWithDefaultAndKeepTheSelectedModel() = runBlocking {
        for (transport in listOf(ModelTransport.GENERIC_CHAT_COMPLETIONS, ModelTransport.OPENAI_RESPONSES)) {
            val requests = mutableListOf<JsonObject>()
            val configuration = Configuration(config(transport), ReasoningCapability(listOf(ReasoningEffort.HIGH)))
            val router = router(configuration, requests) { attempt ->
                if (attempt == 1) 400 to """{"error":{"param":"reasoning.effort","code":"unsupported_value"}}"""
                else 200 to if (transport == ModelTransport.OPENAI_RESPONSES)
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}\n\n"
                    else "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            }
            val result = router.streamChat(listOf(ChatMessage(Role.USER, "hi")), reasoningEffort = ReasoningEffort.HIGH).toList()
            assertTrue(result.any { it is LLMStreamEvent.Content && it.text == "ok" })
            assertEquals(2, requests.size)
            assertEquals(1, configuration.rejected)
            assertEquals(0, configuration.modelFallbacks)
            assertTrue(requests.all { it["model"]!!.jsonPrimitive.content == "selected" })
            assertNull(requests[1]["reasoning_effort"])
            assertNull(requests[1]["reasoning"])
            // Subsequent rounds use the cached rejection without another failed effort request.
            router.streamChat(listOf(ChatMessage(Role.USER, "again")), reasoningEffort = ReasoningEffort.HIGH).toList()
            assertEquals(3, requests.size)
            assertNull(requests.last()["reasoning"])
            assertNull(requests.last()["reasoning_effort"])
        }
    }

    @Test fun failedDefaultRetryIsBounded() {
        val requests = mutableListOf<JsonObject>()
        val configuration = Configuration(config(), ReasoningCapability(listOf(ReasoningEffort.HIGH)))
        val router = router(configuration, requests) { 400 to """{"error":{"param":"reasoning_effort"}}""" }
        assertThrows(ProviderHttpException::class.java) { runBlocking { router.streamChat(listOf(ChatMessage(Role.USER,"hi")), reasoningEffort = ReasoningEffort.HIGH).toList() } }
        assertEquals(2, requests.size)
        assertEquals(1, configuration.rejected)
    }

    @Test fun unsupportedSelectionIsOmittedBeforeTheFirstRequest() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val configuration = Configuration(config(), ReasoningCapability(listOf(ReasoningEffort.LOW)))
        router(configuration, requests) { 200 to "data: [DONE]\n\n" }
            .streamChat(listOf(ChatMessage(Role.USER,"hi")), reasoningEffort = ReasoningEffort.MAX).toList()
        assertEquals(1, requests.size)
        assertNull(requests.single()["reasoning_effort"])
        assertNull(requests.single()["reasoning"])
    }

    @Test fun unrelatedClientErrorsAreNotRetriedAsReasoningFailures() {
        val requests = mutableListOf<JsonObject>()
        val configuration = Configuration(config(), ReasoningCapability(listOf(ReasoningEffort.HIGH)))
        val router = router(configuration, requests) { 422 to """{"error":{"param":"tools","message":"invalid schema"}}""" }
        assertThrows(ProviderHttpException::class.java) { runBlocking { router.streamChat(listOf(ChatMessage(Role.USER,"hi")), reasoningEffort = ReasoningEffort.HIGH).toList() } }
        assertEquals(1, requests.size)
        assertEquals(0, configuration.rejected)
    }

    @Test fun streamedContentIsNeverReplayedAfterReasoningFailure() {
        val requests = mutableListOf<JsonObject>()
        val configuration = Configuration(config(), ReasoningCapability(listOf(ReasoningEffort.HIGH)))
        val router = router(configuration, requests) { 200 to "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"error\":{\"param\":\"reasoning_effort\",\"code\":\"unsupported_value\"}}\n\n" }
        assertThrows(Exception::class.java) { runBlocking { router.streamChat(listOf(ChatMessage(Role.USER,"hi")), reasoningEffort = ReasoningEffort.HIGH).toList() } }
        assertEquals(1, requests.size)
        assertEquals(0, configuration.rejected)
    }
}
