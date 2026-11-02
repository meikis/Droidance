// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ProviderTransportDiagnostic
import com.droidance.core.model.llm.session.ModelTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEditFormValidationTest {
    @Test
    fun requiredFieldsAreReportedInFormOrder() {
        assertEquals(
            ProviderFormError.NAME_REQUIRED,
            ProviderEditUiState().validationError
        )
        assertEquals(
            ProviderFormError.URL_REQUIRED,
            completeState(name = "Provider").copy(baseUrl = "").validationError
        )
        assertEquals(
            ProviderFormError.KEY_REQUIRED,
            completeState(apiKey = "").validationError
        )
        assertEquals(
            ProviderFormError.MODEL_REQUIRED,
            completeState(models = emptyList(), modelInput = "").validationError
        )
    }

    @Test
    fun everyProtocolMapsToAConfigThatCanServeRequests() {
        val responses = completeState().withProtocol(ModelTransport.OPENAI_RESPONSES)
        assertEquals(ApiType.OPENAI_RESPONSES, responses.apiType)
        assertEquals(ModelTransport.OPENAI_RESPONSES, responses.savedConfig().resolvedTransportOrNull())
        val chat = responses.withProtocol(ModelTransport.GENERIC_CHAT_COMPLETIONS)
        assertEquals(ApiType.OPENAI_COMPATIBLE, chat.apiType)
        assertEquals(ModelTransport.GENERIC_CHAT_COMPLETIONS, chat.savedConfig().resolvedTransportOrNull())
    }

    @Test
    fun selectingTheCurrentProtocolKeepsACustomEntryUnchanged() {
        val custom = storedConfig().copy(apiType = ApiType.CUSTOM, transportKind = ModelTransport.OPENAI_RESPONSES)
        val state = custom.toEditState()
        assertEquals(ModelTransport.OPENAI_RESPONSES, state.transportKind)
        assertEquals(state, state.withProtocol(ModelTransport.OPENAI_RESPONSES))
    }

    @Test
    fun unconfirmedEntryNeedsAProtocolAndIsConfirmedBySaving() {
        val unconfirmed = storedConfig().copy(
            apiType = ApiType.CUSTOM,
            transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
            transportDiagnostic = ProviderTransportDiagnostic.TRANSPORT_CONFIRMATION_REQUIRED,
            isEnabled = false
        )
        val state = unconfirmed.toEditState()
        assertNull(state.transportKind)
        assertNull(state.requestUrl)
        assertEquals(ProviderFormError.PROTOCOL_REQUIRED, state.validationError)

        val confirmed = state.withProtocol(ModelTransport.OPENAI_RESPONSES)
        assertNull(confirmed.validationError)
        val saved = confirmed.savedConfig()
        assertNull(saved.transportDiagnostic)
        assertEquals(ModelTransport.OPENAI_RESPONSES, saved.resolvedTransportOrNull())
        assertTrue(saved.isAvailableForRequests())
    }

    @Test
    fun storedProtocolMismatchIsNotShownAsConfirmed() {
        val mismatched = storedConfig().copy(transportKind = ModelTransport.OPENAI_RESPONSES)
        assertEquals(ProviderFormError.PROTOCOL_REQUIRED, mismatched.toEditState().validationError)
    }

    @Test
    fun configuredDisabledEntryStaysDisabled() {
        assertFalse(storedConfig().copy(isEnabled = false).toEditState().isEnabled)
    }

    @Test
    fun requestUrlFollowsAddressAndProtocol() {
        val state = completeState(baseUrl = "https://relay.example/team/v1/")
        assertEquals("https://relay.example/team/v1/chat/completions", state.requestUrl)
        assertEquals(
            "https://relay.example/team/v1/responses",
            state.withProtocol(ModelTransport.OPENAI_RESPONSES).requestUrl
        )
        assertEquals(
            "https://relay.example/v1/responses",
            completeState(baseUrl = "https://relay.example/v1/responses")
                .withProtocol(ModelTransport.OPENAI_RESPONSES).requestUrl
        )
        assertNull(completeState(baseUrl = "").requestUrl)
        assertNull(completeState(baseUrl = "relay.example/v1").requestUrl)
    }

    @Test
    fun httpAndHttpsUrlsWithHostsAreAccepted() {
        listOf(
            "http://localhost:8080/v1",
            "https://api.example.com/v1",
            "  https://api.example.com/v1  "
        ).forEach { url ->
            assertNull(
                "Expected URL to be accepted: $url",
                completeState(baseUrl = url).validationError
            )
        }
    }

    @Test
    fun urlsWithoutSupportedSchemeHostOrWithoutUserInfoAreRejected() {
        listOf(
            "api.example.com/v1",
            "ftp://api.example.com/v1",
            "https:///v1",
            "https://user:password@api.example.com/v1"
        ).forEach { url ->
            assertEquals(
                "Expected URL to be rejected: $url",
                ProviderFormError.INVALID_URL,
                completeState(baseUrl = url).validationError
            )
        }
    }

    @Test
    fun modelInputMustContainARealModelToken() {
        listOf(
            " ",
            ",;，；、\n\r\t",
            " , ; ， ； 、 \n\t "
        ).forEach { input ->
            assertEquals(
                "Expected model input to be rejected: ${input.escapeForMessage()}",
                ProviderFormError.MODEL_REQUIRED,
                completeState(models = emptyList(), modelInput = input).validationError
            )
        }

        listOf("gpt-5", " gpt-5, ", "model-a；model-b").forEach { input ->
            assertNull(
                "Expected model input to be accepted: $input",
                completeState(models = emptyList(), modelInput = input).validationError
            )
        }
    }

    private fun ProviderEditUiState.savedConfig() = toConfig("provider", models, 128_000)

    private fun storedConfig() = ProviderConfig(
        id = "provider",
        name = "Provider",
        apiType = ApiType.OPENAI_COMPATIBLE,
        transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
        baseUrl = "https://api.example.com/v1",
        apiKey = "test-key",
        models = listOf("gpt-5"),
        defaultModel = "gpt-5"
    )

    private fun completeState(
        name: String = "Provider",
        baseUrl: String = "https://api.example.com/v1",
        apiKey: String = "test-key",
        models: List<String> = listOf("gpt-5"),
        modelInput: String = ""
    ) = ProviderEditUiState(
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models,
        modelInput = modelInput
    )

    private fun String.escapeForMessage(): String = replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
