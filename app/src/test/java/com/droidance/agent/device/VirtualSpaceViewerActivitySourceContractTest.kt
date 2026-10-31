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

class VirtualSpaceViewerActivitySourceContractTest {
    @Test
    fun systemBackControlsTheCurrentAppAndOnlyCollapseClosesTheViewer() {
        val viewer = viewerSources()
        val callback = viewer.substringAfter("onBackPressedDispatcher.addCallback(this")
            .substringBefore("lifecycleScope.launch(Dispatchers.IO)")
        val back = viewer.substringAfter("private fun sendVirtualBack()")
            .substringBefore("private fun applyFrameAppearance")
        assertTrue(callback.contains("handleOnBackPressed() = sendVirtualBack()"))
        assertFalse(callback.contains("closeViewer()"))
        assertTrue(back.contains("awaitInputSettlement()"))
        assertTrue(back.contains("displayedBinding != virtualSpaceManager.activeBinding()"))
        assertTrue(back.contains("virtualSpaceManager.injectKey(KeyEvent.KEYCODE_BACK)"))
        assertFalse(back.contains("closeViewer()"))
        assertFalse(viewer.contains("MainScreenTakeover"))
        for (locale in listOf("values", "values-zh")) {
            val strings = projectFile("app/src/main/res/$locale/strings.xml").readText()
            assertFalse(strings.contains("name=\"chat_takeover_open_virtual_space\""))
            assertTrue(strings.contains("name=\"chat_takeover_handle\""))
        }
        assertTrue(projectFile("app/src/main/res/values-zh/strings.xml").readText()
            .contains("name=\"takeover_close_viewer\">收起</string>"))
    }

    @Test
    fun runtimeIslandOpensVirtualSpaceThroughANormalActivityWithoutOverlayPermission() {
        val viewer = viewerSources()
        val overlay = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val launcher = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerLauncher.kt"
        ).readText()
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val openViewer = overlay.substringAfter("private fun openVirtualSpaceViewer()")
            .substringBefore("private fun enterInteractiveTakeover()")

        assertTrue(manifest.contains(".agent.device.VirtualSpaceViewerActivity"))
        assertTrue(openViewer.contains("virtualSpaceViewerLauncher.open()"))
        assertTrue(launcher.contains("virtualSpaceManager.launchHostActivity(component, expectedOwner)"))
        assertTrue(launcher.contains("VirtualSpaceViewerActivity::class.java.name"))
        assertFalse(openViewer.contains("canDraw()"))
        assertFalse(openViewer.contains("showInteractiveTakeoverPanel()"))
        assertFalse(viewer.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
        assertFalse(viewer.contains("Settings.canDrawOverlays"))
        assertFalse(viewer.contains("wm.addView"))
    }

    @Test
    fun activityViewerReadsAndControlsTheSameVirtualDisplay() {
        val viewer = viewerSources()
        val manager = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()

        assertTrue(viewer.contains("VirtualSpaceViewerMirror.Target(binding, parent"))
        assertTrue(viewer.contains("VirtualDisplayTouchMapper.mapViewToDisplay("))
        assertTrue(viewer.contains("virtualSpaceManager.injectTouch("))
        assertTrue(viewer.contains("virtualSpaceManager.injectKey(KeyEvent.KEYCODE_BACK)"))
        assertTrue(viewer.contains("agentRuntime.beginManualTakeoverFromOverlay()"))
        assertTrue(viewer.contains("agentRuntime.resumeManualTakeover()"))
        assertTrue(manager.contains("fun launchHostActivity(component: String, expectedOwner: RetainedVirtualExecution? = null): Boolean"))
        assertTrue(bridge.contains("fun launchActivityOnDisplay("))
        assertTrue(bridge.contains("targetDisplayId: Int = 0"))
    }

