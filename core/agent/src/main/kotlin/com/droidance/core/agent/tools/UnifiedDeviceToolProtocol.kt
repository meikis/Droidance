// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.DeviceToolNames.ANDROID_READ
import com.droidance.core.agent.tools.DeviceToolNames.ANDROID_ACT
import com.droidance.core.agent.tools.DeviceToolNames.SHELL
import com.droidance.core.agent.tools.DeviceToolNames.SCREEN_ACT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The logical device primitives exposed to the model. Runtime mode and execution space are
 * deliberately absent: the frozen execution context binds each operation to its application UID,
 * paired shell session or virtual display.
 */
enum class ExecutionScope { TURN_LOCAL, APP_UID, DEVICE_SHELL, DISPLAY }

data class OperationRequirements(
    val scope: ExecutionScope,
    val requiresObservation: Boolean,
    val mayChangeDisplay: Boolean
) {
    val requiresBackend: Boolean get() = scope == ExecutionScope.DEVICE_SHELL || scope == ExecutionScope.DISPLAY
}

object UnifiedDeviceToolProtocol {
    val argumentDependentTools: Set<String> = setOf(ANDROID_READ, ANDROID_ACT, "program", "run_skill")

    /** Resolves protocol operations only; never inspects task text or application identity. */
    fun requirements(toolName: String, arguments: String): OperationRequirements {
        val op = operation(toolName, arguments)
        val scope = when {
            toolName == ANDROID_READ && op == "crop" -> ExecutionScope.TURN_LOCAL
            toolName == ANDROID_READ && op in setOf("content", "device", "apps", "capabilities") -> ExecutionScope.APP_UID
            toolName == ANDROID_ACT && op == "content" -> ExecutionScope.APP_UID
            toolName == SHELL || (toolName == ANDROID_READ && op in setOf("system", "network", "clipboard")) -> ExecutionScope.DEVICE_SHELL
            toolName in setOf("program", "run_skill") -> if (runCatching {
                Json.parseToJsonElement(arguments).jsonObject["device_access"]?.jsonPrimitive?.booleanOrNull
            }.getOrNull() == true) ExecutionScope.DISPLAY else ExecutionScope.TURN_LOCAL
            toolName in setOf(ANDROID_READ, ANDROID_ACT, SCREEN_ACT, "run_skill") -> ExecutionScope.DISPLAY
            else -> ExecutionScope.TURN_LOCAL
        }
        return OperationRequirements(scope, isScreenAction(toolName),
            (scope == ExecutionScope.DISPLAY || toolName == SHELL && expectsScreenChange(toolName, arguments)) && (toolName == SHELL || isScreenAction(toolName) || toolName in setOf(ANDROID_ACT, "program", "run_skill")))
    }

    fun operation(toolName: String, arguments: String): String? = when (toolName) {
        ANDROID_READ, ANDROID_ACT -> jsonOperation(arguments)
        SCREEN_ACT -> runCatching {
            val root = Json.parseToJsonElement(arguments).jsonObject
            "batch".takeIf { root["actions"] is JsonArray && "op" !in root }
        }.getOrNull()
        SHELL -> "run"
        else -> null
    }

    fun requestsFrameWait(toolName: String, arguments: String): Boolean =
        isScreenAction(toolName) && runCatching {
            Json.parseToJsonElement(arguments).jsonObject["wait_for_frame_ms"]?.jsonPrimitive?.content?.toLongOrNull()?.let { it > 0 }
        }.getOrNull() == true

    fun isScreenObservation(toolName: String, arguments: String): Boolean =
        (toolName == ANDROID_READ && operation(toolName, arguments) == "screen") ||
            toolName in setOf("observe_screen", "crop_zoom")

    fun isScreenAction(toolName: String): Boolean = toolName == SCREEN_ACT

    fun expectsScreenChange(toolName: String, arguments: String): Boolean {
        if (toolName !in setOf(ANDROID_ACT, SHELL)) return true
        if (toolName == ANDROID_ACT && operation(toolName, arguments) == "content") return false
        return runCatching {
            Json.parseToJsonElement(arguments).jsonObject["expect_screen_change"]
                ?.jsonPrimitive
                ?.booleanOrNull
        }.getOrNull() != false
    }

    /** Passive screen batches still produce observations, but do not mutate device state. */
    fun isObservationOnlyAction(toolName: String, arguments: String): Boolean {
        if (!isScreenAction(toolName)) return false
        return runCatching {
            val root = Json.parseToJsonElement(arguments).jsonObject
            if (operation(toolName, arguments) != "batch") return@runCatching false
            val actions = root["actions"] as? JsonArray ?: return@runCatching false
            actions.isNotEmpty() && actions.all { action ->
                val type = (action as? JsonObject)?.get("op") as? kotlinx.serialization.json.JsonPrimitive
                type?.isString == true && type.content.trim().lowercase() in setOf("wait", "screenshot")
            }
        }.getOrDefault(false)
    }

    fun containsClickAction(toolName: String, arguments: String): Boolean =
        screenActionTypes(toolName, arguments).any { it in setOf("click", "find_and_click") }

    fun containsTextInputAction(toolName: String, arguments: String): Boolean =
        screenActionTypes(toolName, arguments).any { it in setOf("type_text") }

    fun isGroundedNativeContractReceipt(toolName: String, result: ToolExecutionResult): Boolean =
        toolName == ANDROID_ACT && result.platformContractAccepted && result.isVerifiedSuccess

    private fun jsonOperation(arguments: String): String? = runCatching {
        Json.parseToJsonElement(arguments).jsonObject["op"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.lowercase()
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun screenActionTypes(toolName: String, arguments: String): List<String> {
        if (toolName != SCREEN_ACT) return emptyList()
        if (operation(toolName, arguments) != "batch") return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(arguments).jsonObject
            (root["actions"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonObject)
                    ?.get("op")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.lowercase()
            }
        }.getOrDefault(emptyList())
    }
}
