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
    fun freshInstallHasNoProviders() {
        assertTrue(ProviderConfigManager(isolatedContext).getAllConfigs().isEmpty())
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
        val configured = provider("user")
        manager.addConfig(configured)
        manager.deleteConfig(configured.id)
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
