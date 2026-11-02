// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.settings.ComposerSettings
import com.droidance.settings.ProviderPreset
import com.droidance.settings.inDisplayOrder

data class ComposerModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String
) {
    val displayName: String get() = modelId.substringAfterLast('/')
}

data class ComposerPreferences(
    val model: ComposerModelOption? = null,
    val executionSpace: ExecutionSpace = ExecutionSpace.VIRTUAL_SPACE
)

internal fun restoredComposerPreferences(
    settings: ComposerSettings,
    models: List<ComposerModelOption>
): ComposerPreferences = ComposerPreferences(
    model = if (settings.providerId != null && settings.modelId != null) {
        models.firstOrNull { it.providerId == settings.providerId && it.modelId == settings.modelId }
            ?: ComposerModelOption(settings.providerId, settings.providerId, settings.modelId)
    } else null,
    executionSpace = settings.executionSpace
)

internal fun composerModelOptions(
    providers: List<ProviderConfig>,
    presets: List<ProviderPreset>
): List<ComposerModelOption> =
    providers.filter { it.isEnabled && it.isTransportConfigured() }.inDisplayOrder(presets).flatMap { provider ->
        (provider.models + provider.defaultModel).filter(String::isNotBlank).distinct().map { model ->
            ComposerModelOption(provider.id, provider.name, model)
        }
    }

/** Snapshot an explicit choice without changing provider defaults or other conversations. */
internal fun composerModelPlan(
    preferences: ComposerPreferences,
    providers: List<ProviderConfig>
): ModelStreamPlan? {
    val selected = preferences.model ?: return null
    val enabled = providers.filter { it.isEnabled && it.isTransportConfigured() }
    val provider = enabled.firstOrNull { it.id == selected.providerId }
    requireNotNull(provider) { "Selected provider is unavailable" }
    val model = selected.modelId
    require(model.isNotBlank() && (model in provider.models || model == provider.defaultModel)) {
        "Selected model is unavailable"
    }
    val snapshot = provider.copy(
        models = provider.models.toList()
    )
    return ModelStreamPlan.fromProvider(snapshot, model, imageIncluded = false)
}
