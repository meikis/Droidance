// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionBackendKind
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEvidenceProjectorTest {
    @Test fun shellReceiptCanBeRevisitedByOriginalReferenceOnlyWithinItsTurn() {
        val events = finishedStep(10, "shell-call", "shell", outputText = "{\"stdout\":\"original output\"}",
            argumentsJson = "{\"command\":\"private source\"}", sensitiveFieldPaths = listOf("command")).map { event ->
            if (event.eventKind != AgentEventKind.TOOL_FINISHED) event else {
                val payload = Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson)
                event.copy(payloadJson = Json.encodeToString(payload.copy(result = payload.result.copy(
                    sourceRef = "shell-source", dataRef = "shell-data"))))
            }
        }
        val steps = SessionEvidenceProjector.steps(events, THREAD, TURN)
        assertNotNull(SessionEvidenceProjector.find(steps, "shell-source"))
        assertNotNull(SessionEvidenceProjector.find(steps, "shell-data"))
        val own = SessionEvidenceProjector.sourceIndex(events, THREAD, TURN, 32, true,
            requester = TurnEventScope(THREAD, TURN)).getValue("sources").jsonArray.single().jsonObject
        assertEquals("execution_receipt", own.getValue("source_role").jsonPrimitive.content)
        assertTrue(own.getValue("original_text").jsonPrimitive.content.contains("original output"))
        assertFalse(own.toString().contains("private source"))
        val foreign = SessionEvidenceProjector.sourceIndex(events, THREAD, TURN, 32, true,
            requester = TurnEventScope(THREAD, OTHER_TURN))
        assertFalse(foreign.toString().contains("original output"))
    }

    @Test fun currentTurnCanReviewItsOriginalReadReceiptWithoutReplayingSensitiveArguments() {
        val events = finishedStep(10, "read", "device_read", outputText = "original read result",
            argumentsJson = "{\"query\":\"private argument\"}", sensitiveFieldPaths = listOf("query")).map { event ->
            if (event.eventKind != AgentEventKind.TOOL_CALL) event else event.copy(payloadJson = Json.encodeToString(
                Json.decodeFromString<ToolCallEventPayload>(event.payloadJson).copy(effect = ToolEffect.READ_ONLY)))
        }
        val step = SessionEvidenceProjector.steps(events, THREAD, TURN).single()
        val owner = TurnEventScope(THREAD, TURN)
        val description = SessionEvidenceProjector.describe(step, true, owner)
        assertEquals("original read result", description.getValue("result").jsonPrimitive.content)
        assertEquals("true", description.getValue("arguments_redacted").jsonPrimitive.content)
        assertFalse(description.toString().contains("private argument"))
        val index = SessionEvidenceProjector.sourceIndex(events, THREAD, TURN, 32, true, requester = owner)
        assertTrue(index.toString().contains("original read result"))
        assertFalse(index.toString().contains("private argument"))
        for (requester in listOf(null, TurnEventScope(THREAD, OTHER_TURN), TurnEventScope("other-thread", TURN))) {
            assertFalse(SessionEvidenceProjector.describe(step, true, requester).toString().contains("original read result"))
            assertFalse(SessionEvidenceProjector.sourceIndex(events, THREAD, TURN, 32, true,
                requester = requester).toString().contains("original read result"))
        }
        for (restricted in listOf(
            step.copy(call = step.call.copy(effect = ToolEffect.STATE_CHANGING)),
            step.copy(call = step.call.copy(binding = ToolBinding.TURN_LOCAL)),
            step.copy(result = step.result.copy(executionStatus = ToolExecutionStatus.FAILED)))) {
            assertFalse(SessionEvidenceProjector.describe(restricted, true, owner).toString().contains("original read result"))
        }
    }

    @Test fun reviewRetainsLocalPersistenceReceiptsWithoutPromotingThemToContentSources() {
        val receipt = """{"details":{"persistence_confirmed":true,"items":[{"key":"test.preference","revision":"rev-new","status":"updated"}]}}"""
        val local = finishedStep(10, "write", "local_write", outputText = receipt).map { event ->
            if (event.eventKind != AgentEventKind.TOOL_CALL) event else event.copy(payloadJson = Json.encodeToString(
                Json.decodeFromString<ToolCallEventPayload>(event.payloadJson).copy(binding = ToolBinding.TURN_LOCAL)))
        }
        val index = SessionEvidenceProjector.sourceIndex(local + local.map { it.copy(turnId = OTHER_TURN) }, THREAD, TURN, 32, true)
        assertTrue(index.getValue("sources").jsonArray.isEmpty())
        val effect = index.getValue("settled_effects").jsonArray.single().jsonObject
        assertEquals(receipt, effect.getValue("original_receipt").jsonPrimitive.content)
        assertEquals("TURN_LOCAL", effect.getValue("binding").jsonPrimitive.content)
        assertEquals("event-write-tool_finished", effect.getValue("read_arguments").jsonObject.getValue("ref").jsonPrimitive.content)
    }
    @Test
    fun focusedReviewReceivesBoundedOriginalReadTextWithoutLocalNotesOrSensitiveEchoes() {
        fun read(base: Long, id: String, text: String, binding: ToolBinding = ToolBinding.DEVICE_BOUND,
                 sensitive: List<String> = emptyList()) =
            finishedStep(base, id, "android_read", outputText = text, sensitiveFieldPaths = sensitive,
                argumentsJson = if (sensitive.isEmpty()) "{}" else "{\"password\":\"secret\"}").map { event ->
                if (event.eventKind != AgentEventKind.TOOL_CALL) event else event.copy(
                    payloadJson = Json.encodeToString(Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
                        .copy(binding = binding, effect = ToolEffect.READ_ONLY)))
            }
        val events = read(10, "battery", "{\"battery_percent\":79,\"battery_health\":\"good\"}") +
            read(20, "memo", "MODEL_ASSERTION", ToolBinding.TURN_LOCAL) +
            read(30, "secret", "SENSITIVE_ECHO", sensitive = listOf("password")) +
            read(40, "large", "x".repeat(20_000)) +
            read(50, "foreign", "FOREIGN_CONTENT").map { it.copy(turnId = OTHER_TURN) }
        val index = SessionEvidenceProjector.sourceIndex(events, THREAD, TURN, 32, includeTextContent = true)
        val sources = index.getValue("sources").jsonArray.map { it.jsonObject }
        assertTrue(sources[0].getValue("original_text").jsonPrimitive.content.contains("battery_percent"))
        assertEquals("false", sources[0].getValue("original_text_truncated").jsonPrimitive.content)
        assertFalse(index.toString().contains("MODEL_ASSERTION"))
        assertFalse(index.toString().contains("SENSITIVE_ECHO"))
        assertFalse(index.toString().contains("FOREIGN_CONTENT"))
        assertEquals(8_000, sources.last().getValue("original_text").jsonPrimitive.content.length)
        assertEquals("true", sources.last().getValue("original_text_truncated").jsonPrimitive.content)
    }

    @Test
    fun citedLongReceiptSurvivesReviewAndOversizedReceiptHasBoundRetrieval() {
        fun read(base: Long, id: String, text: String) = finishedStep(base, id, "android_read", outputText = text)
            .map { event -> if (event.eventKind != AgentEventKind.TOOL_CALL) event else event.copy(
                payloadJson = Json.encodeToString(Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
                    .copy(effect = ToolEffect.READ_ONLY))) }
        val original = "x".repeat(14_672) + "STREAM_MUSIC volume=0 max=160 device=speaker"
        val events = read(10, "earlier", "e".repeat(20_000)) + read(20, "audio", original)
        val index = SessionEvidenceProjector.sourceIndex(events, THREAD, TURN, 1, true, setOf("audio"))
        val source = index.getValue("sources").jsonArray.single().jsonObject
        assertEquals(original, source.getValue("original_text").jsonPrimitive.content)
        assertEquals("false", source.getValue("original_text_truncated").jsonPrimitive.content)
        assertEquals(0, index.getValue("next_after").jsonPrimitive.int)
        val large = SessionEvidenceProjector.sourceIndex(read(30, "large", "x".repeat(40_000)),
            THREAD, TURN, 32, true, setOf("large")).getValue("sources").jsonArray.single().jsonObject
        assertEquals("", large.getValue("original_text").jsonPrimitive.content)
        assertEquals("true", large.getValue("original_text_truncated").jsonPrimitive.content)
        val retrieval = large.getValue("read_arguments").jsonObject
        assertEquals("artifact", retrieval.getValue("mode").jsonPrimitive.content)
        assertNotNull(SessionEvidenceProjector.find(SessionEvidenceProjector.steps(read(30, "large", "x"), THREAD, TURN),
            retrieval.getValue("ref").jsonPrimitive.content))
    }

    @Test
    fun absentSensitiveFieldDoesNotHideDeviceReceiptDuringReviewOrSessionRead() {
        val events = finishedStep(10, "read", "android_read", outputText = "battery_status=4",
            argumentsJson = "{\"op\":\"system\",\"service\":\"battery\"}",
            sensitiveFieldPaths = listOf("selection_args")).map { event ->
            if (event.eventKind != AgentEventKind.TOOL_CALL) event else event.copy(
                payloadJson = Json.encodeToString(Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
                    .copy(effect = ToolEffect.READ_ONLY)))
        }
        val index = SessionEvidenceProjector.sourceIndex(events, THREAD, TURN, 32, includeTextContent = true)
        assertTrue(index.toString().contains("battery_status=4"))
        val step = SessionEvidenceProjector.steps(events, THREAD, TURN).single()
        assertEquals("battery_status=4", SessionEvidenceProjector.describe(step, true)
            .getValue("result").jsonPrimitive.content)
    }

    @Test
    fun presentNestedSensitiveFieldsAndMalformedArgumentsRemainRedacted() {
        for (arguments in listOf("{\"values\":[{\"secret\":\"private\"}]}", "invalid-json")) {
            val step = SessionEvidenceProjector.steps(finishedStep(10, "read", "read",
                outputText = "echo private", argumentsJson = arguments,
                sensitiveFieldPaths = listOf("arguments.values.0.secret")), THREAD, TURN).single()
            val description = SessionEvidenceProjector.describe(step, true)
            assertEquals("true", description.getValue("content_redacted").jsonPrimitive.content)
            assertFalse(description.toString().contains("private"))
        }
    }

    @Test
    fun sourceIndexIncludesUncitedOriginalsWithoutLeakingAssertionsAndPagesBySettlementSequence() {
        fun source(base: Long, id: String) = finishedStep(base, id, "observe_screen",
            outputText = "unsupported-assertion", argumentsJson = "{\"secret\":\"private-value\"}") +
            attachment(base + 2, id, "attachment-$id", "image-$id", DetailAttachmentRole.RESULT, HASH_A)
        val history = source(10, "uncited") + source(20, "cited") +
            source(30, "foreign").map { it.copy(turnId = OTHER_TURN) }
        val first = SessionEvidenceProjector.sourceIndex(history, THREAD, TURN, 1)
        assertEquals("11", first.getValue("next_after").jsonPrimitive.content)
        assertTrue(first.toString().contains("image-uncited"))
        val complete = SessionEvidenceProjector.sourceIndex(history, THREAD, TURN, 32)
        assertEquals(2, complete.getValue("sources").jsonArray.size)
        assertTrue(complete.toString().contains("image-cited"))
        assertFalse(complete.toString().contains("foreign"))
        assertFalse(complete.toString().contains("unsupported-assertion"))
        assertFalse(complete.toString().contains("private-value"))
    }

    @Test
    fun sourceIndexRetainsExternalTextReadsButExcludesLocalWorkingNotes() {
        fun read(base: Long, id: String, binding: ToolBinding) =
            finishedStep(base, id, "read", outputText = "source-content").map { event ->
                if (event.eventKind != AgentEventKind.TOOL_CALL) event else event.copy(
                    payloadJson = Json.encodeToString(Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
                        .copy(binding = binding, effect = ToolEffect.READ_ONLY)))
            }
        val index = SessionEvidenceProjector.sourceIndex(
            read(10, "original", ToolBinding.DEVICE_BOUND) + read(20, "memo", ToolBinding.TURN_LOCAL),
            THREAD, TURN, 32)
        assertEquals(1, index.getValue("sources").jsonArray.size)
        assertTrue(index.toString().contains("original"))
        assertFalse(index.toString().contains("memo"))
        assertFalse(index.toString().contains("source-content"))
    }

    @Test
    fun stepsAreClosedByFinishedOrRejectedCallsAndRemainScopedToThreadAndTurn() {
        val current = finishedStep(
            baseSequence = 10,
            callId = "finished-current",
            tool = "observe_screen",
            observationId = "observation-current"
        ) + rejectedStep(
            baseSequence = 30,
            callId = "rejected-current",
            tool = "tap",
            diagnostic = "ARGUMENT_SCHEMA_MISMATCH"
        )
        val otherTurn = finishedStep(
            baseSequence = 50,
            callId = "finished-other-turn",
            tool = "observe_screen"
        ).map { it.copy(turnId = OTHER_TURN) }
        val otherThread = finishedStep(
            baseSequence = 70,
            callId = "finished-other-thread",
            tool = "observe_screen"
        ).map { it.copy(threadId = OTHER_THREAD) }

        val steps = SessionEvidenceProjector.steps(
            current + otherTurn + otherThread,
            THREAD,
            TURN
        )

        assertEquals(listOf("finished-current", "rejected-current"), steps.map { it.event.callId })
        assertEquals(ToolExecutionStatus.SUCCEEDED, steps[0].result.executionStatus)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, steps[1].result.executionStatus)
        assertEquals(AgentEventKind.TOOL_CALL_REJECTED, steps[1].event.eventKind)
    }

    @Test
    fun exactReferencesFindStepBySettlementCallResultObservationOrAttachment() {
        val events = finishedStep(
            baseSequence = 10,
            callId = "call-1",
            tool = "observe_screen",
            observationId = "result-observation"
        ) + attachment(
            sequence = 13,
            callId = "call-1",
            attachmentId = "attachment-1",
            observationId = "input-observation",
            role = DetailAttachmentRole.INPUT_OBSERVATION,
            hash = HASH_A
        )
        val steps = SessionEvidenceProjector.steps(events, THREAD, TURN)

        assertNotNull(SessionEvidenceProjector.find(steps, "event-call-1-tool_finished"))
        assertNotNull(SessionEvidenceProjector.find(steps, "call-1"))
        assertNotNull(SessionEvidenceProjector.find(steps, "result-observation"))
        assertNotNull(SessionEvidenceProjector.find(steps, "input-observation"))
        assertNotNull(SessionEvidenceProjector.find(steps, "attachment-1"))
        assertNull(SessionEvidenceProjector.find(steps, "unknown-reference"))
    }

    @Test
    fun descriptionRetainsDisplayExecutionAndAttachmentSourceIdentityWithoutRawDiagnosticBody() {
        val events = attemptCreated(sequence = 1) + finishedStep(
            baseSequence = 10,
            callId = "call-1",
            tool = "observe_screen",
            observationId = "result-observation"
        ) + attachment(
            sequence = 13,
            callId = "call-1",
            attachmentId = "attachment-1",
            observationId = "input-observation",
            role = DetailAttachmentRole.INPUT_OBSERVATION,
            hash = HASH_A
        ) + attachment(
            sequence = 14,
            callId = "call-1",
            attachmentId = "attachment-diagnostic",
            observationId = "diagnostic-observation",
            role = DetailAttachmentRole.DIAGNOSTIC,
            hash = HASH_B
        )
        val step = SessionEvidenceProjector.steps(events, THREAD, TURN).single()
        val described = SessionEvidenceProjector.describe(step, includeContent = false)

        assertEquals(317, described.getValue("display_id").jsonPrimitive.int)
        assertEquals("VIRTUAL_SPACE", described.getValue("execution_space").jsonPrimitive.content)
        assertEquals("STANDARD", described.getValue("runtime_mode").jsonPrimitive.content)
        assertEquals("backend-1", described.getValue("backend_session_id").jsonPrimitive.content)
        assertEquals("attempt-1", described.getValue("attempt_ref").jsonPrimitive.content)
        assertEquals("turn", described.getValue("source_turn_ref").jsonPrimitive.content)
        assertEquals("thread", described.getValue("source_thread_ref").jsonPrimitive.content)
        assertEquals(1, described.getValue("attachments").jsonArray.size)
        val attachment = described.getValue("attachments").jsonArray.single().jsonObject
        assertEquals("attachment-1", attachment.getValue("ref").jsonPrimitive.content)
        assertEquals("input-observation", attachment.getValue("observation_ref").jsonPrimitive.content)
        assertEquals(HASH_A, attachment.getValue("sha256").jsonPrimitive.content)
        assertEquals("event-call-1-tool_finished", attachment.getValue("source_event_ref").jsonPrimitive.content)
        assertEquals("call-1", attachment.getValue("source_call_ref").jsonPrimitive.content)
    }

    @Test
    fun sensitiveStepContentIsRedactedButAttachmentIdentityRemainsAvailable() {
        val events = finishedStep(
            baseSequence = 10,
            callId = "sensitive-call",
            tool = "type_text",
            argumentsJson = "{\"password\":\"secret-value\"}",
            outputText = "echoed secret-value",
            sensitiveFieldPaths = listOf("password")
        ) + attachment(
            sequence = 13,
            callId = "sensitive-call",
            attachmentId = "sensitive-attachment",
            observationId = "sensitive-observation",
            role = DetailAttachmentRole.RESULT,
            hash = HASH_A
        )
        val step = SessionEvidenceProjector.steps(events, THREAD, TURN).single()
        val described = SessionEvidenceProjector.describe(step, includeContent = true).toString()

        assertTrue(described.contains("content_redacted"))
        assertFalse(described.contains("secret-value"))
        assertTrue(described.contains("sensitive-attachment"))
        assertTrue(described.contains(HASH_A))
    }

    @Test
    fun reread_attachment_keeps_read_event_trace_and_original_source_identity() {
        val originalAttachment = DetailAttachmentAddedEventPayload(
            contractVersion = 1,
            attachmentId = "original-attachment",
            role = DetailAttachmentRole.RESULT,
            contentRef = "sha256:$HASH_A",
            contentHash = HASH_A,
            sizeBytes = 12,
            mimeType = "image/png",
            observationId = "original-observation"
        )
        val original = attemptCreated(sequence = 1) + finishedStep(
            baseSequence = 10,
            callId = "original-call",
            tool = "observe_screen"
        ) + attachment(
            sequence = 13,
            callId = "original-call",
            attachmentId = "original-attachment",
            observationId = "original-observation",
            role = DetailAttachmentRole.RESULT,
            hash = HASH_A
        )
        val secondAttachment = originalAttachment.copy(attachmentId = "read-call:history")
        val reread = finishedStep(
            baseSequence = 30,
            callId = "read-call",
            tool = "session_read",
            historicalAttachment = originalAttachment
        ).map { it.copy(attemptId = null, executionId = null) } + attachment(
            sequence = 33,
            callId = "read-call",
            attachmentId = "read-call:history",
            observationId = "original-observation",
            role = DetailAttachmentRole.RESULT,
            hash = HASH_A
        ).copy(attemptId = null, executionId = null)

        val rereadAgain = finishedStep(
            baseSequence = 50,
            callId = "read-again-call",
            tool = "session_read",
            historicalAttachment = secondAttachment
        ).map { it.copy(attemptId = null, executionId = null) } + attachment(
            sequence = 53,
            callId = "read-again-call",
            attachmentId = "read-again-call:history",
            observationId = "original-observation",
            role = DetailAttachmentRole.RESULT,
            hash = HASH_A
        ).copy(attemptId = null, executionId = null)

        val steps = SessionEvidenceProjector.steps(original + reread + rereadAgain, THREAD, TURN)
        val readStep = SessionEvidenceProjector.find(steps, "read-again-call:history")
        assertNotNull(readStep)
        val described = SessionEvidenceProjector.describe(checkNotNull(readStep), includeContent = false)
        assertEquals("event-read-again-call-tool_finished", described.getValue("event_ref").jsonPrimitive.content)
        assertEquals(51, described.getValue("sequence").jsonPrimitive.int)
        assertEquals(317, described.getValue("historical_source").jsonObject.getValue("display_id").jsonPrimitive.int)
        assertEquals(
            "event-original-call-tool_finished",
            described.getValue("historical_source").jsonObject.getValue("event_ref").jsonPrimitive.content
        )
        assertEquals(1011, described.getValue("historical_source").jsonObject.getValue("occurred_at").jsonPrimitive.int)
        assertEquals("original-attachment", described.getValue("historical_source").jsonObject.getValue("attachment_ref").jsonPrimitive.content)
    }

    @Test
    fun duplicateEventIdsAndDuplicateSettlementsDoNotCreateExtraStepsOrAttachments() {
        val original = finishedStep(
            baseSequence = 10,
            callId = "call-1",
            tool = "observe_screen"
        ) + attachment(
            sequence = 13,
            callId = "call-1",
            attachmentId = "attachment-1",
            observationId = "observation-1",
            role = DetailAttachmentRole.INPUT_OBSERVATION,
            hash = HASH_A
        )
        val duplicate = original.map {
            it.copy(sequence = it.sequence + 100, idempotencyKey = "duplicate-${it.eventId}")
        }
        val repeatedSettlement = original.first { it.eventKind == AgentEventKind.TOOL_FINISHED }.copy(
            eventId = "event-call-1-tool_finished-repeat",
            sequence = 50,
            idempotencyKey = "repeat-settlement"
        )

        val steps = SessionEvidenceProjector.steps(
            original + duplicate + repeatedSettlement,
            THREAD,
            TURN
        )

        assertEquals(1, steps.size)
        assertEquals(listOf("attachment-1"), steps.single().attachments.map { it.attachmentId })
    }

    private fun finishedStep(
        baseSequence: Long,
        callId: String,
        tool: String,
        observationId: String? = null,
        argumentsJson: String = "{}",
        outputText: String = "result",
        sensitiveFieldPaths: List<String> = emptyList(),
        historicalAttachment: DetailAttachmentAddedEventPayload? = null
    ): List<AgentEventEnvelope> {
        val result = ToolExecutionResult(
            outputText = outputText,
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.CAPTURED,
            observationId = observationId,
            historicalAttachment = historicalAttachment
        )
        return listOf(
            declaration(
                sequence = baseSequence,
                callId = callId,
                tool = tool,
                argumentsJson = argumentsJson,
                sensitiveFieldPaths = sensitiveFieldPaths
            ),
            event(
                sequence = baseSequence + 1,
                kind = AgentEventKind.TOOL_FINISHED,
                callId = callId,
                attemptId = "attempt-1",
                executionId = "execution-1",
                payloadJson = Json.encodeToString(ToolFinishedEventPayload(
                    contractVersion = 1,
                    result = result,
                    roundDetailRef = "round-detail"
                ))
            )
        )
    }

    private fun rejectedStep(
        baseSequence: Long,
        callId: String,
        tool: String,
        diagnostic: String
    ): List<AgentEventEnvelope> = listOf(
        declaration(baseSequence, callId, tool),
        event(
            sequence = baseSequence + 1,
            kind = AgentEventKind.TOOL_CALL_REJECTED,
            callId = callId,
            attemptId = "attempt-1",
            executionId = "execution-1",
            payloadJson = Json.encodeToString(ToolCallRejectedEventPayload(
                contractVersion = 1,
                result = ToolExecutionResult(
                    outputText = "rejected",
                    executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                    diagnosticCode = diagnostic
                ),
                candidateCapabilitySnapshotRef = null,
                candidateCapabilitySnapshotHash = null,
                roundDetailRef = "round-detail"
            ))
        )
    )

    private fun attemptCreated(sequence: Long): List<AgentEventEnvelope> = listOf(
        event(
            sequence = sequence,
            kind = AgentEventKind.EXECUTION_ATTEMPT_CREATED,
            callId = null,
            attemptId = "attempt-1",
            executionId = "execution-1",
            payloadJson = Json.encodeToString(ExecutionAttemptCreatedEventPayload(
                contractVersion = 1,
                state = ExecutionAttemptState.CREATED,
                context = ExecutionContextSnapshotPayload(
                    executionId = "execution-1",
                    runtimeMode = RuntimeMode.STANDARD,
                    executionSpace = ExecutionSpace.VIRTUAL_SPACE,
                    displayId = 317,
                    backendSessionId = "backend-1",
                    capabilitySnapshotRef = "capability-1",
                    capabilitySnapshotHash = HASH_A,
                    cancellationScopeId = "cancel-1"
                ),
                capabilitySnapshot = CapabilitySnapshotEventPayload(
                    capabilitySnapshotRef = "capability-1",
                    capabilitySnapshotHash = HASH_A,
                    runtimeMode = RuntimeMode.STANDARD,
                    executionSpace = ExecutionSpace.VIRTUAL_SPACE,
                    displayId = 317,
                    backendKind = ExecutionBackendKind.AIR_SIDECAR,
                    backendSessionId = "backend-1",
                    backendAvailable = true,
                    capabilities = emptyList(),
                    scopedShellCapability = null
                )
            ))
        )
    )

    private fun attachment(
        sequence: Long,
        callId: String,
        attachmentId: String,
        observationId: String,
        role: DetailAttachmentRole,
        hash: String
    ): AgentEventEnvelope = event(
        sequence = sequence,
        kind = AgentEventKind.DETAIL_ATTACHMENT_ADDED,
        callId = callId,
        attemptId = "attempt-1",
        executionId = "execution-1",
        payloadJson = Json.encodeToString(DetailAttachmentAddedEventPayload(
            contractVersion = 1,
            attachmentId = attachmentId,
            role = role,
            contentRef = "sha256:$hash",
            contentHash = hash,
            sizeBytes = 12,
            mimeType = "image/png",
            observationId = observationId
        ))
    )

    private fun declaration(
        sequence: Long,
        callId: String,
        tool: String,
        argumentsJson: String = "{}",
        sensitiveFieldPaths: List<String> = emptyList()
    ): AgentEventEnvelope = event(
        sequence = sequence,
        kind = AgentEventKind.TOOL_CALL,
        callId = callId,
        attemptId = "attempt-1",
        executionId = "execution-1",
        payloadJson = Json.encodeToString(ToolCallEventPayload(
            contractVersion = 1,
            providerOrder = 0,
            providerCallId = callId,
            name = tool,
            argumentsJson = argumentsJson,
            source = ToolCallSource.MODEL_REQUESTED,
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            contractHash = HASH_A,
            presentation = ToolPresentationSnapshot(
                labelResourceKey = "tool.test",
                category = ToolPresentationCategory.PRIMARY,
                resultImageRole = ResultImageRole.BEFORE_AND_AFTER,
                sensitiveFieldPaths = sensitiveFieldPaths
            ),
            roundDetailRef = "round-detail"
        ))
    )

    private fun event(
        sequence: Long,
        kind: AgentEventKind,
        callId: String?,
        attemptId: String?,
        executionId: String?,
        payloadJson: String
    ): AgentEventEnvelope = AgentEventEnvelope(
        schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
        eventId = "event-${callId ?: kind.name.lowercase()}-${kind.name.lowercase()}",
        eventKind = kind,
        sequence = sequence,
        occurredAt = sequence + 1000,
        threadId = THREAD,
        turnId = TURN,
        roundId = "round",
        attemptId = attemptId,
        executionId = executionId,
        callId = callId,
        payloadHash = HASH_A,
        payloadJson = payloadJson,
        idempotencyKey = "key-$sequence-${kind.name}"
    )

    private companion object {
        const val THREAD = "thread"
        const val TURN = "turn"
        const val OTHER_THREAD = "other-thread"
        const val OTHER_TURN = "other-turn"
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
    }
}
