// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTakeoverVisualSignatureTest {
    @Test
    fun smallRgbNoiseDoesNotCountAsAChangedPixel() {
        val baseline = AppTakeoverVisualSignature.fromArgb(IntArray(100) { 0xff202020.toInt() })
        val noisy = AppTakeoverVisualSignature.fromArgb(IntArray(100) { 0xff292929.toInt() })

        assertEquals(0.0, baseline.changedFraction(noisy, pixelDelta = 18), 0.0)
    }

    @Test
    fun localizedStableContentChangeRemainsDetectable() {
        val before = IntArray(1_000) { 0xffffffff.toInt() }
        val after = before.copyOf().also { pixels ->
            repeat(5) { pixels[it + 300] = 0xffcc0000.toInt() }
        }
        val baseline = AppTakeoverVisualSignature.fromArgb(before)
        val changed = AppTakeoverVisualSignature.fromArgb(after)

        assertEquals(0.005, baseline.changedFraction(changed, pixelDelta = 18), 0.000_001)
        assertTrue(baseline.changedFraction(changed, pixelDelta = 18) >= 0.001)
        assertNotEquals(baseline.fingerprint(), changed.fingerprint())
    }
}
