// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EventStoreTimelinePerformanceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun long_history_is_virtualized_inside_outer_list_and_details_load_only_on_click() {
        val presentation = ToolPresentationSnapshot(
            "tool.screen_act", ToolPresentationCategory.PRIMARY, ResultImageRole.NONE
        )
        val entries = (0 until 2_000).map { i ->
            ToolActivityReadModel(
                itemId = "item-$i", turnId = "fixture", sequence = i.toLong(), visible = true,
                roundId = "round-$i", callId = "call-$i", attemptId = null, executionId = null,
                source = ToolCallSource.MODEL_REQUESTED,
                presentation = presentation.copy(actionTitle = "Entry $i"),
                state = ToolActivityState.UNVERIFIED,
                diagnosticCode = null, publicProgress = null, operationCounted = true,
                startedAt = 1L, finishedAt = 2L
            )
        }
        val activity = mutableStateOf(TurnActivityReadModel(
            "fixture", TurnReadModelState.RUNNING, entries,
            entries.size, "tool.screen_act", entries.map {
                PublicProgressReadModel("progress-${it.callId}", it.sequence, "Progress ${it.sequence}", listOf(it.roundId))
            }, 1L, null, null
        ))
        val detail = ToolDetailReadModel(
            threadId = "fixture", turnId = "fixture", roundId = "round-0", callId = "call-0",
            providerCallId = "provider-0", providerOrder = 0, technicalName = "screen_act",
            argumentsJson = """{"actions":[{"op":"click","x":100,"y":200}]}""",
            source = ToolCallSource.MODEL_REQUESTED, binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY, presentation = presentation,
            roundDetailRef = "detail-0", attemptId = null, executionId = null,
            backendSessionId = null, displayId = null, outputText = "已取得操作回执",
            executionStatus = ToolExecutionStatus.SUCCEEDED, evidenceStatus = ToolEvidenceStatus.UNKNOWN,
            actionEvidence = null, observationId = null, diagnosticCode = null, metrics = emptyMap(),
            publicProgressBlocks = emptyList(), startedAt = 1L, finishedAt = 2L,
            exclusiveDurationMillis = 1L, attachments = listOf(attachment("primary-image"))
        )
        val details = CountingDetails(entries.associate { entry ->
            entry.callId to detail.copy(
                callId = entry.callId,
                roundId = entry.roundId,
                attachments = if (entry.callId == detail.callId) detail.attachments
                    else listOf(attachment("unrelated-${entry.callId}"))
            )
        })
        val loadedImages = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize().testTag("outer")) {
                    item {
                        ExecutionProcessHistory(activity.value, activity.value.publicProgressHistory, details, loadAttachment = {
                            loadedImages += it.attachmentId
                            null
                        })
                    }
                }
            }
        }
        compose.onNodeWithText("Progress 0").assertIsDisplayed()
        compose.onNodeWithText("Progress 1999").assertDoesNotExist()
        compose.onNodeWithText("Entry 0").assertDoesNotExist()
        val rendered = compose.onAllNodes(hasText("Progress ", substring = true)).fetchSemanticsNodes().size
        assertTrue("Expected a bounded viewport, got $rendered rows", rendered in 1..50)
        compose.runOnIdle {
            assertEquals(0, details.lookups)
            assertEquals(0, details.scans)
            assertTrue(loadedImages.isEmpty())
        }
        val history = compose.onNode(hasScrollToIndexAction() and !hasTestTag("outer"))
        history.performScrollToKey("progress-call-1000")
        compose.onNodeWithText("Progress 1000").assertIsDisplayed()
        compose.runOnIdle {
            val earlier = (0 until 100).map { i ->
                PublicProgressReadModel("earlier-$i", i - 100L, "Earlier progress $i", listOf("earlier-$i"))
            }
            activity.value = activity.value.copy(publicProgressHistory = earlier + activity.value.publicProgressHistory)
        }
        compose.onNodeWithText("Progress 1000").assertIsDisplayed()
        history.performScrollToKey("progress-call-0")
        compose.onNodeWithText("Progress 0").performClick()
        compose.onNodeWithText("100, 200", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, details.scans)
            assertEquals(1, details.lookups)
            assertEquals(listOf("primary-image"), loadedImages)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.execution_close_details)).performClick()
        // Returning from details preserves the timeline's scroll anchor.
        compose.onNodeWithText("Progress 0").assertIsDisplayed()
        history.performScrollToKey("progress-call-1999")
        compose.onNodeWithText("Progress 1999").assertIsDisplayed()
        compose.runOnIdle {
            activity.value = activity.value.copy(publicProgressHistory = emptyList())
            loadedImages.clear()
        }
        compose.onNodeWithText("Entry 0").assertDoesNotExist()
        // Unnarrated receipts remain clickable and keep the current scroll anchor.
        compose.onNodeWithText("Entry 1999").assertIsDisplayed()
        val receiptRows = compose.onAllNodes(hasClickAction() and
            hasAnyAncestor(hasTestTag("execution-process-history"))).fetchSemanticsNodes()
        assertTrue(receiptRows.size in 1..50)
        // A narrated round with many calls virtualizes its secondary detail list as well.
        compose.runOnIdle {
            activity.value = activity.value.copy(publicProgressHistory = listOf(
                PublicProgressReadModel("combined", 0, "Combined progress", entries.map { it.roundId })))
        }
        compose.onNodeWithText("Combined progress").performClick()
        compose.onNodeWithText("Entry 0").assertIsDisplayed()
        compose.onNodeWithText("Entry 1999").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, details.scans)
            assertTrue(loadedImages.size in 1..50)
        }
    }

    private class CountingDetails(private val backing: Map<String, ToolDetailReadModel>) :
        Map<String, ToolDetailReadModel> by backing {
        var scans = 0
        var lookups = 0
        override val values: Collection<ToolDetailReadModel>
            get() {
                scans++
                return backing.values
            }
        override fun get(key: String): ToolDetailReadModel? {
            lookups++
            return backing[key]
        }
    }

    private fun attachment(id: String) = DetailAttachmentReadModel(
        attachmentId = id, eventId = id, sequence = 1L,
        role = DetailAttachmentRole.RESULT, contentRef = id, contentHash = id,
        sizeBytes = 0L, mimeType = "image/png", width = 1, height = 1, observationId = null
    )
}
