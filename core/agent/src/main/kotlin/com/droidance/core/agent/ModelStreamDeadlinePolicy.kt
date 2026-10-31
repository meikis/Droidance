// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.LLMStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** Monotonic stream deadlines. Provider activity is liveness, not a completed decision. */
data class ModelStreamDeadlinePolicy(val firstEventMs: Long, val idleMs: Long, val firstOutputMs: Long) {
    init { require(firstEventMs >= 0 && idleMs >= 0 && firstOutputMs >= 0) }

    data class Remaining(val phase: String, val millis: Long)

    fun remaining(elapsedMs: Long, lastEventMs: Long?, outputStarted: Boolean): Remaining? {
        val budgets = mutableListOf<Remaining>()
        if (lastEventMs == null) {
            if (firstEventMs > 0) budgets += Remaining("first_event", firstEventMs - elapsedMs)
        } else if (idleMs > 0) budgets += Remaining("stream_idle", idleMs - (elapsedMs - lastEventMs))
        if (!outputStarted && firstOutputMs > 0) budgets += Remaining("first_output", firstOutputMs - elapsedMs)
        return budgets.minByOrNull { it.millis }
    }
}

class ModelStreamTimeoutException(val phase: String) : java.io.InterruptedIOException("model stream timeout: $phase")

/** Supervision lets the consumer retain buffered fragments before observing a producer failure.
 * Cancellation still cancels the producer, and no fragments are executed by this collector.
 */
internal suspend fun Flow<LLMStreamEvent>.collectWithDeadlines(
    policy: ModelStreamDeadlinePolicy,
    outputStarted: () -> Boolean,
    consume: suspend (LLMStreamEvent) -> Unit
) {
    // With no watchdog, retain ordinary Flow backpressure and publication ordering.
    if (policy.firstEventMs == 0L && policy.idleMs == 0L && policy.firstOutputMs == 0L) {
        collect { consume(it) }
        return
    }
    supervisorScope {
        val started = System.nanoTime()
        var lastActivityMs: Long? = null
        val channel = Channel<LLMStreamEvent>(Channel.BUFFERED)
        // Report producer failures through the channel. A socket may throw IOException
        // after cancellation; it must not escape a supervised launch to Android's handler.
        val producer = launch {
            try {
                this@collectWithDeadlines.collect { channel.send(it) }
            } catch (cancel: CancellationException) {
                if (currentCoroutineContext().isActive) channel.close(cancel) else throw cancel
            } catch (error: Exception) {
                channel.close(error)
            } finally { channel.close() }
        }
        try {
            while (true) {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val remaining = policy.remaining(elapsedMs, lastActivityMs, outputStarted())
                val received = if (remaining == null) channel.receiveCatching() else {
                    if (remaining.millis <= 0) throw ModelStreamTimeoutException(remaining.phase)
                    withTimeoutOrNull(remaining.millis) { channel.receiveCatching() }
                        ?: throw ModelStreamTimeoutException(remaining.phase)
                }
                received.exceptionOrNull()?.let { throw it }
                if (received.isClosed) break
                val event = received.getOrThrow()
                if (event !is LLMStreamEvent.ReasoningConfiguration && event !is LLMStreamEvent.RequestConfiguration)
                    lastActivityMs = (System.nanoTime() - started) / 1_000_000
                consume(event)
            }
        } finally {
            channel.cancel()
            withContext(NonCancellable) { producer.cancelAndJoin() }
        }
    }
}
