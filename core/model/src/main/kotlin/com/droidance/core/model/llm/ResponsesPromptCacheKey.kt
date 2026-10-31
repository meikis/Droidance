// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.security.MessageDigest

/**
 * Routes Agent Responses requests that share the same model, instructions and tools to the
 * same provider cache, so a new conversation can reuse the stable prefix written by earlier
 * ones and each round can reuse its settled history.
 * Only documented endpoints receive the field; the value is a digest, never raw content.
 */
object ResponsesPromptCacheKey {
    fun supports(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        return uri.scheme == "https" && (host == "api.openai.com" || host.endsWith(".volces.com"))
    }

    fun apply(request: JsonObject, endpoint: String, scope: ModelDiagnosticScope?): JsonObject {
        if (scope == null || "prompt_cache_key" in request || !supports(endpoint)) return request
        val prefix = listOf("model", "instructions", "tools").joinToString("\u001f") { request[it]?.toString().orEmpty() }
        val digest = MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return JsonObject(request + ("prompt_cache_key" to JsonPrimitive("droidance-$digest")))
    }
}
