// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.droidance.ui.theme.DroidanceTheme
import com.droidance.settings.AppThemeMode
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import com.droidance.core.agent.event.TurnActivityReadModel
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.ui.execution.TurnPresentationUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolated rendering fixtures; no Provider requests or app navigation. */
class TakeoverPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val opening = "好的，我先帮你查找，到确认页面再告诉你。"
    private val handoff = "当前页面需要身份验证，请完成验证后点继续，我会接着处理。"

    @Test fun modelGeneratedTitleKeepsTheExistingTakeoverButtonAndLocalizedFallback() {
        val title = "请在当前应用完成验证"
        val base = task(TurnReadModelState.WAITING_FOR_USER, handoff)
        val state = mutableStateOf(base.copy(activity = base.activity.copy(entries = listOf(
            ToolActivityReadModel("human-call", "handoff", 1, true, "round", "human-call", null, null,
                ToolCallSource.MODEL_REQUESTED, ToolPresentationSnapshot("tool.ask_human",
                    ToolPresentationCategory.SUPPORTING, ResultImageRole.NONE, actionTitle = title),
                ToolActivityState.VERIFIED, null, null, false, 1, 2),
            LifecycleActivityReadModel("request", "handoff", 3, true,
                LifecycleActivityKind.USER_INTERVENTION, null, null, "human-call", null)
        ))))
        var opens = 0
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.DARK) {
                ChatBubble(text = "", isUser = false, timestamp = "", turnPresentation = state.value,
                    canOpenVirtualSpace = true, onOpenVirtualSpace = { opens++ })
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_takeover_handle)).assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, opens) }
        compose.onNodeWithText("进入虚拟空间").assertDoesNotExist()
        capture("takeover-model-generated-title.png")
        compose.runOnIdle { state.value = base }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_takeover_title)).assertIsDisplayed()
    }

    @Test fun handoffAppearsOnceBelowLogAndRestoredPauseSurvivesWithoutLiveState() {
        val presentation = mutableStateOf(task(TurnReadModelState.WAITING_FOR_USER, handoff))
        val streaming = mutableStateOf(true)
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.DARK) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 48.dp)) {
                        ChatBubble(text = "$opening\n\n$handoff", isUser = false, timestamp = "",
                            isStreaming = streaming.value, isPaused = false, statusText = "输入文字",
                            turnPresentation = presentation.value,
                            canOpenVirtualSpace = true)
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onAllNodesWithText(opening).assertCountEquals(1)
        compose.onAllNodesWithText(handoff).assertCountEquals(1)
        compose.onNodeWithText("$opening\n\n$handoff").assertDoesNotExist()
        val openingBounds = compose.onNodeWithText(opening).fetchSemanticsNode().boundsInRoot
        val logBounds = compose.onNodeWithTag("execution-process-status-row").fetchSemanticsNode().boundsInRoot
        val handoffBounds = compose.onNodeWithText(handoff).fetchSemanticsNode().boundsInRoot
        assertTrue(openingBounds.bottom <= logBounds.top)
        assertTrue(logBounds.bottom <= handoffBounds.top)
        compose.onNodeWithText(context.getString(R.string.chat_status_running), substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_status_paused), substring = true).assertDoesNotExist()
        compose.onNodeWithText("输入文字").assertDoesNotExist()
        capture("handoff-below-log.png")
        compose.runOnIdle { streaming.value = false }
        compose.onNodeWithTag("execution-process-status-row").assertIsDisplayed()
        compose.onNodeWithText(handoff).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_takeover_handle)).assertIsEnabled()
        compose.runOnIdle {
            streaming.value = true
            presentation.value = task(TurnReadModelState.RUNNING, handoff)
        }
        compose.onNodeWithText(handoff).assertDoesNotExist()
        compose.runOnIdle {
            streaming.value = false
            presentation.value = task(TurnReadModelState.TERMINAL, handoff).copy(
                finalSummary = "任务已停止，尚未到达确认页面。", terminalStatus = "cancelled")
        }
        compose.onNodeWithText(context.getString(R.string.chat_status_paused), substring = true).assertDoesNotExist()
        compose.onNodeWithText("任务已停止，尚未到达确认页面。").assertIsDisplayed()
    }

    @Test fun runtimeRecoveryReasonHasItsOwnHandoffBlockBeforeDurableRequestArrives() {
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.DARK) {
                ChatBubble(text = opening, isUser = false, timestamp = "", isStreaming = true,
                    isPaused = true, liveIntervention = handoff,
                    turnPresentation = task(TurnReadModelState.RUNNING, ""))
            }
        }
        compose.onAllNodesWithText(opening).assertCountEquals(1)
        compose.onAllNodesWithText(handoff).assertCountEquals(1)
        val log = compose.onNodeWithTag("execution-process-status-row")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(log.bottom <= compose.onNodeWithText(handoff).fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun takeoverRendersMarkdownAsBodyBeforeItsCompactActionCard() {
        val markdown = "订单已准备好。\n\n- **商品**：一杯饮品\n- **费用**：合计 ¥23.7\n\n最后一步请你操作。"
        var opened = 0
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.DARK) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 48.dp)) {
                        ChatBubble(text = opening, isUser = false, timestamp = "",
                            isStreaming = false,
                            turnPresentation = task(TurnReadModelState.WAITING_FOR_USER, markdown),
                            canOpenVirtualSpace = true, onOpenVirtualSpace = { opened++ })
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(markdown).assertDoesNotExist()
        compose.onNodeWithText("**商品**", substring = true).assertDoesNotExist()
        val item = compose.onNodeWithText("商品：一杯饮品", substring = true).assertIsDisplayed()
        val title = compose.onNodeWithText(context.getString(R.string.chat_takeover_title)).assertIsDisplayed()
        assertTrue(item.fetchSemanticsNode().boundsInRoot.bottom <= title.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText(context.getString(R.string.chat_takeover_handle)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.chat_takeover_handle)).performClick()
        assertTrue(opened == 1)
        capture("takeover-markdown-body.png")
    }

    @Test fun userPauseKeepsVirtualSpaceEntryWithoutAModelInterventionMessage() {
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.DARK) {
                ChatBubble(text = opening, isUser = false, timestamp = "", isStreaming = false,
                    turnPresentation = task(TurnReadModelState.PAUSED_USER, ""),
                    canOpenVirtualSpace = true)
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.chat_takeover_handle)).assertIsEnabled()
        compose.onNodeWithTag("execution-task-progress").assertDoesNotExist()
    }

    @Test fun statusReplacesPreviousRoundAndDisappearsOnPauseCompletionAndTurnChange() {
        val initial = task(TurnReadModelState.RUNNING, "")
        val presentation = mutableStateOf(initial)
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.DARK) {
                ChatBubble(text = opening, isUser = false, timestamp = "", isStreaming = true,
                    turnPresentation = presentation.value)
            }
        }
        val first = "已经看到当前页面，接下来查看下方内容。"
        val second = "已经看到下方内容，接下来打开详情。"
        compose.runOnIdle { presentation.value = initial.copy(activity = initial.activity.copy(publicProgressHistory = listOf(PublicProgressReadModel("first", 1L, first, listOf("first"))))) }
        compose.onNodeWithText(first).assertIsDisplayed()
        compose.runOnIdle { presentation.value = initial.copy(activity = initial.activity.copy(publicProgressHistory = listOf(PublicProgressReadModel("first", 1L, first, listOf("first")), PublicProgressReadModel("second", 2L, second, listOf("second"))))) }
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(second).assertIsDisplayed()
        capture("latest-round-progress.png")
        compose.runOnIdle { presentation.value = presentation.value.copy(
            activity = presentation.value.activity.copy(state = TurnReadModelState.PAUSED_USER)) }
        compose.onNodeWithText(second).assertDoesNotExist()
        compose.runOnIdle { presentation.value = presentation.value.copy(
            activity = presentation.value.activity.copy(state = TurnReadModelState.RUNNING)) }
        compose.onNodeWithText(second).assertIsDisplayed()
        compose.runOnIdle { presentation.value = presentation.value.copy(finalSummary = "已完成查看。",
            activity = presentation.value.activity.copy(state = TurnReadModelState.TERMINAL)) }
        compose.onNodeWithText(second).assertDoesNotExist()
        compose.onNodeWithText("已完成查看。").assertIsDisplayed()
        compose.runOnIdle { presentation.value = initial.copy(turnId = "next-turn",
            activity = initial.activity.copy(turnId = "next-turn")) }
        compose.onNodeWithText(second).assertDoesNotExist()
        compose.onNodeWithText("已完成查看。").assertDoesNotExist()
    }

    private fun task(state: TurnReadModelState, intervention: String) = TurnPresentationUiState(

        turnId = "handoff", startedAtMs = 0L, opening = opening, intervention = intervention,
        finalSummary = "", toolDetails = emptyMap(), resultCard = null, hasDeviceExecution = true,
        activity = TurnActivityReadModel("handoff", state, emptyList(), 4, null, emptyList(), 0L, null, null))

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
