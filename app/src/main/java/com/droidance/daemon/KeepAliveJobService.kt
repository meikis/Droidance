// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.droidance.core.device.air.AirModePairingManager
import com.droidance.automation.AutomationManager
import com.droidance.debug.DebugLogManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 持久化守护 JobService。
 * 系统会周期性唤起它，用来检查常驻前台服务是否仍在运行。
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
        private const val JOB_ID = 10086
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(JOB_ID) != null) return

            val jobInfo = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, KeepAliveJobService::class.java)
            )
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()

            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "KeepAlive job scheduled")
            } else {
                Log.e(TAG, "KeepAlive job schedule failed")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "KeepAlive job triggered, service running: ${TaskExecutionService.instance != null}")
        runCatching { TaskExecutionService.startStandby(applicationContext) }
            .onFailure { Log.w(TAG, "Restart standby service failed: ${it.message}") }
        work = scope.launch {
            try {
                val ep = EntryPointAccessors.fromApplication(applicationContext, KeepAliveEntryPoint::class.java)
                ep.airModePairingManager().keepAlive()
                ep.appUpdateManager().checkIfDue(appOpen = false)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "Restore device connection failed: ${error.message}")
            } finally { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface KeepAliveEntryPoint {
    fun airModePairingManager(): AirModePairingManager
    fun debugLogManager(): DebugLogManager
    fun automationManager(): AutomationManager
    fun appUpdateManager(): com.droidance.update.AppUpdateManager
    fun foregroundState(): com.droidance.agent.AppForegroundState
    fun runtimeSurfaceController(): com.droidance.ui.overlay.RuntimeSurfaceController
}
