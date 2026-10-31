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

class VirtualSpaceSidecarRecoverySourceContractTest {
    @Test
    fun removedDisplayIsReleasedBeforeStartingItsReplacement() {
        val source = projectFile(
            "core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java"
        ).readText()
        val start = source.substringAfter("public synchronized boolean start(")
            .substringBefore("public synchronized Bundle configureDisplayInsets(")
        assertTrue(start.contains("if (getDisplayId() > Display.DEFAULT_DISPLAY)"))
        assertTrue(start.indexOf("releaseDisplay();") in 0 until start.indexOf("createVirtualDisplay("))
        val displayId = source.substringAfter("public synchronized int getDisplayId()")
            .substringBefore("public synchronized Bundle inspectDisplayContent(")
        assertTrue(displayId.contains("current.isValid()"))
        assertTrue(displayId.contains("current.getDisplayId() == displayId"))
    }

    @Test
    fun binderTransportFailureInvalidatesCachedServiceAndRetriesStartupPath() {
        val source = projectFile(
            "core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt"
        ).readText()

        assertTrue(source.contains("current is RemoteException"))
        assertTrue(source.contains("isBinderTransportFailure(failure)"))
        assertTrue(source.contains("protocolCompatibility.remove(binder)"))
        assertTrue(source.contains("registeredStart.ok || !registeredStart.channelUnavailable"))
        assertTrue(source.contains("channelUnavailable = isBinderTransportFailure(e)"))
    }

    private fun projectFile(path: String): File {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        return if (root.name == "app") File(requireNotNull(root.parentFile), path) else File(root, path)
    }
}
