// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.prompt

import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

private fun freezeToolParameter(parameter: ToolParameter): ToolParameter = parameter.copy(
    enumValues = parameter.enumValues?.toList(),
    requiredWhen = parameter.requiredWhen.toSortedMap().mapValues { it.value.toList() },
    allowedWhen = parameter.allowedWhen.toSortedMap().mapValues { it.value.toList() },
    items = parameter.items?.let(::freezeToolParameter),
    properties = parameter.properties?.toSortedMap()?.mapValues { (_, nested) ->
        freezeToolParameter(nested)
    },
    additionalPropertySchema = parameter.additionalPropertySchema?.let(::freezeToolParameter)
)

const val STABLE_PREFIX_FORMAT_VERSION = 2

enum class StableIndexKind {
    SKILL,
    MEMORY
}

data class StableIndexEntry(
    val kind: StableIndexKind,
    val name: String,
    val description: String,
    val version: String
) {
    init {
        require(name.isNotBlank()) { "Stable index name cannot be blank" }
        require(description.isNotBlank()) { "Stable index description cannot be blank" }
        require(version.isNotBlank()) { "Stable index version cannot be blank" }
    }
}

class StablePrefix internal constructor(
    val formatVersion: Int,
    val systemPrompt: String,
    tools: List<ToolDefinition>,
    indexEntries: List<StableIndexEntry>,
    val canonicalJson: String,
    val hash: String
) {
    private val frozenTools = tools.map(::copyTool)
    private val frozenIndexEntries = indexEntries.map(StableIndexEntry::copy)

    val tools: List<ToolDefinition>
        get() = frozenTools.map(::copyTool)

    val indexEntries: List<StableIndexEntry>
        get() = frozenIndexEntries.map(StableIndexEntry::copy)

    private companion object {
        fun copyTool(tool: ToolDefinition): ToolDefinition = tool.copy(
            parameters = tool.parameters.toSortedMap().mapValues { (_, parameter) ->
                freezeToolParameter(parameter)
            }.toMap()
        )
    }
}

/**
 * Builds the immutable prefix shared by every request in one model session identity.
 * Dynamic task, capability, observation and screenshot data are intentionally absent.
 */
object StablePrefixBuilder {
    fun build(
        systemPrompt: String,
        tools: Collection<ToolDefinition>,
        indexEntries: Collection<StableIndexEntry>
    ): StablePrefix {
        require(systemPrompt.isNotBlank()) { "Stable system prompt cannot be blank" }
        val sortedTools = tools.map(::freezeTool).sortedBy(ToolDefinition::name)
        require(sortedTools.none { it.name.isBlank() }) { "Stable tool name cannot be blank" }
        require(sortedTools.none { it.description.isBlank() }) { "Stable tool description cannot be blank" }
        require(sortedTools.map(ToolDefinition::name).distinct().size == sortedTools.size) {
            "Stable tool names must be unique"
        }
        sortedTools.forEach { tool ->
            require(tool.parameters.keys.none(String::isBlank)) { "Stable tool parameter name cannot be blank" }
            tool.parameters.values.forEach { parameter ->
                // A blank description is omitted from the schema; help-only tools rely on that.
                require(parameter.type.isNotBlank()) { "Stable tool parameter type cannot be blank" }
                require(parameter.enumValues.orEmpty().none(String::isBlank)) {
                    "Stable tool enum value cannot be blank"
                }
                require(parameter.enumValues.orEmpty().distinct().size == parameter.enumValues.orEmpty().size) {
                    "Stable tool enum values must be unique"
                }
            }
        }
        val sortedIndexes = indexEntries.sortedWith(
            compareBy(StableIndexEntry::kind, StableIndexEntry::name, StableIndexEntry::version)
        )
        require(sortedIndexes.map { it.kind to it.name }.distinct().size == sortedIndexes.size) {
            "Stable index kind/name pairs must be unique"
        }

        val canonical = buildJsonObject {
            put("formatVersion", STABLE_PREFIX_FORMAT_VERSION)
            put("systemPrompt", systemPrompt)
            put("tools", JsonArray(sortedTools.map(::toolJson)))
            put("indexes", JsonArray(sortedIndexes.map(::indexJson)))
        }.toString()
        return StablePrefix(
            formatVersion = STABLE_PREFIX_FORMAT_VERSION,
            systemPrompt = systemPrompt,
            tools = sortedTools,
            indexEntries = sortedIndexes,
            canonicalJson = canonical,
            hash = sha256Hex(canonical)
        )
    }

    private fun toolJson(tool: ToolDefinition): JsonElement = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", buildJsonObject {
            tool.parameters.toSortedMap().forEach { (name, parameter) ->
                put(name, parameterJson(parameter))
            }
        })
    }

    private fun parameterJson(parameter: ToolParameter): JsonElement = buildJsonObject {
        put("type", parameter.type)
        put("description", parameter.description)
        put("required", parameter.required)
        parameter.enumValues?.let { values ->
            put("enumValues", JsonArray(values.map(::JsonPrimitive)))
        }
        parameter.items?.let { put("items", parameterJson(it)) }
        parameter.properties?.let { properties ->
            put("properties", buildJsonObject {
                properties.toSortedMap().forEach { (name, nested) -> put(name, parameterJson(nested)) }
            })
        }
        if (parameter.requiredWhen.isNotEmpty()) put("requiredWhen", buildJsonObject {
            parameter.requiredWhen.toSortedMap().forEach { (selector, values) ->
                put(selector, JsonArray(values.map(::JsonPrimitive)))
            }
        })
        parameter.additionalProperties?.let { put("additionalProperties", it) }
        parameter.additionalPropertySchema?.let { put("additionalPropertySchema", parameterJson(it)) }
        put("deferredValidation", parameter.deferredValidation)
        parameter.minLength?.let { put("minLength", it) }
        parameter.minItems?.let { put("minItems", it) }
        if (parameter.allowedWhen.isNotEmpty()) put("allowedWhen", buildJsonObject {
            parameter.allowedWhen.toSortedMap().forEach { (selector, values) ->
                put(selector, JsonArray(values.map(::JsonPrimitive)))
            }
        })
    }

    private fun indexJson(entry: StableIndexEntry): JsonElement = buildJsonObject {
        put("kind", entry.kind.name)
        put("name", entry.name)
        put("description", entry.description)
        put("version", entry.version)
    }

    private fun freezeTool(tool: ToolDefinition): ToolDefinition = tool.copy(
        parameters = tool.parameters.toSortedMap().mapValues { (_, parameter) ->
            freezeToolParameter(parameter)
        }.toMap()
    )
}

fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
