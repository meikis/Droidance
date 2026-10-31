// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import com.droidance.core.agent.ports.AgentConfiguration
import com.droidance.settings.ExecutionSettingsRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Adapts persisted settings and Markdown to the platform-independent coordinator. */
class AndroidAgentConfiguration @Inject constructor(
    private val rules: RuleConfigManager,
    private val settings: ExecutionSettingsRepository,
    private val language: LanguageManager
) : AgentConfiguration {
    override fun readMarkdownSection(fileName: String, heading: String): String = rules.readMarkdownSection(fileName, heading)
    override fun harnessPolicy(): HarnessPolicy = rules.harnessPolicy()
    override fun harnessPrompts(): HarnessPromptPolicy = rules.harnessPrompts()
    override fun toolContracts(): ToolContractsDocument = rules.toolContracts()
    override fun message(key: String): String = rules.message(key)
    override fun publicFallback(key: String): String = rules.publicFallback(key)
    override fun taskWallMinutes(): Int = settings.taskWallMinutes()
    override fun outputLanguage(): String = language.resolved().promptName
    override fun operatorSystemPrompt(): String = buildOperatorSystemPrompt(rules::readMarkdownSection, outputLanguage(), rules.harnessPolicy().screenActionBatch)
}

internal fun buildOperatorSystemPrompt(readSection: (String, String) -> String, userLanguage: String,
    batchPolicy: ScreenActionBatchPolicy): String =
    (listOf("operator_behavior", "public_metadata", "output_language").map { "agent_core.md" to it } + listOf(
        "device_tools.md" to "android_read_description",
        "device_tools.md" to "screen_act_description",
        "device_tools.md" to "device_inventory_contract",
        "completion_recovery.md" to "batch_contract",
        "completion_recovery.md" to "contract",
        "completion_recovery.md" to "delivery_check",
        "workspace.md" to "collection_contract"
    )).joinToString("\n\n") { (file, heading) ->
        readSection(file, heading).replace("{language}", userLanguage)
            .replace("{batch_policy}", Json.encodeToString(batchPolicy)).trim()
    }
