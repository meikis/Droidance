// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.droidance.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal fun executionActionResource(toolName: String, toolArguments: String = ""): Int =
    executionActionResourceOrNull(toolName, toolArguments) ?: R.string.execution_action_generic

internal fun executionActionResourceOrNull(toolName: String, toolArguments: String = ""): Int? {
    val rawName = toolName.trim()
    val baseName = rawName.substringBefore('.')
    val qualifiedOperation = rawName.substringAfter('.', missingDelimiterValue = "")
    val arguments = runCatching { Json.parseToJsonElement(toolArguments).jsonObject }.getOrNull()
    val argumentOperation = (arguments?.get("op") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val operation = qualifiedOperation.ifBlank { argumentOperation }.lowercase()
    if (baseName == "screen_act" && arguments?.get("actions") is JsonArray) {
        val single = (arguments?.get("actions") as? kotlinx.serialization.json.JsonArray)?.singleOrNull()
            as? kotlinx.serialization.json.JsonObject
        val scalar = single?.let(com.droidance.core.agent.tools.ScreenActionBatchProtocol::primitive)
        if (scalar != null) return executionActionResourceOrNull("screen_act", scalar.toString())
        return R.string.execution_action_batch
    }
    val hasDirectRoute = (arguments?.get("route_action") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true ||
        (arguments?.get("uri") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

    return when (baseName) {
        "android_read" -> when (operation) {
            "screen" -> R.string.execution_action_observe_screen
            "crop" -> R.string.execution_action_crop_zoom
            "content" -> R.string.execution_action_read_content
            "network" -> R.string.execution_action_read_network
            "device" -> R.string.execution_action_read_device
            "capabilities" -> R.string.execution_action_find_routes
            "system" -> R.string.execution_action_read_system
            else -> R.string.execution_action_android_read
        }
        "android_act" -> when (operation) {
            "open" -> if (hasDirectRoute) R.string.execution_action_open_route else R.string.execution_action_open_app
            else -> R.string.execution_action_android_act
        }
        "screen_act" -> when (operation) {
            "batch" -> R.string.execution_action_batch
            "wait" -> R.string.execution_action_wait
            "screenshot" -> R.string.execution_action_observe_screen
            "visit" -> R.string.execution_action_visit
            "collect" -> R.string.execution_action_collect
            "click", "tap" -> R.string.execution_action_click
            "scroll" -> when ((arguments?.get("direction") as? JsonPrimitive)?.contentOrNull) {
                "down" -> R.string.execution_action_scroll_down
                "up" -> R.string.execution_action_scroll_up
                "left" -> R.string.execution_action_scroll_left
                "right" -> R.string.execution_action_scroll_right
                else -> R.string.execution_action_swipe
            }
            "swipe" -> R.string.execution_action_swipe
            "type_text" -> R.string.execution_action_type_text
            "press_key" -> R.string.execution_action_press_key
            else -> R.string.execution_action_screen_act
        }
        "workspace" -> R.string.execution_action_workspace
        "automation" -> R.string.execution_action_automation
        "program" -> R.string.execution_action_program
        "session_read", "session_search" -> R.string.execution_action_read_history
        "shell" -> R.string.execution_action_shell
        "observe_screen" -> R.string.execution_action_observe_screen
        "crop_zoom" -> R.string.execution_action_crop_zoom
        "click" -> R.string.execution_action_click
        "swipe" -> R.string.execution_action_swipe
        "type_text" -> R.string.execution_action_type_text
        "press_key" -> R.string.execution_action_press_key
        "open_app" -> if (hasDirectRoute) R.string.execution_action_open_route else R.string.execution_action_open_app
        "run_shell" -> R.string.execution_action_run_shell
        "wait" -> R.string.execution_action_wait
        "ask_human" -> R.string.execution_action_ask_human
        "save_skill" -> R.string.execution_action_save_skill
        "present_result_card" -> R.string.execution_action_present_result_card
        "done" -> R.string.execution_action_done
        "report_result" -> R.string.execution_action_report_result
        else -> null
    }
}

@Composable
internal fun executionActionLabel(toolName: String, toolArguments: String = ""): String {
    val action = stringResource(executionActionResource(toolName, toolArguments))
    val parameters = executionActionParameterSummary(toolName, toolArguments)
    return parameters?.let {
        stringResource(R.string.execution_action_with_parameters, action, it)
    } ?: action
}

internal fun executionActionParameterSummary(toolName: String, toolArguments: String): String? {
    val rawName = toolName.trim()
    val baseName = rawName.substringBefore('.')
    val qualifiedOperation = rawName.substringAfter('.', missingDelimiterValue = "")
    val arguments = runCatching { Json.parseToJsonElement(toolArguments).jsonObject }.getOrNull()
        ?: return null
    val operation = qualifiedOperation
        .ifBlank { (arguments["op"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        .lowercase()

    if (baseName == "screen_act" && arguments?.get("actions") is JsonArray) {
        val single = (arguments["actions"] as? JsonArray)?.singleOrNull() as? JsonObject
        return single?.let(com.droidance.core.agent.tools.ScreenActionBatchProtocol::primitive)?.let {
            executionActionParameterSummary("screen_act", it.toString())
        }
    }

    fun int(name: String): Int? = (arguments[name] as? JsonPrimitive)?.intOrNull
    fun string(name: String): String? = (arguments[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    fun point(x: String, y: String): String? {
        val horizontal = int(x) ?: return null
        val vertical = int(y) ?: return null
        return "$horizontal, $vertical"
    }

    return when {
        (baseName == "screen_act" && operation in setOf("click", "tap")) || baseName == "click" ->
            point("x", "y")
        (baseName == "screen_act" && operation in setOf("scroll", "swipe")) || baseName == "swipe" -> {
            val start = point("startX", "startY")
            val end = point("endX", "endY")
            if (start != null && end != null) "$start → $end" else null
        }
        (baseName == "screen_act" && operation in setOf("type_text")) || baseName == "type_text" ->
            point("x", "y")
        (baseName == "screen_act" && operation in setOf("press_key")) || baseName == "press_key" ->
            string("key")
        (baseName == "android_read" && operation == "crop") || baseName == "crop_zoom" -> {
            val start = point("left", "top")
            val end = point("right", "bottom")
            if (start != null && end != null) "$start → $end" else null
        }
        (baseName == "android_act" && operation == "open") || baseName == "open_app" ->
            sequenceOf("app_name", "package_name")
                .mapNotNull { string(it) }
                .firstOrNull(String::isNotBlank)
        baseName in setOf("shell", "run_shell") ->
            string("command") ?: (arguments["argv"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(" ")
        baseName == "program" ->
            string("path") ?: string("op")
        baseName == "workspace" -> {
            val op = string("op")
            val path = string("path")
            if (op != null && path != null) "$op $path" else path ?: op
        }
        baseName == "android_read" && operation == "content" ->
            string("uri")
        baseName == "android_read" && operation == "system" ->
            string("service")
        baseName == "android_read" && operation == "capabilities" ->
            string("query") ?: string("package_name") ?: string("intent_action")
        baseName == "read_notifications" ->
            string("package_name") ?: string("query")
        baseName in setOf("click_notification", "dismiss_notification") ->
            string("id") ?: string("key")
        baseName == "manage_app_takeover" ->
            string("package_name") ?: string("op")
        baseName == "automation" ->
            string("instruction")?.take(30) ?: string("op")
        baseName == "session_search" ->
            string("query")
        baseName == "session_read" ->
            string("session_id") ?: string("turn_id")
        baseName in setOf("recall_skill", "save_skill", "read_skill_file", "skill_source", "verify_skill", "run_skill") ->
            string("name") ?: string("path")
        baseName == "task_memo" ->
            string("op") ?: string("key")
        baseName == "ask_human" ->
            string("prompt") ?: string("question")
        else -> string("op") ?: string("name") ?: string("command") ?: string("path") ?: string("query")
    }
}


/** Expand canonical batch atoms for display without exposing typed text or raw tool payloads. */
internal fun executionActionDisplayArguments(toolName: String, toolArguments: String): List<String> {
    if (toolName.substringBefore('.') != "screen_act") return listOf(toolArguments)
    val args = runCatching { Json.parseToJsonElement(toolArguments) as? JsonObject }.getOrNull()
    val operation = toolName.substringAfter('.', "").ifBlank { (args?.get("op") as? JsonPrimitive)?.contentOrNull.orEmpty() }
    if (args?.get("actions") !is JsonArray) return listOf(toolArguments)
    val actions = (args?.get("actions") as? JsonArray).orEmpty()
    return actions.mapNotNull { (it as? JsonObject)?.let(com.droidance.core.agent.tools.ScreenActionBatchProtocol::primitive)?.toString() }
        .ifEmpty { listOf(toolArguments) }
}
