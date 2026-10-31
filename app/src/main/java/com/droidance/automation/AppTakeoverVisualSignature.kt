// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Small, app-agnostic visual signature used only to decide whether the retained display deserves
 * another Agent check. It never interprets pixels as a message or a business event.
 */
internal class AppTakeoverVisualSignature private constructor(
    private val rgb: ByteArray
) {
    fun changedFraction(other: AppTakeoverVisualSignature, pixelDelta: Int): Double {
        if (rgb.size != other.rgb.size || rgb.isEmpty()) return 1.0
        var changedPixels = 0
        var index = 0
        while (index < rgb.size) {
            val redChanged = abs(unsigned(rgb[index]) - unsigned(other.rgb[index])) >= pixelDelta
            val greenChanged = abs(unsigned(rgb[index + 1]) - unsigned(other.rgb[index + 1])) >= pixelDelta
            val blueChanged = abs(unsigned(rgb[index + 2]) - unsigned(other.rgb[index + 2])) >= pixelDelta
            if (redChanged || greenChanged || blueChanged) changedPixels += 1
            index += CHANNELS
        }
        return changedPixels.toDouble() / (rgb.size / CHANNELS).toDouble()
    }

    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(rgb)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff

    companion object {
        private const val CHANNELS = 3
        private const val GRID_COLUMNS = 64
        private const val GRID_ROWS = 128
        private const val CONTENT_TOP_PERCENT = 4
        private const val CONTENT_BOTTOM_PERCENT = 96

        fun fromEncodedImage(bytes: ByteArray): AppTakeoverVisualSignature? {
            if (bytes.isEmpty()) return null
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return try {
                val top = (source.height * CONTENT_TOP_PERCENT / 100)
                    .coerceIn(0, (source.height - 1).coerceAtLeast(0))
                val bottom = (source.height * CONTENT_BOTTOM_PERCENT / 100)
                    .coerceIn(top + 1, source.height)
                val content = Bitmap.createBitmap(source, 0, top, source.width, bottom - top)
                try {
                    val scaled = Bitmap.createScaledBitmap(
                        content,
                        GRID_COLUMNS,
                        GRID_ROWS,
                        true
                    )
                    try {
                        val pixels = IntArray(GRID_COLUMNS * GRID_ROWS)
                        scaled.getPixels(
                            pixels,
                            0,
                            GRID_COLUMNS,
                            0,
                            0,
                            GRID_COLUMNS,
                            GRID_ROWS
                        )
                        fromArgb(pixels)
                    } finally {
                        if (scaled !== content) scaled.recycle()
                    }
                } finally {
                    if (content !== source) content.recycle()
                }
            } catch (_: RuntimeException) {
                null
            } finally {
                source.recycle()
            }
        }

        internal fun fromArgb(pixels: IntArray): AppTakeoverVisualSignature {
            val rgb = ByteArray(pixels.size * CHANNELS)
            pixels.forEachIndexed { index, color ->
                val offset = index * CHANNELS
                rgb[offset] = ((color ushr 16) and 0xff).toByte()
                rgb[offset + 1] = ((color ushr 8) and 0xff).toByte()
                rgb[offset + 2] = (color and 0xff).toByte()
            }
            return AppTakeoverVisualSignature(rgb)
        }
    }
}
