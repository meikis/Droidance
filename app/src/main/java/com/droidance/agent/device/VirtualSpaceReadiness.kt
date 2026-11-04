// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.content.ComponentName
import android.content.Context
import com.droidance.agent.RuntimeCapabilityRegistry
import com.droidance.debug.DebugLogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VirtualSpaceReadiness @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityRegistry: RuntimeCapabilityRegistry,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val sidecarBridge: VirtualSpaceSidecarBridge,
    private val virtualSpaceRuntimePolicy: VirtualSpaceRuntimePolicy,
    private val debugLogManager: DebugLogManager,
    private val driver: NativeInputDriverClient,
    private val client: dagger.Lazy<com.droidance.runtime.RuntimeProcessClient>
) {
    private companion object {
        const val INPUT_PROBE_ATTEMPTS = 3
        const val INPUT_PROBE_RETRY_MS = 200L
    }

    fun ensureReadyResult(requireHealthProbe: Boolean = false): VirtualSpaceReadinessResult {
        if (!client.get().isOwner) {
            val result = client.get().command("prepareDisplay", android.os.Bundle().apply { putBoolean("probe", requireHealthProbe) })
            val id = result.getInt("display", -1).takeIf { it > 0 }
            if (id != null) virtualSpaceManager.adoptRuntimeDisplay(id, result.getInt("width"), result.getInt("height"),
                result.getInt("density"), null, null, false)
            return VirtualSpaceReadinessResult(VirtualSpaceReadinessStatus.valueOf(result.getString("status")!!), id,
                result.getString("message").orEmpty())
        }
        val mode = capabilityRegistry.currentStrategy().selection.effectiveMode
            ?: return failed(
                status = VirtualSpaceReadinessStatus.DISPLAY_MISSING,
                displayId = null,
                message = "Virtual space requires a selected runtime mode."
            )
        val existing = capabilityRegistry.getVirtualDisplayId()
        if (existing != null && virtualSpaceManager.hasLiveSessionForMode(mode)) {
            // 只有显式要求(创建/设置页自检)才跑健康探活——它会在虚拟屏上启动探针 Activity。
            // 每个动作回合都调本方法；若这里因健康标记未 ready 就重跑探活，会把已打开的目标 App
            // 反复盖成 Droidance 探针页(observe 里 pkg=com.droidance.debug)，导致"开了应用却无法操作"。
            // display 已存在且 active 时直接复用，健康检查只在创建时做一次。
            if (requireHealthProbe || capabilityRegistry.refresh().virtualSpaceHealthReason
                    .startsWith("[INPUT_PERMISSION_DENIED]")) {
                return runHealthProbe(existing, mode)
            }
            return VirtualSpaceReadinessResult(
                status = VirtualSpaceReadinessStatus.READY,
                displayId = existing,
                message = "Virtual space is ready on displayId=$existing."
            )
        }
        if (existing != null) {
            debugLogManager.appendEvent(
                "Virtual space stale sidecar session discarded before task entry: displayId=$existing mode=${mode.name}"
            )
            virtualSpaceManager.discardStaleSession(
                "stale sidecar session discarded before task entry"
            )
        }
        val started = virtualSpaceManager.start(mode)
        if (started != null) {
            val probe = runHealthProbe(started, mode)
            if (!probe.ready) return probe
            if (probe.status == VirtualSpaceReadinessStatus.FRAME_PENDING) return probe
            return VirtualSpaceReadinessResult(
                status = VirtualSpaceReadinessStatus.READY,
                displayId = started,
                message = "Virtual space started and verified on displayId=$started."
            )
        }
        val reason = virtualSpaceManager.lastFailureReason()
            .ifBlank { "Virtual space is selected, but no virtual displayId is available." }
        capabilityRegistry.markVirtualSpaceHealthFailed(reason)
        return VirtualSpaceReadinessResult(
            status = VirtualSpaceReadinessStatus.DISPLAY_MISSING,
            displayId = null,
            message = reason
        )
    }

    fun probeReadyResult(): VirtualSpaceReadinessResult = ensureReadyResult(requireHealthProbe = true)

    private fun runHealthProbe(displayId: Int, mode: RuntimeMode): VirtualSpaceReadinessResult {
        driver.command("probeReset")
        val launch = sidecarBridge.launchActivity(displayId, probeComponentName(), mode)
        if (!launch.ok) {
            return failed(
                status = VirtualSpaceReadinessStatus.LAUNCH_FAILED,
                displayId = displayId,
                message = "[VIRTUAL_SPACE_LAUNCH_FAILED] ${launch.detail.ifBlank { "probe launch failed" }}"
            )
        }
        val frameFailure = waitForNonBlackFrame(displayId, mode)
        val inputFailure = warmUpInputChannel(displayId, mode)
        if (inputFailure != null) {
            return failed(VirtualSpaceReadinessStatus.INPUT_PERMISSION_DENIED, displayId,
                "[INPUT_PERMISSION_DENIED] ${inputFailure.detail}")
        }
        // 黑帧/无帧时附上探针实际落点：区分"被重定向主屏"和"虚拟屏渲染黑帧"两类真因
        if (frameFailure != null) {
            val (status, reason) = frameFailure
            if (
                status == VirtualSpaceReadinessStatus.FRAME_FAILED &&
                driver.command("probeState").getInt("display", -1) == displayId
            ) {
                capabilityRegistry.markVirtualSpaceHealthReady(displayId)
                debugLogManager.appendEvent(
                    "virtual_space: probe reached the target display; first frame is pending displayId=$displayId"
                )
                driver.command("probeFinish")
                return VirtualSpaceReadinessResult(
                    status = VirtualSpaceReadinessStatus.FRAME_PENDING,
                    displayId = displayId,
                    message = "[VIRTUAL_SPACE_FRAME_PENDING] Probe reached displayId=$displayId; " +
                        "the first render is still pending and will be verified after target launch."
                )
            }
            return failed(
                status = status,
                displayId = displayId,
                message = "$reason; ${describeProbe(displayId)}"
            )
        }
        // A rendered frame establishes capture readiness. Explicit input denial was handled above;
        // transient warm-up failures while the input window registers remain nonfatal.
        capabilityRegistry.markVirtualSpaceHealthReady(displayId)
        // 自检做完立刻销毁探针 Activity，避免白屏残留并覆盖目标 App 的首帧。
        driver.command("probeFinish")
        return VirtualSpaceReadinessResult(
            status = VirtualSpaceReadinessStatus.READY,
            displayId = displayId,
            message = "Virtual space is ready on displayId=$displayId."
        )
    }

    /**
     * Retry transient input-channel startup failures; explicit permission denial cannot self-heal.
     */
    private fun warmUpInputChannel(displayId: Int, mode: RuntimeMode): VirtualSpaceSidecarBridge.CommandResult? {
        repeat(INPUT_PROBE_ATTEMPTS) { attempt ->
            val tap = sidecarBridge.injectNative(displayId, "tap 16 16", mode)
            if (tap.ok) return null
            if (tap.inputPermissionDenied) return tap
            if (attempt < INPUT_PROBE_ATTEMPTS - 1) Thread.sleep(INPUT_PROBE_RETRY_MS)
        }
        debugLogManager.appendEvent(
            "virtual_space: input warm-up was not confirmed; runtime injection may recover displayId=$displayId"
        )
        return null
    }

    /** 帧探测通过返回 null；失败返回 (状态, 原因)，由调用方补充落点诊断后统一 failed()。 */
    private fun waitForNonBlackFrame(displayId: Int, mode: RuntimeMode): Pair<VirtualSpaceReadinessStatus, String>? {
        val deadline = System.currentTimeMillis() + virtualSpaceRuntimePolicy.renderWaitTimeoutMs()
        var lastReason = "[VIRTUAL_SPACE_FRAME_FAILED] no probe frame returned for displayId=$displayId"
        while (System.currentTimeMillis() < deadline) {
            val bytes = sidecarBridge.captureLatestPng(displayId, mode = mode)
            if (bytes != null) {
                val blackFrame = virtualSpaceRuntimePolicy.checkBlackFrame(bytes)
                if (!blackFrame.isBlack) return null
                lastReason = virtualSpaceRuntimePolicy.emptyFrameFailure()
            }
            Thread.sleep(virtualSpaceRuntimePolicy.renderWaitPollMs())
        }
        val status = if (lastReason.startsWith(VirtualSpaceRuntimePolicy.EMPTY_FRAME_MARKER)) {
            VirtualSpaceReadinessStatus.BLACK_FRAME
        } else {
            VirtualSpaceReadinessStatus.FRAME_FAILED
        }
        return status to lastReason
    }

    private fun failed(
        status: VirtualSpaceReadinessStatus,
        displayId: Int?,
        message: String
    ): VirtualSpaceReadinessResult {
        capabilityRegistry.markVirtualSpaceHealthFailed(message)
        // 真因落盘：之前探活失败只在设置页一闪而过，debug 包里看不到卡在哪一步
        debugLogManager.appendEvent("virtual_space: readiness probe failed [$status] $message")
        // 失败路径也要销毁探针（落点诊断已在上游读过静态字段），避免它作为白屏残留到下次任务
        driver.command("probeFinish")
        return VirtualSpaceReadinessResult(status = status, displayId = displayId, message = message)
    }

    private fun describeProbe(expected: Int): String {
        val landed = driver.command("probeState").getInt("display", -1)
        return "Probe display=$landed, expected=$expected"
    }

    private fun probeComponentName(): String =
        ComponentName(context.packageName, VirtualSpaceProbeActivity::class.java.name).flattenToString()
}

data class VirtualSpaceReadinessResult(
    val status: VirtualSpaceReadinessStatus,
    val displayId: Int?,
    val message: String
) {
    val ready: Boolean
        get() = (
            status == VirtualSpaceReadinessStatus.READY ||
                status == VirtualSpaceReadinessStatus.FRAME_PENDING
            ) && displayId != null
}

enum class VirtualSpaceReadinessStatus {
    READY,
    FRAME_PENDING,
    DISPLAY_MISSING,
    LAUNCH_FAILED,
    FRAME_FAILED,
    BLACK_FRAME,
    INPUT_FAILED,
    INPUT_PERMISSION_DENIED,
    TEXT_INPUT_FAILED
}
