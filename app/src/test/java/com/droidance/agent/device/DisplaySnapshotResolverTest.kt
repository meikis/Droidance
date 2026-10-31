// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DisplaySnapshotResolverTest {
    @Test
    fun mainDisplayUsesSystemDisplayMetrics() {
        val physical = PhysicalDisplaySnapshot(1080, 2376, 480, 0)

        assertEquals(
            physical,
            resolveDisplaySnapshot(
                displayId = 0,
                physicalDisplay = physical,
                virtualDisplay = null
            )
        )
    }

    @Test
    fun privateVirtualDisplayUsesFrozenSidecarMetrics() {
        val resolved = resolveDisplaySnapshot(
            displayId = 186,
            physicalDisplay = null,
            virtualDisplay = VirtualDisplayMetrics(
                displayId = 186,
                width = 1080,
                height = 2376,
                densityDpi = 480,
                rotationDegrees = 0
            )
        )

        assertEquals(1080, resolved.width)
        assertEquals(2376, resolved.height)
        assertEquals(480, resolved.densityDpi)
        assertEquals(0, resolved.rotationDegrees)
    }

    @Test
    fun virtualDisplayMetricsMustMatchFrozenDisplayId() {
        val error = assertThrows(IllegalStateException::class.java) {
            resolveDisplaySnapshot(
                displayId = 187,
                physicalDisplay = null,
                virtualDisplay = VirtualDisplayMetrics(186, 1080, 2376, 480, 0)
            )
        }

        assertEquals(
            "Virtual display 187 is unavailable from the active backend session.",
            error.message
        )
    }
}
