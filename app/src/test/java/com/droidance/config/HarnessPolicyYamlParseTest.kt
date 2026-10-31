// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import com.droidance.core.agent.ObservationRecoveryPolicy
import com.droidance.core.agent.StoppedTaskReplyPolicy
import com.droidance.core.agent.harness.ToolContractConfiguration
import com.droidance.core.agent.workspace.TaskExecutionPolicy
import com.droidance.core.agent.workspace.WorkspaceContextPolicy
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/**
 * App 启动时 RuleConfigManager.initialize() 会解析并反序列化 rules/ 下的 YAML，
 * 任何语法或结构错误（例如未引用标量里含 ": " 被解析成嵌套 map）都会让 App 直接闪退。
 * 本测试用与 RuleConfigManager 完全相同的解析管线在 JVM 上验证，把这类问题挡在装机前。
 */
class HarnessPolicyYamlParseTest {

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }
    private val yaml = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .build()
    )

    @Test
    fun harnessPolicyYamlParsesAndValidates() {
        val policy = loadYaml("harness_policy.yaml", HarnessPolicy.serializer())
        policy.validate()
        // Tunable values may change; their relations are the contract.
        val thresholds = policy.runtimeThresholds
        assertTrue(thresholds.llmRecoveryTimeoutMs >= thresholds.llmRoundTimeoutMs)
        assertTrue(thresholds.llmTransientRetryBackoffMs in 1 until thresholds.llmRoundTimeoutMs)
        assertTrue(thresholds.llmFirstEventTimeoutMs in 1..thresholds.llmRoundTimeoutMs)
        assertTrue(thresholds.llmStreamIdleTimeoutMs in 1..thresholds.llmRoundTimeoutMs)
        // A first-output deadline would interrupt continuing reasoning.
        assertEquals(0L, thresholds.llmFirstOutputTimeoutMs)
        assertTrue(policy.toolSafety.confirmationTools.isNotEmpty())
    }

    @Test
    fun harnessPromptsYamlParsesAndValidates() {
        val policy = loadYaml("harness_prompts.yaml", HarnessPromptPolicy.serializer())
        policy.validate()
        assertFalse(policy.messages.containsKey("round_budget_nearing"))
        assertFalse(policy.messages.containsKey("screen_unchanged_frame"))
        assertFalse(policy.messages.containsKey("deeplink_route_required"))
        assertTrue(policy.communicationContract.screenEvidenceParameter.isNotBlank())
        assertTrue(policy.messages.containsKey("context_limit_exceeded"))
    }

    @Test
    fun toolContractsYamlParsesAndValidates() {
        val document = loadYaml("tool_contracts.yaml", ToolContractsDocument.serializer())
        document.validate()
        // The YAML is the static source of truth, but every static operator tool must
        // still agree with the protocol's backend requirement before a round is built.
        ToolContractConfiguration.parse(document.toolContracts)
        assertTrue(document.toolContracts.getValue("android_read").requiresResultBeforeAction)
        assertTrue(document.toolContracts.getValue("recall_skill").requiresResultBeforeAction)
        assertTrue(document.toolContracts.getValue("session_search").requiresResultBeforeAction)
        assertFalse(document.toolContracts.getValue("screen_act").requiresResultBeforeAction)
        assertFalse(document.toolContracts.getValue("wait").requiresResultBeforeAction)
    }

    @Test
    fun runtimeMarkdownPolicySectionsStillDecodeAfterRuleUpdates() {
        val observation = ruleSection("observation_recovery.md", "policy")
        Json.decodeFromString<ObservationRecoveryPolicy>(observation).validate()
        assertFalse(ruleSection("observation_recovery.md", "replan_context").isBlank())

        Json.decodeFromString<WorkspaceContextPolicy>(ruleSection("workspace.md", "context_policy")).validate()
        Json.decodeFromString<TaskExecutionPolicy>(ruleSection("workspace.md", "execution_policy")).validate()
        assertFalse(ruleSection("workspace.md", "description").isBlank())
        assertFalse(ruleSection("workspace.md", "record_recovery").isBlank())

        Json.decodeFromString<StoppedTaskReplyPolicy>(ruleSection("stopped_task_reply.md", "policy")).validate()
        assertFalse(ruleSection("stopped_task_reply.md", "instruction").isBlank())
    }

    private fun ruleSection(fileName: String, heading: String): String = markdownSection(
        projectFile("app/src/main/assets/rules/$fileName").readText(), heading
    )

    private fun <T> loadYaml(fileName: String, deserializer: DeserializationStrategy<T>): T {
        val file = projectFile("app/src/main/assets/rules/$fileName")
        val content = file.readText()
        return try {
            val parsed = yaml.loadFromString(content)
                ?: error("YAML document is empty")
            json.decodeFromJsonElement(deserializer, parsed.toJsonElement())
        } catch (error: Exception) {
            throw IllegalStateException("Invalid $fileName: ${error.message}", error)
        }
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
            require(key is String && key.isNotBlank()) { "YAML mapping keys must be non-blank strings" }
            key to value.toJsonElement()
        })
        is List<*> -> JsonArray(map { it.toJsonElement() })
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        else -> error("Unsupported YAML value type: ${this::class.java.name}")
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
