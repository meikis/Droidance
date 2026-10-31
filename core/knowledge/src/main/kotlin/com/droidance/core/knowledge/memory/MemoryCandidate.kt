// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolCallRejectedEventPayload
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.TurnCompletedEventPayload
import com.droidance.core.agent.event.UserTextEventPayload
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MEMORY_CANDIDATE_VERSION = 2

@Serializable
data class MemoryCandidateStep(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val effect: ToolEffect,
    val executionStatus: String,
    val evidenceStatus: ToolEvidenceStatus,
    val evidenceRef: String?,
    val outputSummary: String,
    val sourceCallSequence: Long,
    val settlementSequence: Long
)

@Serializable
data class MemoryCandidate(
    val version: Int,
    val candidateHash: String,
    val threadId: String,
    val turnId: String,
    val sourceThroughSequence: Long,
    val userInput: String,
    val status: String,
    val resultText: String,
    val steps: List<MemoryCandidateStep>,
    val verificationEvidenceRefs: List<String>,
    val hasVerifiedStateChange: Boolean,
    val extractedAt: Long
)

/** Deterministic extraction stage: typed Turn facts become a secret-redacted candidate. */
object TurnMemoryCandidateProjector {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun project(events: List<AgentEventEnvelope>, turnId: String): MemoryCandidate? {
        val turnEvents = events.filter { it.turnId == turnId }
        if (turnEvents.isEmpty()) return null
        require(turnEvents.zipWithNext().all { (left, right) -> left.sequence < right.sequence }) {
            "MemoryCandidate source events must be strictly ordered"
        }
        require(turnEvents.map(AgentEventEnvelope::threadId).distinct().size == 1) {
            "MemoryCandidate source events must belong to one thread"
        }
        val completionEvent = turnEvents.singleOrNull {
            it.eventKind == AgentEventKind.TURN_COMPLETED
        } ?: return null
        val completion = decode<TurnCompletedEventPayload>(completionEvent)
        val userInput = turnEvents.firstOrNull { it.eventKind == AgentEventKind.USER_MESSAGE }
            ?.let { decode<UserTextEventPayload>(it).text }
            ?: return null
        val calls = turnEvents.asSequence()
            .filter { it.eventKind == AgentEventKind.TOOL_CALL }
            .associateBy({ requireNotNull(it.callId) }, { it to decode<ToolCallEventPayload>(it) })
        val settlements = turnEvents.asSequence()
            .filter {
                it.eventKind == AgentEventKind.TOOL_FINISHED ||
                    it.eventKind == AgentEventKind.TOOL_CALL_REJECTED
            }
            .associateBy { requireNotNull(it.callId) }

        val steps = calls.entries
            .sortedBy { it.value.first.sequence }
            .map { (callId, callEntry) ->
                val settlementEvent = requireNotNull(settlements[callId]) {
                    "Completed Turn contains an unsettled tool call: $callId"
                }
                val result = when (settlementEvent.eventKind) {
                    AgentEventKind.TOOL_FINISHED -> decode<ToolFinishedEventPayload>(settlementEvent).result
                    AgentEventKind.TOOL_CALL_REJECTED ->
                        decode<ToolCallRejectedEventPayload>(settlementEvent).result
                    else -> error("Unsupported settlement event")
                }
                val evidenceRef = result.observationId?.let { "observation:$it" }
                    ?: result.sourceRef
                    ?: (settlementEvent.eventId.takeIf {
                        callEntry.second.effect != ToolEffect.STATE_CHANGING && result.isVerifiedSuccess
                    }?.let { "event:$it" })
                MemoryCandidateStep(
                    callId = callId,
                    toolName = callEntry.second.name,
                    argumentsJson = MemorySecretRedactor.redactJson(callEntry.second.argumentsJson).take(1_200),
                    effect = callEntry.second.effect,
                    executionStatus = result.executionStatus.name,
                    evidenceStatus = result.evidenceStatus,
                    evidenceRef = evidenceRef,
                    outputSummary = MemorySecretRedactor.redact(result.outputText).take(320),
                    sourceCallSequence = callEntry.first.sequence,
                    settlementSequence = settlementEvent.sequence
                )
            }
        val verifiedSteps = steps.filter { step ->
            when (step.effect) {
                ToolEffect.STATE_CHANGING ->
                    step.executionStatus == "SUCCEEDED" &&
                        step.evidenceStatus == ToolEvidenceStatus.CAPTURED &&
                        step.evidenceRef != null
                ToolEffect.READ_ONLY ->
                    step.executionStatus == "SUCCEEDED" &&
                        step.evidenceRef != null
                else ->
                    step.executionStatus == "SUCCEEDED" &&
                        step.evidenceRef != null
            }
        }
        val verifiedStateChanges = verifiedSteps.filter {
            it.effect == ToolEffect.STATE_CHANGING
        }
        val evidenceRefs = verifiedSteps.mapNotNull(MemoryCandidateStep::evidenceRef).distinct()
        val resultText = MemorySecretRedactor.redact(
            completion.assistantReply ?: checkNotNull(completion.systemNotice)
        ).take(1_000)
        val signature = MemoryCandidateSignature(
            version = MEMORY_CANDIDATE_VERSION,
            userInput = MemorySecretRedactor.redact(userInput).normalizedForHash(),
            status = completion.status.lowercase(),
            tools = steps.map { step ->
                MemoryCandidateStepSignature(
                    toolName = step.toolName,
                    effect = step.effect,
                    executionStatus = step.executionStatus,
                    evidenceStatus = step.evidenceStatus
                )
            }
        )
        return MemoryCandidate(
            version = MEMORY_CANDIDATE_VERSION,
            candidateHash = sha256(json.encodeToString(signature)),
            threadId = completionEvent.threadId,
            turnId = turnId,
            sourceThroughSequence = turnEvents.maxOf(AgentEventEnvelope::sequence),
            userInput = MemorySecretRedactor.redact(userInput).take(1_000),
            status = completion.status.lowercase(),
            resultText = resultText,
            steps = steps,
            verificationEvidenceRefs = evidenceRefs,
            hasVerifiedStateChange = verifiedStateChanges.isNotEmpty(),
            extractedAt = completionEvent.occurredAt
        )
    }

