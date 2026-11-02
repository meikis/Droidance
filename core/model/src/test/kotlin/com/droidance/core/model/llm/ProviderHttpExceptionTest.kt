// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpExceptionTest {
    @Test fun retryAfterParsesSecondsAndHttpDateButRejectsInvalidValues() {
        val now = java.time.Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
        fun parse(value: String?) = ProviderHttpException.fromResponse(429, "fixture", "{}", value, now).retryAfterMs
        assertEquals(5000L, parse("5"))
        assertEquals(7000L, parse("Mon, 12 Oct 2026 00:00:07 GMT"))
        assertEquals(0L, parse("Mon, 12 Oct 2026 00:00:00 GMT"))
        assertNull(parse("-1"))
        assertNull(parse("invalid"))
        assertNull(parse(null))
    }
    @Test fun permissionDenialIsSeparateFromAuthentication() {
        val body = """{"error":{"code":"forbidden","message":"Your plan does not include this model"}}"""
        val denied = ProviderHttpException.fromResponse(403, "provider", body)
        assertEquals(ProviderErrorCategory.PERMISSION_DENIED, denied.category)
        assertEquals("Your plan does not include this model", denied.publicReason)
        val unauthorized = ProviderHttpException.fromResponse(401, "provider", body)
        assertEquals(ProviderErrorCategory.AUTHENTICATION, unauthorized.category)
        assertNull(unauthorized.publicReason)
    }

    @Test fun permissionReasonIsReadFromCommonBodyShapes() {
        fun reason(body: String) = ProviderHttpException.fromResponse(403, "provider", body).publicReason
        assertEquals("denied", reason("""{"error":"denied"}"""))
        assertEquals("denied", reason("""{"message":"denied"}"""))
        assertEquals("denied", reason("""{"msg":"denied"}"""))
        assertEquals("denied", reason("""{"detail":"denied"}"""))
        assertEquals("denied", reason(""""denied""""))
        assertEquals("Access denied", reason("Access denied"))
        assertEquals("Forbidden", reason("Forbidden"))
        assertNull(reason("<html><body>403 Forbidden</body></html>"))
        assertNull(reason("""{"error":{"code":403}}"""))
        assertNull(reason("null"))
        assertNull(reason(""))
    }

    @Test fun permissionReasonNeverCarriesSecretsAndStaysShort() {
        val apiKey = "plain-config-value"
        fun reason(message: String) = requireNotNull(ProviderHttpException.fromResponse(
            403, "provider", """{"error":{"message":"$message"}}""",
            redactor = TransportDiagnosticRedactor(apiKey)
        ).publicReason)
        for ((message, secret) in listOf(
            "Key $apiKey is blocked" to apiKey,
            "Key sk-abc123def456ghi789 is blocked" to "sk-abc123def456ghi789",
            "Key sk-ab***yz is blocked" to "sk-ab***yz",
            "Header Bearer abc.def-123 was refused" to "abc.def-123",
            "Token a1B2c3D4e5F6g7H8i9J0k1L2m3N4 was refused" to "a1B2c3D4e5F6g7H8i9J0k1L2m3N4",
            "See https://relay.example/billing?token=abc for details" to "relay.example",
            "Account owner@example.com is suspended" to "owner@example.com",
            "Address 203.0.113.9 is not allowed" to "203.0.113.9"
        )) {
            val redacted = reason(message)
            assertFalse(message, redacted.contains(secret))
            assertTrue(message, redacted.contains("[REDACTED]"))
        }
        assertEquals("line one line two", reason("line one\\n  line two"))
        assertEquals(200, reason("x ".repeat(500)).length)
    }

    @Test fun recognizesOnlyTheExplicitImageEndpointRejection() {
        val exact = """{"error":{"code":404,"message":"No endpoints found that support image input"}}"""
        assertEquals("IMAGE_INPUT_UNAVAILABLE", ProviderHttpException.fromResponse(404, "provider", exact).category.name)
        for (body in listOf(
            """{"error":{"code":404,"message":"No endpoints found"}}""",
            """{"error":{"code":404,"message":"An image request failed"}}""",
            """{"error":{"code":"model_not_found","message":"No endpoints found that support image input"}}""",
            """{"message":"No endpoints found that support image input"}"""
        )) assertTrue(ProviderHttpException.fromResponse(404, "provider", body).category.name != "IMAGE_INPUT_UNAVAILABLE")
        for (status in listOf(400, 401, 402, 403, 429, 500)) {
            assertTrue(ProviderHttpException.fromResponse(status, "provider", exact).category.name != "IMAGE_INPUT_UNAVAILABLE")
        }
        assertEquals(ProviderErrorCategory.BILLING, ProviderHttpException.fromResponse(402, "provider", exact).category)
        assertEquals(ProviderErrorCategory.SERVER, ProviderHttpException.fromResponse(500, "provider", exact).category)
    }

    @Test fun onlyExplicitReasoningRejectionsAreEligibleForDefaultRetry() {
        for (param in listOf("reasoning", "reasoning.effort", "reasoning_effort")) {
            assertEquals(ProviderErrorCategory.REASONING_UNSUPPORTED, ProviderHttpException.fromResponse(
                400, "provider", """{"error":{"param":"$param","message":"unsupported value"}}""").category)
        }
        assertEquals(ProviderErrorCategory.REASONING_UNSUPPORTED, ProviderHttpException.fromResponse(
            422, "provider", """{"error":{"code":"unsupported_parameter","message":"Unsupported parameter: 'reasoning'"}}""").category)
        for (status in listOf(401, 429, 500)) {
            assertTrue(ProviderHttpException.fromResponse(status,"provider", """{"error":{"param":"reasoning.effort"}}""").category != ProviderErrorCategory.REASONING_UNSUPPORTED)
        }
        assertEquals(ProviderErrorCategory.REQUEST_REJECTED, ProviderHttpException.fromResponse(
            400,"provider", """{"error":{"message":"Something about reasoning failed"}}""").category)
        assertEquals(ProviderErrorCategory.REQUEST_REJECTED, ProviderHttpException.fromResponse(
            400,"provider", """{"error":{"type":"invalid_request_error","param":"tools","message":"Invalid tools on reasoning_effort model"}}""").category)
    }
    @Test
    fun classifiesHttpStatusAndProviderCodeWithoutMessageKeywords() {
        val auth = ProviderHttpException.fromResponse(401, "provider", "not json")
        val image = ProviderHttpException.fromResponse(
            400,
            "provider",
            """{"error":{"code":"unsupported_image_input","message":"rejected"}}"""
        )
        val previous = ProviderHttpException.fromResponse(
            404,
            "provider",
            """{"error":{"type":"previous_response_not_found"}}"""
        )
        val namespacedPrevious = ProviderHttpException.fromResponse(
            400,
            "provider",
            """{"error":{"code":"InvalidParameter.PreviousResponseNotFound"}}"""
        )

        assertEquals(ProviderErrorCategory.AUTHENTICATION, auth.category)
        assertNull(auth.providerCode)
        assertEquals(ProviderErrorCategory.IMAGE_UNSUPPORTED, image.category)
        assertEquals("unsupported_image_input", image.providerCode)
        assertEquals(ProviderErrorCategory.PREVIOUS_RESPONSE_NOT_FOUND, previous.category)
        assertEquals(
            ProviderErrorCategory.PREVIOUS_RESPONSE_NOT_FOUND,
            namespacedPrevious.category
        )
    }

    @Test
    fun genericClientFailureDoesNotPretendToBeAnImageCapabilityFailure() {
        val failure = ProviderHttpException.fromResponse(
            400,
            "provider",
            """{"error":{"message":"image request was invalid"}}"""
        )

        assertEquals(ProviderErrorCategory.REQUEST_REJECTED, failure.category)
    }

    @Test
    fun classifiesStructuredImageDetailParameterRejection() {
        val failure = ProviderHttpException.fromResponse(
            400,
            "provider",
            """{"error":{"code":"InvalidParameter","message":"unsupported value","param":"input.content.detail"}}"""
        )

        assertEquals(ProviderErrorCategory.IMAGE_DETAIL_UNSUPPORTED, failure.category)
    }
}
