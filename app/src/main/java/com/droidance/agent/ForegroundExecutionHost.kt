// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.droidance.daemon.TaskExecutionService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns Android foreground residency, notifications and localized runtime presentation. */
@Singleton
class ForegroundExecutionHost @Inject constructor(
    private val foregroundState: com.droidance.agent.AppForegroundState,
    @ApplicationContext private val appContext: Context
) {
    private val powerManager by lazy {
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val wifiManager by lazy {
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val executionWakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Droidance::ForegroundHostWakeLock"
        ).apply { setReferenceCounted(false) }
    }

    private val executionWifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "Droidance::ForegroundHostWifiLock"
        ).apply { setReferenceCounted(false) }
    }

    private val lock = Any()
    @Volatile private var executionActiveCount = 0

    fun localizedString(resId: Int, vararg formatArgs: Any): String {
        return ContextCompat.getContextForLanguage(appContext).getString(resId, *formatArgs)
    }

    fun startExecution(taskTitle: String? = null) {
        synchronized(lock) {
            executionActiveCount++
            try {
                if (!executionWakeLock.isHeld) {
                    executionWakeLock.acquire()
                }
            } catch (e: Exception) {
                Log.w("ForegroundExecutionHost", "Failed to acquire execution wake lock: ${e.message}")
            }
            try {
                if (!executionWifiLock.isHeld) {
                    executionWifiLock.acquire()
                }
            } catch (e: Exception) {
                Log.w("ForegroundExecutionHost", "Failed to acquire execution wifi lock: ${e.message}")
            }
        }
        TaskExecutionService.startExecution(appContext, taskTitle)
    }

    fun stopExecution() {
        synchronized(lock) {
            executionActiveCount = (executionActiveCount - 1).coerceAtLeast(0)
            if (executionActiveCount == 0) {
                try {
                    if (executionWakeLock.isHeld) executionWakeLock.release()
                } catch (e: Exception) {
                    Log.w("ForegroundExecutionHost", "Failed to release execution wake lock: ${e.message}")
                }
                try {
                    if (executionWifiLock.isHeld) executionWifiLock.release()
                } catch (e: Exception) {
                    Log.w("ForegroundExecutionHost", "Failed to release execution wifi lock: ${e.message}")
                }
            }
        }
        TaskExecutionService.stopExecution(appContext)
    }

    fun notifyAutomationStarted(taskTitle: String): Boolean =
        TaskExecutionService.notifyAutomationStarted(appContext, taskTitle)

    fun notifyTaskCompleted(taskTitle: String? = null, summary: String): Boolean =
        TaskExecutionService.notifyTaskCompleted(appContext, taskTitle, summary)

    fun notifyTaskInterrupted(taskTitle: String? = null, reason: String): Boolean =
        TaskExecutionService.notifyTaskInterrupted(appContext, taskTitle, reason)

    fun notifyTaskDone(summary: String) {
        if (!foregroundState.isInForeground) {
            TaskExecutionService.notifyTaskDone(appContext, summary)
        }
    }


    fun notifyManualTakeoverRequired() {
        TaskExecutionService.notifyManualTakeoverRequired(appContext)
    }

    fun cancelManualTakeover() {
        TaskExecutionService.cancelManualTakeover(appContext)
    }

    fun notifyRuntimePermissionRequired(reason: String) {
        TaskExecutionService.notifyRuntimePermissionRequired(appContext, reason)
    }

}
