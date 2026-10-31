// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.droidance.ui.screens.followLatestItemToBottom
import com.droidance.ui.screens.rememberConversationTurnFocus
import com.droidance.ui.viewmodel.UIMessage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationTurnFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retainedTurnIsFocusedAfterLoadingAndNewRepliesDoNotPullItToTheBottom() {
        val loaded = mutableStateOf(false)
        val messages = mutableStateOf(emptyList<UIMessage>())
        var firstVisible = -1
        compose.setContent {
            val list = rememberLazyListState()
            var readingHistory by remember { mutableStateOf(true) }
            val pending = rememberConversationTurnFocus(list, messages.value, loaded.value, "turn-12") { readingHistory = it }
            LaunchedEffect(messages.value, pending, readingHistory) {
                if (!pending && !readingHistory && messages.value.isNotEmpty()) list.followLatestItemToBottom(messages.value.lastIndex)
            }
            SideEffect { firstVisible = list.firstVisibleItemIndex }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(messages.value, key = { it.messageId }) { message ->
                    Text(message.text, Modifier.fillMaxWidth().height(120.dp))
                }
            }
        }
        compose.runOnIdle {
            messages.value = (0..35).map { UIMessage("Retained turn $it", true, "fixture", turnId = "turn-$it") }
            loaded.value = true
        }
        compose.onNodeWithText("Retained turn 12").assertIsDisplayed()
        compose.runOnIdle { assertEquals(12, firstVisible) }
        compose.runOnIdle { messages.value = messages.value + UIMessage("New reply", false, "fixture", turnId = "turn-35") }
        compose.runOnIdle { assertEquals(12, firstVisible) }
        compose.onNodeWithText("Retained turn 12").assertIsDisplayed()
    }

    @Test fun unavailableTurnFallsBackToTheRetainedConversation() {
        var resolved: Boolean? = null
        var pending = true
        compose.setContent {
            val list = rememberLazyListState()
            pending = rememberConversationTurnFocus(list, emptyList(), true, "missing-turn") { resolved = it }
            LazyColumn(state = list) {}
        }
        compose.runOnIdle { assertEquals(false, resolved); assertFalse(pending) }
    }
}
