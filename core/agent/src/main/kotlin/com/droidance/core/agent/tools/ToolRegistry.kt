// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/**
 * Standard registry for tools.
 * Enforces unique tool names and explicitly rejects duplicate registration conflicts.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        val existing = tools[tool.name]
        if (existing != null && existing !== tool) {
            throw IllegalArgumentException(
                "Duplicate tool registration conflict for '${tool.name}': " +
                    "existing instance ${existing::class.java.name}, new instance ${tool::class.java.name}"
            )
        }
        tools[tool.name] = tool
    }

    fun getTool(name: String): Tool? = tools[name]

    fun allTools(): Map<String, Tool> = tools.toMap()

    fun hasTool(name: String): Boolean = tools.containsKey(name)

    fun clear() {
        tools.clear()
    }
}
