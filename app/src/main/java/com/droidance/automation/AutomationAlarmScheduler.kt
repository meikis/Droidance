// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@kotlinx.serialization.Serializable
enum class AutomationAlarmPrecision {
    EXACT,
    APPROXIMATE
}

@Singleton
class AutomationAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(definition: AutomationDefinition): AutomationAlarmPrecision {
        if (!definition.enabled || definition.nextRunAtMs <= 0L) {
            cancel(definition.id)
            return precision()
        }
        val pendingIntent = pendingIntent(
            automationId = definition.id,
            definitionVersion = definition.version,
            scheduledForMs = definition.nextRunAtMs,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: error("Unable to create automation alarm PendingIntent")
        val precision = precision()
        val actualPrecision = if (precision == AutomationAlarmPrecision.EXACT && runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    definition.nextRunAtMs,
                    pendingIntent
                )
            }.isSuccess
        ) {
            AutomationAlarmPrecision.EXACT
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                definition.nextRunAtMs,
                pendingIntent
            )
            AutomationAlarmPrecision.APPROXIMATE
        }
        Log.i(
            TAG,
            "Scheduled automation=${definition.id} at=${definition.nextRunAtMs} precision=$actualPrecision"
        )
        return actualPrecision
    }

    fun cancel(automationId: String) {
        pendingIntent(
            automationId = automationId,
            definitionVersion = 0,
            scheduledForMs = 0L,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let(alarmManager::cancel)
    }

    fun precision(): AutomationAlarmPrecision =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            AutomationAlarmPrecision.EXACT
        } else {
            AutomationAlarmPrecision.APPROXIMATE
        }

    private fun pendingIntent(
        automationId: String,
        definitionVersion: Int,
        scheduledForMs: Long,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, AutomationAlarmReceiver::class.java).apply {
            action = "$ACTION_PREFIX$automationId"
            putExtra(EXTRA_AUTOMATION_ID, automationId)
            putExtra(EXTRA_DEFINITION_VERSION, definitionVersion)
            putExtra(EXTRA_SCHEDULED_FOR_MS, scheduledForMs)
        }
        return PendingIntent.getBroadcast(context, automationId.hashCode(), intent, flags)
    }

    companion object {
        const val EXTRA_AUTOMATION_ID = "automation_id"
        const val EXTRA_DEFINITION_VERSION = "automation_definition_version"
        const val EXTRA_SCHEDULED_FOR_MS = "automation_scheduled_for_ms"
        private const val ACTION_PREFIX = "com.droidance.AUTOMATION_ALARM."
        private const val TAG = "AutomationAlarm"
    }
}
