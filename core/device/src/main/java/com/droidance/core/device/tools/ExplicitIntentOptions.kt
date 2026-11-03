// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.droidance.core.model.llm.models.ToolParameter

internal object OpenAppToolSchema {
    val parameters = linkedMapOf(
        "package_name" to ToolParameter("string", "App package (optional with uri or app_name)", false),
        "app_name" to ToolParameter("string", "App name or alias resolved from the cached installed-app inventory. Opens the app without a separate package lookup. Also supplies the target for route_action.", false),
        "activity" to ToolParameter("string", "Grounded Android activity component: package/class, or a class name with package_name. This explicit component is distinct from a catalog route_action.", false),
        "uri" to ToolParameter("string", "Explicit URI or deep link", false),
        "intent_action" to ToolParameter("string", "Android Intent action identifier, distinct from the public action_title. Optional for opening the app launcher.", false),
        "categories" to ToolParameter("array", "Android intent categories", false,
            items = ToolParameter("string", "Android intent category name.", false)),
        "mime_type" to ToolParameter("string", "Android intent MIME type", false),
        "string_extras" to ToolParameter("object", "String extras keyed by documented extra name. Choose the map from the platform contract, not the appearance of a value.", false,
            additionalPropertySchema = ToolParameter("string", "", false)),
        "int_extras" to ToolParameter("object", "32-bit integer extras keyed by documented extra name; JSON numbers without quotes.", false,
            additionalPropertySchema = ToolParameter("integer", "", false)),
        "long_extras" to ToolParameter("object", "64-bit integer extras keyed by documented extra name; JSON numbers without quotes.", false,
            additionalPropertySchema = ToolParameter("integer", "", false)),
        "boolean_extras" to ToolParameter("object", "Boolean extras keyed by documented extra name; JSON true/false without quotes. Each extra name belongs in exactly one typed map.", false,
            additionalPropertySchema = ToolParameter("boolean", "", false)),
        "expect_screen_change" to ToolParameter("boolean", "Set false only for a documented native action that completes without rendering a destination. The tool returns the synchronous Android command receipt instead of waiting for a screen transition.", false),
        "completion_evidence" to ToolParameter("string", "Use platform_contract only when Android documentation defines command acceptance as completion of the requested native action.", false, listOf("platform_contract")),
        "route_action" to ToolParameter("string", "Semantic Deep Link catalog action. Always pair it with app_name or package_name so the route cannot resolve into an unrelated app. Catalog routes are exclusive with explicit uri, activity, intent_action and Intent options; choose one destination mode.", false),
        "route_keyword" to ToolParameter("string", "Route keyword placeholder value", false),
        "route_params" to ToolParameter("object", "Additional route placeholder values", false),
        "route_category" to ToolParameter("string", "Optional catalog category", false),
        "launch_mode" to ToolParameter("string", "Task-stack policy: resume keeps the current page; reset_task returns through the launcher entry; fresh force-stops then starts a clean task. Omitted: an app's first plain open in this run uses the configured entry policy, later opens resume", false)
    )

