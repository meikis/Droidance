// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import org.junit.Assert.assertEquals
import org.junit.Test

class AirAdbPortPolicyTest {
    @Test
    fun connectPortsDoNotUseLegacyTcpipWhenWirelessDebuggingPortIsMissing() {
        assertEquals(
            emptyList<Int>(),
            AirAdbPortPolicy.connectPorts(lastPort = null, settingsPort = null)
        )
    }

    @Test
    fun connectPortsUseWirelessDebuggingPortOnly() {
        assertEquals(
            listOf(37123),
            AirAdbPortPolicy.connectPorts(lastPort = null, settingsPort = 37123)
        )
    }

    @Test
    fun connectPortsRejectLegacyTcpipPortForAir() {
        assertEquals(
            emptyList<Int>(),
            AirAdbPortPolicy.connectPorts(lastPort = null, settingsPort = 5555)
        )
    }

    @Test
    fun connectPortsKeepPersistedLastPortAsABestEffortCandidate() {
        // Settings 暂未返回当前端口时仍可快速尝试上次端口；候选存在不代表旧 TLS 服务仍存活。
        assertEquals(
            listOf(37791),
            AirAdbPortPolicy.connectPorts(lastPort = 37791, settingsPort = null)
        )
    }

    @Test
    fun connectPortsTryLastPortBeforeSettingsPortAndDeduplicate() {
        assertEquals(
            listOf(37791, 40000),
            AirAdbPortPolicy.connectPorts(lastPort = 37791, settingsPort = 40000)
        )
        assertEquals(
            listOf(37791),
            AirAdbPortPolicy.connectPorts(lastPort = 37791, settingsPort = 37791)
        )
    }
}
