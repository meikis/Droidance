// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AppTakeoverModelSelectionTest {
    private fun provider() = ProviderConfig(id = "chosen", name = "chosen",
        apiType = ApiType.OPENAI_COMPATIBLE, transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
        baseUrl = "https://example.invalid/v1", apiKey = "not-persisted", models = listOf("default", "selected"), defaultModel = "default")

    @Test fun continuationRetainsSelectedModelWithoutChangingProviderDefaults() {
        val provider = provider()
        val selection = AppTakeoverModelSelection("chosen", "selected")
        val plan = selection.plan(listOf(provider))
        assertEquals("chosen", plan.providerId)
        assertEquals("selected", plan.initialModel)
        assertTrue(plan.imageIncluded)
        assertEquals(ReasoningEffort.DEFAULT, plan.reasoningEffort)
        assertEquals("default", provider.defaultModel)
        val encoded = Json.encodeToString(selection)
        assertFalse(encoded.contains("not-persisted"))
        assertEquals(selection, Json.decodeFromString<AppTakeoverModelSelection>(encoded))
    }

    @Test fun unavailableSelectionCannotFallBackToADifferentModelOrProvider() {
        val selection = AppTakeoverModelSelection("chosen", "selected")
        for (providers in listOf(emptyList(), listOf(provider().copy(isEnabled = false)),
                listOf(provider().copy(models = listOf("default"))), listOf(provider().copy(id = "other")))) {
            assertThrows(IllegalArgumentException::class.java) { selection.plan(providers) }
        }
    }
}