    /** Reject competing destinations before any package lookup or Android effect. */
    fun routeArgumentMismatch(input: JsonObject): com.droidance.core.agent.event.ToolExecutionResult? {
        val unsupported = input.keys - parameters.keys - setOf("op", "action_title")
        if (unsupported.isNotEmpty()) return ToolProtocolResult.notExecuted(
            "ARGUMENT_SCHEMA_MISMATCH", true, buildJsonObject {
                put("tool", JsonPrimitive(DeviceToolNames.ANDROID_ACT))
                put("issue", JsonPrimitive("unsupported_intent_parameters"))
                put("unsupported_fields", JsonArray(unsupported.sorted().map(::JsonPrimitive)))
                put("allowed_fields", JsonArray(parameters.keys.map(::JsonPrimitive)))
            }
        )
        runCatching { ExplicitIntentOptions.parse(input) }.exceptionOrNull()?.let { error ->
            return ToolProtocolResult.notExecuted("ARGUMENT_SCHEMA_MISMATCH", true, buildJsonObject {
                put("tool", JsonPrimitive(DeviceToolNames.ANDROID_ACT))
                put("issue", JsonPrimitive(error.message.orEmpty()))
            })
        }
        val routeAction = (input["route_action"] as? JsonPrimitive)?.contentOrNull
        if (routeAction.isNullOrBlank()) return null
        val hasAppTarget = listOf("package_name", "app_name").any { field ->
            !(input[field] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        }
        if (!hasAppTarget) return ToolProtocolResult.notExecuted(
            "ARGUMENT_SCHEMA_MISMATCH", true, buildJsonObject {
                put("tool", JsonPrimitive(DeviceToolNames.ANDROID_ACT))
                put("issue", JsonPrimitive("route_action_requires_app_target"))
            }
        )
        val conflictingFields = listOf("uri", "activity", "intent_action", "categories", "mime_type",
            "string_extras", "int_extras", "long_extras", "boolean_extras",
            "expect_screen_change", "completion_evidence").filter { field ->
            input[field]?.let { it != JsonNull } == true
        }
        if (conflictingFields.isEmpty()) return null
        return ToolProtocolResult.notExecuted("ARGUMENT_SCHEMA_MISMATCH", true, buildJsonObject {
            put("tool", JsonPrimitive(DeviceToolNames.ANDROID_ACT))
            put("issue", JsonPrimitive("route_fields_conflict_with_intent_options"))
            put("conflicting_fields", JsonArray(conflictingFields.map(::JsonPrimitive)))
            put("exclusive_modes", JsonArray(listOf("catalog_route", "explicit_intent").map(::JsonPrimitive)))
        })
    }
}

/** Generic Android Intent fields shared by direct main-screen and virtual-space launches. */
data class ExplicitIntentOptions(
    val categories: List<String> = emptyList(),
    val mimeType: String? = null,
    val stringExtras: Map<String, String> = emptyMap(),
    val intExtras: Map<String, Int> = emptyMap(),
    val longExtras: Map<String, Long> = emptyMap(),
    val booleanExtras: Map<String, Boolean> = emptyMap(),
    val expectScreenChange: Boolean? = null,
    val completionEvidence: NativeCompletionEvidence? = null
) {
    fun isEmpty(): Boolean = categories.isEmpty() && mimeType.isNullOrBlank() &&
        stringExtras.isEmpty() && intExtras.isEmpty() && longExtras.isEmpty() &&
        booleanExtras.isEmpty() && expectScreenChange != false && completionEvidence == null

    fun receiptSummary(): String = buildList {
        if (categories.isNotEmpty()) add("categories=${categories.joinToString()}")
        mimeType?.takeIf(String::isNotBlank)?.let { add("mime_type=$it") }
        if (stringExtras.isNotEmpty()) add("string_extras=${stringExtras.toSortedMap()}")
        if (intExtras.isNotEmpty()) add("int_extras=${intExtras.toSortedMap()}")
        if (longExtras.isNotEmpty()) add("long_extras=${longExtras.toSortedMap()}")
        if (booleanExtras.isNotEmpty()) add("boolean_extras=${booleanExtras.toSortedMap()}")
    }.joinToString("; ")

    fun receiptMarker(): String =
        if (completionEvidence == NativeCompletionEvidence.PLATFORM_CONTRACT) {
            "NATIVE_CONTRACT_ACCEPTED"
        } else {
            "NATIVE_ACTION_ACCEPTED_UNVERIFIED"
        }

    fun toShellArguments(escape: (String) -> String): String = buildString {
        categories.forEach { append(" -c ${escape(it)}") }
        mimeType?.takeIf(String::isNotBlank)?.let { append(" -t ${escape(it)}") }
        stringExtras.toSortedMap().forEach { (key, value) ->
            append(" --es ${escape(key)} ${escape(value)}")
        }
        intExtras.toSortedMap().forEach { (key, value) ->
            append(" --ei ${escape(key)} $value")
        }
        longExtras.toSortedMap().forEach { (key, value) ->
            append(" --el ${escape(key)} $value")
        }
        booleanExtras.toSortedMap().forEach { (key, value) ->
            append(" --ez ${escape(key)} $value")
        }
    }

    companion object {
        val EMPTY = ExplicitIntentOptions()

        fun parse(json: JsonObject): ExplicitIntentOptions = ExplicitIntentOptions(
            categories = json.arrayOfStrings("categories"),
            mimeType = json["mime_type"]?.jsonPrimitive?.contentOrNull,
            stringExtras = json.stringMap("string_extras"),
            intExtras = json.intMap("int_extras"),
            longExtras = json.longMap("long_extras"),
            booleanExtras = json.booleanMap("boolean_extras"),
            expectScreenChange = json["expect_screen_change"]?.jsonPrimitive?.let { primitive ->
                primitive.lenientBooleanOrNull()
                    ?: throw IllegalArgumentException("expect_screen_change must be a boolean")
            },
            completionEvidence = json["completion_evidence"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                NativeCompletionEvidence.entries.firstOrNull { it.wireName == raw }
                    ?: throw IllegalArgumentException("completion_evidence must be platform_contract")
            }
        ).also { options ->
            val keys = options.stringExtras.keys.toList() + options.intExtras.keys.toList() +
                options.longExtras.keys.toList() + options.booleanExtras.keys.toList()
            require(keys.size == keys.toSet().size) { "Each Intent extra name must occur in exactly one typed map" }
        }

        private fun JsonObject.arrayOfStrings(key: String): List<String> {
            val element = this[key] ?: return emptyList()
            val array = element as? JsonArray
                ?: throw IllegalArgumentException("$key must be an array of strings")
            return array.mapIndexed { index, item ->
                item.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("$key[$index] must be a non-empty string")
            }
        }

        private fun JsonObject.objectField(key: String): JsonObject? {
            val element = this[key] ?: return null
            return element as? JsonObject
                ?: throw IllegalArgumentException("$key must be an object")
        }

        private fun JsonObject.stringMap(key: String): Map<String, String> =
            objectField(key)?.mapValuesChecked(key) { primitive ->
                primitive.contentOrNull?.takeIf { primitive.isString }
            } ?: emptyMap()

        private fun JsonObject.intMap(key: String): Map<String, Int> =
            objectField(key)?.mapValuesChecked(key) { primitive ->
                primitive.intOrNull?.takeUnless { primitive.isString }
            } ?: emptyMap()

        private fun JsonObject.longMap(key: String): Map<String, Long> =
            objectField(key)?.mapValuesChecked(key) { primitive ->
                primitive.longOrNull?.takeUnless { primitive.isString }
            } ?: emptyMap()

        private fun JsonObject.booleanMap(key: String): Map<String, Boolean> =
            objectField(key)?.mapValuesChecked(key) { primitive ->
                primitive.booleanOrNull?.takeUnless { primitive.isString }
            } ?: emptyMap()

        private fun <T> JsonObject.mapValuesChecked(
            fieldName: String,
            parse: (kotlinx.serialization.json.JsonPrimitive) -> T?
        ): Map<String, T> = entries.associate { (key, value) ->
            require(key.isNotBlank()) { "$fieldName keys must be non-empty" }
            val primitive = runCatching { value.jsonPrimitive }.getOrNull()
                ?: throw IllegalArgumentException("$fieldName.$key must be a scalar value")
            key to (parse(primitive)
                ?: throw IllegalArgumentException("$fieldName.$key has an invalid value type"))
        }
    }
}

enum class NativeCompletionEvidence(val wireName: String) {
    PLATFORM_CONTRACT("platform_contract")
}
