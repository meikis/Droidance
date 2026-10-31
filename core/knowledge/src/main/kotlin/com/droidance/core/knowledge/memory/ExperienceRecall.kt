// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

/** Generic lexical recall over Agent-authored methods. Ranking is a retrieval order, never an applicability decision. */
object ExperienceRecall {
    private const val MAX_QUERY_TOKENS = 64
    private const val MAX_TOKEN_WEIGHT = 8

    data class Candidate(val id: String, val tokens: Set<String>, val succeeded: Int, val failed: Int, val updatedAt: Long)

    data class Weights(val success: Int, val failure: Int)

    /** The installed App label lets a user's own wording reach an English method of that package. */
    fun indexTokens(update: ExperienceUpdate, appLabel: String?): List<String> = tokenizeMemoryQuery(listOfNotNull(
        update.title, update.package_name, appLabel, update.recall_phrases.joinToString(" "),
        update.preconditions, update.method
    ).joinToString(" "))

    /** Execution-context terms go first so a long user message cannot truncate them away. */
    fun queryTokens(text: String, context: List<String>): List<String> =
        (tokenizeMemoryQuery(context.joinToString(" ")) + tokenizeMemoryQuery(text.take(2000)))
            .distinct().take(MAX_QUERY_TOKENS)

    fun rank(candidates: List<Candidate>, queryTokens: List<String>, weights: Weights): List<Candidate> {
        val query = queryTokens.toSet()
        return candidates.sortedWith(compareByDescending<Candidate> { candidate ->
            candidate.tokens.filter { it in query }.sumOf { it.length.coerceAtMost(MAX_TOKEN_WEIGHT) } +
                weights.success * candidate.succeeded - weights.failure * candidate.failed
        }.thenByDescending { it.updatedAt }.thenBy { it.id })
    }
}
