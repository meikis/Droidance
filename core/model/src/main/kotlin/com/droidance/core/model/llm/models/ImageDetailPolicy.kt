// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.models

import kotlinx.serialization.Serializable

/** Concrete wire value. SERVICE_DEFAULT means the detail field is omitted. */
@Serializable
enum class ImageDetailOption(val wireValue: String?) {
    ORIGINAL("original"),
    HIGH("high"),
    LOW("low"),
    AUTO("auto"),
    SERVICE_DEFAULT(null)
}

data class ImageDetailCapabilityStatus(
    val effective: ImageDetailOption?,
    val detectedAtMillis: Long?,
    val returnedModel: String?,
    val needsDetection: Boolean
)

data class ImageDetailCapabilitySnapshot(
    val accepted: ImageDetailOption?,
    val rejected: Set<ImageDetailOption>,
    val detectedAtMillis: Long
)

object ImageDetailNegotiation {
    val fallbackOrder = listOf(
        ImageDetailOption.ORIGINAL,
        ImageDetailOption.HIGH,
        ImageDetailOption.AUTO,
        ImageDetailOption.SERVICE_DEFAULT,
        ImageDetailOption.LOW
    )

    fun candidates(
        capability: ImageDetailCapabilitySnapshot?,
        nowMillis: Long,
        ttlMillis: Long
    ): List<ImageDetailOption> {
        val fresh = capability?.detectedAtMillis?.let { detectedAt ->
            detectedAt > 0L && nowMillis - detectedAt < ttlMillis
        } == true
        return capability?.accepted?.takeIf { fresh }?.let { accepted ->
            listOf(accepted) + fallbackOrder.filter { it != accepted && it !in capability.rejected }
        } ?: fallbackOrder
    }
}
