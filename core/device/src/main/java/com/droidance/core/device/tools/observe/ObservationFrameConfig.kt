// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools.observe

import com.droidance.core.agent.config.VisionFramePolicy

data class ObservationFrameConfig(
    val maxLongEdge: Int,
    val maxPixels: Int,
    val jpegQuality: Int
) {
    fun scalerConfig(): VisionFrameScaler.Config = VisionFrameScaler.Config(
        maxLongEdge = maxLongEdge,
        maxPixels = maxPixels,
        jpegQuality = jpegQuality
    )

    companion object {
        fun fromPolicy(policy: VisionFramePolicy): ObservationFrameConfig = ObservationFrameConfig(
            maxLongEdge = policy.maxLongEdge,
            maxPixels = policy.maxPixels,
            jpegQuality = policy.jpegQuality
        )
    }
}
