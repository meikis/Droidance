// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AirModePairingReceiver : BroadcastReceiver() {

    @Inject lateinit var pairingManager: AirModePairingManager
    @Inject lateinit var notificationController: AirModeNotificationController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AirModeNotificationController.ACTION_SUBMIT_CODE &&
            intent.action != AirModeNotificationController.ACTION_SUBMIT_MANUAL_PAIRING) return
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        if (intent.action == AirModeNotificationController.ACTION_SUBMIT_MANUAL_PAIRING) {
            val input = results.getCharSequence(AirModeNotificationController.KEY_MANUAL_PAIRING)
                ?.toString() ?: return
            val pairing = AirManualPairingInput.parse(input)
            if (pairing == null) {
                notificationController.showPairingFailure(AirPairingFailure.INVALID_MANUAL_PAIRING_INPUT, allowRetry = true)
            } else {
                pairingManager.requestPairing(pairing.code, pairing.port)
            }
            return
        }
        val code = results.getCharSequence(AirModeNotificationController.KEY_PAIRING_CODE)
            ?.toString()?.trim() ?: return
        if (code.length != 6 || !code.all { it in '0'..'9' }) {
            notificationController.showInvalidPairingCode()
            return
        }
        pairingManager.requestPairing(code)
    }
}
