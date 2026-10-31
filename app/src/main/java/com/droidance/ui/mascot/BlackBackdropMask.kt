// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

/** Sprite import: remove only the black backdrop connected to the canvas border. */
internal object BlackBackdropMask {
    fun clear(pixels: IntArray, width: Int, height: Int) {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val queue = IntArray(pixels.size)
        var read = 0
        var write = 0
        fun visit(index: Int) {
            val color = pixels[index]
            if (color ushr 24 != 0 && (color shr 16 and 255) < 24 &&
                (color shr 8 and 255) < 24 && (color and 255) < 24) {
                pixels[index] = 0
                queue[write++] = index
            }
        }
        for (x in 0 until width) { visit(x); visit((height - 1) * width + x) }
        for (y in 0 until height) { visit(y * width); visit(y * width + width - 1) }
        while (read < write) {
            val index = queue[read++]
            if (index % width > 0) visit(index - 1)
            if (index % width < width - 1) visit(index + 1)
            if (index >= width) visit(index - width)
            if (index < pixels.size - width) visit(index + width)
        }
        // The source edge was antialiased over black. Recover coverage at that outer edge
        // so pearl-white silhouettes have no dark dotted fringe on a light surface.
        for (index in pixels.indices) {
            val color = pixels[index]
            if (color ushr 24 == 0) continue
            val edge = index % width == 0 || index % width == width - 1 || index < width ||
                index >= pixels.size - width || pixels[index - 1] == 0 || pixels[index + 1] == 0 ||
                pixels[index - width] == 0 || pixels[index + width] == 0
            if (!edge) continue
            val r = color shr 16 and 255
            val g = color shr 8 and 255
            val b = color and 255
            val coverage = maxOf(r, g, b)
            if (coverage in 1..254) pixels[index] = (coverage shl 24) or
                ((r * 255 / coverage) shl 16) or ((g * 255 / coverage) shl 8) or (b * 255 / coverage)
        }
    }
}
