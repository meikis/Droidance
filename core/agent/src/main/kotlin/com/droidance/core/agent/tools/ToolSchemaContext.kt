// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.Serializable

/** Request documentation only. Executable schemas and full tool help retain their original contracts. */
@Serializable
data class ToolSchemaContextPolicy(
    val toolDescriptions: Map<String, String> = emptyMap(),
    /** Exact tool and parameter paths; [] addresses array items and {} additional property values. */
    val parameterDescriptions: Map<String, Map<String, String>> = emptyMap(),
    /**
     * Tools that offer turn metadata parameters (task title, conversation title, deadline and
     * the next reasoning effort). Null offers them on every tool; listing the tools that can
     * open or continue a task keeps the capability without repeating the schema everywhere.
     */
    val turnMetadataTools: Set<String>? = null,
    /**
     * Tools whose parameter descriptions are left out of every request. Types, enums and
     * required fields stay binding, and workspace help returns the full documentation, so
     * rarely used tools stay available without resending their prose each round.
     */
    val helpOnlyParameterTools: Set<String> = emptySet()
) {
    init {
        require(turnMetadataTools == null || turnMetadataTools.none(String::isBlank))
        require(helpOnlyParameterTools.none(String::isBlank))
        require(helpOnlyParameterTools.none(parameterDescriptions::containsKey))
        require(toolDescriptions.values.none(String::isBlank))
        require(parameterDescriptions.values.all { descriptions ->
            descriptions.keys.none(String::isBlank) && descriptions.values.none(String::isBlank)
        })
    }
}

object ToolSchemaContext {
    /** Applied to a tool's own parameters, before shared protocol parameters are added. */
    fun omitParameterDocumentation(tools: List<ToolDefinition>, policy: ToolSchemaContextPolicy): List<ToolDefinition> {
        fun undocumented(parameter: ToolParameter): ToolParameter = parameter.copy(
            description = "",
            items = parameter.items?.let(::undocumented),
            properties = parameter.properties?.mapValues { undocumented(it.value) },
            additionalPropertySchema = parameter.additionalPropertySchema?.let(::undocumented)
        )
        return tools.map { tool ->
            if (tool.name in policy.helpOnlyParameterTools) tool.copy(parameters = tool.parameters.mapValues { undocumented(it.value) })
            else tool
        }
    }

    fun compact(tools: List<ToolDefinition>, policy: ToolSchemaContextPolicy): List<ToolDefinition> =
        tools.map { tool ->
            val descriptions = policy.parameterDescriptions[tool.name].orEmpty()
            val visited = mutableSetOf<String>()
            fun compactParameter(path: String, parameter: ToolParameter): ToolParameter {
                visited += path
                return parameter.copy(
                    description = descriptions[path] ?: parameter.description,
                    items = parameter.items?.let { compactParameter("$path[]", it) },
                    properties = parameter.properties?.mapValues { (name, child) ->
                        compactParameter("$path.$name", child)
                    },
                    additionalPropertySchema = parameter.additionalPropertySchema?.let {
                        compactParameter("$path{}", it)
                    }
                )
            }
            val parameters = if (descriptions.isEmpty()) tool.parameters else tool.parameters.mapValues { (name, parameter) ->
                compactParameter(name, parameter)
            }
            require(descriptions.keys.all { it in visited }) { "Unknown parameter documentation path for ${tool.name}" }
            tool.copy(description = policy.toolDescriptions[tool.name] ?: tool.description, parameters = parameters)
        }
}
