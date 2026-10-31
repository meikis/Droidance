// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExecutionSettingsRepositoryTest {
    @Test
    fun taskDurationRemainsOptionalWithoutAnArtificialUpperLimit() {
        assertEquals(0, ExecutionSettingsPolicy.DEFAULT_TASK_WALL_MINUTES)
        assertEquals(0, ExecutionSettingsPolicy.normalizeTaskWallMinutes(-1))
        assertEquals(5, ExecutionSettingsPolicy.normalizeTaskWallMinutes(5))
        assertEquals(30, ExecutionSettingsPolicy.normalizeTaskWallMinutes(30))
        assertEquals(120, ExecutionSettingsPolicy.normalizeTaskWallMinutes(120))
        assertEquals(1440, ExecutionSettingsPolicy.normalizeTaskWallMinutes(1440))
        assertEquals(1441, ExecutionSettingsPolicy.normalizeTaskWallMinutes(1441))
        assertEquals(Int.MAX_VALUE, ExecutionSettingsPolicy.normalizeTaskWallMinutes(Int.MAX_VALUE))
    }

    @Test
    fun deepLinkIsDisabledByDefault() {
        assertFalse(ExecutionSettingsPolicy.DEFAULT_ENABLE_DEEPLINK)
    }

}
