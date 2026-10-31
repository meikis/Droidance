// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.llm.session

import com.droidance.core.model.llm.session.*

import com.droidance.core.agent.llm.session.*

import com.droidance.core.agent.prompt.StablePrefix
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


class FrozenTransportConfig private constructor(
    private val normalizedEndpoint: String,
    private val adapterVersion: String,
    private val normalizedExtraBodyJson: String,
    semanticOptions: Map<String, String>
) {
    private val semanticOptions = semanticOptions.toSortedMap().toMap()

    init {
        require(normalizedEndpoint.isNotBlank()) { "Transport endpoint cannot be blank" }
        require(adapterVersion.isNotBlank()) { "Adapter version cannot be blank" }
        require(semanticOptions.keys.none(String::isBlank)) { "Semantic option keys cannot be blank" }
    }

    fun hash(transport: ModelTransport): String {
        val canonical = buildJsonObject {
            put("transport", transport.name)
            put("endpoint", normalizedEndpoint)
            put("adapterVersion", adapterVersion)
            put("extraBody", Json.parseToJsonElement(normalizedExtraBodyJson))
            put("semanticOptions", buildJsonObject {
                semanticOptions.forEach { (key, value) -> put(key, value) }
            })
        }.toString()
        return sha256Hex(canonical)
    }

    override fun equals(other: Any?): Boolean = other is FrozenTransportConfig &&
        normalizedEndpoint == other.normalizedEndpoint &&
        adapterVersion == other.adapterVersion &&
        normalizedExtraBodyJson == other.normalizedExtraBodyJson &&
        semanticOptions == other.semanticOptions

    override fun hashCode(): Int {
        var result = normalizedEndpoint.hashCode()
        result = 31 * result + adapterVersion.hashCode()
        result = 31 * result + normalizedExtraBodyJson.hashCode()
        result = 31 * result + semanticOptions.hashCode()
        return result
    }

    companion object {
        fun create(
            normalizedEndpoint: String,
            adapterVersion: String,
            extraBodyJson: String = "{}",
            semanticOptions: Map<String, String> = emptyMap()
        ): FrozenTransportConfig {
            require(extraBodyJson.isNotBlank()) { "Transport extra body cannot be blank" }
            val normalizedExtraBody = canonicalizeJson(extraBodyJson)
            require(Json.parseToJsonElement(normalizedExtraBody) is JsonObject) {
                "Transport extra body must be a JSON object"
            }
            require(normalizedEndpoint == normalizedEndpoint.trim()) {
                "Normalized transport endpoint cannot contain surrounding whitespace"
            }
            require(normalizedEndpoint.isNotBlank()) { "Transport endpoint cannot be blank" }
            require(URI(normalizedEndpoint).rawUserInfo == null) {
                "Normalized transport endpoint cannot contain credential material"
            }
            require(adapterVersion == adapterVersion.trim()) {
                "Adapter version cannot contain surrounding whitespace"
            }
            return FrozenTransportConfig(
                normalizedEndpoint = normalizedEndpoint,
                adapterVersion = adapterVersion,
                normalizedExtraBodyJson = normalizedExtraBody,
                semanticOptions = semanticOptions.toSortedMap().toMap()
            )
        }
    }
}

@Serializable
data class ModelSessionIdentity(
    val threadId: String,
    val providerId: String,
    val transportKind: ModelTransport,
    val transportConfigHash: String,
    val authScopeHash: String,
    val modelId: String,
    val stablePrefixHash: String
) {
    init {
        require(threadId.isNotBlank()) { "Model session requires threadId" }
        require(providerId.isNotBlank()) { "Model session requires providerId" }
        require(modelId.isNotBlank()) { "Model session requires modelId" }
        requireSha256(transportConfigHash, "transportConfigHash")
        requireSha256(authScopeHash, "authScopeHash")
        requireSha256(stablePrefixHash, "stablePrefixHash")
    }

    companion object {
        fun create(
            threadId: String,
            providerId: String,
            transportKind: ModelTransport,
            transportConfig: FrozenTransportConfig,
            authScopeHash: String,
            modelId: String,
            stablePrefix: StablePrefix
        ): ModelSessionIdentity = ModelSessionIdentity(
            threadId = threadId,
            providerId = providerId,
            transportKind = transportKind,
            transportConfigHash = transportConfig.hash(transportKind),
            authScopeHash = authScopeHash,
            modelId = modelId,
            stablePrefixHash = stablePrefix.hash
        )
    }
}

@Serializable
class ModelSessionKey(
    val identity: ModelSessionIdentity,
    val epoch: Long
) {
    init {
        require(epoch >= 0) { "Model session epoch cannot be negative" }
    }

    override fun equals(other: Any?): Boolean = other is ModelSessionKey &&
        identity == other.identity && epoch == other.epoch

    override fun hashCode(): Int = 31 * identity.hashCode() + epoch.hashCode()
}

@Serializable
enum class EpochAdvanceReason {
    CONTEXT_CHECKPOINT,
    PROVIDER_HANDLE_INVALIDATED,
    PROCESS_RECOVERY
}

sealed interface EpochAdvanceTrigger {
    val evidenceRef: String
}

data class ContextCheckpointApplied(
    val checkpointEventId: String
) : EpochAdvanceTrigger {
    init {
        require(checkpointEventId.isNotBlank()) { "Checkpoint event id cannot be blank" }
    }

    override val evidenceRef: String = checkpointEventId
}

data class ProviderHandleInvalidated(
    val diagnosticCode: String,
    val handleRef: String
) : EpochAdvanceTrigger {
    init {
        require(diagnosticCode.isNotBlank()) { "Handle invalidation requires a diagnostic code" }
        require(handleRef.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
            "Handle invalidation evidence must be a one-way SHA-256 reference"
        }
    }

    override val evidenceRef: String = handleRef
}

class ModelSessionEpochTransition internal constructor(
    val previous: ModelSessionKey,
    val current: ModelSessionKey,
    val reason: EpochAdvanceReason,
    val evidenceRef: String
)

object ModelSessionEpochPolicy {
    fun advance(
        current: ModelSessionKey,
        trigger: EpochAdvanceTrigger
    ): ModelSessionEpochTransition = ModelSessionEpochTransition(
        previous = current,
        current = ModelSessionKey(current.identity, Math.addExact(current.epoch, 1L)),
        reason = when (trigger) {
            is ContextCheckpointApplied -> EpochAdvanceReason.CONTEXT_CHECKPOINT
            is ProviderHandleInvalidated -> EpochAdvanceReason.PROVIDER_HANDLE_INVALIDATED
        },
        evidenceRef = trigger.evidenceRef
    )
}

data class FrozenTurnModelSession(
    val turnId: String,
    val providerConfigSnapshotRef: String,
    val key: ModelSessionKey
) {
    init {
        require(turnId.isNotBlank()) { "Frozen model session requires turnId" }
        require(providerConfigSnapshotRef.isNotBlank()) { "Provider config snapshot ref cannot be blank" }
    }
}

private fun requireSha256(value: String, label: String) {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "$label must be a lowercase SHA-256 hash" }
}

private fun sha256Hex(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun canonicalizeJson(raw: String): String =
    Json.encodeToString(JsonElement.serializer(), canonicalize(Json.parseToJsonElement(raw)))

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries.sortedBy { it.key }.associate { (key, value) -> key to canonicalize(value) }
    )
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}
