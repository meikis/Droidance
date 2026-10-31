// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.Serializable

/** Immutable page evidence. It never grants a current-screen coordinate lease. */
@Serializable
data class BatchScreenshotEvidence(
    val actionIndex: Int,
    val capturedAtMs: Long,
    val executionId: String,
    val backendSessionId: String,
    val displayId: Int,
    val rotationDegrees: Int,
    val attachment: DetailAttachmentAddedEventPayload
)
