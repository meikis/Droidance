// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.MemoryContextItemPayload
import com.droidance.core.agent.event.MemoryContextLoadedEventPayload
import com.droidance.core.agent.event.MemoryContextScope
import com.droidance.core.agent.config.MemoryContextPolicy
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressiveMemoryContextProjector @Inject constructor(
    private val durableMemoryStore: DurableMemoryStore,
    private val sessionIndex: SessionIndex,
    private val experiences: ExperienceRepository
) {
    suspend fun project(query: String, policy: MemoryContextPolicy, foregroundPackage: String?): MemoryContextLoadedEventPayload {
        val durableEntries = durableMemoryStore.contextSnapshot().entries
        val episodes = sessionIndex.search(query, policy.relatedMemoryMaxItems).map { result ->
            ProgressiveMemoryEntry(
                ref = "episode:${result.candidateHash}",
                scope = ProgressiveMemoryScope.EPISODE,
                category = "task",
                // The bounded index is a retrieval hint, not a source of task facts. Keep the
                // historical goal for matching, but defer the prior model report to explicit
                // session_search so stale products, prices, or seller claims cannot be copied
                // into a new task before a fresh observation is grounded.
                content = result.userInput,
                updatedAt = result.lastSeenAt.toString(),
                sourceThroughSequence = result.sourceThroughSequence
            )
        }
        val methods = experiences.contextHints(query, foregroundPackage, policy.experienceContextItems).map { row ->
            val feedback = row.feedback.promptSummary(policy.summaryMaxChars * 2 / 3)
            val remaining = (policy.summaryMaxChars - feedback.length - 2).coerceAtLeast(0)
            val method = "Historical method; verify applicability. ${row.packageName}: ${row.title}"
                .replace(Regex("\\s+"), " ").take(remaining)
            MemoryContextItemPayload(row.ref, MemoryContextScope.EXPERIENCE,
                "$method; $feedback".take(policy.summaryMaxChars))
        }
        return MemoryContextLoadedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            queryHash = sha256(listOfNotNull(query, foregroundPackage).joinToString("\n")),
            items = ProgressiveMemorySelector.select(
                entries = durableEntries + episodes,
                query = query,
                coreProfileCategories = policy.coreProfileCategories.toSet(),
                coreProfileMaxItems = policy.coreProfileMaxItems,
                relatedMemoryMaxItems = policy.relatedMemoryMaxItems,
                summaryMaxChars = policy.summaryMaxChars
            ) + methods
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
