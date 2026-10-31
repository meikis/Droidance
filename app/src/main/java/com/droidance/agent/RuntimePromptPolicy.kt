// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.config.RuleConfigManager
import javax.inject.Inject

class RuntimePromptPolicy @Inject constructor(
    private val ruleConfigManager: RuleConfigManager
) {
    fun guidance(
        heading: String,
        replacements: Map<String, String> = emptyMap()
    ): String = guidanceFrom(
        fileName = "utility_prompts.md",
        heading = heading,
        replacements = replacements
    )

    fun guidanceFrom(
        fileName: String,
        heading: String,
        replacements: Map<String, String> = emptyMap()
    ): String {
        return replacements.entries.fold(
            ruleConfigManager.readMarkdownSection(fileName, heading)
        ) { content, (key, value) ->
            content.replace("{{$key}}", value)
        }.trim()
    }

    fun memoryBehaviorBlock(): String = guidanceFrom(
        fileName = "memory_policy.md",
        heading = "runtime_memory_contract"
    )

    fun agentSoulBlock(agentSoul: String): String {
        if (agentSoul.isBlank()) return ""
        return guidanceFrom(
            fileName = "memory_policy.md",
            heading = "agent_soul_context",
            replacements = mapOf("agent_soul" to agentSoul.trim())
        )
    }
}
