// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.*
import com.droidance.core.model.llm.session.ModelTransport
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class RouterConfigurationTest {
    @Test
    fun configurationIsFrozenWithinTurnAndRefreshedForIdenticalNextRequest() {
        var selected = config("first")
        var reads = 0
        val router = LLMRouter(OkHttpClient(), object : TransportConfiguration {
            override fun reasoningCapability(config: ProviderConfig, model: String) =
                com.droidance.core.model.llm.models.ReasoningCapability(com.droidance.core.model.llm.models.ReasoningEffort.entries.filter { it.wireValue != null })
            override fun recordReasoningRejected(config: ProviderConfig, model: String) = Unit
            override fun resolveProvider(): ProviderConfig { reads++; return selected }
            override fun getCurrentModel(config: ProviderConfig) = config.defaultModel
            override fun advanceToNextModel(config: ProviderConfig): String? = null
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
        }, object : TransportUsageRecorder {
            override fun recordRequest(provider: String, model: String, promptTokens: Long,
                completionTokens: Long, cachedInputTokens: Long, cacheFieldsReported: Boolean) = Unit
        }, object : TransportDiagnostics {
            override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String,
            request: kotlinx.serialization.json.JsonObject) = TransportExchangeTrace.None
        override fun requestStarted() = Unit
            override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) = Unit
            override fun warning(tag: String, message: String) = Unit
            override fun debug(tag: String, message: String) = Unit
        })
        val messages = listOf(ChatMessage(Role.USER, "same request"))
        router.beginTurn()
        assertEquals("first", router.planStreamChat(messages, false).providerId)
        selected = config("second")
        assertEquals("first", router.planStreamChat(messages, false).providerId)
        assertEquals(1, reads)
        router.endTurn()
        router.beginTurn()
        assertEquals("second", router.planStreamChat(messages, false).providerId)
        assertEquals(2, reads)
        router.endTurn()
    }

    private fun config(id: String) = ProviderConfig(
        id = id, name = id, apiType = ApiType.OPENAI_RESPONSES,
        transportKind = ModelTransport.OPENAI_RESPONSES,
        baseUrl = "https://example.invalid", defaultModel = "model"
    )
}
