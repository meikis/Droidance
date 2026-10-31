// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import org.junit.Assert.*
import org.junit.Test
import com.droidance.core.agent.device.AppLaunchMode

class LauncherIntentCommandTest {
    private fun escape(value: String) = "'" + value.replace("'", "'\\''") + "'"

    @Test fun hotRoutesPreserveTheTargetPageWhileColdVirtualRoutesStillPrepareAnEntry() {
        assertFalse(AppEntryLanding.required(true, AppLaunchMode.RESUME, "sample.app", "sample.app"))
        assertTrue(AppEntryLanding.required(true, AppLaunchMode.RESUME, "sample.app", null))
        assertTrue(AppEntryLanding.required(true, AppLaunchMode.RESUME, "sample.app", "sample.other"))
        assertTrue(AppEntryLanding.required(true, AppLaunchMode.FRESH, "sample.app", "sample.app"))
        assertFalse(AppEntryLanding.required(false, AppLaunchMode.RESUME, "sample.app", null))
    }

    @Test fun cachedComponentUsesHomeSemanticsAndTheFrozenDisplayWithoutASecondLookup() {
        val command = LauncherIntentCommand.build(266, 0x10000000,
            "sample.reader/.Main", "sample.reader", ::escape)
        assertEquals("am start --display 266 -f 0x10000000 -a android.intent.action.MAIN" +
            " -c android.intent.category.LAUNCHER -n 'sample.reader/.Main' -p 'sample.reader'", command)
        assertFalse(command.contains("resolve-activity"))
        assertFalse(command.contains("force-stop"))
        assertFalse(command.contains("CLEAR_TASK"))
    }

    @Test fun missingComponentUsesPlatformLauncherResolutionWithinTheSameRequest() {
        val command = LauncherIntentCommand.build(8, 0x10000000, null, "sample.reader", ::escape)
        assertTrue(command.contains(" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"))
        assertTrue(command.endsWith(" -p 'sample.reader'"))
        assertFalse(command.contains(" -n "))
        assertFalse(command.contains("$("))
    }
}
