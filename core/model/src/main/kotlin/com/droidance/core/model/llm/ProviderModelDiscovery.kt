// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface ModelDiscoveryResult {
    data class Success(val body: String) : ModelDiscoveryResult
    data class HttpFailure(val statusCode: Int) : ModelDiscoveryResult
    data object Unavailable : ModelDiscoveryResult
}

/** A bounded models request to the configured API address, without redirecting credentials. */
class ProviderModelDiscovery(httpClient: OkHttpClient) {
    private val client = httpClient.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .build()

    suspend fun fetch(baseUrl: String, apiKey: String): ModelDiscoveryResult {
        val request = runCatching {
            val root = ProviderEndpoint.apiRoot(baseUrl).toHttpUrlOrNull()
                ?: return ModelDiscoveryResult.Unavailable
            if (root.username.isNotEmpty() || root.password.isNotEmpty() ||
                root.query != null || root.fragment != null) return ModelDiscoveryResult.Unavailable
            Request.Builder().url(root.newBuilder().addPathSegment("models").build())
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
                .get().build()
        }.getOrNull() ?: return ModelDiscoveryResult.Unavailable

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(ModelDiscoveryResult.Unavailable)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) return@use ModelDiscoveryResult.HttpFailure(it.code)
                            val body = it.body ?: return@use ModelDiscoveryResult.Unavailable
                            val limit = 8L * 1024 * 1024
                            if (body.contentLength() > limit) return@use ModelDiscoveryResult.Unavailable
                            val source = body.source()
                            val buffer = Buffer()
                            while (buffer.size <= limit && source.read(buffer, minOf(8192, limit + 1 - buffer.size)) != -1L) { }
                            if (buffer.size > limit) ModelDiscoveryResult.Unavailable
                            else ModelDiscoveryResult.Success(buffer.readUtf8())
                        }
                    }.getOrDefault(ModelDiscoveryResult.Unavailable)
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}

/** Extract model IDs from OpenAI-compatible and simple array model lists. */
object ProviderModelList {
    fun parse(raw: String): List<String>? = runCatching {
        val entries = when (val root = Json.parseToJsonElement(raw)) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
            else -> null
        } ?: return null
        entries.mapNotNull { entry ->
            val value = when (entry) {
                is JsonObject -> entry["id"] ?: entry["name"]
                is JsonPrimitive -> entry
                else -> null
            } as? JsonPrimitive
            value?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isISOControl) }
        }.distinct()
    }.getOrNull()
}
