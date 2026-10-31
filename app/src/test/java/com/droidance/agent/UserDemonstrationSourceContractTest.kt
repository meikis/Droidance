// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDemonstrationSourceContractTest {
    @Test
    fun virtualSpaceGesturesBecomeMultimodalLearningEvidenceWithoutAccessibility() {
        val viewer = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt"
        ).readText()
        val recorder = projectFile(
            "app/src/main/java/com/droidance/agent/OperationRecorder.kt"
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/com/droidance/ui/viewmodel/RecorderViewModel.kt"
        ).readText()
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val executor = com.droidance.agent.executorSources()
        val registrar = projectFile("app/src/main/java/com/droidance/tools/SkillToolRegistrar.kt").readText()

        assertTrue(viewer.contains("UserDemonstrationGestureClassifier.classify"))
        assertTrue(viewer.contains("operationRecorder.recordUserGesture"))
        assertTrue(viewer.contains("beforeFrame = capturedBefore"))
        assertTrue(viewer.contains("afterFrame = afterFrame"))
        assertTrue(recorder.contains("beforeFrameRef = beforeRef"))
        assertTrue(recorder.contains("afterFrameRef = afterRef"))
        assertTrue(recorder.contains("eventStore.eventsForThread(\"skill-learning:\$id\")"))
        assertTrue(recorder.contains("SkillLearningSessionProjector.project"))
        assertFalse(recorder.contains("_operations.value = _operations.value + recorded"))
        assertTrue(recorder.contains("action.beforeFrameRef else action.afterFrameRef"))
        assertTrue(recorder.contains("historicalAttachment = DetailAttachmentAddedEventPayload("))
        assertTrue(registrar.contains("tool(\"skill_source\""))
        assertTrue(executor.contains("readLearning = operationRecorder::readLearningSource"))
        assertTrue(viewModel.contains("virtualSpaceViewerLauncher.open()"))
        assertTrue(viewModel.contains("operationRecorder.completedSessionId()"))
        assertTrue(viewModel.contains("readMarkdownSection(\"skills.md\", \"demonstration_request\")"))
        assertTrue(viewModel.contains("session_id=\$session"))
        assertFalse(viewModel.contains("llmRouter"))
        assertFalse(viewModel.contains("chatStream"))
        assertFalse(viewModel.contains("_learnedScriptName"))
        assertFalse(viewModel.contains("generationResult"))
        assertFalse(viewModel.contains("原始回复："))
        assertFalse(viewModel.contains("result.take(300)"))
        assertFalse(viewModel.contains("result.take(500)"))
        assertFalse(manifest.contains("android.accessibilityservice.AccessibilityService"))
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
