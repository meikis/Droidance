// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeDensityQualifierTest {
    @Test fun exactStandardDensitiesDoNotBorrowAnAdjacentQualifier() {
        assertEquals("xhdpi", standardDensityQualifier(320))
        assertEquals("xxhdpi", standardDensityQualifier(480))
        assertEquals("xxxhdpi", standardDensityQualifier(640))
        assertNull(standardDensityQualifier(560))
        assertNull(standardDensityQualifier(0))
    }
}