    @Test
    fun expandedViewerReplacesTheBackgroundIslandWithOneUnifiedControlIsland() {
        val viewer = viewerSources()
        val controller = projectFile("app/src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val renderer = projectFile("app/src/main/java/com/droidance/ui/overlay/RuntimeSurfaceBitmapRenderer.kt").readText()
        val island = projectFile("app/src/main/java/com/droidance/agent/device/ViewerControlIslandView.kt").readText()

        assertTrue(viewer.contains("runtimeSurfaceController.setViewerExpanded(true)"))
        assertTrue(viewer.contains("runtimeSurfaceController.setViewerExpanded(false)"))
        assertTrue(controller.contains("if (viewerExpanded || foregroundState.isInForeground) return"))
        assertTrue(island.contains("R.string.takeover_close_viewer"))
        assertFalse(island.contains("R.drawable.ic_overlay_chevron_down"))
        assertFalse(island.contains("R.drawable.ic_overlay_arrow_back"))
        assertTrue(viewer.contains("override fun handleOnBackPressed() = sendVirtualBack()"))
        assertTrue(island.contains("onToggle()"))
        assertTrue(viewer.contains("::toggleTakeover"))
        assertTrue(viewer.contains("R.string.takeover_start_control"))
        assertTrue(island.contains("R.string.common_stop"))
        assertTrue(viewer.contains("agentRuntime.stopCurrentMobileUse(origin = \"virtual_space_viewer_stop\")"))
        assertTrue(renderer.contains("private fun drawStopGlyph("))
        // 三处岛（顶部运行岛位图、接管镜像控制岛、查看页控制岛）共用 RuntimeIslandStyle 令牌，
        // 不再各写一遍十六进制，视觉才不会各自漂移。
        assertTrue(renderer.contains("RuntimeIslandStyle.fillColor()"))
        // V12 uses the shared mascot renderer in both islands.
        assertTrue(island.contains("RuntimeIslandStyle.fillColor()"))
        assertTrue(island.contains("mascotPainter.draw("))
        assertTrue(renderer.contains("private fun drawMascot("))
        assertFalse(renderer.contains("RuntimeIslandFlowLight.draw("))
        // 状态点已经由岛内流光取代，两处都不许再画那颗点。
        assertFalse(viewer.contains("RuntimeIslandStyle.dot("))
        assertFalse(renderer.contains("RuntimeIslandStyle.dot("))
    }

    @Test
    fun taskCompletionKeepsTheViewerOpenAndRetainedReviewStillOpens() {
        val viewer = viewerSources()
        val launcher = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerLauncher.kt"
        ).readText()

        assertTrue(viewer.contains("private fun hasViewableVirtualSpace(): Boolean = virtualSpaceManager.activeBinding() != null"))
        assertTrue(launcher.contains("virtualSpaceReadiness.ensureReadyResult().ready"))
        assertTrue(launcher.contains("virtualSpaceManager.activeBinding() != null"))
        assertFalse(launcher.contains("requireHealthProbe = true"))
        assertFalse(launcher.contains("agentRuntime"))
        assertFalse(viewer.contains("agentRuntime.shouldKeepVirtualSpaceViewerOpen()"))
        assertFalse(viewer.contains("shouldKeepVirtualSpaceViewerOpen()"))
        assertFalse(viewer.contains("observedActiveTask"))
        val observers = viewer.substringAfter("repeatOnLifecycle(Lifecycle.State.STARTED)")
            .substringBefore("override fun onStart()")
        assertFalse(observers.contains("closeViewer()"))
        assertFalse(observers.contains("finish()"))
        assertTrue(viewer.contains("onStop = ::stopTaskFromIsland"))
        assertFalse(viewer.contains("virtualSpaceManager.stop()"))
        assertFalse(viewer.contains("executionContextStore"))
        assertFalse(launcher.contains("executionContextStore"))
    }

