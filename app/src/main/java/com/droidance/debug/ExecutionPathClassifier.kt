// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.tools.DeviceToolNames

import com.droidance.core.agent.tools.UnifiedDeviceToolProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Labels the path that was actually selected by a tool call; it does not route tasks. */
object ExecutionPathClassifier {
    private val guiTools = setOf(
        "observe_screen",
        "crop_zoom",
        "click",
        "swipe",
        "type_text",
        "press_key"
    )

    fun classify(toolName: String, arguments: String): String? {
        if (toolName == DeviceToolNames.SHELL) return "shell"
        if (toolName == DeviceToolNames.ANDROID_READ) {
            return when (UnifiedDeviceToolProtocol.operation(toolName, arguments)) {
                "screen", "crop" -> "gui"
                "device", "content" -> "android_api"
                else -> null
            }
        }
        if (toolName == DeviceToolNames.ANDROID_ACT) {
            return if (UnifiedDeviceToolProtocol.operation(toolName, arguments) == "open") {
                classifyOpenApp(arguments)
            } else {
                null
            }
        }
        if (toolName == DeviceToolNames.SCREEN_ACT) {
            return if (UnifiedDeviceToolProtocol.operation(toolName, arguments) in setOf(
                    "batch", "click", "find_and_click", "swipe", "drag", "path", "scroll", "type_text", "press_key"
                )
            ) {
                "gui"
            } else {
                null
            }
        }
        return when (toolName) {
            in guiTools -> "gui"
            "query_android_content" -> "android_api"
            "open_app" -> classifyOpenApp(arguments)
            "run_shell" -> "shell"
            "run_skill" -> "compiled_skill"
            else -> null
        }
    }

    private fun classifyOpenApp(arguments: String): String {
        val values = jsonStrings(arguments)
        return when {
            !values["uri"].isNullOrBlank() || !values["route_action"].isNullOrBlank() -> "deeplink"
            !values["intent_action"].isNullOrBlank() -> "native_intent"
            else -> "native_launch"
        }
    }

    private fun jsonStrings(arguments: String): Map<String, String> = runCatching {
        Json.parseToJsonElement(arguments).jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull.orEmpty()
        }
    }.getOrDefault(emptyMap())
}
