// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.event

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*

import com.droidance.agent.event.*

import com.droidance.core.agent.attempt.ExecutionRoundPlanner
import com.droidance.core.agent.ActionControlProtocol
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.attempt.DeviceToolCapabilityRequirement
import com.droidance.core.agent.attempt.PlannedToolRejection
import com.droidance.core.agent.attempt.PlannedToolStart
import com.droidance.core.agent.attempt.PreparedRoundExecutionPlan
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.harness.PublicMessagePlan
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RejectedToolCall
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.RoundExecutionMode
import com.droidance.core.agent.harness.RoundExecutionPlan
import com.droidance.core.agent.harness.RoundFollowUp
import com.droidance.core.agent.harness.ZeroToolRoundDecision
import com.droidance.core.agent.harness.ZERO_TOOL_OPENING_VERSION_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_OPENING_VERSION
import com.droidance.core.agent.harness.zeroToolFinishMetadata
import com.droidance.core.agent.harness.prepareZeroToolExecution
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedModelRoundCommitTest {
    @Test fun legacyStageHintCommitsAsOrdinaryProgressWithAnUnchangedContinuingToolPlan() {
        val round = NormalizedModelRound.create(listOf("First source results"),
            listOf(ProviderToolCall("next", "read_memory", "{}")), mapOf("public_output_kind" to "stage_result"))
        val execution = prepare(round, startCommitted = true)
        assertEquals(PublicMessageKind.PROGRESS, execution.roundPlan.publicMessage!!.kind)
        assertEquals(RoundFollowUp.NONE, execution.roundPlan.followUp)
        val plan = commit(round, execution, true)
        val payload = json.decodeFromString<PublicProgressEventPayload>(plan.drafts.single {
            it.eventKind == AgentEventKind.PUBLIC_PROGRESS }.payloadJson)
        assertEquals(round.publicBlocks, payload.publicBlocks)
        assertTrue(plan.drafts.none { it.eventKind in setOf(AgentEventKind.ASSISTANT_MESSAGE, AgentEventKind.TURN_COMPLETED) })
    }

    private val json = Json { ignoreUnknownKeys = false }
    private val capabilityRequirements = listOf(
        DeviceToolCapabilityRequirement("click", DeviceCapability.CLICK)
    )
    private val registrations = listOf(
        RegisteredToolContract(
            "read_memory", "Read memory", ToolBinding.TURN_LOCAL, ToolEffect.READ_ONLY, emptyMap()
        ),
        RegisteredToolContract(
            "save_memory", "Save memory", ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING, emptyMap()
        ),
        RegisteredToolContract(
            "click", "Click screen", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING, emptyMap()
        ),
        RegisteredToolContract(
            "ask_human", "Ask user", ToolBinding.TURN_LOCAL, ToolEffect.USER_INTERVENTION, emptyMap()
        ),
        RegisteredToolContract(
            "done", "Complete turn", ToolBinding.TURN_LOCAL, ToolEffect.COMPLETION, emptyMap()
        )
    )

    @Test
    fun phaseAActivationAtomicallyCommitsOneVersionedStartWithoutToolsOrAttempt() {
        for (decision in listOf(ZeroToolRoundDecision.ACTIVATE_ANDROID, ZeroToolRoundDecision.ACTIVATE_LOCAL)) {
            val round = NormalizedModelRound.create(
                publicBlocks = listOf("I will check the requested state."), toolCalls = emptyList(),
                finishMetadata = zeroToolFinishMetadata(decision, null, null)
            )
            val prepared = prepareZeroToolExecution(round)
            val plan = commit(round, prepared, startCommitted = false, publicMessageId = "opening")
            assertEquals(listOf(AgentEventKind.MODEL_ROUND_COMPLETED, AgentEventKind.ASSISTANT_MESSAGE),
                plan.drafts.map { it.eventKind })
            val completed = json.decodeFromString<ModelRoundCompletedEventPayload>(plan.drafts.first().payloadJson)
            val opening = json.decodeFromString<AssistantMessageEventPayload>(plan.drafts.last().payloadJson)
            assertEquals(ZERO_TOOL_OPENING_VERSION, completed.finishMetadata[ZERO_TOOL_OPENING_VERSION_KEY])
            assertEquals(PublicMessageKind.START, completed.publicMessageKind)
            assertEquals(PublicMessageKind.START, opening.kind)
            assertEquals(round.publicBlocks, opening.publicBlocks)
            assertEquals(completed.publicBlocks, opening.publicBlocks)
            assertEquals(null, prepared.attemptBinding)
            assertTrue(prepared.toolStarts.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                commit(round, prepared, startCommitted = false, publicMessageId = null)
            }
            assertThrows(IllegalArgumentException::class.java) {
                commit(round, prepared, startCommitted = true, publicMessageId = "duplicate")
            }
        }
    }

    @Test
    fun provider_calls_are_declared_and_disposed_exactly_once_in_provider_order() {
        val round = round(
            public = "开始处理",
            tools = listOf("save_memory", "unknown", "save_memory")
        )
        val execution = prepare(round, startCommitted = false)
        assertEquals(listOf("call-0"), execution.roundPlan.selectedCalls.map { it.callId })
        assertEquals(
            listOf("call-1", "call-2"),
            execution.roundPlan.rejectedCalls.map { it.call.callId }
        )

        val commit = commit(
            round = round,
            execution = execution,
            startCommitted = false,
            publicMessageId = "message-start"
        )

        val declarations = commit.drafts.filter { it.eventKind == AgentEventKind.TOOL_CALL }
        assertEquals(listOf("call-0", "call-1", "call-2"), declarations.map { it.callId })
        assertEquals(
            listOf(0, 1, 2),
            declarations.map { json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerOrder }
        )
        val dispositions = commit.drafts.filter {
            it.eventKind == AgentEventKind.TOOL_STARTED ||
                it.eventKind == AgentEventKind.TOOL_CALL_REJECTED
        }
        assertEquals(listOf("call-0", "call-1", "call-2"), dispositions.map { it.callId })
        assertEquals(
            listOf(
                AgentEventKind.TOOL_STARTED,
                AgentEventKind.TOOL_CALL_REJECTED,
                AgentEventKind.TOOL_CALL_REJECTED
            ),
            dispositions.map { it.eventKind }
        )

        val reorderedRound = NormalizedModelRound.create(
            publicBlocks = round.publicBlocks,
            toolCalls = round.toolCalls.reversed()
        )
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelRoundCommit.create(
                scope = scope,
                expectedStartCommitted = false,
                repairPurpose = RepairPurpose.NONE,
                submittedItems = submittedItems,
                roundDetailRef = ROUND_DETAIL_REF,
                normalizedRound = reorderedRound,
                preparedExecution = execution,
                presentationsByCallId = presentations(execution),
                publicMessageId = null
            )
        }

        val extraPresentation = presentations(execution) + (
            "call-extra" to ToolPresentationSnapshot(
                labelResourceKey = "tool.extra",
                category = ToolPresentationCategory.DIAGNOSTIC,
                resultImageRole = ResultImageRole.NONE
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelRoundCommit.create(
                scope = scope,
                expectedStartCommitted = false,
                repairPurpose = RepairPurpose.NONE,
                submittedItems = submittedItems,
                roundDetailRef = ROUND_DETAIL_REF,
                normalizedRound = round,
                preparedExecution = execution,
                presentationsByCallId = extraPresentation,
                publicMessageId = null
            )
        }

        val missingPresentation = presentations(execution).toMutableMap().apply { remove("call-2") }
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelRoundCommit.create(
                scope = scope,
                expectedStartCommitted = false,
                repairPurpose = RepairPurpose.NONE,
                submittedItems = submittedItems,
                roundDetailRef = ROUND_DETAIL_REF,
                normalizedRound = round,
                preparedExecution = execution,
                presentationsByCallId = missingPresentation,
                publicMessageId = null
            )
        }
    }

    @Test fun taskSummaryStartsBeforeLocalLookupsAndRepairProseRemainsProgress() {
        val opening = prepareAndCommit("Summarize the requested comparison", listOf("read_memory"), false,
            messageId = "task-opening")
        assertPublicClassification(opening, PublicMessageKind.START, AgentEventKind.ASSISTANT_MESSAGE)
        val repair = prepareAndCommit("Repairing a missing field", listOf("click"), true)
        assertPublicClassification(repair, PublicMessageKind.PROGRESS, AgentEventKind.PUBLIC_PROGRESS)
    }

    @Test
    fun public_message_lifecycle_is_materialized_as_start_progress_final_and_intervention() {
        val start = prepareAndCommit(
            public = "开始执行",
            tools = listOf("click"),
            startCommitted = false,
            messageId = "message-start"
        )
        assertPublicClassification(
            commit = start,
            expectedKind = PublicMessageKind.START,
            expectedEventKind = AgentEventKind.ASSISTANT_MESSAGE
        )
        assertEquals(
            PublicMessageKind.START,
            json.decodeFromString<AssistantMessageEventPayload>(
                start.drafts.single { it.eventKind == AgentEventKind.ASSISTANT_MESSAGE }.payloadJson
            ).kind
        )

        val progress = prepareAndCommit(
            public = "读取本地资料",
            tools = listOf("read_memory"),
            startCommitted = true
        )
        assertPublicClassification(
            commit = progress,
            expectedKind = PublicMessageKind.PROGRESS,
            expectedEventKind = AgentEventKind.PUBLIC_PROGRESS
        )
        assertEquals(
            listOf("读取本地资料"),
            json.decodeFromString<PublicProgressEventPayload>(
                progress.drafts.single { it.eventKind == AgentEventKind.PUBLIC_PROGRESS }.payloadJson
            ).publicBlocks
        )

        val final = prepareAndCommit(
            public = "任务完成",
            tools = emptyList(),
            startCommitted = true
        )
        assertPublicClassification(
            commit = final,
            expectedKind = PublicMessageKind.FINAL,
            expectedEventKind = null
        )

        val intervention = prepareAndCommit(
            public = "请接管处理",
            tools = listOf("ask_human"),
            startCommitted = true,
            messageId = "message-intervention"
        )
        assertPublicClassification(
            commit = intervention,
            expectedKind = PublicMessageKind.USER_INTERVENTION,
            expectedEventKind = AgentEventKind.ASSISTANT_MESSAGE
        )
        assertEquals(
            listOf(AgentEventKind.USER_INTERVENTION_REQUESTED),
            intervention.drafts.filter {
                it.eventKind == AgentEventKind.USER_INTERVENTION_REQUESTED
            }.map { it.eventKind }
        )

        assertThrows(IllegalArgumentException::class.java) {
            prepareAndCommit(
                public = "开始执行",
                tools = listOf("click"),
                startCommitted = false
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepareAndCommit(
                public = "读取本地资料",
                tools = listOf("read_memory"),
                startCommitted = true,
                messageId = "unexpected-message"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepareAndCommit(
                public = "任务完成",
                tools = emptyList(),
                startCommitted = true,
                messageId = "unexpected-message"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepareAndCommit(
                public = "请接管处理",
                tools = listOf("ask_human"),
                startCommitted = true
            )
        }
    }

    @Test
    fun deterministic_public_classification_cannot_be_overridden_by_a_forged_round_plan() {
        val round = round(public = "开始执行", tools = listOf("save_memory"))
        val valid = prepare(round, startCommitted = false)
        val call = valid.roundPlan.selectedCalls.single()
        val forgedRoundPlan = RoundExecutionPlan(
            publicMessage = PublicMessagePlan(PublicMessageKind.PROGRESS, "开始执行"),
            selectedCalls = listOf(call),
            rejectedCalls = emptyList(),
            executionMode = RoundExecutionMode.SERIAL,
            followUp = RoundFollowUp.NONE
        )
        val forgedExecution = PreparedRoundExecutionPlan(
            roundPlan = forgedRoundPlan,
            attemptBinding = null,
            candidateCapabilitySnapshotRef = null,
            candidateCapabilitySnapshotHash = null,
            toolStarts = listOf(PlannedToolStart(call, null, null, null)),
            toolRejections = emptyList()
        )

        assertThrows(IllegalArgumentException::class.java) {
            commit(
                round = round,
                execution = forgedExecution,
                startCommitted = false,
                publicMessageId = "message-start"
            )
        }
    }

    @Test
    fun tool_presentation_is_frozen_into_the_event_payload() {
        val round = round(public = "读取资料", tools = listOf("read_memory"))
        val execution = prepare(round, startCommitted = true)
        val sensitivePaths = mutableListOf("arguments.secret", "arguments.account")
        val presentation = ToolPresentationSnapshot(
            labelResourceKey = "tool.read_memory",
            category = ToolPresentationCategory.SUPPORTING,
            resultImageRole = ResultImageRole.INPUT,
            sensitiveFieldPaths = sensitivePaths,
            actionTitle = "读取相关资料"
        )
        val sourceMap = mutableMapOf("call-0" to presentation)

        val commit = PreparedModelRoundCommit.create(
            scope = scope,
            expectedStartCommitted = true,
            repairPurpose = RepairPurpose.NONE,
            submittedItems = submittedItems,
            roundDetailRef = ROUND_DETAIL_REF,
            normalizedRound = round,
            preparedExecution = execution,
            presentationsByCallId = sourceMap,
            publicMessageId = null
        )
        sensitivePaths.clear()
        sensitivePaths += "arguments.changed"
        sourceMap.clear()

        val payload = json.decodeFromString<ToolCallEventPayload>(
            commit.drafts.single { it.eventKind == AgentEventKind.TOOL_CALL }.payloadJson
        )
        assertEquals("tool.read_memory", payload.presentation.labelResourceKey)
        assertEquals("读取相关资料", payload.presentation.actionTitle)
        val changedTitle = PreparedModelRoundCommit.create(
            scope = scope,
            expectedStartCommitted = true,
            repairPurpose = RepairPurpose.NONE,
            submittedItems = submittedItems,
            roundDetailRef = ROUND_DETAIL_REF,
            normalizedRound = round,
            preparedExecution = execution,
            presentationsByCallId = mapOf("call-0" to payload.presentation.copy(actionTitle = "核对相关资料")),
            publicMessageId = null
        )
        assertTrue(commit.planFingerprint != changedTitle.planFingerprint)
        assertEquals(ToolPresentationCategory.SUPPORTING, payload.presentation.category)
        assertEquals(ResultImageRole.INPUT, payload.presentation.resultImageRole)
        assertEquals(
            listOf("arguments.account", "arguments.secret"),
            payload.presentation.sensitiveFieldPaths
        )
    }

    @Test
    fun selected_and_rejected_calls_are_exclusive_and_rejections_are_not_executed() {
        val round = round(
            public = "开始处理",
            tools = listOf("save_memory", "unknown", "save_memory")
        )
        val execution = prepare(round, startCommitted = false)
        val commit = commit(
            round = round,
            execution = execution,
            startCommitted = false,
            publicMessageId = "message-start"
        )
        val rejectedPayloads = commit.drafts
            .filter { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }
            .map { json.decodeFromString<ToolCallRejectedEventPayload>(it.payloadJson) }
        assertTrue(rejectedPayloads.isNotEmpty())
        assertTrue(rejectedPayloads.all {
            it.result.executionStatus == ToolExecutionStatus.NOT_EXECUTED &&
                it.result.evidenceStatus == ToolEvidenceStatus.NOT_REQUIRED &&
                it.result.observationId == null
        })

        val selectedCall = execution.roundPlan.selectedCalls.single()
        assertThrows(IllegalArgumentException::class.java) {
            RoundExecutionPlan(
                publicMessage = execution.roundPlan.publicMessage,
                selectedCalls = listOf(selectedCall),
                rejectedCalls = listOf(RejectedToolCall(selectedCall, "REJECTED")),
                executionMode = RoundExecutionMode.SERIAL,
                followUp = RoundFollowUp.NONE
            )
        }

        val rejectedCall = execution.roundPlan.rejectedCalls.first().call
        assertThrows(IllegalArgumentException::class.java) {
            PlannedToolRejection(
                call = rejectedCall,
                result = ToolExecutionResult(
                    outputText = "handler ran",
                    executionStatus = ToolExecutionStatus.FAILED,
                    evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                    diagnosticCode = "FAILED_AFTER_EXECUTION"
                ),
                attemptId = null,
                executionId = null,
                executionContext = null
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolCallRejectedEventPayload(
                contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                result = ToolExecutionResult(
                    outputText = "handler ran",
                    executionStatus = ToolExecutionStatus.FAILED,
                    evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                    diagnosticCode = "FAILED_AFTER_EXECUTION"
                ),
                candidateCapabilitySnapshotRef = null,
                candidateCapabilitySnapshotHash = null,
                roundDetailRef = ROUND_DETAIL_REF
            )
        }
    }

    @Test
    fun actionableLocalPreflightFeedbackIsCommittedWithTheRejectedCall() {
        val round = round("Apply the local policy", listOf("save_memory"))
        val feedback = "Local policy rejected this call"
        val execution = ExecutionRoundPlanner(
            registrations = registrations,
            capabilityRequirements = capabilityRequirements,
            callIdFactory = { index, _ -> "call-$index" },
            preflight = {
                ToolExecutionResult(
                    outputText = feedback,
                    executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                    diagnosticCode = "POLICY_REJECTED"
                )
            }
        ).plan(
            round = round,
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        val commit = commit(
            round = round,
            execution = execution,
            startCommitted = false,
            publicMessageId = "message-start"
        )
        val payload = json.decodeFromString<ToolCallRejectedEventPayload>(
            commit.drafts.single { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }.payloadJson
        )

        assertEquals(feedback, payload.result.outputText)
        assertEquals("POLICY_REJECTED", payload.result.diagnosticCode)
    }

    @Test
    fun completionClaimsArePartOfTheAtomicRoundAndItsFingerprint() {
        fun archive(result: String): PreparedModelRoundCommit {
            val parsed = ActionControlProtocol.extract(listOf(ToolCall(
                "provider-done", "done",
                """{"evidence_refs":["obs_1"],"claims":[{"result":"$result","evidence_refs":["obs_1"]}]}"""
            )))
            val round = NormalizedModelRound.create(
                publicBlocks = emptyList(),
                toolCalls = parsed.toolCalls.map { ProviderToolCall(it.id, it.name, it.arguments) },
                finishMetadata = parsed.finishMetadata
            )
            return commit(round, prepare(round, startCommitted = true), startCommitted = true)
        }
        val first = archive("Observed page")
        val completed = json.decodeFromString<ModelRoundCompletedEventPayload>(
            first.drafts.single { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }.payloadJson
        )
        assertTrue(completed.finishMetadata.getValue(ActionControlProtocol.CLAIMS_METADATA).contains("Observed page"))
        assertEquals(1, first.drafts.count { it.eventKind == AgentEventKind.TOOL_CALL })
        val changed = json.decodeFromString<ModelRoundCompletedEventPayload>(
            archive("Different claim").drafts.single { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }.payloadJson
        )
        org.junit.Assert.assertNotEquals(completed.planFingerprint, changed.planFingerprint)
    }

    private fun prepareAndCommit(
        public: String,
        tools: List<String>,
        startCommitted: Boolean,
        messageId: String? = null
    ): PreparedModelRoundCommit {
        val round = round(public, tools)
        return commit(
            round = round,
            execution = prepare(round, startCommitted),
            startCommitted = startCommitted,
            publicMessageId = messageId
        )
    }

    private fun prepare(
        round: NormalizedModelRound,
        startCommitted: Boolean,
        repairPurpose: RepairPurpose = RepairPurpose.NONE
    ): PreparedRoundExecutionPlan = ExecutionRoundPlanner(
        registrations = registrations,
        capabilityRequirements = capabilityRequirements,
        callIdFactory = { index, _ -> "call-$index" }
    ).plan(
        round = round,
        startCommitted = startCommitted,
        repairPurpose = repairPurpose
    )

    private fun commit(
        round: NormalizedModelRound,
        execution: PreparedRoundExecutionPlan,
        startCommitted: Boolean,
        repairPurpose: RepairPurpose = RepairPurpose.NONE,
        publicMessageId: String? = null
    ): PreparedModelRoundCommit = PreparedModelRoundCommit.create(
        scope = scope,
        expectedStartCommitted = startCommitted,
        repairPurpose = repairPurpose,
        submittedItems = submittedItems,
        roundDetailRef = ROUND_DETAIL_REF,
        normalizedRound = round,
        preparedExecution = execution,
        presentationsByCallId = presentations(execution),
        publicMessageId = publicMessageId
    )

    private fun presentations(
        execution: PreparedRoundExecutionPlan
    ): Map<String, ToolPresentationSnapshot> = (
        execution.roundPlan.selectedCalls + execution.roundPlan.rejectedCalls.map { it.call }
    ).associate { call ->
        call.callId to ToolPresentationSnapshot(
            labelResourceKey = "tool.${call.providerCall.name}",
            category = if (call.contract.effect == ToolEffect.COMPLETION) {
                ToolPresentationCategory.COMPLETION
            } else {
                ToolPresentationCategory.PRIMARY
            },
            resultImageRole = ResultImageRole.NONE
        )
    }

    private fun assertPublicClassification(
        commit: PreparedModelRoundCommit,
        expectedKind: PublicMessageKind,
        expectedEventKind: AgentEventKind?
    ) {
        val completed = json.decodeFromString<ModelRoundCompletedEventPayload>(
            commit.drafts.single { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }.payloadJson
        )
        assertEquals(expectedKind, completed.publicMessageKind)
        val publicEvents = commit.drafts.filter {
            it.eventKind == AgentEventKind.ASSISTANT_MESSAGE ||
                it.eventKind == AgentEventKind.PUBLIC_PROGRESS
        }
        if (expectedEventKind == null) {
            assertTrue(publicEvents.isEmpty())
        } else {
            assertEquals(expectedEventKind, publicEvents.single().eventKind)
        }
    }

    private fun round(public: String?, tools: List<String>): NormalizedModelRound =
        NormalizedModelRound.create(
            publicBlocks = listOfNotNull(public),
            toolCalls = tools.mapIndexed { index, name ->
                ProviderToolCall(
                    providerCallId = "provider-$index",
                    name = name,
                    argumentsJson = "{\"index\":$index}"
                )
            }
        )

    private companion object {
        val scope = ModelRoundScope("thread-1", "turn-1", "round-1")
        val submittedItems = SubmittedItemSequenceRange(2, 4)
        const val ROUND_DETAIL_REF = "detail://round-1"
    }
}
