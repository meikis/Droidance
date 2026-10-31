// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.knowledge.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.knowledge.memory.ProgressiveMemoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Local knowledge reads; device activation and model execution belong to the caller. */
class KnowledgeToolRegistrar(
    private val registerTool: (Tool) -> Unit,
    private val searchMemories: suspend (String, Int, String, String?) -> String,
    private val expandEpisode: suspend (String) -> String?,
    private val expandMemory: (String) -> ProgressiveMemoryEntry?,
    private val recallSkill: (String) -> String?,
    private val readSession: suspend (String) -> ToolExecutionResult,
    private val expandExperience: suspend (String) -> String?,
    private val searchSkills: ((String, Int) -> List<com.droidance.core.knowledge.skill.SkillInfo>)? = null
) {
    fun registerAll() {
        registerSessionRead()
        registerSessionSearch()
        registerRecallSkill()
    }

    private fun registerSessionRead() = registerTool(object : Tool {
        override val name = "session_read"
        override val definition = ToolDefinition(name, "Read settled evidence by exact reference. Omit thread_id/turn_id for this task, or supply both from a historical search result. Historical evidence never establishes current device state.", mapOf(
            "thread_id" to ToolParameter("string", "Historical thread identity from session_search; requires turn_id.", false),
            "turn_id" to ToolParameter("string", "Historical turn identity from session_search; requires thread_id.", false),
            "mode" to ToolParameter("string", "Read mode.", false, listOf("list", "step", "image", "incidents", "programs", "artifact")),
            "ref" to ToolParameter("string", "Event, call, observation or attachment reference from this task.", false),
            "role" to ToolParameter("string", "Image role when reading a step reference.", false, listOf("pre", "post")),
            "offset" to ToolParameter("integer", "UTF-16 artifact cursor, default zero.", false),
            "after" to ToolParameter("integer", "Exclusive sequence cursor returned by a previous page.", false),
            "limit" to ToolParameter("integer", "Page size, 1..32; default 8.", false)
        ))
        override suspend fun execute(arguments: String): ToolExecutionResult = readSession(arguments)
    })

    private fun registerSessionSearch() = registerTool(object : Tool {
        override val name = "session_search"
        override val definition = ToolDefinition(
            name,
            "Search durable profiles, long-term memories and task history by key/ref or content, or scope=experience for reusable methods. index_revision identifies the search index; only a durable item revision may be used with update_memory. Expand exact episode:, profile:, long_term:, or experience:id:revision references. Historical methods require current verification.",
            mapOf(
                "query" to ToolParameter("string", "Search query; empty lists all indexed records. Reformulate queries when wording differs.", false),
                "ref" to ToolParameter("string", "Exact reference returned by the memory index or a prior search", false),
                "limit" to ToolParameter("integer", "Result count. Default: 5, max: 10", false),
                "scope" to ToolParameter("string", "Search corpus", false, listOf("all", "profile", "long_term", "episode", "experience")),
                "cursor" to ToolParameter("string", "Exact next_cursor from the same search; stale cursors require a new search", false)
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            val document = runCatching { Json.parseToJsonElement(arguments).jsonObject }
                .getOrElse { return ToolProtocolResult.invalidArguments() }
            val ref = runCatching { document["ref"]?.jsonPrimitive?.contentOrNull }
                .getOrElse { return ToolProtocolResult.invalidArgument("ref", "expected_string") }
            val query = runCatching { document["query"]?.jsonPrimitive?.contentOrNull }
                .getOrElse { return ToolProtocolResult.invalidArgument("query", "expected_string") }
            val limit = runCatching { document["limit"]?.jsonPrimitive?.intOrNull }
                .getOrElse { return ToolProtocolResult.invalidArgument("limit", "expected_integer") }
            return runCatching {
                if (ref != null) {
                    return@runCatching when (ref.substringBefore(':')) {
                        "experience" -> expandExperience(ref)?.let { detail ->
                            val payload = Json.parseToJsonElement(detail).jsonObject
                            ToolProtocolResult.success(detail).copy(metrics = mapOf("experience_detail_hit" to 1L,
                                "experience_version_mismatch" to if (payload["installed_version_status"]?.jsonPrimitive?.contentOrNull == "mismatched") 1L else 0L))
                        }
                            ?: ToolProtocolResult.notExecuted("EXPERIENCE_REFERENCE_UNAVAILABLE", retryable = false)
                        "episode" -> expandEpisode(ref.removePrefix("episode:"))?.let(ToolProtocolResult::success)
                            ?: ToolProtocolResult.notExecuted("HISTORICAL_CANDIDATE_NOT_FOUND", retryable = false)
                        "profile", "long_term" -> {
                            val entry = expandMemory(ref)
                                ?: return@runCatching ToolProtocolResult.notExecuted("MEMORY_REFERENCE_NOT_FOUND", retryable = false)
                            ToolProtocolResult.success(buildJsonObject {
                                put("ref", entry.ref)
                                put("category", entry.category)
                                put("content", entry.content)
                                put("updated_at", entry.updatedAt)
                                put("revision", entry.revision)
                                put("expires_at", entry.expiresAt)
                            }.toString())
                        }
                        else -> ToolProtocolResult.invalidArgument("ref", "invalid_memory_ref")
                    }
                }
                val scope = document["scope"]?.jsonPrimitive?.contentOrNull ?: "all"
                val cursor = document["cursor"]?.jsonPrimitive?.contentOrNull
                ToolProtocolResult.success(searchMemories(query.orEmpty(), limit ?: 5, scope, cursor))
            }.getOrElse {
                if (it is CancellationException) throw it
                ToolProtocolResult.failed("SESSION_SEARCH_FAILED", retryable = true, it)
            }
        }
    })

    private fun registerRecallSkill() = registerTool(object : Tool {
        override val name = "recall_skill"
        override val definition = ToolDefinition(
            name,
            "Search or load installed Skills. Use op=\"search\" or query to discover skills, and op=\"load\" or skill to load full instructions.",
            mapOf(
                "op" to ToolParameter("string", "Operation: 'search' to find matching skills by keyword, or 'load' to read the full markdown body of a skill.", false, listOf("search", "load")),
                "query" to ToolParameter("string", "Search keyword when searching skills.", false),
                "skill" to ToolParameter("string", "Exact catalog name or declared alias to load. @name and /skill name are accepted.", false),
                "limit" to ToolParameter("integer", "Maximum number of results when searching (default 5).", false)
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            val op = arguments.jsonString("op")
            val skill = arguments.jsonString("skill")
            val query = arguments.jsonString("query")
            val limit = runCatching { Json.parseToJsonElement(arguments).jsonObject["limit"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 5

            val effectiveOp = when {
                op == "search" -> "search"
                op == "load" -> "load"
                !skill.isNullOrBlank() -> "load"
                !query.isNullOrBlank() -> "search"
                else -> return ToolProtocolResult.missingArgument("skill")
            }

            return if (effectiveOp == "search") {
                val searchQuery = query.orEmpty()
                val results = searchSkills?.invoke(searchQuery, limit).orEmpty()
                val json = buildJsonObject {
                    put("query", kotlinx.serialization.json.JsonPrimitive(searchQuery))
                    put("count", kotlinx.serialization.json.JsonPrimitive(results.size))
                    put("skills", kotlinx.serialization.json.JsonArray(results.map {
                        buildJsonObject {
                            put("name", kotlinx.serialization.json.JsonPrimitive(it.name))
                            put("version", kotlinx.serialization.json.JsonPrimitive(it.version))
                            put("description", kotlinx.serialization.json.JsonPrimitive(it.description))
                            put("revision", it.revision)
                            it.recentFeedback?.let { feedback -> put("recent_assessments", feedback.toJson()) }
                        }
                    }))
                }
                ToolProtocolResult.success(json.toString())
            } else {
                val targetSkill = skill ?: query ?: return ToolProtocolResult.missingArgument("skill")
                runCatching {
                    recallSkill(targetSkill)?.let(ToolProtocolResult::success)
                        ?: ToolProtocolResult.notExecuted("SKILL_NOT_FOUND", retryable = false)
                }.getOrElse {
                    if (it is CancellationException) throw it
                    ToolProtocolResult.failed("SKILL_RECALL_FAILED", retryable = true, it)
                }
            }
        }
    })

    private fun String.jsonString(key: String): String? = runCatching {
        Json.parseToJsonElement(this).jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()

}
