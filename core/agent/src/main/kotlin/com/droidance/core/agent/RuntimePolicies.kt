// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.providerHttpException

object ModelRoundRetryPolicy {
    fun isTransientFailure(error: Throwable): Boolean {
        error.providerHttpException()?.let { failure ->
            return failure.category !in setOf(com.droidance.core.model.llm.ProviderErrorCategory.AUTHENTICATION,
                com.droidance.core.model.llm.ProviderErrorCategory.BILLING,
                com.droidance.core.model.llm.ProviderErrorCategory.REQUEST_REJECTED,
                com.droidance.core.model.llm.ProviderErrorCategory.CONTEXT_LIMIT) &&
                (failure.statusCode in setOf(408, 409, 429) || failure.statusCode >= 500)
        }
        return generateSequence(error) { it.cause }.take(8).any { it is java.io.IOException }
    }

    fun backoffMs(baseMs: Long, retryNumber: Int, error: Throwable? = null,
        random: Double = kotlin.random.Random.nextDouble()): Long {
        require(baseMs >= 0 && retryNumber > 0 && random in 0.0..1.0)
        val exponential = baseMs.toDouble() * Math.pow(2.0, (retryNumber - 1).toDouble())
        val jittered = (exponential * (0.75 + random * 0.5)).toLong().coerceAtLeast(0)
        return maxOf(jittered, error?.providerHttpException()?.retryAfterMs ?: 0L)
    }

    fun shouldRetry(
        hasActionableOutput: Boolean,
        permanentFailure: Boolean,
        retriesUsed: Int,
        retryLimit: Int
    ): Boolean {
        return !hasActionableOutput && !permanentFailure && retriesUsed < retryLimit
    }
}

/** One decision and its retries share a monotonic budget; completed decisions start fresh. */
class ModelRecoveryBudget(private val limitMs: Long, private val nowNanos: () -> Long = System::nanoTime) {
    init { require(limitMs > 0) }
    private var startedAt: Long? = null

    fun start() { if (startedAt == null) startedAt = nowNanos() }
    fun reset() { startedAt = null }
    fun remainingMs(): Long = startedAt?.let {
        (limitMs - ((nowNanos() - it) / 1_000_000).coerceAtLeast(0)).coerceAtLeast(0)
    } ?: limitMs
    fun requestTimeoutMs(requestedMs: Long): Long {
        require(requestedMs >= 0)
        return remainingMs().let { if (requestedMs == 0L) it else minOf(requestedMs, it) }
    }
    fun retryDelayMs(baseMs: Long, retryNumber: Int, error: Throwable? = null): Long? =
        ModelRoundRetryPolicy.backoffMs(baseMs, retryNumber, error).takeIf { it < remainingMs() }
}

object RoundTimeoutBudgetPolicy {
    fun forOutputBudget(baseTimeoutMs: Long, baseTokens: Int, requestedTokens: Int): Long {
        require(baseTimeoutMs >= 0 && baseTokens > 0 && requestedTokens > 0)
        if (requestedTokens <= baseTokens) return baseTimeoutMs
        return baseTimeoutMs.toBigInteger()
            .multiply(requestedTokens.toBigInteger())
            .divide(baseTokens.toBigInteger())
            .min(Long.MAX_VALUE.toBigInteger()).toLong()
    }

    fun bound(
        requestedTimeoutMs: Long,
        taskWallMs: Long,
        activeElapsedMs: Long,
        finalReply: Boolean
    ): Long {
        val requested = requestedTimeoutMs.coerceAtLeast(0L)
        if (finalReply) return requested
        if (taskWallMs <= 0L) return requested
        val remaining = (taskWallMs - activeElapsedMs).coerceAtLeast(0L)
        return if (requested == 0L) remaining else minOf(requested, remaining)
    }
}

object ManualTakeoverTransitionPolicy {
    fun shouldAcceptOverlayTakeover(
        isPaused: Boolean,
        lastResumeElapsedMs: Long,
        nowElapsedMs: Long,
        debounceMs: Long
    ): Boolean {
        if (isPaused || lastResumeElapsedMs <= 0L || debounceMs <= 0L) return true
        val elapsedSinceResume = nowElapsedMs - lastResumeElapsedMs
        return elapsedSinceResume < 0L || elapsedSinceResume >= debounceMs
    }
}

enum class RuntimeUiOutcome {
    DONE,
    NEED_MORE_INFO,
    FAILED,
    SYSTEM_ERROR
}

object RuntimeUiResultPolicy {
    fun failureSummary(result: TurnResult, fallback: () -> String): String =
        result.modelFailureSummary?.takeIf(String::isNotBlank)
            ?: result.runtimeFailureSummary?.takeIf(String::isNotBlank)
            ?: result.summary.takeIf {
                result.taskDoneByModel && result.taskDoneStatus.equals("failed", ignoreCase = true) && it.isNotBlank()
            } ?: fallback()

    fun outcome(
        terminal: TurnTerminal,
        failureReason: TurnFailureReason
    ): RuntimeUiOutcome {
        if (terminal == TurnTerminal.NEED_MORE_INFO && failureReason in setOf(
                TurnFailureReason.MODEL_TIMEOUT,
                TurnFailureReason.GUARD_STOP,
                TurnFailureReason.CONTEXT_COMPACTION_FAILED
            )
        ) {
            return RuntimeUiOutcome.NEED_MORE_INFO
        }
        if (failureReason != TurnFailureReason.NONE) return RuntimeUiOutcome.SYSTEM_ERROR
        return when (terminal) {
            TurnTerminal.DONE -> RuntimeUiOutcome.DONE
            TurnTerminal.NEED_MORE_INFO -> RuntimeUiOutcome.NEED_MORE_INFO
            TurnTerminal.SYSTEM_ERROR -> RuntimeUiOutcome.SYSTEM_ERROR
            else -> RuntimeUiOutcome.FAILED
        }
    }

    fun isFailed(
        terminal: TurnTerminal,
        failureReason: TurnFailureReason
    ): Boolean {
        return outcome(terminal, failureReason) == RuntimeUiOutcome.FAILED
    }
}

data class RuntimeTerminalProjection(
    val reportStatus: String,
    val executionTerminalStatus: ExecutionTerminalStatus,
    val diagnosticVerdict: String
) {
    companion object {
        fun from(terminal: TurnTerminal): RuntimeTerminalProjection {
            return when (terminal) {
                TurnTerminal.NEED_MORE_INFO -> RuntimeTerminalProjection(
                    reportStatus = "need_more_info",
                    executionTerminalStatus = ExecutionTerminalStatus.NEED_MORE_INFO,
                    diagnosticVerdict = "need_more_info"
                )
                TurnTerminal.FAILED -> RuntimeTerminalProjection(
                    reportStatus = "failed",
                    executionTerminalStatus = ExecutionTerminalStatus.FAILED,
                    diagnosticVerdict = "failed"
                )
                TurnTerminal.SYSTEM_ERROR -> RuntimeTerminalProjection(
                    reportStatus = "error",
                    executionTerminalStatus = ExecutionTerminalStatus.ERROR,
                    diagnosticVerdict = "error"
                )
                else -> RuntimeTerminalProjection(
                    reportStatus = "done",
                    executionTerminalStatus = ExecutionTerminalStatus.DONE,
                    diagnosticVerdict = "completed"
                )
            }
        }
    }
}
