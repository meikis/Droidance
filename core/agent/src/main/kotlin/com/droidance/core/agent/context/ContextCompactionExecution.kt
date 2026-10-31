// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import com.droidance.core.model.llm.withModelRequestTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class ContextCompactionExecutionPolicy(
    val requestTimeoutMs: Long,
    val totalTimeoutMs: Long,
    val requestRetries: Int,
    val maxOutputTokens: Int,
    val retryMaxOutputTokens: Int,
    val chunkInputTokens: Int,
    val maxSoftDeferrals: Int,
    val deferralHeadroomTokens: Int
) {
    fun validate() {
        require(requestTimeoutMs >= 0 && totalTimeoutMs >= 0)
        require(requestRetries >= 0 && maxOutputTokens > 0 && chunkInputTokens > 0)
        require(retryMaxOutputTokens >= maxOutputTokens)
        require(maxSoftDeferrals >= 0 && deferralHeadroomTokens > 0)
    }
}

/** Only transient summary-request failures may defer an early checkpoint. */
class ContextCompactionUnavailable(val diagnostic: String, cause: Throwable? = null) :
    Exception(diagnostic, cause)

class ContextCompactionOutputLimit(cause: Throwable? = null) : Exception("COMPACTION_OUTPUT_LIMIT", cause)

class ContextCompactionExecution(private val policy: ContextCompactionExecutionPolicy) {
    init { policy.validate() }

    suspend fun run(summarize: suspend () -> String): String {
        val result = withModelRequestTimeoutOrNull(policy.totalTimeoutMs) { summarize() }
        coroutineContext.ensureActive()
        return result ?: throw ContextCompactionUnavailable("COMPACTION_TOTAL_TIMEOUT")
    }

    suspend fun request(generate: suspend (Long, Int) -> String): String {
        var lastFailure: ContextCompactionUnavailable? = null
        var outputTokens = policy.maxOutputTokens
        repeat(policy.requestRetries + 1) {
            try {
                val result = withModelRequestTimeoutOrNull(policy.requestTimeoutMs) { generate(policy.requestTimeoutMs, outputTokens) }
                coroutineContext.ensureActive()
                if (result != null) return result
                lastFailure = ContextCompactionUnavailable("COMPACTION_REQUEST_TIMEOUT")
            } catch (error: TimeoutCancellationException) {
                // A nested transport timeout is retryable only while the owning job is active.
                coroutineContext.ensureActive()
                lastFailure = ContextCompactionUnavailable("COMPACTION_REQUEST_TIMEOUT", error)
            } catch (error: ContextCompactionOutputLimit) {
                coroutineContext.ensureActive()
                lastFailure = ContextCompactionUnavailable("COMPACTION_OUTPUT_LIMIT", error)
                if (outputTokens >= policy.retryMaxOutputTokens) throw lastFailure!!
                outputTokens = (outputTokens.toLong() * 2).coerceAtMost(policy.retryMaxOutputTokens.toLong()).toInt()
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                lastFailure = ContextCompactionUnavailable("COMPACTION_REQUEST_IO", error)
            }
        }
        throw checkNotNull(lastFailure)
    }
}

/** Turn-local allowance; it is renewed only by a successfully committed checkpoint. */
class ContextCompactionRecovery(private val policy: ContextCompactionExecutionPolicy) {
    init { policy.validate() }
    private var deferrals = 0

    fun defer(estimatedInputTokens: Int, hardInputTokens: Int): Boolean {
        if (estimatedInputTokens.toLong() + policy.deferralHeadroomTokens >= hardInputTokens ||
            deferrals >= policy.maxSoftDeferrals
        ) return false
        deferrals++
        return true
    }

    fun checkpointCommitted() { deferrals = 0 }
}
