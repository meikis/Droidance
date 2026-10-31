// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolActivityReadModel
import com.droidance.core.agent.event.ToolActivityState
import com.droidance.core.agent.event.ToolCallSource
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.PublicProgressReadModel
import com.droidance.core.agent.event.TurnActivityReadModel
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import com.droidance.core.agent.harness.*
import com.droidance.agent.harness.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnPresentationUiStateTest {
    @Test fun stageResultsRemainVisibleAndCopyableAcrossRunningPauseAndTerminalStates() {
        val stage = com.droidance.core.agent.event.ConversationEntryReadModel("stage", "event", "thread", "turn-1", 5, 5,
            com.droidance.core.agent.event.ConversationEntryKind.ASSISTANT_STAGE_RESULT, "Retained findings", "round-1")
        val base = presentation(false).copy(opening = "", finalSummary = "", stageResults = listOf(stage))
        for (state in listOf(TurnReadModelState.RUNNING, TurnReadModelState.PAUSED_USER,
            TurnReadModelState.PAUSED_RECOVERY_REQUIRED, TurnReadModelState.WAITING_FOR_USER, TurnReadModelState.TERMINAL)) {
            val task = base.copy(activity = base.activity.copy(state = state))
            assertTrue(task.hasDurablePresentation)
            assertTrue(task.hasPublicResponse())
            assertEquals("Retained findings", task.publicCopyText())
            assertEquals("", task.liveDialogue("Stale accumulated stream"))
            assertEquals(listOf(stage), task.stageResults)
        }
        assertEquals("Retained findings\n\nFinal summary", base.copy(finalSummary = "Final summary").publicCopyText())
        assertEquals("Retained findings\n\nRetained findings", base.copy(stageResults = listOf(stage,
            stage.copy(itemId = "second-stage"))).publicCopyText())
    }

    @Test fun onlyReportedResultsAndLegacyStagesStayWhileStatusTextIsLive() {
        val stage = com.droidance.core.agent.event.ConversationEntryReadModel("stage", "event", "thread", "turn-1", 5, 5,
            com.droidance.core.agent.event.ConversationEntryKind.ASSISTANT_STAGE_RESULT, "Legacy findings", "round-1")
        val earlier = PublicProgressReadModel("earlier", 3L, "Opening the first source", listOf("first"))
        val next = PublicProgressReadModel("next", 8L, "Scrolling the list", listOf("next"))
        val base = presentation(true)
        val tool = base.activity.entries.filterIsInstance<ToolActivityReadModel>().single()
        val report = tool.copy(itemId = "report-item", callId = "report-call", sequence = 7L)
        val running = base.copy(finalSummary = "", stageResults = listOf(stage),
            toolDetails = mapOf("report-call" to reportDetail(report, "| A | B |\n|---|---|\n| 1 | 2 |"),
                "bad-call" to reportDetail(report.copy(callId = "bad-call"), "rejected", ToolExecutionStatus.NOT_EXECUTED)),
            activity = base.activity.copy(state = TurnReadModelState.RUNNING, entries = listOf(tool, report),
                publicProgressHistory = listOf(earlier, next)))
        assertEquals(listOf("Legacy findings", "| A | B |\n|---|---|\n| 1 | 2 |"), running.reportedResults.map { it.text })
        assertEquals("Scrolling the list", running.liveStatus(true))
        assertEquals(listOf(earlier), running.progressHistory(true))
        assertEquals(listOf(earlier, next), running.progressHistory(false))
        assertEquals("Legacy findings\n\n| A | B |\n|---|---|\n| 1 | 2 |", running.publicCopyText())
    }

    @Test fun takeoverTitleUsesOnlyTheLatestAcceptedHandoffAndSurvivesReattachment() {
        val base = presentation(true)
        val original = base.activity.entries.filterIsInstance<ToolActivityReadModel>().single()
        fun call(id: String, title: String?, state: ToolActivityState = ToolActivityState.VERIFIED) =
            original.copy(callId = id, presentation = original.presentation.copy(actionTitle = title), state = state)
        fun request(id: String, sequence: Long) = LifecycleActivityReadModel(
            id, base.turnId, sequence, true, LifecycleActivityKind.USER_INTERVENTION, null, null, id, null)
        val entries = listOf(call("first", "旧请求"), request("first", 1),
            call("current", "请在当前应用完成验证"), request("current", 3),
            call("rejected", "不可采用的标题", ToolActivityState.REJECTED), request("rejected", 5),
            request("other", 7).copy(turnId = "another-turn"))
        val task = base.copy(activity = base.activity.copy(state = TurnReadModelState.WAITING_FOR_USER,
            entries = entries))
        assertEquals("请在当前应用完成验证", task.handoffTitle())
        assertEquals(task.handoffTitle(), task.copy(activity = task.activity.copy(entries = entries.toList())).handoffTitle())
        assertEquals(null, task.copy(isCurrentSegment = false).handoffTitle())
        for (state in listOf(TurnReadModelState.RUNNING, TurnReadModelState.PAUSED_USER,
            TurnReadModelState.PAUSED_RECOVERY_REQUIRED, TurnReadModelState.TERMINAL)) {
            assertEquals(null, task.copy(activity = task.activity.copy(state = state)).handoffTitle())
        }
        for (missing in listOf(null, " ")) {
            assertEquals(null, task.copy(activity = task.activity.copy(entries = listOf(
                call("first", "旧请求"), request("first", 1), call("current", missing), request("current", 3)
            ))).handoffTitle())
        }
    }
    @Test fun emptyInterruptedPreparationDoesNotCreateAHistoricalCard() {
        val empty = presentation(true).copy(isCurrentSegment = false, opening = "", intervention = "", finalSummary = "",
            resultCard = null, activity = presentation(true).activity.copy(entries = emptyList(), publicProgressHistory = emptyList()))
        assertFalse(empty.hasDurablePresentation)
        assertTrue(empty.copy(opening = "Earlier reply").hasDurablePresentation)
    }

    @Test fun committedAndroidOpeningShowsIntentBeforeSessionActivation() {
        fun thread(decision: ZeroToolRoundDecision, owner: String = "turn-1") = AgentThreadReadModel(
            metadata = ThreadMetadataReadModel("thread", "", null, false, false, 1, 1, 1, 1),
            turns = emptyList(), conversation = ConversationReadModel("thread", emptyList()),
            activityByTurn = emptyMap(), toolDetails = emptyMap(), resultCards = emptyList(),
            roundDetails = mapOf("round" to ModelRoundDetailReadModel(
                "thread", owner, "round", "ref", "fingerprint", SubmittedItemSequenceRange(1, 1),
                1, listOf("Opening"), PublicMessageKind.START,
                mapOf(ZERO_TOOL_PHASE_KEY to ZERO_TOOL_PHASE_VALUE, ZERO_TOOL_DECISION_KEY to decision.wireName),
                1, "event")),
            completeRecord = CompleteRecordReadModel("thread", emptyList(), emptyList()))
        assertTrue(thread(ZeroToolRoundDecision.ACTIVATE_ANDROID).hasAndroidExecutionIntent("turn-1"))
        assertFalse(thread(ZeroToolRoundDecision.ACTIVATE_LOCAL).hasAndroidExecutionIntent("turn-1"))
        assertFalse(thread(ZeroToolRoundDecision.DIRECT_REPLY).hasAndroidExecutionIntent("turn-1"))
        assertFalse(thread(ZeroToolRoundDecision.ACTIVATE_ANDROID, "older").hasAndroidExecutionIntent("turn-1"))
    }

    @Test fun executionDoesNotWaitForPublicTextAndChatNeverShowsLog() {
        val execution = presentation(true).copy(opening = "", finalSummary = "")
        assertTrue(execution.showExecutionLog())
        assertTrue(execution.copy(finalSummary = "Result").showExecutionLog())
        assertFalse(presentation(false).showExecutionLog())
    }

    @Test
    fun androidActivationShowsLogWithOpeningBeforeFirstDeviceCall() {
        val presentation = presentation(hasDeviceExecution = false).let {
            it.copy(androidActivated = true, finalSummary = "",
                activity = it.activity.copy(state = TurnReadModelState.RUNNING,
                    entries = emptyList(), operationCount = 0))
        }
        assertTrue(presentation.hasDurablePresentation)
        assertTrue(presentation.hasExecutionPresentation)
        assertTrue(presentation.showExecutionLog())
        assertTrue(presentation.copy(opening = "I will check.").showExecutionLog())
    }

    @Test
    fun localToolCompletionUsesCanonicalFinalWithoutAPhoneExecutionCard() {
        val presentation = presentation(hasDeviceExecution = false)

        assertTrue(presentation.hasDurablePresentation)
        assertFalse(presentation.hasExecutionPresentation)
    }

    @Test
    fun openingAndFinalRemainDurableWithoutDeviceExecution() {
        val presentation = presentation(hasDeviceExecution = false).copy(opening = "I will check.")

        assertTrue(presentation.hasDurablePresentation)
        assertFalse(presentation.hasExecutionPresentation)
    }

    @Test
    fun opening_is_visible_before_device_preparation_or_final_reply() {
        val presentation = presentation(hasDeviceExecution = false).let {
            it.copy(opening = "我先查看当前设置，再核对结果。", finalSummary = "",
                activity = it.activity.copy(state = TurnReadModelState.RUNNING))
        }
        assertTrue(presentation.hasDurablePresentation)
        assertFalse(presentation.hasExecutionPresentation)
    }

    @Test
    fun unfinishedTextOnlyTurnKeepsLivePresentation() {
        val presentation = presentation(hasDeviceExecution = false).let {
            it.copy(finalSummary = "", activity = it.activity.copy(state = TurnReadModelState.RUNNING))
        }

        assertTrue(presentation.hasDurablePresentation)
    }

    @Test
    fun textOnlyFailureUsesCanonicalNoticeWithoutAPhoneExecutionCard() {
        val presentation = presentation(hasDeviceExecution = false).copy(
            finalSummary = "The request could not be completed."
        )

        assertTrue(presentation.hasDurablePresentation)
        assertFalse(presentation.hasExecutionPresentation)
    }

    @Test
    fun localToolActivityDoesNotCreateAPhoneExecutionCard() {
        val presentation = presentation(hasDeviceExecution = false)

        assertFalse(presentation.hasExecutionPresentation)
    }

    @Test
    fun deviceBoundActivityCreatesAnExecutionCard() {
        val presentation = presentation(hasDeviceExecution = true)

        assertTrue(presentation.hasExecutionPresentation)
    }

    @Test
    fun interventionWithoutDeviceExecutionKeepsPublicContentButNotAProcessCard() {
        val presentation = presentation(
            hasDeviceExecution = false,
            intervention = "Please provide the missing account name."
        )

        assertTrue(presentation.hasDurablePresentation)
        assertFalse(presentation.hasExecutionPresentation)
    }

    @Test fun retainedViewerBelongsToTheConversationAcrossTurnsAndTransfersOnlyToAnotherConversation() {
        assertTrue(RetainedVirtualExecutionOwnerPolicy.owns("thread-one", "thread-one"))
        assertFalse(RetainedVirtualExecutionOwnerPolicy.owns("thread-two", "thread-one"))
        assertFalse(RetainedVirtualExecutionOwnerPolicy.owns(null, "thread-one"))
        assertFalse(RetainedVirtualExecutionOwnerPolicy.owns("", "thread-one"))
    }

    @Test fun accumulatedOpeningAndHandoffNeverRenderAsAnotherReply() {
        val task = presentation(true).copy(opening = "I will check.", finalSummary = "",
            intervention = "Please complete verification.")
        assertEquals("", task.liveDialogue("I will check.\n\nPlease complete verification."))
        assertEquals("", task.copy(intervention = "").liveDialogue("I will check.\n\nNew progress."))
        val chat = presentation(false).copy(opening = "", finalSummary = "")
        assertEquals("Hello", chat.liveDialogue("Hello"))
        assertEquals("", chat.copy(finalSummary = "Hello").liveDialogue("Hello"))
    }

    @Test fun restoredPauseStatesRemainPausedWithoutLiveRuntimeAndTerminalWins() {
        val task = presentation(true)
        for (state in listOf(TurnReadModelState.WAITING_FOR_USER, TurnReadModelState.PAUSED_USER,
            TurnReadModelState.PAUSED_RECOVERY_REQUIRED)) {
            assertTrue(task.copy(activity = task.activity.copy(state = state)).isPaused())
        }
        assertFalse(task.isPaused(livePaused = true))
        val running = task.copy(activity = task.activity.copy(state = TurnReadModelState.RUNNING))
        assertFalse(running.isPaused())
        assertTrue(running.isPaused(livePaused = true))
    }

    @Test fun oldHandoffIsNotShownDuringLaterManualPause() {
        val task = presentation(true, intervention = "Old request")
        val userPause = task.copy(activity = task.activity.copy(state = TurnReadModelState.PAUSED_USER))
        assertEquals("", userPause.handoffReason())
        assertEquals("New recovery request", userPause.handoffReason("New recovery request"))
        val pending = task.copy(activity = task.activity.copy(state = TurnReadModelState.WAITING_FOR_USER))
        assertEquals("Old request", pending.handoffReason())
        assertEquals("", task.handoffReason("Stale live request"))
    }

    @Test fun liveStatusRespectsEveryPauseAndTerminalBoundary() {
        val base = presentation(true).let { it.copy(finalSummary = "",
            activity = it.activity.copy(state = TurnReadModelState.RUNNING, publicProgressHistory = listOf(PublicProgressReadModel("latest", 10L, "Latest round", listOf("latest"))))) }
        assertEquals("Latest round", base.liveStatus(true))
        assertEquals("", base.liveStatus(false))
        assertEquals("", base.liveStatus(true, livePaused = true))
        for (state in listOf(TurnReadModelState.WAITING_FOR_USER, TurnReadModelState.PAUSED_USER,
            TurnReadModelState.PAUSED_RECOVERY_REQUIRED, TurnReadModelState.TERMINAL)) {
            val task = base.copy(activity = base.activity.copy(state = state))
            assertEquals("", task.liveStatus(true))
            assertTrue(task.reportedResults.isEmpty())
        }
        assertEquals("", base.copy(finalSummary = "Final answer").liveStatus(true))
    }

    @Test fun statusTextIsPreferredOverTheStepTitle() {
        val base = presentation(true).let { it.copy(finalSummary = "",
            activity = it.activity.copy(state = TurnReadModelState.RUNNING,
                publicProgressHistory = listOf(PublicProgressReadModel("progress", 10L, "Observed screen", listOf("progress"))))) }
        val original = base.activity.entries.filterIsInstance<ToolActivityReadModel>().single()
        val action = original.copy(sequence = 11L, presentation = original.presentation.copy(actionTitle = "Scroll down"))
        val next = base.copy(activity = base.activity.copy(entries = listOf(action)))
        assertEquals("Observed screen", next.liveStatus(true))
        assertEquals("Scroll down", next.copy(activity = next.activity.copy(publicProgressHistory = emptyList())).liveStatus(true))
    }

    @Test fun liveStepShowsOnlyTheLatestActionTitleWhileRunning() {
        val base = presentation(true).let { it.copy(finalSummary = "", activity = it.activity.copy(state = TurnReadModelState.RUNNING)) }
        val original = base.activity.entries.filterIsInstance<ToolActivityReadModel>().single()
        fun step(seq: Long, title: String?) = original.copy(itemId = "tool-$seq", callId = "call-$seq", sequence = seq,
            presentation = original.presentation.copy(actionTitle = title))
        val running = base.copy(activity = base.activity.copy(entries = listOf(step(1, "Open the search box"), step(2, "Type the keyword"))))
        assertEquals("Type the keyword", running.liveActionTitle(true))
        assertTrue(running.reportedResults.isEmpty())
        assertEquals("", running.copy(activity = running.activity.copy(entries = listOf(step(3, null)))).liveActionTitle(true))
        assertEquals("", running.liveActionTitle(false))
        assertEquals("", running.liveActionTitle(true, livePaused = true))
        assertEquals("", running.copy(activity = running.activity.copy(state = TurnReadModelState.TERMINAL)).liveActionTitle(true))
    }

    @Test fun statusTextMovesToTheLogAndNeverBecomesAResult() {
        val updates = listOf(PublicProgressReadModel("first", 1L, "First status", listOf("first")),
            PublicProgressReadModel("second", 2L, "Second status", listOf("second")))
        val base = presentation(true).let { it.copy(finalSummary = "",
            activity = it.activity.copy(state = TurnReadModelState.RUNNING, publicProgressHistory = updates)) }
        assertTrue(base.reportedResults.isEmpty())
        val terminal = base.copy(finalSummary = "Result", activity = base.activity.copy(state = TurnReadModelState.TERMINAL))
        assertEquals(updates, terminal.progressHistory(true))
        assertEquals("Result", terminal.publicCopyText())
    }

    private fun reportDetail(call: ToolActivityReadModel, text: String,
                             status: ToolExecutionStatus = ToolExecutionStatus.SUCCEEDED) = ToolDetailReadModel(
        threadId = "thread", turnId = call.turnId, roundId = call.roundId, callId = call.callId,
        providerCallId = call.callId, providerOrder = 0, technicalName = "report_result",
        argumentsJson = kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive(text)) }.toString(),
        source = call.source, binding = ToolBinding.TURN_LOCAL,
        effect = ToolEffect.STATE_CHANGING, presentation = call.presentation, roundDetailRef = "detail",
        attemptId = null, executionId = null, backendSessionId = null, displayId = null,
        outputText = "shown", executionStatus = status,
        evidenceStatus = ToolEvidenceStatus.UNKNOWN, actionEvidence = null, observationId = null,
        diagnosticCode = null, metrics = emptyMap(), publicProgressBlocks = emptyList(),
        startedAt = 1, finishedAt = 2, exclusiveDurationMillis = 1, attachments = emptyList()
    )

    private fun presentation(
        hasDeviceExecution: Boolean,
        intervention: String = ""
    ) = TurnPresentationUiState(

        turnId = "turn-1",
        startedAtMs = 1L,
        opening = "",
        intervention = intervention,
        finalSummary = "remembered",
        activity = TurnActivityReadModel(
            turnId = "turn-1",
            state = TurnReadModelState.TERMINAL,
            entries = listOf(
                ToolActivityReadModel(
                    itemId = "tool-1",
                    turnId = "turn-1",
                    sequence = 1L,
                    visible = true,
                    roundId = "round-1",
                    callId = "call-1",
                    attemptId = null,
                    executionId = null,
                    source = ToolCallSource.MODEL_REQUESTED,
                    presentation = ToolPresentationSnapshot(
                        labelResourceKey = "tool.done",
                        category = ToolPresentationCategory.COMPLETION,
                        resultImageRole = ResultImageRole.NONE
                    ),
                    state = ToolActivityState.VERIFIED,
                    diagnosticCode = null,
                    publicProgress = null,
                    operationCounted = true,
                    startedAt = 1L,
                    finishedAt = 2L
                )
            ),
            operationCount = 1,
            currentLabelResourceKey = null,
            publicProgressHistory = emptyList(),
            startedAt = 1L,
            completedAt = 2L,
            totalDurationMillis = 1L
        ),
        toolDetails = emptyMap(),
        resultCard = null,
        hasDeviceExecution = hasDeviceExecution
    )
}
