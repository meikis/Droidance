// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import org.junit.Assert.*
import org.junit.Test

class ObservationTargetFreshnessTest {
    private val samples = (0 until 10).flatMap { row -> (0 until 10).map { col ->
        ObservationMappedSample(col * 10 + 5, row * 10 + 5, col * 10 + 5, row * 10 + 5)
    } }
    private val reference = ObservationPixelReference(samples, IntArray(samples.size) {
        if ((it / 10 + it % 10) % 2 == 0) 0 else 0x777777
    })
    private val bounds = intArrayOf(20, 20, 49, 49)

    private fun allowed(change: (Int, Int, Int) -> Int, ref: ObservationPixelReference = reference,
                        targetBounds: IntArray = bounds): Boolean {
        val colors = ref.samples.mapIndexed { index, point ->
            (point.physicalX to point.physicalY) to change(point.physicalX, point.physicalY, ref.colors[index])
        }.toMap()
        val pixels = { x: Int, y: Int -> colors.getValue(x to y) }
        val whole = ObservationPixelComparator.compare(ref, 100, 100, pixels, 8)
        val target = ObservationPixelComparator.compareTarget(ref, 100, 100, pixels, targetBounds, 8)
        return ObservationPixelComparator.allowsAction(whole, target, 0.20)
    }

    @Test fun firstEditorChangesWithoutInvalidatingAnotherVisibleEditor() {
        assertTrue(allowed({ x, y, before -> if (x >= 50 && y < 50) 0xffffff else before }))
    }
    @Test fun outsideAnimationAndKeyboardLeaveAStableTargetAvailable() {
        assertTrue(allowed({ _, y, before -> if (y >= 50) 0xffffff else before }))
        assertTrue(allowed({ x, y, before -> if (x !in 20..49 || y !in 20..49) 0xffffff else before }))
    }
    @Test fun movedOrCoveredTargetStopsEvenWhenWholeScreenChangesLittle() {
        assertFalse(allowed({ x, y, before -> if (x in 20..49 && y in 20..49) 0xffffff else before }))
        assertFalse(allowed({ x, y, before -> if (x in 20..49 && y in 20..49) before xor 0x777777 else before }))
    }
    @Test fun uniformOrUndersampledTargetCannotExcuseLargeOutsideChanges() {
        assertFalse(allowed({ _, y, before -> if (y >= 50) 0xffffff else before },
            reference.copy(colors = IntArray(samples.size))))
        assertFalse(allowed({ _, y, before -> if (y >= 50) 0xffffff else before },
            targetBounds = intArrayOf(20, 20, 39, 39)))
        assertTrue(allowed({ _, _, before -> before }, targetBounds = intArrayOf(20, 20, 39, 39)))
    }
}