    @Test
    fun completedTaskRetainsTheVirtualDisplayForReviewAndTheNextExecution() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val viewer = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt"
        ).readText()
        val terminalFinalization = runtime
            .substringAfter("diagnostics.finishTaskDiagnostics(request.requestId)")
            .substringBefore("val effectiveTakeoverStatus")

        assertFalse(runtime.contains("VirtualSpaceTerminalLeasePolicy"))
        assertFalse(terminalFinalization.contains("virtualSpaceManager.stop()"))
        assertFalse(runtime.contains("virtualSpaceManager.stop()"))
        val refresh = viewer.substringAfter("private suspend fun refreshFrame()")
            .substringBefore("private suspend fun handleFrameFailure")
        assertTrue(refresh.contains("virtualSpaceManager.activeBinding()"))
        assertFalse(refresh.contains("shouldKeepVirtualSpaceViewerOpen"))
    }

    @Test
    fun deadRetainedBindingClosesTheViewerAfterTheBoundedFrameFailureCheck() {
        val viewer = viewerSources()
        val refresh = viewer.substringAfter("private suspend fun refreshFrame()")
            .substringBefore("private fun buildTouchListener()")

        assertTrue(refresh.contains("virtualSpaceManager.hasLiveSessionForMode(binding.runtimeMode)"))
        assertTrue(refresh.contains("virtualSpaceManager.discardStaleSession("))
        assertTrue(refresh.contains("closeViewer()"))
    }

    @Test
    fun emptyVirtualDisplayShowsItsOwnNoticeBeforeAnyRetainedPreviewFrame() {
        val viewer = viewerSources()
        val refresh = viewer.substringAfter("private suspend fun refreshFrame()")
            .substringBefore("private suspend fun handleFrameFailure")
        val empty = refresh.substringAfter("VirtualDisplayContentState.EMPTY -> {")
            .substringBefore("VirtualDisplayContentState.UNAVAILABLE ->")

        assertTrue(refresh.indexOf("virtualSpaceManager.inspectDisplayContent(binding)") <
            refresh.indexOf("updateViewerMirror()"))
        assertTrue(refresh.contains("virtualSpaceManager.activeBinding() != binding"))
        assertTrue(refresh.contains("displayContentState = content"))
        assertTrue(refresh.contains("updateViewerMirror()"))
        assertTrue(empty.contains("errorView.visibility = View.GONE"))
        assertTrue(empty.contains("emptyView.visibility = View.VISIBLE"))
        assertFalse(empty.contains("showChannelError()"))
        assertFalse(empty.contains("handleFrameFailure(binding)"))
        assertTrue(viewer.contains("CONTENT_INSPECTION_INTERVAL_MS = 1_000L"))
        for (locale in listOf("values", "values-zh")) {
            val strings = projectFile("app/src/main/res/$locale/strings.xml").readText()
            assertTrue(strings.contains("name=\"virtual_space_viewer_empty\""))
        }
    }

    @Test
    fun explicitViewerStopCancelsTheRunAndKeepsTheRetainedDisplay() {
        val viewer = viewerSources()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        assertTrue(viewer.contains("agentRuntime.stopCurrentMobileUse(origin = \"virtual_space_viewer_stop\")"))
        assertTrue(runtime.contains("fun stopCurrentMobileUse(origin: String)"))
        assertTrue(runtime.contains("_isMobileUseTaskActive.value = false"))
        assertFalse(runtime.contains("virtualSpaceManager.stop()"))
        assertFalse(runtime.contains("virtualSpaceManager.stop()"))
    }

    @Test
    fun activityViewerKeepsAFocusableHostWindowOnTheMainDisplay() {
        val viewer = viewerSources()

        // The viewer is the main-display host; making it non-focusable causes an
        // input-dispatch timeout while the host covers MainActivity.
        assertFalse(viewer.contains("FLAG_NOT_FOCUSABLE"))
        assertFalse(viewer.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"))
        assertTrue(viewer.contains("windowManager.defaultDisplay.displayId != Display.DEFAULT_DISPLAY"))
    }

    @Test
    fun manualViewerUsesThePlatformImeAndHasNoTextProxy() {
        val viewer = viewerSources()
        assertFalse(viewer.contains("FLAG_ALT_FOCUSABLE_IM"))
        assertTrue(viewer.contains("nativeImeEnabled = manualInputEnabled()"))
        assertTrue(viewer.contains("virtualSpaceManager.viewerImeBottomInset(binding)"))
        assertFalse(viewer.contains("EditText"))
        assertFalse(viewer.contains("ClipboardManager"))
        assertFalse(viewer.contains("sendImeText"))
        assertTrue(viewer.contains("isTeaching() || agentRuntime.isPaused.value"))
        assertTrue(viewer.contains("inputBottomInset(): Int = maxOf(hostNavigationBottomInsetPx, nativeImeBottomInsetPx)"))
        assertTrue(viewer.contains("attributes.height = WindowManager.LayoutParams.MATCH_PARENT"))
        assertFalse("Virtual space viewport must not be compressed when IME is active",
            viewer.contains("bounds.height() - nativeImeBottomInsetPx"))
        assertTrue(viewer.contains("dismissNativeIme()"))
    }

    @Test
    fun controlIslandKeepsAFixedWidthAndNeverPacksTextButtonsIntoIt() {
        val viewer = viewerSources()
        val island = viewer.substringAfter("islandView = ViewerControlIslandView(")
            .substringBefore("islandView.translationY")

        // 岛宽定死在统一令牌上，不随按钮显隐和状态文案长短左右伸缩，也不再铺满屏宽。
        assertTrue(island.contains("islandWidthPx()"))
        assertTrue(island.contains("islandHeightPx()"))
        assertTrue("Both islands share the bottom placement", island.contains("islandBottomMarginPx("))
        assertTrue(viewer.contains("RuntimeIslandPosition.defaultTopPx(parentHeight, islandHeightPx())"))
        assertFalse(island.contains("ViewGroup.LayoutParams.MATCH_PARENT"))
        // 状态区吸收剩余宽度并自己截断，长文案不许挤压右侧按钮。
        assertTrue(viewer.contains("LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)"))
        assertTrue(viewer.contains("RuntimeIslandLabelView(context)"))
        val islandBuilder = projectFile("app/src/main/java/com/droidance/agent/device/ViewerControlIslandView.kt").readText()
        assertFalse(islandBuilder.contains("ic_overlay_arrow_back"))
        assertFalse(islandBuilder.contains("ic_overlay_keyboard"))
        assertFalse(viewer.contains("takeoverToolsView"))
        assertTrue(viewer.contains("RuntimeIslandPresentation.State.USER_CONTROL"))
        assertTrue(viewer.contains("RuntimeIslandPresentation.State.WAITING_FOR_USER"))
        // 操作按钮一律是等尺寸正圆图标：文字按钮在中英文下宽度差很大，会把岛挤变形。
        assertTrue(viewer.contains("private fun controlIconChip("))
        assertTrue(viewer.contains("LinearLayout.LayoutParams(chipSizePx, chipSizePx)"))
        assertTrue(viewer.contains("R.drawable.ic_overlay_stop"))
        assertTrue(viewer.contains("R.drawable.ic_overlay_play"))
        assertTrue(viewer.contains("R.drawable.ic_overlay_pause"))
        assertTrue(viewer.contains("RuntimeIslandStyle.stopIcon(density)"))
        val stopInit = viewer.substringAfter("stopView = it").substringBefore("}")
        assertTrue(stopInit.contains("RuntimeIslandStyle.NEUTRAL_CHIP_FILL"))
        assertTrue(stopInit.contains("RuntimeIslandStyle.CHIP_SIZE_DP / 2f"))
        assertFalse(viewer.contains("private fun controlButton("))
    }

    @Test
    fun activityViewerStaysInTheDroidanceTaskAndNeverRemovesIt() {
        val viewer = viewerSources()
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val declaration = manifest
            .substringAfter("android:name=\".agent.device.VirtualSpaceViewerActivity\"")
            .substringBefore("/>")

        assertTrue(declaration.contains("android:launchMode=\"singleTop\""))
        assertFalse(declaration.contains("android:launchMode=\"singleTask\""))
        assertFalse(declaration.contains("android:taskAffinity=\"\""))
        assertFalse(viewer.contains("finishAndRemoveTask()"))
        assertTrue(viewer.contains("finish()"))
    }

    @Test
    fun mirrorTargetsSixtyFpsAndStopsWithoutChangingTheVirtualDisplayOutput() {
        val viewer = viewerSources()
        val mirror = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarViewerMirror.java").readText()
        val lifecycle = projectFile("app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerMirror.kt").readText()
        val refresh = viewer.substringAfter("private suspend fun refreshFrame()").substringBefore("private suspend fun handleFrameFailure")
        assertTrue(viewer.contains("SurfaceView(this@VirtualSpaceViewerActivity)"))
        assertTrue(viewer.contains("preferredRefreshRate = 60f"))
        assertTrue(viewer.contains("Lifecycle.State.STARTED"))
        assertTrue(viewer.substringAfter("override fun onStop()").substringBefore("override fun onDestroy()").contains("viewerMirror.update(null)"))
        assertTrue(viewer.contains("viewerMirror.close()"))
        assertFalse(refresh.contains("captureLatestPreviewJpeg"))
        assertFalse(refresh.contains("decodeByteArray"))
        assertTrue(refresh.contains("sampleViewerBarColors(binding, topInset, navigationInset)"))
        assertTrue(mirror.contains("mirrorDisplay"))
        assertTrue(mirror.contains("setFrameRate(mirror, 60f"))
        assertFalse(mirror.contains("setSurface("))
        assertFalse(mirror.contains("VirtualDisplay" + " display"))
        assertTrue(mirror.contains("requestedOwner.equals(owner)"))
        assertTrue(lifecycle.contains("Channel<Request>(Channel.CONFLATED)"))
        assertTrue(lifecycle.contains("request.revision == revision.get()"))
        assertTrue(lifecycle.contains("finally {"))
        assertTrue(lifecycle.contains("manager.detachViewerMirror(it.binding, owner)"))
    }

    @Test
    fun controlStateChangesAreCollectedSeparatelyFromPreviewFrames() {
        val viewer = viewerSources()
        val previewLoop = viewer.substringAfter("while (isActive)")
            .substringBefore("agentRuntime.isPaused.collect")

        assertFalse(previewLoop.contains("refreshControlState()"))
        assertTrue(viewer.contains("agentRuntime.isPaused.collect { manual ->"))
        assertTrue(viewer.contains("if (manual != lastManualState) refreshControlState(manual)"))
        assertTrue(viewer.contains("private fun refreshControlState(manual: Boolean = agentRuntime.isPaused.value)"))
    }

    @Test
    fun viewerIslandFollowsDisplayFramesWhileAuroraRetainsItsOwnBudget() {
        val viewer = viewerSources()
        val aurora = projectFile("app/src/main/java/com/droidance/ui/overlay/AuroraEdgeView.kt").readText()
        val island = projectFile("app/src/main/java/com/droidance/agent/device/ViewerControlIslandView.kt").readText()

        assertTrue(aurora.contains("com.droidance.core.device.agent.device.AuroraEdgePainter.FRAME_INTERVAL_MS"))
        assertTrue(aurora.contains("now - lastDrawAtMs >= ANIMATION_FRAME_INTERVAL_MS"))
        assertFalse(aurora.contains("if (!lowPower)"))
        assertTrue(island.contains("postInvalidateOnAnimation()"))
        assertFalse(island.contains("DECORATION_FRAME_INTERVAL_MS"))
    }

    @Test
    fun viewerAppliesWindowInsetsAndAdaptsSystemBarAppearanceToPreview() {
        val viewer = viewerSources()

        assertTrue(viewer.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"))
        assertTrue(viewer.contains("ViewCompat.setOnApplyWindowInsetsListener(rootContainer)"))
        assertTrue(viewer.contains("WindowInsetsCompat.Type.statusBars()"))
        assertTrue(viewer.contains("WindowInsetsCompat.Type.displayCutout()"))
        assertTrue(viewer.contains("WindowInsetsCompat.Type.navigationBars()"))
        assertTrue(viewer.contains("navigationBackground.setBackgroundColor(colors.navigation)"))
        assertTrue(viewer.contains("params.height = navInsets.bottom"))
        assertTrue(viewer.contains("window.isNavigationBarContrastEnforced = false"))
        assertTrue(viewer.contains("private fun applyFrameAppearance(colors: ViewerSystemBarColors)"))
        assertTrue(viewer.contains("controller.isAppearanceLightStatusBars = statusLight"))
        assertTrue(viewer.contains("controller.isAppearanceLightNavigationBars = navigationLight"))
        assertTrue(viewer.contains("applySystemBarInsets("))
    }

    @Test
    fun viewerEdgeGlowAdaptsToDeviceCornerRadiiDynamically() {
        val viewer = viewerSources()
        val aurora = projectFile("app/src/main/java/com/droidance/ui/overlay/AuroraEdgeView.kt").readText()
        val radiusHelper = projectFile("app/src/main/java/com/droidance/ui/overlay/DeviceCornerRadius.kt").readText()

        assertTrue(viewer.contains("auroraView.updateWindowInsets(insets.toWindowInsets())"))
        assertTrue(aurora.contains("DeviceCornerRadius.resolve("))
        assertTrue(aurora.contains("painter.resize(width, height, cornerRadii.toPathRadii())"))
        assertTrue(aurora.contains("painter.draw(canvas, phase, 1f)"))
        assertTrue(radiusHelper.contains("getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)"))
        assertTrue(radiusHelper.contains("rounded_corner_radius"))
    }

    private fun viewerSources(): String = listOf(
        "VirtualSpaceViewerActivity.kt", "ViewerControlIslandView.kt"
    ).joinToString("\n") { projectFile("app/src/main/java/com/droidance/agent/device/$it").readText() }

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
