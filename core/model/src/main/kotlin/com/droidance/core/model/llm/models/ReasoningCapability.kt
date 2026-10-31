// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest

@Serializable
enum class ReasoningParameter { REASONING_EFFORT, REASONING_OBJECT }

@Serializable
data class ReasoningCapability(
    val efforts: List<ReasoningEffort> = emptyList(),
    val parameter: ReasoningParameter = ReasoningParameter.REASONING_EFFORT,
    val forceDefault: Boolean = false
) {
    val options: List<ReasoningEffort> get() = listOf(ReasoningEffort.DEFAULT) +
        ReasoningEffort.entries.filter { it != ReasoningEffort.DEFAULT && it in efforts }

    fun resolve(effort: ReasoningEffort): ReasoningEffort =
        effort.takeIf { it in options } ?: ReasoningEffort.DEFAULT
}

/**
 * Data-driven model IDs and gateway dialects. A model profile matches an exact id or an id
 * prefix; otherwise a gateway may declare the efforts its hosted models accept. Unknown models
 * deliberately have no explicit effort, and a provider rejection falls back to its default.
 */
class ReasoningCatalog(raw: String) {
    private val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun gateway(baseUrl: String): JsonObject? {
        val host = runCatching { URI(baseUrl.trim()).host?.lowercase() }.getOrNull()
        return (root?.get("gateways") as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["hosts"] as? JsonArray)?.any { value -> value.string() == host } == true }
    }

    fun parameter(baseUrl: String): ReasoningParameter = runCatching {
        ReasoningParameter.valueOf(gateway(baseUrl)?.get("parameter").string().orEmpty())
    }.getOrDefault(ReasoningParameter.REASONING_EFFORT)

    fun capability(baseUrl: String, model: String): ReasoningCapability {
        val parameter = parameter(baseUrl)
        val profiles = (root?.get("models") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val profile = profiles.firstOrNull { (it["ids"] as? JsonArray)?.any { value -> value.string() == model } == true }
            ?: profiles.firstOrNull { (it["prefixes"] as? JsonArray)?.any { value ->
                value.string()?.takeIf(String::isNotBlank)?.let(model::startsWith) == true } == true }
        val efforts = profile?.get("efforts") ?: gateway(baseUrl)?.get("efforts")
        return ReasoningCapability(efforts.efforts().orEmpty(), parameter)
    }
}

/** Parse only explicit capability metadata. Missing fields never imply support. */
object ReasoningMetadata {
    fun parse(raw: String, model: String, parameter: ReasoningParameter): ReasoningCapability? = runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val entry = if (root["id"].string() == model) root else
            (root["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it["id"].string() == model } ?: return null
        val reasoning = entry["reasoning"] as? JsonObject
        if (reasoning != null) {
            val rawEfforts = reasoning["supported_efforts"]
            val efforts = when (rawEfforts) {
                null -> emptyList()
                JsonNull -> ReasoningEffort.entries.filter { it != ReasoningEffort.DEFAULT }
                else -> rawEfforts.efforts() ?: return null
            }.filterNot { it == ReasoningEffort.NONE && reasoning["mandatory"].boolean() == true }
            return ReasoningCapability(efforts, parameter)
        }
        val effort = (entry["capabilities"] as? JsonObject)?.get("effort") as? JsonObject
        if (effort != null) {
            if (effort["supported"].boolean() == false) return ReasoningCapability(parameter = parameter)
            val efforts = ReasoningEffort.entries.filter { level ->
                level.wireValue != null && ((effort[level.wireValue] as? JsonObject)?.get("supported")).boolean() == true
            }
            return ReasoningCapability(efforts, parameter)
        }
        val supported = entry["supported_parameters"] as? JsonArray
        if (supported != null && supported.all { it.string() != null } &&
            supported.none { it.string() in listOf("reasoning", "reasoning_effort") }) {
            return ReasoningCapability(parameter = parameter)
        }
        null
    }.getOrNull()
}

/** Cache identity includes endpoint, authentication scope, adapter, and model. */
fun reasoningProviderKey(config: ProviderConfig): String = MessageDigest.getInstance("SHA-256")
    .digest(listOf(config.id, config.baseUrl.trim().trimEnd('/'), config.apiKey.trim(),
        config.resolvedTransportOrNull()?.name.orEmpty(),
        (config.models + config.defaultModel).filter(String::isNotBlank).distinct().sorted().joinToString("\u001e"))
        .joinToString("\u001f").toByteArray()).joinToString("") { "%02x".format(it) }

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.efforts(): List<ReasoningEffort>? {
    val values = this as? JsonArray ?: return null
    val parsed = values.map { value ->
        ReasoningEffort.entries.firstOrNull { it.wireValue != null && it.wireValue == value.string() }
            ?: return null
    }
    return ReasoningEffort.entries.filter { it in parsed }
}
