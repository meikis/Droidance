// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import android.app.PendingIntent
import android.app.AlarmManager
import android.app.Service
import android.content.Context
import com.droidance.core.device.presentation.LocalizedNotifications
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.droidance.R
import com.droidance.core.device.air.AirModePairingManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject

/**
 * 前台常驻服务
 * 双重职责：
 * 1. 保活 — 常驻通知栏，防止系统杀掉进程
 * 2. 任务执行 — 执行自动化任务时更新通知显示当前状态
 */
@AndroidEntryPoint
class TaskExecutionService : Service() {

    companion object {
        const val CHANNEL_ID = "droidance_task_channel"
        const val NOTIFICATION_ID = 1001

        /** Intent action：更新通知文本 */

        /** Intent action：切换到待命模式 */
        const val ACTION_STANDBY = "com.droidance.STANDBY"

        /** Intent action：开始执行任务（进入前台服务） */
        const val ACTION_START_EXECUTION = "com.droidance.START_EXECUTION"
        /** Intent action：停止执行任务（退出前台服务） */
        const val ACTION_STOP_EXECUTION = "com.droidance.STOP_EXECUTION"
        const val EXTRA_TASK_TITLE = "task_title"

        /** 静态实例引用，供其他组件检查服务是否在运行 */
        @Volatile
        var instance: TaskExecutionService? = null
            private set

        /** 便捷方法：启动常驻服务（待命模式） */
        fun startStandby(context: Context) {
            val appContext = context.applicationContext
            KeepAliveJobService.schedule(appContext)
            val intent = Intent(appContext, TaskExecutionService::class.java).apply {
                action = ACTION_STANDBY
            }
            try {
                appContext.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w("TaskExecutionService", "启动常驻服务失败: ${e.message}")
            }
        }

        /** 便捷方法：开始前台任务执行（按需前台服务） */
        fun startExecution(context: Context, taskTitle: String? = null) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, TaskExecutionService::class.java).apply {
                action = ACTION_START_EXECUTION
                if (!taskTitle.isNullOrBlank()) {
                    putExtra(EXTRA_TASK_TITLE, taskTitle)
                }
            }
            try {
                appContext.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w("TaskExecutionService", "启动任务执行前台服务失败: ${e.message}")
            }
        }

        /** 便捷方法：停止前台任务执行并清除常驻通知 */
        fun stopExecution(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, TaskExecutionService::class.java).apply {
                action = ACTION_STOP_EXECUTION
            }
            try {
                appContext.startService(intent)
            } catch (e: Exception) {
                Log.w("TaskExecutionService", "停止任务执行前台服务失败: ${e.message}")
            }
        }

        /** 任务完成通知 ID（独立于常驻通知，可被用户划掉） */
        const val TASK_DONE_NOTIFICATION_ID = 1002
        // Use a result-alert channel so an earlier ordinary-priority channel cannot
        // keep completion notices from appearing as heads-up alerts.
        const val TASK_DONE_CHANNEL_ID = "droidance_task_result_alerts"
        const val MANUAL_TAKEOVER_NOTIFICATION_ID = 1003
        const val MANUAL_TAKEOVER_CHANNEL_ID = "droidance_manual_takeover"
        const val TASK_STARTED_NOTIFICATION_ID = 1004

        fun refreshLanguage(context: Context) {
            val localized = localizedContext(context)
            val manager = NotificationManagerCompat.from(context)
            listOf(
                Triple(CHANNEL_ID, R.string.task_service_channel_name, R.string.task_service_channel_description),
                Triple(TASK_DONE_CHANNEL_ID, R.string.task_done_channel_name, R.string.task_done_channel_description),
                Triple(MANUAL_TAKEOVER_CHANNEL_ID, R.string.manual_takeover_channel_name, R.string.manual_takeover_channel_description)
            ).forEach { (id, name, description) ->
                manager.getNotificationChannel(id)?.let { existing ->
                    existing.name = localized.getString(name)
                    existing.description = localized.getString(description)
                    manager.createNotificationChannel(existing)
                }
            }
            LocalizedNotifications.refresh(context, setOf(NOTIFICATION_ID, TASK_DONE_NOTIFICATION_ID,
                MANUAL_TAKEOVER_NOTIFICATION_ID, TASK_STARTED_NOTIFICATION_ID))
        }

        private fun localizedContext(context: Context): Context =
            ContextCompat.getContextForLanguage(context)

        /**
         * 通知发送结果之前被静默吞掉(SecurityException 直接 catch 忽略)，真机上通知到底有没有
         * 发出去完全不可见。这里把结果(含"通知总开关是否关闭"这个比异常更常见的沉默失败原因)
         * 记进 debugLogManager，让下次 debug 导出能直接看到。
         */
        private fun logNotifyResult(context: Context, kind: String, result: Result<Unit>) {
            runCatching {
                val debugLogManager = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    KeepAliveEntryPoint::class.java
                ).debugLogManager()
                val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                val detail = result.exceptionOrNull()?.let { "failed: ${it.message}" } ?: "sent"
                debugLogManager.appendEvent("🔔 notify($kind): $detail, notifications_enabled=$enabled")
            }
        }

        /** 发送定时任务启动通知（场景 b） */
        fun notifyAutomationStarted(context: Context, taskTitle: String): Boolean {
            if (suppressForegroundNotification(context, "task_started")) return true
            val l10n = localizedContext(context)
            val channel = NotificationChannelCompat.Builder(
                TASK_DONE_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(l10n.getString(R.string.task_done_channel_name))
                .setDescription(l10n.getString(R.string.task_done_channel_description))
                .build()
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(channel)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context, 4, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val text = l10n.getString(R.string.automation_started_notification_text, taskTitle)
            val notification = NotificationCompat.Builder(l10n, TASK_DONE_CHANNEL_ID)
                .apply { LocalizedNotifications.setText(this, l10n, R.string.automation_started_notification_title, R.string.automation_started_notification_text, taskTitle) }
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setAutoCancel(true)
                // Progress shares the result channel without alerting before completion.
                .setSilent(true)
                .setContentIntent(pendingIntent)
                .build()

            val result = try {
                nm.notify(TASK_STARTED_NOTIFICATION_ID, notification)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            }
            logNotifyResult(context, "task_started", result)
            return result.isSuccess && nm.areNotificationsEnabled()
        }

        /** 发送任务完成通知（场景 e） */
        fun notifyTaskCompleted(context: Context, taskTitle: String? = null, summary: String): Boolean {
            if (suppressForegroundNotification(context, "task_complete")) return true
            val l10n = localizedContext(context)
            val channel = NotificationChannelCompat.Builder(
                TASK_DONE_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(l10n.getString(R.string.task_done_channel_name))
                .setDescription(l10n.getString(R.string.task_done_channel_description))
                .build()
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(channel)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context, 1, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val text = summary.trim()
            val notification = NotificationCompat.Builder(l10n, TASK_DONE_CHANNEL_ID)
                .apply { LocalizedNotifications.setText(this, l10n, R.string.task_completed_notification_title) }
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val result = try {
                nm.notify(TASK_DONE_NOTIFICATION_ID, notification)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            }
            logNotifyResult(context, "task_complete", result)
            return result.isSuccess && nm.areNotificationsEnabled()
        }

        /** 发送任务中断/失败通知（场景 c） */
        fun notifyTaskInterrupted(context: Context, taskTitle: String? = null, reason: String): Boolean {
            if (suppressForegroundNotification(context, "task_interrupted")) return true
            val l10n = localizedContext(context)
            val channel = NotificationChannelCompat.Builder(
                TASK_DONE_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(l10n.getString(R.string.task_done_channel_name))
                .setDescription(l10n.getString(R.string.task_done_channel_description))
                .build()
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(channel)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context, 1, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val text = reason.trim().ifBlank { l10n.getString(R.string.task_interrupted_default_text) }
            val notification = NotificationCompat.Builder(l10n, TASK_DONE_CHANNEL_ID)
                .apply { LocalizedNotifications.setText(this, l10n, R.string.task_interrupted_notification_title, if (reason.isBlank()) R.string.task_interrupted_default_text else 0) }
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val result = try {
                nm.notify(TASK_DONE_NOTIFICATION_ID, notification)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            }
            logNotifyResult(context, "task_interrupted", result)
            return result.isSuccess && nm.areNotificationsEnabled()
        }

        private fun isAppForeground(context: Context): Boolean = runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                KeepAliveEntryPoint::class.java
            )
            // Cross-display input can pause the viewer while it remains visible.
            entryPoint.foregroundState().isInForeground ||
                entryPoint.runtimeSurfaceController().isViewerExpanded
        }.getOrDefault(false)

        /** In-app presentation counts as delivery, so scheduled results are not retried later. */
        private fun suppressForegroundNotification(context: Context, kind: String): Boolean {
            if (!isAppForeground(context)) return false
            logNotifyResult(context, "${kind}_suppressed_foreground", Result.success(Unit))
            return true
        }

        /**
         * 发送任务结束通知（独立通知，非常驻）。
         *
         * 任务完成时：若应用在界面前台（即在对话窗口）已显示在应用界面里，不用再弹窗通知；
         * 若应用不在前台（即在应用外面），才需要弹窗通知。
         *
         * 正文就是模型最终给用户的那段话本身：不带成败标题、不加前缀，展开即全文。
         */
        fun notifyTaskDone(context: Context, text: String): Boolean {
            if (suppressForegroundNotification(context, "task_complete")) return true
            val l10n = localizedContext(context)
            // 创建通知渠道（高优先级，有声音）
            val channel = NotificationChannelCompat.Builder(
                TASK_DONE_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(l10n.getString(R.string.task_done_channel_name))
                .setDescription(l10n.getString(R.string.task_done_channel_description))
                .build()
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(channel)

            // 点击跳回主界面
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = if (launchIntent != null) {
                PendingIntent.getActivity(
                    context, 1, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val notification = NotificationCompat.Builder(l10n, TASK_DONE_CHANNEL_ID)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val result = try {
                nm.notify(TASK_DONE_NOTIFICATION_ID, notification)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            }
            logNotifyResult(context, "task_complete", result)
            return result.isSuccess && nm.areNotificationsEnabled()
        }
        fun notifyManualTakeoverRequired(context: Context, detail: String? = null) {
            if (suppressForegroundNotification(context, "manual_takeover")) return
            val l10n = localizedContext(context)
            val channel = NotificationChannelCompat.Builder(
                MANUAL_TAKEOVER_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(l10n.getString(R.string.manual_takeover_channel_name))
                .setDescription(l10n.getString(R.string.manual_takeover_channel_description))
                .build()
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(channel)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = if (launchIntent != null) {
                PendingIntent.getActivity(
                    context,
                    2,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                null
            }

            val manualText = detail?.trim()?.takeIf { it.isNotBlank() }
                ?: l10n.getString(R.string.manual_takeover_notification_text)
            val notification = NotificationCompat.Builder(l10n, MANUAL_TAKEOVER_CHANNEL_ID)
                .apply { LocalizedNotifications.setText(this, l10n, R.string.manual_takeover_notification_title, if (detail.isNullOrBlank()) R.string.manual_takeover_notification_text else 0) }
                .setContentText(manualText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(manualText))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()

            val result = try {
                nm.notify(MANUAL_TAKEOVER_NOTIFICATION_ID, notification)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            }
            logNotifyResult(context, "manual_takeover", result)
        }

        /** Background tasks cannot force a runtime-permission dialog; this opens the normal host. */
        fun notifyRuntimePermissionRequired(context: Context, detail: String) {
            if (suppressForegroundNotification(context, "runtime_permission")) return
            val l10n = localizedContext(context)
            val channel = NotificationChannelCompat.Builder(
                MANUAL_TAKEOVER_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(l10n.getString(R.string.manual_takeover_channel_name))
                .setDescription(l10n.getString(R.string.manual_takeover_channel_description))
                .build()
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(channel)
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context,
                    3,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val text = detail.trim().ifBlank {
                l10n.getString(R.string.runtime_permission_notification_text)
            }
            val notification = NotificationCompat.Builder(l10n, MANUAL_TAKEOVER_CHANNEL_ID)
                .apply { LocalizedNotifications.setText(this, l10n, R.string.runtime_permission_notification_title, if (detail.isBlank()) R.string.runtime_permission_notification_text else 0) }
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()
            val result = try {
                nm.notify(MANUAL_TAKEOVER_NOTIFICATION_ID, notification)
                Result.success(Unit)
            } catch (error: SecurityException) {
                Result.failure(error)
            }
            logNotifyResult(context, "runtime_permission", result)
        }

        fun cancelManualTakeover(context: Context) {
            try {
                NotificationManagerCompat.from(context).cancel(MANUAL_TAKEOVER_NOTIFICATION_ID)
            } catch (_: SecurityException) {
                // 通知权限被拒
            }
        }
    }

    @Inject lateinit var airModePairingManager: AirModePairingManager

    private val keepAliveWakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Droidance::KeepAliveWakeLock"
        ).apply { setReferenceCounted(false) }
    }

    private val keepAliveWifiLock by lazy {
        @Suppress("DEPRECATION")
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "Droidance::KeepAliveWifiLock"
        ).apply { setReferenceCounted(false) }
    }

    @Volatile private var isStandby = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        KeepAliveJobService.schedule(this)
        airModePairingManager.ensureConnectedIfPaired()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val l10n = ContextCompat.getContextForLanguage(this)
        when (intent?.action) {
            ACTION_STOP_EXECUTION -> {
                val shouldKeepStandby = airModePairingManager.isPreviouslyPaired
                if (shouldKeepStandby) {
                    isStandby = true
                    val notification = buildNotification(l10n, R.string.task_service_standby_status)
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (e: Exception) {
                        Log.w("TaskExecutionService", "进入待命前台失败: ${e.message}")
                    }
                    acquireKeepAliveWakeLock()
                    acquireKeepAliveWifiLock()
                    return START_STICKY
                } else {
                    releaseKeepAliveWakeLock()
                    releaseKeepAliveWifiLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            ACTION_START_EXECUTION -> {
                isStandby = false
                val title = intent.getStringExtra(EXTRA_TASK_TITLE)
                val notification = if (!title.isNullOrBlank()) {
                    buildNotification(l10n, R.string.task_service_running_named, title)
                } else {
                    buildNotification(l10n, R.string.task_service_running_status)
                }
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    Log.w("TaskExecutionService", "进入前台执行服务失败: ${e.message}")
                }
                acquireKeepAliveWakeLock()
                acquireKeepAliveWifiLock()
                return START_NOT_STICKY
            }
            ACTION_STANDBY -> {
                isStandby = true
                val notification = buildNotification(l10n, R.string.task_service_standby_status)
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    Log.w("TaskExecutionService", "进入待命前台失败: ${e.message}")
                }
                acquireKeepAliveWakeLock()
                acquireKeepAliveWifiLock()
                return START_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        instance = null
        if (isStandby) {
            scheduleRestart(0)
        }
        releaseKeepAliveWakeLock()
        releaseKeepAliveWifiLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isStandby) {
            scheduleRestart(1)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(l10n: Context, @StringRes statusResource: Int, vararg arguments: String): android.app.Notification {
        // 点击通知跳回主界面
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(l10n, CHANNEL_ID)
            .apply { LocalizedNotifications.setText(this, l10n, R.string.app_name, statusResource, *arguments) }
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireKeepAliveWakeLock() {
        try {
            if (!keepAliveWakeLock.isHeld) {
                keepAliveWakeLock.acquire(12 * 60 * 60 * 1000L)
            }
        } catch (error: Exception) {
            Log.w("TaskExecutionService", "Keep-alive wake lock acquisition failed: ${error.javaClass.simpleName}")
        }
    }

    private fun releaseKeepAliveWakeLock() {
        try {
            if (keepAliveWakeLock.isHeld) {
                keepAliveWakeLock.release()
            }
        } catch (error: Exception) {
            Log.w("TaskExecutionService", "Keep-alive wake lock release failed: ${error.javaClass.simpleName}")
        }
    }

    private fun acquireKeepAliveWifiLock() {
        try {
            if (!keepAliveWifiLock.isHeld) {
                keepAliveWifiLock.acquire()
            }
        } catch (error: Exception) {
            Log.w("TaskExecutionService", "Keep-alive Wi-Fi lock acquisition failed: ${error.javaClass.simpleName}")
        }
    }

    private fun releaseKeepAliveWifiLock() {
        try {
            if (keepAliveWifiLock.isHeld) {
                keepAliveWifiLock.release()
            }
        } catch (error: Exception) {
            Log.w("TaskExecutionService", "Keep-alive Wi-Fi lock release failed: ${error.javaClass.simpleName}")
        }
    }

    private fun scheduleRestart(requestCode: Int) {
        val restartIntent = Intent(applicationContext, TaskExecutionService::class.java).apply {
            action = ACTION_STANDBY
        }
        val pendingRestart = PendingIntent.getService(
            applicationContext,
            requestCode,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 3_000L,
                pendingRestart
            )
        } catch (error: Exception) {
            Log.w("TaskExecutionService", "Standby restart alarm scheduling failed: ${error.javaClass.simpleName}")
        }
    }

    private fun createNotificationChannel() {
        val l10n = ContextCompat.getContextForLanguage(this)
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(l10n.getString(R.string.task_service_channel_name))
            .setDescription(l10n.getString(R.string.task_service_channel_description))
            .build()

        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }
}
