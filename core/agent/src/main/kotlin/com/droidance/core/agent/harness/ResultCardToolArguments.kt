// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The durable argument shape for the generic result-card tool. */
data class ResultCardToolArguments(
    val title: String,
    val items: List<ResultCardToolItem>
) {
    companion object {
        const val TOOL_NAME = "present_result_card"

        /** Validate live completion entries before projecting them into presentation-only rows. */
        fun parseCompletion(argumentsJson: String): ResultCardToolArguments {
            val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
            val title = arguments.requiredString("title")
            val entries = arguments.requiredArray("items").map(PublicResultEntry::parse)
            require(entries.isNotEmpty()) { "Result card items cannot be empty" }
            return ResultCardToolArguments(title, entries.map { ResultCardToolItem(it.text, it.deeplink.orEmpty()) })
        }

        /** Projection of persisted presentation arguments, independent of live evidence validation. */
        fun parse(argumentsJson: String): ResultCardToolArguments {
            val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
            val title = arguments.requiredString("title")
            val parsedItems = arguments.requiredArray("items").mapIndexed { index, value ->
                val item = value as? JsonObject
                    ?: throw IllegalArgumentException("Result card item $index must be an object")
                ResultCardToolItem(
                    text = item.requiredString("text"),
                    deeplink = if ("deeplink" in item) item.requiredStringAllowEmpty("deeplink") else ""
                )
            }
            require(parsedItems.isNotEmpty()) { "Result card items cannot be empty" }
            return ResultCardToolArguments(title, parsedItems)
        }

        private fun JsonObject.requiredString(name: String): String {
            val primitive = this[name]?.jsonPrimitive
                ?: throw IllegalArgumentException("Missing string '$name'")
            val value = primitive.stringValueOrNull()
                ?: throw IllegalArgumentException("Invalid string '$name'")
            return value.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Missing or blank string '$name'")
        }

        private fun JsonObject.requiredArray(name: String): JsonArray = this[name] as? JsonArray
            ?: throw IllegalArgumentException("Missing array '$name'")

        private fun JsonObject.requiredStringAllowEmpty(name: String): String {
            val primitive = this[name]?.jsonPrimitive
                ?: throw IllegalArgumentException("Missing string '$name'")
            return primitive.stringValueOrNull()
                ?: throw IllegalArgumentException("Invalid string '$name'")
        }

        private fun JsonPrimitive.stringValueOrNull(): String? =
            takeIf(JsonPrimitive::isString)?.content
    }
}

data class ResultCardToolItem(
    val text: String,
    val deeplink: String
)

fun renderResultCardMarkdown(
    title: String,
    items: List<Pair<String, String>>
): String = buildString {
    append("**${title.trim()}**")
    items.forEachIndexed { index, (rawText, deeplink) ->
        val text = rawText
            .trim()
            .replace(Regex("\\r\\n?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .let { if (items.size > 1) it.replaceFirst(LEADING_ORDERED_ITEM_MARKER, "") else it }
        val rendered = if (deeplink.isBlank()) {
            text
        } else {
            linkResultText(text, deeplink)
        }
        append("\n\n")
        if (items.size > 1) append("${index + 1}. ")
        append(if (items.size > 1) indentListContinuation(rendered) else rendered)
    }
}.trim()

private val LEADING_ORDERED_ITEM_MARKER = Regex("""^\s*\d+[.)]\s+""")

private fun linkResultText(text: String, deeplink: String): String {
    val newline = text.indexOf('\n')
    if (newline < 0) return "[$text]($deeplink)"
    val firstLine = text.substring(0, newline)
    if (firstLine.isBlank()) return text
    return "[$firstLine]($deeplink)${text.substring(newline)}"
}

private fun indentListContinuation(text: String): String =
    text.lineSequence().joinToString("\n") { line ->
        if (line.isBlank()) line else "   $line"
    }.removePrefix("   ")
