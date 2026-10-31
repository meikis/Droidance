// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/** A socket blocked in read must be cancelled even when no application deadline is configured. */
internal fun Call.bindCancellation(context: CoroutineContext): CompletableJob = Job(context[Job]).also { child ->
    child.invokeOnCompletion { failure -> if (failure != null) cancel() }
}

/** Supported provider routing hint only; it is neither a cached response nor a cache-hit claim. */
internal fun Request.Builder.withCacheSession(scope: ModelDiagnosticScope?): Request.Builder = apply {
    if (scope != null && build().url.host == "openrouter.ai") {
        val session = MessageDigest.getInstance("SHA-256").digest(scope.threadId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        header("x-session-id", "droidance-$session")
    }
}

/** Monotonic transport milestones; a gap before headers includes provider queue/processing time. */
internal class ModelTransportTiming(private val trace: TransportExchangeTrace) : EventListener() {
    private val started = System.nanoTime()
    private fun mark(name: String) = trace.timing(name, (System.nanoTime() - started) / 1_000_000)
    override fun dnsStart(call: Call, domainName: String) = mark("dns_start")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = mark("dns_end")
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = mark("connect_start")
    override fun secureConnectStart(call: Call) = mark("tls_start")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = mark("tls_end")
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = mark("connect_end")
    override fun requestHeadersEnd(call: Call, request: Request) = mark("request_headers_sent")
    override fun requestBodyEnd(call: Call, byteCount: Long) = mark("request_body_sent")
    override fun responseHeadersEnd(call: Call, response: Response) = mark("response_headers_received")
    override fun responseBodyStart(call: Call) = mark("response_body_start")
}
