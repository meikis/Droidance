// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusOverlaySourceContractTest {
    /** 主界面隐藏运行岛；查看页使用自己的控制岛。前台由 Activity 生命周期提供。 */
    @Test
    fun runtime_surface_visibility_uses_host_and_viewer_state() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val functionBody = source.substringAfter("private fun updateRuntimeOverlayVisibility() {")
            .substringBefore("\n    private fun showRuntimeSurfaceIndicator()")
        val gateBody = source.substringAfter("private fun shouldShowExternalTaskOverlay()")
            .substringBefore("\n    private fun runtimeIslandActionKind()")
        val externalHideBranch = functionBody.substringAfter("if (!shouldShowExternalTaskOverlay()) {")
            .substringBefore("return")

        assertTrue(gateBody.contains("agentRuntime.isMobileUseTaskActive.value"))
        assertTrue(gateBody.contains("foregroundState.isInForeground"))
        assertTrue(gateBody.contains("RuntimeOverlayVisibility.shouldShow("))
        assertFalse(gateBody.contains("ExecutionSpace.VIRTUAL_SPACE"))
        assertTrue(gateBody.contains("runtimeSurfaceController.isViewerExpanded"))
        assertTrue(source.contains("foregroundState.foreground.collect"))
        assertFalse("前台判定不得回到 dumpsys", source.contains("dumpsys activity activities"))
        assertTrue(functionBody.contains("if (!shouldShowExternalTaskOverlay())"))
        assertTrue(functionBody.contains("showRuntimeSurfaceIndicator()"))
        assertTrue(externalHideBranch.contains("runtimeSurfaceController.hide("))
    }

    /** 查看页自成一个 task：关掉它应该回到用户点岛之前的画面，而不是掉进 Droidance 主界面。 */
    @Test
    fun virtual_space_viewer_closes_back_to_where_the_user_came_from() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val viewer = manifest.substringAfter(".agent.device.VirtualSpaceViewerActivity")
            .substringBefore("/>")

        assertTrue(viewer.contains("android:taskAffinity="))
        assertTrue(viewer.contains("android:excludeFromRecents=\"true\""))
    }

    /** 运行指示只能来自 shell sidecar 的 SurfaceControl，App 侧不得再创建任何悬浮窗。 */
    @Test
    fun runtime_indicators_never_use_app_overlay_windows() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val visibilityBody = source.substringAfter("private fun updateRuntimeOverlayVisibility() {")
            .substringBefore("\n    private fun showRuntimeSurfaceIndicator()")

        assertTrue(visibilityBody.contains("showRuntimeSurfaceIndicator()"))
        assertFalse("不得申请或检查悬浮窗权限", source.contains("Settings.canDrawOverlays"))
        assertFalse("不得创建需要悬浮窗权限的 Window", source.contains("TYPE_APPLICATION_OVERLAY"))
        assertFalse(source.contains("WindowManager.LayoutParams"))
        assertFalse(source.contains("wm.addView"))
        assertFalse("整个 App 不得再声明悬浮窗权限", File("src/main/AndroidManifest.xml").readText().contains("SYSTEM_ALERT_WINDOW"))
    }

    /** 任务期小手由 shell sidecar 按真实输入生命周期与目标 display 精确驱动。 */
    @Test
    fun execution_avatar_is_action_scoped_and_finishes_its_own_animation() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val controller = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val surface = File("../core/device/src/main/java/com/droidance/core/device/agent/device/SidecarRuntimeSurface.java").readText()
        val visibilityBody = source.substringAfter("private fun updateRuntimeOverlayVisibility() {")
            .substringBefore("\n    private fun showRuntimeSurfaceIndicator()")
        val collectorBlock = source.substringAfter("inputActionLifecycle.events.collect { event ->")
            .substringBefore("\n            }")
        val captureHookBody = source.substringAfter("sidecarCapture.beforeCaptureHook = { displayId ->")
            .substringBefore("\n        sidecarCapture.afterCaptureHook")

        assertFalse("不得再空闲隐藏小手", source.contains("scheduleExecutionAvatarIdleHide()"))
        assertTrue(surface.contains("new SurfaceControl.Builder()"))
        assertTrue(surface.contains("new Surface(control)"))
        assertTrue(surface.contains("handPainter.draw(canvas, pointer"))
        assertTrue(controller.contains("bitmapRenderer.pointerBitmapPng()"))
        // 小手使用原来的 PNG 素材；主屏位图与虚拟屏预览共用 HandCursorBitmap 的同一张图。
        assertTrue(File("src/main/java/com/droidance/ui/overlay/HandCursorBitmap.kt").readText().contains("robot_hand_pointer.png"))
        assertTrue(File("src/main/java/com/droidance/ui/overlay/TouchIndicatorView.kt").readText().contains("HandCursorBitmap.of("))
        assertTrue(
            File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceBitmapRenderer.kt")
                .readText()
                .contains("HandCursorBitmap.of(context")
        )
        assertFalse(controller.contains("private var cursorState: CursorState?"))
        assertFalse(controller.contains("cursorState?.let"))
        assertTrue(visibilityBody.contains("showRuntimeSurfaceIndicator()"))
        assertTrue(collectorBlock.contains("is InputActionLifecycleEvent.Started"))
        assertTrue(collectorBlock.contains("runtimeSurfaceController.showAction(event)"))
        assertTrue(collectorBlock.contains("runtimeSurfaceController.finishAction(event)"))
        val finishActionBody = controller.substringAfter("fun finishAction(event: InputActionLifecycleEvent.Finished)")
            .substringBefore("\n    fun hide(")
        assertFalse("输入结束不能抢先隐藏仍在播放的小手动画", finishActionBody.contains("hiddenCursor"))
        assertTrue(surface.contains("CursorMotion.sample("))
        assertTrue(surface.contains("mainHandler.postDelayed(() -> animateCursorFrame(token), delayMs)"))
        assertTrue(surface.contains("frame.alpha <= 0f"))
        assertFalse("不得按旧固定延时重放或隐藏触点", collectorBlock.contains("scheduleGestureVisualsAutoHide(event)"))
        assertFalse("截图分析不能为隐藏悬浮窗额外等待", captureHookBody.contains("delay(360L)"))
    }

    @Test
    fun tool_arguments_stay_out_of_the_island() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val runtime = File("src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val executor = com.droidance.agent.executorSources()

        assertFalse(source.contains("agentRuntime.currentActionDetail.collect"))
        assertTrue(runtime.contains("val currentActionDetail: StateFlow<String>"))
        assertTrue(runtime.contains("fun updateCurrentToolDetail(toolName: String, arguments: String)"))
        assertTrue(executor.contains("turnBinding.progress.updateCurrentToolDetail(toolCall.name, toolCall.arguments)"))
    }

    @Test
    fun actions_from_both_execution_spaces_drive_only_their_target_display_cursor() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val controller = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val surface = File("../core/device/src/main/java/com/droidance/core/device/agent/device/SidecarRuntimeSurface.java").readText()
        val collectorBlock = source.substringAfter("inputActionLifecycle.events.collect { event ->")
            .substringBefore("\n            }")
        val bindingBody = controller.substringAfter("private fun currentBinding()")
            .substringBefore("private fun hiddenCursor(")

        assertFalse(collectorBlock.contains("if (event.displayId != 0) return@post"))
        assertTrue(collectorBlock.contains("runtimeSurfaceController.showAction(event)"))
        assertFalse(collectorBlock.contains("if (virtualSpaceManager.isActive())"))
        assertFalse(source.contains("private fun inputActionForScreen("))
        assertTrue(bindingBody.contains("bridge.runtimeSurfaceBinding("))
        assertFalse(
            "主屏直接执行会话 ID 不能冒充 sidecar 显示会话 ID",
            bindingBody.contains("backendSessionId = current.backendSessionId")
        )
        assertTrue(surface.contains("displayManager.getDisplay(targetDisplayId)"))
        assertTrue(surface.contains("resolveDisplayLayerStack(targetDisplay)"))
        assertTrue(surface.contains("\"Droidance Runtime Hand\""))
    }

    @Test
    fun runtime_controls_use_one_compact_bottom_island() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val runtimeVisibility = source.substringAfter("private fun updateRuntimeOverlayVisibility() {")
            .substringBefore("private fun showRuntimeSurfaceIndicator()")
        val island = File("../core/device/src/main/java/com/droidance/core/device/agent/device/SidecarRuntimeSurface.java").readText()
        val renderer = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceBitmapRenderer.kt").readText()
        val controller = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()

        assertTrue(runtimeVisibility.contains("showRuntimeSurfaceIndicator()"))
        assertTrue("岛高走统一令牌", renderer.contains("dp(RuntimeIslandStyle.HEIGHT_DP)"))
        assertTrue(renderer.contains("RuntimeIslandPosition.defaultTopPx(screenHeightPx(), layout.heightPx)"))
        assertFalse(renderer.contains("cameraCutoutTopOffsetPx("))
        assertTrue(island.contains("clearSurface(inputSurface)"))
        assertTrue(island.contains(".setParent(control)"))
        assertTrue(controller.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertFalse(island.contains("TYPE_APPLICATION_OVERLAY"))
        assertFalse(island.contains("Typeface"))
        assertFalse(island.contains("TextPaint"))
        assertFalse(island.contains("drawText("))
        assertFalse(island.contains("measureText("))
        assertFalse(island.contains("ValueAnimator"))
        assertFalse(island.contains("Choreographer"))
        assertTrue(island.contains("mainHandler.postDelayed("))
        assertTrue(controller.contains("SurfaceControlViewHost(appContext, display"))
        assertTrue(controller.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertTrue("岛体走统一令牌的近黑胶囊", renderer.contains("RuntimeIslandStyle.fillColor()"))
        assertTrue("岛内按钮退成中性胶囊，不再是蓝色实心块", renderer.contains("RuntimeIslandStyle.chipFill(paused)"))
        // 小手回到 PNG 素材，两处共用 HandCursorBitmap 的同一张图。
        assertTrue("小手用原来的 PNG 素材", renderer.contains("HandCursorBitmap.of(context"))
    }

    @Test
    fun runtime_island_integrates_the_transparent_mascot_without_a_separate_tile() {
        val renderer = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceBitmapRenderer.kt").readText()
        val style = File("src/main/java/com/droidance/ui/overlay/RuntimeIslandStyle.kt").readText()
        val painter = File("src/main/java/com/droidance/ui/mascot/MascotPainter.kt").readText()
        listOf("droidance_mascot_head", "droidance_mascot_body").forEach { name ->
            assertTrue(painter.contains("R.drawable.$name"))
            val sprite = File("src/main/res/drawable-nodpi/$name.png")
            assertTrue(sprite.isFile && sprite.length() > 0L)
        }

        assertTrue(renderer.contains("MascotPainter(context)"))
        assertTrue(renderer.contains("private fun drawMascot("))
        assertTrue(renderer.contains("MascotMotion.loopingDancePose("))
        assertTrue(renderer.contains("MascotFraming.FULL"))
        assertFalse("主屏角色舱不再绘制旧的状态流光底色", renderer.contains("RuntimeIslandFlowLight.draw("))
        assertTrue(style.contains("MASCOT_SIZE_DP = 20f"))
        assertFalse(style.contains("drawMascotStage"))
        assertTrue(style.contains("LABEL_INSET_DP = MASCOT_LEFT_DP + MASCOT_SIZE_DP"))
    }

    @Test
    fun runtime_island_space_toggle_arrows_hug_the_mascot() {
        val style = File("src/main/java/com/droidance/ui/overlay/RuntimeIslandStyle.kt").readText()
        assertTrue(style.contains("const val MASCOT_LEFT_DP = 8f"))
        assertTrue(style.contains("const val SPACE_TOGGLE_ARROW_OFFSET_DP = 12f"))
        assertTrue(style.contains("SPACE_TOGGLE_ARROW_OFFSET_DP * density"))
    }

    @Test
    fun runtime_island_preserves_task_titles_and_localizes_handoff_states() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val presentation = File("src/main/java/com/droidance/ui/overlay/RuntimeIslandPresentation.kt").readText()
        assertTrue(source.contains("RuntimeIslandPresentation.label(context, runtimeIslandState(), agentRuntime.currentTaskTitle.value)"))
        assertFalse(presentation.contains("executionActionResourceOrNull"))
        assertTrue(presentation.contains("R.string.runtime_island_takeover_requested"))
        assertTrue(presentation.contains("R.string.runtime_island_user_control"))
        assertTrue(presentation.contains("R.string.runtime_island_permission"))
    }

    @Test
    fun runtime_island_input_is_restricted_to_a_fixed_size_child_surface() {
        val app = File("src/main/java/com/droidance/DroidanceApp.kt").readText()
        val surface = File("../core/device/src/main/java/com/droidance/core/device/agent/device/SidecarRuntimeSurface.java").readText()
        val controller = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val executability = controller.substringAfter("private fun isExecutable(command: Command)")
            .substringBefore("private companion object")

        assertFalse("任务期不得再显示会参与触摸遮挡判定的全屏 App 悬浮层", app.contains("taskGlowOverlay.show()"))
        assertTrue("输入层必须是灵动岛视觉层的子图层", surface.contains(".setParent(control)"))
        assertTrue("输入层必须有与灵动岛相同的真实缓冲边界", surface.contains(".setBufferSize(width, height)"))
        assertTrue("输入缓冲必须透明，不得遮挡当前 App", surface.contains("clearSurface(inputSurface)"))
        assertTrue("输入层必须显式裁剪为灵动岛尺寸", surface.contains("setCrop(inputControl, new Rect(0, 0, width, height))"))
        assertFalse("不得创建需要悬浮窗权限的 Window", surface.contains("TYPE_APPLICATION_OVERLAY"))
        assertTrue("Input follows the island surface", controller.contains("it.reparent(pkg.surfaceControl, surface)"))
        assertTrue(controller.contains("SurfaceControlViewHost(appContext, display"))
        assertTrue(controller.contains("host.setView(view, widthPx, heightPx)"))
        assertTrue(controller.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL"))
        assertTrue(
            "尺寸一变 sidecar 就重建图层，必须重注册输入面，否则接管/停止会静默失灵",
            surface.contains("indicatorLayer.width != width || indicatorLayer.height != height")
        )
        assertTrue("旧执行空间命令必须在进入 sidecar 前丢弃", executability.contains("if (command.binding != current) return false"))
    }

    /** 主屏与虚拟空间复用同一个系统运行岛，岛上的接管按钮进入对应空间的接管路径。 */
    @Test
    fun virtual_space_island_auto_shows_and_taps_to_open_running_app() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val surface = File("../core/device/src/main/java/com/droidance/core/device/agent/device/SidecarRuntimeSurface.java").readText()
        val renderer = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceBitmapRenderer.kt").readText()

        assertTrue("岛高定死在统一令牌上", renderer.contains("dp(RuntimeIslandStyle.HEIGHT_DP)"))
        assertTrue("灵动岛输入面是视觉层下的定尺寸子图层", surface.contains(".setParent(control)"))
        val controller = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        assertTrue(controller.contains("it.reparent(pkg.surfaceControl, surface)"))
        assertTrue(controller.contains("SurfaceControlViewHost(appContext, display"))
        assertTrue(surface.contains("SurfaceControl.Builder"))
        assertFalse(surface.contains("TYPE_SYSTEM_ERROR"))
        assertFalse(surface.contains("TYPE_APPLICATION_OVERLAY"))
        val actionBody = source.substringAfter("private fun handleRuntimeIslandAction()")
            .substringBefore("private fun handleRuntimeIslandStop()")
        assertTrue(actionBody.contains("openVirtualSpaceViewer()"))
        assertTrue(source.contains("runtimeSurfaceController.setOnStopClick"))
        assertTrue(source.contains("agentRuntime.stopCurrentMobileUse(origin = \"runtime_island_stop\")"))
        assertFalse("接管不再迁移主屏", surface.contains("bringCurrentAppToMainDisplay()"))
        // 不再镜像屏幕：消除黑帧/丑窗口与"退不出只能重启"的全屏陷阱
        assertFalse("灵动岛不镜像屏幕", surface.contains("supportsSurfacePreview()"))
        assertFalse("灵动岛不附着虚拟屏 Surface", surface.contains("attachVisibleSurface"))
        assertFalse("灵动岛不铺满整屏", surface.contains("PixelFormat.OPAQUE"))
    }

    @Test
    fun unifiedRuntimeIslandShowsTaskTitleTakeoverAndStopForBothExecutionSpaces() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val renderer = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceBitmapRenderer.kt").readText()
        val controller = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val showBody = source.substringAfter("private fun showRuntimeSurfaceIndicator()")
            .substringBefore("private fun runtimeIslandMood()")
        val actionBody = source.substringAfter("private fun handleRuntimeIslandAction()")
            .substringBefore("private fun handleRuntimeIslandStop()")

        assertFalse(showBody.contains("progressText"))
        assertTrue(showBody.contains("currentActionText = runtimeTaskTitleText()"))
        // 岛内按钮改成图标后不再传文案；语义由 IslandAction 表达，分支必须与文案版一一对应。
        assertTrue(showBody.contains("action = runtimeIslandActionKind()"))
        assertTrue(source.contains("IslandAction.OPEN"))
        assertTrue(actionBody.contains("openVirtualSpaceViewer()"))
        // 停止仍必须在岛上有独立可点区域，只是从文案胶囊换成 iOS 那种圆角方块图标。
        assertTrue(renderer.contains("private fun drawStopGlyph("))
        assertTrue(renderer.contains("private fun drawIconChip("))
        assertTrue(renderer.contains("stopButtonLeftPx = stopLeft + padding"))
        assertTrue(controller.contains("event.x >= inputStopButtonLeftPx"))
        assertTrue(controller.contains("stopClickListener?.invoke()"))
        assertFalse(showBody.contains("canDraw()"))
    }

    /** 虚拟空间的观察/接管只走普通 Activity 查看页，不再有浮窗镜像面板。 */
    @Test
    fun virtual_space_takeover_goes_through_the_viewer_activity() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val viewer = File("src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt").readText()
        val launcher = File(
            "src/main/java/com/droidance/agent/device/VirtualSpaceViewerLauncher.kt"
        ).readText()
        val openBody = source.substringAfter("private fun openVirtualSpaceViewer() {")
            .substringBefore("\n    private fun showVirtualSpaceViewerOpenFailed()")

        assertFalse(source.contains("showInteractiveTakeoverPanel()"))
        assertFalse(source.contains("captureLatestPreviewJpeg()"))
        assertFalse(source.contains("takeoverTouchChannel"))
        assertTrue(openBody.contains("virtualSpaceViewerLauncher.open()"))
        assertTrue(launcher.contains("VirtualSpaceViewerActivity::class.java.name"))
        assertTrue(launcher.contains("virtualSpaceManager.launchHostActivity(component, expectedOwner)"))
        assertTrue(openBody.contains("showVirtualSpaceViewerOpenFailed()"))
        assertTrue(viewer.contains("virtualSpaceManager.captureLatestPreviewJpeg()"))
        assertTrue(viewer.contains("VirtualDisplayTouchMapper.mapViewToDisplay("))
        assertTrue(viewer.contains("agentRuntime.beginManualTakeoverFromOverlay()"))
    }

    @Test
    fun islandOpensVirtualViewerWithoutForegroundTakeover() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        assertTrue(source.contains("openVirtualSpaceViewer()"))
        assertFalse(source.contains("beginMainScreenCapture"))
        assertFalse(source.contains("showForegroundTakeoverControl"))
        assertFalse(source.contains("SCREEN_BRIGHT_WAKE_LOCK"))
    }

    @Test
    fun virtualObservationDoesNotChangePhysicalScreenState() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        assertTrue(source.contains("openVirtualSpaceViewer()"))
        assertFalse(source.contains("beginMainScreenCapture"))
        assertFalse(source.contains("showForegroundTakeoverControl"))
        assertFalse(source.contains("SCREEN_BRIGHT_WAKE_LOCK"))
    }

    @Test
    fun mainCaptureSuppressionIsRetired() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        assertTrue(source.contains("openVirtualSpaceViewer()"))
        assertFalse(source.contains("beginMainScreenCapture"))
        assertFalse(source.contains("showForegroundTakeoverControl"))
        assertFalse(source.contains("SCREEN_BRIGHT_WAKE_LOCK"))
    }

    @Test
    fun takeover_control_is_visible_for_external_mobile_use_tasks() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val pausedCollector = source.substringAfter("agentRuntime.isPaused.collect")
            .substringBefore("agentRuntime.manualTakeoverAvailable.collect")
        val takeoverCollector = source.substringAfter("agentRuntime.manualTakeoverAvailable.collect")
            .substringBefore("\n        }\n        appScope.launch")

        assertTrue(source.contains("agentRuntime.manualTakeoverAvailable.collect"))
        assertTrue(source.contains("agentRuntime.isMobileUseTaskActive.value"))
        assertTrue(pausedCollector.contains("shouldShowExternalTaskOverlay()"))
        assertTrue(pausedCollector.contains("showRuntimeSurfaceIndicator()"))
        assertTrue(takeoverCollector.contains("shouldShowExternalTaskOverlay()"))
        assertTrue(takeoverCollector.contains("showRuntimeSurfaceIndicator()"))
    }

    @Test
    fun runtime_surface_resumes_when_capture_ends_but_does_not_replay_stale_state() {
        val source = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val controller = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val surface = File("../core/device/src/main/java/com/droidance/core/device/agent/device/SidecarRuntimeSurface.java").readText()
        val collectorBlock = source.substringAfter("inputActionLifecycle.events.collect { event ->")
            .substringBefore("\n            }")
        val afterCaptureHookBody = source.substringAfter("sidecarCapture.afterCaptureHook = { displayId ->")
            .substringBefore("\n        }\n\n        // 运行岛只服务")

        assertTrue(collectorBlock.contains("runtimeSurfaceController.showAction(event)"))
        assertTrue(collectorBlock.contains("runtimeSurfaceController.finishAction(event)"))
        assertTrue(controller.contains("private val commands = Channel<Command>(Channel.UNLIMITED)"))
        assertFalse(controller.contains("cursorState?.let"))
        assertTrue(controller.contains("runtimeVisible"))
        val suppression = surface.substringAfter("synchronized boolean setSuppressed(boolean suppressed)")
            .substringBefore("\n    synchronized void destroy()")
        assertTrue("截图期间只隐藏小手，不能销毁正在播放的动画状态", suppression.contains("hideCursor();"))
        assertFalse(suppression.substringAfter("if (suppressed)").substringBefore("return true;").contains("removeCursor();"))
        assertTrue("截图结束后必须恢复仍有效的小手动画", suppression.contains("boolean cursorApplied = applyCursor();"))
        assertFalse(surface.contains("Math.cos"))
        assertFalse(surface.contains("scheduleTrailFade"))
    }

    @Test
    fun cursor_persists_during_gui_task_and_main_screen_only_for_main_space() {
        val overlaySource = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val controllerSource = File("src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val runtimeSource = File("src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        val updateVisibilityBody = overlaySource.substringAfter("private fun updateRuntimeOverlayVisibility() {")
            .substringBefore("\n    private fun showRuntimeSurfaceIndicator()")
        assertTrue("非主屏任务显式隐藏物理屏幕小手", updateVisibilityBody.contains("runtimeSurfaceController.hideCursorOnDisplay(Display.DEFAULT_DISPLAY)"))

        assertTrue("RuntimeSurfaceController 提供 hideCursorOnDisplay", controllerSource.contains("fun hideCursorOnDisplay("))
        assertFalse("小手在任务执行期间保持悬浮，不得按固定延时提前自动隐藏", controllerSource.contains("scheduleCursorDismissal"))

        val preliminaryBlock = runtimeSource.substringAfter("val preliminaryStrategy =")
            .substringBefore("return taskLifecycle.run(")
        assertFalse("Phase A 开始前不得把普通会话或本地工具当成移动端任务", preliminaryBlock.contains("preliminarySpace == ExecutionSpace.MAIN_SCREEN"))
    }

    /** Both execution spaces use the stable task title and preserve handoff states. */
    @Test
    fun both_execution_spaces_display_fixed_task_titles_on_external_island() {
        val overlaySource = File("src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()

        val titleTextBody = overlaySource.substringAfter("private fun runtimeTaskTitleText(): String {")
            .substringBefore("\n    private fun isRuntimeActive()")

        assertTrue(titleTextBody.contains("agentRuntime.currentTaskTitle.value"))
        assertFalse(titleTextBody.contains("ExecutionSpace.VIRTUAL_SPACE"))
        assertFalse(overlaySource.contains("agentRuntime.currentAction"))
        val titleListener = overlaySource.substringAfter("agentRuntime.currentTaskTitle.collect")
            .substringBefore("agentRuntime.isPaused.collect")
        assertTrue(titleListener.contains("showRuntimeSurfaceIndicator()"))
        assertTrue(overlaySource.contains("RuntimeIslandPresentation.State.WAITING_FOR_USER"))
        assertFalse(overlaySource.contains("actionScrollEnabled"))
        assertFalse(overlaySource.contains("runtimeProgressText"))
        assertFalse(titleTextBody.contains("R.string.overlay_status_default"))
    }
}
