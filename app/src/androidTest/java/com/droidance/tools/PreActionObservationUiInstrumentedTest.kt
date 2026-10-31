// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.device.debug.InputProbeActivity
import com.droidance.debug.ObservationProbeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Device-side, app-owned UI fixtures for calibrating the freshness signal.
 * These tests record detector false/true signal outcomes; they do not equate pixels with
 * semantic success and do not enable enforce mode.
 */
@RunWith(AndroidJUnit4::class)
class PreActionObservationUiInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun probeRecordsStableBackgroundChangeDialogRotationAndPipelinePercentiles() {
        var stableFrame: Bitmap? = null
        var settledFrame: Bitmap? = null
        var changedFrame: Bitmap? = null
        var dialogFrame: Bitmap? = null
        ActivityScenario.launch<ObservationProbeActivity>(ObservationProbeActivity::class.java).use { scenario ->
            try {
                settle()
                stableFrame = screenshot()
                SystemClock.sleep(300)
                settledFrame = screenshot()
                logComparison(
                    label = "stable_no_intentional_change",
                    expectedChange = false,
                    before = stableFrame,
                    after = settledFrame
                )
                stableFrame?.recycle()
                stableFrame = null

                var backgroundBefore = 0
                scenario.onActivity { activity ->
                    val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    backgroundBefore = (content.getChildAt(0).background as? ColorDrawable)?.color ?: 0
                }
                scenario.onActivity { activity ->
                    val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    assertTrue("Observation probe must expose its content view", content.childCount > 0)
                    content.getChildAt(0).performClick()
                }
                SystemClock.sleep(700)
                changedFrame = screenshot()
                var backgroundChanged = false
                var backgroundAfter = 0
                scenario.onActivity { activity ->
                    val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    val surface = content.getChildAt(0)
                    backgroundAfter = (surface.background as? ColorDrawable)?.color ?: 0
                    backgroundChanged = backgroundAfter != backgroundBefore
                }
                logComparison(
                    label = "probe_background_toggle",
                    expectedChange = backgroundChanged,
                    before = settledFrame,
                    after = changedFrame,
                    extra = "background_changed=$backgroundChanged background_before=$backgroundBefore " +
                        "background_after=$backgroundAfter"
                )
                settledFrame?.recycle()
                settledFrame = null

                var dialog: AlertDialog? = null
                try {
                    scenario.onActivity { activity ->
                        dialog = AlertDialog.Builder(activity)
                            .setTitle("probe")
                            .setMessage("overlay")
                            .setPositiveButton("close", null)
                            .create()
                        dialog?.show()
                    }
                    settle()
                    dialogFrame = screenshot()
                    var dialogTriggered = false
                    scenario.onActivity { dialogTriggered = dialog?.isShowing == true }
                    logComparison(
                        label = "probe_dialog_overlay",
                        expectedChange = dialogTriggered,
                        before = changedFrame,
                        after = dialogFrame,
                        extra = "dialog_triggered=$dialogTriggered"
                    )
                    dialogFrame?.recycle()
                    dialogFrame = null
                } finally {
                    scenario.onActivity { dialog?.dismiss() }
                }

                val beforeRotation = scenarioRotation(scenario)
                val beforeRequestedOrientation = scenarioRequestedOrientation(scenario)
                try {
                    scenario.onActivity { activity ->
                        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    settle(1_000)
                    val rotated = screenshot()
                    try {
                        val afterRotation = scenarioRotation(scenario)
                        logRotation(
                            beforeRotation = beforeRotation,
                            afterRotation = afterRotation,
                            beforeWidth = changedFrame?.width,
                            beforeHeight = changedFrame?.height,
                            afterWidth = rotated.width,
                            afterHeight = rotated.height
                        )
                    } finally {
                        rotated.recycle()
                    }
                } finally {
                    scenario.onActivity { activity ->
                        activity.requestedOrientation = beforeRequestedOrientation
                    }
                    settle(1_000)
                }

                val benchmarkFrame = screenshot()
                try {
                    measureCapturePngCompare(benchmarkFrame)
                } finally {
                    benchmarkFrame.recycle()
                }
            } finally {
                dialogFrame?.recycle()
                changedFrame?.recycle()
                settledFrame?.recycle()
                stableFrame?.recycle()
            }
        }
    }

