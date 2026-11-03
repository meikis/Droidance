// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.content.Context
import android.os.Bundle
import com.droidance.core.device.agent.device.RuntimePlatformDriver
import com.droidance.core.device.agent.device.RuntimeWire
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.Executors

/**
 * The execution owner's notifications are posted by the platform driver, which runs as the
 * application, so the system attributes them to Droidance rather than to the shell. Delivery is
 * asynchronous: a frozen presentation process must never block task execution.
 */
internal object RuntimeNotifications {
    private val delivery = Executors.newSingleThreadExecutor { Thread(it, "DroidanceNotifications") }

    fun post(context: Context, id: Int, title: Int, text: String, ongoing: Boolean = false): Boolean {
        val entry = EntryPointAccessors.fromApplication(context, RuntimeOwnerEntryPoint::class.java)
        if (!ongoing && entry.foreground().isInForeground) return true
        val arguments = Bundle().apply {
            putInt("id", id); putString("title", context.getString(title)); putString("text", text)
            putBoolean("ongoing", ongoing)
            putString("statusChannel", context.getString(com.droidance.R.string.task_service_channel_name))
            putString("resultChannel", context.getString(com.droidance.R.string.task_done_channel_name))
        }
        send("notify", arguments)
        return true
    }

    fun cancel(context: Context, id: Int) {
        send("cancelNotification", Bundle().apply { putInt("id", id) })
    }

    private fun send(operation: String, arguments: Bundle) = delivery.execute {
        try { RuntimeWire.read(RuntimePlatformDriver.connect().command(operation, arguments)) }
        catch (error: Exception) {
            android.util.Log.w("DroidanceRuntime", "Notification unavailable: ${error.javaClass.simpleName}")
        }
    }
}
