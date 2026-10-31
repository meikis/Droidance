// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
