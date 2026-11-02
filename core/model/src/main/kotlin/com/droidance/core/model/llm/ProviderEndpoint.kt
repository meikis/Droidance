// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.session.ModelTransport

/** A Provider address is either an API root or a complete endpoint of either protocol. */
object ProviderEndpoint {
    fun resolve(baseUrl: String, transport: ModelTransport): String = apiRoot(baseUrl) + transport.path

    fun apiRoot(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val endpoint = ModelTransport.entries.firstOrNull { trimmed.endsWith(it.path) } ?: return trimmed
        return trimmed.removeSuffix(endpoint.path)
    }

    private val ModelTransport.path: String
        get() = when (this) {
            ModelTransport.OPENAI_RESPONSES -> "/responses"
            ModelTransport.GENERIC_CHAT_COMPLETIONS -> "/chat/completions"
        }
}
