// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.prompt

import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StablePrefixTest {
    @Test fun conditionalAndLengthConstraintsAreFrozenAndChangeTheIdentity() {
        val allowed = mutableMapOf("op" to mutableListOf("edit"))
        val field = ToolParameter("string", "Value", allowedWhen = allowed, minLength = 2, deferredValidation = true)
        fun build(value: ToolParameter) = StablePrefixBuilder.build("system", listOf(tool("edit", mapOf("value" to value))), emptyList())
        val prefix = build(field)
        assertNotEquals(prefix.hash, build(field.copy(minLength = 3)).hash)
        assertNotEquals(prefix.hash, build(field.copy(allowedWhen = emptyMap())).hash)
        assertNotEquals(prefix.hash, build(field.copy(deferredValidation = false)).hash)
        allowed.getValue("op").clear()
        assertEquals(listOf("edit"), prefix.tools.single().parameters.getValue("value").allowedWhen.getValue("op"))
    }
    @Test
    fun map_and_collection_order_do_not_change_the_prefix() {
        val first = StablePrefixBuilder.build(
            systemPrompt = "You are Droidance.",
            tools = listOf(tool("tap", linkedMapOf("y" to intParameter(), "x" to intParameter())), tool("observe")),
            indexEntries = listOf(memory("memory-b"), skill("skill-a"))
        )
        val second = StablePrefixBuilder.build(
            systemPrompt = "You are Droidance.",
            tools = listOf(tool("observe"), tool("tap", linkedMapOf("x" to intParameter(), "y" to intParameter()))),
            indexEntries = listOf(skill("skill-a"), memory("memory-b"))
        )

        assertEquals(first.canonicalJson, second.canonicalJson)
        assertEquals(first.hash, second.hash)
    }

    @Test
    fun tool_schema_and_static_index_version_change_the_identity_hash() {
        val baseline = StablePrefixBuilder.build(
            "You are Droidance.",
            listOf(tool("observe")),
            listOf(skill("skill-a", version = "1"))
        )
        val changedTool = StablePrefixBuilder.build(
            "You are Droidance.",
            listOf(tool("observe", mapOf("detail" to intParameter()))),
            listOf(skill("skill-a", version = "1"))
        )
        val changedIndex = StablePrefixBuilder.build(
            "You are Droidance.",
            listOf(tool("observe")),
            listOf(skill("skill-a", version = "2"))
        )

        assertNotEquals(baseline.hash, changedTool.hash)
        assertNotEquals(baseline.hash, changedIndex.hash)
    }

    @Test
    fun duplicate_tool_or_index_keys_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StablePrefixBuilder.build("system", listOf(tool("tap"), tool("tap")), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            StablePrefixBuilder.build("system", emptyList(), listOf(skill("same"), skill("same")))
        }
    }

    @Test
    fun returned_collections_cannot_mutate_the_frozen_prefix_or_its_hash() {
        val prefix = StablePrefixBuilder.build(
            "You are Droidance.",
            listOf(
                tool(
                    "tap",
                    linkedMapOf(
                        "x" to ToolParameter(
                            "integer",
                            "coordinate",
                            enumValues = mutableListOf("1", "2")
                        )
                    )
                )
            ),
            listOf(skill("skill-a"))
        )
        val originalHash = prefix.hash
        val originalJson = prefix.canonicalJson

        runCatching { (prefix.tools as MutableList).clear() }
        runCatching { (prefix.indexEntries as MutableList).clear() }
        runCatching { (prefix.tools.single().parameters as MutableMap).clear() }
        runCatching {
            (prefix.tools.single().parameters.getValue("x").enumValues as MutableList).clear()
        }

        assertEquals(originalHash, prefix.hash)
        assertEquals(originalJson, prefix.canonicalJson)
        assertEquals(listOf("tap"), prefix.tools.map { it.name })
        assertEquals(listOf("1", "2"), prefix.tools.single().parameters.getValue("x").enumValues)
        assertEquals(listOf("skill-a"), prefix.indexEntries.map { it.name })
    }

    private fun tool(
        name: String,
        parameters: Map<String, ToolParameter> = emptyMap()
    ) = ToolDefinition(name, "$name description", parameters)

    private fun intParameter() = ToolParameter("integer", "coordinate")

    private fun skill(name: String, version: String = "1") =
        StableIndexEntry(StableIndexKind.SKILL, name, "$name description", version)

    private fun memory(name: String, version: String = "1") =
        StableIndexEntry(StableIndexKind.MEMORY, name, "$name description", version)
}
