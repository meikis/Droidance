// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import com.droidance.ui.execution.TurnPresentationUiState
import com.droidance.ui.screens.followLatestItemToBottom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real touch/fling regression using the production card, nested connection and layout follower. */
class ChatHistoryScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var outer: LazyListState
    private lateinit var controller: ChatScrollController
    private var userScrollNotifications = 0
    private val task = mutableStateOf(presentation())

    @Test fun expandedLogAtBottomDoesNotResumeFollowingAndDraggingPastItsTopMovesConversation() {
        showConversation()
        compose.onNodeWithTag("execution-records-toggle").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(controller.followingLatest) }
        compose.onNodeWithTag("execution-process-history").performTouchInput { swipeDown() }
        compose.waitForIdle()
        var anchor = 0 to 0
        compose.runOnIdle {
            assertFalse(controller.followingLatest)
            assertEquals("Focus/keyboard handling must run once per drag", 1, userScrollNotifications)
            assertTrue("Dragging over the log must move the outer conversation", outer.canScrollForward)
            anchor = outer.firstVisibleItemIndex to outer.firstVisibleItemScrollOffset
            task.value = task.value.copy(finalSummary = "A longer final response.\n".repeat(30))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(anchor, outer.firstVisibleItemIndex to outer.firstVisibleItemScrollOffset) }
    }

    @Test fun longLogScrollsWithoutBottomPinningAndStillVirtualizesItsRows() {
        showConversation()
        compose.onNodeWithTag("execution-records-toggle").performClick()
        compose.onNodeWithText("Progress 0").assertIsDisplayed()
        compose.onNodeWithText("Progress 1999").assertDoesNotExist()
        compose.onNodeWithTag("execution-process-history").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText("Progress 0").assertDoesNotExist()
        compose.runOnIdle { assertFalse(controller.followingLatest) }
        val rendered = compose.onAllNodes(hasText("Progress ", substring = true)).fetchSemanticsNodes().size
        assertTrue("Long log must retain bounded composition, got $rendered", rendered in 1..50)
        val before = compose.onAllNodes(hasText("Progress ", substring = true)).fetchSemanticsNodes()
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text }
        compose.runOnIdle { task.value = task.value.copy(finalSummary = "Updated final response") }
        compose.waitForIdle()
        val after = compose.onAllNodes(hasText("Progress ", substring = true)).fetchSemanticsNodes()
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text }
        assertEquals(before, after)
    }

    private fun showConversation() {
        compose.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val scroll = remember(list) { ChatScrollController(true, { !list.canScrollForward },
                    onUserScroll = { userScrollNotifications++ }) }
                outer = list
                controller = scroll
                LaunchedEffect(scroll.followingLatest) {
                    if (scroll.followingLatest) list.followLatestItemToBottom(60) { scroll.followingLatest }
                }
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("conversation")
                    .nestedScroll(scroll)) {
                    items(60) { Text("Retained message $it\n".repeat(4), Modifier.fillMaxWidth()) }
                    item(key = "latest") {
                        ChatBubble("", false, "", turnPresentation = task.value,
                            onExecutionLayoutChange = scroll::readHistory)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun presentation(): TurnPresentationUiState {
        // A long log of tool steps; the model's own replies would also render in the conversation.
        val calls = (0 until 2_000).map {
            ToolActivityReadModel("item-$it", "turn", it.toLong(), true, "round-$it", "call-$it", null, null,
                ToolCallSource.MODEL_REQUESTED, ToolPresentationSnapshot("tool.screen_act", ToolPresentationCategory.PRIMARY,
                    ResultImageRole.NONE, actionTitle = "Progress $it"),
                ToolActivityState.UNVERIFIED, null, null, true, 1L, 2L)
        }
        val activity = TurnActivityReadModel("turn", TurnReadModelState.TERMINAL, calls, calls.size, null,
            emptyList(), 1L, 2L, 1L)
        return TurnPresentationUiState("turn", 1L, "Opening", "", "Final response", activity, emptyMap(),
            null, true, terminalStatus = "done")
    }
}
