// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.session.ModelTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderDisplayOrderTest {
    private val presets = listOf(
        preset("alpha", "https://alpha.example/v1"),
        preset("beta", "https://beta.example/v1"),
        preset("gamma", "https://gamma.example/v1")
    )

    private fun preset(id: String, baseUrl: String) = ProviderPreset(
        id, id, baseUrl, ApiType.OPENAI_COMPATIBLE, ModelTransport.GENERIC_CHAT_COMPLETIONS
    )

    private fun config(id: String, baseUrl: String, isEnabled: Boolean = true) = ProviderConfig(
        id = id, name = id, apiType = ApiType.OPENAI_COMPATIBLE,
        transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
        baseUrl = baseUrl, apiKey = "key", models = listOf("model"), defaultModel = "model",
        isEnabled = isEnabled
    )

    private val disabledPreset = presets[2].createConfig()
    private val defaultPreset = config("default-preset", "https://alpha.example/v1")
    private val enabledPreset = config("preset-beta", "https://beta.example/v1")
    private val customOne = config("custom-one", "https://relay.example/v1")
    private val customDisabled = config("custom-two", "https://relay.example/openai", isEnabled = false)

    @Test fun defaultComesFirstThenCustomThenEnabledThenDisabledPresets() {
        val stored = listOf(defaultPreset, disabledPreset, enabledPreset, customOne, customDisabled)
        assertEquals(
            listOf(defaultPreset, customOne, customDisabled, enabledPreset, disabledPreset),
            stored.inDisplayOrder(presets)
        )
    }

    @Test fun storedOrderStaysUntouchedAndStillSelectsTheSameDefault() {
        val stored = listOf(defaultPreset, disabledPreset, enabledPreset, customOne)
        val snapshot = stored.toList()
        val displayed = stored.inDisplayOrder(presets)
        assertEquals(snapshot, stored)
        assertEquals(
            stored.first(ProviderConfig::isAvailableForRequests),
            displayed.first(ProviderConfig::isAvailableForRequests)
        )
    }

    @Test fun entriesWithinATierKeepTheirStoredOrder() {
        val second = customOne.copy(id = "custom-second")
        val third = customOne.copy(id = "custom-third")
        val stored = listOf(defaultPreset, third, customOne, second)
        assertEquals(stored, stored.inDisplayOrder(presets))
    }

    @Test fun unavailableEntriesNeverTakeTheDefaultSlot() {
        val unconfirmed = customOne.copy(id = "unconfirmed", transportKind = null)
        val stored = listOf(unconfirmed, disabledPreset, enabledPreset)
        assertEquals(listOf(enabledPreset, unconfirmed, disabledPreset), stored.inDisplayOrder(presets))
    }

    @Test fun catalogAddressDecidesCustomWhateverTheIdOrSpelling() {
        val picked = config("picked-from-catalog", " https://BETA.example/v1/chat/completions/ ")
        val redirected = config("preset-beta", "https://relay.example/v1")
        val stored = listOf(defaultPreset, picked, redirected)
        assertEquals(listOf(defaultPreset, redirected, picked), stored.inDisplayOrder(presets))
    }
}
