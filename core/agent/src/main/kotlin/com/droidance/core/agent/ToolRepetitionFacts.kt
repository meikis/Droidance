// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolExecutionResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable
data class ToolRepetitionPolicy(
    val historySize: Int,
    val warningThreshold: Int,
    val ignoredArgumentKeys: Set<String>,
    val ignoredResultKeys: Set<String>
) {
    init { require(warningThreshold > 1 && historySize >= warningThreshold && historySize <= 128) }
}

/** Reports repeated technical outcomes. It never judges progress or stops a task. */
class ToolRepetitionFacts(private val policy: ToolRepetitionPolicy) {
    private data class Receipt(val call: String, val result: String)
    private val history = ArrayDeque<Receipt>()
    private val warned = mutableSetOf<Receipt>()

    fun record(name: String, arguments: String, result: ToolExecutionResult): JsonObject? {
        val call = hash(name + "\n" + canonical(parse(arguments), policy.ignoredArgumentKeys))
        val body = result.data ?: parse(result.outputText)
        val outcome = hash("${result.executionStatus}:${result.diagnosticCode}:" +
            canonical(body, if (result.data == null) policy.ignoredResultKeys else emptySet()))
        if (history.lastOrNull { it.call == call }?.result != outcome) warned.removeAll { it.call == call }
        val receipt = Receipt(call, outcome)
        history.addLast(receipt)
        while (history.size > policy.historySize) history.removeFirst()
        warned.retainAll(history.toSet())
        val count = history.asReversed().filter { it.call == call }.takeWhile { it.result == outcome }.size
        if (count < policy.warningThreshold || !warned.add(receipt)) return null
        return buildJsonObject {
            put("tool", name); put("same_arguments_and_result_count", count)
            put("execution_status", result.executionStatus.name)
            result.diagnosticCode?.let { put("diagnostic_code", it) }
        }
    }

    fun reset() { history.clear(); warned.clear() }

    private fun parse(text: String): JsonElement = runCatching { Json.parseToJsonElement(text) }
        .getOrElse { JsonPrimitive(text) }

    // Only known top-level envelope fields are excluded. Source data keeps all its fields.
    private fun canonical(value: JsonElement, ignored: Set<String>): String = sort(
        if (value is JsonObject) JsonObject(value.filterKeys { it !in ignored }) else value
    ).toString()

    private fun sort(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to sort(it.value) })
        is JsonArray -> JsonArray(value.map(::sort))
        else -> value
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
