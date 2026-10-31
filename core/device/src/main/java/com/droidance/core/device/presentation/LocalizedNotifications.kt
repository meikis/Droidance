// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.presentation

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Retains resource references so an existing notice can change language without replaying events. */
object LocalizedNotifications {
    private const val TITLE = "com.droidance.localized.title"
    private const val TEXT = "com.droidance.localized.text"
    private const val ARGUMENTS = "com.droidance.localized.arguments"

    fun setText(
        builder: NotificationCompat.Builder,
        context: Context,
        @StringRes title: Int,
        @StringRes text: Int = 0,
        vararg arguments: String
    ) {
        val localized = ContextCompat.getContextForLanguage(context)
        builder.setContentTitle(localized.getString(title))
        if (text != 0) {
            val value = localized.getString(text, *arguments)
            builder.setContentText(value).setStyle(NotificationCompat.BigTextStyle().bigText(value))
        }
        builder.addExtras(Bundle().apply {
            putInt(TITLE, title)
            putInt(TEXT, text)
            putStringArray(ARGUMENTS, arguments)
        })
    }

    /** Updates only still-visible notices, preserving their intents, timestamps and user content. */
    fun refresh(
        context: Context,
        ids: Set<Int>,
        updateActions: (NotificationCompat.Builder, Notification) -> Unit = { _, _ -> }
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        val localized = ContextCompat.getContextForLanguage(context)
        for (active in manager.activeNotifications) {
            val notification = active.notification
            val extras = notification.extras
            if (active.id !in ids || !extras.containsKey(TITLE)) continue
            val builder = NotificationCompat.Builder(localized, notification)
                .setOnlyAlertOnce(true)
            setText(builder, localized, extras.getInt(TITLE), extras.getInt(TEXT),
                *extras.getStringArray(ARGUMENTS).orEmpty())
            val timeout = notification.timeoutAfter
            if (timeout > 0) {
                val remaining = timeout - (System.currentTimeMillis() - active.postTime).coerceAtLeast(0)
                if (remaining <= 0) continue
                builder.setTimeoutAfter(remaining)
            }
            updateActions(builder, notification)
            try {
                manager.notify(active.tag, active.id, builder.build())
            } catch (error: SecurityException) {
                Log.w("LocalizedNotifications", "Notification permission changed during locale refresh", error)
            }
        }
    }
}
