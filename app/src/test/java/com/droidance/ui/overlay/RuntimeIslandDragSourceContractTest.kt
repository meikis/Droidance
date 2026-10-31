// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeIslandDragSourceContractTest {
    @Test
    fun mainScreenIslandDragStaysLocalToItsInputSurface() {
        val controller = projectFile("app/src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt").readText()
        val input = controller.substringAfter("private fun onIndicatorInputEvent(").substringBefore("private fun runOnInputSync")
        assertTrue(input.contains("event.rawY - inputDownRawY"))
        assertTrue(input.contains("it.setPosition(surface"))
        assertFalse(input.contains("updateRuntimeIslandBitmap"))
        assertFalse(input.contains("Command.Move"))
        assertTrue(input.contains("if (dragging)"))
        assertTrue(input.contains("appScope.launch(Dispatchers.IO)"))
        assertTrue(controller.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertTrue(controller.contains("SurfaceControlViewHost(appContext, display"))
        assertTrue(controller.contains("host.setView(view, widthPx, heightPx)"))
        assertTrue(controller.contains("override fun setLayoutParams(params: ViewGroup.LayoutParams?)"))
        assertFalse(controller.contains("getMethod(\"setView\""))
        assertTrue(controller.contains("object : RuntimeIslandSurfaceView(appContext)"))
        assertFalse("Dragging must not stop status drawing", controller.contains("command.visible && dragging"))
        assertFalse("Animation must not cross the Binder queue every frame", controller.contains("startAnimation("))
    }

    @Test
    fun bothIslandsClampInsideTheScreenAndShareOneOffset() {
        val controller = projectFile(
            "app/src/main/java/com/droidance/ui/overlay/RuntimeSurfaceController.kt"
        ).readText()
        val viewer = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt"
        ).readText()
        val store = projectFile(
            "app/src/main/java/com/droidance/ui/overlay/RuntimeIslandPosition.kt"
        ).readText()

        // 拖出屏幕就再也点不回来，两处都必须钳制。
        assertTrue(controller.contains("private fun clampIslandTop("))
        assertTrue(controller.contains("topPx.coerceIn(0, maxTop)"))
        assertTrue(viewer.contains("coerceIn(maxUp, maxDown.coerceAtLeast(maxUp))"))
        // 两处默认落点都从屏幕底部起算，挪一处等于挪同一个岛，所以只记一份偏移。
        assertTrue(store.contains("KEY_BOTTOM_OFFSET"))
        assertFalse(store.contains("KEY_MAIN_SCREEN"))
        assertFalse(store.contains("KEY_VIEWER"))
        // 位置跨任务与重启保留。
        assertTrue(controller.contains("RuntimeIslandPosition.write(appContext, offsetX, offsetY)"))
        assertTrue(viewer.contains("RuntimeIslandPosition.read(this)"))
        assertTrue(viewer.contains("RuntimeIslandPosition.readX(this)"))
        assertTrue(viewer.contains("RuntimeIslandPosition.write(this, islandDragOffsetX, islandDragOffsetPx)"))
    }

    @Test
    fun viewerControlIslandStaysAboveThePlatformIme() {
        val viewer = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt"
        ).readText()
        val applyOffset = viewer.substringAfter("private fun applyIslandOffset(offsetPx: Int) {")
            .substringBefore("\n    private fun endIslandDrag()")

        assertTrue(applyOffset.contains("islandView.translationY = islandDragOffsetPx.toFloat()"))
        assertTrue(viewer.contains("inputBottomInset(): Int = maxOf(hostNavigationBottomInsetPx, nativeImeBottomInsetPx)"))
        assertTrue(applyOffset.contains("currentTopInsetPx - controlsTop"))
        assertTrue(applyOffset.contains("currentBottomInsetPx - controlsBottom"))
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