    private inline fun <reified T> decode(event: AgentEventEnvelope): T =
        json.decodeFromString(event.payloadJson)

    private fun String.normalizedForHash(): String =
        trim().lowercase().replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Serializable
private data class MemoryCandidateSignature(
    val version: Int,
    val userInput: String,
    val status: String,
    val tools: List<MemoryCandidateStepSignature>
)

@Serializable
private data class MemoryCandidateStepSignature(
    val toolName: String,
    val effect: ToolEffect,
    val executionStatus: String,
    val evidenceStatus: ToolEvidenceStatus
)

object MemorySecretRedactor {
    private val bearerPattern = Regex("(?i)\\bbearer\\s+[a-z0-9._~+\\-/]+=*")
    private val secretAssignmentPattern = Regex(
        "(?i)(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|authorization|password|passwd|secret|cookie|otp|pairing[_ -]?code|sms[_ -]?code|verification[_ -]?code|验证码|短信验证码|提取码|密码|令牌|密钥)(\\s*[:=＝]\\s*)([^\\s,;，。]+)"
    )
    private val verificationCodePattern = Regex(
        "(?i)(verification\\s+code|otp|sms\\s+code|验证码|动态码|校验码)[\\s:：是为]+([0-9]{4,8})\\b"
    )

    fun redact(value: String): String = value
        .replace(bearerPattern, "Bearer [REDACTED]")
        .replace(secretAssignmentPattern) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }
        .replace(verificationCodePattern) { match ->
            "${match.groupValues[1]}: [REDACTED]"
        }

    fun redactJson(value: String): String = runCatching {
        redactJsonElement(Json.parseToJsonElement(value)).toString()
    }.getOrElse { redact(value) }

    private fun redactJsonElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, nested) ->
            if (normalizeKey(key) in sensitiveJsonKeys) JsonPrimitive("[REDACTED]")
            else redactJsonElement(nested)
        })
        is JsonArray -> JsonArray(element.map(::redactJsonElement))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
        else -> element
    }

    private fun normalizeKey(key: String): String =
        key.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private val sensitiveJsonKeys = setOf(
        "api_key", "access_token", "refresh_token", "authorization", "password", "passwd",
        "secret", "cookie", "otp", "pairing_code", "sms_code", "verification_code"
    )
}

object MemoryCandidateJsonConsolidator {
    private val json = Json { encodeDefaults = true }

    fun merge(existingJson: String, incomingJson: String): String {
        val existing = json.decodeFromString<MemoryCandidate>(existingJson)
        val incoming = json.decodeFromString<MemoryCandidate>(incomingJson)
        require(existing.candidateHash == incoming.candidateHash) {
            "Cannot consolidate different MemoryCandidate hashes"
        }
        require(existing.version == incoming.version) {
            "Cannot consolidate different MemoryCandidate versions"
        }
        return json.encodeToString(
            incoming.copy(
                verificationEvidenceRefs = (
                    existing.verificationEvidenceRefs + incoming.verificationEvidenceRefs
                ).distinct(),
                hasVerifiedStateChange = existing.hasVerifiedStateChange ||
                    incoming.hasVerifiedStateChange
            )
        )
    }
}
