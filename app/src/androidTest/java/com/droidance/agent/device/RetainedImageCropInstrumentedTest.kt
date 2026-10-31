// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.tools.observe.RetainedImageCrop
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetainedImageCropInstrumentedTest {
    @Test fun croppedPixelsBelongToOriginalBytesEvenAfterSourceChanges() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val original = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.eraseColor(Color.BLUE); source.recycle()
        val result = RetainedImageCrop.crop("""{"left":250,"top":250,"right":750,"bottom":750,"zoom":2}""", original)
        val image = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        assertEquals(100, result.width); assertEquals(100, result.height)
        assertEquals(Color.RED, image.getPixel(50, 50)); image.recycle()
    }
    @Test fun selectedRegionUsesSourceImageCoordinates() {
        val source = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        for (x in 50..99) for (y in 0..49) source.setPixel(x, y, Color.BLUE)
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.recycle()
        val result = RetainedImageCrop.crop("""{"left":500,"top":0,"right":1000,"bottom":1000,"zoom":1}""", bytes)
        val image = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        assertEquals(50, image.width); assertEquals(Color.BLUE, image.getPixel(25, 25)); image.recycle()
        assertEquals(com.droidance.core.agent.event.ImageSourceRegion(50, 0, 50, 50, 100, 50), result.sourceRegion)
    }
    @Test fun invalidCoordinatesAreRejectedBeforeImageDecode() {
        for (args in listOf("""{"left":-1,"top":0,"right":500,"bottom":500}""",
            """{"left":"1","top":0,"right":500,"bottom":500}""",
            """{"left":500,"top":0,"right":500,"bottom":500}""")) {
            assertThrows(RuntimeException::class.java) { RetainedImageCrop.crop(args, byteArrayOf()) }
        }
    }
}
