// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the sidecar Binder broadcast sent by the paired shell-identity app_process
 * server and stores it in [SidecarBinderRegistry].
 */
class SidecarBinderReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SIDECAR_BINDER_READY) return
        val container = intent.getParcelableExtra<SidecarBinderContainer>(EXTRA_BINDER_CONTAINER)
        val binder = container?.binder
        if (binder == null) {
            Log.w(TAG, "SIDECAR_BINDER_READY received but binder is null")
            return
        }
        val uid = intent.getIntExtra(EXTRA_SIDECAR_UID, INVALID_UID)
        if (uid != SHELL_UID) {
            Log.w(TAG, "SIDECAR_BINDER_READY received with unsupported uid=$uid")
            return
        }
        val protocolVersion = intent.getIntExtra(EXTRA_SIDECAR_PROTOCOL_VERSION, INVALID_PROTOCOL_VERSION)
        if (protocolVersion != VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION) {
            Log.w(TAG, "ignored stale sidecar binder uid=$uid protocol=$protocolVersion")
            return
        }
        val previouslyAlive = SidecarBinderRegistry.isAlive(uid)
        SidecarBinderRegistry.set(uid, binder)
        if (!previouslyAlive) {
            Log.d(TAG, "sidecar binder registered from broadcast uid=$uid")
        }
    }

    companion object {
        const val ACTION_SIDECAR_BINDER_READY = "com.droidance.action.SIDECAR_BINDER_READY"
        const val EXTRA_BINDER_CONTAINER = "binder_container"
        const val EXTRA_SIDECAR_UID = "sidecar_uid"
        const val EXTRA_SIDECAR_PROTOCOL_VERSION = "sidecar_protocol_version"
        private const val SHELL_UID = 2000
        private const val INVALID_UID = -1
        private const val INVALID_PROTOCOL_VERSION = -1
        private const val TAG = "DroidanceVSpace"
    }
}
