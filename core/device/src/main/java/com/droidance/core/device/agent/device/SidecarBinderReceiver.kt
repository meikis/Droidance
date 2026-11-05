// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the sidecar Binder broadcast sent by the paired shell-identity app_process
 * server and stores it in [SidecarBinderRegistry].
 */
class SidecarBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val binder = SidecarBinderHandoff.receive(context, intent) ?: return
        val previouslyAlive = SidecarBinderRegistry.isAlive(SHELL_UID)
        SidecarBinderRegistry.set(SHELL_UID, binder)
        if (!previouslyAlive) {
            Log.d(TAG, "sidecar binder registered from broadcast uid=$SHELL_UID")
        }
    }

    companion object {
        const val ACTION_SIDECAR_BINDER_READY = "com.droidance.action.SIDECAR_BINDER_READY"
        private const val SHELL_UID = 2000
        private const val TAG = "DroidanceVSpace"
    }
}
