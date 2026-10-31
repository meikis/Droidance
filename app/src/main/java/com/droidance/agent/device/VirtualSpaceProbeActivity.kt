// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View

/**
 * 探针实际落点记录：探活失败时用来区分
 * "探针被系统重定向到主屏(display=0)" 和 "探针在虚拟屏上但渲染黑帧"。
 */
object VirtualSpaceProbeLanding {
    @Volatile var displayId: Int? = null
    @Volatile var landedAtMs: Long = 0L
    @Volatile private var active: java.lang.ref.WeakReference<Activity>? = null

    fun register(activity: Activity) {
        active = java.lang.ref.WeakReference(activity)
    }

    fun clear(activity: Activity) {
        if (active?.get() === activity) active = null
    }

    /**
     * 就绪自检做完后由 readiness 主动调用，销毁探针 Activity。探针只用来验证虚拟屏能出帧+能注入，
     * 用完必须关：否则它的白屏会一直留在虚拟屏上，被截成 Droidance 自身的纯白帧。
     * 销毁后目标 App 落到一块干净的虚拟屏上，从第一帧起就是它自己。
     */
    fun finishActive() {
        val activity = active?.get() ?: return
        active = null
        activity.runOnUiThread {
            if (!activity.isFinishing) activity.finish()
        }
    }

    fun describe(expectedDisplayId: Int): String {
        val landed = displayId ?: return "probe activity never reported a display (not launched or crashed)"
        val ageSec = (System.currentTimeMillis() - landedAtMs) / 1000
        return if (landed == expectedDisplayId) {
            "probe landed on expected display=$landed (${ageSec}s ago)"
        } else {
            "probe REDIRECTED to display=$landed, expected=$expectedDisplayId (${ageSec}s ago)"
        }
    }
}

class VirtualSpaceProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VirtualSpaceProbeLanding.displayId =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.displayId
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.displayId
            }
        VirtualSpaceProbeLanding.landedAtMs = System.currentTimeMillis()
        VirtualSpaceProbeLanding.register(this)
        setContentView(View(this).apply {
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = false
        })
    }

    override fun onDestroy() {
        VirtualSpaceProbeLanding.clear(this)
        super.onDestroy()
    }
}
