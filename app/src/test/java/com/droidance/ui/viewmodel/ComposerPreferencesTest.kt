// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.settings.ComposerSettings
import com.droidance.settings.ProviderPreset
import org.junit.Assert.*
import org.junit.Test

class ComposerPreferencesTest {
    private fun provider(id: String = "primary") = ProviderConfig(
        id = id, name = id, apiType = ApiType.OPENAI_COMPATIBLE,
        transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
        baseUrl = "https://example.invalid/v1", models = listOf("small", "large"), defaultModel = "small"
    )

    @Test fun newConversationUsesAutomaticModelAndBackgroundSpace() {
        val preferences = ComposerPreferences()
        assertNull(preferences.model)
        assertEquals(ExecutionSpace.VIRTUAL_SPACE, preferences.executionSpace)
        assertNull(composerModelPlan(preferences, emptyList()))
    }

    @Test fun savedSelectionRestoresAllChoicesUsingCurrentProviderMetadata() {
        val settings = ComposerSettings("primary", "large", ExecutionSpace.MAIN_SCREEN)
        val currentModel = ComposerModelOption("primary", "Renamed provider", "large")
        val restored = restoredComposerPreferences(settings, listOf(currentModel))
        assertEquals(currentModel, restored.model)
        assertEquals(ExecutionSpace.MAIN_SCREEN, restored.executionSpace)
    }

    @Test fun temporarilyUnavailableModelIsRetainedAndCannotSilentlySendWithAnotherModel() {
        val restored = restoredComposerPreferences(ComposerSettings("primary", "large"), emptyList())
        assertEquals("primary", restored.model?.providerId)
        assertEquals("large", restored.model?.modelId)
        assertThrows(IllegalArgumentException::class.java) {
            composerModelPlan(restored, emptyList())
        }
    }

    @Test fun explicitModelUsesItsProviderAndModelDefaultReasoning() {
        val secondary = provider("secondary")
        val preferences = ComposerPreferences(model = ComposerModelOption("secondary", "secondary", "large"))
        val plan = requireNotNull(composerModelPlan(preferences, listOf(provider(), secondary)))
        assertEquals("secondary", plan.providerId)
        assertEquals("large", plan.initialModel)
        assertEquals(ReasoningEffort.DEFAULT, plan.reasoningEffort)
        assertEquals("small", secondary.defaultModel)
    }

    @Test fun missingDisabledOrRemovedModelsRejectSubmission() {
        val selected = ComposerPreferences(model = ComposerModelOption("primary", "primary", "large"))
        for (providers in listOf(emptyList(), listOf(provider().copy(isEnabled = false)), listOf(provider().copy(models = listOf("small"))))) {
            assertThrows(IllegalArgumentException::class.java) { composerModelPlan(selected, providers) }
        }
    }

    @Test fun pickerUsesOnlyConfiguredEnabledModelsAndKeepsProviderIdentity() {
        val enabled = provider().copy(models = listOf("small", "large", "small", ""))
        val options = composerModelOptions(listOf(enabled, provider("other"), provider("disabled").copy(isEnabled = false), provider("unknown").copy(transportKind = null)), emptyList())
        assertEquals(listOf("primary", "primary", "other", "other"), options.map { it.providerId })
        assertEquals(listOf("small", "large", "small", "large"), options.map { it.modelId })
        assertEquals("model-name", ComposerModelOption("p", "p", "namespace/model-name").displayName)
    }

    @Test fun pickerPlacesCustomProvidersAheadOfPresetsWithoutChangingTheRequestPlan() {
        val preset = ProviderPreset("catalog", "catalog", "https://catalog.invalid/v1",
            ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS)
        val defaultPreset = provider("default").copy(baseUrl = preset.baseUrl)
        val laterPreset = provider("later").copy(baseUrl = preset.baseUrl)
        val custom = provider("custom")
        val stored = listOf(defaultPreset, laterPreset, custom)

        val options = composerModelOptions(stored, listOf(preset))

        assertEquals(listOf("default", "custom", "later"), options.map { it.providerId }.distinct())
        assertEquals(defaultPreset.id, options.first().providerId)
        val selected = options.first { it.providerId == custom.id && it.modelId == "large" }
        val plan = requireNotNull(composerModelPlan(ComposerPreferences(model = selected), stored))
        assertEquals(custom.id, plan.providerId)
        assertEquals("large", plan.initialModel)
        assertNull(composerModelPlan(ComposerPreferences(), stored))
    }
}
