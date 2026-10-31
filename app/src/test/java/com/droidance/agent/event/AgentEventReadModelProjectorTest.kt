// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.event

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*

import com.droidance.agent.event.*

import com.droidance.core.agent.prompt.sha256Hex
import com.droidance.core.agent.harness.ZERO_TOOL_OPENING_VERSION_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_OPENING_VERSION
import com.droidance.core.agent.harness.zeroToolFinishMetadata

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.RoundExecutionMode
import com.droidance.core.agent.harness.RoundFollowUp
import com.droidance.core.agent.harness.ZERO_TOOL_DECISION_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_PHASE_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_PHASE_VALUE
import com.droidance.core.agent.harness.ZeroToolRoundDecision
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventReadModelProjectorTest {
    @Test fun stageResultsReplayBeforeFinalAndNeverBecomeTransientProgressOrHumanRequests() {
        val stream = EventStream()
        stream.thread(); stream.turn(); stream.userMessage("Read several sources")
        stream.roundStart("opening", "detail-opening")
        stream.roundCompleted("opening", "detail-opening", PublicMessageKind.START, listOf("I will review the sources."))
        stream.assistant("opening", "message-opening", PublicMessageKind.START, listOf("I will review the sources."), "detail-opening")
        for (i in 1..2) {
            val round = "stage-$i"
            stream.roundStart(round, "detail-$i")
            stream.roundCompleted(round, "detail-$i", PublicMessageKind.STAGE_RESULT, listOf("Same retained finding"))
            stream.assistant(round, "message-$i", PublicMessageKind.STAGE_RESULT, listOf("Same retained finding"), "detail-$i")
        }
        val live = AgentEventReadModelProjector.project(stream.events)
        assertEquals(2, live.conversation.entries.count { it.kind == ConversationEntryKind.ASSISTANT_STAGE_RESULT })
        assertTrue(live.activityByTurn.getValue(EventStream.TURN_ID).publicProgressHistory.isEmpty())
        stream.completeTurn("Overall summary")
        val restored = stream.events.map { Json.decodeFromString<AgentEventEnvelope>(Json.encodeToString(it)) }
        val replay = AgentEventReadModelProjector.project(restored)
        assertEquals(listOf("message-1", "message-2"), replay.conversation.entries.filter {
            it.kind == ConversationEntryKind.ASSISTANT_STAGE_RESULT }.map { it.itemId })
        assertEquals("Overall summary", replay.conversation.entries.last().text)
        assertEquals(TurnReadModelState.TERMINAL, replay.activityByTurn.getValue(EventStream.TURN_ID).state)
    }

    @Test fun identicalProgressOnEitherSideOfAStageKeepsTheNewerEventInsteadOfCollapsingAcrossIt() {
        val stream = EventStream()
        stream.thread(); stream.turn(); stream.userMessage("Read several sources")
        fun publicRound(id: String, kind: PublicMessageKind, text: String) {
            stream.roundStart(id, "detail-$id")
            stream.roundCompleted(id, "detail-$id", kind, listOf(text))
            if (kind == PublicMessageKind.PROGRESS) stream.progress(id, listOf(text), "detail-$id")
            else stream.assistant(id, "message-$id", kind, listOf(text), "detail-$id")
        }
        publicRound("opening", PublicMessageKind.START, "I will review the sources.")
        publicRound("before", PublicMessageKind.PROGRESS, "Reviewing a source")
        publicRound("stage", PublicMessageKind.STAGE_RESULT, "First source findings")
        publicRound("after", PublicMessageKind.PROGRESS, "Reviewing a source")
        val projection = AgentEventReadModelProjector.project(stream.events)
        val progress = projection.activityByTurn.getValue(EventStream.TURN_ID).publicProgressHistory
        val stage = projection.conversation.entries.single { it.kind == ConversationEntryKind.ASSISTANT_STAGE_RESULT }
        assertEquals(2, progress.size)
        assertTrue(progress.first().sequence < stage.sequence)
        assertTrue(progress.last().sequence > stage.sequence)
        assertEquals(listOf("after"), progress.last().roundIds)
    }

    @Test
    fun execution_round_timing_includes_model_request_and_all_actions_until_the_last_receipt() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("核对资料")
        stream.roundStart("round-timing", "detail-timing")
        val startedAt = stream.events.last().occurredAt
        fun timing() = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn.getValue(EventStream.TURN_ID).executionRoundTimings.getValue("round-timing")
        assertEquals(ExecutionRoundTimingReadModel("round-timing", startedAt, null), timing())
        stream.roundCompleted("round-timing", "detail-timing", kind = null, blocks = emptyList())
        stream.toolCall("round-timing", "call-one", "detail-timing")
        stream.toolCall("round-timing", "call-two", "detail-timing")
        stream.toolStarted("round-timing", "call-one", "detail-timing")
        stream.toolFinished("round-timing", "call-one", "detail-timing", verifiedResult("已读取"))
        stream.toolStarted("round-timing", "call-two", "detail-timing")
        assertEquals(null, timing().finishedAt)
        stream.toolFinished("round-timing", "call-two", "detail-timing", verifiedResult("已核对"))
        val finishedAt = stream.events.last().occurredAt
        assertEquals(ExecutionRoundTimingReadModel("round-timing", startedAt, finishedAt), timing())
        stream.attachment("call-two")
        assertEquals(finishedAt, timing().finishedAt)
    }

    @Test
    fun model_only_and_rejected_rounds_keep_their_thinking_time() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("核对资料")
        stream.roundStart("round-thought", "detail-thought")
        val startedAt = stream.events.last().occurredAt
        stream.roundCompleted("round-thought", "detail-thought", kind = null, blocks = emptyList())
        val modelFinishedAt = stream.events.last().occurredAt
        fun timing() = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn.getValue(EventStream.TURN_ID).executionRoundTimings.getValue("round-thought")
        assertEquals(ExecutionRoundTimingReadModel("round-thought", startedAt, modelFinishedAt), timing())
        stream.rejectedCall("rejected")
        assertEquals(stream.events.last().occurredAt, timing().finishedAt)
        assertEquals(startedAt, timing().startedAt)
    }

    @Test
    fun terminal_interrupted_round_stops_at_the_recorded_turn_completion() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("核对资料")
        stream.roundStart("round-interrupted", "detail-interrupted")
        val startedAt = stream.events.last().occurredAt
        stream.completeTurn("已停止")
        val timing = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn.getValue(EventStream.TURN_ID).executionRoundTimings.getValue("round-interrupted")
        assertEquals(ExecutionRoundTimingReadModel("round-interrupted", startedAt, stream.events.last().occurredAt), timing)
    }

    @Test
    fun action_title_replays_from_serialized_events_and_stays_on_the_same_row() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("核对资料")
        stream.roundStart("round-title", "detail-title")
        stream.roundCompleted("round-title", "detail-title", kind = null, blocks = emptyList())
        stream.toolCall("round-title", "call-title", "detail-title", actionTitle = "读取相关资料")
        stream.toolStarted("round-title", "call-title", "detail-title")
        val pending = AgentEventReadModelProjector.project(stream.events)
        assertEquals("读取相关资料", pending.activityByTurn.getValue(EventStream.TURN_ID).currentActionTitle)
        stream.toolFinished("round-title", "call-title", "detail-title", verifiedResult("已读取"))
        val json = Json
        val restored = stream.events.map { json.decodeFromString<AgentEventEnvelope>(json.encodeToString(it)) }
        val replay = AgentEventReadModelProjector.project(restored)
        val entry = replay.activityByTurn.getValue(EventStream.TURN_ID).entries.filterIsInstance<ToolActivityReadModel>().single()
        assertEquals("call-title", entry.callId)
        assertEquals("读取相关资料", entry.presentation.actionTitle)
        assertEquals(ToolActivityState.VERIFIED, entry.state)
        assertEquals(null, replay.activityByTurn.getValue(EventStream.TURN_ID).currentActionTitle)
        assertEquals("读取相关资料", replay.toolDetails.getValue("call-title").presentation.actionTitle)
    }

    @Test
    fun parameter_details_hide_batch_input_and_query_with_configured_sensitive_paths() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("输入内容")
        stream.roundStart("round-input", "detail-input")
        stream.roundCompleted("round-input", "detail-input", kind = null, blocks = emptyList())
        stream.toolCall(
            "round-input", "call-input", "detail-input",
            argumentsJson = """{"query":"private-query","actions":[{"op":"type_text","text":"private-value"}]}""",
            sensitivePaths = listOf("text", "query", "actions"), actionTitle = "输入指定内容"
        )
        stream.toolStarted("round-input", "call-input", "detail-input")
        val arguments = AgentEventReadModelProjector.project(stream.events)
            .toolDetails.getValue("call-input").argumentsJson
        assertFalse(arguments.contains("private-query"))
        assertFalse(arguments.contains("private-value"))
        assertTrue(arguments.contains("actions"))
        assertTrue(arguments.contains("<redacted>"))
    }

    @Test
    fun userImageAttachmentsSurviveTheCanonicalConversationProjection() {
        val stream = EventStream()
        val attachment = UserAttachmentPayload(
            attachmentId = "message-attachment-1",
            contentRef = "sha256:${"a".repeat(64)}",
            contentHash = "a".repeat(64),
            sizeBytes = 128L,
            mimeType = "image/png"
        )
        stream.thread()
        stream.turn()
        stream.userMessage("describe this", listOf(attachment))

        val projection = AgentEventReadModelProjector.project(stream.events)

        assertEquals(
            listOf(attachment),
            projection.conversation.entries.single().attachments
        )
    }

    @Test
    fun uiObservationQuarantinesAMalformedThreadWithoutWeakeningStrictReplay() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("筛选价格")
        stream.roundStart("round-1", detailRef = "round-detail-1")
        stream.roundCompleted(
            roundId = "round-1",
            detailRef = "round-detail-1",
            kind = null,
            blocks = listOf("点击最低价输入框")
        )

        assertProjectionCode("PUBLIC_MESSAGE_CLASSIFICATION_MISMATCH", stream.events)
        val result = AgentThreadProjectionResult.fromEvents(stream.events)
        assertTrue(result is AgentThreadProjectionResult.Quarantined)
        val failure = (result as AgentThreadProjectionResult.Quarantined).failure
        assertEquals("PUBLIC_MESSAGE_CLASSIFICATION_MISMATCH", failure.code)
        assertEquals(stream.events.last().eventId, failure.eventId)
        assertEquals(stream.events.last(), stream.events.single { it.eventId == failure.eventId })
    }

    @Test
    fun startReceiptMayFollowTurnLocalProgressBeforeTheFirstDeviceAction() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("处理页面")

        stream.roundStart("round-local", detailRef = "round-detail-local")
        stream.roundCompleted(
            roundId = "round-local",
            detailRef = "round-detail-local",
            kind = PublicMessageKind.PROGRESS,
            blocks = listOf("先等待页面稳定")
        )
        stream.toolCall(
            roundId = "round-local",
            callId = "call-local",
            detailRef = "round-detail-local",
            name = "wait"
        )
        stream.progress(
            roundId = "round-local",
            blocks = listOf("先等待页面稳定"),
            detailRef = "round-detail-local"
        )
        stream.toolStarted("round-local", "call-local", "round-detail-local")
        stream.toolFinished(
            roundId = "round-local",
            callId = "call-local",
            detailRef = "round-detail-local",
            result = verifiedResult("等待完成")
        )

        stream.roundStart("round-device", detailRef = "round-detail-device")
        stream.roundCompleted(
            roundId = "round-device",
            detailRef = "round-detail-device",
            kind = PublicMessageKind.START,
            blocks = listOf("现在开始处理设备页面")
        )
        stream.toolCall(
            roundId = "round-device",
            callId = "call-device",
            detailRef = "round-detail-device",
            name = "click"
        )
        stream.assistant(
            roundId = "round-device",
            messageId = "assistant-start",
            kind = PublicMessageKind.START,
            blocks = listOf("现在开始处理设备页面"),
            detailRef = "round-detail-device"
        )
        stream.toolStarted("round-device", "call-device", "round-detail-device")
        stream.toolFinished(
            roundId = "round-device",
            callId = "call-device",
            detailRef = "round-detail-device",
            result = verifiedResult("点击完成")
        )

        val projection = AgentEventReadModelProjector.project(stream.events)

        assertEquals(1, projection.turns.size)
        assertTrue(projection.conversation.entries.any {
            it.kind == ConversationEntryKind.ASSISTANT_START &&
                it.text == "现在开始处理设备页面"
        })
    }

    @Test
    fun context_checkpoint_is_a_typed_replay_fact_not_an_unchecked_json_object() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.malformedContextCheckpoint()

        assertProjectionCode("INVALID_TYPED_PAYLOAD", stream.events)
    }

    @Test
    fun memory_context_loaded_is_a_turn_scoped_replay_fact() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("hello")
        stream.memoryContextLoaded()

        val projection = AgentEventReadModelProjector.project(stream.events)

        assertEquals(EventStream.TURN_ID, projection.turns.single().turnId)
    }

    @Test
    fun androidActivationAdvancesToEpochOneWithoutSwitchingProviderOrModel() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("open settings")
        stream.roundStart("phase-a", detailRef = "model-round:phase-a")
        stream.roundCompleted(
            roundId = "phase-a",
            detailRef = "model-round:phase-a",
            kind = PublicMessageKind.START,
            blocks = listOf("I will check the requested state."),
            finishMetadata = mapOf(
                ZERO_TOOL_PHASE_KEY to ZERO_TOOL_PHASE_VALUE,
                ZERO_TOOL_OPENING_VERSION_KEY to ZERO_TOOL_OPENING_VERSION,
                ZERO_TOOL_DECISION_KEY to ZeroToolRoundDecision.ACTIVATE_ANDROID.wireName
            ),
            executionMode = RoundExecutionMode.NONE,
            followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS
        )
        stream.assistant("phase-a", "opening:phase-a", PublicMessageKind.START, listOf("I will check the requested state."), "model-round:phase-a")
        stream.activateAndroid()

        val turn = AgentEventReadModelProjector.project(stream.events).turns.single()

        assertEquals(1L, turn.modelSessionEpoch)
        assertEquals("provider", turn.modelSessionIdentity.providerId)
        assertEquals("model", turn.modelSessionIdentity.modelId)
        assertEquals("4".repeat(64), turn.modelSessionIdentity.stablePrefixHash)
    }

    @Test
    fun androidActivationRejectsAnUnclassifiedPriorModelRound() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("open settings")
        stream.roundStart("other-round", detailRef = "model-round:other-round")
        stream.roundCompleted(
            roundId = "other-round",
            detailRef = "model-round:other-round",
            kind = PublicMessageKind.FINAL,
            blocks = listOf("not an activation boundary")
        )
        stream.activateAndroid()

        assertProjectionCode("INVALID_MODEL_SESSION_ACTIVATION_BOUNDARY", stream.events)
    }

    @Test
    fun incremental_round_projection_keeps_last_progress_until_new_public_progress_commits() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("Inspect the current screen")
        stream.roundStart("opening", "opening-detail")
        stream.roundCompleted("opening", "opening-detail", PublicMessageKind.START, listOf("I will inspect."))
        stream.assistant("opening", "opening-message", PublicMessageKind.START, listOf("I will inspect."), "opening-detail")
        var previous: String? = null
        for (index in 1..3) {
            val round = "progress-$index"
            val detail = "detail-$index"
            val next = "Public observation $index"
            stream.roundStart(round, detail)
            assertEquals(previous, AgentEventReadModelProjector.project(stream.events)
                .activityByTurn.getValue(EventStream.TURN_ID).publicProgress)
            stream.roundCompleted(round, detail, PublicMessageKind.PROGRESS, listOf(next))
            stream.progress(round, listOf(next), detail)
            val projected = AgentEventReadModelProjector.project(stream.events)
            val activity = projected.activityByTurn.getValue(EventStream.TURN_ID)
            assertEquals(next, activity.publicProgress)
            assertEquals((1..index).map { "Public observation $it" }, activity.publicProgressHistory.map { it.text })
            assertEquals(stream.events.filter { it.eventKind == AgentEventKind.PUBLIC_PROGRESS }.map { it.eventId },
                activity.publicProgressHistory.map { it.eventId })
            assertEquals(1, projected.conversation.entries.count { it.kind == ConversationEntryKind.ASSISTANT_START })
            previous = next
        }
    }

    @Test
    fun duplicate_public_text_keeps_the_rounds_needed_to_resolve_all_receipts() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("Inspect the screen")
        for (index in 1..2) {
            stream.roundStart("round-$index", "detail-$index")
            stream.roundCompleted("round-$index", "detail-$index", PublicMessageKind.PROGRESS, listOf("Checking details"))
            stream.progress("round-$index", listOf("Checking details"), "detail-$index")
        }
        val history = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn.getValue(EventStream.TURN_ID).publicProgressHistory
        assertEquals(1, history.size)
        assertEquals(listOf("round-1", "round-2"), history.single().roundIds)
        assertEquals(stream.events.first { it.eventKind == AgentEventKind.PUBLIC_PROGRESS }.eventId, history.single().eventId)
    }

    @Test
    fun one_replay_drives_home_im_activity_detail_and_complete_record() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("请处理")

        stream.roundStart("round-1", detailRef = "round-detail-1")
        stream.roundCompleted(
            roundId = "round-1",
            detailRef = "round-detail-1",
            kind = PublicMessageKind.START,
            blocks = listOf("我先检查当前状态")
        )
        stream.toolCall(
            roundId = "round-1",
            callId = "call-1",
            detailRef = "round-detail-1",
            argumentsJson = "{\"secret\":\"hidden\",\"value\":\"visible\"}",
            sensitivePaths = listOf("arguments.secret")
        )
        stream.assistant(
            roundId = "round-1",
            messageId = "assistant-start",
            kind = PublicMessageKind.START,
            blocks = listOf("我先检查当前状态"),
            detailRef = "round-detail-1"
        )
        stream.toolStarted("round-1", "call-1", "round-detail-1")
        stream.toolFinished(
            roundId = "round-1",
            callId = "call-1",
            detailRef = "round-detail-1",
            result = verifiedResult("已检查")
        )

        stream.roundStart("round-2", detailRef = "round-detail-2")
        stream.roundCompleted(
            roundId = "round-2",
            detailRef = "round-detail-2",
            kind = PublicMessageKind.PROGRESS,
            blocks = listOf("正在执行下一步")
        )
        stream.toolCall(
            roundId = "round-2",
            callId = "call-2",
            detailRef = "round-detail-2"
        )
        stream.progress(
            roundId = "round-2",
            blocks = listOf("正在执行下一步"),
            detailRef = "round-detail-2"
        )
        stream.toolStarted("round-2", "call-2", "round-detail-2")
        stream.toolFinished(
            roundId = "round-2",
            callId = "call-2",
            detailRef = "round-detail-2",
            result = ToolExecutionResult(
                outputText = "未取得证据",
                executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                diagnosticCode = "EVIDENCE_TIMEOUT"
            )
        )
        stream.completeTurn(assistantReply = "处理完成")

        val projection = AgentEventReadModelProjector.project(stream.events)

        assertEquals(
            listOf(
                ConversationEntryKind.USER_MESSAGE,
                ConversationEntryKind.ASSISTANT_START,
                ConversationEntryKind.TURN_ASSISTANT_REPLY
            ),
            projection.conversation.entries.map { it.kind }
        )
        assertEquals(
            listOf(ConversationEntryKind.ASSISTANT_START, ConversationEntryKind.TURN_ASSISTANT_REPLY),
            projection.conversation.publicReplies(EventStream.TURN_ID).map { it.kind }
        )

        val activity = projection.activityByTurn.getValue(EventStream.TURN_ID)
        val tools = activity.entries.filterIsInstance<ToolActivityReadModel>()
        assertEquals(2, tools.size)
        assertEquals(2, activity.operationCount)
        assertEquals(ToolActivityState.VERIFIED, tools[0].state)
        assertEquals(ToolActivityState.UNVERIFIED, tools[1].state)
        assertEquals("正在执行下一步", activity.publicProgress)
        assertEquals("正在执行下一步", tools[1].publicProgress)
        assertFalse(activity.entries.any { it.itemId.startsWith("progress") })

        val firstDetail = projection.toolDetails.getValue("call-1")
        assertTrue(firstDetail.argumentsJson.contains("<redacted>"))
        assertTrue(firstDetail.argumentsJson.contains("visible"))
        assertEquals("round-detail-1", firstDetail.roundDetailRef)
        assertEquals(listOf("正在执行下一步"), projection.toolDetails.getValue("call-2").publicProgressBlocks)
        assertEquals(stream.events.map { it.eventId }, projection.completeRecord.sourceEventIds)
        assertEquals(stream.events, projection.completeRecord.events)
    }

    @Test
    fun absent_optional_sensitive_field_does_not_hide_safe_action_coordinates() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("点击指定位置")
        stream.roundStart("round-1", detailRef = "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.toolCall(
            roundId = "round-1",
            callId = "click-call",
            detailRef = "round-detail-1",
            name = "screen_act",
            argumentsJson = """{"actions":[{"op":"click","x":320,"y":640}]}""",
            sensitivePaths = listOf("text")
        )
        stream.toolStarted("round-1", "click-call", "round-detail-1")
        stream.toolFinished(
            roundId = "round-1",
            callId = "click-call",
            detailRef = "round-detail-1",
            result = verifiedResult("已点击")
        )

        val detail = AgentEventReadModelProjector.project(stream.events)
            .toolDetails
            .getValue("click-call")

        assertTrue(detail.argumentsJson.contains("\"x\":320"))
        assertTrue(detail.argumentsJson.contains("\"y\":640"))
        assertFalse(detail.argumentsJson.contains("<redacted>"))
    }

    @Test
    fun successful_result_card_is_projected_from_its_durable_tool_arguments_only() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("展示结果")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.toolCall(
            roundId = "round-1",
            callId = "card-success",
            detailRef = "round-detail-1",
            name = "present_result_card",
            category = ToolPresentationCategory.SUPPORTING,
            effect = ToolEffect.STATE_CHANGING,
            argumentsJson = """{
                "title":"可继续处理",
                "items":[
                    {"text":"第一项","deeplink":"droidance://result/one"},
                    {"text":"第二项","deeplink":"droidance://result/two"}
                ]
            }"""
        )
        stream.toolStarted("round-1", "card-success", "round-detail-1")
        stream.toolFinished(
            roundId = "round-1",
            callId = "card-success",
            detailRef = "round-detail-1",
            result = verifiedResult("accepted")
        )
        stream.toolCall(
            roundId = "round-1",
            callId = "card-failed",
            detailRef = "round-detail-1",
            name = "present_result_card",
            category = ToolPresentationCategory.SUPPORTING,
            effect = ToolEffect.STATE_CHANGING,
            argumentsJson = """{
                "title":"不应显示",
                "items":[{"text":"无效结果","deeplink":"droidance://result/failed"}]
            }"""
        )
        stream.toolStarted("round-1", "card-failed", "round-detail-1")
        stream.toolFinished(
            roundId = "round-1",
            callId = "card-failed",
            detailRef = "round-detail-1",
            result = ToolExecutionResult(
                outputText = "failed",
                executionStatus = ToolExecutionStatus.FAILED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                diagnosticCode = "RESULT_CARD_REJECTED"
            )
        )

        val cards = AgentEventReadModelProjector.project(stream.events).resultCards

        assertEquals(1, cards.size)
        assertEquals("card-success", cards.single().callId)
        assertEquals("可继续处理", cards.single().title)
        assertEquals(
            listOf("第一项", "第二项"),
            cards.single().items.map(ResultCardItemReadModel::text)
        )
        assertEquals(
            listOf("droidance://result/one", "droidance://result/two"),
            cards.single().items.map(ResultCardItemReadModel::deeplink)
        )
    }

    @Test
    fun obsolete_result_card_shape_is_skipped_without_aborting_projection() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("展示结果")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.toolCall(
            roundId = "round-1",
            callId = "card-legacy",
            detailRef = "round-detail-1",
            name = "present_result_card",
            category = ToolPresentationCategory.SUPPORTING,
            effect = ToolEffect.STATE_CHANGING,
            argumentsJson = """{
                "title":"监测已开启",
                "summary":"QQ消息自动回复监测已启动"
            }"""
        )
        stream.toolStarted("round-1", "card-legacy", "round-detail-1")
        stream.toolFinished(
            roundId = "round-1",
            callId = "card-legacy",
            detailRef = "round-detail-1",
            result = verifiedResult("accepted")
        )

        val model = AgentEventReadModelProjector.project(stream.events)

        assertTrue(model.resultCards.isEmpty())
        assertEquals("card-legacy", model.toolDetails.getValue("card-legacy").callId)
    }

    @Test
    fun unparseable_result_card_is_skipped_instead_of_aborting_thread_projection() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("展示结果")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.toolCall(
            roundId = "round-1",
            callId = "card-broken",
            detailRef = "round-detail-1",
            name = "present_result_card",
            category = ToolPresentationCategory.SUPPORTING,
            effect = ToolEffect.STATE_CHANGING,
            argumentsJson = """{"title":"只有标题"}"""
        )
        stream.toolStarted("round-1", "card-broken", "round-detail-1")
        stream.toolFinished(
            roundId = "round-1",
            callId = "card-broken",
            detailRef = "round-detail-1",
            result = verifiedResult("accepted")
        )

        val model = AgentEventReadModelProjector.project(stream.events)

        assertTrue(model.resultCards.isEmpty())
        assertEquals("card-broken", model.toolDetails.getValue("card-broken").callId)
    }

    @Test
    fun presentation_and_operation_count_are_structural_not_text_based() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("执行")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.attemptCreated()
        stream.attemptState(ExecutionAttemptState.RUNNING)

        stream.settledCall(
            callId = "primary",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.PRIMARY,
            binding = ToolBinding.DEVICE_BOUND,
            name = "android_read",
            result = verifiedResult("文本里即使写失败也不改变结构化成功")
        )
        stream.settledCall(
            callId = "supporting",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.SUPPORTING,
            binding = ToolBinding.DEVICE_BOUND,
            name = "shell",
            result = verifiedResult("ok")
        )
        stream.settledCall(
            callId = "device-failed",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.PRIMARY,
            binding = ToolBinding.DEVICE_BOUND,
            name = "screen_act",
            effect = ToolEffect.STATE_CHANGING,
            result = ToolExecutionResult(
                outputText = "返回失败也计为一次设备交互尝试",
                executionStatus = ToolExecutionStatus.FAILED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                diagnosticCode = "DEVICE_ACTION_FAILED"
            )
        )
        listOf("program", "task_memo", "skill", "memory").forEach { name ->
            stream.settledCall(
                callId = "local-$name",
                source = ToolCallSource.MODEL_REQUESTED,
                category = ToolPresentationCategory.PRIMARY,
                binding = ToolBinding.TURN_LOCAL,
                name = name,
                result = verifiedResult("ok")
            )
        }
        stream.settledCall(
            callId = "automatic-success",
            source = ToolCallSource.HARNESS_AUTOMATIC,
            category = ToolPresentationCategory.DIAGNOSTIC,
            result = verifiedResult("ok"),
            triggerCallId = "primary"
        )
        stream.settledCall(
            callId = "automatic-failed",
            source = ToolCallSource.HARNESS_AUTOMATIC,
            category = ToolPresentationCategory.DIAGNOSTIC,
            result = ToolExecutionResult(
                outputText = "failed",
                executionStatus = ToolExecutionStatus.FAILED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                diagnosticCode = "AUTOMATIC_CHECK_FAILED"
            ),
            triggerCallId = "primary"
        )
        stream.settledCall(
            callId = "automatic-device",
            source = ToolCallSource.HARNESS_AUTOMATIC,
            category = ToolPresentationCategory.DIAGNOSTIC,
            binding = ToolBinding.DEVICE_BOUND,
            result = verifiedResult("ok"),
            triggerCallId = "primary"
        )
        stream.settledCall(
            callId = "completion",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.COMPLETION,
            effect = ToolEffect.COMPLETION,
            result = verifiedResult("done")
        )
        stream.settledCall(
            callId = "intervention",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.SUPPORTING,
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.USER_INTERVENTION,
            result = verifiedResult("resolved")
        )
        stream.rejectedCall("rejected", binding = ToolBinding.DEVICE_BOUND)
        stream.attemptState(ExecutionAttemptState.COMPLETED)
        stream.completeTurn(assistantReply = "完成")

        val activity = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn
            .getValue(EventStream.TURN_ID)
        val byId = activity.entries.filterIsInstance<ToolActivityReadModel>().associateBy { it.callId }

        assertEquals(7, activity.operationCount)
        assertEquals(
            setOf("primary", "supporting", "device-failed", "local-program", "local-task_memo", "local-skill", "local-memory"),
            byId.values.filter(ToolActivityReadModel::operationCounted).map { it.callId }.toSet()
        )
        assertTrue(byId.getValue("primary").visible)
        assertTrue(byId.getValue("supporting").visible)
        assertFalse(byId.getValue("automatic-success").visible)
        assertFalse(byId.getValue("automatic-failed").visible)
        val details = AgentEventReadModelProjector.project(stream.events).toolDetails
        assertEquals("primary", details.getValue("automatic-success").triggerCallId)
        assertEquals("primary", details.getValue("automatic-failed").triggerCallId)
        assertFalse(byId.getValue("completion").visible)
        assertFalse(byId.getValue("intervention").visible)
        assertTrue(byId.getValue("rejected").visible)
        assertEquals(ToolActivityState.REJECTED, byId.getValue("rejected").state)
    }

    @Test
    fun device_interaction_count_waits_for_a_return_and_counts_a_batch_once() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("执行批量操作")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.attemptCreated()
        stream.attemptState(ExecutionAttemptState.RUNNING)
        stream.toolCall(
            roundId = "round-1",
            callId = "batch",
            detailRef = "round-detail-1",
            name = "screen_act",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            argumentsJson = """{"actions":[{"op":"click","x":10,"y":20},{"op":"click","x":30,"y":40}]}"""
        )
        stream.toolStarted("round-1", "batch", "round-detail-1", deviceBound = true)

        val pending = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn.getValue(EventStream.TURN_ID)
        assertEquals(0, pending.operationCount)
        assertEquals(ToolActivityState.PENDING, pending.entries.filterIsInstance<ToolActivityReadModel>().single().state)

        stream.toolFinished(
            roundId = "round-1",
            callId = "batch",
            detailRef = "round-detail-1",
            result = verifiedResult("两次点击已返回"),
            deviceBound = true
        )
        val returned = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn.getValue(EventStream.TURN_ID)
        assertEquals(1, returned.operationCount)

        stream.settledCall(
            callId = "not-executed",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.PRIMARY,
            binding = ToolBinding.DEVICE_BOUND,
            name = "screen_act",
            effect = ToolEffect.STATE_CHANGING,
            result = ToolExecutionResult(
                outputText = "action not executed",
                executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                diagnosticCode = "STALE_OBSERVATION"
            )
        )
        val notExecuted = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn.getValue(EventStream.TURN_ID)
        assertEquals(1, notExecuted.operationCount)
        assertFalse(notExecuted.entries.filterIsInstance<ToolActivityReadModel>().last().operationCounted)
    }

    @Test
    fun command_sources_preserve_actual_tool_inputs_without_inventing_a_shell_invocation() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("执行")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        val inputs = listOf(
            Triple("inline", "program", """{"op":"run","code":"console.log('hello');"}"""),
            Triple("file", "program", """{"op":"run","path":"scripts/task.js"}"""),
            Triple("poll", "program", """{"op":"poll","handle":"run-1"}"""),
            Triple("read", "workspace", """{"op":"read","path":"notes.txt"}"""),
            Triple("write", "workspace", """{"op":"write","path":"script.js","text":"console.log('saved');"}""")
        )
        inputs.forEach { (id, name, arguments) ->
            stream.toolCall("round-1", id, "round-detail-1", name = name, argumentsJson = arguments)
            stream.toolStarted("round-1", id, "round-detail-1")
            stream.toolFinished("round-1", id, "round-detail-1", verifiedResult("returned content"))
        }
        val details = AgentEventReadModelProjector.project(stream.events).toolDetails
        assertEquals("console.log('hello');", details.getValue("inline").commandSource)
        assertEquals("scripts/task.js", details.getValue("file").commandSource)
        inputs.drop(2).forEach { (id, name, arguments) ->
            assertEquals("$name $arguments", details.getValue(id).commandSource)
        }
    }

    @Test
    fun late_attachment_is_appended_without_changing_settled_tool() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("执行")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.settledCall(
            callId = "call-1",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.PRIMARY,
            result = verifiedResult("ok")
        )
        stream.completeTurn(assistantReply = "完成")
        stream.attachment("call-1")

        val projection = AgentEventReadModelProjector.project(stream.events)
        val detail = projection.toolDetails.getValue("call-1")

        assertEquals(1, detail.attachments.size)
        assertEquals(DetailAttachmentRole.RESULT, detail.attachments.single().role)
        assertEquals(123L, detail.attachments.single().sizeBytes)
        assertEquals(ToolExecutionStatus.SUCCEEDED, detail.executionStatus)
        assertEquals(1, projection.activityByTurn.getValue(EventStream.TURN_ID).operationCount)
    }

    @Test fun retainedImagesKeepHistoricalIdentityAndOriginalTimeWhenReadDuringAnotherOperation() {
        val stream = EventStream()
        stream.thread(); stream.turn(); stream.userMessage("Read an earlier source")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.settledCall("source", ToolCallSource.MODEL_REQUESTED, ToolPresentationCategory.PRIMARY, verifiedResult("Source"))
        stream.attachment("source")
        val sourceEvent = stream.events.last()
        val source = Json.decodeFromString<DetailAttachmentAddedEventPayload>(sourceEvent.payloadJson)
        stream.roundStart("round-2", "round-detail-2")
        stream.roundCompleted("round-2", "round-detail-2", kind = null, blocks = emptyList())
        stream.settledCall("current", ToolCallSource.MODEL_REQUESTED, ToolPresentationCategory.PRIMARY, verifiedResult("Current"))
        stream.attachment("current")
        stream.settledCall("history", ToolCallSource.MODEL_REQUESTED, ToolPresentationCategory.PRIMARY,
            verifiedResult("Historical").copy(historicalAttachment = source))
        stream.attachment("history")
        val details = AgentEventReadModelProjector.project(stream.events).toolDetails
        assertFalse(details.getValue("current").attachments.single().historical)
        val historical = details.getValue("history").attachments.single()
        assertTrue(historical.historical)
        assertEquals(sourceEvent.occurredAt, historical.sourceOccurredAt)
        assertEquals(source.contentRef, historical.contentRef)
    }

    @Test
    fun laterCurrentObservationKeepsTheOriginalNotExecutedCallStatus() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("检查当前状态")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.settledCall(
            callId = "call-1",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.PRIMARY,
            effect = ToolEffect.STATE_CHANGING,
            result = ToolExecutionResult(
                outputText = "action not executed",
                executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                diagnosticCode = "STALE_OBSERVATION"
            )
        )
        stream.completeTurn(assistantReply = "已保留观察")
        stream.attachment("call-1", DetailAttachmentRole.CURRENT_OBSERVATION)

        val detail = AgentEventReadModelProjector.project(stream.events).toolDetails.getValue("call-1")

        assertEquals(ToolExecutionStatus.NOT_EXECUTED, detail.executionStatus)
        assertEquals(DetailAttachmentRole.CURRENT_OBSERVATION, detail.attachments.single().role)
        assertEquals("observation-1", detail.attachments.single().observationId)
    }

    @Test
    fun batch_screenshot_positions_replay_from_retained_evidence_without_guessing_attachment_names() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("查看页面")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        val attachment = DetailAttachmentAddedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION,
            "attachment-call-1", DetailAttachmentRole.RESULT, "sha256:${"c".repeat(64)}", "c".repeat(64),
            123L, "image/png", 100, 200, "observation-1")
        stream.settledCall(callId = "call-1", source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.PRIMARY, result = verifiedResult("ok").copy(
                batchScreenshots = listOf(BatchScreenshotEvidence(3, 100, "execution", "backend", 1, 0, attachment))))
        stream.attachment("call-1")
        stream.settledCall(callId = "call-2", source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.PRIMARY, result = verifiedResult("ok"))
        stream.attachment("call-2")
        val restored = stream.events.map { Json.decodeFromString<AgentEventEnvelope>(Json.encodeToString(it)) }
        val projection = AgentEventReadModelProjector.project(restored)
        assertEquals(3, projection.toolDetails.getValue("call-1").attachments.single().batchActionIndex)
        assertEquals(null, projection.toolDetails.getValue("call-2").attachments.single().batchActionIndex)
    }

    @Test
    fun toolDetailRetainsIndependentActionEvidence() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("执行")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        val expected = ToolActionEvidence(
            delivery = ActionDeliveryEvidence.ACTION_DELIVERED,
            transition = StateTransitionEvidence.STATE_CHANGED,
            goalProgress = GoalProgressEvidence.NOT_PROVEN
        )
        stream.settledCall(
            callId = "call-1",
            source = ToolCallSource.MODEL_REQUESTED,
            category = ToolPresentationCategory.PRIMARY,
            result = verifiedResult("changed").copy(actionEvidence = expected)
        )

        val detail = AgentEventReadModelProjector.project(stream.events)
            .toolDetails
            .getValue("call-1")

        assertEquals(expected, detail.actionEvidence)
    }

    @Test
    fun replay_fails_closed_on_order_hash_duplicate_and_post_terminal_mutation() {
        val stream = minimalCompletedStream()

        assertProjectionCode("NON_MONOTONIC_SEQUENCE", stream.events.toMutableList().apply {
            val first = this[1]
            this[1] = this[2]
            this[2] = first
        })
        assertProjectionCode(
            "PAYLOAD_INTEGRITY_FAILURE",
            stream.events.mapIndexed { index, event ->
                if (index == 1) event.copy(payloadHash = "0".repeat(64)) else event
            }
        )
        assertProjectionCode(
            "DUPLICATE_EVENT_ID",
            stream.events.mapIndexed { index, event ->
                if (index == 1) event.copy(eventId = stream.events.first().eventId) else event
            }
        )

        val mutated = EventStream(stream.events)
        mutated.systemNotice("late")
        assertProjectionCode("EVENT_AFTER_TURN_COMPLETION", mutated.events)
    }

    @Test
    fun pause_and_resume_are_visible_lifecycle_facts() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("执行")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted("round-1", "round-detail-1", kind = null, blocks = emptyList())
        stream.attemptCreated()
        stream.attemptState(ExecutionAttemptState.RUNNING)
        stream.attemptState(ExecutionAttemptState.PAUSED_USER, "USER_PAUSED")
        stream.attemptState(ExecutionAttemptState.RUNNING)
        stream.attemptState(ExecutionAttemptState.COMPLETED)
        stream.completeTurn(assistantReply = "完成")

        val activity = AgentEventReadModelProjector.project(stream.events)
            .activityByTurn
            .getValue(EventStream.TURN_ID)
        val lifecycle = activity.entries.filterIsInstance<LifecycleActivityReadModel>()

        assertEquals(
            listOf(
                LifecycleActivityKind.ATTEMPT_PAUSED_USER,
                LifecycleActivityKind.ATTEMPT_RESUMED
            ),
            lifecycle.map { it.kind }
        )
        assertEquals(TurnReadModelState.TERMINAL, activity.state)
    }

    @Test
    fun deviceToolDetailReplaysFrozenBackendDisplayCallAndObservation() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("检查当前屏幕")
        stream.roundStart("round-device", "round-detail-device")
        stream.roundCompleted("round-device", "round-detail-device", kind = null, blocks = emptyList())
        stream.toolCall(
            roundId = "round-device",
            callId = "screen-call",
            detailRef = "round-detail-device",
            name = "android_read",
            binding = ToolBinding.DEVICE_BOUND
        )
        stream.attemptCreated()
        stream.attemptState(ExecutionAttemptState.RUNNING)
        stream.toolStarted("round-device", "screen-call", "round-detail-device", deviceBound = true)
        stream.toolFinished(
            roundId = "round-device",
            callId = "screen-call",
            detailRef = "round-detail-device",
            result = ToolExecutionResult(
                outputText = "observed",
                executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = ToolEvidenceStatus.CAPTURED,
                observationId = "observation-current"
            ),
            deviceBound = true
        )
        stream.attemptState(ExecutionAttemptState.COMPLETED)
        stream.completeTurn(assistantReply = "已检查")

        val detail = AgentEventReadModelProjector.project(stream.events)
            .toolDetails
            .getValue("screen-call")

        assertEquals("screen-call", detail.callId)
        assertEquals("backend-session", detail.backendSessionId)
        assertEquals(0, detail.displayId)
        assertEquals("observation-current", detail.observationId)
        assertEquals(EventStream.ATTEMPT_ID, detail.attemptId)
        assertEquals(EventStream.EXECUTION_ID, detail.executionId)
    }

    @Test
    fun turn_recovery_without_attempt_is_persistently_paused() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("执行")
        stream.turnRecoveryRequired("process-2")

        val projection = AgentEventReadModelProjector.project(stream.events)
        val activity = projection.activityByTurn.getValue(EventStream.TURN_ID)

        assertEquals(TurnReadModelState.PAUSED_RECOVERY_REQUIRED, projection.turns.single().state)
        assertEquals(TurnReadModelState.PAUSED_RECOVERY_REQUIRED, activity.state)
        assertEquals(
            listOf(LifecycleActivityKind.TURN_PAUSED_RECOVERY_REQUIRED),
            activity.entries.filterIsInstance<LifecycleActivityReadModel>().map { it.kind }
        )
    }

    @Test
    fun recovery_resume_supersedes_old_intervention_and_remains_a_single_epoch_boundary() {
        val stream = interventionStream().apply {
            toolFinished("round-1", "intervention", "round-detail-1", verifiedResult("ready"))
            turnRecoveryRequired("process-1")
        }
        val recoveryId = stream.events.last().eventId
        stream.resumeRecovery(recoveryId)
        val resumed = AgentEventReadModelProjector.project(stream.events)
        assertEquals(TurnReadModelState.RUNNING, resumed.turns.single().state)
        assertEquals(1L, resumed.turns.single().modelSessionEpoch)
        stream.resumeRecovery(recoveryId, previousEpoch = 1L)
        val failure = runCatching { AgentEventReadModelProjector.project(stream.events) }.exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test
    fun recovery_epoch_rejects_wrong_pause_or_unsettled_call() {
        val wrongPause = EventStream().apply {
            thread()
            turn()
            userMessage("continue")
            turnRecoveryRequired("process-1")
            resumeRecovery("other-pause")
        }
        assertTrue(runCatching { AgentEventReadModelProjector.project(wrongPause.events) }.isFailure)
        val pending = interventionStream().apply { turnRecoveryRequired("process-1") }
        pending.resumeRecovery(pending.events.last().eventId)
        assertTrue(runCatching { AgentEventReadModelProjector.project(pending.events) }.isFailure)
    }

    @Test
    fun rejected_intervention_message_replays_without_waiting_for_user() {
        val stream = EventStream()
        stream.thread()
        stream.turn()
        stream.userMessage("执行")
        stream.roundStart("round-1", "round-detail-1")
        stream.roundCompleted(
            "round-1",
            "round-detail-1",
            kind = PublicMessageKind.USER_INTERVENTION,
            blocks = listOf("请接管并完成验证")
        )
        stream.assistant(
            roundId = "round-1",
            messageId = "intervention-message",
            kind = PublicMessageKind.USER_INTERVENTION,
            blocks = listOf("请接管并完成验证"),
            detailRef = "round-detail-1"
        )
        stream.rejectedCall("intervention", ToolEffect.USER_INTERVENTION)

        val projection = AgentEventReadModelProjector.project(stream.events)

        assertEquals(
            TurnReadModelState.RUNNING,
            projection.activityByTurn.getValue(EventStream.TURN_ID).state
        )
        assertEquals(
            listOf(
                ConversationEntryKind.USER_MESSAGE,
                ConversationEntryKind.ASSISTANT_USER_INTERVENTION
            ),
            projection.conversation.entries.map(ConversationEntryReadModel::kind)
        )
    }

    @Test
    fun intervention_wait_state_requires_a_verified_settled_result() {
        val pending = interventionStream()
        assertEquals(
            TurnReadModelState.RUNNING,
            AgentEventReadModelProjector.project(pending.events)
                .activityByTurn.getValue(EventStream.TURN_ID).state
        )

        pending.toolFinished(
            roundId = "round-1",
            callId = "intervention",
            detailRef = "round-detail-1",
            result = ToolExecutionResult(
                outputText = "intervention unavailable",
                executionStatus = ToolExecutionStatus.INTERRUPTED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                diagnosticCode = "INTERVENTION_INTERRUPTED"
            )
        )
        assertEquals(
            TurnReadModelState.RUNNING,
            AgentEventReadModelProjector.project(pending.events)
                .activityByTurn.getValue(EventStream.TURN_ID).state
        )

        val settled = interventionStream().apply {
            toolFinished(
                roundId = "round-1",
                callId = "intervention",
                detailRef = "round-detail-1",
                result = verifiedResult("intervention ready")
            )
        }
        assertEquals(
            TurnReadModelState.WAITING_FOR_USER,
            AgentEventReadModelProjector.project(settled.events)
                .activityByTurn.getValue(EventStream.TURN_ID).state
        )
    }

    @Test
    fun phase_a_resume_can_activate_android_without_replaying_the_interrupted_round() {
        val stream = EventStream().apply {
            thread()
            turn()
            userMessage("open settings")
            roundStart("old-round", "old-detail")
            turnRecoveryRequired("process")
        }
        stream.resumeRecovery(stream.events.last().eventId)
        stream.roundStart("new-round", "new-detail")
        stream.roundCompleted(
            roundId = "new-round", detailRef = "new-detail",
            kind = PublicMessageKind.START, blocks = listOf("I will check the requested state."),
            finishMetadata = mapOf(
                ZERO_TOOL_PHASE_KEY to ZERO_TOOL_PHASE_VALUE,
                ZERO_TOOL_OPENING_VERSION_KEY to ZERO_TOOL_OPENING_VERSION,
                ZERO_TOOL_DECISION_KEY to ZeroToolRoundDecision.ACTIVATE_ANDROID.wireName
            ),
            executionMode = RoundExecutionMode.NONE, followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS
        )
        stream.assistant("new-round", "opening:new-round", PublicMessageKind.START, listOf("I will check the requested state."), "new-detail")
        stream.activateAndroid(previousEpoch = 1L)
        val turn = AgentEventReadModelProjector.project(stream.events).turns.single()
        assertEquals(2L, turn.modelSessionEpoch)
        assertEquals("provider", turn.modelSessionIdentity.providerId)
        assertEquals("4".repeat(64), turn.modelSessionIdentity.stablePrefixHash)
    }

    @Test
    fun localActivationUsesTheLocalBoundaryAndSupportsTurnLocalResultsAndRecovery() {
        val stream = localActivationStream()
        stream.roundStart("local-round", "round-detail-local")
        stream.roundCompleted(
            roundId = "local-round",
            detailRef = "round-detail-local",
            kind = null,
            blocks = emptyList()
        )
        stream.toolCall(
            roundId = "local-round",
            callId = "local-search",
            detailRef = "round-detail-local",
            name = "session_search",
            binding = ToolBinding.TURN_LOCAL
        )
        stream.toolStarted("local-round", "local-search", "round-detail-local")
        stream.toolFinished(
            roundId = "local-round",
            callId = "local-search",
            detailRef = "round-detail-local",
            result = verifiedResult("local history")
        )
        stream.turnRecoveryRequired("local-process")
        val recoveryId = stream.events.last().eventId
        stream.resumeRecovery(
            recoveryEventId = recoveryId,
            previousEpoch = 1L,
            identity = modelIdentity().copy(stablePrefixHash = "4".repeat(64))
        )
        stream.roundStart("local-resumed-round", "round-detail-local-resumed")
        stream.roundCompleted(
            roundId = "local-resumed-round",
            detailRef = "round-detail-local-resumed",
            kind = null,
            blocks = emptyList()
        )
        stream.toolCall(
            roundId = "local-resumed-round",
            callId = "local-recalled-skill",
            detailRef = "round-detail-local-resumed",
            name = "recall_skill",
            binding = ToolBinding.TURN_LOCAL
        )
        stream.toolStarted(
            "local-resumed-round",
            "local-recalled-skill",
            "round-detail-local-resumed"
        )
        stream.toolFinished(
            roundId = "local-resumed-round",
            callId = "local-recalled-skill",
            detailRef = "round-detail-local-resumed",
            result = verifiedResult("skill body")
        )

        val projection = AgentEventReadModelProjector.project(stream.events)
        val turn = projection.turns.single()

        assertEquals(TurnActivationKind.LOCAL, turn.activationKind)
        assertEquals(2L, turn.modelSessionEpoch)
        assertEquals(null, turn.activeAttemptId)
        assertEquals("local history", projection.toolDetails.getValue("local-search").outputText)
        assertEquals(ToolBinding.TURN_LOCAL, projection.toolDetails.getValue("local-search").binding)
        assertEquals("skill body", projection.toolDetails.getValue("local-recalled-skill").outputText)
        assertEquals(ToolBinding.TURN_LOCAL, projection.toolDetails.getValue("local-recalled-skill").binding)
        assertEquals(null, projection.toolDetails.getValue("local-search").attemptId)
        assertEquals(null, projection.toolDetails.getValue("local-recalled-skill").attemptId)
        assertEquals(TurnReadModelState.RUNNING, turn.state)
    }

    @Test
    fun localActivationRejectsADeviceAttempt() {
        val stream = localActivationStream()
        stream.roundStart("local-device-round", "round-detail-local-device")
        stream.roundCompleted(
            roundId = "local-device-round",
            detailRef = "round-detail-local-device",
            kind = null,
            blocks = emptyList()
        )
        stream.toolCall(
            roundId = "local-device-round",
            callId = "illegal-device-call",
            detailRef = "round-detail-local-device",
            name = "android_read",
            binding = ToolBinding.DEVICE_BOUND
        )
        stream.attemptCreated()

        assertProjectionCode("LOCAL_TURN_DEVICE_ATTEMPT", stream.events)
    }

    @Test
    fun activationMarkerAndEventKindMustMatch() {
        val localEventAfterAndroidBoundary = EventStream().apply {
            thread()
            turn()
            userMessage("local knowledge")
            roundStart("wrong-local-round", "wrong-local-detail")
            roundCompleted(
                roundId = "wrong-local-round",
                detailRef = "wrong-local-detail",
                kind = PublicMessageKind.START,
                blocks = listOf("I will check the requested state."),
                finishMetadata = mapOf(
                    ZERO_TOOL_PHASE_KEY to ZERO_TOOL_PHASE_VALUE,
                    ZERO_TOOL_OPENING_VERSION_KEY to ZERO_TOOL_OPENING_VERSION,
                ZERO_TOOL_DECISION_KEY to ZeroToolRoundDecision.ACTIVATE_ANDROID.wireName
                ),
                executionMode = RoundExecutionMode.NONE,
                followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS
            )
            assistant("wrong-local-round", "opening:wrong-local-round", PublicMessageKind.START, listOf("I will check the requested state."), "wrong-local-detail")
            activateAndroid(activationKind = TurnActivationKind.LOCAL)
        }
        assertProjectionCode(
            "INVALID_MODEL_SESSION_ACTIVATION_BOUNDARY",
            localEventAfterAndroidBoundary.events
        )

        val androidEventAfterLocalBoundary = EventStream().apply {
            thread()
            turn()
            userMessage("local knowledge")
            roundStart("wrong-android-round", "wrong-android-detail")
            roundCompleted(
                roundId = "wrong-android-round",
                detailRef = "wrong-android-detail",
                kind = PublicMessageKind.START,
                blocks = listOf("I will look up the local knowledge."),
                finishMetadata = mapOf(
                    ZERO_TOOL_PHASE_KEY to ZERO_TOOL_PHASE_VALUE,
                    ZERO_TOOL_OPENING_VERSION_KEY to ZERO_TOOL_OPENING_VERSION,
                ZERO_TOOL_DECISION_KEY to ZeroToolRoundDecision.ACTIVATE_LOCAL.wireName
                ),
                executionMode = RoundExecutionMode.NONE,
                followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS
            )
            assistant("wrong-android-round", "opening:wrong-android-round", PublicMessageKind.START, listOf("I will look up the local knowledge."), "wrong-android-detail")
            activateAndroid()
        }
        assertProjectionCode(
            "INVALID_MODEL_SESSION_ACTIVATION_BOUNDARY",
            androidEventAfterLocalBoundary.events
        )
    }

    @Test
    fun historicalFinalActivationBoundariesReplayWithoutInventingAnOpening() {
        for (local in listOf(false, true)) {
            val stream = EventStream().apply {
                thread(); turn(); userMessage("history fixture")
                roundStart("old-phase-a", "old-detail")
                roundCompleted("old-phase-a", "old-detail", PublicMessageKind.FINAL,
                    listOf(if (local) "<activate_local/>" else "<activate_android/>"),
                    finishMetadata = mapOf(
                        ZERO_TOOL_PHASE_KEY to ZERO_TOOL_PHASE_VALUE,
                        ZERO_TOOL_DECISION_KEY to (if (local) ZeroToolRoundDecision.ACTIVATE_LOCAL else
                            ZeroToolRoundDecision.ACTIVATE_ANDROID).wireName
                    ), executionMode = RoundExecutionMode.NONE, followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS)
                activateAndroid(activationKind = if (local) TurnActivationKind.LOCAL else TurnActivationKind.ANDROID)
            }
            // Round-trip serialized historical envelopes through the current strict projector.
            val restored = stream.events.map { Json.decodeFromString<AgentEventEnvelope>(Json.encodeToString(it)) }
            val replay = AgentEventReadModelProjector.project(restored)
            assertEquals(1L, replay.turns.single().modelSessionEpoch)
            assertEquals(if (local) TurnActivationKind.LOCAL else TurnActivationKind.ANDROID,
                replay.turns.single().activationKind)
            assertTrue(replay.conversation.entries.none { it.kind == ConversationEntryKind.ASSISTANT_START })
        }
    }

    @Test
    fun repairedActivationReplaysOneOpeningAndRetainsTheFailedRound() {
        for (local in listOf(false, true)) {
            val stream = EventStream().apply {
                thread(); turn(); userMessage("repair fixture")
                roundStart("failed-a", "failed-detail")
                roundCompleted("failed-a", "failed-detail", null, emptyList(),
                    zeroToolFinishMetadata(ZeroToolRoundDecision.FAILED, "PHASE_A_PROTOCOL_ERROR", null),
                    RoundExecutionMode.NONE, RoundFollowUp.TERMINATE_PROTOCOL, "PHASE_A_PROTOCOL_ERROR")
                phaseAOpening(local)
                activateAndroid(activationKind = if (local) TurnActivationKind.LOCAL else TurnActivationKind.ANDROID)
            }
            val replay = AgentEventReadModelProjector.project(stream.events)
            assertEquals(1L, replay.turns.single().modelSessionEpoch)
            assertEquals("Opening fixture", replay.conversation.entries.single {
                it.kind == ConversationEntryKind.ASSISTANT_START
            }.text)
        }
    }

    @Test
    fun versionedActivationCannotProceedWithoutItsAtomicStartEvent() {
        val clean = EventStream().apply {
            thread(); turn(); userMessage("missing opening")
            roundStart("phase-opening", "opening-detail")
            roundCompleted("phase-opening", "opening-detail", PublicMessageKind.START, listOf("Opening fixture"),
                zeroToolFinishMetadata(ZeroToolRoundDecision.ACTIVATE_ANDROID, null, null),
                RoundExecutionMode.NONE, RoundFollowUp.COMPLETE_AFTER_RESULTS)
            activateAndroid()
        }
        assertProjectionCode("INVALID_MODEL_SESSION_ACTIVATION_BOUNDARY", clean.events)
    }

    @Test
    fun historicalRecoveryReplayRetainsOneCommittedOpening() {
        for (local in listOf(false, true)) {
            val stream = EventStream().apply {
                thread(); turn(); userMessage("recovery fixture")
                phaseAOpening(local)
                turnRecoveryRequired("process")
            }
            stream.resumeRecovery(stream.events.last().eventId)
            stream.activateAndroid(previousEpoch = 1L,
                activationKind = if (local) TurnActivationKind.LOCAL else TurnActivationKind.ANDROID)
            val replay = AgentEventReadModelProjector.project(stream.events)
            assertEquals(2L, replay.turns.single().modelSessionEpoch)
            assertEquals(1, replay.conversation.entries.count { it.kind == ConversationEntryKind.ASSISTANT_START })
        }
    }

    private fun EventStream.phaseAOpening(local: Boolean) {
        roundStart("phase-opening", "opening-detail")
        roundCompleted("phase-opening", "opening-detail", PublicMessageKind.START, listOf("Opening fixture"),
            zeroToolFinishMetadata(if (local) ZeroToolRoundDecision.ACTIVATE_LOCAL else
                ZeroToolRoundDecision.ACTIVATE_ANDROID, null, null),
            RoundExecutionMode.NONE, RoundFollowUp.COMPLETE_AFTER_RESULTS)
        assistant("phase-opening", "opening-message", PublicMessageKind.START, listOf("Opening fixture"), "opening-detail")
    }

    private fun localActivationStream(): EventStream = EventStream().apply {
        thread()
        turn()
        userMessage("local knowledge")
        roundStart("phase-local", "round-detail-phase-local")
        roundCompleted(
            roundId = "phase-local",
            detailRef = "round-detail-phase-local",
            kind = PublicMessageKind.START,
            blocks = listOf("I will look up the local knowledge."),
            finishMetadata = mapOf(
                ZERO_TOOL_PHASE_KEY to ZERO_TOOL_PHASE_VALUE,
                ZERO_TOOL_OPENING_VERSION_KEY to ZERO_TOOL_OPENING_VERSION,
                ZERO_TOOL_DECISION_KEY to ZeroToolRoundDecision.ACTIVATE_LOCAL.wireName
            ),
            executionMode = RoundExecutionMode.NONE,
            followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS
        )
        assistant("phase-local", "opening:phase-local", PublicMessageKind.START, listOf("I will look up the local knowledge."), "round-detail-phase-local")
        activateAndroid(activationKind = TurnActivationKind.LOCAL)
    }

    private fun interventionStream(): EventStream = EventStream().apply {
        thread()
        turn()
        userMessage("执行")
        roundStart("round-1", "round-detail-1")
        roundCompleted(
            "round-1",
            "round-detail-1",
            kind = PublicMessageKind.USER_INTERVENTION,
            blocks = listOf("请接管并完成验证")
        )
        toolCall(
            roundId = "round-1",
            callId = "intervention",
            detailRef = "round-detail-1",
            effect = ToolEffect.USER_INTERVENTION
        )
        assistant(
            roundId = "round-1",
            messageId = "intervention-message",
            kind = PublicMessageKind.USER_INTERVENTION,
            blocks = listOf("请接管并完成验证"),
            detailRef = "round-detail-1"
        )
        toolStarted("round-1", "intervention", "round-detail-1")
        interventionRequested("round-1", "intervention", "intervention-message")
    }

    private fun minimalCompletedStream(): EventStream = EventStream().apply {
        thread()
        turn()
        userMessage("hello")
        completeTurn(assistantReply = "world")
    }

    private fun assertProjectionCode(code: String, events: List<AgentEventEnvelope>) {
        val failure = try {
            AgentEventReadModelProjector.project(events)
            throw AssertionError("Expected projection failure $code")
        } catch (error: AgentEventProjectionException) {
            error
        }
        assertEquals(code, failure.code)
    }

    private class EventStream(seed: List<AgentEventEnvelope> = emptyList()) {
        val events = seed.toMutableList()
        private var nextSequence = (seed.maxOfOrNull { it.sequence } ?: 0L) + 1L
        private var activeRoundId: String? = seed.lastOrNull { it.roundId != null }?.roundId
        private var activeRoundDetail: String = "round-detail-1"

        fun thread() {
            add(
                kind = AgentEventKind.THREAD_CREATED,
                payload = ThreadCreatedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION, "对话")
            )
        }

        fun turn() {
            add(
                kind = AgentEventKind.TURN_STARTED,
                turnId = TURN_ID,
                payload = TurnStartedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    modelSessionIdentity = modelIdentity(),
                    providerConfigSnapshotRef = "provider-config-ref"
                )
            )
        }

        fun userMessage(
            text: String,
            attachments: List<UserAttachmentPayload> = emptyList()
        ) {
            add(
                kind = AgentEventKind.USER_MESSAGE,
                turnId = TURN_ID,
                payload = UserTextEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION,
                    "user-message",
                    text,
                    attachments
                )
            )
        }

        fun activateAndroid(
            previousEpoch: Long = 0L,
            activationKind: TurnActivationKind = TurnActivationKind.ANDROID
        ) {
            add(
                kind = if (activationKind == TurnActivationKind.LOCAL) {
                    AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED
                } else {
                    AgentEventKind.MODEL_SESSION_ACTIVATED
                },
                turnId = TURN_ID,
                payload = ModelSessionActivatedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    modelSessionIdentity = modelIdentity().copy(stablePrefixHash = "4".repeat(64)),
                    providerConfigSnapshotRef = "provider-config-ref",
                    previousEpoch = previousEpoch,
                    currentEpoch = previousEpoch + 1L
                )
            )
        }

        fun memoryContextLoaded() {
            add(
                kind = AgentEventKind.MEMORY_CONTEXT_LOADED,
                turnId = TURN_ID,
                payload = MemoryContextLoadedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    queryHash = "b".repeat(64),
                    items = listOf(
                        MemoryContextItemPayload(
                            ref = "profile:preferred_address",
                            scope = MemoryContextScope.CORE_PROFILE,
                            summary = "Address the user as Alex"
                        )
                    )
                )
            )
        }

        fun turnRecoveryRequired(recoveryId: String) {
            add(
                kind = AgentEventKind.TURN_RECOVERY_REQUIRED,
                turnId = TURN_ID,
                payload = TurnRecoveryRequiredEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    recoveryId = recoveryId,
                    diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
                )
            )
        }

        fun resumeRecovery(
            recoveryEventId: String,
            previousEpoch: Long = 0L,
            identity: ModelSessionIdentity = modelIdentity()
        ) {
            add(
                kind = AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED,
                turnId = TURN_ID,
                payload = ModelSessionEpochAdvancedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    previousEpoch = previousEpoch,
                    currentEpoch = previousEpoch + 1L,
                    reason = com.droidance.core.agent.llm.session.EpochAdvanceReason.PROCESS_RECOVERY,
                    evidenceRef = recoveryEventId,
                    diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY,
                    identity = identity
                )
            )
        }

        fun roundStart(roundId: String, detailRef: String) {
            activeRoundId = roundId
            activeRoundDetail = detailRef
            val range = SubmittedItemSequenceRange(1L, nextSequence - 1L)
            add(
                kind = AgentEventKind.MODEL_ROUND_STARTED,
                turnId = TURN_ID,
                roundId = roundId,
                payload = ModelRoundStartedEventPayload(
                    MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    RepairPurpose.NONE,
                    startCommitted = events.any { it.eventKind == AgentEventKind.ASSISTANT_MESSAGE },
                    submittedItems = range,
                    roundDetailRef = detailRef
                )
            )
        }

        fun roundCompleted(
            roundId: String,
            detailRef: String,
            kind: PublicMessageKind?,
            blocks: List<String>,
            finishMetadata: Map<String, String> = emptyMap(),
            executionMode: RoundExecutionMode = RoundExecutionMode.SERIAL,
            followUp: RoundFollowUp = RoundFollowUp.NONE,
            terminalDiagnosticCode: String? = null
        ) {
            val start = events.last { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED && it.roundId == roundId }
            val started = JSON.decodeFromString(
                ModelRoundStartedEventPayload.serializer(),
                start.payloadJson
            )
            add(
                kind = AgentEventKind.MODEL_ROUND_COMPLETED,
                turnId = TURN_ID,
                roundId = roundId,
                payload = ModelRoundCompletedEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    planFingerprint = "a".repeat(64),
                    submittedItems = started.submittedItems,
                    providerAckSequence = started.submittedItems.lastSequence,
                    publicBlocks = blocks,
                    publicMessageKind = kind,
                    finishMetadata = finishMetadata,
                    executionMode = executionMode,
                    followUp = followUp,
                    terminalDiagnosticCode = terminalDiagnosticCode,
                    candidateCapabilitySnapshotRef = null,
                    candidateCapabilitySnapshotHash = null,
                    attemptLifecycleSequence = null,
                    roundDetailRef = detailRef
                )
            )
        }

        fun assistant(
            roundId: String,
            messageId: String,
            kind: PublicMessageKind,
            blocks: List<String>,
            detailRef: String
        ) {
            add(
                kind = AgentEventKind.ASSISTANT_MESSAGE,
                turnId = TURN_ID,
                roundId = roundId,
                payload = AssistantMessageEventPayload(
                    MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    messageId,
                    kind,
                    blocks,
                    detailRef
                )
            )
        }

        fun progress(roundId: String, blocks: List<String>, detailRef: String) {
            add(
                kind = AgentEventKind.PUBLIC_PROGRESS,
                turnId = TURN_ID,
                roundId = roundId,
                payload = PublicProgressEventPayload(
                    MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    blocks,
                    detailRef
                )
            )
        }

        fun toolCall(
            roundId: String,
            callId: String,
            detailRef: String,
            name: String = "generic_tool",
            source: ToolCallSource = ToolCallSource.MODEL_REQUESTED,
            category: ToolPresentationCategory = ToolPresentationCategory.PRIMARY,
            effect: ToolEffect = ToolEffect.READ_ONLY,
            argumentsJson: String = "{}",
            sensitivePaths: List<String> = emptyList(),
            triggerCallId: String? = null,
            binding: ToolBinding = ToolBinding.TURN_LOCAL,
            actionTitle: String? = null
        ) {
            add(
                kind = AgentEventKind.TOOL_CALL,
                turnId = TURN_ID,
                roundId = roundId,
                attemptId = ATTEMPT_ID.takeIf { binding == ToolBinding.DEVICE_BOUND },
                executionId = EXECUTION_ID.takeIf { binding == ToolBinding.DEVICE_BOUND },
                callId = callId,
                payload = ToolCallEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    providerOrder = events.count {
                        it.eventKind == AgentEventKind.TOOL_CALL && it.roundId == roundId
                    },
                    providerCallId = "provider-$callId",
                    name = name,
                    argumentsJson = argumentsJson,
                    source = source,
                    binding = binding,
                    effect = effect,
                    contractHash = "a".repeat(64),
                    presentation = ToolPresentationSnapshot(
                        labelResourceKey = "tool.generic",
                        category = category,
                        resultImageRole = ResultImageRole.NONE,
                        sensitiveFieldPaths = sensitivePaths,
                        actionTitle = actionTitle
                    ),
                    roundDetailRef = detailRef,
                    triggerCallId = triggerCallId
                )
            )
        }

        fun toolStarted(
            roundId: String,
            callId: String,
            detailRef: String,
            deviceBound: Boolean = false
        ) {
            add(
                kind = AgentEventKind.TOOL_STARTED,
                turnId = TURN_ID,
                roundId = roundId,
                attemptId = ATTEMPT_ID.takeIf { deviceBound },
                executionId = EXECUTION_ID.takeIf { deviceBound },
                callId = callId,
                payload = ToolStartedEventPayload(MODEL_ROUND_EVENT_CONTRACT_VERSION, detailRef)
            )
        }

        fun toolFinished(
            roundId: String,
            callId: String,
            detailRef: String,
            result: ToolExecutionResult,
            deviceBound: Boolean = false
        ) {
            add(
                kind = AgentEventKind.TOOL_FINISHED,
                turnId = TURN_ID,
                roundId = roundId,
                attemptId = ATTEMPT_ID.takeIf { deviceBound },
                executionId = EXECUTION_ID.takeIf { deviceBound },
                callId = callId,
                payload = ToolFinishedEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION,
                    result,
                    detailRef,
                    exclusiveDurationMillis = 12L
                )
            )
        }

        fun settledCall(
            callId: String,
            source: ToolCallSource,
            category: ToolPresentationCategory,
            result: ToolExecutionResult,
            effect: ToolEffect = ToolEffect.READ_ONLY,
            triggerCallId: String? = null,
            binding: ToolBinding = ToolBinding.TURN_LOCAL,
            name: String = "generic_tool"
        ) {
            val roundId = checkNotNull(activeRoundId)
            toolCall(
                roundId = roundId,
                callId = callId,
                detailRef = activeRoundDetail,
                source = source,
                category = category,
                effect = effect,
                triggerCallId = triggerCallId,
                binding = binding,
                name = name
            )
            toolStarted(roundId, callId, activeRoundDetail, deviceBound = binding == ToolBinding.DEVICE_BOUND)
            toolFinished(roundId, callId, activeRoundDetail, result, deviceBound = binding == ToolBinding.DEVICE_BOUND)
        }

        fun rejectedCall(
            callId: String,
            effect: ToolEffect = ToolEffect.READ_ONLY,
            binding: ToolBinding = ToolBinding.TURN_LOCAL
        ) {
            val roundId = checkNotNull(activeRoundId)
            toolCall(roundId, callId, activeRoundDetail, effect = effect, binding = binding)
            add(
                kind = AgentEventKind.TOOL_CALL_REJECTED,
                turnId = TURN_ID,
                roundId = roundId,
                attemptId = ATTEMPT_ID.takeIf { binding == ToolBinding.DEVICE_BOUND },
                executionId = EXECUTION_ID.takeIf { binding == ToolBinding.DEVICE_BOUND },
                callId = callId,
                payload = ToolCallRejectedEventPayload(
                    MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    ToolExecutionResult(
                        outputText = "not executed",
                        executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                        evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                        diagnosticCode = "PRECONDITION_REJECTED"
                    ),
                    candidateCapabilitySnapshotRef = null,
                    candidateCapabilitySnapshotHash = null,
                    roundDetailRef = activeRoundDetail
                )
            )
        }

        fun interventionRequested(roundId: String, callId: String, messageId: String) {
            add(
                kind = AgentEventKind.USER_INTERVENTION_REQUESTED,
                turnId = TURN_ID,
                roundId = roundId,
                callId = callId,
                payload = UserInterventionRequestedEventPayload(
                    MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    messageId
                )
            )
        }

        fun attemptCreated() {
            val roundId = checkNotNull(activeRoundId)
            add(
                kind = AgentEventKind.EXECUTION_ATTEMPT_CREATED,
                turnId = TURN_ID,
                roundId = roundId,
                attemptId = ATTEMPT_ID,
                executionId = EXECUTION_ID,
                payload = ExecutionAttemptCreatedEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    state = ExecutionAttemptState.CREATED,
                    context = ExecutionContextSnapshotPayload(
                        executionId = EXECUTION_ID,
                        runtimeMode = com.droidance.core.agent.device.RuntimeMode.ULTRA,
                        executionSpace = com.droidance.core.agent.device.ExecutionSpace.MAIN_SCREEN,
                        displayId = 0,
                        backendSessionId = "backend-session",
                        capabilitySnapshotRef = "capability-ref",
                        capabilitySnapshotHash = "b".repeat(64),
                        cancellationScopeId = "cancel-scope"
                    ),
                    capabilitySnapshot = CapabilitySnapshotEventPayload(
                        capabilitySnapshotRef = "capability-ref",
                        capabilitySnapshotHash = "b".repeat(64),
                        runtimeMode = com.droidance.core.agent.device.RuntimeMode.ULTRA,
                        executionSpace = com.droidance.core.agent.device.ExecutionSpace.MAIN_SCREEN,
                        displayId = 0,
                        backendKind = com.droidance.core.agent.attempt.ExecutionBackendKind.ROOT_BACKEND,
                        backendSessionId = "backend-session",
                        backendAvailable = true,
                        capabilities = emptyList(),
                        scopedShellCapability = null
                    )
                )
            )
        }

        fun attemptState(state: ExecutionAttemptState, diagnosticCode: String? = null) {
            add(
                kind = AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
                turnId = TURN_ID,
                attemptId = ATTEMPT_ID,
                executionId = EXECUTION_ID,
                payload = ExecutionAttemptStateChangedEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION,
                    state,
                    diagnosticCode
                )
            )
        }

        fun completeTurn(assistantReply: String) {
            add(
                kind = AgentEventKind.TURN_COMPLETED,
                turnId = TURN_ID,
                payload = TurnCompletedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    status = "COMPLETED",
                    assistantReply = assistantReply,
                    totalDurationMillis = 100L
                )
            )
        }

        fun attachment(callId: String, role: DetailAttachmentRole = DetailAttachmentRole.RESULT) {
            val call = events.single { it.eventKind == AgentEventKind.TOOL_CALL && it.callId == callId }
            add(
                kind = AgentEventKind.DETAIL_ATTACHMENT_ADDED,
                turnId = TURN_ID,
                roundId = call.roundId,
                callId = callId,
                payload = DetailAttachmentAddedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    attachmentId = "attachment-$callId",
                    role = role,
                    contentHash = "c".repeat(64),
                    contentRef = "sha256:${"c".repeat(64)}",
                    sizeBytes = 123L,
                    mimeType = "image/png",
                    width = 100,
                    height = 200,
                    observationId = "observation-1"
                )
            )
        }

        fun systemNotice(text: String) {
            add(
                kind = AgentEventKind.SYSTEM_NOTICE,
                turnId = TURN_ID,
                payload = SystemNoticeEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION,
                    "notice-${events.size}",
                    text
                )
            )
        }

        fun malformedContextCheckpoint() {
            add(
                kind = AgentEventKind.CONTEXT_CHECKPOINT,
                turnId = TURN_ID,
                payload = JsonObject(emptyMap())
            )
        }

        private inline fun <reified T> add(
            kind: AgentEventKind,
            payload: T,
            turnId: String? = null,
            roundId: String? = null,
            attemptId: String? = null,
            executionId: String? = null,
            callId: String? = null
        ) {
            val payloadJson = canonicalizeJson(JSON.encodeToString(payload))
            val sequence = nextSequence++
            events += AgentEventEnvelope(
                schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
                eventId = "event-$sequence",
                eventKind = kind,
                sequence = sequence,
                occurredAt = sequence * 10L,
                threadId = THREAD_ID,
                turnId = turnId,
                roundId = roundId,
                attemptId = attemptId,
                executionId = executionId,
                callId = callId,
                payloadHash = sha256Hex(payloadJson),
                payloadJson = payloadJson,
                idempotencyKey = "idempotency-$sequence"
            )
        }

        companion object {
            const val THREAD_ID = "thread-1"
            const val TURN_ID = "turn-1"
            const val ATTEMPT_ID = "attempt-1"
            const val EXECUTION_ID = "execution-1"
        }
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }

        fun modelIdentity(): ModelSessionIdentity = ModelSessionIdentity(
            threadId = EventStream.THREAD_ID,
            providerId = "provider",
            transportKind = ModelTransport.OPENAI_RESPONSES,
            transportConfigHash = "1".repeat(64),
            authScopeHash = "2".repeat(64),
            modelId = "model",
            stablePrefixHash = "3".repeat(64)
        )

        fun verifiedResult(output: String): ToolExecutionResult = ToolExecutionResult(
            outputText = output,
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
        )
    }
}
