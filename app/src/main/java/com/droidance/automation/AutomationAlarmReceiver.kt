// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.droidance.daemon.TaskExecutionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AutomationAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var automationManager: AutomationManager

    override fun onReceive(context: Context, intent: Intent) {
        val automationId = intent.getStringExtra(AutomationAlarmScheduler.EXTRA_AUTOMATION_ID)
        val definitionVersion = intent.getIntExtra(
            AutomationAlarmScheduler.EXTRA_DEFINITION_VERSION,
            0
        )
        val scheduledForMs = intent.getLongExtra(
            AutomationAlarmScheduler.EXTRA_SCHEDULED_FOR_MS,
            0L
        )
        if (automationId.isNullOrBlank() || definitionVersion <= 0 || scheduledForMs <= 0L) {
            Log.w(TAG, "Ignoring malformed automation alarm")
            return
        }
        val accepted = automationManager.onAlarm(
            automationId = automationId,
            definitionVersion = definitionVersion,
            scheduledForMs = scheduledForMs
        )
        Log.i(TAG, "Automation alarm id=$automationId accepted=$accepted")
        TaskExecutionService.startStandby(context)
    }

    private companion object {
        const val TAG = "AutomationAlarmReceiver"
    }
}
