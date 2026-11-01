// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProviderPresetCatalogTest {
    private val source = File("src/main/assets/${ProviderPresetCatalog.ASSET_PATH}").readText()

    @Test fun bundledPresetsHaveUsableEndpointsAndConfirmedTransportsWithoutCredentials() {
        val presets = ProviderPresetCatalog.decode(source)
        assertTrue(presets.any { it.id == "openrouter" })
        assertTrue(presets.any { it.id == "volcengine" })
        assertTrue(presets.any { it.id == "hunyuan" })
        assertTrue(presets.any { it.id == "baidu" })
        assertTrue(presets.any { it.id == "nvidia" })
        presets.forEach { preset ->
            val config = preset.createConfig()
            assertTrue(config.isTransportConfigured())
            assertFalse(config.isAvailableForRequests())
            assertEquals("", config.apiKey)
            assertTrue(config.models.isEmpty())
            assertEquals("", config.defaultModel)
        }
    }

    @Test fun rejectsDuplicateIdsBundledModelsAndMismatchedProtocols() {
        val first = source.substringAfter('[').substringBefore('}') + "}"
        assertThrows(IllegalArgumentException::class.java) { ProviderPresetCatalog.decode("[$first,$first]") }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPresetCatalog.decode(source.replace("\"GENERIC_CHAT_COMPLETIONS\"", "\"OPENAI_RESPONSES\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPresetCatalog.decode(source.replace("\"baseUrl\"", "\"models\": [\"bundled-model\"], \"baseUrl\""))
        }
    }
}
