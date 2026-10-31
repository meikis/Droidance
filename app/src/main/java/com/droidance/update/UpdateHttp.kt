// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun OkHttpClient.forUpdates(): OkHttpClient = newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.MINUTES)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

/** Cancels connection setup and blocked body reads when the owning coroutine stops. */
internal suspend fun <T> OkHttpClient.withUpdateResponse(
    request: Request,
    block: suspend (Response) -> T
): T = coroutineScope {
    val call = newCall(request)
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        call.execute().use { block(it) }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        cancellation.cancel()
    }
}

internal class UpdateHttpException(val status: Int) : IOException("HTTP $status") {
    val retryable: Boolean get() = status == 408 || status == 429 || status in 500..599
}
