// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.session.ModelTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderEndpointTest {
    private val chat = ModelTransport.GENERIC_CHAT_COMPLETIONS
    private val responses = ModelTransport.OPENAI_RESPONSES

    @Test fun apiRootGainsTheProtocolPath() {
        assertEquals("https://relay.example/v1/chat/completions", ProviderEndpoint.resolve("https://relay.example/v1", chat))
        assertEquals("https://relay.example/v1/responses", ProviderEndpoint.resolve("https://relay.example/v1", responses))
    }

    @Test fun completeEndpointIsNotRepeated() {
        assertEquals(
            "https://relay.example/v1/chat/completions",
            ProviderEndpoint.resolve(" https://relay.example/v1/chat/completions/ ", chat)
        )
        assertEquals(
            "https://relay.example/v1/responses",
            ProviderEndpoint.resolve(" https://relay.example/v1/responses/ ", responses)
        )
    }

    @Test fun relayPathPrefixIsPreserved() {
        val prefixed = "https://relay.example/openai/team-a/v1"
        assertEquals("$prefixed/chat/completions", ProviderEndpoint.resolve(prefixed, chat))
        assertEquals("$prefixed/responses", ProviderEndpoint.resolve(prefixed, responses))
        assertEquals("$prefixed/chat/completions", ProviderEndpoint.resolve("$prefixed/chat/completions", chat))
        assertEquals("$prefixed/responses", ProviderEndpoint.resolve("$prefixed/responses", responses))
    }

    @Test fun switchingProtocolReplacesACompleteEndpoint() {
        assertEquals("https://relay.example/v1/responses", ProviderEndpoint.resolve("https://relay.example/v1/chat/completions", responses))
        assertEquals("https://relay.example/v1/chat/completions", ProviderEndpoint.resolve("https://relay.example/v1/responses", chat))
    }

    @Test fun apiRootRemovesOnlyAnEndpointSuffix() {
        assertEquals("https://relay.example/v1", ProviderEndpoint.apiRoot("https://relay.example/v1/"))
        assertEquals("https://relay.example/v1", ProviderEndpoint.apiRoot("https://relay.example/v1/responses"))
        assertEquals("https://relay.example/v1", ProviderEndpoint.apiRoot("https://relay.example/v1/chat/completions"))
        assertEquals("https://relay.example/responses/v1", ProviderEndpoint.apiRoot("https://relay.example/responses/v1"))
    }
}
