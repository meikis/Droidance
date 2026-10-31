// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.model.llm.models.ChatMessage

/** Immutable request prefix only; the caller appends its current decision tail separately.
 * Committed EventStore messages remain the source of facts. Preserve dispatched history
 * between checkpoints, without retaining former screen bindings or mutable request snapshots.
 * A changed projection or removed directive starts a new prefix rather than retaining stale facts.
 */
class CacheableRequestHistory {
    private data class State(
        val durable: List<ChatMessage>,
        val directives: List<ChatMessage>,
        val memory: String,
        val prefix: List<ChatMessage>
    )
    private var committed: State? = null
    private var prepared: State? = null
    private var reuseFacts: Map<String, String> = emptyMap()

    fun reset() { committed = null; prepared = null; reuseFacts = emptyMap() }

    /** Local byte-stability facts; provider cache hits are measured independently. */
    fun preparationFacts(): Map<String, String> = reuseFacts

    fun prepare(
        durable: List<ChatMessage>,
        directives: List<ChatMessage>,
        memory: ChatMessage?
    ): List<ChatMessage> {
        val uniqueDirectives = directives.distinctBy { it.copy(timestamp = 0) }
        val previous = committed
        val reusable = previous != null && durable.size >= previous.durable.size &&
            previous.durable.indices.all { sameWire(previous.durable[it], durable[it]) } &&
            previous.directives.all { old -> uniqueDirectives.any { sameWire(old, it) } } &&
            (previous.memory.isEmpty() || memory != null)
        val base = if (reusable) {
            val prior = checkNotNull(previous)
            prior.prefix + durable.drop(prior.durable.size) +
                uniqueDirectives.filter { next -> prior.directives.none { sameWire(it, next) } } +
                listOfNotNull(memory.takeIf { it?.content.orEmpty() != prior.memory })
        } else listOfNotNull(memory) + uniqueDirectives + durable
        val reason = when {
            reusable -> "none"
            previous == null -> "no_committed_prefix"
            durable.size < previous.durable.size || previous.durable.indices.any { !sameWire(previous.durable[it], durable[it]) } -> "durable_history_changed"
            previous.directives.any { old -> uniqueDirectives.none { sameWire(old, it) } } -> "directive_removed_or_changed"
            else -> "memory_removed"
        }
        reuseFacts = mapOf("local_prefix_reused" to reusable.toString(),
            "local_prefix_reused_messages" to (if (reusable) previous!!.prefix.size else 0).toString(),
            "local_prefix_reset_reason" to reason,
            "duplicate_directives_removed" to (directives.size - uniqueDirectives.size).toString())
        prepared = State(durable.toList(), uniqueDirectives, memory?.content.orEmpty(), base)
        return base
    }

    /** Commit only when dispatching, so a pause/restart cannot consume an unsent update. */
    fun recordSent(immutablePrefix: List<ChatMessage>) {
        val state = checkNotNull(prepared)
        require(immutablePrefix.size == state.prefix.size && immutablePrefix.indices.all {
            sameWire(immutablePrefix[it], state.prefix[it])
        }) { "Only the prepared immutable prefix can be committed" }
        committed = state
        prepared = null
    }

    /** Read-only summary fork: keep the sent prefix and append the last committed response. */
    fun fork(durable: List<ChatMessage>): List<ChatMessage> {
        val state = committed ?: return durable
        val matches = durable.size >= state.durable.size && state.durable.indices.all {
            sameWire(state.durable[it].copy(imageBase64 = null, imageBase64List = emptyList()),
                durable[it].copy(imageBase64 = null, imageBase64List = emptyList()))
        }
        return if (matches) state.prefix + durable.drop(state.durable.size) else durable
    }

    /** Extra retained request context must be included in the checkpoint budget. */
    fun retainedRequestContext(): List<ChatMessage> {
        val state = committed ?: return emptyList()
        val accounted = (state.durable + state.directives).toMutableList()
        return state.prefix.filter { message ->
            val index = accounted.indexOfFirst { sameWire(it, message) }
            if (index < 0) true else { accounted.removeAt(index); false }
        }
    }

    private fun sameWire(left: ChatMessage, right: ChatMessage): Boolean =
        left.copy(timestamp = 0) == right.copy(timestamp = 0)
}
