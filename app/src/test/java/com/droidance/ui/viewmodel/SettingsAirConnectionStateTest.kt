// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.core.device.air.AirModePairingManager.PairingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAirConnectionStateTest {
    private val offlinePaired = SettingsUiState(
        airBridgeState = PairingState.IDLE,
        airPreviouslyPaired = true,
        airResidentChannelAvailable = true
    )

    @Test
    fun residentChannelKeepsPairedDeviceConnectedWithoutLiveAdb() {
        assertTrue(offlinePaired.airDeviceConnected)
    }

    @Test
    fun liveAdbConnectionIsConnectedWithoutResidentChannel() {
        assertTrue(SettingsUiState(airBridgeState = PairingState.CONNECTED).airDeviceConnected)
    }

    @Test
    fun pairedDeviceWithoutEitherChannelIsNotConnected() {
        assertFalse(offlinePaired.copy(airResidentChannelAvailable = false).airDeviceConnected)
    }

    @Test
    fun residentChannelDoesNotHidePairingWork() {
        assertFalse(offlinePaired.copy(airPairingRequired = true).airDeviceConnected)
        assertFalse(offlinePaired.copy(airPreviouslyPaired = false).airDeviceConnected)
        assertFalse(offlinePaired.copy(airBridgeState = PairingState.AWAITING_CODE).airDeviceConnected)
    }
}
