// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ImageDetailOption
import com.droidance.core.model.llm.models.ImageDetailCapabilityStatus
import com.droidance.core.model.llm.models.ReasoningCapability

interface TransportConfiguration {
    fun reasoningCapability(config: ProviderConfig, model: String): ReasoningCapability
    /** Extra top-level Responses request fields declared for this gateway. */
    fun responsesRequestFields(config: ProviderConfig): kotlinx.serialization.json.JsonObject = ProviderRequestFields.EMPTY
    fun recordReasoningRejected(config: ProviderConfig, model: String)
    fun resolveProvider(): ProviderConfig?
    fun getCurrentModel(config: ProviderConfig): String
    fun advanceToNextModel(config: ProviderConfig): String?
    fun resetModelFallback(configId: String)
    fun imageDetailCandidates(config: ProviderConfig, model: String): List<ImageDetailOption>
    fun imageDetailCapabilityStatus(config: ProviderConfig, model: String): ImageDetailCapabilityStatus
    fun recordImageDetailAccepted(
        config: ProviderConfig,
        model: String,
        detail: ImageDetailOption,
        returnedModel: String?
    )
    fun recordImageDetailRejected(config: ProviderConfig, model: String, detail: ImageDetailOption)
}

/** One actual transport attempt. Missing provider counts remain unknown, including on failure. */
data class ModelRequestUsage(
    val provider: String,
    val model: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val scope: ModelDiagnosticScope? = null
)

interface TransportUsageRecorder {
    fun recordRequest(usage: ModelRequestUsage)
}

interface TransportDiagnostics {
    fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String,
        request: kotlinx.serialization.json.JsonObject): TransportExchangeTrace
    fun requestStarted()
    fun recordTokenUsage(inputTokens: Int, outputTokens: Int)
    fun warning(tag: String, message: String)
    fun debug(tag: String, message: String)
}
