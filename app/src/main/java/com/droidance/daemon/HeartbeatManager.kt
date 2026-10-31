// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.droidance.settings.GeneralSettingsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeartbeatManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val generalSettingsRepository: GeneralSettingsRepository
) {
    companion object {
        const val HEARTBEAT_WORK_NAME = "droidance_heartbeat"
        const val MIN_HEARTBEAT_INTERVAL_MINUTES = 15L
    }

    fun startHeartbeat() {
        val intervalMinutes = generalSettingsRepository.heartbeatInterval().toLong()
            .coerceAtLeast(MIN_HEARTBEAT_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .addTag("heartbeat")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i("HeartbeatManager", "Heartbeat started with a ${intervalMinutes}-minute interval")
    }

}

class HeartbeatWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i("HeartbeatWorker", "Heartbeat check started")

            // 0. 确保后台常驻服务仍在运行
            runCatching { TaskExecutionService.startStandby(appContext) }
                .onFailure { Log.w("HeartbeatWorker", "Failed to start the standby service: ${it.message}") }

            Result.success()
        } catch (e: Exception) {
            Log.e("HeartbeatWorker", "Heartbeat check failed", e)
            Result.retry()
        }
    }
}
