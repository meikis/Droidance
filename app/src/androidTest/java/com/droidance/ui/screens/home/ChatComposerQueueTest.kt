// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.agent.ConversationSubmissionMode
import com.droidance.settings.AppThemeMode
import com.droidance.core.knowledge.skill.SkillInfo
import com.droidance.ui.theme.DroidanceTheme
import com.droidance.ui.viewmodel.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatComposerQueueTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test fun busyComposerStacksMessagesWithDeleteAndSteerActionsAndSendsToQueue() {
        val sent = mutableListOf<Pair<String, ConversationSubmissionMode>>()
        val deleted = mutableListOf<String>()
        val steered = mutableListOf<String>()
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    ChatInputBar(availableSkills = emptyList(), isProcessing = true,
                        queuedMessages = listOf(UIMessage("first queued", true, "", turnId = "first"),
                            UIMessage("second queued", true, "", turnId = "second")),
                        onDeleteQueuedMessage = { deleted += it }, onSteerQueuedMessage = { steered += it },
                        onSendMessage = { text, mode, _ -> sent += text to mode; true })
                }
            }
        }
        compose.onNodeWithText("first queued").assertExists()
        compose.onNodeWithText("second queued").assertExists()
        compose.onAllNodesWithText(label(R.string.composer_steer_queued_label)).assertCountEquals(2)
        repeat(2) { index ->
            val steer = compose.onAllNodesWithContentDescription(label(R.string.composer_steer_queued))[index]
                .assertIsEnabled().fetchSemanticsNode().boundsInRoot
            val delete = compose.onAllNodesWithContentDescription(label(R.string.composer_delete_queued))[index]
                .assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertTrue("Steering must appear before deletion without overlap", steer.right <= delete.left)
        }
        compose.onAllNodesWithContentDescription(label(R.string.composer_delete_queued))[0].performClick()
        compose.onAllNodesWithContentDescription(label(R.string.composer_steer_queued))[1].performClick()
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick().performTextInput("third queued")
        compose.onNodeWithContentDescription(label(R.string.composer_send)).performClick()
        editor.assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("first"), deleted)
            assertEquals(listOf("second"), steered)
            assertEquals(listOf("third queued" to ConversationSubmissionMode.QUEUE), sent)
        }
        // Capture the rendered stack and composer for visual review.
        compose.onRoot().captureToImage().let { image ->
            val bitmap = image.asAndroidBitmap()
            val output = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "queued-composer.png")
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun attachmentsAndSkillSelectionStayInteractiveWhileBusy() {
        val skill = SkillInfo("fixture", "fixture", description = "Input selection fixture", rawContent = "fixture",
            revision = "a".repeat(64), displayNames = mapOf("en" to "Fixture skill", "zh" to "Fixture skill"))
        var sent = ""
        var mode: ConversationSubmissionMode? = null
        var selected = emptyList<com.droidance.core.agent.RequestedSkill>()
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    ChatInputBar(availableSkills = listOf(skill), isProcessing = true,
                        onSendMessage = { text, selectedMode, selections -> sent = text; mode = selectedMode; selected = selections; true })
                }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.composer_attachment)).assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.composer_album)).assertHasClickAction()
        compose.onNodeWithText(label(R.string.composer_camera)).assertHasClickAction()
        compose.onNodeWithText(label(R.string.skills_title)).performClick()
        compose.onNodeWithText("@Fixture skill").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("@Fixture skill", substring = true)
        compose.onNodeWithContentDescription(label(R.string.composer_send)).performClick()
        compose.runOnIdle {
            assertEquals("@Fixture skill", sent)
            assertEquals(ConversationSubmissionMode.QUEUE, mode)
            assertEquals(listOf(com.droidance.core.agent.RequestedSkill("fixture", "a".repeat(64))), selected)
        }
    }
    @Test fun typedMentionOpensTheReferencedSkillFromTheComposer() {
        val skill = SkillInfo("fixture", "fixture", description = "", rawContent = "fixture",
            revision = "a".repeat(64), displayNames = mapOf("en" to "Fixture", "zh" to "Fixture"))
        var opened: SkillInfo? = null
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    ChatInputBar(availableSkills = listOf(skill), onOpenSkill = { opened = it },
                        isProcessing = false, onSendMessage = { _, _, _ -> true })
                }
            }
        }
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick().performTextInput("@Fi")
        compose.onNodeWithText("@Fixture").performClick()
        editor.assertTextContains("@Fixture", substring = true)
        editor.performTouchInput { click(androidx.compose.ui.geometry.Offset(30.dp.toPx(), center.y)) }
        compose.runOnIdle { assertEquals(skill.selectionKey, opened?.selectionKey) }
    }

    @Test fun sentMentionOpensTheReferencedSkill() {
        val skill = SkillInfo("fixture", "fixture", description = "", rawContent = "fixture",
            revision = "a".repeat(64), displayNames = mapOf("en" to "Fixture", "zh" to "Fixture"))
        var opened: SkillInfo? = null
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.LIGHT) {
                ChatBubble(text = "@Fixture", isUser = true, timestamp = "", availableSkills = listOf(skill),
                    onOpenSkill = { opened = it })
            }
        }
        compose.onNodeWithText("@Fixture").performClick()
        compose.runOnIdle { assertEquals(skill.selectionKey, opened?.selectionKey) }
    }

    @Test fun referenceTextIsBlueInTheComposer() {
        val skill = SkillInfo("fixture", "fixture", description = "", rawContent = "fixture",
            revision = "a".repeat(64), displayNames = mapOf("en" to "Fixture", "zh" to "Fixture"))
        compose.setContent {
            DroidanceTheme(themeMode = AppThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    ChatInputBar(availableSkills = listOf(skill), initialText = "@Fixture ",
                        isProcessing = false, onSendMessage = { _, _, _ -> true })
                }
            }
        }
        val bitmap = compose.onNode(hasSetTextAction()).captureToImage().asAndroidBitmap()
        var bluePixels = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            if (blue > red * 1.35f && blue > green * 1.15f && blue > 100) bluePixels++
        }
        assertTrue("The referenced skill must be visibly blue", bluePixels > 5)
    }

}
