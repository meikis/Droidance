// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One execution lane. Foreground admission preempts idle work before waiting for cleanup. */
class IdleWorkScheduler(private val scope: CoroutineScope) {
    data class State(val pending: Int = 0, val running: Boolean = false, val failures: Int = 0)
    private val lane = Mutex()
    private val monitor = Any()
    private val pending = linkedMapOf<String, suspend () -> Unit>()
    private var foreground = 0
    private var worker: Job? = null
    private var failures = 0
    private var running = false
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    suspend fun <T> runForeground(block: suspend () -> T): T {
        synchronized(monitor) {
            foreground++
            worker?.cancel()
        }
        try {
            return lane.withLock { block() }
        } finally {
            synchronized(monitor) {
                foreground--
                startWorker()
            }
        }
    }

    fun enqueue(key: String, block: suspend () -> Unit) = synchronized(monitor) {
        pending.putIfAbsent(key, block)
        publish(running)
        startWorker()
    }

    fun clearPending() = synchronized(monitor) {
        pending.clear()
        worker?.cancel()
        failures = 0
        publish(false)
    }

    private fun publish(running: Boolean) {
        this.running = running
        mutableState.value = State(pending.size, running, failures)
    }

    /** Called under monitor. Cancellation keeps the source pending; ordinary failure settles it. */
    private fun startWorker() {
        if (!scope.isActive || foreground > 0 || pending.isEmpty() || worker != null) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = synchronized(monitor) {
                    if (foreground > 0) null else pending.entries.firstOrNull()?.let { it.key to it.value }
                } ?: break
                lane.withLock {
                    currentCoroutineContext().ensureActive()
                    val admitted = synchronized(monitor) {
                        (foreground == 0 && pending[entry.first] === entry.second).also { if (it) publish(true) }
                    }
                    if (!admitted) return@withLock
                    try {
                        entry.second()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        synchronized(monitor) { failures++ }
                    }
                    synchronized(monitor) {
                        if (pending[entry.first] === entry.second) pending.remove(entry.first)
                        publish(false)
                    }
                }
            }
        }
        worker = next
        next.invokeOnCompletion {
            synchronized(monitor) {
                if (worker === next) worker = null
                publish(false)
                startWorker()
            }
        }
        next.start()
    }
}
