// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ProviderConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Optional, bounded metadata GET. Failure is a cache miss and never a task failure. */
class ReasoningModelDiscovery(httpClient: OkHttpClient) {
    private val client = httpClient.newBuilder()
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .build()

    suspend fun fetch(config: ProviderConfig): String? {
        val request = runCatching {
            Request.Builder().url("${ProviderEndpoint.apiRoot(config.baseUrl)}/models")
                .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
                .get().build()
        }.getOrNull() ?: return null
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onResponse(call: Call, response: Response) {
                    val raw = runCatching {
                        response.use {
                            if (!it.isSuccessful) return@use null
                            val body = it.body ?: return@use null
                            val limit = 2L * 1024 * 1024
                            if (body.contentLength() > limit) return@use null
                            val source = body.source()
                            val buffer = Buffer()
                            while (buffer.size <= limit && source.read(buffer, minOf(8192, limit + 1 - buffer.size)) != -1L) { }
                            if (buffer.size > limit) null else buffer.readUtf8()
                        }
                    }.getOrNull()
                    if (continuation.isActive) continuation.resume(raw)
                }
            })
        }
    }
}
