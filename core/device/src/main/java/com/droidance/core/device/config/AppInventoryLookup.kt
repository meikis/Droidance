// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.config

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** Coalesces missing lookups and bounds negative caching by inventory generation and time. */
internal class AppInventoryLookup(
    private val current: () -> AppInventorySnapshot,
    private val refresh: suspend () -> Unit,
    private val policy: () -> AppInventoryPolicy,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val misses = LinkedHashMap<Pair<String, String>, Pair<Long, Long>>()

    suspend fun ensure(query: String, packageName: String, refreshIncomplete: Boolean = false) {
        val key = query.trim().lowercase(Locale.ROOT) to packageName.trim()
        // Cached hits must not queue behind a different missing lookup's device scan.
        if (satisfied(current(), key, refreshIncomplete)) return
        mutex.withLock {
            val before = current()
            if (satisfied(before, key, refreshIncomplete)) return@withLock
            val previous = misses[key]
            if (previous?.first == before.generation && now() - previous.second < policy().retryAfterMs)
                return@withLock
            refresh()
            val after = current()
            if (before.packages == after.packages && before.apps == after.apps) {
                misses.replaceAll { _, value ->
                    if (value.first == before.generation) after.generation to value.second else value
                }
            }
            misses.remove(key)
            misses[key] = after.generation to now()
            while (misses.size > policy().missCacheEntries) misses.remove(misses.keys.first())
        }
    }

    private fun satisfied(before: AppInventorySnapshot, key: Pair<String, String>,
                          refreshIncomplete: Boolean): Boolean {
        val present = when {
            key.second.isNotBlank() -> key.second in before.packages
            key.first.isNotBlank() -> AppNameMatcher.findCandidates(key.first, before.apps).isNotEmpty()
            else -> before.capturedAtMs > 0
        }
        return present && (!refreshIncomplete || (before.complete && !before.dirty))
    }
}
