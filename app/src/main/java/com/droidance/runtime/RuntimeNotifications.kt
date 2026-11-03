// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.droidance.R
import dagger.hilt.android.EntryPointAccessors

/** The execution owner's notifications do not wait for the GUI process to run. */
internal object RuntimeNotifications {
    private const val CHANNEL = "droidance_resident_tasks"
    // The running-task notice is status, not an alert: no heads-up, sound or vibration.
    private const val STATUS_CHANNEL = "droidance_resident_status"
    fun post(context: Context, id: Int, title: Int, text: String, ongoing: Boolean = false): Boolean {
        val entry = EntryPointAccessors.fromApplication(context, RuntimeOwnerEntryPoint::class.java)
        if (!ongoing && entry.foreground().isInForeground) return true
        return try {
            val shell = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
            val manager = shell.getSystemService(NotificationManager::class.java)
            val channel = if (ongoing) NotificationChannel(STATUS_CHANNEL,
                context.getString(R.string.task_service_channel_name), NotificationManager.IMPORTANCE_LOW)
            else NotificationChannel(CHANNEL, context.getString(R.string.task_done_channel_name), NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
            val launch = Intent().setClassName(context.packageName, "com.droidance.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val content = PendingIntent.getActivity(shell, id, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(shell, channel.id)
                .setSmallIcon(Icon.createWithResource(context.packageName, R.drawable.ic_launcher_monochrome))
                .setContentTitle(context.getString(title))
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(content).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true).build()
            manager.notify("Droidance", id, notification)
            manager.areNotificationsEnabled()
        } catch (error: Exception) {
            android.util.Log.w("DroidanceRuntime", "Notification unavailable: ${error.javaClass.simpleName}")
            false
        }
    }
    fun cancel(context: Context, id: Int) {
        runCatching {
            context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
                .getSystemService(NotificationManager::class.java).cancel("Droidance", id)
        }
    }
}
