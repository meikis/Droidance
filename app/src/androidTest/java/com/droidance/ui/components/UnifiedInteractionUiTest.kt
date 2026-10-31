// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.settings.AppThemeMode
import com.droidance.ui.theme.DroidanceTheme
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real rendering and callbacks only: no app data, model calls or device tasks. */
class UnifiedInteractionUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun longDialogKeepsActionsVisibleWithLargeText() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                    DroidanceDialog(onDismissRequest = { dismissed = true }, title = label(R.string.ui_operation_failed),
                        text = "Scrollable explanation. ".repeat(100),
                        confirmLabel = label(R.string.ui_ask_assistant), onConfirm = { confirmed = true },
                        dismissLabel = label(R.string.form_keep_editing))
                }
            }
        }
        compose.onNodeWithText(label(R.string.ui_ask_assistant)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.form_keep_editing)).assertIsDisplayed()
        val actionBounds = compose.onNodeWithText(label(R.string.ui_ask_assistant)).fetchSemanticsNode().boundsInWindow
        assertTrue("Actions must fit inside the display", actionBounds.bottom < context.resources.displayMetrics.heightPixels - 24)
        screenshot("long-dialog-large-text-light.png")
        compose.onNodeWithText(label(R.string.form_keep_editing)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(confirmed) }
    }

    @Test fun calendarPreservesTheSelectedCalendarDay() {
        val date = LocalDate.of(2026, 10, 31)
        var selected: LocalDate? = null
        compose.setContent {
            DroidanceTheme(AppThemeMode.DARK) { DroidanceDatePicker(date, {}, { selected = it }) }
        }
        // Keep the first column visible; a calendar wider than its dialog can silently clip Sundays.
        compose.onNodeWithText("4").assertIsDisplayed()
        compose.onNodeWithText("11").assertIsDisplayed()
        screenshot("calendar-dark.png")
        compose.onNodeWithText(label(R.string.common_save)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(date, selected) }
    }

    @Test fun cancellingTimeInputDoesNotCommitASelection() {
        var selected = false
        var dismissed = false
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                DroidanceTimePicker(23, 45, { dismissed = true }, { _, _ -> selected = true })
            }
        }
        compose.onNodeWithText(label(R.string.common_cancel)).assertIsDisplayed()
        screenshot("time-input-light.png")
        compose.onNodeWithText(label(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(selected) }
    }

    @Test fun invalidTimeCannotBeSavedAndValidInputCommitsExactly() {
        var selected: Pair<Int, Int>? = null
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) { DroidanceTimePicker(9, 30, {}, { h, m -> selected = h to m }) }
        }
        compose.onNode(hasSetTextAction() and hasText(label(R.string.ui_hour))).performTextReplacement("25")
        compose.onNodeWithText(label(R.string.common_save)).assertIsNotEnabled()
        compose.runOnIdle { assertNull(selected) }
        compose.onNode(hasSetTextAction() and hasText(label(R.string.ui_hour))).performTextReplacement("23")
        compose.onNode(hasSetTextAction() and hasText(label(R.string.ui_minute))).performTextReplacement("59")
        compose.onNodeWithText(label(R.string.common_save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(23 to 59, selected) }
    }

    @Test fun invalidDateCannotCommitAndTypedDateSurvivesCalendarToggle() {
        var selected: LocalDate? = null
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) { DroidanceDatePicker(LocalDate.of(2026, 10, 31), {}, { selected = it }) }
        }
        compose.onNodeWithText(label(R.string.ui_date_input)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("2026-02-31")
        compose.onNodeWithText(label(R.string.common_save)).assertIsNotEnabled()
        compose.runOnIdle { assertNull(selected) }
        compose.onNode(hasSetTextAction()).performTextReplacement("2026-11-02")
        compose.onNodeWithText(label(R.string.ui_date_calendar)).performClick()
        compose.onNodeWithText(label(R.string.common_save)).performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 11, 2), selected) }
    }

    private fun screenshot(name: String) {
        val output = File(context.getExternalFilesDir(null), "unified-ui/$name")
        output.parentFile!!.mkdirs()
        compose.waitForIdle()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
