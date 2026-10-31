// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.agent.event.ToolExecutionResult

enum class ToolCategory {
    TOUCH, INPUT, PERCEPTION, NAVIGATION, NOTIFICATION,
    MEMORY, BROWSER, SYSTEM, COMMUNICATION, FILE, SCRIPT, META, COMPOUND
}

enum class ToolTier { CORE, EXTENDED }

interface Tool {
    val name: String
    val definition: ToolDefinition
    val tier: ToolTier get() = ToolTier.EXTENDED
    val category: ToolCategory get() = ToolCategory.SYSTEM
    suspend fun execute(arguments: String): ToolExecutionResult
}
