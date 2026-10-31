// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualSpaceViewerLauncherTest {
    @Test
    fun reopeningAfterDisplayReleasePreparesAnotherDisplayBeforeLaunchingTheViewer() {
        val fixture = DisplayFixture()
        assertTrue(fixture.launcher.open())
        fixture.releaseDisplay()

        assertTrue(fixture.launcher.open())

        assertEquals(listOf("inspect", "prepare", "create:1", "launch:1", "release:1",
            "inspect", "prepare", "create:2", "launch:2"), fixture.events)
    }

    @Test fun anExpiredConversationEntryCannotPrepareAnEmptyReplacementDisplay() {
        val fixture = DisplayFixture()
        val owner = RetainedVirtualExecution("old-thread", "old-execution",
            SidecarSessionBinding(RuntimeMode.STANDARD, 123, "old-backend"))
        assertFalse(fixture.launcher.open(owner))
        assertEquals(listOf("inspect"), fixture.events)
    }

    @Test
    fun openingAnExistingDisplayPreservesItsIdentity() {
        val fixture = DisplayFixture()
        assertTrue(fixture.launcher.open())
        assertTrue(fixture.launcher.open())

        assertEquals(listOf("inspect", "prepare", "create:1", "launch:1", "inspect", "launch:1"), fixture.events)
    }

    @Test
    fun changingSelectedModeDoesNotPrepareOrReplaceTheExistingFrozenDisplay() {
        val fixture = DisplayFixture()
        assertTrue(fixture.launcher.open())
        fixture.selectedMode = RuntimeMode.ULTRA

        assertTrue(fixture.launcher.open())

        assertEquals(RuntimeMode.STANDARD, fixture.displayMode)
        assertEquals(1, fixture.events.count { it == "prepare" })
        assertEquals(listOf("launch:1", "launch:1"), fixture.events.filter { it.startsWith("launch:") })
    }

    @Test
    fun failedReadinessNeverDispatchesTheHostLaunch() {
        val fixture = DisplayFixture(preparationAllowed = false)

        assertFalse(fixture.launcher.open())

        assertEquals(listOf("inspect", "prepare"), fixture.events)
    }

    @Test
    fun hostLaunchFailureIsReportedWithoutRecreatingOrReplayingTheDisplay() {
        val fixture = DisplayFixture(launchAllowed = false)

        assertFalse(fixture.launcher.open())

        assertEquals(listOf("inspect", "prepare", "create:1", "launch:1"), fixture.events)
    }

    private class DisplayFixture(
        private val preparationAllowed: Boolean = true,
        private val launchAllowed: Boolean = true
    ) {
        val events = mutableListOf<String>()
        var selectedMode = RuntimeMode.STANDARD
        var displayMode: RuntimeMode? = null
            private set
        private var displayId: Int? = null
        private var generation = 0
        val launcher = VirtualSpaceViewerLauncher(
            hasLiveDisplay = {
                events += "inspect"
                displayId != null
            },
            prepareDisplay = {
                events += "prepare"
                if (preparationAllowed && displayId == null) {
                    displayId = ++generation
                    displayMode = selectedMode
                    events += "create:$displayId"
                }
                preparationAllowed && displayId != null
            },
            launchViewer = {
                checkNotNull(displayId)
                events += "launch:$displayId"
                launchAllowed
            }
        )

        fun releaseDisplay() {
            events += "release:$displayId"
            displayId = null
            displayMode = null
        }
    }
}
