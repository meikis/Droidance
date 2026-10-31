// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools.observe

import com.droidance.core.device.tools.observe.*

import com.droidance.tools.observe.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

/** A pure image transform. No device reads, coordinate lease or freshness are produced. */
object RetainedImageCrop {
    data class Image(val bytes: ByteArray, val width: Int, val height: Int,
                     val sourceRegion: com.droidance.core.agent.event.ImageSourceRegion)

    fun crop(arguments: String, original: ByteArray): Image {
        val args = Json.parseToJsonElement(arguments).jsonObject
        fun bound(name: String) = (args[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?.also { require(it in 0..1000) { "CROP_BOUND_OUT_OF_RANGE:$name" } }
            ?: error("CROP_INTEGER_REQUIRED:$name")
        val left = bound("left"); val top = bound("top")
        val right = bound("right"); val bottom = bound("bottom")
        require(right > left && bottom > top) { "CROP_EMPTY_REGION" }
        val zoom = args["zoom"]?.let {
            (it as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.doubleOrNull
                ?.also { value -> require(value.isFinite() && value in 1.0..4.0) { "CROP_ZOOM_OUT_OF_RANGE" } }
                ?: error("CROP_NUMBER_REQUIRED:zoom")
        } ?: 2.0
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size) ?: error("CROP_SOURCE_DECODE_FAILED")
        try {
            val x = left * bitmap.width / 1000; val y = top * bitmap.height / 1000
            val width = (right * bitmap.width / 1000 - x).coerceAtLeast(1)
            val height = (bottom * bitmap.height / 1000 - y).coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(bitmap, x, y, width, height)
            val zoomed = Bitmap.createScaledBitmap(cropped,
                (width * zoom).toInt().coerceAtLeast(1), (height * zoom).toInt().coerceAtLeast(1), true)
            try {
                val output = ByteArrayOutputStream()
                zoomed.compress(Bitmap.CompressFormat.PNG, 100, output)
                return Image(output.toByteArray(), zoomed.width, zoomed.height,
                    com.droidance.core.agent.event.ImageSourceRegion(x, y, width, height, bitmap.width, bitmap.height))
            } finally { if (zoomed !== cropped) zoomed.recycle(); cropped.recycle() }
        } finally { bitmap.recycle() }
    }
}
