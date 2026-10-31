// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import java.util.UUID
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
enum class AppTakeoverSignalType {
    POLL,
    RECHECK,
    NOTIFICATION,
    FRAME_CHANGE
}

@Serializable
data class AppTakeoverSignal(
    val id: String,
    val type: AppTakeoverSignalType,
    val occurredAtMs: Long,
    val notificationKey: String? = null,
    val title: String = "",
    val content: String = ""
)

/** Retain the registering Agent's selection, never provider credentials or a replacement default. */
@Serializable
data class AppTakeoverModelSelection(val providerId: String, val modelId: String) {
    fun plan(providers: List<com.droidance.core.model.llm.models.ProviderConfig>): com.droidance.core.model.llm.ModelStreamPlan {
        val provider = providers.singleOrNull { it.id == providerId && it.isEnabled && it.isTransportConfigured() }
        requireNotNull(provider) { "APP_TAKEOVER_PROVIDER_UNAVAILABLE" }
        require(modelId.isNotBlank() && (modelId in provider.models || modelId == provider.defaultModel)) {
            "APP_TAKEOVER_MODEL_UNAVAILABLE"
        }
        return com.droidance.core.model.llm.ModelStreamPlan.fromProvider(provider.copy(models = provider.models.toList()), modelId, imageIncluded = true)
    }
}

@Serializable
data class AppTakeoverWatch(
    val packageName: String,
    val instruction: String,
    val pollIntervalMs: Long,
    val enabled: Boolean = true,
    val checkpoint: String = "",
    val lastSummary: String = "",
    val lastFailure: String = "",
    val taskStartedAtMs: Long = System.currentTimeMillis(),
    val originTurnId: String? = null,
    val modelSelection: AppTakeoverModelSelection? = null,
    val deadlineAtMs: Long? = null,
    val nextCheckAtMs: Long? = 0L,
    val terminalReason: AppTakeoverTerminalReason? = null,
    val nextPollAtMs: Long,
    val pendingSignals: List<AppTakeoverSignal> = emptyList(),
    val pendingNotBeforeAtMs: Long = 0L,
    val activeRunId: String? = null,
    val activeRunStartedAtMs: Long = 0L,
    val activePollDueAtMs: Long = 0L,
    val activeSignals: List<AppTakeoverSignal> = emptyList(),
    val lastClaimedAtMs: Long = 0L,
    val lastCompletedAtMs: Long = 0L,
    val lastHealthCheckAtMs: Long = 0L,
    val lastSettledFrameFingerprint: String = "",
    val consecutiveFailures: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
enum class AppTakeoverTerminalReason { GOAL_COMPLETED, DEADLINE_REACHED }

/** Clock and protocol facts only; the Agent owns object identities and business decisions. */
object AppTakeoverTaskPolicy {
    const val MAX_CHECKPOINT_CHARS = 16_000

    fun deadline(durationSeconds: Long?, deadlineAt: String?, requestAtMs: Long): Long? {
        require(durationSeconds == null || deadlineAt == null) { "MUTUALLY_EXCLUSIVE_DEADLINE" }
        return when {
            durationSeconds != null -> {
                require(durationSeconds > 0L) { "INVALID_DURATION" }
                Math.addExact(requestAtMs, Math.multiplyExact(durationSeconds, 1_000L))
            }
            deadlineAt != null -> OffsetDateTime.parse(deadlineAt).toInstant().toEpochMilli()
            else -> null
        }
    }

    fun checkAt(completedAtMs: Long, delaySeconds: Long): Long {
        require(delaySeconds >= 0L) { "INVALID_RECHECK_DELAY" }
        return Math.addExact(completedAtMs, Math.multiplyExact(delaySeconds, 1_000L))
    }

    fun expired(watch: AppTakeoverWatch, nowMs: Long): Boolean =
        watch.terminalReason == null && watch.deadlineAtMs?.let { nowMs >= it } == true
}

data class ClaimedAppTakeoverRun(
    val runId: String,
    val watch: AppTakeoverWatch,
    val signals: List<AppTakeoverSignal>
)

object AppTakeoverQueuePolicy {
    fun selectNext(watches: List<AppTakeoverWatch>, nowMs: Long): AppTakeoverWatch? =
        watches.asSequence()
            .filter { it.enabled && it.terminalReason == null && it.activeRunId == null }
            .filterNot { AppTakeoverTaskPolicy.expired(it, nowMs) }
            .filter { readyAt(it) <= nowMs }
            .sortedWith(
                compareBy<AppTakeoverWatch> { it.lastClaimedAtMs }
                    .thenBy { it.createdAtMs }
                    .thenBy { it.packageName }
            )
            .firstOrNull()

    fun readyAt(watch: AppTakeoverWatch): Long {
        val pendingAt = if (watch.pendingSignals.isEmpty()) {
            Long.MAX_VALUE
        } else {
            watch.pendingNotBeforeAtMs
        }
        return minOf(watch.nextPollAtMs, pendingAt, watch.nextCheckAtMs ?: Long.MAX_VALUE,
            watch.deadlineAtMs ?: Long.MAX_VALUE)
    }

    /**
     * Keep a deadline cadence instead of starting a fresh full interval after completion.
     * A signal-triggered run before the deadline preserves that deadline. A run that crosses its
     * deadline gets one immediate reconciliation pass, after which the cadence starts there.
     */
    fun nextPollAt(
        previousDueAtMs: Long,
        completedAtMs: Long,
        pollIntervalMs: Long
    ): Long {
        if (previousDueAtMs > completedAtMs && previousDueAtMs != Long.MAX_VALUE) {
            return previousDueAtMs
        }
        if (previousDueAtMs > 0L && previousDueAtMs != Long.MAX_VALUE) {
            val followingDeadline = previousDueAtMs.saturatingAdd(pollIntervalMs)
            return maxOf(followingDeadline, completedAtMs)
        }
        return completedAtMs.saturatingAdd(pollIntervalMs)
    }

    fun nextRetryAt(failedAtMs: Long, pollIntervalMs: Long, retryDelayMs: Long): Long =
        failedAtMs + maxOf(pollIntervalMs, retryDelayMs)

    private fun Long.saturatingAdd(delta: Long): Long =
        if (this > Long.MAX_VALUE - delta) Long.MAX_VALUE else this + delta
}

/** One durable EventStore thread per watched app; every wake becomes an ordinary child Turn. */
object AppTakeoverSessionIds {
    fun threadId(packageName: String): String = UUID.nameUUIDFromBytes(
        "app_takeover:${packageName.trim().lowercase()}".toByteArray(Charsets.UTF_8)
    ).toString()
}

/** Pixel equality is only a wake-up gate; it never interprets an app-specific business event. */
object AppTakeoverHealthPolicy {
    fun canSkipModel(
        signals: List<AppTakeoverSignal>,
        targetAlreadyVisible: Boolean,
        currentFingerprint: String?,
        lastSettledFingerprint: String,
        nextCheckAtMs: Long?,
        checkpoint: String,
        nowMs: Long
    ): Boolean = signals.isNotEmpty() &&
        signals.all { it.type == AppTakeoverSignalType.POLL } &&
        (nextCheckAtMs == null || nextCheckAtMs > nowMs) && checkpoint.isNotBlank() &&
        targetAlreadyVisible &&
        !currentFingerprint.isNullOrBlank() &&
        lastSettledFingerprint.isNotBlank() &&
        currentFingerprint == lastSettledFingerprint
}
