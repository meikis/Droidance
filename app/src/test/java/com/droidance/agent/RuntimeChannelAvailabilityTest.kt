// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.device.RuntimeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeChannelAvailabilityTest {
    @Test fun startupAndSuccessfulConnectionsAreSilent() {
        for (mode in RuntimeMode.entries) {
            val state = RuntimeChannelAvailability()
            assertFalse(state.update(mode, false))
            assertFalse(state.update(mode, true))
            assertFalse(state.update(mode, true))
        }
    }

    @Test fun eachOutageNotifiesOnceAndRecoveryRearmsIt() {
        for (mode in RuntimeMode.entries) {
            val state = RuntimeChannelAvailability()
            assertFalse(state.update(mode, true))
            assertTrue(state.update(mode, false))
            assertFalse(state.update(mode, false))
            assertFalse(state.update(mode, true))
            assertTrue(state.update(mode, false))
        }
    }

    @Test fun modeSwitchDoesNotReportThePreviousChannelAsLost() {
        val state = RuntimeChannelAvailability()
        assertFalse(state.update(RuntimeMode.STANDARD, true))
        assertFalse(state.update(RuntimeMode.ULTRA, false))
        assertFalse(state.update(null, false))
    }
}
