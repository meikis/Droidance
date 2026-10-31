// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCheckPolicyTest {
    @Test
    fun checksOnlyAfterTheIntervalWhileNothingIsKnown() {
        assertFalse(shouldAutoCheck(true, AUTO_CHECK_INTERVAL_MS - 1, knownUpdateNeedsRestoring = false))
        assertTrue(shouldAutoCheck(true, AUTO_CHECK_INTERVAL_MS, knownUpdateNeedsRestoring = false))
    }

    @Test
    fun knownUpdateIsRestoredInsideTheInterval() {
        assertTrue(shouldAutoCheck(true, 0L, knownUpdateNeedsRestoring = true))
    }

    @Test
    fun disabledAutoCheckNeverRuns() {
        assertFalse(shouldAutoCheck(false, AUTO_CHECK_INTERVAL_MS, knownUpdateNeedsRestoring = true))
    }
}
