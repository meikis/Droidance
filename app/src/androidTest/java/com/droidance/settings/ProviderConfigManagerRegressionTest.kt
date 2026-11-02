// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.BuildConfig
import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.ui.viewmodel.ProviderEditViewModel
import com.droidance.ui.viewmodel.ProviderFormError
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderConfigManagerRegressionTest {
    private lateinit var baseContext: Context
    private lateinit var isolatedContext: Context
    private lateinit var isolatedPrefix: String
    private val isolatedPreferenceNames = linkedSetOf<String>()

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
        isolatedPrefix = "provider-regression-${UUID.randomUUID()}-"
        isolatedContext = object : ContextWrapper(baseContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = isolatedPrefix + name
                isolatedPreferenceNames += isolatedName
                return baseContext.getSharedPreferences(isolatedName, mode)
            }
        }
    }

    @After
    fun tearDown() {
        isolatedPreferenceNames.forEach(baseContext::deleteSharedPreferences)
        isolatedPreferenceNames.clear()
    }

    @Test
    fun selectingDefaultProviderPersistsOrderAcrossReconstructionInIsolatedPreferences() {
        val manager = ProviderConfigManager(isolatedContext)
        val first = provider("first")
        val selected = provider("selected")
        manager.addConfig(first)
        manager.addConfig(selected)

        manager.setDefaultProvider(selected.id)

        assertEquals(selected.id, manager.getDefaultConfig()?.id)
        assertEquals(selected.id, manager.getAllConfigs().first().id)

        val reconstructed = ProviderConfigManager(isolatedContext)
        assertEquals(selected.id, reconstructed.getDefaultConfig()?.id)
        assertEquals(
            manager.getAllConfigs().map { it.id },
            reconstructed.getAllConfigs().map { it.id }
        )
        assertTrue(reconstructed.getConfig(first.id)?.isEnabled == true)
    }

    @Test
    fun displayOrderLeavesPersistedOrderAndDefaultProviderUnchanged() {
        val manager = ProviderConfigManager(isolatedContext)
        val presetUrl = manager.presets.first().baseUrl
        val selected = provider("selected").copy(baseUrl = presetUrl)
        val custom = provider("custom")
        manager.addConfig(selected)
        manager.addConfig(custom)
        val stored = manager.getAllConfigs()

        val displayed = stored.inDisplayOrder(manager.presets)

        assertEquals(selected.id, displayed.first().id)
        assertEquals(custom.id, displayed[1].id)
        assertEquals(stored, manager.getAllConfigs())
        assertEquals(selected.id, manager.resolveProvider()?.id)
        assertEquals(stored, ProviderConfigManager(isolatedContext).getAllConfigs())
    }

    @Test
    fun freshInstallSeedsDisabledPresetsOnlyOnce() {
        val manager = ProviderConfigManager(isolatedContext)
        assertEquals(manager.presets.map { it.createConfig() }, manager.getAllConfigs())
        assertTrue(manager.getEnabledConfigs().isEmpty())
        assertEquals(null, manager.getDefaultConfig())
        assertTrue(manager.getAllConfigs().all { it.apiKey.isEmpty() && !it.isEnabled })
        manager.deleteConfig(manager.getAllConfigs().first().id)
        assertEquals(manager.getAllConfigs(), ProviderConfigManager(isolatedContext).getAllConfigs())
    }

    @Test
    fun eachPresetRequiresAUserModelAndPreservesItsProtocolAcrossReloads() {
        val manager = ProviderConfigManager(isolatedContext)
        manager.presets.forEach { preset ->
            val config = preset.createConfig()
            val editor = ProviderEditViewModel(manager)
            editor.loadProvider(config.id)
            assertEquals(ProviderFormError.KEY_REQUIRED, editor.uiState.value.validationError)
            editor.updateApiKey("test-key")
            assertEquals(ProviderFormError.MODEL_REQUIRED, editor.uiState.value.validationError)
            assertTrue(!editor.saveProvider())
            assertEquals(config, manager.getConfig(config.id))
            editor.updateModelInput("user-selected-model")
            assertEquals(null, editor.uiState.value.validationError)
            assertTrue(editor.saveProvider())
            val saved = ProviderConfigManager(isolatedContext).getConfig(config.id)!!
            assertEquals(config.copy(apiKey = "test-key", isEnabled = true,
                models = listOf("user-selected-model"), defaultModel = "user-selected-model"), saved)
            assertTrue(saved.isAvailableForRequests())
            manager.setDefaultProvider(saved.id)
            assertEquals(saved.id, ProviderConfigManager(isolatedContext).getDefaultConfig()?.id)
        }
    }

    @Test
    fun selectingANewTemplateClearsKeysAndSavingUsesItsTransport() {
        val manager = ProviderConfigManager(isolatedContext)
        val editor = ProviderEditViewModel(manager)
        editor.loadProvider(null)
        manager.presets.forEach { preset ->
            editor.updateApiKey("previous-provider-key")
            editor.selectPreset(preset.id)
            assertEquals("", editor.uiState.value.apiKey)
            assertEquals(preset.baseUrl, editor.uiState.value.baseUrl)
            assertTrue(editor.uiState.value.models.isEmpty())
            assertEquals("", editor.uiState.value.defaultModel)
            editor.updateApiKey("new-provider-key")
            editor.updateModelInput("user-selected-model")
            assertTrue(editor.saveProvider())
            val saved = manager.getAllConfigs().last()
            assertEquals(preset.apiType, saved.apiType)
            assertEquals(preset.transportKind, saved.resolvedTransportOrNull())
            assertEquals("user-selected-model", saved.defaultModel)
        }
        editor.selectPreset(null)
        assertEquals("", editor.uiState.value.name)
        assertEquals("", editor.uiState.value.apiKey)
        assertEquals(ModelTransport.GENERIC_CHAT_COMPLETIONS, editor.uiState.value.transportKind)
    }

    @Test
    fun editingADisabledConfiguredProviderKeepsItDisabled() {
        val manager = ProviderConfigManager(isolatedContext)
        val configured = provider("disabled").copy(isEnabled = false)
        manager.addConfig(configured)
        val editor = ProviderEditViewModel(manager)
        editor.loadProvider(configured.id)
        editor.updateName("Renamed")
        assertTrue(editor.saveProvider())
        assertEquals(configured.copy(name = "Renamed"), manager.getConfig(configured.id))
    }

    @Test
    fun enabledProviderWithoutAddressIsNotAvailableUntilConfigured() {
        val manager = ProviderConfigManager(isolatedContext)
        val placeholder = provider("placeholder").copy(baseUrl = "", apiKey = "", isEnabled = false)
        manager.addConfig(placeholder)
        manager.updateConfig(placeholder.copy(isEnabled = true))
        assertTrue(manager.getEnabledConfigs().isEmpty())
        assertEquals(null, manager.getDefaultConfig())
        assertTrue(runCatching { manager.setDefaultProvider(placeholder.id) }.isFailure)
    }

    @Test
    fun existingProvidersLoadWithoutAdditions() {
        val configured = provider("user")
        writeExistingConfigs(listOf(configured))
        assertEquals(listOf(configured), ProviderConfigManager(isolatedContext).getAllConfigs())
    }

    @Test
    fun deletingLastProviderPersistsAcrossReconstruction() {
        val manager = ProviderConfigManager(isolatedContext)
        manager.getAllConfigs().forEach { manager.deleteConfig(it.id) }
        assertTrue(ProviderConfigManager(isolatedContext).getAllConfigs().isEmpty())
    }

    private fun writeExistingConfigs(configs: List<ProviderConfig>) {
        assertTrue(isolatedContext.getSharedPreferences("droidance_providers", Context.MODE_PRIVATE).edit()
            .putString("provider_configs", Json.encodeToString(configs))
            .putInt("provider_config_schema_version", 3)
            .commit())
    }

    private fun provider(id: String) = ProviderConfig(
        id = "provider-regression-$id",
        name = id,
        apiType = ApiType.OPENAI_COMPATIBLE,
        transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
        baseUrl = "https://$id.example.com/v1",
        apiKey = "key-$id",
        models = listOf("model-$id"),
        defaultModel = "model-$id",
        isEnabled = true
    )
}
