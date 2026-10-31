// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class FlatMarkdownUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun flatMarkdown_displays_tilde_range_correctly() {
        compose.setContent {
            FlatMarkdown("5000~8000之间")
        }
        compose.onNodeWithText("5000~8000之间").assertIsDisplayed()
    }

    @Test
    fun flatMarkdown_displays_dollar_and_brackets_correctly() {
        compose.setContent {
            FlatMarkdown("价格$100以及<Enter>")
        }
        compose.onNodeWithText("价格$100以及<Enter>").assertIsDisplayed()
    }
}
