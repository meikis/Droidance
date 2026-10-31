// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.content.Context
import android.view.Display
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSnapshotProviderInstrumentedTest {
    @Test
    fun applicationContextCanCapturePhysicalDisplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val snapshot = captureDisplay(context, Display.DEFAULT_DISPLAY)

        assertTrue(snapshot.width > 0)
        assertTrue(snapshot.height > 0)
        assertTrue(snapshot.densityDpi > 0)
        assertTrue(snapshot.rotationDegrees in setOf(0, 90, 180, 270))
    }

    @Test
    fun rotationIsProjectedToDegrees() {
        assertEquals(0, rotationDegrees(Surface.ROTATION_0))
        assertEquals(90, rotationDegrees(Surface.ROTATION_90))
        assertEquals(180, rotationDegrees(Surface.ROTATION_180))
        assertEquals(270, rotationDegrees(Surface.ROTATION_270))
    }
}
