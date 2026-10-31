// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.device.agent.device.CursorMotion
import com.droidance.debug.MascotGestureProbeActivity
import com.droidance.core.device.tools.InputAction
import com.droidance.ui.overlay.VirtualSpaceHandCue
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MascotFrameDeliveryTest {
    @Test fun pointerFrameIsCommittedWithoutDecorativePreTravel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latencies = mutableListOf<Long>()
        ActivityScenario.launch(MascotGestureProbeActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            repeat(3) { index ->
                val committed = CountDownLatch(1)
                val elapsed = AtomicLong(-1)
                scenario.onActivity { activity ->
                    assertTrue(activity.pointer.isHardwareAccelerated)
                    val start = CursorMotion.nowMs()
                    activity.pointer.viewTreeObserver.registerFrameCommitCallback {
                        elapsed.set(CursorMotion.nowMs() - start)
                        committed.countDown()
                    }
                    activity.pointer.showCue(VirtualSpaceHandCue("frame-$index", 0,
                        InputAction.Tap(160 + index * 20, 320), start))
                }
                assertTrue("Pointer must reach a committed frame", committed.await(1500, TimeUnit.MILLISECONDS))
                latencies += elapsed.get()
                assertTrue("Pointer first-frame latency ${elapsed.get()} ms", elapsed.get() in 0..120)
                instrumentation.waitForIdleSync()
            }
        }
        File(instrumentation.targetContext.getExternalFilesDir(null), "mascot-frame-latencies.txt")
            .writeText(latencies.joinToString(","))
    }
}
