// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.knowledge.tools.*

import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.knowledge.memory.ProgressiveMemoryEntry
import com.droidance.core.knowledge.memory.ProgressiveMemoryScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeToolRegistrarTest {
    @Test
    fun exactProfileAndLongTermRefsReturnCompleteContentWithoutCrossLoading() = runBlocking {
        val profileContent = "profile-content-" + "p".repeat(700)
        val longTermContent = "long-term-content-" + "l".repeat(900)
        val memoryReads = mutableListOf<String>()
        val episodeReads = mutableListOf<String>()
        val searchCalls = mutableListOf<List<String?>>()
        val tools = tools(
            searchMemories = { query, limit, scope, cursor ->
                searchCalls += listOf(query, limit.toString(), scope, cursor)
                error("query search must not run for an exact memory ref")
            },
            expandEpisode = { ref ->
                episodeReads += ref
                error("episode expansion must not run for a memory ref")
            },
            expandMemory = { ref ->
                memoryReads += ref
                when (ref) {
                    "profile:person" -> ProgressiveMemoryEntry(
                        ref = ref,
                        scope = ProgressiveMemoryScope.PROFILE,
                        category = "identity",
                        content = profileContent,
                        updatedAt = "2026-09-05T01:02:03Z"
                    )
                    "long_term:project" -> ProgressiveMemoryEntry(
                        ref = ref,
                        scope = ProgressiveMemoryScope.LONG_TERM,
                        category = "project",
                        content = longTermContent,
                        updatedAt = "2026-09-05T04:05:06Z"
                    )
                    else -> null
                }
            }
        )
        val sessionSearch = tools.getValue("session_search")

        val profile = sessionSearch.execute("""{"ref":"profile:person"}""")
        val longTerm = sessionSearch.execute("""{"ref":"long_term:project"}""")

        assertEquals(ToolExecutionStatus.SUCCEEDED, profile.executionStatus)
        assertEquals(ToolExecutionStatus.SUCCEEDED, longTerm.executionStatus)
        assertEquals(profileContent, field(profile, "content"))
        assertEquals("profile:person", field(profile, "ref"))
        assertEquals("identity", field(profile, "category"))
        assertEquals("2026-09-05T01:02:03Z", field(profile, "updated_at"))
        assertEquals(longTermContent, field(longTerm, "content"))
        assertEquals("long_term:project", field(longTerm, "ref"))
        assertEquals("project", field(longTerm, "category"))
        assertEquals("2026-09-05T04:05:06Z", field(longTerm, "updated_at"))
        assertEquals(listOf("profile:person", "long_term:project"), memoryReads)
        assertTrue(episodeReads.isEmpty())
        assertTrue(searchCalls.isEmpty())
    }

    @Test
    fun exactEpisodeRefUsesOnlyTheMatchingEpisodeExpansion() = runBlocking {
        val episodeReads = mutableListOf<String>()
        val episodeBodies = mapOf(
            "candidate-42" to "full episode body for candidate 42",
            "candidate-other" to "other episode body that must not be loaded"
        )
        val tools = tools(
            expandEpisode = { ref ->
                episodeReads += ref
                episodeBodies[ref]
            },
            expandMemory = { error("memory expansion must not run for an episode ref") },
            searchMemories = { _, _, _, _ -> error("query search must not run for an exact episode ref") }
        )

        val result = tools.getValue("session_search").execute(
            """{"ref":"episode:candidate-42","query":"ignored","limit":10}"""
        )

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("full episode body for candidate 42", result.outputText)
        assertEquals(listOf("candidate-42"), episodeReads)
    }

    @Test
    fun queryWithoutRefUsesSearchAndDoesNotExpandAnyStoredBody() = runBlocking {
        val searchCalls = mutableListOf<List<String?>>()
        val tools = tools(
            searchMemories = { query, limit, scope, cursor ->
                searchCalls += listOf(query, limit.toString(), scope, cursor)
                "bounded search result"
            },
            expandEpisode = { error("episode body must not load for a query") },
            expandMemory = { error("memory body must not load for a query") }
        )

        val result = tools.getValue("session_search").execute(
            """{"query":"find this history","limit":7}"""
        )

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("bounded search result", result.outputText)
        assertEquals(listOf(listOf("find this history", "7", "all", null)), searchCalls)
    }

    @Test
    fun queryForwardsScopeAndCursorToTheUnifiedMemorySearch() = runBlocking {
        val searchCalls = mutableListOf<List<String?>>()
        val tools = tools(
            searchMemories = { query, limit, scope, cursor ->
                searchCalls += listOf(query, limit.toString(), scope, cursor)
                "page"
            },
            expandEpisode = { error("episode body must not load for a query") },
            expandMemory = { error("memory body must not load for a query") }
        )

        val result = tools.getValue("session_search").execute(
            """{"query":"language","limit":3,"scope":"profile","cursor":"revision:query:10"}"""
        )

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("page", result.outputText)
        assertEquals(listOf(listOf("language", "3", "profile", "revision:query:10")), searchCalls)
    }

    @Test
    fun unknownRefsAndMissingResultsReturnStructuredNotExecutedFailures() = runBlocking {
        val tools = tools(
            searchMemories = { _, _, _, _ -> error("search must not run for a ref") },
            expandEpisode = { ref -> if (ref == "missing") null else error("unexpected episode ref") },
            expandMemory = { ref -> if (ref.endsWith(":missing")) null else error("unexpected memory ref") }
        )
        val sessionSearch = tools.getValue("session_search")

        val unknown = sessionSearch.execute("""{"ref":"unknown:value"}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, unknown.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", unknown.diagnosticCode)
        assertEquals("true", field(unknown, "retryable"))
        val unknownDetails = Json.parseToJsonElement(unknown.outputText).jsonObject.getValue("details").jsonObject
        assertEquals("ref", unknownDetails.getValue("parameter").jsonPrimitive.content)
        assertEquals("invalid_memory_ref", unknownDetails.getValue("reason").jsonPrimitive.content)

        listOf(
            """{"ref":"episode:missing"}""" to "HISTORICAL_CANDIDATE_NOT_FOUND",
            """{"ref":"profile:missing"}""" to "MEMORY_REFERENCE_NOT_FOUND",
            """{"ref":"long_term:missing"}""" to "MEMORY_REFERENCE_NOT_FOUND"
        ).forEach { (arguments, expectedCode) ->
            val result = sessionSearch.execute(arguments)
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
            assertEquals(expectedCode, result.diagnosticCode)
            assertEquals("false", field(result, "retryable"))
            assertTrue(Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject.isEmpty())
        }
    }

    @Test
    fun episodeAndSearchCancellationArePropagatedWithoutConversion() {
        val episodeCancellation = CancellationException("episode read cancelled")
        val episodeTools = tools(
            expandEpisode = { throw episodeCancellation },
            searchMemories = { _, _, _, _ -> error("search must not run") },
            expandMemory = { error("memory must not run") }
        )
        assertSame(episodeCancellation, assertThrows(CancellationException::class.java) {
            runBlocking {
                episodeTools.getValue("session_search").execute("""{"ref":"episode:candidate"}""")
            }
        })

        val searchCancellation = CancellationException("search cancelled")
        val searchTools = tools(
            searchMemories = { _, _, _, _ -> throw searchCancellation },
            expandEpisode = { error("episode must not run") },
            expandMemory = { error("memory must not run") }
        )
        assertSame(searchCancellation, assertThrows(CancellationException::class.java) {
            runBlocking {
                searchTools.getValue("session_search").execute("""{"query":"history"}""")
            }
        })
    }

    @Test
    fun recallSkillCallsOnlyTheRequestedSkillProvider() = runBlocking {
        val recalledSkills = mutableListOf<String>()
        val tools = tools(
            searchMemories = { _, _, _, _ -> error("session search must not run for recall_skill") },
            expandEpisode = { error("episode expansion must not run for recall_skill") },
            expandMemory = { error("memory expansion must not run for recall_skill") },
            recallSkill = { skill ->
                recalledSkills += skill
                "full skill body for $skill"
            }
        )

        val result = tools.getValue("recall_skill").execute("""{"skill":"demo-skill"}""")

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("full skill body for demo-skill", result.outputText)
        assertEquals(listOf("demo-skill"), recalledSkills)
    }

    @Test
    fun successfulSkillReadPreservesSuccessStatusWhenBodyContainsFailureMarkers() = runBlocking {
        val body = "[SKILL_FAILED]\n{\"execution_status\":\"FAILED\",\"code\":\"SKILL_STEP_FAILED\"}"
        val tools = tools(
            searchMemories = { _, _, _, _ -> error("session search must not run for recall_skill") },
            expandEpisode = { error("episode expansion must not run for recall_skill") },
            expandMemory = { error("memory expansion must not run for recall_skill") },
            recallSkill = { body }
        )

        val result = tools.getValue("recall_skill").execute("""{"skill":"demo-skill"}""")

        assertEquals(body, result.outputText)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(ToolEvidenceStatus.NOT_REQUIRED, result.evidenceStatus)
        assertNull(result.diagnosticCode)
    }

    @Test
    fun recallSkillSupportsSearchOperation() = runBlocking {
        val sampleSkill = com.droidance.core.knowledge.skill.SkillInfo(
            id = "wifi-toggle",
            name = "Wi-Fi Toggle",
            version = 2,
            description = "Turns Wi-Fi on or off via shell settings.",
            rawContent = "markdown body"
        )
        val tools = tools(
            searchSkills = { query, limit ->
                if (query.contains("wifi")) listOf(sampleSkill) else emptyList()
            }
        )

        val result = tools.getValue("recall_skill").execute("""{"op":"search","query":"wifi"}""")

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(result.outputText.contains("Wi-Fi Toggle"))
        assertTrue(result.outputText.contains("Turns Wi-Fi on or off"))
    }

    @Test
    fun recallSkillSupportsLoadOperationExplicitly() = runBlocking {
        val tools = tools(
            recallSkill = { skill -> "full body of $skill" }
        )

        val result = tools.getValue("recall_skill").execute("""{"op":"load","skill":"wifi-toggle"}""")

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("full body of wifi-toggle", result.outputText)
    }

    private fun tools(
        searchMemories: suspend (String, Int, String, String?) -> String = { _, _, _, _ -> error("unexpected session search") },
        expandEpisode: suspend (String) -> String? = { error("unexpected episode expansion") },
        expandMemory: (String) -> ProgressiveMemoryEntry? = { error("unexpected memory expansion") },
        recallSkill: (String) -> String? = { error("unexpected skill recall") },
        searchSkills: ((String, Int) -> List<com.droidance.core.knowledge.skill.SkillInfo>)? = null
    ): Map<String, Tool> {
        val registered = mutableMapOf<String, Tool>()
        KnowledgeToolRegistrar(
            registerTool = { tool -> registered[tool.name] = tool },
            searchMemories = searchMemories,
            expandEpisode = expandEpisode,
            expandMemory = expandMemory,
            recallSkill = recallSkill,
            readSession = { error("unexpected session read") },
            expandExperience = { error("unexpected experience expansion") },
            searchSkills = searchSkills
        ).registerAll()
        return registered
    }

    private fun field(result: ToolExecutionResult, name: String): String = Json.parseToJsonElement(result.outputText)
        .jsonObject
        .getValue(name)
        .jsonPrimitive
        .content
}
