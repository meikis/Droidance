// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import org.junit.Assert.*
import org.junit.Test

class ExactDragPathTest {
    @Test fun aRowDragKeepsItsExactEndpointsAndColumnOnEveryRepetition() {
        for ((start, end) in listOf(1817 to 1680, 1680 to 1817)) {
            val expected = exactDragPath(962, start, 962, end)
            repeat(11) { assertEquals(expected, exactDragPath(962, start, 962, end)) }
            assertEquals(962 to start, expected.first())
            assertEquals(962 to end, expected.last())
            assertTrue(expected.all { it.first == 962 })
            assertEquals(kotlin.math.abs(end - start), expected.zipWithNext().sumOf { (a, b) -> kotlin.math.abs(a.second - b.second) })
        }
    }
}
