// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.event.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBrowsingProgressTest {
    private val scope = TurnEventScope("thread", "turn")
    private val policy = WorkspaceContextPolicy(
        maxActiveChars = 2048,
        deviceChangesBeforeReview = 1,
        browsingOperations = mapOf("screen_act" to listOf("scroll"))
    )

    @Test fun batchScrollUsesTheCanonicalOpField() {
        assertTrue(WorkspaceBrowsingProgress.isBrowsing("screen_act",
            """{"actions":[{"op":"screenshot"},{"op":"scroll","direction":"down"}]}""", policy))
        assertFalse(WorkspaceBrowsingProgress.isBrowsing("screen_act",
            """{"actions":[{"op":"click","x":2,"y":3}]}""", policy))
    }

    @Test
    fun freshWorkspaceDoesNotNeedAReviewBeforeItsFirstAction() {
        val progress = WorkspaceBrowsingProgress(
            browseCallsSinceRecordChange = 0,
            recentObservationRefs = emptyList(),
            activeFile = null,
            deviceCallsSinceRecordChange = 0
        )

        assertFalse(progress.reviewDue(policy))
        assertTrue(progress.copy(browseCallsSinceRecordChange = 1).reviewDue(policy))
    }

    @Test
    fun recordReviewBecomesAdvisoryAfterDeviceChanges() {
        val active = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val ready = WorkspaceBrowsingProgress(0, emptyList(), active, 0)

        assertFalse(ready.reviewDue(policy))

        val stale = ready.copy(deviceCallsSinceRecordChange = 1)
        assertTrue(stale.reviewDue(policy))
    }

    @Test
    fun changedActiveRecordResetsTheReviewAdvisory() {
        val file = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val newer = file.copy(contentRef = "sha256:${"b".repeat(64)}", requestHash = "new-hash")
        val beforeRecordChange = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledScroll(3, "scroll-1", "obs-1").toTypedArray(),
        )

        val before = WorkspaceBrowsingProgress.project(beforeRecordChange, scope, policy)
        assertEquals(1, before.deviceCallsSinceRecordChange)
        assertTrue(before.reviewDue(policy))

        val afterRecordChange = beforeRecordChange + workspaceWrite(6, newer)
        val reset = WorkspaceBrowsingProgress.project(afterRecordChange, scope, policy)
        assertEquals(0, reset.deviceCallsSinceRecordChange)
        assertEquals(0, reset.browseCallsSinceRecordChange)
        assertFalse(reset.reviewDue(policy))

        val afterNextBrowse = WorkspaceBrowsingProgress.project(
            afterRecordChange + settledScroll(8, "scroll-2", "obs-2"), scope, policy
        )
        assertEquals(1, afterNextBrowse.deviceCallsSinceRecordChange)
        assertEquals(listOf("obs-2"), afterNextBrowse.recentObservationRefs)
        assertEquals(newer.contentRef, afterNextBrowse.activeFile?.contentRef)
        assertTrue(afterNextBrowse.reviewDue(policy))
    }

    @Test
    fun emptyFileWriteDoesNotResetTheReviewAdvisory() {
        val file = WorkspaceFileVersion("work.md", "sha256:${"a".repeat(64)}", 0, 0, "hash")
        val beforeRecordChange = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledScroll(3, "scroll-1", "obs-1").toTypedArray(),
        )

        val before = WorkspaceBrowsingProgress.project(beforeRecordChange, scope, policy)
        assertEquals(1, before.deviceCallsSinceRecordChange)
        assertTrue(before.reviewDue(policy))

        val emptyUpdate = file.copy(contentRef = "sha256:${"b".repeat(64)}", chars = 0, sizeBytes = 0)
        val afterRecordChange = beforeRecordChange + workspaceWrite(6, emptyUpdate)
        val notReset = WorkspaceBrowsingProgress.project(afterRecordChange, scope, policy)
        assertEquals(1, notReset.deviceCallsSinceRecordChange)
        assertTrue(notReset.reviewDue(policy))
    }

    @Test
    fun historicalRecordRejectionsDoNotCreateAnotherExecutionBudget() {
        val file = WorkspaceFileVersion("work.md", "sha256:${"a".repeat(64)}", 4, 4, "hash-a")
        val events = mutableListOf<AgentEventEnvelope>()
        events += workspaceWrite(1, file)
        events += focus(2, file.path)
        events += settledScroll(3, "scroll-1", "obs-1")
        val before = WorkspaceBrowsingProgress.project(events, scope, policy)
        events += workspaceReviewRejected(6, "record-reject-1")
        events += workspaceReviewRejected(8, "record-reject-2")
        val afterRejections = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(before, afterRejections)
        assertFalse(afterRejections.facts().containsKey("pending_record_rejections"))
        assertTrue(afterRejections.reviewDue(policy))
        events += settledScroll(10, "scroll-2", "obs-2")
        val continued = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(2, continued.deviceCallsSinceRecordChange)
        assertEquals(2, continued.browseCallsSinceRecordChange)
        assertEquals(listOf("obs-2"), continued.recentObservationRefs)
        assertEquals(file, continued.activeFile)
        assertTrue(continued.reviewDue(policy))
    }

    @Test
    fun pureObservationScreenBatchesDoNotCountAsDeviceChangesButMixedBatchesDo() {
        val file = WorkspaceFileVersion("work.md", "sha256:${"a".repeat(64)}", 4, 4, "hash")
        val events = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledAction(3, "wait-only", screenBatch("wait", "screenshot"), "obs-wait").toTypedArray(),
            *settledAction(6, "mixed", screenBatch("screenshot", "click"), "obs-click").toTypedArray()
        )

        val progress = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(1, progress.deviceCallsSinceRecordChange)
        assertEquals(0, progress.browseCallsSinceRecordChange)
        assertTrue(progress.recentObservationRefs.isEmpty())
    }

    @Test
    fun unrelatedStateChangingActionsCountAsDeviceChangesAndMakeTheRecordStale() {
        val file = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val events = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledScroll(3, "scroll-1", "obs-1").toTypedArray(),
            *settledClick(6, "click-1", "obs-2").toTypedArray()
        )

        val progress = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(0, progress.browseCallsSinceRecordChange)
        assertEquals(2, progress.deviceCallsSinceRecordChange)
        assertTrue(progress.recentObservationRefs.isEmpty())
        assertTrue(progress.reviewDue(policy))
    }

    @Test
    fun sameContentRewriteAndFocusDoNotMakeAStaleRecordCurrent() {
        val file = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val events = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledScroll(3, "scroll-1", "obs-1").toTypedArray(),
            focus(6, file.path),
            workspaceWrite(7, file.copy(requestHash = "same-content-rewrite"))
        )

        val progress = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(1, progress.deviceCallsSinceRecordChange)
        assertEquals(1, progress.browseCallsSinceRecordChange)
        assertEquals(listOf("obs-1"), progress.recentObservationRefs)
        assertTrue(progress.reviewDue(policy))
    }

    @Test
    fun writingAnUnfocusedFileDoesNotResetBrowsingBeforeItIsFocused() {
        val fileA = WorkspaceFileVersion("a.md", "sha256:${"a".repeat(64)}", 5, 5, "hash-a")
        val fileB = WorkspaceFileVersion("b.md", "sha256:${"b".repeat(64)}", 5, 5, "hash-b")
        val events = listOf(
            workspaceWrite(1, fileA),
            focus(2, fileA.path),
            *settledScroll(3, "scroll-a1", "obs-a1").toTypedArray(),
            *settledScroll(6, "scroll-a2", "obs-a2").toTypedArray(),
            workspaceWrite(9, fileB),
            *settledScroll(10, "scroll-a3", "obs-a3").toTypedArray(),
            focus(12, fileB.path)
        )

        val progress = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(3, progress.deviceCallsSinceRecordChange)
        assertEquals(3, progress.browseCallsSinceRecordChange)
        assertEquals(fileB.contentRef, progress.activeFile?.contentRef)
        assertTrue(progress.reviewDue(policy))
    }

    @Test
    fun sameContentActivatedRewriteDoesNotClearBrowsingProgress() {
        val file = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val events = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledScroll(3, "scroll-1", "obs-1").toTypedArray(),
            workspaceWrite(6, file.copy(requestHash = "same-content-activate")),
            focus(7, file.path),
            *settledScroll(8, "scroll-2", "obs-2").toTypedArray()
        )

        val progress = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(2, progress.deviceCallsSinceRecordChange)
        assertEquals(2, progress.browseCallsSinceRecordChange)
        assertTrue(progress.reviewDue(policy))
    }

    @Test
    fun planUpdatesDoNotPretendTheWorkingRecordWasUpdated() {
        val file = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val memoCall = ToolCallEventPayload(
            contractVersion = 1, providerOrder = 0, providerCallId = "memo-1",
            name = "task_memo", argumentsJson = "{}",
            source = ToolCallSource.MODEL_REQUESTED, binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY, contractHash = "a".repeat(64),
            presentation = ToolPresentationSnapshot(
                "task.memo", ToolPresentationCategory.PRIMARY, ResultImageRole.NONE
            ), roundDetailRef = "round-10"
        )
        val memoResult = ToolExecutionResult(
            outputText = "working",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
        )
        val events = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledScroll(3, "scroll-1", "obs-1").toTypedArray(),
            *settledClick(6, "click-1", "obs-2").toTypedArray()
        )

        val beforeMemo = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(2, beforeMemo.deviceCallsSinceRecordChange)
        assertTrue(beforeMemo.reviewDue(policy))

        val eventsWithMemo = events + listOf(
            event(9, AgentEventKind.TOOL_CALL, "memo-1", "round-9", Json.encodeToString(memoCall), "call-memo-1"),
            event(10, AgentEventKind.TOOL_FINISHED, "memo-1", "round-9",
                Json.encodeToString(ToolFinishedEventPayload(1, memoResult, "round-9")), "finish-memo-1")
        )
        val afterMemo = WorkspaceBrowsingProgress.project(eventsWithMemo, scope, policy)
        assertEquals(2, afterMemo.deviceCallsSinceRecordChange)
        assertEquals(0, afterMemo.browseCallsSinceRecordChange)
        assertTrue(afterMemo.reviewDue(policy))
    }

    @Test
    fun focusingAndReadingTheActiveRecordDoNotResetBrowsingProgress() {
        val file = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val events = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledScroll(3, "scroll-1", "obs-1").toTypedArray(),
            focus(6, file.path),
            *settledWorkspaceRead(7, "read-1").toTypedArray(),
            *settledScroll(9, "scroll-2", "obs-2").toTypedArray()
        )

        val beforeSecondBrowse = WorkspaceBrowsingProgress.project(events.dropLast(2), scope, policy)
        assertEquals(1, beforeSecondBrowse.deviceCallsSinceRecordChange)
        assertTrue(beforeSecondBrowse.reviewDue(policy))

        val progress = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(2, progress.deviceCallsSinceRecordChange)
        assertEquals(2, progress.browseCallsSinceRecordChange)
        assertEquals(listOf("obs-2"), progress.recentObservationRefs)
        assertTrue(progress.reviewDue(policy))
    }

    @Test
    fun unknownSuccessfulDeviceCallCountsButRejectedBrowseDoesNot() {
        val file = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val events = listOf(
            workspaceWrite(1, file),
            focus(2, file.path),
            *settledUnknownClick(3, "click-unknown").toTypedArray(),
            *rejected(6, "review-1").toTypedArray()
        )

        val progress = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(1, progress.deviceCallsSinceRecordChange)
        assertEquals(0, progress.browseCallsSinceRecordChange)
        assertTrue(progress.recentObservationRefs.isEmpty())
        assertTrue(progress.reviewDue(policy))
    }

    @Test
    fun rejectedActionsDoNotChangeCountersAndUserSteerStartsANewBrowsingEpoch() {
        val file = WorkspaceFileVersion("research.md", "sha256:${"a".repeat(64)}", 5, 5, "hash")
        val events = mutableListOf<AgentEventEnvelope>()
        events += workspaceWrite(1, file)
        events += focus(2, file.path)
        events += settledScroll(3, "scroll-1", "obs-1")
        events += settledScroll(6, "scroll-2", "obs-2")
        events += rejected(9, "review-1")
        events += rejected(11, "review-2")

        val beforeSteer = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(2, beforeSteer.deviceCallsSinceRecordChange)
        assertEquals(2, beforeSteer.browseCallsSinceRecordChange)
        assertTrue(beforeSteer.reviewDue(policy))

        events += userSteer(13)
        events += settledScroll(14, "scroll-3", "obs-3")
        val afterSteer = WorkspaceBrowsingProgress.project(events, scope, policy)
        assertEquals(1, afterSteer.browseCallsSinceRecordChange)
        assertEquals(3, afterSteer.deviceCallsSinceRecordChange)
        assertEquals(listOf("obs-3"), afterSteer.recentObservationRefs)
        assertTrue(afterSteer.reviewDue(policy))
    }

    private fun workspaceWrite(sequence: Long, file: WorkspaceFileVersion) = event(
        sequence, AgentEventKind.WORKSPACE_FILE_WRITTEN, null, null, Json.encodeToString(file), "workspace-$sequence"
    )

    private fun focus(sequence: Long, path: String) = event(
        sequence, AgentEventKind.WORKSPACE_FOCUS_CHANGED, null, null,
        Json.encodeToString(WorkspaceFocus(path)), "focus-$sequence"
    )

    private fun settledScroll(sequence: Long, callId: String, observationId: String): List<AgentEventEnvelope> =
        settled(sequence, callId, observationId, "{\"actions\":[{\"op\":\"scroll\",\"direction\":\"down\"}]}")

    private fun settledAction(sequence: Long, callId: String, arguments: String, observationId: String): List<AgentEventEnvelope> =
        settled(sequence, callId, observationId, arguments)

    private fun screenBatch(vararg types: String): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject(mapOf(
            "actions" to kotlinx.serialization.json.JsonArray(types.map { type ->
                kotlinx.serialization.json.JsonObject(mapOf("op" to kotlinx.serialization.json.JsonPrimitive(type)))
            })
        ))
    )

    private fun settledClick(sequence: Long, callId: String, observationId: String): List<AgentEventEnvelope> =
        settled(sequence, callId, observationId, "{\"actions\":[{\"op\":\"click\",\"x\":100,\"y\":100}]}")

    private fun settledUnknownClick(sequence: Long, callId: String): List<AgentEventEnvelope> =
        settled(
            sequence, callId, null, "{\"actions\":[{\"op\":\"click\",\"x\":100,\"y\":100}]}",
            evidenceStatus = ToolEvidenceStatus.UNKNOWN
        )

    private fun settledWorkspaceRead(sequence: Long, callId: String): List<AgentEventEnvelope> {
        val call = ToolCallEventPayload(
            contractVersion = 1,
            providerOrder = 0,
            providerCallId = callId,
            name = "workspace",
            argumentsJson = "{\"op\":\"read\",\"path\":\"research.md\"}",
            source = ToolCallSource.MODEL_REQUESTED,
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY,
            contractHash = "a".repeat(64),
            presentation = ToolPresentationSnapshot(
                "workspace.read", ToolPresentationCategory.PRIMARY, ResultImageRole.NONE
            ),
            roundDetailRef = "round-$sequence"
        )
        val result = ToolExecutionResult(
            outputText = "record page",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
        )
        return listOf(
            event(sequence, AgentEventKind.TOOL_CALL, callId, "round-$sequence", Json.encodeToString(call), "call-$callId"),
            event(sequence + 1, AgentEventKind.TOOL_FINISHED, callId, "round-$sequence",
                Json.encodeToString(ToolFinishedEventPayload(1, result, "round-$sequence")), "finish-$callId")
        )
    }

    private fun rejected(sequence: Long, callId: String): List<AgentEventEnvelope> {
        val call = ToolCallEventPayload(
            contractVersion = 1, providerOrder = 0, providerCallId = callId,
            name = "screen_act", argumentsJson = "{\"actions\":[{\"op\":\"scroll\"}]}",
            source = ToolCallSource.MODEL_REQUESTED, binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING, contractHash = "a".repeat(64),
            presentation = ToolPresentationSnapshot(
                "screen.act", ToolPresentationCategory.PRIMARY, ResultImageRole.INPUT
            ), roundDetailRef = "round-$sequence"
        )
        val result = ToolExecutionResult(
            outputText = "fresh observation required", executionStatus = ToolExecutionStatus.NOT_EXECUTED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
            diagnosticCode = "STALE_OBSERVATION"
        )
        return listOf(
            event(sequence, AgentEventKind.TOOL_CALL, callId, "round-$sequence",
                Json.encodeToString(call), "call-$callId"),
            event(sequence + 1, AgentEventKind.TOOL_CALL_REJECTED, callId, "round-$sequence",
                Json.encodeToString(ToolCallRejectedEventPayload(1, result, null, null, "round-$sequence")),
                "rejected-$callId")
        )
    }

    private fun workspaceReviewRejected(sequence: Long, callId: String): List<AgentEventEnvelope> {
        val call = ToolCallEventPayload(
            contractVersion = 1, providerOrder = 0, providerCallId = callId,
            name = "screen_act", argumentsJson = "{\"actions\":[{\"op\":\"click\",\"x\":10,\"y\":20}]}",
            source = ToolCallSource.MODEL_REQUESTED, binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING, contractHash = "a".repeat(64),
            presentation = ToolPresentationSnapshot(
                "screen.act", ToolPresentationCategory.PRIMARY, ResultImageRole.INPUT
            ), roundDetailRef = "round-$sequence"
        )
        val result = ToolExecutionResult(
            outputText = "record review required", executionStatus = ToolExecutionStatus.NOT_EXECUTED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED, diagnosticCode = "WORKSPACE_REVIEW_REQUIRED"
        )
        return listOf(
            event(sequence, AgentEventKind.TOOL_CALL, callId, "round-$sequence",
                Json.encodeToString(call), "call-$callId"),
            event(sequence + 1, AgentEventKind.TOOL_CALL_REJECTED, callId, "round-$sequence",
                Json.encodeToString(ToolCallRejectedEventPayload(1, result, null, null, "round-$sequence")),
                "rejected-$callId")
        )
    }

    private fun userSteer(sequence: Long): AgentEventEnvelope = event(
        sequence, AgentEventKind.USER_STEER, null, null,
        Json.encodeToString(UserTextEventPayload(1, "steer-$sequence", "inspect the current state")),
        "steer-$sequence"
    )

    private fun settled(
        sequence: Long,
        callId: String,
        observationId: String?,
        arguments: String,
        evidenceStatus: ToolEvidenceStatus = ToolEvidenceStatus.CAPTURED
    ): List<AgentEventEnvelope> {
        val call = ToolCallEventPayload(
            contractVersion = 1,
            providerOrder = 0,
            providerCallId = callId,
            name = "screen_act",
            argumentsJson = arguments,
            source = ToolCallSource.MODEL_REQUESTED,
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            contractHash = "a".repeat(64),
            presentation = ToolPresentationSnapshot(
                "screen.act", ToolPresentationCategory.PRIMARY, ResultImageRole.INPUT
            ),
            roundDetailRef = "round-$sequence"
        )
        val result = ToolExecutionResult(
            outputText = "observed",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = evidenceStatus,
            diagnosticCode = if (evidenceStatus == ToolEvidenceStatus.UNKNOWN) "EVIDENCE_UNAVAILABLE" else null,
            observationId = observationId
        )
        return listOf(
            event(sequence, AgentEventKind.TOOL_CALL, callId, "round-$sequence", Json.encodeToString(call), "call-$callId"),
            event(sequence + 1, AgentEventKind.TOOL_FINISHED, callId, "round-$sequence",
                Json.encodeToString(ToolFinishedEventPayload(1, result, "round-$sequence")), "finish-$callId")
        )
    }

    private fun event(
        sequence: Long,
        kind: AgentEventKind,
        callId: String?,
        roundId: String?,
        payload: String,
        key: String
    ) = AgentEventEnvelope(
        schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
        eventId = "event-$sequence",
        eventKind = kind,
        sequence = sequence,
        occurredAt = sequence,
        threadId = scope.threadId,
        turnId = scope.turnId,
        roundId = roundId,
        attemptId = null,
        executionId = null,
        callId = callId,
        payloadHash = "a".repeat(64),
        payloadJson = payload,
        idempotencyKey = key
    )
}
