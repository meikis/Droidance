// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.core.agent.TaskExecutionClock
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import com.droidance.core.agent.event.ConversationEntryKind
import com.droidance.core.agent.event.ConversationEntryReadModel
import com.droidance.core.agent.event.PublicProgressReadModel
import com.droidance.core.agent.event.TurnActivityReadModel
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.settings.AppThemeMode
import com.droidance.ui.execution.TurnPresentationUiState
import com.droidance.ui.theme.DroidanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Rendering only: no device operation or model request is performed by these fixtures. */
class ReportedResultsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resultsKeepCompactLogAndSurviveHandoffResumeAndCancellationOnANarrowScreen() {
        val report = call("report", 6)
        val findings = "Confirmed findings from the first source. The remaining source has not been checked."
        val handoff = "The next source needs human verification. Its price is still unknown."
        val earlierStatus = "Reading the first source"
        val task = mutableStateOf(TurnPresentationUiState("turn", 1, "", handoff, "",
            TurnActivityReadModel("turn", TurnReadModelState.WAITING_FOR_USER, listOf(report), 1, null,
                listOf(PublicProgressReadModel("earlier", 3, earlierStatus, listOf("round:report"))),
                1, null, null, activeDurationMillis = 358_000),
            mapOf("report" to reportDetail(report, findings)), null, true,
            executionClock = TaskExecutionClock.Snapshot(358_000, null)))
        val streaming = mutableStateOf(false)
        val viewportWidth = mutableStateOf(320.dp)
        var opens = 0
        var stops = 0
        compose.setContent { DroidanceTheme(themeMode = AppThemeMode.DARK) {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.width(viewportWidth.value).verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 48.dp)) {
                            ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = streaming.value,
                                turnPresentation = task.value, canOpenVirtualSpace = true,
                                onOpenVirtualSpace = { opens++ }, onStop = { stops++ })
                        }
                    }
                }
            }
        } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val elapsed = context.getString(R.string.chat_elapsed, context.getString(R.string.chat_duration_minutes, 5, 58))
        val count = context.getString(R.string.chat_process_record_count, 1)
        val resultTag = "reported-result:report:report"
        compose.onNodeWithText(elapsed, substring = true).assertIsDisplayed()
        compose.onNodeWithText(count, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("execution-result-label").assertDoesNotExist()
        compose.onNodeWithTag("execution-task-card").assertWidthIsEqualTo(296.dp)
        val history = compose.onNodeWithTag("execution-records-toggle").fetchSemanticsNode().boundsInRoot
        val result = compose.onNodeWithTag(resultTag).fetchSemanticsNode().boundsInRoot
        assertTrue(result.width > history.width)
        assertTrue(result.bottom <= compose.onNodeWithText(handoff).fetchSemanticsNode().boundsInRoot.top)
        capture("reported-results-compact-paused.png")
        compose.onNodeWithTag("execution-records-toggle").performClick()
        compose.onNodeWithText(earlierStatus).assertIsDisplayed()
        compose.onNodeWithTag(resultTag).assertIsDisplayed()
        compose.onNodeWithTag("execution-records-toggle").performClick()
        compose.onNodeWithText(earlierStatus).assertDoesNotExist()
        compose.runOnIdle {
            // Rebuild from durable state, with no accumulated transport reply.
            task.value = task.value.copy(toolDetails = task.value.toolDetails.toMap(),
                activity = task.value.activity.copy(entries = task.value.activity.entries.toList()))
        }
        compose.onNodeWithTag(resultTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_takeover_handle)).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.runOnIdle {
            streaming.value = true
            task.value = task.value.copy(activity = task.value.activity.copy(state = TurnReadModelState.RUNNING,
                publicProgressHistory = listOf(PublicProgressReadModel("next", 8, "Checking the next source", listOf("next")))))
        }
        compose.onNodeWithTag(resultTag).assertIsDisplayed()
        compose.onNodeWithText(handoff).assertDoesNotExist()
        compose.onNodeWithText(elapsed, substring = true).assertIsDisplayed()
        compose.onNodeWithText(count, substring = true).assertIsDisplayed()
        for (width in listOf(320.dp, 360.dp)) {
            compose.runOnIdle { viewportWidth.value = width }
            compose.onNodeWithTag("execution-task-card").assertWidthIsEqualTo(width - 24.dp)
            val textLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("$elapsed · $count", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
            capture("reported-results-compact-running-${width.value.toInt()}.png")
            assertTrue("Summary overflow at $width: ${textLayouts.map { "${it.size}, lines=${it.lineCount}, constraints=${it.layoutInput.constraints}" }}",
                textLayouts.isNotEmpty() && textLayouts.none { it.hasVisualOverflow })
        }
        val header = compose.onNodeWithTag("execution-process-status-row").fetchSemanticsNode().boundsInRoot
        val stop = compose.onNodeWithContentDescription(context.getString(R.string.app_takeover_status_stop))
            .fetchSemanticsNode().boundsInRoot
        val open = compose.onNodeWithContentDescription(context.getString(R.string.virtual_space_viewer_open))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(stop.left >= compose.onNodeWithTag("execution-records-toggle").fetchSemanticsNode().boundsInRoot.right)
        assertTrue(kotlin.math.abs(stop.center.y - open.center.y) < 1f)
        assertTrue(stop.top >= header.top && stop.bottom <= header.bottom)
        compose.onNodeWithContentDescription(context.getString(R.string.virtual_space_viewer_open)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.app_takeover_status_stop)).performClick()
        compose.runOnIdle {
            assertEquals(2, opens)
            assertEquals(1, stops)
            task.value = task.value.copy(finalSummary = "Stopped. The next source remains unchecked.", terminalStatus = "cancelled",
                activity = task.value.activity.copy(state = TurnReadModelState.TERMINAL))
            streaming.value = false
        }
        compose.onNodeWithTag(resultTag).assertIsDisplayed()
        assertTrue(task.value.publicCopyText().startsWith(findings))
        compose.onNodeWithText("Stopped. The next source remains unchecked.").assertIsDisplayed()
        compose.onNodeWithText(count, substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.app_takeover_status_stop)).assertDoesNotExist()
    }

    @Test fun statusIsLiveOnlyWhileReportedResultsStayAboveTheFinalAnswer() {
        val task = mutableStateOf(TurnPresentationUiState("turn", 1, "", "", "",
            TurnActivityReadModel("turn", TurnReadModelState.RUNNING, emptyList(), 0, null,
                listOf(PublicProgressReadModel("first", 5, "Opening the first source", listOf("r1"))), 1, null, null),
            emptyMap(), null, false, androidActivated = true))
        compose.setContent { DroidanceTheme(themeMode = AppThemeMode.DARK) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = !task.value.isTerminal,
                        turnPresentation = task.value)
                }
            }
        } }
        compose.onNode(hasText("Opening the first source") and hasAnyAncestor(hasTestTag("execution-task-progress")))
            .assertIsDisplayed()
        val report = call("report", 6)
        compose.runOnIdle { task.value = task.value.copy(
            toolDetails = mapOf("report" to reportDetail(report, "Top items: A, B")),
            activity = task.value.activity.copy(entries = listOf(report), publicProgressHistory =
                task.value.activity.publicProgressHistory + PublicProgressReadModel("second", 7, "Opening the next app", listOf("r2")))) }
        compose.onNodeWithText("Opening the first source").assertDoesNotExist()
        compose.onNode(hasText("Opening the next app") and hasAnyAncestor(hasTestTag("execution-task-progress")))
            .assertIsDisplayed()
        compose.onNodeWithTag("reported-result:report:report").assertIsDisplayed()
        compose.runOnIdle { task.value = task.value.copy(finalSummary = "Both parts summarized",
            activity = task.value.activity.copy(state = TurnReadModelState.TERMINAL)) }
        compose.onNodeWithTag("execution-task-progress").assertDoesNotExist()
        compose.onNodeWithText("Opening the next app").assertDoesNotExist()
        val reported = compose.onNodeWithTag("reported-result:report:report").fetchSemanticsNode().boundsInRoot
        val final = compose.onNodeWithText("Both parts summarized").fetchSemanticsNode().boundsInRoot
        assertTrue(reported.bottom <= final.top)
    }

    @Test fun earlierPersistedStageEntriesRenderLikeReportedResults() {
        val report = call("report", 8)
        val task = TurnPresentationUiState("turn", 1, "", "", "Overall result",
            TurnActivityReadModel("turn", TurnReadModelState.TERMINAL, listOf(report), 1, null,
                listOf(PublicProgressReadModel("p1", 3, "Reading source one", listOf("r1"))), 1, null, null),
            mapOf("report" to reportDetail(report, "Second source findings")), null, false, terminalStatus = "done",
            stageResults = listOf(stage("legacy", "First source findings").copy(sequence = 5)))
        compose.setContent { DroidanceTheme(themeMode = AppThemeMode.DARK) {
            Column { ChatBubble(text = "", isUser = false, timestamp = "", isStreaming = false, turnPresentation = task) }
        } }
        val legacy = compose.onNodeWithTag("reported-result:legacy").fetchSemanticsNode().boundsInRoot
        val reported = compose.onNodeWithTag("reported-result:report:report").fetchSemanticsNode().boundsInRoot
        assertTrue(legacy.bottom <= reported.top)
        compose.onNodeWithText("Reading source one").assertDoesNotExist()
    }

    @Test fun appTakeoverShowsReportedResultsAndTheLiveStatus() {
        val report = call("report", 4)
        val task = TurnPresentationUiState("turn", 1, "", "", "",
            TurnActivityReadModel("turn", TurnReadModelState.RUNNING, listOf(report), 1, null,
                listOf(PublicProgressReadModel("latest", 8, "Reading source two", listOf("r2"))), 1, null, null),
            mapOf("report" to reportDetail(report, "First source findings")), null, false)
        compose.setContent { DroidanceTheme(themeMode = AppThemeMode.DARK) {
            Column { AppTakeoverLogCard("Task", "", true, listOf(task), { null }, {}, {}, {}) }
        } }
        compose.onNodeWithTag("reported-result:report:report").assertIsDisplayed()
        compose.onNode(hasText("Reading source two") and hasAnyAncestor(hasTestTag("execution-task-progress")))
            .assertIsDisplayed()
    }

    @Test fun aHistoricalResultCannotBeCaptionedAsTheCurrentOperationsAfterImage() {
        val image = com.droidance.core.agent.event.DetailAttachmentReadModel("history", "event", 1,
            com.droidance.core.agent.event.DetailAttachmentRole.RESULT, "sha256:old", "old", 1, "image/png",
            null, null, "original", historical = true)
        compose.setContent {
            androidx.compose.material3.Text(com.droidance.ui.execution.executionImageCaption(image))
        }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(com.droidance.R.string.execution_image_historical)).assertExists()
        compose.onNodeWithText(context.getString(com.droidance.R.string.execution_image_after)).assertDoesNotExist()
    }

    private fun call(id: String, sequence: Long) = ToolActivityReadModel(id, "turn", sequence, true, "round:$id", id, null, null,
        ToolCallSource.MODEL_REQUESTED, ToolPresentationSnapshot("tool.report_result", ToolPresentationCategory.SUPPORTING,
            ResultImageRole.NONE), ToolActivityState.VERIFIED, null, null, true, 1L, 2L)

    private fun reportDetail(call: ToolActivityReadModel, text: String) = ToolDetailReadModel(
        threadId = "thread", turnId = call.turnId, roundId = call.roundId, callId = call.callId,
        providerCallId = call.callId, providerOrder = 0, technicalName = "report_result",
        argumentsJson = kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive(text)) }.toString(),
        source = call.source, binding = ToolBinding.TURN_LOCAL, effect = ToolEffect.STATE_CHANGING,
        presentation = call.presentation, roundDetailRef = "detail", attemptId = null, executionId = null,
        backendSessionId = null, displayId = null, outputText = "shown", executionStatus = ToolExecutionStatus.SUCCEEDED,
        evidenceStatus = ToolEvidenceStatus.UNKNOWN, actionEvidence = null, observationId = null,
        diagnosticCode = null, metrics = emptyMap(), publicProgressBlocks = emptyList(),
        startedAt = 1, finishedAt = 2, exclusiveDurationMillis = 1, attachments = emptyList())

    private fun stage(id: String, text: String) = ConversationEntryReadModel(id, "event:$id", "thread", "turn", 2, 2,
        ConversationEntryKind.ASSISTANT_STAGE_RESULT, text, "round:$id")

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
