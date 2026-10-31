// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.connection

import com.droidance.core.device.air.AirModePairingManager.PairingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConnectionUiStateTest {
    private val paired = DeviceConnectionSnapshot(
        transportAvailable = false, pairingState = PairingState.IDLE,
        previouslyPaired = true, pairingRequired = false, wifiAvailable = true,
        wirelessDebuggingEnabled = true, connectionFailed = false
    )

    @Test fun residentTransportPreventsFalseWarningsWhenAdbAndWifiAreUnavailable() {
        val snapshot = paired.copy(transportAvailable = true, wifiAvailable = false,
            wirelessDebuggingEnabled = false, pairingRequired = true, connectionFailed = true)
        assertEquals(DeviceConnectionStatus.READY, snapshot.status())
        assertFalse(DeviceConnectionUiState().observe(snapshot.status(), 0L).noticeVisible)
    }

    @Test fun onlyExplicitTrustLossRequiresRepairing() {
        assertEquals(DeviceConnectionStatus.PAIRING_REQUIRED,
            paired.copy(pairingRequired = true).status())
        assertEquals(DeviceConnectionStatus.UNAVAILABLE, paired.copy(connectionFailed = true).status())
        assertEquals(DeviceConnectionAction.RETRY, paired.copy(connectionFailed = true).status().action)
    }

    @Test fun wifiAndWirelessDebuggingFailuresLeadToTheirOwnSettings() {
        assertEquals(DeviceConnectionAction.WIFI_SETTINGS,
            paired.copy(wifiAvailable = false, wirelessDebuggingEnabled = false).status().action)
        assertEquals(DeviceConnectionAction.WIRELESS_SETTINGS,
            paired.copy(wirelessDebuggingEnabled = false).status().action)
    }

    @Test fun unreadableDebuggingSettingIsNotReportedAsOff() {
        assertEquals(DeviceConnectionStatus.UNAVAILABLE, paired.copy(wirelessDebuggingEnabled = null).status())
    }

    @Test fun automaticRecoveryStaysQuietUntilItFails() {
        val connecting = paired.copy(pairingState = PairingState.CONNECTING)
        val state = DeviceConnectionUiState().observe(connecting.status(), 0L)
        assertTrue(state.noticeVisible)
        assertFalse(state.dialogVisible)
        assertTrue(state.confirm(connecting.copy(connectionFailed = true).status()).dialogVisible)
    }

    @Test fun activePairingDoesNotPromptTheUserToRestartPairing() {
        for (phase in listOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE, PairingState.PAIRING)) {
            val state = DeviceConnectionUiState().observe(paired.copy(
                pairingState = phase, previouslyPaired = false, pairingRequired = true).status(), 0L)
            assertEquals(DeviceConnectionStatus.PAIRING, state.status)
            assertFalse(state.dialogVisible)
        }
    }

    @Test fun laterKeepsAnEntryWithoutRepromptingAcrossRetriesOrChangingCauses() {
        var state = DeviceConnectionUiState().confirm(DeviceConnectionStatus.UNAVAILABLE)
        assertTrue(state.dialogVisible)
        state = state.dismiss()
        for (next in listOf(DeviceConnectionStatus.UNAVAILABLE, DeviceConnectionStatus.RECONNECTING,
            DeviceConnectionStatus.UNAVAILABLE, DeviceConnectionStatus.WIFI_UNAVAILABLE)) {
            state = state.confirm(next)
            assertTrue(state.noticeVisible)
            assertFalse(state.dialogVisible)
        }
    }

    @Test fun aNewOutageCanPromptAfterAConfirmedRecovery() {
        val recovered = DeviceConnectionUiState().confirm(DeviceConnectionStatus.UNAVAILABLE)
            .dismiss().observe(DeviceConnectionStatus.READY, 6_000L)
        assertFalse(recovered.noticeVisible)
        assertFalse(recovered.dialogVisible)
        assertTrue(recovered.confirm(DeviceConnectionStatus.UNAVAILABLE, 7_000L).dialogVisible)
    }

    @Test fun detailsCanBeOpenedAgainAndAreDismissedOnRecovery() {
        val reopened = DeviceConnectionUiState().confirm(DeviceConnectionStatus.WIRELESS_DISABLED)
            .dismiss().showDetails()
        assertTrue(reopened.dialogVisible)
        assertTrue(reopened.observe(DeviceConnectionStatus.WIRELESS_DISABLED, 6_000L).dialogVisible)
        assertFalse(reopened.observe(DeviceConnectionStatus.READY, 7_000L).dialogVisible)
    }

    @Test fun firstConnectionUsesPairingRatherThanAnUnusableRetry() {
        val status = paired.copy(previouslyPaired = false).status()
        assertEquals(DeviceConnectionStatus.UNPAIRED, status)
        assertEquals(DeviceConnectionAction.PAIR, status.action)
    }

    @Test fun startupDoesNotTreatAnUnknownWifiObservationAsADisconnection() {
        val unknown = paired.copy(wifiAvailable = null, wirelessDebuggingEnabled = false,
            connectionFailed = true).status()
        assertEquals(DeviceConnectionStatus.CHECKING, unknown)
        val state = DeviceConnectionUiState().confirm(unknown)
        assertFalse(state.noticeVisible)
        assertFalse(state.dialogVisible)
        assertEquals(DeviceConnectionStatus.READY,
            paired.copy(wifiAvailable = null, transportAvailable = true).status())
    }

    @Test fun startupWifiCallbackAndResidentChannelRecoveryDoNotFlashWarnings() {
        var state = DeviceConnectionUiState()
        for ((time, status) in listOf(
            0L to paired.copy(wifiAvailable = null).status(),
            1_000L to paired.copy(wifiAvailable = null).status(),
            2_000L to paired.copy(pairingState = PairingState.IDLE).status(),
            3_000L to paired.copy(pairingState = PairingState.CONNECTING).status(),
            4_000L to paired.copy(transportAvailable = true).status()
        )) {
            state = state.observe(status, time)
            assertFalse(state.dialogVisible)
            assertFalse(state.prompted)
        }
        assertFalse(state.noticeVisible)
    }

    @Test fun shortWifiLossDoesNotShowANoticeOrConsumeTheOutagePrompt() {
        var state = DeviceConnectionUiState().observe(DeviceConnectionStatus.READY, 0L)
        for (time in listOf(1_000L, 2_000L, 5_999L)) {
            state = state.observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, time)
            assertFalse(state.noticeVisible)
            assertFalse(state.dialogVisible)
            assertFalse(state.prompted)
        }
        state = state.observe(DeviceConnectionStatus.READY, 6_000L)
        assertFalse(state.dialogVisible)
        assertTrue(state.confirm(DeviceConnectionStatus.WIFI_UNAVAILABLE, 7_000L).dialogVisible)
    }

    @Test fun confirmedWifiLossPromptsOnceAndRecoveryClosesItImmediately() {
        var state = DeviceConnectionUiState().confirm(DeviceConnectionStatus.WIFI_UNAVAILABLE)
        assertEquals(DeviceConnectionStatus.WIFI_UNAVAILABLE, state.status)
        assertTrue(state.noticeVisible)
        assertTrue(state.dialogVisible)
        state = state.dismiss().observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 8_000L)
        assertTrue(state.noticeVisible)
        assertFalse(state.dialogVisible)
        state = state.observe(DeviceConnectionStatus.READY, 8_001L)
        assertFalse(state.noticeVisible)
        assertFalse(state.dialogVisible)
    }

    @Test fun changingFailureCausesMustBeConfirmedSeparately() {
        var state = DeviceConnectionUiState().observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 0L)
            .observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 4_000L)
            .observe(DeviceConnectionStatus.WIRELESS_DISABLED, 4_500L)
            .observe(DeviceConnectionStatus.WIRELESS_DISABLED, 5_000L)
        assertFalse(state.noticeVisible)
        assertFalse(state.dialogVisible)
        state = state.observe(DeviceConnectionStatus.WIRELESS_DISABLED, 9_500L)
        assertEquals(DeviceConnectionStatus.WIRELESS_DISABLED, state.status)
        assertTrue(state.dialogVisible)
    }

    @Test fun anUnreadableObservationRestartsFailureConfirmation() {
        var state = DeviceConnectionUiState().observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 0L)
            .observe(DeviceConnectionStatus.CHECKING, 4_000L)
            .observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 8_000L)
        assertFalse(state.dialogVisible)
        state = state.observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 12_999L)
        assertFalse(state.dialogVisible)
        assertTrue(state.observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 13_000L).dialogVisible)
    }

    @Test fun backgroundTimeDoesNotCountAsConfirmedFailureOrResetADismissal() {
        var state = DeviceConnectionUiState().observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 0L)
            .checking().observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 60_000L)
        assertFalse(state.noticeVisible)
        assertFalse(state.dialogVisible)
        state = state.observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 65_000L)
        assertTrue(state.dialogVisible)
        state = state.dismiss().checking().confirm(DeviceConnectionStatus.WIFI_UNAVAILABLE, 120_000L)
        assertTrue(state.noticeVisible)
        assertFalse(state.dialogVisible)
    }

    private fun DeviceConnectionUiState.confirm(status: DeviceConnectionStatus, sinceMs: Long = 0L) =
        observe(status, sinceMs).observe(status, sinceMs + DeviceConnectionUiState.FAILURE_CONFIRMATION_MS)
}
