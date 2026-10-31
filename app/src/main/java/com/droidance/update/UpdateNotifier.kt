// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.droidance.MainActivity
import com.droidance.R
import com.droidance.update.model.AppReleaseInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Returns false when the system would hide it, so the next app open can show the dialog. */
    fun notify(release: AppReleaseInfo): Boolean {
        val l10n = ContextCompat.getContextForLanguage(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(l10n.getString(R.string.update_notification_channel))
                .build()
        )
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(l10n, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(l10n.getString(R.string.update_notification_title, release.versionName))
            .setContentText(l10n.getString(R.string.update_notification_text))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Update notification not permitted: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "UpdateNotifier"
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 10087
    }
}
