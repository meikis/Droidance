// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.EntryPointAccessors

/**
 * 开机广播接收器
 * 在设备启动后恢复定时任务和心跳机制
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        Log.i("BootReceiver", "Device boot detected; restoring Droidance background work")
        // HeartbeatManager 使用 WorkManager，系统会自动恢复
        KeepAliveJobService.schedule(context)
        TaskExecutionService.startStandby(context)
        // Device reconnection can block; keep it off the main thread so the
        // receiver cannot ANR. goAsync() holds the broadcast open until restore finishes.
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread({
            try {
                runCatching {
                    val ep = EntryPointAccessors.fromApplication(appContext, KeepAliveEntryPoint::class.java)
                    ep.airModePairingManager().ensureConnectedIfPaired()
                    ep.automationManager().restoreSchedules()
                }.onFailure {
                    Log.w("BootReceiver", "Failed to restore background work: ${it.message}")
                }
            } finally {
                pending.finish()
            }
        }, "droidance-boot-restore").start()
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