    @Test
    fun inputProbeRecordsImeAttemptAndControlFrameSignal() {
        var beforeIme: Bitmap? = null
        var afterIme: Bitmap? = null
        ActivityScenario.launch<InputProbeActivity>(InputProbeActivity::class.java).use { scenario ->
            try {
                settle(700)
                scenario.onActivity { activity ->
                    val manager = activity.getSystemService(InputMethodManager::class.java)
                    manager.hideSoftInputFromWindow(activity.editor.windowToken, 0)
                }
                settle(500)
                val imeVisibleBefore = imeVisible(scenario)
                beforeIme = screenshot()

                scenario.onActivity { activity ->
                    val manager = activity.getSystemService(InputMethodManager::class.java)
                    activity.editor.requestFocus()
                    manager.showSoftInput(activity.editor, InputMethodManager.SHOW_IMPLICIT)
                }
                settle(1_000)
                val imeVisibleAfter = imeVisible(scenario)
                afterIme = screenshot()
                val imeTriggered = if (
                    imeVisibleBefore != null &&
                    imeVisibleAfter != null &&
                    imeVisibleBefore != imeVisibleAfter
                ) true else null
                logComparison(
                    label = "input_probe_ime_and_control",
                    expectedChange = imeTriggered,
                    before = beforeIme,
                    after = afterIme,
                    extra = "ime_visible_before=$imeVisibleBefore ime_visible_after=$imeVisibleAfter " +
                        "ime_triggered=$imeTriggered"
                )
            } finally {
                scenario.onActivity { activity ->
                    activity.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.editor.windowToken, 0)
                }
                afterIme?.recycle()
                beforeIme?.recycle()
            }
        }
    }

    private fun logComparison(
        label: String,
        expectedChange: Boolean?,
        before: Bitmap?,
        after: Bitmap?,
        extra: String = ""
    ) {
        val comparison = if (before == null || after == null) {
            null
        } else {
            compareScreenshots(before, after)
        }
        val sampled = comparison?.sampledPixels ?: 0
        val changed = comparison?.changedPixels ?: 0
        val thresholdSignal = comparison?.let {
            ObservationPixelComparator.exceedsChangeFraction(it, threshold = 0.005)
        } ?: false
        val falseSignal = expectedChange?.let { !it && thresholdSignal }
        val missedSignal = expectedChange?.let { it && !thresholdSignal }
        val evaluation = when (expectedChange) {
            null -> "not_triggered_or_unavailable"
            true -> "triggered"
            false -> "no_intentional_change"
        }
        android.util.Log.i(
            "PreActionObservationUi",
            "case=$label evaluation=$evaluation expected_change=$expectedChange sampled=$sampled " +
                "changed=$changed changed_fraction=${comparison?.changedFraction ?: "unavailable"} " +
                "threshold_signal=$thresholdSignal false_signal=$falseSignal missed_signal=$missedSignal $extra"
        )
    }

    private fun compareScreenshots(before: Bitmap, after: Bitmap): ObservationPixelComparison? {
        if (before.width != after.width || before.height != after.height) return null
        val size = before.width to before.height
        val mapper = ModelCoordinateMapper(
            scale = 1f,
            modelSize = size,
            physicalSize = size
        )
        val samples = ObservationPixelComparator.mappedGrid(
            mapper = mapper,
            modelSize = size,
            physicalSize = size,
            sampleSide = 8
        )
        val reference = ObservationPixelComparator.captureReference(
            width = before.width,
            height = before.height,
            samples = samples,
            pixelAt = before::getPixel
        )
        return ObservationPixelComparator.compare(
            reference = reference,
            width = after.width,
            height = after.height,
            pixelAt = after::getPixel,
            channelDelta = 8
        )
    }

    private fun measureCapturePngCompare(referenceFrame: Bitmap) {
        val size = referenceFrame.width to referenceFrame.height
        val mapper = ModelCoordinateMapper(
            scale = 1f,
            modelSize = size,
            physicalSize = size
        )
        val samples = ObservationPixelComparator.mappedGrid(mapper, size, size, 8)
        val referenceDecoded = decode(encodePng(referenceFrame))
        val durations = mutableListOf<Long>()
        var validComparisons = 0
        try {
            val reference = ObservationPixelComparator.captureReference(
                width = referenceDecoded.width,
                height = referenceDecoded.height,
                samples = samples,
                pixelAt = referenceDecoded::getPixel
            )
            repeat(16) {
                val started = SystemClock.elapsedRealtimeNanos()
                val captured = checkNotNull(automation.takeScreenshot())
                try {
                    val encoded = encodePng(captured)
                    val decoded = decode(encoded)
                    try {
                        if (decoded.width == size.first && decoded.height == size.second) {
                            ObservationPixelComparator.compare(
                                reference = reference,
                                width = decoded.width,
                                height = decoded.height,
                                pixelAt = decoded::getPixel,
                                channelDelta = 8
                            )
                            validComparisons++
                            durations += SystemClock.elapsedRealtimeNanos() - started
                        }
                    } finally {
                        decoded.recycle()
                    }
                } finally {
                    captured.recycle()
                }
            }
        } finally {
            referenceDecoded.recycle()
        }
        assertTrue("Expected at least one same-size capture", validComparisons > 0)
        val sorted = durations.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95Index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
        val p95 = sorted[p95Index]
        android.util.Log.i(
            "PreActionObservationUi",
            "capture_png_decode_compare_only samples=16 valid=$validComparisons " +
                "compare_p50_ns=$p50 compare_p95_ns=$p95"
        )
    }

    private fun screenshot(): Bitmap = checkNotNull(automation.takeScreenshot()) {
        "UiAutomation.takeScreenshot returned null"
    }

    private fun settle(extraMs: Long = 500L) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(extraMs)
        instrumentation.waitForIdleSync()
    }

    private fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap = checkNotNull(
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    )

    private fun scenarioRotation(scenario: ActivityScenario<ObservationProbeActivity>): Int {
        var value = 0
        scenario.onActivity { value = it.resources.configuration.orientation }
        return value
    }

    private fun scenarioRequestedOrientation(scenario: ActivityScenario<ObservationProbeActivity>): Int {
        var value = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario.onActivity { value = it.requestedOrientation }
        return value
    }

    private fun logRotation(
        beforeRotation: Int,
        afterRotation: Int,
        beforeWidth: Int?,
        beforeHeight: Int?,
        afterWidth: Int,
        afterHeight: Int
    ) {
        android.util.Log.i(
            "PreActionObservationUi",
            "case=rotation expected_frame_identity_change=true " +
                "before_orientation=$beforeRotation after_orientation=$afterRotation " +
                "before_size=${beforeWidth}x${beforeHeight} after_size=${afterWidth}x${afterHeight} " +
                "cross_rotation_compare=not_applied"
        )
    }

    private fun imeVisible(scenario: ActivityScenario<InputProbeActivity>): Boolean? {
        var visible: Boolean? = null
        scenario.onActivity { activity ->
            if (Build.VERSION.SDK_INT >= 30) {
                visible = activity.editor.rootWindowInsets
                    ?.isVisible(WindowInsets.Type.ime()) == true
            }
        }
        return visible
    }
}
