// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.program

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The worker queues host calls. Only a currently executing tool invocation may service them. */
class ProgramConnection(private val context: Context) : AutoCloseable {
    data class Request(val operation: String, val arguments: String, val answer: CompletableFuture<String> = CompletableFuture())
    val requests = LinkedBlockingQueue<Request>(1)
    private val connected = CompletableDeferred<IProgramWorker>()
    @Volatile var status = "starting"
        private set
    @Volatile var identity = ""
        private set
    @Volatile var error = ""
        private set
    @Volatile private var closed = false
    @Volatile private var worker: IProgramWorker? = null
    private var bound = false
    private val activeRequest = java.util.concurrent.atomic.AtomicReference<Request?>()
    private val callback = object : IProgramHost.Stub() {
        override fun invoke(operation: String, arguments: String): String {
            check(!closed) { "PROGRAM_REVOKED" }
            val request = Request(operation, arguments)
            check(activeRequest.compareAndSet(null, request)) { "HOST_QUEUE_FULL" }
            try {
                check(!closed && requests.offer(request)) { "PROGRAM_REVOKED" }
                if (closed) request.answer.completeExceptionally(IllegalStateException("PROGRAM_REVOKED"))
                return request.answer.get(300, TimeUnit.SECONDS)
            } finally { activeRequest.compareAndSet(request, null) }
        }
        override fun finished(result: String, failure: String) {
            if (!closed) { error = failure; status = result }
        }
    }
    private val service = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val remote = IProgramWorker.Stub.asInterface(binder)
            if (closed) { remote.stop(); return }
            worker = remote
            connected.complete(remote)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (!closed && status in setOf("starting", "running")) {
                error = "WORKER_LOST: computation stopped; inspect any pending device effect"
                status = "interrupted"
            }
            connected.completeExceptionally(IllegalStateException("WORKER_LOST"))
        }
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("WORKER_UNAVAILABLE")) }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
    }
    suspend fun start(code: String, timeoutMs: Long) {
        try {
            check(android.os.Build.VERSION.SDK_INT >= 29) { "PROGRAM_REQUIRES_API_29" }
            bound = context.bindIsolatedService(Intent(context, ProgramWorkerService::class.java), Context.BIND_AUTO_CREATE,
                "run_" + java.util.UUID.randomUUID().toString().replace("-", ""), context.mainExecutor, service)
            check(bound) { "WORKER_UNAVAILABLE" }
            val remote = withTimeout(5000) { connected.await() }
            check(!closed)
            identity = withContext(kotlinx.coroutines.Dispatchers.IO) { remote.identity() }
            check(identity.contains("\"isolated\":true")) { "WORKER_NOT_ISOLATED" }
            status = "running"
            remote.run(code, timeoutMs, callback)
        } catch (error: Throwable) { close(); throw error }
    }
    override fun close() {
        if (closed) return
        closed = true
        if (status in setOf("starting", "running")) status = "canceled"
        activeRequest.getAndSet(null)?.answer?.completeExceptionally(IllegalStateException("PROGRAM_REVOKED"))
        requests.poll()?.answer?.completeExceptionally(IllegalStateException("PROGRAM_REVOKED"))
        runCatching { worker?.stop() }
        if (bound) { runCatching { context.unbindService(service) }; bound = false }
    }
}
