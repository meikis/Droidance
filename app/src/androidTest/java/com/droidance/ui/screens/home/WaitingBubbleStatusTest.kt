// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import com.droidance.ui.theme.DroidanceTheme
import com.droidance.settings.AppThemeMode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.core.agent.event.PublicProgressReadModel
import com.droidance.core.agent.event.TurnActivityReadModel
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.ui.execution.TurnPresentationUiState
import org.junit.Rule
import org.junit.Test

class WaitingBubbleStatusTest {
    private val foregroundState get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<com.droidance.DroidanceApp>().foregroundState
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pausedHostReleasesExternalIslandAndRecreationRecovers() {
        val scenario = compose.activityRule.scenario
        scenario.onActivity { org.junit.Assert.assertTrue(foregroundState.isInForeground) }
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity { org.junit.Assert.assertFalse(foregroundState.isInForeground) }
        scenario.moveToState(Lifecycle.State.CREATED)
        org.junit.Assert.assertFalse(foregroundState.isInForeground)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.recreate()
        scenario.onActivity { org.junit.Assert.assertTrue(foregroundState.isInForeground) }
    }

    @Test fun waitingChatActivationAndExecutionHaveDistinctPresentation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val presentation = mutableStateOf<TurnPresentationUiState?>(null)
        val startedAt = System.currentTimeMillis()
        var stopCount = 0
        val reply = mutableStateOf("")
        val streaming = mutableStateOf(true)
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.SYSTEM) {
                ChatBubble(text = reply.value, isUser = false, timestamp = "", isThinking = true,
                    isStreaming = streaming.value, startedAtMs = startedAt, turnPresentation = presentation.value,
                    onStop = { stopCount++ })
            }
        }
        val stop = context.getString(R.string.app_takeover_status_stop)
        compose.onNodeWithContentDescription(stop).assertDoesNotExist()
        compose.onAllNodesWithTag("response-wave-dot", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithTag("waiting-response-status").assertDoesNotExist()
        compose.onNodeWithTag("waiting-response-elapsed").assertDoesNotExist()
        capture("v4-waiting.png")
        compose.runOnIdle {
            presentation.value = TurnPresentationUiState(

                turnId = "waiting-fixture", startedAtMs = startedAt, opening = "",
                intervention = "", finalSummary = "", toolDetails = emptyMap(), resultCard = null,
                hasDeviceExecution = false, androidActivated = true,
                activity = TurnActivityReadModel("waiting-fixture", TurnReadModelState.RUNNING,
                    emptyList(), 0, null, emptyList(), startedAt, null, null)
            )
        }
        compose.onNodeWithContentDescription(stop).assertIsDisplayed()
        compose.onAllNodesWithTag("response-wave-dot", useUnmergedTree = true).assertCountEquals(3)
        capture("execution-without-opening.png")
        compose.runOnIdle {
            presentation.value = presentation.value!!.copy(activity = presentation.value!!.activity.copy(
                operationCount = 2, publicProgressHistory = listOf(PublicProgressReadModel("progress", 1L, "Live event progress without an opening", listOf("progress")))))
        }
        compose.onNodeWithText(context.getString(R.string.chat_process_record_count, 2), substring = true).assertIsDisplayed()
        compose.onNodeWithText("Live event progress without an opening").assertIsDisplayed()
        capture("execution-live-events-without-opening.png")
        compose.runOnIdle {
            presentation.value = presentation.value!!.copy(androidActivated = false)
            reply.value = "Chat response"
        }
        compose.onNodeWithText("Chat response").assertIsDisplayed()
        compose.onNodeWithContentDescription(stop).assertDoesNotExist()
        compose.runOnIdle {
            reply.value = ""
            presentation.value = presentation.value!!.copy(androidActivated = true,
                activity = presentation.value!!.activity.copy(operationCount = 0, publicProgressHistory = emptyList()))
        }
        compose.onNodeWithContentDescription(stop).assertIsDisplayed()
        compose.runOnIdle {
            presentation.value = presentation.value!!.copy(opening = "Opening fixture", finalSummary = "")
        }
        compose.onNodeWithContentDescription(stop).assertIsDisplayed().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, stopCount) }
        val resultBounds = compose.onNodeWithText("Opening fixture").fetchSemanticsNode().boundsInRoot
        val stopBounds = compose.onNodeWithContentDescription(stop).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(resultBounds.bottom <= stopBounds.top)
        compose.onAllNodesWithTag("response-wave-dot", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText(context.getString(R.string.chat_process_record_count, 0), substring = true)
            .assertIsDisplayed()
        capture("execution-before-first-device-call.png")
        compose.runOnIdle {
            streaming.value = false
            presentation.value = presentation.value!!.copy(finalSummary = "Stopped before first action",
                terminalStatus = "cancelled",
                activity = presentation.value!!.activity.copy(state = TurnReadModelState.TERMINAL))
        }
        compose.onNodeWithContentDescription(stop).assertDoesNotExist()
        compose.onNodeWithText("Stopped before first action").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_process_record_count, 0), substring = true)
            .assertIsDisplayed()
    }

    @Test fun queuedConversationHasAStopControlWithoutCreatingExecutionLogsForChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queued = mutableStateOf(true)
        var stops = 0
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.SYSTEM) {
                ChatBubble(text = "", isUser = false, timestamp = "", isThinking = true,
                    isStreaming = true, isQueued = queued.value,
                    statusText = context.getString(R.string.chat_status_queued),
                    startedAtMs = System.currentTimeMillis(), onStop = { stops++ })
            }
        }
        val stop = context.getString(R.string.app_takeover_status_stop)
        compose.onNodeWithText(context.getString(R.string.chat_status_queued)).assertDoesNotExist()
        compose.onNodeWithContentDescription(stop).assertIsDisplayed().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, stops) }
        compose.onNodeWithText(context.getString(R.string.chat_view_process), substring = true).assertDoesNotExist()
        capture("queued-conversation-stop.png")
        compose.runOnIdle { queued.value = false }
        compose.onNodeWithContentDescription(stop).assertDoesNotExist()
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

}
