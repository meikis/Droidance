// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.settings.AppThemeMode
import com.droidance.ui.theme.DroidanceTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses synthetic reading fixtures; never sends a task or changes a saved conversation. */
class ReadingPolishTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedParagraphKeepsItsPositionAcrossStreamingSyntaxChanges() {
        val content = mutableStateOf("Stable paragraph.\n\nLive\n")
        compose.setContent {
            DroidanceTheme(AppThemeMode.DARK) {
                Surface { FlatMarkdown(content.value, Modifier.width(280.dp).padding(12.dp), streaming = true) }
            }
        }
        val initial = compose.onNodeWithText("Stable paragraph.").fetchSemanticsNode().boundsInRoot
        for (suffix in listOf("**bold", "**bold**\n\n```text\nline", "**bold**\n\n```text\nline\n```\n\nDone.")) {
            compose.runOnIdle { content.value = "Stable paragraph.\n\nLive\n$suffix" }
            compose.onNodeWithText("Stable paragraph.").assertIsDisplayed()
            assertEquals(initial, compose.onNodeWithText("Stable paragraph.").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun longTableAndCodeRemainScrollableAndReadable() {
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                Surface(Modifier.width(220.dp)) {
                    FlatMarkdown("| Product name | Price | Details |\n| --- | --- | --- |\n| A long product title | 123.00 | More details here |\n\n```text\nabcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz\n```")
                }
            }
        }
        val scrollable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        scrollable.assertCountEquals(2)
        for (index in 0..1) {
            scrollable[index].performTouchInput { swipeLeft() }
            val range = scrollable[index].fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(range.value() > 0f)
        }
    }

    @Test fun appendedEmojiNeverSplitsAJoinedGrapheme() {
        val family = "👨‍👩‍👧‍👦"
        val text = "A${family}B"
        for (offset in 2 until 1 + family.length) assertEquals(1, graphemeBoundaryBefore(text, offset))
        assertEquals(1 + family.length, graphemeBoundaryBefore(text, 1 + family.length))
    }

    @Test fun readingHierarchyRendersInBothThemes() {
        val theme = mutableStateOf(AppThemeMode.DARK)
        compose.setContent {
            DroidanceTheme(theme.value) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Droidance", style = MaterialTheme.typography.titleLarge)
                        FlatMarkdown("## 任务结果 · Task results\n\n内容已经整理好了。The result is ready to read.\n\n### 核对信息\n\n1. 保留清晰的阅读层级。\n2. 已显示的文字保持稳定。\n\n| 项目 | 状态 |\n| --- | --- |\n| 阅读排版 | 已更新 |\n| 交互过渡 | 已更新 |\n\n```text\nReady to continue.\n```")
                    }
                }
            }
        }
        for (mode in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            compose.runOnIdle { theme.value = mode }
            compose.onNodeWithText("任务结果 · Task results").assertIsDisplayed()
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-polish").apply { mkdirs() }
            File(directory, "reading-${mode.name.lowercase()}.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
