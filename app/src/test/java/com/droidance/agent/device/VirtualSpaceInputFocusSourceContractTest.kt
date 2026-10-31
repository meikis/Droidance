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

class VirtualSpaceInputFocusSourceContractTest {
    @Test
    fun readiness_probe_does_not_request_text_focus_or_inject_text() {
        val probe = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceProbeActivity.kt"
        ).readText()
        val readiness = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceReadiness.kt"
        ).readText()

        assertFalse(probe.contains("EditText"))
        assertFalse(probe.contains("requestFocus"))
        assertTrue(probe.contains("isFocusable = false"))
        assertFalse(readiness.contains("injectText(displayId"))
        assertFalse(readiness.contains("KeyEvent.KEYCODE_SHIFT_LEFT"))
    }

    @Test
    fun textInputRequiresExpectedWindowOnTheRequestedDisplay() {
        val service = projectFile(
            "core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java"
        ).readText()
        val text = service.substringAfter("public Bundle text(")
            .substringBefore("@Override\n    public String getForegroundComponent")
        val focus = service.substringAfter("public String getInputFocusState")
            .substringBefore("@Override\n    public byte[] capturePng")

        assertTrue(text.contains("getInputFocusState(targetDisplayId)"))
        assertTrue(text.contains("expectedFocusTarget.equals(focusState)"))
        assertTrue(text.contains("focusConfirmed"))
        assertTrue(text.indexOf("focusConfirmed") < text.indexOf("injectTextOnDisplay"))
        assertTrue(focus.contains("shellCommands.executeInputFocus"))
        assertTrue(focus.contains("InputWindowFocusParser.parse(query.stdout, targetDisplayId).target"))
        assertFalse(focus.contains("getForegroundComponent(targetDisplayId)"))
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
