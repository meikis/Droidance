// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.automation

import android.os.Bundle
import com.droidance.agent.device.NativeInputDriverClient
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Android listener operations use its system-bound driver, independently of the GUI. */
@Singleton
class ResidentNotificationAccess @Inject constructor(private val driver: NativeInputDriverClient) {
    fun available(): Boolean = driver.command("notifications").getBoolean("available")
    fun getAllNotifications(): List<NotificationInfo> =
        Json.decodeFromString(driver.command("notifications").getString("items") ?: "[]")
    fun clickNotification(key: String, displayId: Int?): Boolean = driver.command("notificationClick", Bundle().apply {
        putString("key", key); displayId?.let { putInt("display", it) }
    }).getBoolean("result")
    fun dismissNotification(key: String) { driver.command("notificationDismiss", Bundle().apply { putString("key", key) }) }
}
