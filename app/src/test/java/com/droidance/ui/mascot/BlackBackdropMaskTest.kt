// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import org.junit.Assert.*
import org.junit.Test

class BlackBackdropMaskTest {
    @Test fun clearsTheBackdropWithoutPunchingThroughTheDarkFace() {
        val black = 0xff080808.toInt()
        val white = 0xfffefeff.toInt()
        val pixels = IntArray(49) { black }
        for (y in 1..5) for (x in 1..5) pixels[y * 7 + x] = white
        for (y in 2..4) for (x in 2..4) pixels[y * 7 + x] = black
        BlackBackdropMask.clear(pixels, 7, 7)
        assertEquals(0, pixels[0])
        assertEquals(0, pixels[48])
        assertEquals(black, pixels[24])
        assertEquals(white, pixels[8])
    }
    @Test fun backgroundBetweenTheLegsRemainsTransparent() {
        val pixels = IntArray(25) { 0xff000000.toInt() }
        for (y in 1..4) { pixels[y * 5 + 1] = -1; pixels[y * 5 + 3] = -1 }
        BlackBackdropMask.clear(pixels, 5, 5)
        assertEquals(0, pixels[22])
        assertEquals(-1, pixels[21])
        assertEquals(-1, pixels[23])
    }
}
