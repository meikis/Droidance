// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Shared, immutable sprites. Their generated black source backdrop is removed once on import. */
internal object MascotSprites {
    private val cache = HashMap<Int, Bitmap>()

    @Synchronized
    fun load(context: Context, resource: Int): Bitmap = cache.getOrPut(resource) {
        val source = requireNotNull(BitmapFactory.decodeResource(context.resources, resource,
            BitmapFactory.Options().apply { inSampleSize = 2; inScaled = false }))
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        BlackBackdropMask.clear(pixels, source.width, source.height)
        Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
            .also { source.recycle() }
    }
}
