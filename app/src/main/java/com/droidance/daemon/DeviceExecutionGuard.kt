// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import android.app.KeyguardManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.droidance.agent.RuntimeCapabilityRegistry
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.DeviceExecutionStrategy
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.agent.device.VirtualSpaceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程任务执行守护。
 *
 * 定时任务可能在后台、息屏、锁屏时到达。执行 UI 自动化前先做一次轻量准备：
 * - 确保前台服务处于待命/保活
 * - 拿短时 WakeLock + WifiLock，避免任务期间 CPU / 网络休眠
 * - 虚拟空间保持 sidecar/VirtualDisplay，不唤醒、不解锁实体屏
 * - 任务结束后释放锁
 */
enum class DevicePreparationFailure(val requiresUser: Boolean, val messageResource: Int) {
    BACKEND_UNAVAILABLE(false, com.droidance.R.string.automation_backend_unavailable)
}

class DevicePreparationException(val reason: DevicePreparationFailure, detail: String) :
    IllegalStateException("[${reason.name}] $detail")

@Singleton
class DeviceExecutionGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityRegistry: RuntimeCapabilityRegistry,
    private val virtualSpaceManager: VirtualSpaceManager
) {
    data class ExecutionPreparation(
        val executionSpace: ExecutionSpace,
        val runtimeMode: RuntimeMode,
        val summary: String
    )

    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val keyguardManager by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val lock = Any()

    private val executionWakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Droidance::RemoteExecutionWakeLock"
        ).apply { setReferenceCounted(false) }
    }

    private val executionWifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "Droidance::RemoteExecutionWifiLock"
        ).apply { setReferenceCounted(false) }
    }

    @Volatile private var activeCount = 0

    fun guardSession(reason: String): AutoCloseable {
        synchronized(lock) {
            activeCount += 1
            TaskExecutionService.startStandby(context)
            acquireWakeLock()
            acquireWifiLock()
            Log.i("DeviceExecutionGuard", "Session guard acquired: $reason, activeCount=$activeCount")
            return AutoCloseable {
                synchronized(lock) {
                    activeCount = (activeCount - 1).coerceAtLeast(0)
                    if (activeCount == 0) {
                        releaseWifiLock()
                        releaseWakeLock()
                        Log.i("DeviceExecutionGuard", "Session guard released: $reason")
                    }
                }
            }
        }
    }

    fun prepareForRemoteExecution(
        reason: String,
        strategy: DeviceExecutionStrategy = capabilityRegistry.currentStrategy()
    ): ExecutionPreparation {
        synchronized(lock) {
            val executionSpace = ExecutionSpace.VIRTUAL_SPACE
            val runtimeMode = strategy.selection.effectiveMode ?: throw DevicePreparationException(
                DevicePreparationFailure.BACKEND_UNAVAILABLE,
                strategy.selection.unavailableReason ?: "Runtime mode is unavailable.")
            require(runtimeMode == RuntimeMode.STANDARD) { "Only Standard execution is supported" }
            val wasInteractive = powerManager.isInteractive
            val wasLocked = keyguardManager.isKeyguardLocked

            activeCount += 1
            TaskExecutionService.startStandby(context)
            acquireWakeLock()
            acquireWifiLock()


                // 顺序不能反：虚拟屏电源组独立于主屏，任务之间隔超过唤醒锁超时就会 DOZING。
                // 先唤醒再重挂 surface——反过来的话，DOZING 的屏上没有窗口在绘，重挂等不到新帧，
                // 会把虚拟空间自己判成不可用，任务第一步就失败。
                val interactive = virtualSpaceManager.ensureDisplayInteractive()
                if (wasLocked || !wasInteractive) {
                    virtualSpaceManager.onScreenLocked()
                } else {
                    virtualSpaceManager.onScreenUnlocked()
                }
                val wakeText = if (interactive) "awake" else "unconfirmed"
                val summary = "Virtual-space background execution; main screen unchanged; " +
                    "virtual display power=$wakeText; wake lock=held; Wi-Fi lock=held"
                Log.i("DeviceExecutionGuard", "Remote execution prepared: $reason, $summary")
                return ExecutionPreparation(executionSpace, runtimeMode, summary)

        }
    }

    fun finishRemoteExecution(preparation: ExecutionPreparation, reason: String = "") {
        synchronized(lock) {
            activeCount = (activeCount - 1).coerceAtLeast(0)
            if (activeCount > 0) return

            releaseWifiLock()
            releaseWakeLock()
            Log.i(
                "DeviceExecutionGuard",
                "Remote execution guard released: $reason, space=${preparation.executionSpace}"
            )
        }
    }

    private fun acquireWakeLock() {
        try {
            if (!executionWakeLock.isHeld) {
                // 任务时长由墙钟协议决定，唤醒锁跟随任务生命周期，由 finish 释放。
                executionWakeLock.acquire()
            }
        } catch (e: Exception) {
            Log.w("DeviceExecutionGuard", "Failed to acquire the execution wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (executionWakeLock.isHeld) executionWakeLock.release()
        } catch (e: Exception) {
            Log.w("DeviceExecutionGuard", "Failed to release the execution wake lock: ${e.message}")
        }
    }

    private fun acquireWifiLock() {
        try {
            if (!executionWifiLock.isHeld) {
                executionWifiLock.acquire()
            }
        } catch (e: Exception) {
            Log.w("DeviceExecutionGuard", "Failed to acquire the execution Wi-Fi lock: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            if (executionWifiLock.isHeld) executionWifiLock.release()
        } catch (e: Exception) {
            Log.w("DeviceExecutionGuard", "Failed to release the execution Wi-Fi lock: ${e.message}")
        }
    }

}
