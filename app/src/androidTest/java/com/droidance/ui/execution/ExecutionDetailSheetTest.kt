// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

/** Checks actual dialog focus as well as content: an invisible window can still consume every tap. */
@OptIn(ExperimentalMaterial3Api::class)
class ExecutionDetailSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf(false)
    private var receipt by mutableStateOf("Initial receipt")
    private var dismissals = 0
    private var backgroundClicks = 0
    private lateinit var sheetState: SheetState
    private lateinit var sheetScope: CoroutineScope

    @Test fun selection_starts_visible_and_a_silent_hide_removes_the_window() {
        showFixture()
        openDetails()
        compose.runOnIdle { assertEquals(SheetValue.Expanded, sheetState.currentValue) }
        // Deliberately bypass ModalBottomSheet's onDismissRequest, as an interrupted transition can.
        compose.runOnIdle { sheetScope.launch { sheetState.hide() } }
        assertReleased()
        assertEquals(1, dismissals)
        compose.onNodeWithTag("background-action").performClick()
        assertEquals(1, backgroundClicks)
    }

    @Test fun back_and_content_refresh_release_focus_on_every_reopen() {
        showFixture()
        repeat(5) { index ->
            openDetails()
            compose.runOnIdle { receipt = "Receipt $index" }
            compose.onNodeWithTag("sheet-content").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertReleased()
        }
        assertEquals(5, dismissals)
    }

    @Test fun an_interrupted_hide_can_finish_or_reopen_without_leaving_a_hidden_window() {
        showFixture()
        openDetails()
        val firstState = sheetState
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { sheetScope.launch { sheetState.hide() } }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { sheetScope.launch { sheetState.show() } }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("sheet-content").assertIsDisplayed()
            assertEquals(0, dismissals)
            compose.runOnIdle { sheetScope.launch { sheetState.hide() } }
            compose.mainClock.advanceTimeBy(400)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertReleased()
        openDetails()
        assertNotSame(firstState, sheetState)
        compose.runOnIdle { assertEquals(SheetValue.Expanded, sheetState.currentValue) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertReleased()
        assertEquals(2, dismissals)
    }

    private fun showFixture() {
        compose.setContent {
            MaterialTheme {
                Column {
                    Button(onClick = { selected = true }, modifier = Modifier.testTag("open-details")) {
                        Text("Open details")
                    }
                    Button(onClick = { backgroundClicks++ }, modifier = Modifier.testTag("background-action")) {
                        Text("Background action")
                    }
                }
                if (selected) {
                    sheetState = rememberExecutionDetailSheetState()
                    sheetScope = rememberCoroutineScope()
                    ExecutionDetailSheet(onDismissRequest = { dismissals++; selected = false }, sheetState = sheetState) {
                        Column(Modifier.fillMaxWidth().height(240.dp).testTag("sheet-content")) {
                            Text(receipt)
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
    }

    private fun openDetails() {
        compose.onNodeWithTag("open-details").performClick()
        compose.onNodeWithTag("sheet-content").assertIsDisplayed()
        compose.waitUntil(5_000) { !compose.activity.hasWindowFocus() }
    }

    private fun assertReleased() {
        compose.onNodeWithTag("sheet-content").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
    }
}
