// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualImePolicySourceContractTest {
    @Test
    fun virtualDisplayKeepsTheUsersImeOffTheHiddenDisplayBeforeBecomingReady() {
        val source = projectFile(
            "core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java"
        ).readText()
        val displayReady = source.substringAfter("displayId = createdDisplay.getDisplayId();")
            .substringBefore("status = \"READY\";")

        val policy = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/SidecarViewerIme.java").readText()
        assertTrue(policy.contains("setDisplayImePolicy"))
        assertTrue(policy.contains("static final int HIDE = 2"))
        assertTrue(displayReady.contains("viewerIme = new SidecarViewerIme(displayId)"))
        assertTrue(policy.contains("platform.setPolicy(displayId, HIDE)"))
        assertTrue(policy.contains("requestedLifetime.linkToDeath"))
        assertTrue(source.contains("enabled && !viewerMirror.isOwnedBy(owner)"))
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
