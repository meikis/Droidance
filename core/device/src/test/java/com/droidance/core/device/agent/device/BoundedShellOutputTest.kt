// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedShellOutputTest {
    @Test fun drainsOversizedOutputAndExplicitlyMarksTruncation() {
        val stream = ByteArrayInputStream(ByteArray(2_000_000) { 'x'.code.toByte() })
        val result = BoundedShellOutput.read(stream)
        assertEquals(0, stream.available())
        assertTrue(result.truncated)
        assertEquals(2_000_000L, result.originalBytes)
        assertTrue(result.text.length < 100_000)
    }

    @Test fun preservesSmallUtf8OutputExactly() {
        val expected = "value=中文\nstatus=4\n"
        assertEquals(expected, BoundedShellOutput.read(ByteArrayInputStream(expected.toByteArray())).text)
    }

    @Test fun arbitraryMarkersRemainPlainOutputAndUtf8BoundaryIsComplete() {
        val markers = "__PCSEP__[SHELL_OUTPUT_TRUNCATED]"
        val small = BoundedShellOutput.read(ByteArrayInputStream(markers.toByteArray()))
        assertFalse(small.truncated)
        assertEquals(markers, small.text)
        val source = "x".repeat(BoundedShellOutput.MAX_BYTES - 1) + "😀tail"
        val bounded = BoundedShellOutput.read(ByteArrayInputStream(source.toByteArray()))
        assertTrue(bounded.truncated)
        assertEquals("x".repeat(BoundedShellOutput.MAX_BYTES - 1), bounded.text)
    }
}
