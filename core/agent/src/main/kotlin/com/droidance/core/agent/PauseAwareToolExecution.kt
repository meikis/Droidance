// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

class ManualToolPauseException(cause: Throwable? = null) :
    IllegalStateException("Tool interrupted by explicit manual pause; effect is unknown", cause)

/** Cancel the in-flight operation without cancelling its owning task or replaying it. */
object PauseAwareToolExecution {
    suspend fun <T> run(paused: StateFlow<Boolean>, shouldInterrupt: () -> Boolean = { true }, operation: suspend () -> T): T = supervisorScope {
        if (paused.value && shouldInterrupt()) throw ManualToolPauseException()
        var interrupted = false
        val pending = async(start = CoroutineStart.LAZY) { operation() }
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            paused.first { it && shouldInterrupt() }
            interrupted = true
            pending.cancel(CancellationException("manual pause"))
        }
        try {
            pending.await()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (interrupted) throw ManualToolPauseException(error)
            throw error
        } finally {
            watcher.cancel()
        }
    }
}
