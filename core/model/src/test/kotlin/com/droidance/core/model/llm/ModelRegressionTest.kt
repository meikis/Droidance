// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.ImageDetailOption
import com.droidance.core.model.llm.models.ImageDetailCapabilityStatus
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ModelRegressionTest {
    @Test
    fun frozenPlanRetainsConfiguredContextWindowFor32kAndLargeWindow() {
        for (window in listOf(32_000, 1_000_000)) {
            var selected = provider(contextWindowTokens = window)
            val router = router(object : TransportConfiguration {
                override fun reasoningCapability(config: ProviderConfig, model: String) =
                com.droidance.core.model.llm.models.ReasoningCapability(com.droidance.core.model.llm.models.ReasoningEffort.entries.filter { it.wireValue != null })
            override fun recordReasoningRejected(config: ProviderConfig, model: String) = Unit
            override fun resolveProvider(): ProviderConfig = selected
                override fun getCurrentModel(config: ProviderConfig): String = config.defaultModel
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
            })
            val session = router.createSession()
            try {
                val messages = listOf(ChatMessage(Role.USER, "freeze context"))
                val frozen = session.planStreamChat(messages, hasImage = false)
                selected = selected.copy(contextWindowTokens = 128_000)

                val repeated = session.planStreamChat(messages, hasImage = false)
                assertEquals("plan lost configured context window $window", window, frozen.contextWindowTokens)
                assertEquals("replanning changed frozen context window $window", window, repeated.contextWindowTokens)
                assertEquals("session lost frozen context window $window", window, session.currentContextWindowTokens())
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun sequentialSessionsUseFreshCancellationBaseline() {
        val router = router()
        val first = router.createSession()
        first.close()

        val second = router.createSession()
        try {
            val generation = second.currentCancellationGeneration()
            assertFalse(
                "new session inherited cancellation from the closed session",
                second.wasCancellationRequestedSince(generation)
            )
        } finally {
            second.close()
        }
    }

    @Test
    fun closedSessionIsIdempotentAndCannotCancelOrMutateCurrentSession() {
        val router = router()
        val old = router.createSession()
        old.close()

        val current = router.createSession()
        try {
            val generation = current.currentCancellationGeneration()
            val messages = listOf(ChatMessage(Role.USER, "current session"))
            assertEquals(32_000, current.planStreamChat(messages, hasImage = false).contextWindowTokens)

            old.cancelActiveRequests()
            old.close()
            assertEquals(generation, current.currentCancellationGeneration())
            assertFalse(
                "closed session cancellation affected the current session",
                current.wasCancellationRequestedSince(generation)
            )

            assertThrowsClosed { old.planStreamChat(messages, hasImage = false) }
            assertThrowsClosed { old.resetResponsesSessionAfterCheckpoint() }
            assertThrowsClosed { old.resetResponsesSessionForAndroidActivation() }
            assertThrowsClosed { old.reportImageSendRejected("stale closed session update") }
            assertTrue(
                "closed session mutation affected current vision capability",
                current.canSendVisionMessages()
            )
        } finally {
            current.close()
        }
    }

    @Test
    fun streamCreatedBeforeCloseCannotStartRequestAfterClose() {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor {
                requests.incrementAndGet()
                throw AssertionError("closed session must not reach the HTTP transport")
            }
            .build()
        val router = router(httpClient = client)
        val session = router.createSession()
        try {
            val coldStream = runBlocking {
                session.streamChat(listOf(ChatMessage(Role.USER, "stale stream")))
            }
            session.close()

            assertThrowsClosed {
                runBlocking { coldStream.toList() }
            }
            assertEquals("closed session stream started an HTTP request", 0, requests.get())
        } finally {
            session.close()
        }
    }

    @Test
    fun activeSessionCancellationIsObservableThroughSessionApi() {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor {
                requests.incrementAndGet()
                throw AssertionError("cancelled request must not reach the HTTP transport")
            }
            .build()
        val router = router(httpClient = client)
        val session = router.createSession()
        try {
            val coldStream = runBlocking {
                session.streamChat(listOf(ChatMessage(Role.USER, "cancel before request")))
            }
            val generation = session.currentCancellationGeneration()
            session.cancelActiveRequests()

            assertTrue(
                "active session cancellation did not advance its cancellation state",
                session.wasCancellationRequestedSince(generation)
            )
            assertThrows(IOException::class.java) {
                runBlocking { coldStream.toList() }
            }
            assertEquals("cancelled request reached the HTTP transport", 0, requests.get())
        } finally {
            session.close()
        }
    }

    private fun assertThrowsClosed(block: () -> Unit) {
        try {
            block()
        } catch (error: IllegalStateException) {
            assertEquals("ModelSession is closed", error.message)
            return
        }
        throw AssertionError("closed ModelSession operation unexpectedly succeeded")
    }

    private fun router(
        configuration: TransportConfiguration,
        httpClient: OkHttpClient = OkHttpClient()
    ): LLMRouter = LLMRouter(
        httpClient = httpClient,
        configManager = configuration,
        tokenUsageRepository = object : TransportUsageRecorder {
            override fun recordRequest(
                provider: String,
                model: String,
                promptTokens: Long,
                completionTokens: Long,
                cachedInputTokens: Long,
                cacheFieldsReported: Boolean
            ) = Unit
        },
        diagnostics = object : TransportDiagnostics {
            override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String,
            request: kotlinx.serialization.json.JsonObject) = TransportExchangeTrace.None
        override fun requestStarted() = Unit
            override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) = Unit
            override fun warning(tag: String, message: String) = Unit
            override fun debug(tag: String, message: String) = Unit
        }
    )

    private fun router(
        contextWindowTokens: Int = 32_000,
        httpClient: OkHttpClient = OkHttpClient()
    ): LLMRouter {
        val selected = provider(contextWindowTokens)
        return router(object : TransportConfiguration {
            override fun reasoningCapability(config: ProviderConfig, model: String) =
                com.droidance.core.model.llm.models.ReasoningCapability(com.droidance.core.model.llm.models.ReasoningEffort.entries.filter { it.wireValue != null })
            override fun recordReasoningRejected(config: ProviderConfig, model: String) = Unit
            override fun resolveProvider(): ProviderConfig = selected
            override fun getCurrentModel(config: ProviderConfig): String = config.defaultModel
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
        }, httpClient)
    }

    private fun provider(contextWindowTokens: Int = 32_000) = ProviderConfig(
        id = "regression-provider",
        name = "regression-provider",
        apiType = ApiType.OPENAI_RESPONSES,
        transportKind = ModelTransport.OPENAI_RESPONSES,
        baseUrl = "https://example.invalid",
        defaultModel = "regression-model",
        contextWindowTokens = contextWindowTokens
    )
}
