// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.config

import com.droidance.core.agent.config.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VisionFramePolicy(
    @SerialName("max_long_edge")
    val maxLongEdge: Int,
    @SerialName("max_pixels")
    val maxPixels: Int,
    @SerialName("jpeg_quality")
    val jpegQuality: Int
) {
    fun validate() {
        require(maxLongEdge > 0)
        require(maxPixels > 0)
        require(jpegQuality in 1..100)
    }
}
