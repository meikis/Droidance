// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.models

import kotlinx.serialization.Serializable

@Serializable
data class ReasoningCacheEntry(
    val capability: ReasoningCapability,
    val detectedAtMillis: Long,
    val rejectedAtMillis: Long? = null
)

@Serializable
data class ReasoningCacheSnapshot(
    val entries: Map<String, ReasoningCacheEntry> = emptyMap(),
    val attempts: Map<String, Long> = emptyMap()
)

class ReasoningCapabilityCache(
    private val catalog: ReasoningCatalog,
    snapshot: ReasoningCacheSnapshot = ReasoningCacheSnapshot(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val entries = snapshot.entries.toMutableMap()
    private val attempts = snapshot.attempts.toMutableMap()
    private val ttl = 24L * 60 * 60 * 1_000
    private val retryInterval = 60L * 60 * 1_000

    @Synchronized fun capability(config: ProviderConfig, model: String): ReasoningCapability {
        val cached = entries[key(config, model)]
        if (cached?.rejectedAtMillis?.let { fresh(it, ttl) } == true) {
            return ReasoningCapability(parameter = cached.capability.parameter, forceDefault = true)
        }
        if (cached != null && fresh(cached.detectedAtMillis, ttl)) return cached.capability
        return catalog.capability(config.baseUrl, model)
    }

    @Synchronized fun shouldRefresh(config: ProviderConfig): Boolean =
        attempts[reasoningProviderKey(config)]?.let { !fresh(it, retryInterval) } ?: true

    @Synchronized fun recordAttempt(config: ProviderConfig) {
        attempts[reasoningProviderKey(config)] = now()
        prune()
    }

    @Synchronized fun recordMetadata(config: ProviderConfig, model: String, capability: ReasoningCapability) {
        val key = key(config, model)
        entries[key] = ReasoningCacheEntry(capability, now(), entries[key]?.rejectedAtMillis)
        prune()
    }

    @Synchronized fun recordRejected(config: ProviderConfig, model: String) {
        val key = key(config, model)
        entries[key] = ReasoningCacheEntry(capability(config, model), now(), now())
        prune()
    }

    @Synchronized fun snapshot(): ReasoningCacheSnapshot = ReasoningCacheSnapshot(entries.toMap(), attempts.toMap())

    private fun fresh(at: Long, duration: Long) = now() - at in 0 until duration
    private fun key(config: ProviderConfig, model: String) = "${reasoningProviderKey(config)}:$model"
    private fun prune() {
        entries.entries.removeAll { !fresh(it.value.detectedAtMillis, ttl) &&
            it.value.rejectedAtMillis?.let { rejected -> fresh(rejected, ttl) } != true }
        attempts.entries.removeAll { !fresh(it.value, ttl) }
        while (entries.size > 256) entries.remove(entries.minBy { it.value.detectedAtMillis }.key)
        while (attempts.size > 64) attempts.remove(attempts.minBy { it.value }.key)
    }
}
