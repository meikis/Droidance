// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.config.TokenUsageTotals
import com.droidance.core.agent.event.TurnActivityReadModel
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.core.model.llm.ModelRequestUsage
import com.droidance.settings.AppThemeMode
import com.droidance.ui.execution.TurnPresentationUiState
import com.droidance.ui.theme.DroidanceTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

class ChatTokenUsageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun replyFooterOpensCompactDetailsAndFollowsTheAppTheme() {
        val usage = TokenUsageTotals().addRequest(ModelRequestUsage("p", "m", 1_234_567, 201_234, 900_000))
        val show = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val durable = mutableStateOf(false)
        val presentation = TurnPresentationUiState(
            turnId = "turn", startedAtMs = 1_000, opening = "", intervention = "",
            finalSummary = "已整理相关结果。", hasDeviceExecution = true, terminalStatus = "done",
            toolDetails = emptyMap(), resultCard = null,
            activity = TurnActivityReadModel("turn", TurnReadModelState.TERMINAL, emptyList(), 4,
                null, emptyList(), 1_000, 29_000, 28_000)
        )
        compose.setContent {
            DroidanceTheme(if (dark.value) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                Column(Modifier.width(320.dp).padding(12.dp)) {
                    ChatBubble("已整理相关结果。", false, "10:25", tokenUsage = usage,
                        turnPresentation = presentation.takeIf { durable.value },
                        onOpenTokenUsage = { show.value = true })
                }
                if (show.value) TokenUsageSheet(usage, conversation = false, onDismiss = { show.value = false })
            }
        }
        compose.onNodeWithText("1.44M tokens").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-token-usage-total").assertTextEquals("1.44M tokens")
        compose.onNodeWithText("1.23M").assertIsDisplayed()
        compose.onNodeWithText("201.2K").assertIsDisplayed()
        compose.onNodeWithText("900.0K").assertIsDisplayed()
        capture("chat-token-details-light.png")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithText("1.23M").assertIsDisplayed()
        capture("chat-token-details-dark.png")
        compose.onNodeWithContentDescription(context.getString(R.string.common_close)).performClick()
        compose.onNodeWithTag("chat-token-usage-sheet").assertDoesNotExist()
        compose.onNodeWithText("1.44M tokens").assertIsDisplayed()
        capture("chat-token-footer-dark.png", sheet = false)
        compose.runOnIdle { durable.value = true }
        compose.onNodeWithText("1.44M tokens").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-token-usage-total").assertTextEquals("1.44M tokens")
    }

    @Test fun anOpenSheetUpdatesWhenUsageArrivesAndMarksIncompleteCounts() {
        val usage = mutableStateOf(TokenUsageTotals().addRequest(ModelRequestUsage("p", "m")))
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                TokenUsageSheet(usage.value, conversation = true, onDismiss = {})
            }
        }
        compose.onNodeWithTag("chat-token-usage-total").assertTextEquals("— tokens")
        compose.onNodeWithText(context.getString(R.string.chat_token_usage_unavailable)).assertIsDisplayed()
        compose.runOnIdle { usage.value = usage.value.addRequest(ModelRequestUsage("p", "m", 10_000, 2_800)) }
        compose.onNodeWithTag("chat-token-usage-total").assertTextEquals("12.8K+ tokens")
        compose.onNodeWithText(context.getString(R.string.chat_token_usage_incomplete)).assertIsDisplayed()
    }

    private fun capture(name: String, sheet: Boolean = true) {
        val node = if (sheet) compose.onNodeWithTag("chat-token-usage-sheet") else compose.onRoot()
        node.captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
