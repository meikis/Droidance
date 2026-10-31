// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.session.ModelTransport
import java.security.MessageDigest

/**
 * Stable, opaque plan for an upcoming streaming model interaction.
 * Keeps provider configuration and internal routing details encapsulated inside core:model.
 */
data class ModelStreamPlan(
    val initialModel: String,
    val imageIncluded: Boolean,
    val providerId: String = "default-provider",
    val transportKind: ModelTransport = ModelTransport.OPENAI_RESPONSES,
    val transportConfigHash: String = DUMMY_HASH,
    val authScopeHash: String = DUMMY_HASH,
    val providerSnapshotRef: String = DUMMY_HASH,
    val contextWindowTokens: Int = 128_000,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val reasoningPreference: ReasoningEffort? = null,
    internal val providerConfig: ProviderConfig? = null
) {
    companion object {
        const val DUMMY_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        fun fromProvider(
            config: ProviderConfig,
            initialModel: String,
            imageIncluded: Boolean
        ): ModelStreamPlan {
            val transport = config.resolvedTransportOrNull() ?: ModelTransport.OPENAI_RESPONSES
            val transportConfigHash = sha256(
                listOf(
                    config.baseUrl.trimEnd('/'),
                    "llm-router-v1",
                    "api_type=${config.apiType.name}"
                ).joinToString("\u001f")
            )
            val authScopeHash = sha256("${config.id}\u001f${config.apiKey.trim()}")
            val providerSnapshotRef = sha256(
                listOf(
                    config.id,
                    config.apiType.name,
                    transport.name,
                    config.baseUrl.trimEnd('/'),
                    initialModel,
                    config.contextWindowTokens.toString(),
                    config.reasoningPreference?.name ?: "agent"
                ).joinToString("\u001f")
            )
            return ModelStreamPlan(
                initialModel = initialModel,
                imageIncluded = imageIncluded,
                providerId = config.id,
                transportKind = transport,
                transportConfigHash = transportConfigHash,
                authScopeHash = authScopeHash,
                providerSnapshotRef = providerSnapshotRef,
                contextWindowTokens = config.contextWindowTokens,
                reasoningEffort = config.reasoningPreference ?: ReasoningEffort.DEFAULT,
                reasoningPreference = config.reasoningPreference,
                providerConfig = config
            )
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
