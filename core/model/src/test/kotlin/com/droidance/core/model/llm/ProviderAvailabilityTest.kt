// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.session.ModelTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAvailabilityTest {
    private val provider = ProviderConfig(
        id = "test", name = "test", apiType = ApiType.OPENAI_COMPATIBLE,
        transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
        baseUrl = "https://provider.example/v1", isEnabled = true
    )

    @Test fun providerNeedsAnAddressAndAConfirmedTransportToBeAvailable() {
        assertTrue(provider.isAvailableForRequests())
        assertFalse(provider.copy(baseUrl = "  ").isAvailableForRequests())
        assertFalse(provider.copy(isEnabled = false).isAvailableForRequests())
        assertFalse(provider.copy(transportKind = null).isAvailableForRequests())
    }
}
