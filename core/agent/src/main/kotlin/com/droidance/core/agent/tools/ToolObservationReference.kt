// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolExecutionResult

/** Observation identity is metadata; untrusted output text cannot supply or override it. */
object ToolObservationReference {
    fun forGroundedCall(result: ToolExecutionResult, groundsCompletion: Boolean, turnId: String, callId: String): ToolExecutionResult {
        if (!groundsCompletion || result.observationId != null) return result
        val id = java.util.UUID.nameUUIDFromBytes("$turnId\u0000$callId".toByteArray(Charsets.UTF_8))
        return attachToSuccessfulResult(result, "call_$id")
    }

    private val validId = Regex("[a-zA-Z0-9_-]+")
    fun fromResult(result: ToolExecutionResult): String? = result.observationId

    fun attachToSuccessfulResult(result: ToolExecutionResult, observationId: String): ToolExecutionResult {
        if (!result.isVerifiedSuccess || result.observationId != null) return result
        require(validId.matches(observationId)) { "Observation id contains unsupported characters" }
        return result.copy(
            observationId = observationId,
            sourceRef = result.sourceRef ?: observationId
        )
    }
}
