// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools.observe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object VisionFrameScaler {
    data class Config(
        val maxLongEdge: Int,
        val maxPixels: Int,
        val jpegQuality: Int
    )

    data class ScaledFrame(
        val base64Jpeg: String,
        val width: Int,
        val height: Int,
        val scale: Float,
        val physicalWidth: Int,
        val physicalHeight: Int,
        val byteCount: Int
    ) {
        val modelSize: Pair<Int, Int> get() = width to height
        val physicalSize: Pair<Int, Int> get() = physicalWidth to physicalHeight
    }

    fun scale(bytes: ByteArray, config: Config): ScaledFrame? {
        return try {
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val physicalWidth = original.width
            val physicalHeight = original.height
            val scale = computeScale(physicalWidth, physicalHeight, config)
            val (targetWidth, targetHeight) = targetSize(physicalWidth, physicalHeight, scale)
            val scaled = if (targetWidth == physicalWidth && targetHeight == physicalHeight) {
                original
            } else {
                Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true).also {
                    original.recycle()
                }
            }
            val output = ByteArrayOutputStream()
            scaled.compress(
                Bitmap.CompressFormat.JPEG,
                config.jpegQuality.coerceIn(1, 100),
                output
            )
            scaled.recycle()
            val jpeg = output.toByteArray()
            ScaledFrame(
                base64Jpeg = Base64.encodeToString(jpeg, Base64.NO_WRAP),
                width = targetWidth,
                height = targetHeight,
                scale = scale,
                physicalWidth = physicalWidth,
                physicalHeight = physicalHeight,
                byteCount = jpeg.size
            )
        } catch (_: Exception) {
            null
        }
    }

    fun computeScale(width: Int, height: Int, config: Config): Float {
        if (width <= 0 || height <= 0) return 1f
        if (config.maxLongEdge <= 0 || config.maxPixels <= 0) return 1f
        val longEdge = max(width, height).toDouble()
        val pixels = width.toDouble() * height.toDouble()
        val longEdgeScale = config.maxLongEdge.toDouble() / longEdge
        val pixelScale = sqrt(config.maxPixels.toDouble() / pixels)
        return minOf(1.0, longEdgeScale, pixelScale).coerceAtLeast(0.01).toFloat()
    }

    fun targetSize(width: Int, height: Int, scale: Float): Pair<Int, Int> {
        val effectiveScale = scale.takeIf { it > 0f } ?: 1f
        return max(1, (width * effectiveScale).roundToInt()) to
            max(1, (height * effectiveScale).roundToInt())
    }

}
