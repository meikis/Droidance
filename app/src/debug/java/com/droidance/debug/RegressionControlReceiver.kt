// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidance.agent.AgentRuntime
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Debug-only, shell-permission protected cancellation of one explicitly identified task. */
@AndroidEntryPoint
class RegressionControlReceiver : BroadcastReceiver() {
    @Inject lateinit var runtime: AgentRuntime

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.droidance.debug.CANCEL_REGRESSION_TURN") return
        val turnId = intent.getStringExtra("turn_id") ?: return
        if (!turnId.startsWith("turn:") || turnId.length <= 5) return
        runtime.cancelTurn(turnId)
    }
}
