// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent

import com.droidance.core.agent.*

import com.droidance.core.device.agent.*

import android.graphics.BitmapFactory

object ScreenVisualFingerprint {
    fun fromBytes(imagePng: ByteArray): Long? {
        if (imagePng.isEmpty()) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 8
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(imagePng, 0, imagePng.size, options) ?: return null
        val scaled = runCatching {
            android.graphics.Bitmap.createScaledBitmap(decoded, 8, 8, true)
        }.getOrNull() ?: run {
            decoded.recycle()
            return null
        }
        return try {
            val luminance = IntArray(64)
            var sum = 0L
            var index = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val color = scaled.getPixel(x, y)
                    val value = (
                        299 * android.graphics.Color.red(color) +
                            587 * android.graphics.Color.green(color) +
                            114 * android.graphics.Color.blue(color)
                        ) / 1000
                    luminance[index++] = value
                    sum += value
                }
            }
            val average = (sum / luminance.size).toInt()
            luminance.foldIndexed(0L) { bit, acc, value ->
                if (value >= average) acc or (1L shl bit) else acc
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /** Luminance of every eighth pixel, fine enough to see a digit appear in a display. */
    fun changeSignature(imagePng: ByteArray): ScreenChangeSignature? {
        if (imagePng.isEmpty()) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 8
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(imagePng, 0, imagePng.size, options) ?: return null
        return try {
            val width = decoded.width
            val height = decoded.height
            if (width <= 0 || height <= 0) return null
            val pixels = IntArray(width * height)
            decoded.getPixels(pixels, 0, width, 0, 0, width, height)
            val luminance = ByteArray(pixels.size) { index ->
                val color = pixels[index]
                ((299 * android.graphics.Color.red(color) + 587 * android.graphics.Color.green(color) +
                    114 * android.graphics.Color.blue(color)) / 1000).toByte()
            }
            ScreenChangeSignature(width, height, luminance)
        } finally {
            decoded.recycle()
        }
    }
}
