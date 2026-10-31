// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.config.ToolSafetyPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Generic action confirmation and lexical command-risk hints.
 * Shell patterns are heuristics, not an execution sandbox or a proof of isolation.
 * The program worker has a separate OS identity and exposes only mediated capabilities.
 */
object ToolRiskPolicy {
    data class Decision(
        val allowed: Boolean,
        val reason: String = "",
        val requiresConfirmation: Boolean = false,
        val rejection: com.droidance.core.agent.event.ToolExecutionResult? = null
    )

    private val confirmations = HighRiskConfirmationStore()

    fun confirmNextHighRisk(scope: TurnEventScope): Boolean = confirmations.confirm(scope)

    fun revoke(scope: TurnEventScope) = confirmations.revoke(scope)

    private fun consumeOneShotConfirmation(scope: TurnEventScope, actionKey: String, reason: String): Decision =
        if (confirmations.consumeOrRequest(scope, actionKey)) Decision(allowed = true)
        else blocked(reason, requiresConfirmation = true)

    /**
     * Confirmation identity must be stable across processes and collision-resistant. Kotlin's
     * Int.hashCode is neither: two different destructive payloads can share a value, and the
     * value can change with runtime/platform details. Canonicalize JSON object keys first, then
     * bind the tool name and full payload to SHA-256.
     */
    private fun actionKey(toolName: String, arguments: String): String {
        val canonicalArguments = runCatching {
            canonicalize(Json.parseToJsonElement(arguments)).toString()
        }.getOrElse {
            arguments.trim().replace(Regex("\\s+"), " ")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$toolName\u0000$canonicalArguments".toByteArray(Charsets.UTF_8))
        return "$toolName:${digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value) }
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun blockedOrConfirmed(scope: TurnEventScope, toolName: String, arguments: String, reason: String): Decision =
        consumeOneShotConfirmation(scope, actionKey(toolName, arguments), reason)

    private fun blocked(reason: String, requiresConfirmation: Boolean = false): Decision {
        val rejection = ToolProtocolResult.notExecuted(
            code = if (requiresConfirmation) "HIGH_RISK_CONFIRMATION_REQUIRED" else "TOOL_POLICY_BLOCKED",
            retryable = requiresConfirmation,
            details = buildJsonObject {
                put("reason", JsonPrimitive(reason))
                put("confirmation_required", JsonPrimitive(requiresConfirmation))
            }
        )
        return Decision(false, rejection.outputText, requiresConfirmation, rejection)
    }

    fun assess(toolName: String, arguments: String, policy: ToolSafetyPolicy, scope: TurnEventScope): Decision = when (toolName) {
        DeviceToolNames.SHELL -> assessShell(arguments, policy, scope)
        in policy.confirmationTools -> blockedOrConfirmed(
            scope = scope,
            toolName = toolName,
            arguments = arguments,
            reason = "$toolName can affect external communication, personal data, or device state."
        )
        else -> Decision(allowed = true)
    }

    private fun assessShell(arguments: String, policy: ToolSafetyPolicy, scope: TurnEventScope): Decision {
        val command = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")
        if (command.isBlank()) return Decision(allowed = true)

        val normalized = command.trim().replace(Regex("""\s+"""), " ")
        val lower = normalized.lowercase()

        // UI-tree reads and simulated input are forbidden: perception goes through screenshots, and
        // input through screen_act on the bound display with its coordinate mapping and checks.
        if (policy.shellForbiddenPatterns.any { Regex(it).containsMatchIn(lower) }) {
            return blocked("UI-tree reads and simulated input are blocked by policy. Observe with screenshots and act through screen_act on the bound display.")
        }

        if (policy.shellDestructivePatterns.any { Regex(it).containsMatchIn(lower) }) {
            return blockedOrConfirmed(scope = scope, toolName = DeviceToolNames.SHELL, arguments = arguments, reason = "shell blocked a privileged command that may modify the system, delete data, or terminate processes.")
        }

        return Decision(allowed = true)
    }

}
