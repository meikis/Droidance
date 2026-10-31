// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.automation.*
import com.droidance.settings.AppThemeMode
import com.droidance.ui.theme.DroidanceTheme
import com.droidance.ui.viewmodel.AutomationDraft
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Presentation fixtures never persist plans, request a model, or execute device tasks. */
class AutomationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private val schedule = AutomationSchedule(AutomationCadence.ONCE, 9, 0, timeZoneId = ZoneId.systemDefault().id,
        runAtMs = System.currentTimeMillis() + 3_600_000L)
    private val definition = AutomationDefinition("fixture", 1, "Fixture task", "**Rendered instructions**", true, schedule,
        enabled = false, finishedAtMs = 1L)

    @Test fun endedTaskHasOneStatusAndActionsOpenOnlyOnRequest() {
        var opened = false
        var edited = false
        compose.setContent {
            DroidanceTheme(AppThemeMode.DARK) {
                Surface { AutomationListRow(definition, null, { opened = true }, { edited = true }, {}, {}, {}) }
            }
        }
        compose.onAllNodesWithText(label(R.string.automation_filter_ended)).assertCountEquals(1)
        compose.onNodeWithText("AI 命名").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.automation_run_now)).assertDoesNotExist()
        screenshot("list-dark.png")
        compose.onNodeWithText(definition.title).performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.onNodeWithContentDescription(label(R.string.common_more)).performClick()
        compose.onNodeWithText(label(R.string.automation_reschedule)).performClick()
        compose.runOnIdle { assertTrue(edited) }
    }

    @Test fun historyOpensTheBoundConversationWithoutRepeatingItsResult() {
        val run = AutomationOccurrence("result", definition.id, 1, System.currentTimeMillis(),
            threadId = "original-thread", turnId = "original-turn",
            executionState = AutomationExecutionState.SUCCEEDED, deliveryState = AutomationDeliveryState.HANDOFF_ACCEPTED,
            resultSummary = "**Result belongs only in the conversation**")
        var opened: Pair<String, String>? = null
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                AutomationDetail(definition, listOf(run), null, {}, {}, {}, {}, {}, {},
                    onOpenConversation = { thread, turn -> opened = thread to turn })
            }
        }
        compose.onNodeWithText(label(R.string.automation_notification_sent)).assertExists()
        compose.onNodeWithText("Rendered instructions", substring = true).assertIsDisplayed()
        compose.onNodeWithText(definition.instruction).assertDoesNotExist()
        compose.onNodeWithText(run.resultSummary).assertDoesNotExist()
        compose.onNodeWithText("Result belongs only in the conversation", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.automation_open_conversation)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("original-thread" to "original-turn", opened) }
        screenshot("conversation-link-light.png")
    }

    @Test fun occurrenceWithoutATurnDoesNotOfferAnEmptyConversation() {
        val run = AutomationOccurrence("not-started", definition.id, 1, System.currentTimeMillis(),
            executionState = AutomationExecutionState.SKIPPED, resultSummary = "An unbound record")
        var opened = false
        compose.setContent {
            DroidanceTheme(AppThemeMode.DARK) {
                AutomationDetail(definition, listOf(run), null, {}, {}, {}, {}, {}, {},
                    onOpenConversation = { _, _ -> opened = true })
            }
        }
        compose.onNodeWithContentDescription(label(R.string.automation_open_conversation)).assertDoesNotExist()
        compose.onNodeWithText(run.resultSummary).assertDoesNotExist()
        compose.runOnIdle { assertFalse(opened) }
    }

    @Test fun editorPreviewsMarkdownAndProtectsUnsavedChanges() {
        var left = false
        val draft = AutomationDraft("Fixture task", "**Rendered instructions**", schedule, titleWasGenerated = true)
        compose.setContent {
            DroidanceTheme(AppThemeMode.DARK) { AutomationEditor(draft, null, AutomationAlarmPrecision.EXACT, null,
                { left = true }, { _, _, _ -> }) }
        }
        compose.onNodeWithText("Rendered instructions", substring = true).assertIsDisplayed()
        compose.onNodeWithText(draft.instruction).assertDoesNotExist()
        screenshot("editor-dark.png")
        compose.onNodeWithText(label(R.string.common_edit)).performClick()
        compose.onNodeWithText(draft.instruction).assertExists()
        compose.onNode(hasSetTextAction() and hasText(draft.title)).performClick().performTextReplacement("Changed name")
        compose.onNodeWithText(label(R.string.automation_save)).assertIsDisplayed()
        screenshot("editor-keyboard.png", fullScreen = true)
        compose.onNodeWithContentDescription(label(R.string.common_back)).performClick()
        compose.onNodeWithText(label(R.string.form_unsaved_title)).assertIsDisplayed()
        compose.runOnIdle { assertFalse(left) }
        compose.onNodeWithText(label(R.string.form_keep_editing)).performClick()
        compose.onNodeWithText("Changed name").assertExists()
    }

    @Test fun advancedScheduleKeepsExistingSettingsAndRestoresRelativeInput() {
        val restored = StateRestorationTester(compose)
        var selected: AutomationSchedule? = null
        var delay: Long? = null
        restored.setContent {
            DroidanceTheme(AppThemeMode.DARK) { Surface { Column { AutomationScheduleFields(schedule) { s, d -> selected = s; delay = d } } } }
        }
        compose.onNodeWithText(label(R.string.automation_zone_label)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.automation_after_enable)).performClick()
        compose.onNode(hasSetTextAction() and hasText(label(R.string.automation_delay_minutes))).performTextReplacement("17")
        compose.runOnIdle { assertEquals(17L, delay); assertNotNull(selected) }
        restored.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("17").assertExists()
        compose.runOnIdle { assertEquals(17L, delay) }
        compose.onNodeWithText(label(R.string.automation_advanced)).performClick()
        compose.onNodeWithText(label(R.string.automation_zone_label)).assertExists()
    }

    @Test fun pausedEditorSavesWithoutOfferingToEnableThePlan() {
        val paused = definition.copy(finishedAtMs = 0L)
        val draft = AutomationDraft(paused.title, paused.instruction, schedule, titleWasGenerated = false, editingAutomationId = paused.id)
        val saved = mutableStateOf(false)
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) { AutomationEditor(draft, paused, AutomationAlarmPrecision.EXACT, null,
                {}, { _, _, _ -> saved.value = true }) }
        }
        compose.onNodeWithText(label(R.string.automation_save)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.common_save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(saved.value) }
        screenshot("editor-light.png")
    }
    @Test fun refinementReceivesManualEditsAndTheOriginalRequest() {
        val draft = AutomationDraft("Fixture task", "Original work", schedule,
            originalRequest = "Original request", clarification = "When should this run?")
        var received: AutomationDraft? = null
        var answer: String? = null
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                AutomationEditor(draft, null, AutomationAlarmPrecision.EXACT, null,
                    {}, { _, _, _ -> }, onRefine = { current, reply -> received = current; answer = reply })
            }
        }
        compose.onNode(hasSetTextAction() and hasText(draft.title)).performTextReplacement("Edited task")
        compose.onNode(hasSetTextAction() and hasText(label(R.string.automation_refine_label))).performTextReplacement("Tomorrow at noon")
        compose.onNodeWithText(label(R.string.automation_refine_action)).performClick()
        compose.runOnIdle {
            assertEquals("Edited task", received?.title)
            assertEquals(draft.originalRequest, received?.originalRequest)
            assertEquals(schedule, received?.schedule)
            assertEquals("Tomorrow at noon", answer)
        }
        screenshot("refinement-light.png")
    }

    private fun screenshot(name: String, fullScreen: Boolean = false) {
        val output = File(context.getExternalFilesDir(null), "automation-ui/$name")
        output.parentFile!!.mkdirs()
        val bitmap = if (fullScreen) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

}
