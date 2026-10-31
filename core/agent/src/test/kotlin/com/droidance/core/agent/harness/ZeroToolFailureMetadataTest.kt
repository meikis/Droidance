// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ZeroToolFailureMetadataTest {
    @org.junit.Test fun providerFailurePreservesClassificationWithoutItsRawMessage() {
        val cause = com.droidance.core.model.llm.ProviderHttpException.fromResponse(429, "provider",
            "{\"error\":{\"code\":\"rate_limit\",\"message\":\"private-body\"}}")
        val result = zeroToolFinishMetadata(ZeroToolRoundDecision.FAILED, "PHASE_A_REQUEST_FAILED", IllegalStateException("wrapper", cause))
        org.junit.Assert.assertEquals("429", result["response_http_status"])
        org.junit.Assert.assertEquals("RATE_LIMIT", result["response_error_category"])
        org.junit.Assert.assertEquals("rate_limit", result["response_provider_code"])
        org.junit.Assert.assertFalse(result.values.any { it.contains("private-body") })
    }

    @Test
    fun failedZeroToolPlanPreservesTheHostFailureInsteadOfClaimingAnEmptyModelResponse() {
        for (code in listOf("PHASE_A_REQUEST_FAILED", "PHASE_A_REQUEST_CANCELLED", "PHASE_A_PROTOCOL_ERROR")) {
            val round = NormalizedModelRound.create(
                publicBlocks = emptyList(),
                toolCalls = emptyList(),
                finishMetadata = zeroToolFinishMetadata(ZeroToolRoundDecision.FAILED, code, null)
            )
            val plan = prepareZeroToolExecution(round)
            assertEquals(code, plan.roundPlan.terminalDiagnosticCode)
            assertEquals(RoundFollowUp.TERMINATE_PROTOCOL, plan.roundPlan.followUp)
            assertEquals(RoundExecutionMode.NONE, plan.roundPlan.executionMode)
            assertEquals(null, plan.roundPlan.publicMessage)
            assertEquals(null, plan.attemptBinding)
            assertEquals(emptyList<Any>(), plan.toolStarts)
            assertEquals(emptyList<Any>(), plan.toolRejections)
        }
    }

    @Test
    fun successfulZeroToolPlansKeepDirectReplyAndActivationSemantics() {
        for ((decision, output) in listOf(
            ZeroToolRoundDecision.DIRECT_REPLY to "Hello",
            ZeroToolRoundDecision.ACTIVATE_ANDROID to "I will check the device.",
            ZeroToolRoundDecision.ACTIVATE_LOCAL to "I will look up the local history."
        )) {
            val plan = prepareZeroToolExecution(NormalizedModelRound.create(
                publicBlocks = listOf(output), toolCalls = emptyList(),
                finishMetadata = zeroToolFinishMetadata(decision, null, null)
            ))
            assertEquals(null, plan.roundPlan.terminalDiagnosticCode)
            assertEquals(RoundFollowUp.COMPLETE_AFTER_RESULTS, plan.roundPlan.followUp)
            assertEquals(if (decision == ZeroToolRoundDecision.DIRECT_REPLY) PublicMessageKind.FINAL
                else PublicMessageKind.START, plan.roundPlan.publicMessage?.kind)
            assertEquals(output, plan.roundPlan.publicMessage?.text)
            assertEquals(null, plan.attemptBinding)
        }
    }

    @Test
    fun failedRequestRetainsTypeWithoutSensitiveExceptionText() {
        val failure = InterruptedIOException("secret request content").apply {
            stackTrace = arrayOf(StackTraceElement("example.Transport", "read", "Transport.kt", 42))
        }
        val metadata = zeroToolFinishMetadata(ZeroToolRoundDecision.FAILED,
            "PHASE_A_REQUEST_FAILED", failure)
        assertEquals("java.io.InterruptedIOException", metadata["exception_type"])
        assertEquals("example.Transport.read:42", metadata["exception_origin"])
        assertEquals("PHASE_A_REQUEST_FAILED", metadata[ZERO_TOOL_DIAGNOSTIC_KEY])
        assertFalse(metadata.toString().contains("secret"))
    }

    @Test
    fun successfulZeroToolResponseDoesNotAcquireFailureMetadata() {
        val metadata = zeroToolFinishMetadata(ZeroToolRoundDecision.DIRECT_REPLY, null, null)
        assertFalse(metadata.containsKey("exception_type"))
        assertFalse(metadata.containsKey(ZERO_TOOL_DIAGNOSTIC_KEY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun successfulResponseCannotBeAnnotatedWithAnException() {
        zeroToolFinishMetadata(ZeroToolRoundDecision.DIRECT_REPLY, null, IllegalStateException())
    }
}
