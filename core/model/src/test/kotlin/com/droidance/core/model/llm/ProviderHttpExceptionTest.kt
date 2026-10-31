// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import org.junit.Assert.assertEquals
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
