// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

class UpdateHttpTest {
    @Test fun cancellation_interrupts_a_request_waiting_for_headers() = runBlocking {
        ServerSocket(0).use { server ->
            val requestReceived = CompletableDeferred<Unit>()
            val serverJob = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    requestReceived.complete(Unit)
                    assertEquals(-1, input.read())
                }
            }
            val job = launch(Dispatchers.IO) {
                OkHttpClient().forUpdates().withUpdateResponse(
                    Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
                ) { error("Server deliberately does not send a response") }
            }
            withTimeout(5_000) { requestReceived.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            withTimeout(5_000) { serverJob.join() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun signing_identity_requires_all_current_signers_to_match() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertTrue(sameSigningIdentity(listOf(a), listOf(a.copyOf())))
        assertTrue(sameSigningIdentity(listOf(a, b), listOf(b.copyOf(), a.copyOf())))
        assertFalse(sameSigningIdentity(emptyList(), emptyList()))
        assertFalse(sameSigningIdentity(listOf(a), listOf(b)))
        assertFalse(sameSigningIdentity(listOf(a, b), listOf(a)))
        assertFalse(sameSigningIdentity(listOf(a), listOf(a, b)))
    }

    @Test fun cancellation_interrupts_a_blocked_body_read() = runBlocking {
        ServerSocket(0).use { server ->
            val bodyRead = CompletableDeferred<Unit>()
            val serverJob = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\na".toByteArray())
                        flush()
                    }
                    assertEquals(-1, input.read())
                }
            }
            val job = launch(Dispatchers.IO) {
                OkHttpClient().forUpdates().withUpdateResponse(
                    Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
                ) { response ->
                    response.body!!.byteStream().use { input ->
                        assertEquals('a'.code, input.read())
                        bodyRead.complete(Unit)
                        input.read()
                    }
                }
            }
            withTimeout(5_000) { bodyRead.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            withTimeout(5_000) { serverJob.join() }
            assertTrue(job.isCancelled)
        }
    }
}
