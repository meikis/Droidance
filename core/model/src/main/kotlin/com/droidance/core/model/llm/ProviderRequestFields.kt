// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/**
 * Data-driven extra top-level fields for a gateway's Responses requests, such as an explicit
 * cache mode. Fields never replace a value the request already carries. A provider that rejects
 * them is retried without them by the router, so an entry cannot make a task fail.
 */
class ProviderRequestFields(raw: String) {
    private val gateways = runCatching {
        ((Json.parseToJsonElement(raw) as? JsonObject)?.get("gateways") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
    }.getOrNull().orEmpty()

    /** An entry may narrow its hosts to base URL path prefixes, e.g. one product line of a gateway. */
    fun responses(baseUrl: String): JsonObject {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return EMPTY
        val host = uri.host?.lowercase() ?: return EMPTY
        val path = uri.path.orEmpty().trimEnd('/') + "/"
        return gateways.firstOrNull { gateway ->
            (gateway["hosts"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == host } == true &&
                ((gateway["path_prefixes"] as? JsonArray)?.any { prefix ->
                    (prefix as? JsonPrimitive)?.contentOrNull?.let { path.startsWith(it.trimEnd('/') + "/") } == true
                } ?: true)
        }?.get("responses") as? JsonObject ?: EMPTY
    }

    companion object {
        val EMPTY = JsonObject(emptyMap())

        fun apply(request: JsonObject, fields: JsonObject): JsonObject =
            if (fields.isEmpty()) request else JsonObject(fields.filterKeys { it !in request } + request)

        /** Only a request rejection that names one of the added fields disables them. */
        fun rejectedBy(error: Throwable, fields: JsonObject): Boolean {
            val http = error.providerHttpException() ?: return false
            return http.category == ProviderErrorCategory.REQUEST_REJECTED &&
                fields.keys.any { key -> http.message.orEmpty().contains(key, ignoreCase = true) }
        }
    }
}
