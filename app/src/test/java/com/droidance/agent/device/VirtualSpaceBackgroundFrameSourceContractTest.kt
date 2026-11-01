// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualSpaceBackgroundFrameSourceContractTest {
    @Test
    fun appLaunchWaitsForAFrameDifferentFromThePreviousApp() {
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val beginExecution = manager.substringAfter("fun beginExecution(executionId: String, threadId: String)")
            .substringBefore("fun launchApp(")
        val launchApp = manager.substringAfter("fun launchApp(")
            .substringBefore("fun launchActivity(component: String)")
        val launchActivity = manager.substringAfter("fun launchActivity(component: String)")
            .substringBefore("fun injectNative(nativeArgs: String)")
        val waitForFrame = manager.substringAfter("private fun waitForRenderableFrame(")
            .substringBefore("private fun monotonicNowMs()")
        val appLaunch = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()

        assertTrue(launchApp.contains("val previousFrameBudgetMs = virtualSpaceRuntimePolicy.renderWaitPollMs()"))
        assertTrue(launchApp.contains("captureFrameSample(id, session, previousFrameBudgetMs)"))
        assertTrue(launchApp.contains("val frameBoundaryAtMs = System.currentTimeMillis()"))
        assertTrue(launchApp.indexOf("previousFrame") < launchApp.indexOf("launchPackage(binding"))
        assertTrue(launchApp.contains("waitForRenderableFrame("))
        assertTrue(launchApp.contains("return VirtualSpaceLaunchResult("))
        assertTrue(launchApp.contains("commandSent = result.ok"))
        assertTrue(launchApp.contains("detail = result.detail"))
        assertTrue(launchActivity.contains("val previousFrameBudgetMs = virtualSpaceRuntimePolicy.renderWaitPollMs()"))
        assertTrue(launchActivity.contains("captureFrameSample(id, session, previousFrameBudgetMs)"))
        assertTrue(launchActivity.contains("val frameBoundaryAtMs = System.currentTimeMillis()"))
        assertTrue(launchActivity.indexOf("previousFrame") < launchActivity.indexOf("sidecarBridge.launchActivity"))
        assertTrue(launchActivity.contains("waitForRenderableFrame("))
        assertTrue(launchActivity.contains("return VirtualSpaceLaunchResult("))
        assertTrue(launchActivity.contains("commandSent = result.ok"))
        assertTrue(launchActivity.contains("detail = result.detail"))
        assertTrue(waitForFrame.contains("captureFrameSample(displayId, session, remainingMs)"))
        assertTrue(waitForFrame.contains("minProducedAtMs = minProducedAtMs"))
        // 执行边界**不许换屏**：20260726-V3 试过每次执行 release 旧屏重建一块，真机实测把冷启动
        // 打断了——空屏上深链只起一个 router/trampoline activity 然后 finish，屏上一帧都不产生，
        // 模型连屏幕都看不到（连续 5 次 VIRTUAL_SPACE_FRAME_FAILED 后只能 ask_human）。
        // 启动模式由调用方选择。显示内容更新要求电源组 interactive；私有屏没有 launcher。
        assertFalse("换屏会打断冷启动，别再退回去", beginExecution.contains("resetDisplay("))
        assertFalse(beginExecution.contains("waitForRenderableFrame("))
        assertFalse(beginExecution.contains("KeyEvent.KEYCODE_HOME"))
        assertFalse(beginExecution.contains("failed to establish execution HOME boundary"))
        assertFalse(launchApp.contains("KeyEvent.KEYCODE_HOME"))
        assertFalse(launchApp.contains("执行 HOME 探针"))
        assertFalse("Frame freshness must not define backend liveness", manager.contains("lifecycleFrameReady"))
        val activeForMode = manager.substringAfter("fun isActiveForMode(mode: RuntimeMode)")
            .substringBefore("fun isVisible()")
        assertTrue(activeForMode.contains("active && sidecarSession?.mode == mode"))
        assertTrue(beginExecution.contains("VirtualSpaceLaunchResult(true, true"))
        assertTrue(appLaunch.contains("if (!launch.accepted)"))
        assertTrue(appLaunch.contains("\"VIRTUAL_SPACE_LAUNCH_FAILED\""))
        assertTrue(appLaunch.contains("if (launch.frameReady != true)"))
        assertTrue(appLaunch.contains("[VIRTUAL_SPACE_LAUNCHED_UNVERIFIED]"))

        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        assertTrue(bridge.contains("markSidecarSuspectOnTimeout: Boolean = true"))
        assertTrue(bridge.contains("if (markSidecarSuspectOnTimeout) markSidecarSuspect(suspectService)"))
    }

    @Test
    fun deepLinkRoutesLandTheAppEntryFirstAndInjectionFailuresReportTheirRealCause() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val route = registrar.substringAfter("private suspend fun launchKnowledgeRoute(")
            .substringBefore("private suspend fun landAppEntry(")
        val verifiedTap = executor.substringAfter("private suspend fun performVerifiedTap(")
            .substringBefore("\n    private ")

        // 深链只保证命令被接受，不保证落地渲染（真机：美团 router activity 起来即 finish，
        // 屏上零帧）。显式 launcher intent 在虚拟屏上一定落地，所以先落地再打深链，
        // 入口落地和路由分别返回执行事实，使用调用方选择的启动模式。
        assertTrue(route.contains("landAppEntry(resolution.route.packageName, launchMode)"))
        assertTrue(route.contains("if (preflight == null && AppEntryLanding.required("))
        val preflightGuard = route.indexOf("if (preflight == null && AppEntryLanding.required(")
        val entryLanding = route.indexOf("landAppEntry(resolution.route.packageName, launchMode)")
        val routeLaunch = route.indexOf("val launchResult = preflight ?: launchExplicitIntent(")
        assertTrue("Preflight must gate entry landing before route dispatch", preflightGuard in 0 until entryLanding)
        assertTrue("Entry landing must precede route dispatch", entryLanding in 0 until routeLaunch)
        // 入口已按 launchMode 重置过，深链不能再走 FRESH，否则会把刚落地的页面 force-stop 掉。
        assertTrue(route.contains("val routeLaunchMode = if (entryLanding != null) AppLaunchMode.RESUME else launchMode"))
        assertTrue(route.contains("launchMode = routeLaunchMode"))
        assertTrue(registrar.contains("AppEntryLanding.launcherComponent(context, packageName)"))
        assertTrue(
            projectFile("core/device/src/main/java/com/droidance/core/device/tools/AppEntryLanding.kt").readText()
                .contains("getLaunchIntentForPackage(packageName)?.component")
        )
        assertTrue(registrar.contains("[APP_ENTRY_REQUEST_ACCEPTED_UNVERIFIED]"))
        assertFalse(registrar.contains("[APP_ENTRY_LANDED]"))
        assertFalse(route.contains("if (routeFailed && entryLanding == null)"))
        assertFalse(route.contains("AppLaunchMode.RESET_TASK"))
        // 注入本身失败时换坐标重试没有意义，且必须据实报出真因：真机日志里"未通过语义校验"
        // 这句含糊结论让模型在一块无法接收输入的屏上把整轮预算耗光。
        assertTrue(verifiedTap.contains("if (!primary.success) {"))
        assertTrue(verifiedTap.contains("ToolProtocolResult.failed(primary.diagnosticCode ?: \"TAP_FAILED\""))
        assertTrue(verifiedTap.contains("JsonPrimitive(primary.message)"))
        assertFalse(verifiedTap.contains("findBestTapFallbackElement"))
        assertFalse(verifiedTap.contains("executeTapByPrecision(fallback"))
    }

    @Test
    fun observeScreenDoesNotFallBackToMainScreenWhenVirtualFrameFails() {
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()

        assertFalse(observe.contains("falling back to the main screen"))
        assertFalse(observe.contains("capture.captureFrame(displayId = null)"))
        assertTrue(observe.contains("deviceBackend.captureScreen("))
        assertTrue(observe.contains("code = \"OBSERVATION_UNAVAILABLE\""))
    }

    @Test
    fun emptyFrameRecoveryCannotMarkTheExecutionBackendUnavailable() {
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val reset = manager.substringAfter("fun resetFrameChannel(): Boolean")
            .substringBefore("fun stop()")

        assertFalse(reset.contains("active = false"))
        assertFalse(reset.contains("lifecycleFrameReady"))
        assertTrue(reset.contains("sidecarBridge.onScreenUnlocked"))
    }

    @Test
    fun virtualTaskStopReleasesTheDisplayWithoutTerminatingTheResidentSidecar() {
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val stop = manager.substringAfter("private fun stopSidecarSession()")
            .substringBefore("/** 主屏分辨率")
        val shutdown = service.substringAfter("public synchronized boolean shutdown()")
            .substringBefore("private synchronized boolean releaseDisplay()")
        val destroy = service.substringAfter("public void destroy()")
            .substringBefore("public boolean registerAsSystemService()")

        assertTrue(stop.contains("sidecarBridge.shutdown(session.mode)"))
        assertFalse(stop.contains("destroy"))
        assertTrue(shutdown.contains("return releaseDisplay()"))
        assertFalse(shutdown.contains("System.exit"))
        assertTrue(destroy.contains("System.exit(0)"))
    }

    @Test
    fun screenshotCacheIsScopedByDisplayId() {
        val backend = projectFile("app/src/main/java/com/droidance/agent/device/AndroidDeviceBackend.kt").readText()
        val capture = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarScreenCapture.kt").readText()
        assertTrue(backend.contains("it.displayId == activeDisplayId"))
        assertTrue(capture.contains("!sidecarBridge.isBoundSessionAlive(binding)"))
        assertTrue(capture.contains("virtualSpaceRuntimePolicy.emptyFrameFailure()"))
        assertFalse(capture.contains("captureMainScreen"))
    }

    @Test
    fun blankVirtualFrameIsReportedAsRecoverableStateWithoutInventingAnAppRestriction() {
        val policy = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceRuntimePolicy.kt").readText()
        val rules = projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText()

        assertTrue(policy.contains("EMPTY_FRAME_MARKER"))
        assertTrue(policy.contains("fun emptyFrameFailure()"))
        assertTrue(policy.contains("virtual_space_empty_frame_guidance"))
        assertFalse(policy.contains("当前页面禁止后台显示"))
        assertTrue(rules.contains("virtual_space_empty_frame_guidance:"))
        assertTrue(rules.contains("The virtual display has no renderable content yet."))
        assertFalse(rules.contains("call android_act(op=\"open\") first"))
    }

    @Test
    fun virtualSpaceHealthProbeStillChecksFrameAndInput() {
        val settings = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()
        val readiness = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceReadiness.kt").readText()
        assertFalse(settings.contains("fun selectExecutionSpace"))
        assertTrue(readiness.contains("VirtualSpaceProbeActivity::class.java.name"))
        assertTrue(readiness.contains("sidecarBridge.captureLatestPng(displayId, mode = mode)"))
        assertTrue(readiness.contains("sidecarBridge.injectNative(displayId, \"tap 16 16\", mode)"))
    }

    @Test
    fun missingVirtualDisplayIdDoesNotTargetMainScreen() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarScreenCapture.kt").readText()
        assertTrue(source.contains("executionContext.displayId <= 0"))
        assertTrue(source.contains("[VIRTUAL_SPACE_UNAVAILABLE]"))
        assertTrue(source.contains("Only Standard virtual-display capture is supported"))
        assertFalse(source.contains("screencap"))
    }

    @Test
    fun defaultTaskExecutionUsesBackgroundContextWithoutSilentMainFallback() {
        val strategy = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/device/DeviceExecutionStrategy.kt").readText()
        val context = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/device/ExecutionContext.kt").readText()
        assertTrue(strategy.contains("requestedSpace != ExecutionSpace.VIRTUAL_SPACE"))
        assertTrue(context.contains("context.mode == RuntimeMode.STANDARD"))
        assertTrue(context.contains("context.isVirtualSpace && context.displayId > 0"))
    }

    @Test
    fun legacyMainScreenPreferenceIsNotTreatedAsExplicitForegroundChoice() {
        val composer = projectFile("app/src/main/java/com/droidance/settings/ComposerSettingsRepository.kt").readText()
        val settings = projectFile("app/src/main/java/com/droidance/settings/GeneralSettingsRepository.kt").readText()
        assertTrue(composer.contains("executionSpace = ExecutionSpace.VIRTUAL_SPACE"))
        assertFalse(settings.contains("setRuntimeMode"))
        assertFalse(settings.contains("ultra_unlocked"))
    }

    @Test
    fun overlayTakeoverStaysOnSameVirtualDisplayWithoutMainScreenMigration() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val overlay = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()

        // 接管只暂停 Agent，绝不把 App 拉到主屏 0（§5.4）。
        val takeoverFn = runtime.substringAfter("fun beginManualTakeoverFromOverlay()")
            .substringBefore("private suspend fun waitIfPaused(")
        assertTrue(runtime.contains("fun beginManualTakeoverFromOverlay()"))
        assertTrue(takeoverFn.contains("pauseForManualControl("))
        assertFalse(takeoverFn.contains("requestManualTakeover("))
        assertFalse(takeoverFn.contains("notifyManualTakeoverRequired"))
        assertFalse(takeoverFn.contains("cancelActiveRequests"))
        assertFalse(
            "takeover must not migrate the app to main display 0",
            takeoverFn.contains("bringCurrentAppToMainDisplay()")
        )
        assertFalse(overlay.contains("am start --display 0"))
        // 灵动岛点击进入同 display 的查看页，显式接管仍走统一暂停入口。
        assertTrue(overlay.contains("openVirtualSpaceViewer()"))
    }

    @Test
    fun virtualSpaceViewerUsesFullFrameAndFloatingReversibleControls() {
        val viewer = listOf("VirtualSpaceViewerActivity.kt", "ViewerControlIslandView.kt")
            .joinToString("\n") { projectFile("app/src/main/java/com/droidance/agent/device/$it").readText() }
        val panel = viewer.substringAfter("private fun buildContentView(): View {")
            .substringBefore("private fun applySystemBarInsets(")

        assertFalse("查看页是普通 Activity，不得退回悬浮窗", viewer.contains("TYPE_APPLICATION_OVERLAY"))
        assertTrue(panel.contains("ViewGroup.LayoutParams.MATCH_PARENT"))
        assertFalse(panel.contains("viewerHeaderHeightPx()"))
        assertFalse(panel.contains("viewerFooterHeightPx()"))
        assertTrue(panel.contains("ViewerControlIslandView("))
        assertTrue(viewer.contains("internal class ViewerControlIslandView("))
        assertTrue(viewer.contains("R.string.takeover_close_viewer"))
        assertTrue(viewer.contains("R.string.takeover_start_control"))
        assertTrue(viewer.contains("R.string.takeover_return_control"))
        assertTrue(viewer.contains("override fun handleOnBackPressed() = sendVirtualBack()"))
        assertTrue(viewer.contains("R.string.common_stop"))
        assertTrue(viewer.contains("private fun sendVirtualBack()"))
        assertTrue(viewer.contains("KeyEvent.KEYCODE_BACK"))
        assertTrue(viewer.contains("agentRuntime.resumeManualTakeover()"))
    }

    @Test
    fun taskTimeVirtualSpaceRecoveryUsesOneSharedReadinessPath() {
        val readiness = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceReadiness.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val backendProbe = projectFile("app/src/main/java/com/droidance/agent/attempt/AndroidExecutionAttemptBackendProbe.kt").readText()
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()
        val appLaunch = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()

        assertTrue(readiness.contains("class VirtualSpaceReadiness"))
        assertTrue(readiness.contains("capabilityRegistry.getVirtualDisplayId()"))
        assertTrue(readiness.contains("virtualSpaceManager.hasLiveSessionForMode(mode)"))
        assertTrue(readiness.contains("virtualSpaceManager.discardStaleSession("))
        assertTrue(readiness.contains("stale sidecar session discarded"))
        assertTrue(readiness.contains("fun ensureReadyResult(requireHealthProbe: Boolean = false): VirtualSpaceReadinessResult"))
        assertTrue(readiness.contains("data class VirtualSpaceReadinessResult"))
        assertTrue(readiness.contains("enum class VirtualSpaceReadinessStatus"))
        assertTrue(readiness.contains("FRAME_PENDING"))
        assertTrue(readiness.contains("VirtualSpaceProbeLanding.displayId == displayId"))
        assertTrue(readiness.contains("status == VirtualSpaceReadinessStatus.FRAME_PENDING"))
        assertTrue(readiness.contains("val message: String"))
        assertTrue(readiness.contains("virtualSpaceManager.lastFailureReason()"))
        assertTrue(readiness.contains("probeReadyResult()"))
        assertTrue(backendProbe.contains("private val virtualSpaceReadiness: VirtualSpaceReadiness"))
        assertTrue(backendProbe.contains("virtualSpaceReadiness.ensureReadyResult()"))
        assertFalse(executor.contains("VirtualSpaceReadiness"))
        assertTrue(executor.contains("override fun beginFrozenExecutionContext("))
        assertFalse(observe.contains("VirtualSpaceReadiness"))
        assertTrue(observe.contains("executionContextStore.requireCurrent()"))
        assertFalse(appLaunch.contains("VirtualSpaceReadiness"))
        assertTrue(appLaunch.contains("executionContextStore.requireCurrent()"))
        assertTrue(appLaunch.contains("context.readinessMessage"))
        assertFalse(appLaunch.contains("private fun ensureVirtualSpaceReady()"))
    }

    @Test
    fun virtualSpaceCreationUsesSelectedRuntimeModeSidecar() {
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val sidecar = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarMain.java").readText()
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val aidl = projectFile("core/device/src/main/aidl/com/droidance/core/device/agent/device/IVirtualSpaceSidecar.aidl").readText()
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val gradle = projectFile("app/build.gradle.kts").readText()
        val proguard = projectFile("app/proguard-rules.pro").readText()

        assertTrue(manager.contains("sidecarBridge.isChannelAvailableForMode(mode)"))
        assertTrue(manager.contains("val session = startBinderSidecar(spec, mode) ?: return null"))
        assertTrue(manager.contains("sidecar display ready"))
        assertTrue(manager.contains("Standard sidecar unavailable"))
        assertFalse(manager.contains("宿主进程后端不可用时需要独立后台 sidecar 提供 displayId"))
        assertTrue(sidecar.contains("public static void main(String[] args)"))
        assertTrue(service.contains("ServiceManager"))
        assertTrue(service.contains("addService"))
        assertTrue(service.contains("ImageReader.newInstance"))
        assertTrue(service.contains("createVirtualDisplay"))
        val displayFlags = projectFile(
            "core/device/src/main/java/com/droidance/core/device/agent/device/SidecarPlatformCompat.java"
        ).readText()
        assertTrue(service.contains("SidecarPlatformCompat.virtualDisplayFlags(Build.VERSION.SDK_INT)"))
        assertFalse(service.contains("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH"))
        // App 自己的查看页/接管镜像一直在主屏前台持有焦点。虚拟屏不自持焦点与触摸模式时，
        // 该屏窗口拿不到自己的焦点代际，注入的 tap/swipe 会被系统丢弃（agent 与人工接管同时点不动）。
        assertTrue("虚拟屏必须自持焦点，否则主屏前台一有 App 窗口就注入不进去", displayFlags.contains("flags |= OWN_FOCUS"))
        // ALWAYS_UNLOCKED 只对非默认显示组合法：两者必须成对出现，单独摘 OWN_DISPLAY_GROUP
        // 会让 createVirtualDisplay 抛参数异常，虚拟空间连屏都建不起来（20260725 实测踩过）。
        assertTrue(displayFlags.contains("flags |= TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED"))
        // 真机 dumpsys 实证(20260726)：独占 power group 和主屏一样受息屏超时管辖，建屏时唤醒一次
        // 之后没有任何用户活动再喂给它，几十秒后 wakefulness=DOZING / mIsInteractive=false，
        // InputManagerService 把该屏列入 "Display not interactive"，InputDispatcher 丢弃所有触摸
        // (TouchStatesByDisplay: <no displays touched>)，ActivityTaskManager 也不再 resume 新
        // activity——"点不动/滑不动"和"深链 Success 却仍是上一任务的页面"是同一个根因。
        //
        // 唯一验证有效的修法：以 displayId 为作用域持一把 SCREEN_BRIGHT 唤醒锁。同机验证：
        //   acquire 后 wakefulness=1(AWAKE)、not interactive: {}、注入触摸进入目标窗口。
        // 所以"只在建屏时唤醒一次"是不够的，注入前必须确认 interactive——这条不能再退回去。
        assertTrue(service.contains("private boolean keepDisplayInteractive("))
        assertTrue(service.contains("\"newWakeLock\", int.class, String.class, int.class"))
        assertTrue(service.contains("SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP"))
        assertTrue(service.contains("\"wakeUp\", long.class, int.class, String.class, int.class"))
        assertTrue(service.contains("\"userActivity\", int.class, long.class, int.class, int.class"))
        // 锁必须带超时并由动作刷新，否则空闲期一直吊着电源组耗电。
        assertTrue(service.contains("DISPLAY_WAKE_LOCK_TIMEOUT_MS"))
        assertTrue(service.contains("\"acquire\", long.class"))
        assertTrue(service.contains("releaseDisplayWakeLock()"))
        // 每条注入/启动入口都要过 interactive 前置；做不到就明确失败，绝不报"点击成功"
        // ——系统丢弃触摸时注入侧看不出异常，无声成功会让 agent 在死屏上耗光整轮预算。
        assertTrue(service.contains("private boolean acceptsInteractiveDisplay("))
        assertTrue(service.contains("display_not_interactive"))
        for (entry in listOf(
            "public boolean tap(",
            "public boolean swipe(",
            "public boolean key(",
            "public boolean launchPackage(",
            "public boolean launchActivity("
        )) {
            assertTrue(
                "${'$'}entry 必须先过 interactive 前置",
                service.substringAfter(entry).substringBefore("\n    }")
                    .contains("acceptsInteractiveDisplay(targetDisplayId)")
            )
        }
        // 真机实测：注入 KEYCODE_WAKEUP 唤醒的是主屏电源组、对虚拟屏无效，还会点亮用户主屏。
        assertFalse(service.contains("KeyEvent.KEYCODE_WAKEUP"))
        // 唤醒结果必须可观测：拼进 getStatus，App 侧 debug 包才看得到 wakelock/interactive 状态。
        assertTrue(service.contains("displayWakeEvidence"))
        assertTrue(
            service.substringAfter("public String getStatus()")
                .substringBefore("\n    @Override")
                .contains("displayWakeEvidence")
        )
        assertFalse(service.contains("VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP"))
        assertTrue(service.contains("injectInputEvent"))
        assertTrue(service.contains("byte[] capturePng"))
        assertTrue(aidl.contains("boolean touch("))
        assertTrue(service.contains("public boolean touch("))
        assertTrue(bridge.contains("fun injectTouch("))
        assertTrue(aidl.contains("launchPackage"))
        assertTrue(aidl.contains("capturePng"))
        assertTrue(bridge.contains("fun captureLatestPng"))
        assertTrue(bridge.contains("fun injectNative"))
        assertTrue(bridge.contains("startAdbShellAppProcess"))
        assertTrue(bridge.contains("airModePairingManager.shell"))
        assertTrue(bridge.contains("adb_shell_app_process_binder"))
        assertTrue(sidecar.contains("private static final String TAG = \"DroidanceVSpace\""))
        assertTrue(sidecar.contains("reportFailure("))
        assertFalse(sidecar.contains("catch (Throwable ignored) {\n        }"))
        assertTrue(bridge.contains("VirtualSpaceSidecarService.SERVICE_NAME"))
        assertTrue(bridge.contains("DroidanceVSpace"))
        assertFalse(bridge.contains("Shizuku.bindUserService"))
        assertFalse(bridge.contains("UserServiceArgs"))
        assertFalse(bridge.contains("rikka.shizuku"))
        assertFalse(manifest.contains("moe.shizuku.manager.permission.API_V23"))
        assertFalse(manifest.contains("rikka.shizuku.ShizukuProvider"))
        assertFalse(gradle.contains("dev.rikka.shizuku"))
        assertTrue(bridge.contains("SidecarBinderRegistry.get("))
        assertFalse(bridge.contains("ServiceManager"))
        assertFalse(bridge.contains("SIDECAR_COMMAND_PATH"))
        assertFalse(bridge.contains("SIDECAR_RESPONSE_PATH"))
        assertFalse(bridge.contains("execBytes(\"cat"))
        assertFalse(service.contains("FileOutputStream"))
        assertFalse(service.contains("commandLoop"))
        assertTrue(proguard.contains("ADB shell app_process"))
        assertTrue(proguard.contains("-keep class com.droidance.core.device.agent.device.VirtualSpaceSidecarMain"))
        assertTrue(proguard.contains("-keep class com.droidance.core.device.agent.device.VirtualSpaceSidecarService"))
    }

    @Test
    fun takeover_touch_stream_preserves_move_events_and_frames_encode_only_when_requested() {
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val aidl = projectFile("core/device/src/main/aidl/com/droidance/core/device/agent/device/IVirtualSpaceSidecar.aidl").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val viewer = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt").readText()
        val listener = service.substringAfter("imageReader.setOnImageAvailableListener")
            .substringBefore("}, new Handler(thread.getLooper()))")
        val capture = service.substringAfter("public byte[] capturePng(int targetDisplayId)")
            .substringBefore("/**", missingDelimiterValue = service)
        val previewCapture = service.substringAfter("public byte[] capturePreviewJpeg(int targetDisplayId)")
            .substringBefore("@Override", missingDelimiterValue = service)
        val previewImplementation = service.substringAfter("byte[] preview()")
            .substringBefore("@Override public void close()", missingDelimiterValue = service)
        val sampler = service.substringAfter("public Bundle sampleFrame(int targetDisplayId)")
            .substringBefore("private static void logFrameTiming")
        assertTrue(sampler.contains("captureDisplayBitmap(targetDisplayId)"))
        assertFalse(sampler.contains("frames.acquire()"))
        assertTrue(sampler.contains("putBoolean(\"freshSnapshot\", true)"))
        val touch = service.substringAfter("public boolean touch(")
            .substringBefore("@Override", missingDelimiterValue = service)

        assertTrue(listener.contains("stream.publish(new CapturedFrame(image"))
        assertTrue(listener.contains("image.getWidth()"))
        assertTrue(listener.contains("image.getHeight()"))
        assertFalse("后台帧回调不能持续压缩全尺寸 PNG", listener.contains("encodeImageToPng"))
        assertTrue(capture.contains("frame.png()"))
        assertTrue("接管预览不能继续轮询全尺寸 PNG", aidl.contains("capturePreviewJpeg"))
        assertTrue(previewCapture.contains("lease.value().preview()"))
        assertFalse(previewCapture.contains("encodeImageToPng"))
        assertTrue(previewImplementation.contains(
            "encodeImageToJpeg(image, width, height, PREVIEW_MAX_WIDTH, PREVIEW_JPEG_QUALITY)"
        ))
        assertTrue(service.contains(
            "private final Object pngLock = new Object(), previewLock = new Object()"
        ))
        assertTrue(bridge.contains("fun captureLatestPreviewJpeg("))
        assertTrue(manager.contains("fun captureLatestPreviewJpeg(): ByteArray?"))
        assertTrue(manager.contains("fun activeDisplaySize(): Pair<Int, Int>?"))
        assertTrue(viewer.contains("virtualSpaceManager.captureLatestPreviewJpeg()"))
        assertFalse(viewer.contains("virtualSpaceManager.captureLatestPng()"))
        assertTrue(viewer.contains("VirtualDisplayTouchMapper.mapViewToDisplay("))
        assertTrue(touch.contains("MotionEvent.ACTION_MOVE"))
        assertTrue(touch.contains("manualTouchActive"))
        assertFalse(touch.contains("manualTouchDispatched"))
        assertTrue(touch.contains("beginRuntimeInputDispatch()"))
        assertTrue(touch.contains("endRuntimeInputDispatch()"))
        assertTrue(touch.contains("Math.max(0L, downTimeMs)"))
        assertTrue(touch.contains("Math.max(safeDownTime, eventTimeMs)"))
        assertTrue(touch.contains("MotionEvent.obtain("))
        assertTrue(touch.contains("injectInputEventOnce(event, INJECT_INPUT_EVENT_MODE_ASYNC)"))
        assertTrue(touch.contains("injectInputEventWithRetry(event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)"))
        assertFalse(touch.contains("return tap(targetDisplayId, x, y)"))
        assertFalse(touch.contains("return longPress("))
    }

    @Test
    fun sidecarProtocolKeepsLegacyTransactionsStableAndRestartsStaleResidentProcess() {
        val aidl = projectFile("core/device/src/main/aidl/com/droidance/core/device/agent/device/IVirtualSpaceSidecar.aidl").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val main = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarMain.java").readText()

        val capturePng = aidl.indexOf("byte[] capturePng(int displayId);")
        val captureMain = aidl.indexOf("byte[] captureMainScreenPng();")
        val execShell = aidl.indexOf("Bundle execShellCommand(String command, long timeoutMs, String executionToken);")
        val shutdown = aidl.indexOf("boolean shutdown();")
        val destroy = aidl.indexOf("void destroy();")
        val preview = aidl.indexOf("byte[] capturePreviewJpeg(int displayId);")
        val protocol = aidl.indexOf("int getProtocolVersion();")
        val screenLocked = aidl.indexOf("boolean onScreenLocked();")
        val screenUnlocked = aidl.indexOf("boolean onScreenUnlocked();")
        val captureFrame = aidl.indexOf("Bundle capturePngFrame(int displayId);")
        val inputEvidence = aidl.indexOf("String getLastInputEvidence();")
        val ensureInteractive = aidl.indexOf("boolean ensureDisplayInteractive(int displayId);")
        assertFalse(aidl.contains("getFocusedEditableBounds"))
        assertFalse(service.contains("dump-visible-window-views"))
        val sampleFrame = aidl.indexOf("byte[] sampleFrame(int displayId);")

        assertTrue("Retired capture transaction must not be shipped", captureMain == -1)
        assertTrue(capturePng < execShell)
        assertTrue("既有 AIDL 事务顺序不能改变", execShell < shutdown)
        assertTrue("既有 AIDL 事务顺序不能改变", shutdown < destroy)
        assertTrue("新事务只能追加，不能让旧常驻进程误读调用", destroy < preview)
        assertTrue("协议探针必须追加在所有既有事务之后", preview < protocol)
        assertTrue("锁屏生命周期事务只能追加在冻结边界之后", protocol < screenLocked)
        assertTrue("解锁生命周期事务只能追加在锁屏事务之后", screenLocked < screenUnlocked)
        assertTrue("带时间戳帧事务只能追加在所有既有事务之后", screenUnlocked < captureFrame)
        assertTrue("唤醒事务只能追加在所有既有事务之后", captureFrame < ensureInteractive)
        assertTrue("唤醒事务只能追加在所有既有事务之后", inputEvidence < ensureInteractive)
        assertTrue(service.contains("public int getProtocolVersion()"))
        assertTrue(service.contains("SIDECAR_PROTOCOL_VERSION = 73"))
        assertTrue(bridge.contains("SIDECAR_PROTOCOL_VERSION = VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION"))
        assertTrue(service.contains("public Bundle capturePngFrame("))
        assertTrue(service.contains("public Bundle sampleFrame(int targetDisplayId)"))
        assertTrue(bridge.contains("fun captureLatestPngFrame("))
        assertTrue(bridge.contains("fun sampleFrame(binding: SidecarSessionBinding)"))
        assertTrue(service.contains("public synchronized boolean onScreenLocked()"))
        assertTrue(service.contains("keepDisplaySurfaceAttached("))
        assertTrue(service.contains("status = \"RECOVERING\""))
        assertTrue(service.contains("waitForFreshFrame("))
        assertTrue(service.contains("status = \"STALE\""))
        assertTrue(service.contains("SIDECAR_PROTOCOL_VERSION"))
        assertTrue(bridge.contains("replaceIncompatibleResidentSidecar("))
        assertTrue(bridge.contains("service.protocolVersion == SIDECAR_PROTOCOL_VERSION"))
        assertTrue(main.contains("replaceStaleSidecarProcesses("))
        assertTrue(main.contains("droidance-sidecar-protocol="))
        assertTrue(main.contains("Process.killProcess(pid)"))
        assertTrue(main.contains("acquireProcessLeaseWithRetry("))
        assertTrue(bridge.contains("stopResidentSidecarProcess("))
        assertTrue(bridge.contains("SidecarBinderRegistry.clear("))
        val replacement = bridge.substringAfter("private fun replaceIncompatibleResidentSidecar(")
            .substringBefore("private fun stopResidentSidecarProcess(")
        val stop = bridge.substringAfter("private fun stopResidentSidecarProcess(")
            .substringBefore("private fun clearStoppedSidecar(")
        val startup = bridge.substringAfter("fun ensureStarted(")
            .substringBefore("fun ensureResidentChannel(")
        assertFalse("未知协议 Binder 不能调用可能错位的 destroy 事务", replacement.contains(".destroy()"))
        assertTrue(
            bridge.contains(
                "protocolVersion = SIDECAR_PROTOCOL_VERSION.takeIf { currentProtocolOnly }"
            )
        )
        assertTrue(
            bridge.contains(
                "append(\"droidance-sidecar-protocol=${'$'}SIDECAR_PROTOCOL_VERSION \")"
            )
        )
        assertTrue(stop.contains("ResidentSidecarProcessCommands.stopCommand("))
        assertTrue(stop.contains("waitForResidentProcessExit("))
        assertTrue(startup.contains("currentProtocolOnly = false"))
        assertTrue(stop.contains("currentProtocolOnly = currentProtocolOnly"))
        assertTrue(
            "sidecar 重复清理必须沿用探测到的主屏/虚拟屏角色",
            bridge.contains("terminateSpecificResidentSidecarProcesses(") &&
                bridge.contains("pids = duplicatePids")
        )
        assertFalse("进程退出必须以 ps 结果为准，不能依赖陈旧 Binder 代理", stop.contains("waitForBinderExit("))
        assertFalse(stop.contains("pgrep"))
        assertFalse(
            "旧协议 sidecar 即使在厂商系统上无法退出，也不能阻塞新代际启动",
            replacement.contains("invalid(")
        )
    }

    @Test
    fun transientPreviewFailureKeepsManualTakeoverPausedAndRetries() {
        val viewer = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt").readText()
        val failure = viewer.substringAfter("private suspend fun refreshFrame()")
            .substringBefore("private fun replayHandCue(")
        val touchDispatch = viewer.substringAfter("for (sample in touchSamples) {")
            .substringBefore("\n        }")

        assertTrue(failure.contains("FRAME_FAILURE_LIMIT"))
        assertTrue(failure.contains("showChannelError()"))
        assertFalse("瞬时掉帧不能擅自退出人工接管", failure.contains("resumeManualTakeover()"))
        assertFalse("触摸派发不能等待截图而阻塞下一次手势", touchDispatch.contains("refreshFrame()"))
    }

    @Test
    fun virtualSpaceUsesSidecarVirtualDisplayInsteadOfOverlayDisplay() {
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val sidecar = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()

        assertTrue(sidecar.contains("createVirtualDisplay"))
        assertTrue(sidecar.contains("ImageReader.newInstance"))
        assertFalse(manager.contains("createVirtualDisplay("))
        assertFalse(manager.contains("ImageReader.newInstance"))
        assertFalse(manager.contains("overlay_display_devices"))
    }

    @Test
    fun oneShellSidecarAllocatesOnlyVirtualDisplaysAfterLaunch() {
        val registry = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarBinderRegistry.kt").readText()
        val receiver = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarBinderReceiver.kt").readText()
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val main = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarMain.java").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(registry.contains("ConcurrentHashMap<Int, IBinder>"))
        assertTrue(registry.contains("fun get(uid: Int): IBinder?"))
        assertTrue(receiver.contains("EXTRA_SIDECAR_UID"))
        assertTrue(receiver.contains("EXTRA_SIDECAR_PROTOCOL_VERSION"))
        assertTrue(receiver.contains("protocolVersion != VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION"))
        assertTrue(receiver.indexOf("protocolVersion !=") < receiver.indexOf("SidecarBinderRegistry.set(uid, binder)"))
        assertTrue(receiver.contains("SidecarBinderRegistry.set(uid, binder)"))
        assertFalse(receiver.contains("service.uid"))
        assertTrue(manifest.contains("android:name=\"com.droidance.core.device.agent.device.SidecarBinderReceiver\""))
        assertTrue(manifest.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue(service.contains("intent.putExtra(SidecarBinderReceiver.EXTRA_SIDECAR_UID, Process.myUid())"))
        assertTrue(service.contains("SidecarBinderReceiver.EXTRA_SIDECAR_PROTOCOL_VERSION"))
        assertFalse(service.contains("EXTRA_SIDECAR_MAIN_SCREEN_ONLY"))
        assertTrue(service.contains("public static final int SIDECAR_PROTOCOL_VERSION = 73"))
        assertTrue(service.contains("intent.setPackage(targetPackage)"))
        assertFalse(service.contains("queryBroadcastReceivers"))

        assertFalse(main.contains("boolean mainScreenOnly"))
        assertTrue(main.contains("acquireProcessLease()"))
        assertTrue(main.contains("tryLock()"))
        assertTrue(main.contains("Process.myUid()"))
        assertFalse(main.contains("service.start(width, height, densityDpi)"))
        assertFalse(bridge.contains("buildAppProcessCommand(width, height, densityDpi, mainScreenOnly = true)"))
        assertTrue(bridge.contains("buildAppProcessCommand(width, height, densityDpi)"))
        assertTrue(bridge.contains("getRegisteredService(RuntimeMode.STANDARD)"))
        assertFalse(bridge.contains("allowMainScreenOnly"))
        assertFalse(bridge.contains("SidecarBinderRegistry.clear()"))
    }

    @Test
    fun sidecarRejectsRootIdentityAtEveryEntryPoint() {
        val main = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarMain.java").readText()
        val context = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarShellContext.java").readText()
        val receiver = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarBinderReceiver.kt").readText()
        assertTrue(main.contains("uid != SHELL_UID"))
        assertTrue(context.contains("uid != SHELL_UID"))
        assertFalse(main.contains("ROOT_UID"))
        assertFalse(context.contains("ROOT_UID"))
        assertTrue(receiver.contains("uid != SHELL_UID"))
    }

    @Test
    fun sidecarOperationFailuresDistinguishBinderOutageFromServiceRejection() {
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val call = bridge.substringAfter("private fun call(")
            .substringBefore("private fun serviceOrNull")

        assertTrue(bridge.contains("val channelUnavailable: Boolean = false"))
        assertTrue(call.contains("channelUnavailable = true"))
        assertTrue(call.contains("val service = serviceOrNull(mode)"))
        assertTrue(call.contains("?: return invalid(\"sidecar binder unavailable for ${'$'}op\", channelUnavailable = true)"))
        assertTrue(call.contains("onSuccess = { ok ->"))
        assertTrue(call.contains("CommandResult(ok = ok"))
    }

    @Test
    fun activeVirtualDisplayOperationsStayBoundToTheirCreatingMode() {
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val readiness = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceReadiness.kt").readText()
        val capture = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarScreenCapture.kt").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()

        assertTrue(manager.contains("fun isActiveForMode(mode: RuntimeMode): Boolean"))
        assertTrue(manager.contains("sidecarSession?.mode == mode"))
        assertTrue(manager.contains("fun hasLiveSessionForMode(mode: RuntimeMode): Boolean"))
        assertTrue(manager.contains("sidecarBridge.isBoundSessionAlive(binding)"))
        assertTrue(manager.contains("fun discardStaleSession("))
        assertTrue(manager.contains("val mode: RuntimeMode"))
        assertTrue(manager.contains("sidecarBridge.shutdown(session.mode)"))
        assertTrue(manager.contains("sidecarBridge.captureLatestPng(id, mode = session.mode)"))
        assertTrue(manager.contains("sidecarBridge.injectNative(binding, nativeArgs)"))
        assertTrue(manager.contains("sidecarBridge.injectTouch("))
        assertTrue(manager.contains("binding = binding"))
        assertTrue(readiness.contains("virtualSpaceManager.hasLiveSessionForMode(mode)"))
        assertFalse(capture.contains("mode = rootInputManager.executionMode"))
        assertTrue(bridge.contains("displayModes[displayId] = mode"))
        assertTrue(bridge.contains("val resolvedMode = mode ?: displayModes[displayId] ?: return null"))
        assertTrue(bridge.contains("serviceOrNull(resolvedMode)"))
    }

    @Test
    fun appRestartRebindsAnExistingResidentSidecarBeforeStartingAnotherProcess() {
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val ensureResident = bridge.substringAfter("fun ensureResidentChannel")
            .substringBefore("private fun awaitBinder")

        assertTrue(bridge.contains("private fun residentSidecarProcessExists("))
        assertTrue(ensureResident.contains("residentSidecarProcessExists("))
        assertTrue(ensureResident.contains("currentProtocolOnly = false"))
        assertFalse(ensureResident.contains("mainScreenOnly"))
        assertTrue(
                ensureResident.indexOf("residentSidecarProcessExists(") <
                ensureResident.indexOf("buildAppProcessCommand")
        )
    }

    @Test
    fun executionStartupReconcilesDuplicateResidentSidecarsBeforeBinding() {
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val ensureStarted = bridge.substringAfter("fun ensureStarted")
            .substringBefore("fun ensureResidentChannel")
        val ensureResident = bridge.substringAfter("fun ensureResidentChannel")
            .substringBefore("private fun awaitBinder")

        assertTrue(bridge.contains("private fun reconcileDuplicateResidentSidecars("))
        assertTrue(bridge.contains("if (pids.size <= 1) return true"))
        assertTrue(ensureStarted.contains("reconcileDuplicateResidentSidecars(selectedMode)"))
        assertTrue(ensureResident.contains("reconcileDuplicateResidentSidecars(selectedMode)"))
        assertTrue(bridge.contains("val canonicalPid = pids.maxOrNull()"))
        assertTrue(bridge.contains("terminateSpecificResidentSidecarProcesses("))
        assertFalse(bridge.contains("fun reconcileMainScreenSidecars(mode: RuntimeMode?): Boolean"))
        val readiness = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceReadiness.kt").readText()
        assertFalse(readiness.contains("reconcileMainScreenSidecars(mode)"))
    }

    @Test
    fun statusOverlayShowsVirtualSpaceIslandWithoutMirrorSurface() {
        val overlay = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val capture = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarScreenCapture.kt").readText()
        val input = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()

        val viewer = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt").readText()

        assertTrue(overlay.contains("agentRuntime.currentTaskTitle.value"))
        // 灵动岛点击进入同 display 的查看页（§5.4），不再把 App 拉主屏。
        assertTrue(overlay.contains("openVirtualSpaceViewer()"))
        assertFalse(overlay.contains("bringCurrentAppToMainDisplay()"))
        assertTrue(viewer.contains("virtualSpaceManager.captureLatestPreviewJpeg()"))
        assertFalse(overlay.contains("supportsSurfacePreview()"))
        assertFalse(overlay.contains("attachVisibleSurface"))
        assertFalse(overlay.contains("detachVisibleSurface"))
        assertFalse(overlay.contains("startVirtualSpaceScreenshotPreview"))
        assertFalse(overlay.contains("sidecarCapture.captureFrame(forceFresh = true, displayId = displayId)"))
        assertTrue(input.contains("sidecarBridge.injectNative(frozen, args)"))
        assertTrue(input.contains("sidecarBridge.injectKey(binding(), keycode)"))
        assertFalse(input.contains("input -d"))
    }

    @Test
    fun virtualTapAndSwipeRestoreTheLastDeviceVerifiedReflectionPathBeforeShellFallback() {
        val service = projectFile(
            "core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java"
        ).readText()
        val tap = service.substringAfter("public boolean tap(")
            .substringBefore("public boolean doubleTap(")
        val swipe = service.substringAfter("public boolean swipe(")
            .substringBefore("public boolean key(")

        assertTrue(
            tap.indexOf("injectTapSafely(targetDisplayId") in
                0 until tap.indexOf("runInputCommand(")
        )
        assertTrue(
            swipe.indexOf("injectSwipe(targetDisplayId") in
                0 until swipe.indexOf("runInputCommand(targetDisplayId, \"swipe\"")
        )
        val injectSwipe = service.substringAfter("private static void injectSwipe(")
            .substringBefore("private static void injectKey(")
        val inject = service.substringAfter("private static void injectInputEvent(InputEvent event)")
            .substringBefore("private static boolean injectInputEventOnce")
        val injectTap = service.substringAfter("private static void injectTap(")
            .substringBefore("private static void injectLongPress(")
        assertTrue(injectSwipe.contains("int steps = Math.max(4, Math.min(18, durationMs / 35))"))
        assertTrue(injectSwipe.contains("MotionEvent.obtain("))
        assertTrue(injectSwipe.contains("injectInputEvent(move)"))
        assertTrue(injectSwipe.contains("waitForInputTime(eventTime)"))
        assertTrue(injectSwipe.contains("waitForInputTime(upTime)"))
        assertFalse(injectSwipe.contains("durationMs / steps"))
        assertTrue(inject.contains("INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH"))
        assertFalse(inject.contains("INJECT_INPUT_EVENT_MODE_ASYNC"))
        assertTrue(injectTap.contains("SystemClock.sleep(Math.max(durationMs, 1))"))
        assertTrue(injectTap.indexOf("injectInputEvent(down)") < injectTap.indexOf("SystemClock.sleep(Math.max(durationMs, 1))"))
        assertTrue(injectTap.indexOf("SystemClock.sleep(Math.max(durationMs, 1))") < injectTap.indexOf("injectInputEvent(up)"))
    }

    @Test
    fun airBackgroundChannelRequiresShellUid() {
        val runner = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/PrivilegedShellRunner.kt").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val context = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarShellContext.java").readText()

        assertTrue(runner.contains("const val ADB_SHELL_UID = 2000"))
        assertTrue(runner.contains("fun isAdbShellUid()"))
        assertTrue(runner.contains("currentAdbShellUid() == ADB_SHELL_UID"))
        assertTrue(runner.contains("fun isAdbShellReady(): Boolean ="))
        assertTrue(runner.contains("airModePairingManager.isConnected && isAdbShellUid()"))
        assertTrue(runner.contains("required shell uid=${'$'}ADB_SHELL_UID"))
        assertFalse(runner.contains("rikka.shizuku"))
        assertTrue(bridge.contains("shellRunner.isAvailable(ShellIdentity.SHELL) ||"))
        assertTrue(bridge.contains("isRegisteredServiceAlive(RuntimeMode.STANDARD)"))
        assertTrue(bridge.contains("if (!shellRunner.isAvailable(ShellIdentity.SHELL))"))
        assertTrue(manager.contains("Standard sidecar unavailable"))
        assertTrue(context.contains("createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)"))
        assertTrue(context.contains("getBasePackageName()"))
        assertTrue(context.contains("getAttributionSource()"))
    }

    @Test
    fun pairedConnectionStartsOnlyTheVirtualResidentChannel() {
        val registry = projectFile("app/src/main/java/com/droidance/agent/RuntimeCapabilityRegistry.kt").readText()
        val aidl = projectFile("core/device/src/main/aidl/com/droidance/core/device/agent/device/IVirtualSpaceSidecar.aidl").readText()
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        assertTrue(registry.contains("airModePairingManager.state.collect"))
        assertTrue(registry.contains("virtualSpaceSidecarBridge.ensureResidentChannel("))
        assertFalse(aidl.contains("captureMainScreenPng"))
        assertFalse(service.contains("captureMainScreenWithScreencap"))
        assertTrue(service.contains("displayId > 0 && targetDisplayId == displayId"))
    }

    @Test
    fun appLaunchViaAmStartAlsoSurvivesWifiOffThroughResidentSidecar() {
        val aidl = projectFile("core/device/src/main/aidl/com/droidance/core/device/agent/device/IVirtualSpaceSidecar.aidl").readText()
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val appLaunch = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val backend = projectFile("app/src/main/java/com/droidance/agent/device/AndroidDeviceBackend.kt").readText()

        // 输入/截图 WiFi 无关只覆盖了点击/文字/截屏；开 App(am start) 之前仍然直接依赖实时 ADB
        // shell(adbShell.exec → airModePairingManager.shell)，WiFi 一断照样打不开新 App。sidecar
        // 本身也是 shell 身份进程，能在进程内 fork sh -c 本地跑 am start，不必依赖 ADB。
        assertTrue(aidl.contains("Bundle execShellCommand(String command, long timeoutMs, String executionToken);"))
        assertTrue(service.contains("public Bundle execShellCommand(String command, long timeoutMs, String executionToken)"))
        assertTrue(service.contains("shellCommands.execute(executionToken, command, timeoutMs)"))
        // 不能用进程内 startActivity（callingPackage 会被 AMS 拒绝）；必须真 fork 一个子进程。
        assertTrue(service.contains("callingPackage"))
        assertTrue(bridge.contains("fun execShellCommand("))
        assertFalse(bridge.contains("allowMainScreenOnly"))
        // VirtualSpaceSidecarBridge.runAmStart 优先本机执行，失败时只允许回退到所选模式的精确 shell。
        assertTrue(bridge.contains("RuntimeMode.STANDARD -> adbShell.exec(command, AM_START_TIMEOUT_MS)"))
        assertFalse(bridge.contains("execRootOnly"))
        // 统一 open 直达绑定任务开始时冻结的 sidecar，不允许跨 display/mode 回退。
        assertTrue(appLaunch.contains("deviceBackend.openIntentCommand(command)"))
        assertFalse(appLaunch.contains("virtualSpaceSidecarBridge"))
        assertFalse(appLaunch.contains("deviceShellCommandChannel"))
        assertTrue(backend.contains("SidecarSessionBinding(context.mode, context.displayId, context.backendSessionId)"))
        assertTrue(backend.contains("sidecarBridge.execShellCommand(binding, command, timeoutMs)"))
        assertTrue(appLaunch.contains("ExplicitIntentCommand.build("))
        assertTrue(projectFile("core/device/src/main/java/com/droidance/core/device/tools/ExplicitIntentCommand.kt")
            .readText().contains("am start --display ${'$'}displayId"))
    }

    @Test
    fun sidecarBinderCallsAreBoundedSoAHungSidecarCannotFreezeTheTask() {
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()

        // sidecar 是独立进程，AIDL 是同步阻塞的双向 Binder transact；真机曾观测到 sidecar 卡住导致
        // 调用方线程无限期挂起、任务卡死上百分钟只能手动取消。所有跨进程调用必须有界超时。
        assertTrue(bridge.contains("private fun <T> callWithTimeout("))
        assertTrue(bridge.contains("sidecarCallExecutor.submit<T>"))
        assertTrue(bridge.contains("future.get(timeoutMs, TimeUnit.MILLISECONDS)"))
        assertTrue(bridge.contains("future.cancel(true)"))
        assertTrue(bridge.contains("suspectService: IVirtualSpaceSidecar? = null"))
        assertTrue(bridge.contains("markSidecarSuspect(suspectService)"))
        assertTrue(bridge.contains("SidecarBinderRegistry.clear(binder)"))
        assertTrue(bridge.contains("isBinderTransportFailure(failure)"))
        // call()/captureLatestPng()/captureMainScreenPng()/startService()/shutdown() 都必须走超时包装。
        assertTrue(bridge.contains("val result = callWithTimeout(frameInspector.sidecarCallTimeoutMs(), suspectService = service) { action(service) }"))
        assertTrue(bridge.contains("timeoutMs = timeoutMs.coerceIn(1L, frameInspector.sidecarCaptureTimeoutMs())"))
        assertTrue(bridge.contains("callWithTimeout(SIDECAR_START_TIMEOUT_MS, suspectService = service)"))
    }

    @Test
    fun manualTakeoverPauseDefaultsToUserControlledWaitWithoutTerminatingTheTask() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        // 用户选择接管后，默认等待应由“恢复/取消”显式结束，不能因固定十分钟预算终止任务。
        val wait = runtime.substringAfter("private suspend fun waitIfPaused()").substringBefore("private fun manualTakeoverWaitTimeoutMs")
        assertTrue(policy.contains("manual_takeover_wait_timeout_ms: 0"))
        assertFalse(wait.contains("withTimeoutOrNull"))
        assertTrue(wait.contains("_isPaused.first { !it }"))
        assertFalse(wait.contains("manualTakeoverWaitTimedOut = true"))

    }

    @Test
    fun cancelledTaskStillGetsAChatSummaryWithoutParallelTodayLog() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val finalizerStart = runtime.lastIndexOf("_agentLoopState.value = AgentLoopState.COMPLETED")
        assertTrue(finalizerStart >= 0)
        val finalizer = runtime.substring(finalizerStart)
            .substringBefore("manualTakeoverWaitTimedOut = false")

        // 真因：任务被取消(用户点停止/等待接管超时)会以 CancellationException 中断 try 块，永远走不到
        // 取消总结保留在对话与 EventStore 中，不再同步写第二份 today-summary 生命周期事实。
        assertTrue(runtime.contains("status == ExecutionTerminalStatus.CANCELED || timedOutWaitingForTakeover"))
        assertTrue(runtime.contains("fun buildCancelledTaskSummary("))
        assertFalse(runtime.substringAfter("private suspend fun buildCancelledTaskSummary(").substringBefore("private suspend fun recentConversationContext").contains("_userInteractionRequest"))
        assertTrue(runtime.contains("conversationHistory.add(ChatMessage(role = Role.ASSISTANT, content = cancelSummary))"))
        assertTrue(runtime.contains("PublicAssistantText.sanitize(buildCancelledTaskSummary(timedOut))"))
        val lifecycle = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskLifecycle.kt").readText()
        assertTrue(lifecycle.contains("ledger.completeWithNotice(active.durableTurn, notice.status, notice.text"))
        assertTrue(lifecycle.contains("withContext(NonCancellable)"))
        assertFalse(runtime.contains("memoryManager.appendTodayLog("))
        val cancelSummaryIndex = finalizer.indexOf("buildCancelledTaskSummary(")
        val lastResponseIndex = finalizer.indexOf("_lastResponse.value = cancelSummary")
        val finalizeArtifactsIndex = finalizer.indexOf("finalizeTaskArtifacts(")
        assertTrue(cancelSummaryIndex >= 0)
        assertTrue(lastResponseIndex > cancelSummaryIndex)
        assertTrue(finalizeArtifactsIndex > lastResponseIndex)
        assertFalse(runtime.contains("TaskExperienceRecorder"))
    }

    @Test
    fun airReadyGateAcceptsResidentSidecarWithoutLiveAdbConnection() {
        val mode = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/device/DeviceMode.kt").readText()
        val registry = projectFile("app/src/main/java/com/droidance/agent/RuntimeCapabilityRegistry.kt").readText()

        // 真因：airReady/textObservationAvailable 之前只看 adbBridgeAvailable(实时ADB连接)，完全
        // 没考虑 virtualSpaceBridgeAvailable(含常驻sidecar binder存活判断)——WiFi关掉、实时连接断了
        // 之后，即便常驻sidecar还活着，顶层门禁也会判定"无可用通道"直接拒绝任务，让V3的常驻通道
        // 机制形同虚设(真机实测复现：WiFi关闭后新任务 execution.mode 变成空字符串)。
        assertTrue(mode.contains("get() = (adbBridgeAvailable || virtualSpaceBridgeAvailable) && textObservationAvailable"))
        assertTrue(registry.contains("textObservationAvailable = screenCaptureAvailable || adbBridgeAvailable || virtualSpaceBridgeAvailable"))
    }

    @Test
    fun virtualSpaceActionsDriveAHandInsideTheAppPreviewInsteadOfTheVirtualDisplay() {
        val overlay = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val controller = projectFile("app/src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val viewer = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt").readText()
        val mapper = projectFile("app/src/main/java/com/droidance/ui/overlay/VirtualDisplayTouchMapper.kt").readText()
        val collectorBlock = overlay.substringAfter("inputActionLifecycle.events.collect { event ->")
            .substringBefore("\n            }")
        val showAction = controller.substringAfter("private fun dispatchHandCue(")
            .substringBefore("fun finishAction(")

        // 小手仍然只跟随真实输入生命周期，并带着事件所属 display；不恢复 App 悬浮头像。
        assertFalse(collectorBlock.contains("if (event.displayId != 0) return@post"))
        assertTrue(collectorBlock.contains("runtimeSurfaceController.showAction(event)"))
        assertTrue(collectorBlock.contains("runtimeSurfaceController.finishAction(event)"))
        assertFalse(collectorBlock.contains("showExecutionAvatarOverlay("))
        assertFalse(collectorBlock.contains("if (virtualSpaceManager.isActive())"))
        assertTrue(collectorBlock.contains("InputActionLifecycleEvent.Finished"))

        // 非主屏动作一律走 App 预览层：虚拟屏上不能再合成 Droidance 自己的小手图层。
        // 合成到虚拟屏会进入模型帧、把无效操作伪装成 raw_changed 掩盖"操作没生效"，
        // 且为避开系统遮挡判定必须在每次注入前销毁图层，导致动作进行时根本看不到小手。
        assertTrue(showAction.contains("if (cue.displayId != Display.DEFAULT_DISPLAY)"))
        assertTrue(showAction.contains("_virtualSpaceHandCue.value = cue"))
        assertTrue(controller.contains("val virtualSpaceHandCue: StateFlow<VirtualSpaceHandCue?>"))
        assertTrue(mapper.contains("fun mapDisplayToView("))
        assertTrue(mapper.contains("fun mapActionToView("))
        // App 侧镜像（查看页）要在预览之上重放小手。
        assertTrue(viewer.contains("handView = TouchIndicatorView("))
        assertTrue(viewer.contains("runtimeSurfaceController.virtualSpaceHandCue.collect(::replayHandCue)"))
        assertTrue(viewer.contains("VirtualDisplayTouchMapper.mapActionToView("))
    }

    @Test
    fun virtualDisplayInjectionRecordsTheRealChannelSoSilentFallbackIsVisible() {
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val input = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()
        assertTrue(service.contains("INPUT_PATH_REFLECTION"))
        assertTrue(service.contains("INPUT_PATH_INPUT_COMMAND"))
        assertTrue(service.contains("private void noteInputEvidence("))
        assertTrue(input.contains("diagnostics.recordInjection("))
    }

    @Test
    fun runtimeIslandUsesASidecarSizedInputChildWithoutAnOverlayWindow() {
        val aidl = projectFile("core/device/src/main/aidl/com/droidance/core/device/agent/device/IVirtualSpaceSidecar.aidl").readText()
        val callbackAidl = projectFile("core/device/src/main/aidl/com/droidance/core/device/agent/device/IRuntimeIndicatorCallback.aidl").readText()
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val surface = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarRuntimeSurface.java").readText()
        val controller = projectFile("app/src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val overlay = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val visibility = overlay.substringAfter("private fun updateRuntimeOverlayVisibility() {")
            .substringBefore("\n    private fun isRuntimeActive()")

        assertTrue(aidl.contains("boolean updateRuntimeIsland("))
        assertTrue(aidl.contains("boolean updateRuntimeCursor("))
        assertTrue(aidl.contains("boolean updateRuntimeIslandBitmap("))
        assertTrue(aidl.contains("boolean updateRuntimeCursorBitmap("))
        assertTrue(aidl.contains("boolean updateRuntimeCursorBitmapOnDisplay("))
        assertTrue(
            aidl.indexOf("boolean updateRuntimeCursorBitmapOnDisplay(") >
                aidl.indexOf("SurfaceControl getRuntimeIslandInputSurface();")
        )
        assertTrue(aidl.contains("SurfaceControl getRuntimeIslandInputSurface();"))
        assertTrue(aidl.contains("boolean setRuntimeSurfaceSuppressed("))
        assertTrue(aidl.contains("void setRuntimeIndicatorCallback("))
        assertTrue(callbackAidl.contains("void onRuntimeIndicatorTapped();"))
        assertTrue(callbackAidl.contains("void onRuntimeStopRequested();"))
        assertTrue(service.contains("SidecarRuntimeSurface"))
        assertTrue(surface.contains("SurfaceControl.Builder"))
        assertTrue(surface.contains("SurfaceControl.Transaction"))
        assertTrue(surface.contains("setCrop(transaction, control, new Rect(0, 0, width, height))"))
        assertTrue(surface.contains(".setParent(control)"))
        assertTrue(surface.contains(".setBufferSize(width, height)"))
        assertTrue(surface.contains("clearSurface(inputSurface)"))
        assertTrue(surface.contains("setCrop(transaction, inputControl, new Rect(0, 0, width, height))"))
        assertTrue(surface.contains("new Surface(control)"))
        assertFalse(surface.contains("WindowManager.LayoutParams"))
        assertFalse(surface.contains("TYPE_APPLICATION_OVERLAY"))
        assertFalse(surface.contains("createWindowContext("))
        assertTrue(surface.contains("Display.DEFAULT_DISPLAY"))
        assertTrue(surface.contains("displayManager.getDisplay(targetDisplayId)"))
        assertTrue(surface.contains("resolveDisplayLayerStack(targetDisplay)"))
        assertTrue(surface.contains("cursorDisplayLayerStack"))
        assertTrue(surface.contains("copyIndicatorInputSurface()"))
        assertTrue(surface.contains("inputDispatchDepth"))
        assertTrue(surface.contains("beginInputDispatch()"))
        assertTrue(surface.contains("endInputDispatch()"))
        val beginDispatch = surface.substringAfter("synchronized void beginInputDispatch()")
            .substringBefore("synchronized void endInputDispatch()")
        assertFalse("真实手势期间小手必须保持可见", beginDispatch.contains("removeCursor();"))
        assertTrue("显示层必须避免触摸遮挡", surface.contains("markTrustedOverlay(transaction, control)"))
        assertTrue(service.contains("beginRuntimeInputDispatch()"))
        assertTrue(service.contains("endRuntimeInputDispatch()"))
        assertFalse(surface.contains("Typeface"))
        assertFalse(surface.contains("TextPaint"))
        assertFalse(surface.contains("drawText("))
        assertFalse(surface.contains("measureText("))
        assertFalse(surface.contains("getFontMetrics("))
        assertFalse(surface.contains("ValueAnimator"))
        assertFalse(surface.contains("Choreographer"))
        assertTrue(surface.contains("mainHandler.postDelayed("))
        assertTrue(surface.contains("handPainter.draw(canvas, pointer"))
        assertFalse(surface.contains("Math.cos"))
        assertFalse(surface.contains("scheduleTrailFade"))
        assertTrue(surface.contains("CursorMotion.sample(CursorMotion.nowMs(), startedAtMs"))
        assertTrue(surface.contains("frame.alpha <= 0f"))
        assertTrue(controller.contains("bridge.runtimeSurfaceBinding("))
        assertTrue(bridge.contains("return SidecarSessionBinding("))
        assertTrue(controller.contains("bridge.updateRuntimeIslandBitmap("))
        assertTrue(controller.contains("bridge.updateRuntimeCursorBitmapOnDisplay("))
        assertTrue(controller.contains("targetDisplayId = command.targetDisplayId"))
        assertTrue(controller.contains("bridge.runtimeIslandSurface("))
        assertTrue(controller.contains("SurfaceControlViewHost(appContext, display"))
        assertTrue(controller.contains("inputHost?.release()"))
        assertTrue(controller.contains("InputTransferToken"))
        assertTrue(controller.contains("setCrop(pkg.surfaceControl, Rect(0, 0, widthPx, heightPx))"))
        assertTrue(controller.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertTrue(bridge.contains("fun runtimeIslandInputSurface("))
        assertTrue(controller.contains("setOnStopClick"))
        assertTrue(visibility.contains("runtimeSurfaceController.showIndicator("))
        assertFalse(visibility.contains("showVirtualSpacePreview()"))
        assertFalse(visibility.contains("showExecutionAvatarOverlay()"))
        assertTrue(bridge.contains("service.lastError.ifBlank"))
        val suppression = surface.substringAfter("synchronized boolean setSuppressed(boolean suppressed)")
            .substringBefore("\n    synchronized void destroy()")
        assertTrue(suppression.contains("hideCursor();"))
        assertTrue(suppression.substringAfter("if (suppressed)").contains("applyCursor()"))
        val finishAction = controller.substringAfter("fun finishAction(actionId: String)")
            .substringBefore("\n    fun hide(")
        assertFalse(finishAction.contains("hiddenCursor("))
    }

    @Test
    fun runtimeSurfaceFailurePreservesTheDeepestSurfaceCause() {
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val surface = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarRuntimeSurface.java").readText()

        assertTrue(surface.contains("ExecutionException"))
        assertTrue(surface.contains("task.get("))
        assertTrue(service.contains("deepestCauseMessage(t)"))
        assertTrue(service.contains("while (cause.getCause() != null && cause.getCause() != cause)"))
    }

    @Test
    fun airTextInputWritesReadsBackAndVerifiesBeforePaste() {
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val shellContext = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarShellContext.java").readText()
        val verifier = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/ClipboardTextVerifier.java").readText()

        val inject = service.substringAfter("private Bundle injectTextOnDisplay")
            .substringBefore("private ClipboardManager shellClipboardManager")

        val writeIdx = inject.indexOf("writeAndReadBackClipboard(clipboard, target)")
        val matchIdx = inject.indexOf("ClipboardTextVerifier.matches(target, readback)")
        val pasteIdx = inject.indexOf("KeyEvent.KEYCODE_V")
        // paste 必须严格发生在写入→回读→精确比较之后。
        assertTrue("write-then-readback must precede the exact-match check", writeIdx in 0 until matchIdx)
        assertTrue("paste must only run after the exact match", matchIdx in 0 until pasteIdx)

        // 目标系统没有 `cmd clipboard`；真实 shell 身份的 ClipboardManager 是唯一写入路径。
        assertFalse(inject.contains("setClipboardViaShell(target)"))
        assertFalse(service.contains("ProcessBuilder(\"/system/bin/cmd\", \"clipboard\""))

        // 回读不一致必须返回失败证据、绝不粘贴；失败原因区分 write_failed / readback_mismatch / paste_failed。
        assertTrue(inject.contains("emptyTextEvidence()"))
        assertTrue(inject.contains("evidence.putBoolean(\"payloadConfirmed\", true)"))
        assertTrue(inject.contains("evidence.putBoolean(\"eventInjected\", true)"))
        assertTrue(inject.contains("write_failed"))
        assertTrue(inject.contains("readback_mismatch"))
        assertTrue(inject.contains("paste_failed"))
        assertTrue(inject.contains("OriginalClipboard original = readOriginalClipboard(clipboard)"))
        assertTrue(inject.contains("restoreClipboardIfOwned(clipboard, target, original)"))
        assertTrue(service.contains("CLIP_LABEL.contentEquals(label)"))

        // sidecar 只记录长度与哈希，不拼接完整剪贴板明文。
        assertTrue(inject.contains("ClipboardTextVerifier.fingerprint(readback)"))
        assertFalse("must not concatenate raw clipboard text into logs", inject.contains("+ target +"))
        assertFalse("must not concatenate raw clipboard text into logs", inject.contains("+ readback +"))

        // 剪贴板读写走 shell 身份 ClipboardManager（SidecarShellContext.getSystemService 补丁 mContext）。
        assertTrue(service.contains("resolveContext().getSystemService(Context.CLIPBOARD_SERVICE)"))

        // SidecarShellContext 补全 shell 身份字段。
        assertTrue(shellContext.contains("com.android.shell"))
        assertTrue(shellContext.contains("getOpPackageName"))
        assertTrue(shellContext.contains("AttributionSource.Builder"))
        assertTrue(shellContext.contains("SHELL_UID") || shellContext.contains("2000"))
        assertTrue(shellContext.contains("public int getDeviceId()"))
        assertTrue(shellContext.contains("public Context createPackageContext("))
        assertTrue(shellContext.contains("public Object getSystemService(String name)"))
        assertTrue(shellContext.contains("mContext"))

        // ClipboardTextVerifier 提供精确比较与非敏感指纹。
        assertTrue(verifier.contains("static boolean matches("))
        assertTrue(verifier.contains("target.equals(readback)"))
        assertTrue(verifier.contains("static String fingerprint("))
    }

    @Test
    fun clipboardFactReadUsesTheFrozenSidecarWithoutCopyingOrReinterpretingText() {
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        val read = service.substringAfter("public Bundle readClipboard()")
            .substringBefore("public void cancelShellCommand")
        assertTrue(Regex("clipboard\\.getPrimaryClip\\(\\)").findAll(read).count() == 1)
        assertTrue(read.contains("item.getText()"))
        assertTrue(read.contains("text == null ? null : text.toString()"))
        assertTrue(read.contains("clip.getDescription().getTimestamp()"))
        assertTrue(read.contains("\"reader_uid\""))
        assertTrue(read.contains("\"reader_user_id\""))
        assertTrue(read.contains("\"clipboard_requested_device_id\""))
        assertTrue(read.contains("CLIPBOARD_NO_READABLE_CLIP"))
        assertTrue(read.contains("CLIPBOARD_READ_DENIED"))
        listOf("setPrimaryClip", "hasPrimaryClip", "clearPrimaryClip", "coerceToText", "readPrimaryClip(",
            "dispatchClipboardKey", "trim(", "normalize(", "replace(").forEach { assertFalse(read.contains(it)) }

        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val boundRead = bridge.substringAfter("fun readClipboard(binding: SidecarSessionBinding)")
            .substringBefore("private fun decodeShellResult")
        assertTrue(boundRead.contains("callBoundValue(binding)"))
        assertTrue(boundRead.contains("service.readClipboard()"))
        assertTrue(boundRead.contains("if (error is CancellationException) throw error"))
        assertTrue(boundRead.contains("\"execution_display_id\", JsonPrimitive(binding.displayId)"))
        assertTrue(boundRead.contains("\"scope\", JsonPrimitive(\"reader_user_clipboard\")"))
        assertTrue(boundRead.contains("CLIPBOARD_READ_DELIVERY_UNKNOWN"))
        assertFalse(boundRead.contains("execShellCommand"))

        val backend = projectFile("app/src/main/java/com/droidance/agent/device/AndroidDeviceBackend.kt").readText()
        val backendRead = backend.substringAfter("override suspend fun readClipboard()")
            .substringBefore("override suspend fun shell")
        assertTrue(backendRead.contains("frozenSidecarBinding(executionContextStore.requireCurrent())"))
        assertTrue(backendRead.contains("sidecarBridge.readClipboard(binding)"))
        assertFalse(backendRead.contains("getSystemService"))
    }

    @Test
    fun clipboardInputUsesShellIdentityWithoutRootHelpers() {
        val service = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java").readText()
        assertTrue(service.contains("shellClipboardManager()"))
        assertTrue(service.contains("writeAndReadBackClipboard(clipboard, target)"))
        assertTrue(service.contains("ClipboardTextVerifier.matches(target, readback)"))
        assertFalse(service.contains("/system/bin/su"))
        assertFalse(service.contains("CLIPBOARD_HELPER_CLASS"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
