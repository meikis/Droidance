// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.settings.AppThemeMode
import com.droidance.ui.theme.DroidanceTheme
import com.droidance.ui.viewmodel.ComposerModelOption
import com.droidance.ui.viewmodel.ComposerPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises attachment identity, popup dismissal and animation disposal through production UI. */
class ChatPolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun individualRemovalRetainsOtherImagesAndSixthImageIsReachable() {
        val initial = (1..6).map { index ->
            val file = File(context.cacheDir, "ui-polish-image-$index.png")
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(80 + index * 20, 115, 140))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            Uri.fromFile(file)
        }
        val images = mutableStateOf(initial)
        val dark = mutableStateOf(false)
        compose.setContent {
            DroidanceTheme(if (dark.value) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                Column(Modifier.fillMaxWidth()) {
                    PendingImageStrip(images.value) { images.value = images.value - it }
                    ChatInputBar(emptyList(),
                        isProcessing = false, onSendMessage = { _, _, _ -> true })
                }
            }
        }
        val removeSecond = context.getString(R.string.composer_remove_image, 2)
        compose.onNodeWithContentDescription(removeSecond).performClick()
        compose.runOnIdle { assertEquals(initial.filterIndexed { index, _ -> index != 1 }, images.value) }
        val removeLast = context.getString(R.string.composer_remove_image, 5)
        compose.onNodeWithTag("composer-pending-images").performScrollToIndex(4)
        compose.onNodeWithContentDescription(removeLast).assertIsDisplayed()
        capture("ui-polish-attachments-light.png")
        compose.runOnIdle { dark.value = true }
        capture("ui-polish-attachments-dark.png")
        compose.onNodeWithContentDescription(removeLast).performClick()
        compose.runOnIdle { assertEquals(listOf(initial[0], initial[2], initial[3], initial[4]), images.value) }
    }

    @Test fun selectingAModelClosesPopupAndItCanBeOpenedAgain() {
        val first = ComposerModelOption("a", "Provider A", "Model A")
        val second = ComposerModelOption("b", "Provider B", "Model B")
        val selected = mutableStateOf(first)
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ComposerSelectionControls(listOf(first, second), ComposerPreferences(selected.value), true,
                        {}, {}, { selected.value = requireNotNull(it) })
                }
            }
        }
        compose.onNodeWithText("Model A").performClick()
        compose.onNodeWithText("Provider B").performClick()
        compose.onNodeWithText("Provider B").assertDoesNotExist()
        compose.onNodeWithText("Model B").assertIsDisplayed().performClick()
        compose.onNodeWithText("Provider A").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.composer_picker_close)).performClick()
        compose.onNodeWithText("Provider A").assertDoesNotExist()
        compose.runOnIdle { assertEquals(second, selected.value) }
    }

    @Test fun newMessageEntryIsNotReplayedAfterItLeavesComposition() {
        val shown = mutableStateOf(true)
        val isNew = mutableStateOf(false)
        val entered = mutableSetOf("history")
        var entries = 0
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                if (shown.value) {
                    val id = if (isNew.value) "new" else "history"
                    key(id) {
                        ChatBubble(id, true, "", animateEntry = id !in entered,
                            onEntryStarted = { entered += id; entries++ })
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, entries); isNew.value = true }
        compose.runOnIdle { assertEquals(1, entries); shown.value = false }
        compose.runOnIdle { shown.value = true }
        compose.runOnIdle { assertEquals(1, entries) }
        compose.onNodeWithText("new").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
}
