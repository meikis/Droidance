// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.ToolEventStatus
import com.droidance.core.agent.ToolResultStatus
import com.droidance.core.agent.event.ActionDeliveryEvidence
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import kotlinx.coroutines.CancellationException

class ToolProtocolResultTest {
    @Test
    fun deliveredActionCanBePersistedWithoutClaimingVerifiedSuccess() {
        val result = ToolProtocolResult.accepted("launch accepted")
        val payload = com.droidance.core.agent.event.ToolFinishedEventPayload(
            contractVersion = 1,
            result = result,
            roundDetailRef = "round:accepted-action"
        )
        assertEquals("ACTION_DELIVERED", payload.result.diagnosticCode)
        assertEquals(com.droidance.core.agent.event.ToolEvidenceStatus.UNKNOWN, payload.result.evidenceStatus)
        assertEquals(false, payload.result.isVerifiedSuccess)
        assertEquals(ToolEventStatus.UNVERIFIED, ToolResultStatus.eventStatus(payload.result))
    }

    @Test
    fun interruptedResultCarriesUnknownEffectEvidence() {
        val result = ToolProtocolResult.interrupted(
            code = "SCREEN_ACT_BATCH_INTERRUPTED",
            details = buildJsonObject {
                put("status", JsonPrimitive("EFFECT_UNKNOWN"))
            }
        )

        assertEquals(ToolExecutionStatus.INTERRUPTED, result.executionStatus)
        assertEquals(ToolEvidenceStatus.UNKNOWN, result.evidenceStatus)
        assertEquals(ActionDeliveryEvidence.UNKNOWN, result.actionEvidence?.delivery)
        assertEquals(ToolEventStatus.FAILED, ToolResultStatus.eventStatus(result))
        val payload = Json.parseToJsonElement(result.outputText).jsonObject
        assertEquals("INTERRUPTED", payload.string("execution_status"))
        assertEquals("SCREEN_ACT_BATCH_INTERRUPTED", payload.string("code"))
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingAToolFailure() {
        val cancellation = CancellationException("user stopped the turn")
        try {
            ToolProtocolResult.failed("TOOL_EXECUTION_FAILED", true, cancellation)
            fail("Cancellation must reach the execution lifecycle owner")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun executionEntryRejectionsNeverBecomeSuccessfulActions() {
        listOf("STALE_OBSERVATION", "TOOL_NOT_FOUND", "CHANNEL_DOWN").forEach { code ->
            val result = ToolProtocolResult.notExecuted(code, retryable = code == "STALE_OBSERVATION")
            assertEquals(true, ToolResultStatus.isNotExecuted(result))
            assertEquals(false, ToolResultStatus.isSuccess(result))
            assertEquals(ToolEventStatus.UNVERIFIED, ToolResultStatus.eventStatus(result))
            assertEquals(code, ToolResultStatus.diagnosticCode(result))
        }
    }

    @Test
    fun notExecutedResultAlwaysCarriesTheCanonicalFields() {
        val result = ToolProtocolResult.notExecuted(
            code = "ARGUMENT_SCHEMA_MISMATCH",
            retryable = true,
            details = buildJsonObject {
                put("tool", JsonPrimitive("screen_act"))
            },
            evidenceRefs = listOf("obs_7", "", "obs_7")
        )
        val payload = Json.parseToJsonElement(result.outputText).jsonObject

        assertEquals("NOT_EXECUTED", payload.string("execution_status"))
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", payload.string("code"))
        assertEquals(true, payload.getValue("retryable").jsonPrimitive.boolean)
        assertEquals("screen_act", payload.getValue("details").jsonObject.string("tool"))
        assertEquals(JsonArray(listOf(JsonPrimitive("obs_7"))), payload["evidenceRefs"])
        assertEquals(ToolEventStatus.UNVERIFIED, ToolResultStatus.eventStatus(result))
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", ToolResultStatus.diagnosticCode(result))
    }

    @Test
    fun failedResultEscapesProviderTextAsJsonData() {
        val result = ToolProtocolResult.failed(
            code = "TOOL_EXECUTION_FAILED",
            retryable = false,
            details = buildJsonObject {
                put("message", JsonPrimitive("bad \"payload\"\nline two"))
            }
        )
        val payload = Json.parseToJsonElement(result.outputText).jsonObject

        assertEquals("FAILED", payload.string("execution_status"))
        assertEquals("bad \"payload\"\nline two", payload.getValue("details").jsonObject.string("message"))
        assertEquals(ToolEventStatus.FAILED, ToolResultStatus.eventStatus(result))
    }

    @Test
    fun commonArgumentAndExceptionFailuresUseTheSameEnvelope() {
        val missing = Json.parseToJsonElement(ToolProtocolResult.missingArgument("query").outputText).jsonObject
        val invalid = Json.parseToJsonElement(ToolProtocolResult.invalidArguments().outputText).jsonObject
        val invalidValue = Json.parseToJsonElement(
            ToolProtocolResult.invalidArgument("op", "unsupported_value", "unknown").outputText
        ).jsonObject
        val failed = Json.parseToJsonElement(
            ToolProtocolResult.failed("DEEPLINK_QUERY_FAILED", retryable = true, IllegalStateException("offline")).outputText
        ).jsonObject

        assertEquals("ARGUMENT_SCHEMA_MISMATCH", missing.string("code"))
        assertEquals("query", missing.getValue("details").jsonObject.string("missing_parameter"))
        assertEquals("invalid_json", invalid.getValue("details").jsonObject.string("reason"))
        assertEquals("op", invalidValue.getValue("details").jsonObject.string("parameter"))
        assertEquals("unsupported_value", invalidValue.getValue("details").jsonObject.string("reason"))
        assertEquals("unknown", invalidValue.getValue("details").jsonObject.string("value"))
        assertEquals("IllegalStateException", failed.getValue("details").jsonObject.string("error_type"))
        assertEquals("offline", failed.getValue("details").jsonObject.string("message"))
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.content
}
