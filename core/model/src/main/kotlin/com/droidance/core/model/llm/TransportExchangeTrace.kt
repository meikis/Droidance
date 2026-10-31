// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.*

/** Correlation only; transport diagnostics never decide or recover task state. */
data class ModelDiagnosticScope(val threadId: String, val turnId: String, val roundId: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelDiagnosticScope>
}

interface TransportExchangeTrace {
    fun response(status: Int, requestId: String?, retryAfter: String?)
    fun timing(name: String, elapsedMs: Long)
    fun line(value: String)
    fun finish(error: Throwable?)

    object None : TransportExchangeTrace {
        override fun response(status: Int, requestId: String?, retryAfter: String?) = Unit
        override fun timing(name: String, elapsedMs: Long) = Unit
        override fun line(value: String) = Unit
        override fun finish(error: Throwable?) = Unit
    }
}

/** Credentials are removed before any diagnostic sink sees the wire body. */
class TransportDiagnosticRedactor(private val apiKey: String) {
    fun text(value: String): String = if (apiKey.isBlank()) value else value.replace(apiKey, "[REDACTED]")
    fun json(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            if (key.lowercase() in setOf("authorization", "api_key", "apikey", "access_token", "refresh_token", "password", "secret"))
                JsonPrimitive("[REDACTED]") else json(child)
        })
        is JsonArray -> JsonArray(value.map(::json))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(text(value.content)) else value
        else -> value
    }
}
