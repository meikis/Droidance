// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ProviderErrorCategory {
    AUTHENTICATION,
    PERMISSION_DENIED,
    RATE_LIMIT,
    BILLING,
    MODEL_UNAVAILABLE,
    CONTEXT_LIMIT,
    IMAGE_UNSUPPORTED,
    IMAGE_INPUT_UNAVAILABLE,
    IMAGE_DETAIL_UNSUPPORTED,
    PREVIOUS_RESPONSE_NOT_FOUND,
    REASONING_UNSUPPORTED,
    REQUEST_REJECTED,
    SERVER,
    UNKNOWN
}

class ProviderHttpException(
    val statusCode: Int,
    val providerCode: String?,
    val category: ProviderErrorCategory,
    providerName: String,
    responseBody: String,
    val retryAfterMs: Long? = null,
    /** Redacted server explanation that is safe to show; only permission denials carry one. */
    val publicReason: String? = null
) : Exception(
    "$providerName request failed: status=$statusCode" +
        providerCode?.let { ", code=$it" }.orEmpty() +
        ", body=${responseBody.take(MAX_BODY_CHARS)}"
) {
    companion object {
        private const val MAX_BODY_CHARS = 1_000
        private const val MAX_REASON_CHARS = 200
        private const val REDACTED = "[REDACTED]"

        fun fromResponse(
            statusCode: Int,
            providerName: String,
            responseBody: String,
            retryAfter: String? = null,
            nowEpochMs: Long = System.currentTimeMillis(),
            redactor: TransportDiagnosticRedactor = TransportDiagnosticRedactor("")
        ): ProviderHttpException {
            val code = extractProviderCode(responseBody)?.lowercase()
            val category = when {
                statusCode == 401 -> ProviderErrorCategory.AUTHENTICATION
                statusCode == 403 -> ProviderErrorCategory.PERMISSION_DENIED
                statusCode == 429 -> ProviderErrorCategory.RATE_LIMIT
                statusCode == 402 -> ProviderErrorCategory.BILLING
                statusCode == 404 && isImageEndpointUnavailable(responseBody) -> ProviderErrorCategory.IMAGE_INPUT_UNAVAILABLE
                code.matchesProviderCode(CONTEXT_LIMIT_CODES) -> ProviderErrorCategory.CONTEXT_LIMIT
                (statusCode == 400 || statusCode == 422) && isReasoningRejected(responseBody, code) -> ProviderErrorCategory.REASONING_UNSUPPORTED
                isImageDetailRejected(responseBody) -> ProviderErrorCategory.IMAGE_DETAIL_UNSUPPORTED
                code.matchesProviderCode(IMAGE_UNSUPPORTED_CODES) -> ProviderErrorCategory.IMAGE_UNSUPPORTED
                code.matchesProviderCode(PREVIOUS_RESPONSE_CODES) -> ProviderErrorCategory.PREVIOUS_RESPONSE_NOT_FOUND
                code.matchesProviderCode(MODEL_UNAVAILABLE_CODES) -> ProviderErrorCategory.MODEL_UNAVAILABLE
                statusCode == 400 || statusCode == 422 -> ProviderErrorCategory.REQUEST_REJECTED
                statusCode >= 500 -> ProviderErrorCategory.SERVER
                else -> ProviderErrorCategory.UNKNOWN
            }
            val publicReason = if (category == ProviderErrorCategory.PERMISSION_DENIED) {
                extractServerReason(responseBody)?.let { redactReason(redactor.text(it)) }
            } else null
            return ProviderHttpException(statusCode, code, category, providerName, responseBody,
                parseRetryAfter(retryAfter, nowEpochMs), publicReason)
        }

        /** Gateways often answer with an HTML page, which explains nothing worth showing. */
        private fun extractServerReason(body: String): String? {
            val trimmed = body.trim()
            if (trimmed.startsWith("<")) return null
            val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
            val objectRoot = root as? JsonObject ?: return (root as? JsonPrimitive)?.contentOrNull
            return sequenceOf(
                (objectRoot["error"] as? JsonObject)?.get("message"),
                objectRoot["error"],
                objectRoot["message"],
                objectRoot["msg"],
                objectRoot["detail"]
            ).mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                .firstOrNull(String::isNotBlank)
        }

        private fun redactReason(reason: String): String? = REASON_SECRET_PATTERNS
            .fold(reason) { text, pattern -> text.replace(pattern, REDACTED) }
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_REASON_CHARS)
            .takeIf(String::isNotBlank)

        private fun parseRetryAfter(value: String?, nowEpochMs: Long): Long? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            trimmed.toLongOrNull()?.let { seconds ->
                return seconds.takeIf { it >= 0 }?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000)
            }
            return runCatching {
                (java.time.ZonedDateTime.parse(trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - nowEpochMs).coerceAtLeast(0)
            }.getOrNull()
        }

        /** A route rejection is not a durable statement about the model's vision capability. */
        private fun isImageEndpointUnavailable(body: String): Boolean = runCatching {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return false
            val error = root["error"] as? JsonObject ?: return false
            error["code"]?.jsonPrimitive?.contentOrNull == "404" &&
                error["message"]?.jsonPrimitive?.contentOrNull?.trim() ==
                "No endpoints found that support image input"
        }.getOrDefault(false)

        private fun isReasoningRejected(body: String, code: String?): Boolean = runCatching {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return false
            val error = root["error"] as? JsonObject
            val parameter = ((error?.get("param") ?: root["param"]) as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull.orEmpty().lowercase()
            if (parameter.isNotBlank()) return parameter == "reasoning_effort" || parameter == "reasoning" || parameter.startsWith("reasoning.")
            code in setOf("unsupported_reasoning_effort", "reasoning_effort_not_supported") ||
                (code in setOf("unsupported_parameter", "unsupported_value", "invalid_parameter", "invalidparameter", "invalid_request_error") &&
                    ((error?.get("message") ?: root["message"]) as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull.orEmpty().lowercase().let {
                            it.contains("reasoning_effort") || it.contains("reasoning.effort") || it.contains("'reasoning'") || it.contains("\"reasoning\"")
                        })
        }.getOrDefault(false)

        private fun isImageDetailRejected(body: String): Boolean = runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            val error = root["error"]?.jsonObject
            val parameter = sequenceOf(error?.get("param"), root["param"])
                .mapNotNull { it?.jsonPrimitive?.contentOrNull }
                .firstOrNull()
                .orEmpty()
                .lowercase()
            parameter == "detail" || parameter.endsWith(".detail")
        }.getOrDefault(false)

        private fun extractProviderCode(body: String): String? = runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            val error = root["error"] as? JsonObject
            sequenceOf(
                error?.get("code"),
                error?.get("type"),
                root["code"],
                root["type"]
            ).mapNotNull { it?.jsonPrimitive?.contentOrNull?.trim() }
                .firstOrNull(String::isNotBlank)
        }.getOrNull()

        private fun String?.matchesProviderCode(knownCodes: Set<String>): Boolean {
            val normalized = this?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return false
            return normalized in knownCodes || normalized.substringAfterLast('.') in knownCodes
        }

        private val WHITESPACE = Regex("\\s+")
        private val REASON_SECRET_PATTERNS = listOf(
            Regex("(?i)\\bhttps?://\\S+"),
            Regex("(?i)\\bbearer\\s+\\S+"),
            Regex("(?i)\\b(?:sk|pk|rk|ak|key)-[a-z0-9*._-]{4,}"),
            Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"),
            Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"),
            Regex("\\b(?=[a-zA-Z0-9_-]*[a-zA-Z])(?=[a-zA-Z0-9_-]*\\d)[a-zA-Z0-9_-]{24,}\\b")
        )
        private val CONTEXT_LIMIT_CODES = setOf(
            "context_length_exceeded",
            "max_context_length_exceeded"
        )
        private val IMAGE_UNSUPPORTED_CODES = setOf(
            "image_unsupported",
            "unsupported_image",
            "unsupported_image_input",
            "unsupported_multimodal_input"
        )
        private val PREVIOUS_RESPONSE_CODES = setOf(
            "previous_response_not_found",
            "previousresponsenotfound"
        )
        private val MODEL_UNAVAILABLE_CODES = setOf(
            "model_not_found",
            "model_unavailable",
            "no_available_model"
        )
    }
}

fun Throwable.providerHttpException(): ProviderHttpException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is ProviderHttpException) return current
        current = current.cause
    }
    return null
}
