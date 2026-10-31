// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarSessionBindingTest {
    @Test
    fun `matches only its frozen runtime mode display and backend session`() {
        val binding = SidecarSessionBinding(
            runtimeMode = RuntimeMode.STANDARD,
            displayId = 7,
            backendSessionId = "standard:7:binder-a"
        )

        assertTrue(binding.matches(RuntimeMode.STANDARD, 7, "standard:7:binder-a"))
        assertFalse(binding.matches(RuntimeMode.ULTRA, 7, "standard:7:binder-a"))
        assertFalse(binding.matches(RuntimeMode.ULTRA, 0, "standard:7:binder-a"))
        assertFalse(binding.matches(RuntimeMode.ULTRA, 7, "standard:7:binder-b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a blank backend session`() {
        SidecarSessionBinding(RuntimeMode.STANDARD, 0, " ")
    }
}
