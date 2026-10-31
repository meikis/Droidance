// SPDX-FileCopyrightText: 2026 Ashin (YBBLab)
// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeScreenFrameInstrumentedTest {
    @Test fun nativePixelsLargerThanBinderPayloadSurviveTheDescriptorReply() {
        val width = 1080
        val height = 512
        var random = 0x3a2719
        val pixels = IntArray(width * height) {
            random = random xor (random shl 13)
            random = random xor (random ushr 17)
            random = random xor (random shl 5)
            0xff000000.toInt() or (random and 0xffffff)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        assertTrue("Test must exceed an ordinary Binder byte-array transaction", png.size > 1024 * 1024)
        val facts = Bundle().apply { putInt("referenceWidth", width); putInt("referenceHeight", height) }
        val sender = NativeScreenFrame.fromEncodedImage(png, facts)
        val parcel = Parcel.obtain()
        try {
            sender.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            assertThrows(IllegalStateException::class.java) { sender.readEncodedImage() }
            parcel.setDataPosition(0)
            NativeScreenFrame.CREATOR.createFromParcel(parcel).use { receiver ->
                val received = receiver.readEncodedImage()
                assertArrayEquals(png, received)
                assertEquals(width, receiver.metadata.getInt("referenceWidth"))
                assertEquals(height, receiver.metadata.getInt("referenceHeight"))
                val decoded = BitmapFactory.decodeByteArray(received, 0, received.size)
                try {
                    assertEquals(width, decoded.width); assertEquals(height, decoded.height)
                    val actual = IntArray(pixels.size)
                    decoded.getPixels(actual, 0, width, 0, 0, width, height)
                    assertArrayEquals(pixels, actual)
                } finally { decoded.recycle() }
            }
        } finally { sender.close(); parcel.recycle() }
    }

    @Test fun transferredEvidenceCannotBeMappedForWriting() {
        NativeScreenFrame.fromEncodedImage(byteArrayOf(1, 2, 3), Bundle()).use { frame ->
            val field = NativeScreenFrame::class.java.getDeclaredField("memory").apply { isAccessible = true }
            val memory = field.get(frame) as SharedMemory
            assertThrows(ErrnoException::class.java) { memory.mapReadWrite() }
            assertArrayEquals(byteArrayOf(1, 2, 3), frame.readEncodedImage())
        }
    }

    @Test fun failedConsumerAndRepeatedRepliesReleaseTheirDescriptors() {
        val before = nativeDescriptorCount()
        repeat(30) {
            val sender = NativeScreenFrame.fromEncodedImage(byteArrayOf(1, 2, 3), Bundle())
            val parcel = Parcel.obtain()
            try {
                sender.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                parcel.setDataPosition(0)
                val receiver = NativeScreenFrame.CREATOR.createFromParcel(parcel)
                assertThrows(IllegalStateException::class.java) {
                    receiver.use { error("Consumer rejected the frame") }
                }
                assertThrows(IllegalStateException::class.java) { receiver.readEncodedImage() }
            } finally { sender.close(); parcel.recycle() }
        }
        assertEquals(before, nativeDescriptorCount())
    }

    @Test fun packetMetadataDoesNotRetainMutableCallerState() {
        val colors = intArrayOf(1, 2)
        val coordinates = intArrayOf(3, 4)
        val facts = Bundle().apply {
            putInt("referenceWidth", 1080)
            putIntArray("referenceColors", colors)
            putIntArray("referenceCoordinates", coordinates)
        }
        NativeScreenFrame.fromEncodedImage(byteArrayOf(1), facts).use { frame ->
            facts.putInt("referenceWidth", 720)
            colors[0] = 9; coordinates[0] = 8
            val received = frame.metadata
            received.putInt("referenceWidth", 1)
            received.getIntArray("referenceColors")!![1] = 7
            received.getIntArray("referenceCoordinates")!![1] = 6
            assertEquals(1080, frame.metadata.getInt("referenceWidth"))
            assertArrayEquals(intArrayOf(1, 2), frame.metadata.getIntArray("referenceColors"))
            assertArrayEquals(intArrayOf(3, 4), frame.metadata.getIntArray("referenceCoordinates"))
        }
    }

    private fun nativeDescriptorCount(): Int = File("/proc/self/fd").listFiles().orEmpty().count { descriptor ->
        runCatching { Os.readlink(descriptor.path).contains("droidance-native-frame") }.getOrDefault(false)
    }
}
