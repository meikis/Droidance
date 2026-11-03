// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.app.ActivityOptions
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 通知监听服务
 * 捕获并发布原始通知事件。
 */
class NotificationWatcher : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationWatcher"

        /** 全局实例引用 */
        var instance: NotificationWatcher? = null
            private set

        /** 通知事件流 */
        private val _notificationFlow = MutableSharedFlow<NotificationInfo>(extraBufferCapacity = 50)
        val notificationFlow: SharedFlow<NotificationInfo> = _notificationFlow
        internal fun publish(info: NotificationInfo) { _notificationFlow.tryEmit(info) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "通知监听服务已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val notification = sbn.notification
        val extras = notification.extras
        val info = NotificationInfo(
            packageName = sbn.packageName,
            appName = extras.getString("android.title.big")
                ?: extras.getString("android.title")
                ?: sbn.packageName,
            title = extras.getCharSequence("android.title")?.toString() ?: "",
            content = extras.getCharSequence("android.text")?.toString() ?: "",
            timestamp = sbn.postTime,
            key = sbn.key
        )

        Log.d(TAG, "Notification received; package=${info.packageName}")

        // 发射通知事件
        _notificationFlow.tryEmit(info)

    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知被清除时的处理（可选）
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.i(TAG, "通知监听服务已断开")
    }

    /**
     * 点击通知（触发其 PendingIntent，相当于用户点了通知栏）
     * @param key 通知的唯一标识
     * @return 是否成功
     */
    fun clickNotification(key: String, displayId: Int? = null): Boolean {
        return try {
            val sbn = activeNotifications?.find { it.key == key }
            val contentIntent = sbn?.notification?.contentIntent
            if (contentIntent != null) {
                val options = displayId?.let {
                    ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle()
                }
                contentIntent.send(this, 0, null, null, null, null, options)
                Log.i(TAG, "Notification opened")
                true
            } else {
                Log.w(TAG, "Notification unavailable")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notification open failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 清除指定通知
     * @param key 通知的唯一标识
     */
    fun dismissNotification(key: String) {
        try {
            cancelNotification(key)
            Log.i(TAG, "Notification dismissed")
        } catch (e: Exception) {
            Log.e(TAG, "Notification dismissal failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 读取当前所有活跃通知
     */
    fun getAllNotifications(): List<NotificationInfo> {
        return try {
            activeNotifications?.map { sbn ->
                val extras = sbn.notification.extras
                NotificationInfo(
                    packageName = sbn.packageName,
                    appName = extras.getCharSequence("android.title")?.toString() ?: sbn.packageName,
                    title = extras.getCharSequence("android.title")?.toString() ?: "",
                    content = extras.getCharSequence("android.text")?.toString() ?: "",
                    timestamp = sbn.postTime,
                    key = sbn.key
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Notification read failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }
}

/**
 * 通知信息数据类
 */
@kotlinx.serialization.Serializable
data class NotificationInfo(
    val packageName: String,   // 来源 App 包名
    val appName: String,       // 来源 App 名称
    val title: String,         // 通知标题
    val content: String,       // 通知内容
    val timestamp: Long,       // 通知时间
    val key: String            // 通知唯一标识
)
