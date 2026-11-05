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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AirModeNotificationController.ACTION_SUBMIT_CODE &&
            intent.action != AirModeNotificationController.ACTION_SUBMIT_MANUAL_PAIRING) return
        val results = RemoteInput.getResultsFromIntent(intent)
        val manualEntry = intent.action == AirModeNotificationController.ACTION_SUBMIT_MANUAL_PAIRING
        val key = if (manualEntry) AirModeNotificationController.KEY_MANUAL_PAIRING
            else AirModeNotificationController.KEY_PAIRING_CODE
        pairingManager.requestPairingFromNotification(
            session = intent.data?.lastPathSegment,
            input = results?.getCharSequence(key)?.toString(),
            manualEntry = manualEntry,
            hasResults = results != null
        )
    }
}
