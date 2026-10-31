// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import com.droidance.core.agent.event.MemoryContextItemPayload
import com.droidance.core.agent.event.MemoryContextLoadedEventPayload
import com.droidance.core.agent.event.MemoryContextScope

enum class ProgressiveMemoryScope(val refPrefix: String) {
    PROFILE("profile"),
    LONG_TERM("long_term"),
    EPISODE("episode"),
    EXPERIENCE("experience")
}

data class ProgressiveMemoryEntry(
    val ref: String,
    val scope: ProgressiveMemoryScope,
    val category: String,
    val content: String,
    val updatedAt: String = "",
    val sourceThroughSequence: Long? = null,
    val revision: String = "",
    val expiresAt: String? = null
)

object ProgressiveMemorySelector {
    fun select(
        entries: List<ProgressiveMemoryEntry>,
        query: String,
        coreProfileCategories: Set<String>,
        coreProfileMaxItems: Int,
        relatedMemoryMaxItems: Int,
        summaryMaxChars: Int
    ): List<MemoryContextItemPayload> {
        require(coreProfileMaxItems > 0)
        require(relatedMemoryMaxItems > 0)
        require(summaryMaxChars in 32..500)
        val normalizedCoreCategories = coreProfileCategories.map(String::lowercase).toSet()
        val coreCandidates = entries.asSequence()
            .filter { entry ->
                entry.scope == ProgressiveMemoryScope.PROFILE &&
                    entry.category.lowercase() in normalizedCoreCategories
            }
            .toList()
            .groupBy { it.category.lowercase() }
            .mapValues { (_, rows) -> rows.sortedWith(compareByDescending<ProgressiveMemoryEntry> { it.updatedAt }
                .thenBy { it.ref }) }
        val core = buildList {
            var index = 0
            while (size < coreProfileMaxItems) {
                val round = normalizedCoreCategories.sorted().mapNotNull { coreCandidates[it]?.getOrNull(index) }
                if (round.isEmpty()) break
                addAll(round.take(coreProfileMaxItems - size)); index++
            }
        }.map { it.toPayload(MemoryContextScope.CORE_PROFILE, summaryMaxChars) }
        val coreRefs = core.mapTo(mutableSetOf(), MemoryContextItemPayload::ref)
        val tokens = tokenizeMemoryQuery(query)
        val related = entries.asSequence()
            .filterNot { it.ref in coreRefs }
            .map { entry ->
                val searchable = searchable(entry)
                val durable = entry.scope in setOf(ProgressiveMemoryScope.PROFILE, ProgressiveMemoryScope.LONG_TERM)
                val exactKey = durable && entry.ref.substringAfter(':').lowercase() in tokens
                Triple(entry, exactKey, relevance(searchable, tokens))
            }
            .filter { (_, exactKey, score) ->
                tokens.isNotEmpty() && (exactKey || MemoryQueryRelevance.isRelevant(score, tokens))
            }
            .sortedWith(
                // Current durable records must not be crowded out by repeated historical goals.
                compareByDescending<Triple<ProgressiveMemoryEntry, Boolean, Int>> { it.second }
                    .thenByDescending { it.first.scope in setOf(ProgressiveMemoryScope.PROFILE, ProgressiveMemoryScope.LONG_TERM) }
                    .thenByDescending { it.third }
                    .thenByDescending { it.first.updatedAt }
                    .thenBy { it.first.ref }
            )
            .take(relatedMemoryMaxItems)
            .map { (entry, _, _) ->
                entry.toPayload(
                    scope = when (entry.scope) {
                        ProgressiveMemoryScope.PROFILE -> MemoryContextScope.RELATED_PROFILE
                        ProgressiveMemoryScope.LONG_TERM -> MemoryContextScope.LONG_TERM
                        ProgressiveMemoryScope.EPISODE -> MemoryContextScope.EPISODE
                        ProgressiveMemoryScope.EXPERIENCE -> MemoryContextScope.EXPERIENCE
                    },
                    summaryMaxChars = summaryMaxChars
                )
            }
            .toList()
        return (core + related).distinctBy(MemoryContextItemPayload::ref)
    }

    private fun ProgressiveMemoryEntry.toPayload(
        scope: MemoryContextScope,
        summaryMaxChars: Int
    ): MemoryContextItemPayload = MemoryContextItemPayload(
        ref = ref,
        scope = scope,
        summary = content.replace(Regex("\\s+"), " ").trim().take(summaryMaxChars),
        sourceThroughSequence = sourceThroughSequence
    )

    private fun searchable(entry: ProgressiveMemoryEntry): String =
        "${entry.ref} ${entry.category} ${entry.content}".lowercase()

    private fun relevance(searchable: String, tokens: List<String>): Int {
        if (tokens.isEmpty()) return 0
        return MemoryQueryRelevance.score(searchable, tokens)
    }
}

object MemoryQueryRelevance {
    fun score(searchable: String, tokens: List<String>): Int =
        tokens.sumOf { token -> if (searchable.contains(token)) token.length else 0 }

    fun isRelevant(score: Int, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        val totalWeight = tokens.sumOf(String::length).coerceAtLeast(1)
        return score > 0 && score.toDouble() / totalWeight >= MIN_QUERY_COVERAGE
    }

    private const val MIN_QUERY_COVERAGE = 0.08
}


fun tokenizeMemoryQuery(text: String): List<String> {
    val latin = Regex("[A-Za-z0-9._-]{2,}").findAll(text).map { it.value.lowercase() }
    val chinese = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(text).flatMap { match ->
        val value = match.value
        sequence {
            yield(value)
            for (size in 2..4) {
                if (value.length >= size) {
                    for (index in 0..value.length - size) yield(value.substring(index, index + size))
                }
            }
        }
    }
    return (latin + chinese).distinct().toList()
}
