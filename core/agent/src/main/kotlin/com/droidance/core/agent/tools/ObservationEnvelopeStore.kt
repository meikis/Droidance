// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/**
 * An image and its coordinate metadata are one observation. Publishing through a
 * capture token prevents an older asynchronous capture from replacing a newer one.
 */
data class ObservationEnvelope(
    val observationId: String,
    val executionId: String,
    val backendSessionId: String,
    val displayId: Int,
    val rotationDegrees: Int,
    val actionId: String,
    val sequence: Long,
    val capturedAtElapsedMs: Long,
    val imageBase64: String?,
    val mode: String,
    val frameScale: Float,
    val frameSize: Pair<Int, Int>?,
    val physicalFrameSize: Pair<Int, Int>?,
    val frameOrigin: Pair<Int, Int>,
    val readableText: String? = null,
    val screenText: String? = null,
    val producedAtMs: Long? = null,
    val pixelReference: ObservationPixelReference? = null,
    /** Retained physical evidence; imageBase64 is the independently encoded model frame. */
    val nativeImagePng: ByteArray? = null,
    val nativeFrameSha256: String? = null,
    val nativeVisualFingerprint: Long? = null,
    /** Foreground activity or window the frame was captured on; identifies the surface, not its content. */
    val foregroundComponent: String? = null
)

class ObservationEnvelopeStore {
    class CaptureToken internal constructor(
        val observationId: String,
        val executionId: String,
        val backendSessionId: String,
        val displayId: Int,
        val rotationDegrees: Int,
        val actionId: String,
        internal val generation: Long,
        internal val sequence: Long
    )

    private var generation = 0L
    private var sequence = 0L
    private var latestToken: CaptureToken? = null
    private var pending: ObservationEnvelope? = null

    @Synchronized
    fun beginCapture(
        executionId: String,
        backendSessionId: String,
        displayId: Int,
        rotationDegrees: Int,
        actionId: String
    ): CaptureToken {
        require(executionId.isNotBlank()) { "Observation executionId cannot be blank" }
        require(backendSessionId.isNotBlank()) { "Observation backendSessionId cannot be blank" }
        require(displayId >= 0) { "Observation displayId cannot be negative" }
        require(actionId.isNotBlank()) { "Observation actionId cannot be blank" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "Observation rotationDegrees must be 0, 90, 180, or 270"
        }
        val observationId = "call_${java.util.UUID.randomUUID()}"
        sequence += 1
        return CaptureToken(
            observationId = observationId,
            executionId = executionId,
            backendSessionId = backendSessionId,
            displayId = displayId,
            rotationDegrees = rotationDegrees,
            actionId = actionId,
            generation = generation,
            sequence = sequence
        ).also {
            latestToken = it
            pending = null
        }
    }

    @Synchronized
    fun publish(
        token: CaptureToken,
        capturedAtElapsedMs: Long,
        imageBase64: String?,
        mode: String,
        frameScale: Float,
        frameSize: Pair<Int, Int>?,
        physicalFrameSize: Pair<Int, Int>?,
        frameOrigin: Pair<Int, Int>,
        readableText: String? = null,
        screenText: String? = null,
        producedAtMs: Long? = null,
        pixelReference: ObservationPixelReference? = null,
        nativeImagePng: ByteArray? = null,
        nativeFrameSha256: String? = null,
        nativeVisualFingerprint: Long? = null,
        foregroundComponent: String? = null
    ): Boolean {
        if ((imageBase64.isNullOrBlank() && readableText.isNullOrBlank() && screenText.isNullOrBlank()) ||
            token != latestToken || token.generation != generation
        ) {
            return false
        }
        pending = ObservationEnvelope(
            observationId = token.observationId,
            executionId = token.executionId,
            backendSessionId = token.backendSessionId,
            displayId = token.displayId,
            rotationDegrees = token.rotationDegrees,
            actionId = token.actionId,
            sequence = token.sequence,
            capturedAtElapsedMs = capturedAtElapsedMs,
            imageBase64 = imageBase64,
            mode = mode,
            frameScale = frameScale,
            frameSize = frameSize,
            physicalFrameSize = physicalFrameSize,
            frameOrigin = frameOrigin,
            readableText = readableText,
            screenText = screenText,
            producedAtMs = producedAtMs,
            pixelReference = pixelReference,
            nativeImagePng = nativeImagePng?.copyOf(),
            nativeFrameSha256 = nativeFrameSha256.takeIf { nativeImagePng != null },
            nativeVisualFingerprint = nativeVisualFingerprint.takeIf { nativeImagePng != null },
            foregroundComponent = foregroundComponent
        )
        return true
    }

    @Synchronized
    fun peek(executionId: String, backendSessionId: String, displayId: Int): ObservationEnvelope? =
        pending?.takeIf {
            it.executionId == executionId &&
                it.backendSessionId == backendSessionId &&
                it.displayId == displayId
        }

    @Synchronized
    fun consume(executionId: String, backendSessionId: String, displayId: Int): ObservationEnvelope? {
        val value = peek(executionId, backendSessionId, displayId) ?: return null
        pending = null
        return value
    }

    @Synchronized
    fun invalidate() {
        generation += 1
        latestToken = null
        pending = null
    }
}
