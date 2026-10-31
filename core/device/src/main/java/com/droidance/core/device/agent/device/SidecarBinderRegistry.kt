// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches the sidecar Binder that the shell-identity server pushes to the app
 * via [SidecarBinderReceiver]. The bridge reads it from here instead of from
 * ServiceManager.getService.
 */
object SidecarBinderRegistry {
    private val binders = ConcurrentHashMap<Int, IBinder>()

    // 诊断：app 端通过广播收到 binder 的次数，区分"sidecar 没发广播"和"app 没收到广播"
    @Volatile
    var receivedCount: Int = 0
        private set

    fun set(uid: Int, value: IBinder?) {
        require(uid == 2000) { "Only the paired shell sidecar can be registered" }
        if (value == null) {
            binders.remove(uid)
        } else {
            // Sidecar 会周期性重发同一个服务的 Binder，BroadcastReceiver 每次都可能
            // 得到一个新的代理对象。无条件覆盖会让 backendSessionId 在任务中途抖动，
            // 已冻结的执行绑定随后被误判为“session changed”。只在旧 Binder 已死时
            // 换代；同一常驻进程的 rebroadcast 保持第一次收到的稳定代理引用。
            val current = binders[uid]
            if (current == null || !current.isBinderAlive) {
                binders[uid] = value
            }
            receivedCount++
        }
    }

    fun get(uid: Int): IBinder? {
        if (uid != 2000) return null
        val binder = binders[uid] ?: return null
        if (binder.isBinderAlive) return binder
        binders.remove(uid, binder)
        return null
    }

    fun isAlive(uid: Int): Boolean = get(uid) != null

    fun clear(value: IBinder) {
        binders.entries
            .filter { it.value == value }
            .forEach { binders.remove(it.key, value) }
    }
}
