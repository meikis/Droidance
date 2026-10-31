// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.settings.AppThemeMode
import com.droidance.ui.screens.followLatestItemToBottom
import com.droidance.ui.theme.DroidanceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises measured rows and viewport changes, independently of a Provider. */
class ChatAutoScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shortFinalReplyArrivingAfterProcessingEndsStaysFullyVisible() {
        val reply = mutableStateOf("")
        val composerHeight = mutableStateOf(96.dp)
        setConversation(reply, composerHeight)
        compose.waitForIdle()
        // The durable reply may arrive after the processing flag has settled.
        compose.runOnIdle { reply.value = "收到" }
        compose.waitForIdle()
        capture("short-final-reply.png")
        assertLatestRowFullyVisible()
        compose.onNodeWithText("收到").assertIsDisplayed()
    }

    @Test fun composerResizeKeepsTheWholeFinalReplyAboveTheInputArea() {
        val reply = mutableStateOf("A final reply whose footer must remain inside the viewport.")
        val composerHeight = mutableStateOf(96.dp)
        setConversation(reply, composerHeight)
        compose.waitForIdle()
        assertLatestRowFullyVisible()
        compose.runOnIdle { composerHeight.value = 264.dp }
        compose.waitForIdle()
        capture("composer-resized-final-reply.png")
        assertLatestRowFullyVisible()
    }

    private fun setConversation(reply: State<String>, composerHeight: State<Dp>) {
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.SYSTEM) {
                val list = rememberLazyListState()
                // Same production follower; no text-length or processing-key retry.
                LaunchedEffect(list) { list.followLatestItemToBottom(2) }
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat-viewport"),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "history") { Text("Retained conversation history.\n".repeat(80)) }
                        item(key = "new-input") {
                            ChatBubble(text = "A new message after returning to this conversation.",
                                isUser = true, timestamp = "fixture")
                        }
                        item(key = "reply") {
                            Box(Modifier.fillMaxWidth().testTag("latest-reply-row")) {
                                ChatBubble(text = reply.value, isUser = false, timestamp = "fixture")
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(composerHeight.value).testTag("composer-area")) {
                        Text("Composer fixture")
                    }
                }
            }
        }
    }

    private fun assertLatestRowFullyVisible() {
        val row = compose.onNodeWithTag("latest-reply-row", useUnmergedTree = true).fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("chat-viewport", useUnmergedTree = true).fetchSemanticsNode()
        val composer = compose.onNodeWithTag("composer-area", useUnmergedTree = true).fetchSemanticsNode()
        // Use un-clipped measured dimensions; assertIsDisplayed alone accepts a partial row.
        val bottom = row.positionInRoot.y + row.size.height
        val viewportBottom = viewport.positionInRoot.y + viewport.size.height
        assertTrue("Reply bottom $bottom must fit in viewport bottom $viewportBottom", bottom <= viewportBottom + 1f)
        assertTrue("Reply bottom $bottom must stay above Composer ${composer.positionInRoot.y}", bottom <= composer.positionInRoot.y + 1f)
        assertTrue("The reply must not start above the viewport", row.positionInRoot.y >= viewport.positionInRoot.y)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
