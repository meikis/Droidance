// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * EventStore-derived cross-session projection.
 *
 * Extraction happens in [TurnMemoryCandidateProjector]. This class is the deterministic
 * consolidation stage: one Room transaction deduplicates candidate hashes, counts references,
 * and preserves the source Turn sequence. It never parses prose into steps and never calls a model.
 */
@Singleton
class SessionIndex @Inject constructor(
    database: MemoryCandidateDatabase,
    private val durableMemoryStore: DurableMemoryStore,
    private val eventStore: com.droidance.core.agent.event.AgentEventStore,
    private val experiences: ExperienceRepository
) {
    private val dao = database.memoryCandidateDao()
    private val json = Json { encodeDefaults = true }

    suspend fun indexCandidate(candidate: MemoryCandidate): ConsolidatedMemoryCandidate {
        val encoded = json.encodeToString(candidate)
        val entity = dao.consolidate(
            candidate = MemoryCandidateEntity(
                candidateHash = candidate.candidateHash,
                version = candidate.version,
                candidateJson = encoded,
                searchText = buildSearchText(candidate),
                status = candidate.status,
                occurrences = 1,
                firstSeenAt = candidate.extractedAt,
                lastSeenAt = candidate.extractedAt,
                sourceThroughSequence = candidate.sourceThroughSequence,
                hasVerifiedStateChange = candidate.hasVerifiedStateChange
            ),
            source = MemoryCandidateSourceEntity(
                turnId = candidate.turnId,
                candidateHash = candidate.candidateHash,
                sourceThroughSequence = candidate.sourceThroughSequence
            )
        )
        return entity.toConsolidated()
    }

    suspend fun search(query: String, limit: Int = 5): List<SessionSearchResult> {
        val boundedLimit = limit.coerceIn(1, 10)
        val tokens = tokenize(query)
        return dao.allCandidates()
            .asSequence()
            .filter { it.version == MEMORY_CANDIDATE_VERSION }
            .map { entity -> entity to relevance(entity.searchText, tokens) }
            .filter { (entity, score) ->
                tokens.isEmpty() || MemoryQueryRelevance.isRelevant(score, tokens)
            }
            .sortedWith(
                compareByDescending<Pair<MemoryCandidateEntity, Int>> { it.second }
                    .thenByDescending { it.first.lastSeenAt }
            )
            .take(boundedLimit)
            .map { (entity, _) -> entity.toSearchResult() }
            .toList()
    }

    private var rebuiltEventCount = -1
    private val originalTexts = mutableMapOf<String, String>()
    private suspend fun rebuildHistoryIfNeeded() {
        val count = eventStore.count()
        if (count == rebuiltEventCount) return
        originalTexts.clear()
        for (thread in eventStore.activeThreadReadModels()) {
            val events = eventStore.eventsForThread(thread.metadata.threadId)
            for (turn in events.filter { it.eventKind == com.droidance.core.agent.event.AgentEventKind.TURN_COMPLETED }) {
                val candidate = TurnMemoryCandidateProjector.project(events, checkNotNull(turn.turnId)) ?: continue
                indexCandidate(candidate)
                val original = events.filter { it.turnId == turn.turnId }.mapNotNull { event ->
                    when (event.eventKind) {
                        com.droidance.core.agent.event.AgentEventKind.USER_MESSAGE,
                        com.droidance.core.agent.event.AgentEventKind.USER_STEER -> json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(event.payloadJson).text
                        com.droidance.core.agent.event.AgentEventKind.TOOL_FINISHED -> json.decodeFromString<com.droidance.core.agent.event.ToolFinishedEventPayload>(event.payloadJson).result.outputText
                        else -> null
                    }
                }.joinToString("\n")
                val ref = "episode:${candidate.candidateHash}"
                originalTexts[ref] = originalTexts[ref].orEmpty() + "\n" + MemorySecretRedactor.redact(original)
            }
        }
        rebuiltEventCount = count
    }

    private val searchMutex = kotlinx.coroutines.sync.Mutex()

    /** Synchronize by content revision before querying; an index failure never means no memories. */
    suspend fun formatSearch(query: String, limit: Int = 5, scope: String = "all", cursor: String? = null): String {
        if (scope == "experience") return experiences.formatSearch(query, limit, cursor)
        searchMutex.lock()
        try {
            require(scope in setOf("all", "profile", "long_term", "episode")) { "Invalid memory scope" }
            rebuildHistoryIfNeeded()
            val episodes = dao.allCandidates().filter { it.version == MEMORY_CANDIDATE_VERSION }
            val memorySnapshot = durableMemoryStore.contextSnapshot().requireAvailable()
            val durable = memorySnapshot.entries
            val texts = durable.associate { it.ref to "${it.ref} ${it.category} ${it.content}" } +
                episodes.associate { "episode:${it.candidateHash}" to (it.searchText + "\n" + originalTexts["episode:${it.candidateHash}"].orEmpty()) }
            val indexed = texts.map { (ref, text) -> MemorySearchEntity(ref, ref.substringBefore(':'),
                tokenizeMemoryQuery(text).joinToString(" "), memoryHash(text)) }
            dao.synchronizeSearch(indexed)
            val revision = memoryHash(indexed.sortedBy { it.ref }.joinToString("\n") { "${it.ref}:${it.revision}" })
            val queryHash = memoryHash("$scope\u0000$query")
            val offset = if (cursor == null) 0 else {
                val parts = cursor.split(':')
                require(parts.size == 3 && parts[0] == revision && parts[1] == queryHash) { "MEMORY_CURSOR_STALE_OR_MISMATCHED" }
                parts[2].toInt().also { require(it >= 0) }
            }
            val tokens = tokenizeMemoryQuery(query)
            val matched = if (tokens.isEmpty()) indexed else dao.match(tokens.joinToString(" OR ") {
                "\"${it.replace("\"", "\"\"")}\""
            })
            val exactRefs = durable.filter {
                it.ref == query.trim() || it.ref.substringAfter(':') == query.trim()
            }.mapTo(mutableSetOf()) { it.ref }
            val hits = (indexed.filter { it.ref in exactRefs } + matched).distinctBy { it.ref }
                .filter { scope == "all" || it.scope == scope }
                .sortedWith(compareByDescending<MemorySearchEntity> { it.ref in exactRefs }
                    .thenByDescending { MemoryQueryRelevance.score(texts[it.ref].orEmpty().lowercase(), tokens) }
                    .thenBy { it.ref })
            val page = hits.drop(offset).take(limit.coerceIn(1, 10))
            val methodPage = if (scope == "all") experiences.formatSearch(query, limit, null) else null
            val next = offset + page.size
            return kotlinx.serialization.json.buildJsonObject {
                methodPage?.let { put("experience_page", Json.parseToJsonElement(it)) }
                put("index_revision", kotlinx.serialization.json.JsonPrimitive(revision))
                put("coverage", kotlinx.serialization.json.JsonPrimitive("all_indexed_records"))
                put("original_history_records", kotlinx.serialization.json.JsonPrimitive(originalTexts.size))
                put("forgotten_refs", kotlinx.serialization.json.JsonArray(memorySnapshot.retiredRefs.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("indexed_count", kotlinx.serialization.json.JsonPrimitive(indexed.size))
                put("matched_count", kotlinx.serialization.json.JsonPrimitive(hits.size))
                put("has_more", kotlinx.serialization.json.JsonPrimitive(next < hits.size))
                put("next_cursor", if (next < hits.size) kotlinx.serialization.json.JsonPrimitive("$revision:$queryHash:$next") else kotlinx.serialization.json.JsonNull)
                put("items", kotlinx.serialization.json.buildJsonArray {
                    page.forEach { hit -> add(kotlinx.serialization.json.buildJsonObject {
                        put("ref", kotlinx.serialization.json.JsonPrimitive(hit.ref))
                        put("scope", kotlinx.serialization.json.JsonPrimitive(hit.scope))
                        durable.firstOrNull { it.ref == hit.ref }?.let { entry ->
                            put("revision", kotlinx.serialization.json.JsonPrimitive(entry.revision))
                        }
                        put("summary", kotlinx.serialization.json.JsonPrimitive(texts[hit.ref].orEmpty().take(500)))
                    }) }
                })
            }.toString()
        } finally { searchMutex.unlock() }
    }

    private fun memoryHash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun formatCandidate(candidateHash: String): String? {
        if (!candidateHash.matches(Regex("^[0-9a-f]{64}$"))) return null
        val entity = dao.candidate(candidateHash) ?: return null
        if (entity.version != MEMORY_CANDIDATE_VERSION) return null
        val result = entity.toSearchResult()
        val originalSources = dao.sourcesForCandidate(candidateHash).mapNotNull { source ->
            eventStore.eventsForTurn(source.turnId).firstOrNull()?.let {
                "thread_id=${it.threadId}; turn_id=${source.turnId}; through_sequence=${source.sourceThroughSequence}"
            }
        }
        return buildString {
            appendLine("## Historical memory detail")
            appendLine("- ref=episode:${result.candidateHash}")
            appendLine("- Goal: ${result.userInput}")
            appendLine("- status=${result.status}; occurrences=${result.occurrences}")
            appendLine("- source_through_sequence=${result.sourceThroughSequence}")
            val candidate = json.decodeFromString<MemoryCandidate>(entity.candidateJson)
            appendLine("- source_thread_id=${candidate.threadId}; source_turn_id=${candidate.turnId}")
            appendLine("- Original source turns (${originalSources.size} available):")
            originalSources.forEach { appendLine("  - $it") }
            appendLine("- Route: ${result.steps.joinToString(" -> ") { it.toolName }.ifBlank { "No tool steps" }}")
            result.steps.take(8).forEach { step ->
                appendLine("  - ${step.toolName}: ${step.argumentsJson.take(240)}")
            }
            appendLine("- Result: ${result.result}")
            appendLine("- Promotion eligible: ${result.promotionEligible}")
        }.trim()
    }

    private fun MemoryCandidateEntity.toConsolidated(): ConsolidatedMemoryCandidate {
        val candidate = json.decodeFromString<MemoryCandidate>(candidateJson)
        return ConsolidatedMemoryCandidate(
            candidate = candidate,
            occurrences = occurrences,
            promotionEligible = status == "done" &&
                candidate.steps.isNotEmpty() &&
                candidate.verificationEvidenceRefs.isNotEmpty()
        )
    }

    private fun MemoryCandidateEntity.toSearchResult(): SessionSearchResult {
        val consolidated = toConsolidated()
        return SessionSearchResult(
            candidateHash = candidateHash,
            userInput = consolidated.candidate.userInput,
            status = status,
            occurrences = occurrences,
            steps = consolidated.candidate.steps.map { step ->
                SessionSearchStep(step.toolName, step.argumentsJson)
            },
            result = consolidated.candidate.resultText,
            promotionEligible = consolidated.promotionEligible,
            sourceThroughSequence = sourceThroughSequence,
            lastSeenAt = lastSeenAt
        )
    }

    private fun buildSearchText(candidate: MemoryCandidate): String = buildString {
        append(candidate.userInput)
        append(' ')
        append(candidate.resultText)
        append(' ')
        append(candidate.steps.joinToString(" ", transform = MemoryCandidateStep::toolName))
    }.lowercase()

    private fun relevance(searchText: String, tokens: List<String>): Int =
        MemoryQueryRelevance.score(searchText, tokens)

    private fun tokenize(text: String): List<String> = tokenizeMemoryQuery(text)

}

data class ConsolidatedMemoryCandidate(
    val candidate: MemoryCandidate,
    val occurrences: Int,
    val promotionEligible: Boolean
)

data class SessionSearchResult(
    val candidateHash: String,
    val userInput: String,
    val status: String,
    val occurrences: Int,
    val steps: List<SessionSearchStep>,
    val result: String,
    val promotionEligible: Boolean,
    val sourceThroughSequence: Long,
    val lastSeenAt: Long
)

data class SessionSearchStep(
    val toolName: String,
    val argumentsJson: String
)
