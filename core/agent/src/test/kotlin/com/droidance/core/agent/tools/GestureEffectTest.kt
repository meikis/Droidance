// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.ScreenChangeSignature
import org.junit.Assert.*
import org.junit.Test

class GestureEffectTest {
    // A 20x100 frame: a fixed header above a picker column whose rows carry distinct values.
    private fun frame(offset: Int) = ScreenChangeSignature(20, 100, ByteArray(2000) { index ->
        val row = index / 20
        when {
            row < 20 -> if (row % 4 == 0) 200.toByte() else 30
            row in 40 until 90 -> ((row - 40 - offset).mod(50) * 5).toByte()
            else -> 0
        }
    })
    private val drag = """{"actions":[{"op":"drag","startX":500,"startY":800,"endX":500,"endY":600},{"op":"screenshot"}]}"""

    @Test fun contentThatMovedIsLeftToTheFrame() {
        assertNull(GestureEffect.still(drag, frame(0), frame(-10)))
    }

    @Test fun contentAtItsEndReportsThatItDidNotMoveAndTheOtherDirection() {
        val text = GestureEffect.describe(checkNotNull(GestureEffect.still(drag, frame(0), frame(0))))
        assertTrue(text, text.startsWith("[GESTURE_EFFECT] drag moved the finger up, but content under it did not move"))
        assertTrue(text, text.contains("only come from moving it down"))
    }

    @Test fun changesOutsideTheGestureBandDoNotCountAsMovement() {
        // A caret blinking in a field to the left of the dragged column.
        val caret = ScreenChangeSignature(20, 100, ByteArray(2000) { index ->
            if (index % 20 == 2 && index / 20 in 10 until 16) 250.toByte() else 0
        })
        assertNotNull(GestureEffect.still(drag, ScreenChangeSignature(20, 100, ByteArray(2000)), caret))
    }

    @Test fun onlyAnUnchangedAreaInTheSameDirectionIsARepeat() {
        val still = checkNotNull(GestureEffect.still(drag, frame(0), frame(0)))
        assertTrue(GestureEffect.repeats(still,
            """{"actions":[{"op":"drag","startX":510,"startY":820,"endX":510,"endY":640}]}""", frame(0)))
        assertFalse("The opposite direction can move it", GestureEffect.repeats(still,
            """{"actions":[{"op":"drag","startX":500,"startY":600,"endX":500,"endY":800}]}""", frame(0)))
        assertFalse("Another column is another control", GestureEffect.repeats(still,
            """{"actions":[{"op":"drag","startX":150,"startY":800,"endX":150,"endY":600}]}""", frame(0)))
        assertFalse("A different stretch may start on the content", GestureEffect.repeats(still,
            """{"actions":[{"op":"drag","startX":500,"startY":400,"endX":500,"endY":100}]}""", frame(0)))
        assertFalse("The area changed since", GestureEffect.repeats(still, drag, frame(-10)))
    }

    @Test fun onlyASingleGestureBatchIsMeasured() {
        assertFalse("Drags observe each repetition themselves", GestureEffect.applies(drag))
        assertTrue(GestureEffect.applies(drag.replace("drag", "swipe")))
        assertFalse(GestureEffect.applies("""{"actions":[{"op":"click","x":1,"y":1},{"op":"drag","startX":1,"startY":1,"endX":1,"endY":9}]}"""))
        assertFalse(GestureEffect.applies("""{"actions":[{"op":"scroll","direction":"down"}]}"""))
    }

    @Test fun dragChecksItsRowsAndIgnoresChangesElsewhereInTheSameColumn() {
        val blank = ScreenChangeSignature(20, 100, ByteArray(2000))
        val header = ScreenChangeSignature(20, 100, ByteArray(2000) { if (it / 20 < 20) 200.toByte() else 0 })
        assertEquals(false, GestureEffect.changed(drag, blank, header))
        assertEquals(true, GestureEffect.changed(drag, frame(0), frame(-10)))
        assertNull(GestureEffect.changed(drag, blank, ScreenChangeSignature(10, 100, ByteArray(1000))))
    }
}
