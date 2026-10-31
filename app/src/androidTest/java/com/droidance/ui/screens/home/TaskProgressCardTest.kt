// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolActivityReadModel
import com.droidance.core.agent.event.ToolActivityState
import com.droidance.core.agent.event.ToolCallSource
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.PublicProgressReadModel
import com.droidance.core.agent.event.TurnActivityReadModel
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.settings.AppThemeMode
import com.droidance.ui.execution.PendingPermissionUiState
import com.droidance.ui.execution.TurnPresentationUiState
import com.droidance.ui.theme.DroidanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Rule
import org.junit.Test

/** Rendering fixtures only; no Provider request, device action, or business-success assertion. */
class TaskProgressCardTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val currentProgress = "正在核对详情页中的尺寸与材质。"
    private val earlierProgress = "已找到相关资料，接下来查看详情。"
    private val firstAction = "读取当前页面"
    private val secondAction = "打开详情页"

    @Test fun guidanceLeavesHistoryAboveTheInputAndOnlyLatestSegmentHasControls() {
        val old = presentation().copy(messageId = "assistant:turn:old", isCurrentSegment = false,
            activity = presentation().activity.copy(activeDurationMillis = 12_000L))
        val latest = presentation().copy(messageId = "assistant:turn:guide", applyingGuidance = true,
            activity = presentation().activity.copy(entries = emptyList(), operationCount = 0,
                publicProgressHistory = emptyList()))
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", turnPresentation = old)
                ChatBubble(text = "Only keep the most recent month", isUser = true, timestamp = "")
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = true, turnPresentation = latest)
            }
        }
        val history = compose.onNodeWithTag("guidance-history").fetchSemanticsNode().boundsInRoot
        val guidance = compose.onNodeWithText("Only keep the most recent month").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("user-guidance-label").assertDoesNotExist()
        val applying = compose.onNodeWithTag("guidance-applying").fetchSemanticsNode().boundsInRoot
        assertTrue(history.top < guidance.top)
        assertTrue(guidance.top < applying.top)
        compose.onAllNodesWithContentDescription(context.getString(R.string.app_takeover_status_stop)).assertCountEquals(1)
        capture("guidance-segment-order.png")
    }

    @Test fun pausedDurationStaysFrozenAndResumesFromAccumulatedWork() {
        val task = mutableStateOf(presentation().copy(
            executionClock = com.droidance.core.agent.TaskExecutionClock.Snapshot(12_000L, null),
            activity = presentation().activity.copy(state = TurnReadModelState.PAUSED_USER)
        ))
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = true,
                    turnPresentation = task.value)
            }
        }
        fun elapsed(seconds: Int) = context.getString(R.string.chat_elapsed,
            context.getString(R.string.chat_duration_seconds, seconds))
        compose.onNodeWithText(elapsed(12), substring = true).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(60_000L)
        compose.onNodeWithText(elapsed(12), substring = true).assertIsDisplayed()
        compose.runOnIdle {
            task.value = task.value.copy(
                executionClock = com.droidance.core.agent.TaskExecutionClock.Snapshot(12_000L,
                    com.droidance.core.agent.TaskExecutionClock.monotonicTimeMs()),
                activity = task.value.activity.copy(state = TurnReadModelState.RUNNING)
            )
        }
        compose.onNodeWithText(elapsed(12), substring = true).assertIsDisplayed()
        compose.runOnIdle {
            task.value = task.value.copy(
                executionClock = com.droidance.core.agent.TaskExecutionClock.Snapshot(13_000L, null),
                activity = task.value.activity.copy(state = TurnReadModelState.PAUSED_USER)
            )
        }
        compose.onNodeWithText(elapsed(13), substring = true).assertIsDisplayed()
    }

    @Test fun statusShowsLiveBelowTheCardAndTheLogOpensItsSteps() {
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = true,
                    turnPresentation = presentation())
            }
        }

        assertCurrentProgressBelowCard()
        compose.onAllNodesWithText(currentProgress).assertCountEquals(1)
        compose.onNodeWithText(earlierProgress).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_process_record_count, 2), substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(firstAction).assertDoesNotExist()
        compose.onNodeWithTag("execution-process-history").assertDoesNotExist()
        capture("task-progress-card-collapsed.png")

        compose.onNodeWithTag("execution-records-toggle").performClick()
        assertCurrentProgressBelowCard()
        compose.onAllNodesWithText(currentProgress).assertCountEquals(1)
        compose.onNode(hasText(earlierProgress) and hasAnyAncestor(hasTestTag("execution-process-history")))
            .assertIsDisplayed()
        capture("task-progress-history-expanded.png")
        compose.onNodeWithText(earlierProgress).performClick()
        compose.onNodeWithText(firstAction).assertIsDisplayed()
        compose.onNode(hasText(secondAction) and hasAnyAncestor(hasTestTag("execution-detail-sheet"))).assertIsDisplayed()
        compose.onNodeWithTag("execution-detail-sheet").assertIsDisplayed()
        capture("task-progress-details.png")
        compose.onNodeWithContentDescription(context.getString(R.string.execution_close_details)).performClick()
        assertCurrentProgressBelowCard()
        compose.onNodeWithText(firstAction).assertDoesNotExist()
    }

    @Test fun missingProgressUsesDotsBelowTheCardAndShowsZeroInteractions() {
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = true,
                    turnPresentation = presentation(progress = null, entries = emptyList()))
            }
        }

        compose.onNodeWithTag("execution-task-card").assertIsDisplayed()
        compose.onNode(hasTestTag("execution-task-progress") and
            !hasAnyAncestor(hasTestTag("execution-task-card")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onAllNodes(hasTestTag("response-wave-dot") and
            hasAnyAncestor(hasTestTag("execution-task-progress")), useUnmergedTree = true)
            .assertCountEquals(3)
        compose.onNodeWithText(context.getString(R.string.chat_process_record_count, 0), substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.execution_action_generic)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_status_running)).assertDoesNotExist()
        capture("task-progress-card-waiting.png")
    }

    @Test fun localTaskKeepsPublicProgressWithoutCreatingADeviceCard() {
        val opening = "我会先整理现有资料。"
        val progress = "正在核对资料中的结论与依据。"
        val task = presentation(progress = progress, entries = emptyList()).copy(
            opening = opening, hasDeviceExecution = false, androidActivated = false
        )
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = true,
                    turnPresentation = task)
            }
        }

        compose.onNodeWithText(opening).assertIsDisplayed()
        compose.onAllNodesWithText(progress).assertCountEquals(1)
        compose.onNodeWithText(progress).assertIsDisplayed()
        compose.onNodeWithTag("execution-task-card").assertDoesNotExist()
        compose.onNodeWithTag("execution-process-status-row").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.app_takeover_status_stop))
            .assertDoesNotExist()
    }

    @Test fun terminalResultsArchiveTheLiveStatusAndKeepExpandedHistoryOpen() {
        val task = mutableStateOf(presentation())
        val streaming = mutableStateOf(true)
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = streaming.value,
                    turnPresentation = task.value)
            }
        }

        listOf("done", "cancelled", "failed", "error", "need_more_info").forEach { status ->
            compose.runOnIdle {
                task.value = presentation()
                streaming.value = true
            }
            compose.onNodeWithTag("execution-records-toggle").performClick()
            compose.onNodeWithText(earlierProgress).assertIsDisplayed()
            val summary = "本次结果：$status"
            val elapsed = context.getString(R.string.chat_elapsed,
                context.getString(R.string.chat_duration_seconds, 13))
            compose.runOnIdle {
                val active = task.value
                task.value = active.copy(
                    terminalStatus = status,
                    finalSummary = summary,
                    activity = active.activity.copy(
                        state = TurnReadModelState.TERMINAL,
                        completedAt = active.startedAtMs + 13_000L,
                        totalDurationMillis = 63_000L,
                        activeDurationMillis = 13_000L
                    )
                )
            }

            compose.onNodeWithTag("execution-task-progress").assertDoesNotExist()
            compose.onNodeWithTag("execution-process-history").assertIsDisplayed()
            compose.onNodeWithText(earlierProgress).assertIsDisplayed()
            compose.onAllNodesWithText(currentProgress).assertCountEquals(1)
            compose.onNode(hasText(currentProgress) and hasAnyAncestor(hasTestTag("execution-task-card")))
                .assertIsDisplayed()
            compose.onNodeWithText(summary).assertIsDisplayed()
            compose.onNodeWithText(elapsed, substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.app_takeover_status_stop))
                .assertDoesNotExist()
            compose.runOnIdle { streaming.value = false }
            compose.onNodeWithText(currentProgress).assertIsDisplayed()
            compose.onNodeWithTag("execution-records-toggle").performClick()
            compose.onNodeWithText(currentProgress).assertDoesNotExist()
            compose.onNodeWithText(summary).assertIsDisplayed()
        }
    }

    @Test fun nextStatusReplacesThePreviousOneWhichMovesToTheLog() {
        val task = mutableStateOf(presentation())
        val next = "已核对尺寸，正在确认资料来源。"
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = true,
                    turnPresentation = task.value)
            }
        }
        compose.onNodeWithTag("execution-records-toggle").performClick()
        compose.runOnIdle {
            task.value = task.value.copy(activity = task.value.activity.copy(
                publicProgressHistory = task.value.activity.publicProgressHistory +
                    PublicProgressReadModel("next", 4L, next, listOf("next"))))
        }
        compose.onAllNodesWithText(currentProgress).assertCountEquals(1)
        compose.onNode(hasText(currentProgress) and hasAnyAncestor(hasTestTag("execution-process-history")))
            .assertIsDisplayed()
        compose.onNode(hasText(next) and hasAnyAncestor(hasTestTag("execution-task-progress")))
            .assertIsDisplayed()
        compose.onNodeWithText(firstAction).assertDoesNotExist()
        val card = compose.onNodeWithTag("execution-task-card").fetchSemanticsNode().boundsInRoot
        val latest = compose.onNodeWithTag("execution-task-progress").fetchSemanticsNode().boundsInRoot
        assertTrue(card.bottom <= latest.top)
        capture("task-progress-new-message.png")
    }

    @Test fun longStatusUsesNormalReplyWidthAndWrapsWithoutTruncation() {
        // Compare wrapped paragraphs: a short sentence's semantic bounds only cover its glyphs.
        val opening = "我会先核对资料，再汇总需要确认的信息。".repeat(3)
        val longProgress = "已找到相关资料，目前正在逐项核对内容与来源，确保结果有明确依据。".repeat(6)
        val task = mutableStateOf(presentation(longProgress).copy(opening = opening))
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = true,
                    turnPresentation = task.value)
            }
        }
        val openingBounds = compose.onNodeWithText(opening).fetchSemanticsNode().boundsInRoot
        val liveBounds = compose.onNodeWithText(longProgress).fetchSemanticsNode().boundsInRoot
        assertEquals(openingBounds.left, liveBounds.left, 1f)
        assertEquals(openingBounds.right, liveBounds.right, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(longProgress).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertTrue(layouts.single().lineCount > 4)
        assertTrue(!layouts.single().hasVisualOverflow)
        capture("task-progress-full-width.png")
        compose.runOnIdle { task.value = task.value.copy(finalSummary = longProgress,
            activity = task.value.activity.copy(state = TurnReadModelState.TERMINAL)) }
        compose.onNodeWithTag("execution-task-progress").assertDoesNotExist()
        val finalBounds = compose.onNodeWithText(longProgress).fetchSemanticsNode().boundsInRoot
        assertEquals(liveBounds.width, finalBounds.width, 1f)
        assertEquals(liveBounds.height, finalBounds.height, 1f)
    }

    @Test fun pausingKeepsSpecificHandoffAndPermissionReasonsWithoutStaleStatus() {
        val task = mutableStateOf(presentation())
        val permission = mutableStateOf<PendingPermissionUiState?>(null)
        var permissionRequests = 0
        val handoff = "当前页面需要身份验证，请完成验证后继续。"
        val permissionReason = "需要读取联系人权限才能继续查找。"
        compose.setContent {
            Fixture {
                ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = true,
                    turnPresentation = task.value, pendingPermission = permission.value,
                    onRequestPermission = { permissionRequests++ })
            }
        }

        assertCurrentProgressBelowCard()
        compose.runOnIdle {
            task.value = task.value.copy(intervention = handoff,
                activity = task.value.activity.copy(state = TurnReadModelState.WAITING_FOR_USER))
        }
        compose.onNodeWithTag("execution-task-progress").assertDoesNotExist()
        compose.onNodeWithText(currentProgress).assertDoesNotExist()
        compose.onAllNodesWithText(handoff).assertCountEquals(1)
        compose.onNodeWithText(handoff).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_status_paused)).assertDoesNotExist()

        compose.runOnIdle {
            task.value = task.value.copy(intervention = "",
                activity = task.value.activity.copy(state = TurnReadModelState.PAUSED_RECOVERY_REQUIRED))
            permission.value = PendingPermissionUiState(
                requestId = "permission-fixture",
                permissions = listOf(android.Manifest.permission.READ_CONTACTS),
                reason = permissionReason
            )
        }
        compose.onNodeWithTag("execution-task-progress").assertDoesNotExist()
        compose.onNodeWithText(currentProgress).assertDoesNotExist()
        compose.onNodeWithText(handoff).assertDoesNotExist()
        compose.onNodeWithText(permissionReason, substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_permission_grant_action)).performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests) }
    }

    private fun assertCurrentProgressBelowCard() {
        compose.onNode(hasTestTag("execution-task-progress") and
            !hasAnyAncestor(hasTestTag("execution-task-card")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNode(hasText(currentProgress) and
            hasAnyAncestor(hasTestTag("execution-task-progress")), useUnmergedTree = true)
            .assertIsDisplayed()
        val card = compose.onNodeWithTag("execution-task-card").fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithTag("execution-task-progress").fetchSemanticsNode().boundsInRoot
        assertTrue(card.bottom <= progress.top)
    }

    @Composable
    private fun Fixture(content: @Composable () -> Unit) {
        DroidanceTheme(themeMode = AppThemeMode.DARK) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 48.dp)) {
                    content()
                }
            }
        }
    }

    private fun presentation(
        progress: String? = currentProgress,
        entries: List<ToolActivityReadModel> = listOf(tool(1L, firstAction), tool(2L, secondAction))
    ): TurnPresentationUiState {
        val startedAt = System.currentTimeMillis() - 7_000L
        return TurnPresentationUiState(
            turnId = "task-card-fixture",
            startedAtMs = startedAt,
            opening = "",
            intervention = "",
            finalSummary = "",

            toolDetails = emptyMap(),
            resultCard = null,
            hasDeviceExecution = entries.isNotEmpty(),
            androidActivated = true,
            activity = TurnActivityReadModel(
                turnId = "task-card-fixture",
                state = TurnReadModelState.RUNNING,
                entries = entries,
                operationCount = entries.size,
                currentLabelResourceKey = null,
                publicProgressHistory = progress?.let { listOf(PublicProgressReadModel("earlier", 1L, earlierProgress, listOf("same-round")),
                    PublicProgressReadModel("progress", 3L, it, listOf("progress"))) }.orEmpty(),
                startedAt = startedAt,
                completedAt = null,
                totalDurationMillis = null
            )
        )
    }

    private fun tool(sequence: Long, title: String) = ToolActivityReadModel(
        itemId = "item-$sequence",
        turnId = "task-card-fixture",
        sequence = sequence,
        visible = true,
        roundId = "same-round",
        callId = "call-$sequence",
        attemptId = null,
        executionId = null,
        source = ToolCallSource.MODEL_REQUESTED,
        presentation = ToolPresentationSnapshot(
            labelResourceKey = "tool.screen_act",
            category = ToolPresentationCategory.PRIMARY,
            resultImageRole = ResultImageRole.NONE,
            actionTitle = title
        ),
        state = ToolActivityState.VERIFIED,
        diagnosticCode = null,
        publicProgress = currentProgress,
        operationCounted = true,
        startedAt = null,
        finishedAt = null
    )

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
