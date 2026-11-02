// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.R
import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ProviderTransportDiagnostic
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.ui.screens.ProviderEditScreen
import com.droidance.ui.viewmodel.ProviderEditViewModel
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderPresetScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var manager: ProviderConfigManager
    private val preferenceNames = mutableSetOf<String>()

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "preset-screen-${UUID.randomUUID()}-"
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = prefix + name
                preferenceNames += isolatedName
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
        manager = ProviderConfigManager(context)
    }

    @After fun tearDown() {
        preferenceNames.forEach(context::deleteSharedPreferences)
    }

    @Test fun seededEntryRequiresAKeyAndUserSuppliedModelBeforeItCanBeEnabled() {
        val editor = ProviderEditViewModel(manager)
        val config = manager.getAllConfigs().first()
        var saved = false
        compose.setContent {
            MaterialTheme { ProviderEditScreen(config.id, { saved = true }, editor) }
        }
        compose.onNodeWithText(config.baseUrl).assertExists()
        compose.onNodeWithText(context.getString(R.string.common_save)).assertIsNotEnabled()
        compose.onNodeWithText("sk-...").performScrollTo().performTextInput("ui-test-key")
        compose.onNodeWithText(context.getString(R.string.common_save)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.provider_custom_model_hint))
            .performScrollTo().performTextInput("user-selected-model")
        compose.onNodeWithText(context.getString(R.string.common_save)).performClick()
        compose.runOnIdle {
            assertTrue(saved)
            assertEquals(config.copy(apiKey = "ui-test-key", isEnabled = true,
                models = listOf("user-selected-model"), defaultModel = "user-selected-model"),
                ProviderConfigManager(context).getConfig(config.id))
        }
    }

    @Test fun protocolChoiceConfirmsAnEntryAndShowsTheRequestUrl() {
        val pending = ProviderConfig(
            id = "pending", name = "Relay", apiType = ApiType.CUSTOM,
            transportDiagnostic = ProviderTransportDiagnostic.TRANSPORT_CONFIRMATION_REQUIRED,
            baseUrl = "https://relay.example/team/v1", apiKey = "ui-test-key",
            models = listOf("relay-model"), defaultModel = "relay-model", isEnabled = false
        )
        manager.addConfig(pending)
        val editor = ProviderEditViewModel(manager)
        var saved = false
        compose.setContent {
            MaterialTheme { ProviderEditScreen(pending.id, { saved = true }, editor) }
        }
        val required = context.getString(R.string.provider_protocol_required)
        compose.onNodeWithText(context.getString(R.string.common_save)).assertIsNotEnabled()
        compose.onNodeWithText(required).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.provider_protocol_responses)).performClick()
        compose.onNodeWithText("https://relay.example/team/v1/responses").assertExists()
        compose.onNodeWithText(context.getString(R.string.common_save)).performClick()
        compose.runOnIdle {
            assertTrue(saved)
            val stored = requireNotNull(ProviderConfigManager(context).getConfig(pending.id))
            assertEquals(ModelTransport.OPENAI_RESPONSES, stored.resolvedTransportOrNull())
            assertEquals(ApiType.OPENAI_RESPONSES, stored.apiType)
            assertTrue(stored.isAvailableForRequests())
        }
    }

    @Test fun pickerPrefillsConnectionAndDoesNotReuseAnotherProvidersKey() {
        val editor = ProviderEditViewModel(manager)
        compose.setContent {
            MaterialTheme { ProviderEditScreen(null, {}, editor) }
        }
        compose.onNodeWithText(context.getString(R.string.provider_preset_custom)).performClick()
        compose.onNodeWithText("OpenAI").performScrollTo().performClick()
        compose.onNodeWithText("https://api.openai.com/v1").assertExists()
        compose.onNodeWithText("sk-...").performScrollTo().performTextInput("previous-provider-key")
        compose.onNode(hasText("OpenAI") and hasText(context.getString(R.string.provider_preset)))
            .performScrollTo().performClick()
        compose.onNodeWithText("Google Gemini").performScrollTo().performClick()
        compose.onNodeWithText("https://generativelanguage.googleapis.com/v1beta/openai").assertExists()
        compose.runOnIdle {
            assertEquals("", editor.uiState.value.apiKey)
            assertEquals(ModelTransport.GENERIC_CHAT_COMPLETIONS, editor.uiState.value.transportKind)
            assertTrue(editor.uiState.value.models.isEmpty())
            assertEquals("", editor.uiState.value.defaultModel)
        }
        compose.onNodeWithText(context.getString(R.string.common_save)).assertIsNotEnabled()
    }
}
