// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools.observe

import com.droidance.core.device.tools.observe.*

import com.droidance.tools.observe.*

import com.droidance.core.agent.tools.ToolCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootOnlyObserveScreenContractTest {
    @Test
    fun toolExecutorExposesStructuredObserveScreenOnOperatorPath() {
        val executorSource = com.droidance.agent.executorSources()
        val unifiedSource = projectFile("core/device/src/main/java/com/droidance/core/device/tools/UnifiedDeviceToolRegistrar.kt").readText()
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")

        assertTrue("android_read should be the operator observation tool", "android_read" in ToolCatalog.operatorToolNames)
        assertFalse(unifiedSource.contains("\"screen\" -> \"observe_screen\""))
        assertTrue(unifiedSource.contains("\"screen\" -> readScreen()"))
        assertTrue(unifiedSource.contains("\"crop\" -> readCrop(payload)"))
        assertFalse(toolsDir.resolve("ScreenCaptureToolRegistrar.kt").exists())
        assertTrue("ToolExecutor should delegate screen reads to ObserveScreenTool", executorSource.contains("private suspend fun readBoundScreen()"))
        assertTrue("ToolExecutor should delegate screen reads to ObserveScreenTool", executorSource.contains("observeScreenTool.execute()"))
    }

    @Test
    fun observeScreenToolUsesCleanVisionAndNoAccessibility() {
        val source = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()

        assertTrue(source.contains("MODE_PURE_VLM"))
        assertFalse(source.contains("MODE_TEXT_OCR"))
        assertFalse(source.contains("SomAnnotator"))
        // 彻底去无障碍：observe 不再有 Accessibility 节点树路径
        assertFalse("observe_screen must not read Accessibility tree", source.contains("useAccessibilityTree"))
        assertFalse("observe_screen must not have an accessibility tree path", source.contains("executeAccessibilityTreePath"))
        assertFalse("observe_screen should not use uiautomator dump", source.contains("uiautomator"))
    }

    @Test
    fun observeScreenToolCachesByFingerprintAndCanInvalidate() {
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()
        val executor = com.droidance.agent.executorSources()

        assertTrue(observe.contains("lastScreenFingerprint"))
        assertTrue(observe.contains("observe_cache=hit"))
        assertTrue(observe.contains("invalidateCache()"))
        assertTrue(executor.contains("observeScreenTool.invalidateCache()"))
        assertTrue(executor.contains("shouldAutoCaptureAfterTool(toolCall, result)"))
    }

    @Test
    fun observationDoesNotPreclassifyOrAutoDismissBusinessControls() {
        val executorSource = com.droidance.agent.executorSources()
        val observeSource = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()

        assertFalse(projectFile("app/src/main/java/com/droidance/tools")
            .resolve("ScreenCaptureToolRegistrar.kt").exists())
        assertFalse(executorSource.contains("analyzeLikelyBlocker"))
        assertFalse(executorSource.contains("dismissLikelyBlockers"))
        assertFalse(observeSource.contains("Blocker candidates:"))
    }

    @Test
    fun virtualObservationNeverWakesThePhysicalScreen() {
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()
        assertFalse(observe.contains("KEYCODE_WAKEUP"))
        assertFalse(observe.contains("screenStateBlockReason"))
        assertTrue(observe.contains("deviceBackend.captureScreen(forceFresh = true)"))
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
