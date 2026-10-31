// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayForegroundParserTest {
    @Test
    fun returnsOnlyRequestedDisplayComponent() {
        val dump = """
            Display #0
              mCurrentFocus=Window{1 u0 com.droidance.debug/.MainActivity}
            Display #96
              mCurrentFocus=Window{2 u0 com.example/.SearchActivity}
            Display #97
              mCurrentFocus=Window{3 u0 com.other/.OtherActivity}
        """.trimIndent()

        assertEquals(
            "com.example/.SearchActivity",
            DisplayForegroundParser.parse(dump, displayId = 96)
        )
    }

    @Test
    fun unknownDisplayDoesNotFallBackToMainDisplay() {
        val dump = """
            Display #0
              mCurrentFocus=Window{1 u0 com.droidance.debug/.MainActivity}
        """.trimIndent()

        assertNull(DisplayForegroundParser.parse(dump, displayId = 96))
    }
}
