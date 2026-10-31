// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import android.content.Intent
import com.droidance.core.agent.device.AppLaunchMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLaunchFlagsTest {
    @Test
    fun resumeNeverRequestsAnAndroidTaskReset() {
        val destructiveFlags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val resumeFlags = AppLaunchMode.RESUME.intentFlags
        assertEquals(0, resumeFlags and destructiveFlags)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, resumeFlags)
    }

    @Test
    fun explicitResetRetainsItsAndroidTaskResetFlags() {
        val flags = AppLaunchMode.RESET_TASK.intentFlags
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
