// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

enum class TextInputOutcome {
    NO_FOCUSED_INPUT_WINDOW,
    PAYLOAD_REJECTED,
    INJECTION_FAILED,
    DELIVERY_UNVERIFIED,
    DELIVERY_VERIFIED
}

data class TextInputEvidence(
    val focusConfirmed: Boolean,
    val payloadConfirmed: Boolean,
    val eventInjected: Boolean,
    val screenChanged: Boolean,
    val deliveryVerified: Boolean,
    val observationId: String? = null,
    val readback: TextInputReadback? = null,
    val injectionDurationMs: Long? = null,
    val readbackDurationMs: Long? = null
) {
    /** 输入通道已接受动作；它与目标输入框内容是否已被读回确认是两类证据。 */
    val accepted: Boolean
        get() = focusConfirmed && payloadConfirmed && eventInjected

    val outcome: TextInputOutcome
        get() = when {
            !focusConfirmed -> TextInputOutcome.NO_FOCUSED_INPUT_WINDOW
            !payloadConfirmed -> TextInputOutcome.PAYLOAD_REJECTED
            !eventInjected -> TextInputOutcome.INJECTION_FAILED
            deliveryVerified -> TextInputOutcome.DELIVERY_VERIFIED
            else -> TextInputOutcome.DELIVERY_UNVERIFIED
        }

    val succeeded: Boolean
        get() = outcome == TextInputOutcome.DELIVERY_VERIFIED

    fun withPostObservation(
        screenChanged: Boolean,
        observationId: String?,
        destinationConfirmed: Boolean = false
    ): TextInputEvidence =
        copy(
            screenChanged = screenChanged,
            deliveryVerified = focusConfirmed && payloadConfirmed && eventInjected && destinationConfirmed,
            observationId = observationId
        )
}

/** Evidence for one input invocation; it does not attest a visual target or task completion. */
data class TextInputReadback(
    val displayId: Int,
    val backendSessionId: String,
    val targetBefore: String,
    val targetAfter: String,
    val payloadHash: String,
    val replacement: Boolean,
    val textMatched: Boolean
) {
    fun matches(text: String, display: Int?, session: String?, focusTarget: String?, clear: Boolean): Boolean =
        textMatched && replacement && clear && displayId == display &&
            backendSessionId == session && backendSessionId.isNotBlank() &&
            targetBefore.contains(";window_id=") && targetBefore == targetAfter &&
            targetAfter == focusTarget && payloadHash == hash(text)

    companion object {
        @JvmStatic fun hash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
