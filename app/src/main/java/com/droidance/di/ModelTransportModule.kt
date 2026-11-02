// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.di

import com.droidance.core.agent.di.*

import com.droidance.di.*

import com.droidance.core.model.llm.*
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ImageDetailOption
import com.droidance.settings.ProviderConfigManager
import com.droidance.config.TokenUsageRepository
import com.droidance.core.device.debug.DiagnosticsCollector
import com.droidance.core.agent.debug.TaskLatencyTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelTransportModule {
    @Provides
    @Singleton
    fun provideRouter(
        httpClient: OkHttpClient,
        configuration: ProviderConfigManager,
        reasoning: com.droidance.settings.ReasoningCapabilityRepository,
        requestFields: com.droidance.settings.ProviderRequestFieldsRepository,
        usageRepository: TokenUsageRepository,
        latency: TaskLatencyTracker,
        diagnostics: DiagnosticsCollector
    ): LLMRouter = LLMRouter(
        httpClient,
        object : TransportConfiguration {
            override fun reasoningCapability(config: ProviderConfig, model: String) = reasoning.capability(config, model)
            override fun recordReasoningRejected(config: ProviderConfig, model: String) = reasoning.recordRejected(config, model)
            override fun responsesRequestFields(config: ProviderConfig) = requestFields.responses(config)
            override fun resolveProvider() = configuration.resolveProvider()
            override fun getCurrentModel(config: ProviderConfig) = configuration.getCurrentModel(config)
            override fun advanceToNextModel(config: ProviderConfig) = configuration.advanceToNextModel(config)
            override fun resetModelFallback(configId: String) = configuration.resetModelFallback(configId)
            override fun imageDetailCandidates(config: ProviderConfig, model: String) =
                configuration.imageDetailCandidates(config, model)
            override fun imageDetailCapabilityStatus(config: ProviderConfig, model: String) =
                configuration.imageDetailCapabilityStatus(config, model)
            override fun recordImageDetailAccepted(
                config: ProviderConfig,
                model: String,
                detail: ImageDetailOption,
                returnedModel: String?
            ) = configuration.recordImageDetailAccepted(config, model, detail, returnedModel)
            override fun recordImageDetailRejected(
                config: ProviderConfig,
                model: String,
                detail: ImageDetailOption
            ) = configuration.recordImageDetailRejected(config, model, detail)
        },
        object : TransportUsageRecorder {
            override fun recordRequest(usage: ModelRequestUsage) =
                usageRepository.recordRequest(usage)
        },
        object : TransportDiagnostics {
            override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String,
                request: kotlinx.serialization.json.JsonObject): TransportExchangeTrace =
                com.droidance.debug.ProviderExchangeRecorder(diagnostics.modelDiagnosticsDirectory()).open(scope, provider, model, request)
            override fun requestStarted() { latency.llmCalls.incrementAndGet() }
            override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) =
                diagnostics.recordTokenUsage(inputTokens, outputTokens)
            override fun warning(tag: String, message: String) { android.util.Log.w(tag, message) }
            override fun debug(tag: String, message: String) { android.util.Log.d(tag, message) }
        }
    )

    @Provides
    @Singleton
    fun provideModelClient(router: LLMRouter): ModelClient = router
}
