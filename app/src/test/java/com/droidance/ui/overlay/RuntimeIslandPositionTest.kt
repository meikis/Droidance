// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeIslandPositionTest {
    @Test fun defaultCenterStaysOneFifthAboveBottomInBothOrientations() {
        for (screenHeight in listOf(720, 1080, 2376, 2780)) {
            for (islandHeight in listOf(37, 111, 183)) {
                val top = RuntimeIslandPosition.defaultTopPx(screenHeight, islandHeight)
                assertEquals(screenHeight * .8f, top + islandHeight / 2f, .5f)
                assertTrue(top >= 0 && top + islandHeight <= screenHeight)
            }
        }
    }

    @Test fun oversizedIslandAnchorsAtTopRatherThanLeavingTheScreen() {
        assertEquals(0, RuntimeIslandPosition.defaultTopPx(100, 120))
        assertEquals(0, RuntimeIslandPosition.defaultTopPx(0, 120))
    }
}
