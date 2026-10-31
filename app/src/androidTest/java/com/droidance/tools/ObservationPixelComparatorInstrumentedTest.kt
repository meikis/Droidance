// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Device-side calibration for the real Android PNG decoder and mapped point reads.
 * The fixture is deterministic and does not depend on a foreground app or UI text.
 */
@RunWith(AndroidJUnit4::class)
class ObservationPixelComparatorInstrumentedTest {
    @Test
    fun pngRoundTripSamplesStableAndDetectsCoverageWithCropMapping() {
        val modelSize = 40 to 30
        val physicalSize = 100 to 80
        val mapper = ModelCoordinateMapper(
            scale = 0.5f,
            modelSize = modelSize,
            physicalSize = physicalSize,
            physicalOrigin = ModelCoordinateMapper.Point(10, 8)
        )
        val model = Bitmap.createBitmap(modelSize.first, modelSize.second, Bitmap.Config.ARGB_8888)
        val physical = Bitmap.createBitmap(
            physicalSize.first,
            physicalSize.second,
            Bitmap.Config.ARGB_8888
        )
        val samples = ObservationPixelComparator.mappedGrid(
            mapper = mapper,
            modelSize = modelSize,
            physicalSize = physicalSize,
            sampleSide = 8
        )
        try {
            for (y in 0 until model.height) {
                for (x in 0 until model.width) {
                    model.setPixel(x, y, fixtureColor(x, y))
                }
            }
            physical.eraseColor(Color.rgb(18, 20, 22))
            samples.forEach { sample ->
                physical.setPixel(
                    sample.physicalX,
                    sample.physicalY,
                    model.getPixel(sample.modelX, sample.modelY)
                )
            }

            val decodedModel = decode(encodePng(model))
            val decodedPhysical = decode(encodePng(physical))
            try {
                val reference = ObservationPixelComparator.captureReference(
                    width = decodedModel.width,
                    height = decodedModel.height,
                    samples = samples,
                    pixelAt = decodedModel::getPixel
                )
                val stableComparison = compareRepeatedly(
                    reference = reference,
                    frame = decodedPhysical,
                    channelDelta = 8
                )
                assertEquals(samples.size, stableComparison.sampledPixels)
                assertEquals(0, stableComparison.changedPixels)

                val covered = requireNotNull(decodedPhysical.copy(Bitmap.Config.ARGB_8888, true))
                try {
                    val target = samples[samples.size / 2]
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val x = (target.physicalX + dx).coerceIn(0, covered.width - 1)
                            val y = (target.physicalY + dy).coerceIn(0, covered.height - 1)
                            covered.setPixel(x, y, Color.WHITE)
                        }
                    }
                    val coverageComparison = ObservationPixelComparator.compare(
                        reference = reference,
                        width = covered.width,
                        height = covered.height,
                        pixelAt = covered::getPixel,
                        channelDelta = 8
                    )
                    assertEquals(samples.size, coverageComparison.sampledPixels)
                    assertTrue(coverageComparison.changedPixels >= 1)
                    assertTrue(
                        ObservationPixelComparator.exceedsChangeFraction(
                            coverageComparison,
                            threshold = 0.005
                        )
                    )
                } finally {
                    covered.recycle()
                }
            } finally {
                decodedModel.recycle()
                decodedPhysical.recycle()
            }
        } finally {
            model.recycle()
            physical.recycle()
        }
    }

    private fun compareRepeatedly(
        reference: ObservationPixelReference,
        frame: Bitmap,
        channelDelta: Int
    ): ObservationPixelComparison {
        val elapsedNanos = LongArray(32)
        var last = ObservationPixelComparison(0, 0, 0L, 0)
        elapsedNanos.indices.forEach { index ->
            val started = SystemClock.elapsedRealtimeNanos()
            last = ObservationPixelComparator.compare(
                reference = reference,
                width = frame.width,
                height = frame.height,
                pixelAt = frame::getPixel,
                channelDelta = channelDelta
            )
            elapsedNanos[index] = SystemClock.elapsedRealtimeNanos() - started
        }
        val sorted = elapsedNanos.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95Index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
        val p95 = sorted[p95Index]
        Log.i(
            "ObservationPixelComparator",
            "png_pure_compare_only samples=${last.sampledPixels} " +
                "compare_p50_ns=$p50 compare_p95_ns=$p95 n=${elapsedNanos.size}"
        )
        assertTrue("p95 must not precede p50", p95 >= p50)
        return last
    }

    private fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap = requireNotNull(
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    )

    private fun fixtureColor(x: Int, y: Int): Int = Color.rgb(
        (x * 17 + y * 3) and 0xff,
        (x * 5 + y * 19 + 31) and 0xff,
        (x * 11 + y * 7 + 63) and 0xff
    )
}
