// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import org.junit.Assert.*
import org.junit.Test

class TaskAnalysisMetricsTest {
    @Test fun skillMetricsKeepExactRunsAndScopedAssessmentsSeparateFromTaskSuccess() {
        val events = listOf(
            event(1, 10, AgentEventKind.PROGRAM_EXECUTION,
                """{"phase":"skills_selected","data":{"skills":[{"name":"demo","revision":"r1","draft":false}]}}"""),
            event(2, 20, AgentEventKind.PROGRAM_EXECUTION,
                """{"phase":"skill_started","data":{"run_id":"one","skill":"demo","revision":"r1","draft":false,"selection":"explicit","path":null}}"""),
            event(3, 30, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"SUCCEEDED","metrics":{"skill_file_reads":1,"skill_assessment_reads":0,"skill_read_chars":500}}}"""),
            event(4, 40, AgentEventKind.PROGRAM_EXECUTION,
                """{"phase":"skill_assessed","data":{"run_id":"one","revision":"wrong","outcome":"succeeded","evidence_kind":"external"}}"""),
            event(5, 50, AgentEventKind.PROGRAM_EXECUTION,
                """{"phase":"skill_assessed","data":{"run_id":"one","revision":"r1","outcome":"failed","evidence_kind":"external"}}"""),
            event(6, 60, AgentEventKind.PROGRAM_EXECUTION,
                """{"phase":"skill_started","data":{"run_id":"two","skill":"demo","revision":"r2","draft":true,"selection":"agent","path":"scripts/main.js","resume":true}}""")
        )
        val metrics = TaskAnalysisMetrics.project(events)
        assertEquals(1, metrics.skillSelections.size)
        assertEquals(2, metrics.skillRuns.size)
        assertEquals("failed", metrics.skillRuns[0].assessment)
        assertEquals(30L, metrics.skillRuns[0].assessmentElapsedMs)
        assertEquals("explicit", metrics.skillRuns[0].selection)
        assertEquals("unassessed", metrics.skillRuns[1].assessment)
        assertTrue(metrics.skillRuns[1].resumed)
        assertTrue(metrics.skillRuns[1].scripted)
        assertEquals(1L, metrics.skillFileReads)
        assertEquals(500L, metrics.skillReadChars)
        assertNull(metrics.semanticSuccess)
    }

    @Test fun providerCacheRatioExcludesUnknownRequestsAndLocalReuseIsIndependent() {
        val metrics = TaskAnalysisMetrics.project(listOf(
            event(1, 10, AgentEventKind.MODEL_ROUND_COMPLETED, """{"finishMetadata":{
                "response_input_tokens":"100","response_cached_input_tokens":"60","response_cache_write_tokens":"30",
                "local_prefix_reused":"true","preparation_retained_images_ms":"12","first_actionable_output_ms":"90"}}"""),
            event(2, 20, AgentEventKind.MODEL_ROUND_COMPLETED, """{"finishMetadata":{
                "response_input_tokens":"100","response_cached_input_tokens":"0","response_cache_write_tokens":"0",
                "local_prefix_reused":"true","preparation_retained_images_ms":"2"}}"""),
            event(3, 30, AgentEventKind.MODEL_ROUND_COMPLETED, """{"finishMetadata":{
                "response_input_tokens":"1000","local_prefix_reused":"false","local_prefix_reset_reason":"durable_history_changed"}}""")
        ))
        assertEquals(60L, metrics.promptCache.reportedCachedTokens)
        assertEquals(30L, metrics.promptCache.reportedWriteTokens)
        assertEquals(0.3, metrics.promptCache.tokenReadRatio!!, 0.0001)
        assertEquals(2, metrics.promptCache.readReportedRequests)
        assertEquals(2, metrics.promptCache.writeReportedRequests)
        assertEquals(1, metrics.promptCache.hitRequests)
        assertEquals(1, metrics.promptCache.missRequests)
        assertEquals(2, metrics.promptCache.localPrefixReuses)
        assertEquals(mapOf("durable_history_changed" to 1), metrics.promptCache.localPrefixResetReasons)
        assertEquals(DurationSamples(2, 14, 2, 12), metrics.preparationStageDurations["preparation_retained_images_ms"])
        assertEquals(90L, metrics.modelStageDurations["first_actionable_output_ms"]!!.totalMs)
        assertNull(TaskAnalysisMetrics.project(emptyList()).promptCache.tokenReadRatio)
        assertNull(TaskAnalysisMetrics.project(emptyList()).promptCache.reportedWriteTokens)
        assertNull(metrics.semanticSuccess)
    }

    @Test fun batchProgressAndCaptureFactsIncludeStoppedSuffixWithoutSemanticInference() {
        val metrics = TaskAnalysisMetrics.project(listOf(event(1, 10, AgentEventKind.TOOL_FINISHED,
            """{"result":{"executionStatus":"FAILED","diagnosticCode":"PRE_ACTION_OBSERVATION_CHANGED","metrics":{
            "screen_action_batch_calls":1,"batch_requested_actions":4,"batch_completed_actions":2,
            "batch_attempted_actions":3,"batch_unexecuted_actions":1,"batch_primitive_attempts":3,
            "batch_primitive_succeeded_receipts":2,"batch_execution_ms":1500,
            "action_group_final_observations":1,"action_group_capture_samples":3,"action_group_observation_ms":450,
            "action_1_text_input_total_ms":400,"action_1_text_input_delivery_verified":0,
            "action_2_text_input_total_ms":500,"action_2_text_input_delivery_verified":0,
            "text_input_total_ms":500,"text_input_delivery_verified":0}}}""")))
        assertEquals(1L, metrics.screenActionBatchCalls)
        assertEquals(4L, metrics.batchRequestedActions)
        assertEquals(2L, metrics.batchCompletedActions)
        assertEquals(1L, metrics.batchUnexecutedActions)
        assertEquals(mapOf("PRE_ACTION_OBSERVATION_CHANGED" to 1), metrics.batchStopDiagnosticCounts)
        assertEquals(1L, metrics.actionGroupFinalObservations)
        assertEquals(3L, metrics.actionGroupCaptureSamples)
        assertEquals(450L, metrics.actionGroupObservationDurationMs)
        assertEquals(DurationSamples(2, 900, 400, 500), metrics.textInputStageDurations["total"])
        assertEquals(2, metrics.unverifiedTextDeliveries)
        assertNull(metrics.semanticSuccess)
    }

    @Test fun programChildBatchMetricsAreCountedOnceWhenOrdinaryReceiptAlsoExists() {
        val receipt = """{"executionStatus":"SUCCEEDED","metrics":{"screen_action_batch_calls":1,
            "batch_requested_actions":2,"batch_completed_actions":2}}"""
        val child = event(1, 10, AgentEventKind.PROGRAM_EXECUTION,
            """{"phase":"device_finished","data":{"child_id":"child","result":$receipt}}""")
        val finished = event(2, 20, AgentEventKind.TOOL_FINISHED,
            """{"result":$receipt}""").copy(callId = "child")
        assertEquals(1L, TaskAnalysisMetrics.project(listOf(child, finished)).screenActionBatchCalls)
        assertEquals(2L, TaskAnalysisMetrics.project(listOf(child)).batchCompletedActions)
    }

    @Test fun withheldExecutionAndSchemaRejectionAreCountedWithoutInventingAnExecutedFailure() {
        val metrics = TaskAnalysisMetrics.project(listOf(
            event(1, 1, AgentEventKind.TOOL_CALL_REJECTED,
                """{"result":{"executionStatus":"NOT_EXECUTED","diagnosticCode":"ARGUMENT_SCHEMA_MISMATCH"}}"""),
            event(2, 2, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"NOT_EXECUTED","diagnosticCode":"OBSERVATION_RECOVERY_REQUIRED"}}"""),
            event(3, 3, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"FAILED","diagnosticCode":"BACKEND_ERROR"}}""")
        ))
        assertEquals(2, metrics.rejectedCalls)
        assertEquals(1, metrics.failedCalls)
        assertEquals(mapOf("ARGUMENT_SCHEMA_MISMATCH" to 1, "OBSERVATION_RECOVERY_REQUIRED" to 1),
            metrics.rejectedCallDiagnosticCounts)
        assertNull(metrics.semanticSuccess)
    }

    @Test fun inputTimingKeepsFullDurationAndUnknownStagesDistinctFromZero() {
        val metrics = TaskAnalysisMetrics.project(listOf(
            event(1, 1, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"SUCCEEDED","metrics":{"text_input_delivery_verified":0,"text_input_injection_ms":3000,"text_input_total_ms":6000,"text_input_readback_ms":0}}}"""),
            event(2, 2, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"SUCCEEDED","metrics":{"text_input_delivery_verified":1,"text_input_injection_ms":4000,"text_input_total_ms":8000}}}"""),
            event(3, 3, AgentEventKind.MODEL_ROUND_COMPLETED,
                """{"finishMetadata":{"response_input_tokens":"20000"}}"""),
            event(4, 4, AgentEventKind.MODEL_ROUND_COMPLETED,
                """{"finishMetadata":{"response_input_tokens":"25000"}}""")
        ))
        assertEquals(1, metrics.verifiedTextDeliveries)
        assertEquals(1, metrics.unverifiedTextDeliveries)
        assertEquals(DurationSamples(2, 14000, 6000, 8000), metrics.textInputStageDurations["total"])
        assertEquals(DurationSamples(1, 0, 0, 0), metrics.textInputStageDurations["readback"])
        assertFalse(metrics.textInputStageDurations.containsKey("focus"))
        assertEquals(45000L, metrics.reportedInputTokens)
        assertEquals(25000L, metrics.maxReportedInputTokensPerRequest)
    }
    @Test fun editorReadbackDoesNotBecomeTargetVerificationAndExportDoesNotInventRuntimeBuild() {
        val metrics = TaskAnalysisMetrics.project(listOf(
            event(1, 1, AgentEventKind.MODEL_ROUND_COMPLETED,
                """{"finishMetadata":{"runtime_build_version":"test-build","returned_model":"actual-model"}}"""),
            event(2, 2, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"SUCCEEDED","metrics":{"text_input_delivery_verified":0,"text_input_current_editor_readback_verified":1,"text_input_target_focus_bound":0}}}""")
        ))
        assertEquals(listOf("test-build"), metrics.runtimeBuildVersions)
        assertEquals(listOf("actual-model"), metrics.returnedModels)
        assertEquals(1, metrics.currentEditorReadbacksVerified)
        assertEquals(0, metrics.targetFocusBindingsVerified)
        assertEquals(1, metrics.targetFocusBindingMetricsAvailable)
        assertEquals(0, metrics.verifiedTextDeliveries)
        assertEquals("not_evaluated", metrics.semanticAssessment)
        assertTrue(TaskAnalysisMetrics.project(emptyList()).runtimeBuildVersions.isEmpty())
        assertEquals(0, TaskAnalysisMetrics.project(emptyList()).targetFocusBindingMetricsAvailable)
    }

    @Test fun plannedReviewIsReportedSeparatelyFromRejectedProtocolCalls() {
        val metrics = TaskAnalysisMetrics.project(listOf(
            event(1, 1, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"NOT_EXECUTED","diagnosticCode":"COMPLETION_REVIEW_REQUIRED"}}"""),
            event(2, 2, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"NOT_EXECUTED","diagnosticCode":"COMPLETION_RESULTS_REQUIRED"}}""")
        ))
        assertEquals(1, metrics.completionReviewRequests)
        assertEquals(1, metrics.rejectedCalls)
        assertEquals(2, metrics.executionStatusCounts["NOT_EXECUTED"])
        assertEquals(mapOf("COMPLETION_RESULTS_REQUIRED" to 1), metrics.rejectedCallDiagnosticCounts)
    }

    @Test fun streamAndObservationStagesRemainIndependentAndMissingUsageStaysUnknown() {
        val metrics = TaskAnalysisMetrics.project(listOf(
            event(1, 1, AgentEventKind.MODEL_ROUND_COMPLETED,
                """{"finishMetadata":{"first_provider_event_ms":"4000","first_content_event_ms":"44000","reasoning_stream_ms":"39000","response_reasoning_tokens":"2980"}}"""),
            event(2, 2, AgentEventKind.TOOL_FINISHED,
                """{"result":{"executionStatus":"SUCCEEDED","metrics":{"observation_capture_ms":1000,"observation_device_capture_ms":400,"observation_ocr_ms":0}}}""")
        ))
        assertEquals(2980L, metrics.reportedReasoningTokens)
        assertEquals(DurationSamples(1, 4000, 4000, 4000), metrics.modelStageDurations["first_provider_event_ms"])
        assertEquals(DurationSamples(1, 44000, 44000, 44000), metrics.modelStageDurations["first_content_event_ms"])
        assertEquals(DurationSamples(1, 0, 0, 0), metrics.observationStageDurations["observation_ocr_ms"])
        assertFalse(metrics.observationStageDurations.containsKey("observation_fingerprint_ms"))
        assertNull(TaskAnalysisMetrics.project(emptyList()).reportedReasoningTokens)
        assertTrue(TaskAnalysisMetrics.project(emptyList()).modelStageDurations.isEmpty())
    }

    @Test fun experienceRecallMetricsReportOfferedMethodsSearchesAndReceiptsWithoutSuccessClaims() {
        val metrics = TaskAnalysisMetrics.project(listOf(
            event(1, 10, AgentEventKind.MEMORY_CONTEXT_LOADED, """{"contractVersion":1,"queryHash":"q","items":[
                {"ref":"profile:language","scope":"CORE_PROFILE","summary":"Chinese"},
                {"ref":"experience:schedule-meeting:2","scope":"EXPERIENCE","summary":"Historical method"}]}"""),
            event(2, 20, AgentEventKind.MEMORY_CONTEXT_LOADED, """{"contractVersion":1,"queryHash":"r","items":[
                {"ref":"experience:schedule-meeting:2","scope":"EXPERIENCE","summary":"Historical method"},
                {"ref":"experience:share-meeting:1","scope":"EXPERIENCE","summary":"Historical method"}]}"""),
            event(3, 30, AgentEventKind.TOOL_CALL, """{"name":"session_search","argumentsJson":"{\"scope\":\"experience\",\"query\":\"meeting\"}"}"""),
            event(4, 40, AgentEventKind.TOOL_CALL, """{"name":"session_search","argumentsJson":"{\"scope\":\"episode\"}"}"""),
            event(5, 50, AgentEventKind.TOOL_CALL, """{"name":"experience","argumentsJson":"{\"op\":\"use\"}"}""").copy(callId = "use-1"),
            event(6, 60, AgentEventKind.TOOL_FINISHED, """{"result":{"executionStatus":"SUCCEEDED"}}""").copy(callId = "use-1"),
            event(7, 70, AgentEventKind.TOOL_CALL, """{"name":"experience","argumentsJson":"{\"op\":\"save\"}"}""").copy(callId = "save-1"),
            event(8, 80, AgentEventKind.TOOL_FINISHED, """{"result":{"executionStatus":"NOT_EXECUTED","diagnosticCode":"EXPERIENCE_EVIDENCE_REQUIRED"}}""").copy(callId = "save-1"),
            event(9, 90, AgentEventKind.TOOL_CALL, """{"name":"done","argumentsJson":"{\"experience_updates\":[{},{}]}"}""")
        ))
        assertEquals(2, metrics.memoryContextLoads)
        assertEquals(listOf("experience:schedule-meeting:2", "experience:share-meeting:1"), metrics.experienceContextRefs)
        assertEquals(1, metrics.experienceSearchCalls)
        assertEquals(mapOf("save:NOT_EXECUTED" to 1, "use:SUCCEEDED" to 1), metrics.experienceToolOutcomes)
        assertEquals(2, metrics.experienceCompletionProposals)
        assertNull(metrics.semanticSuccess)
    }

    private fun event(sequence: Long, time: Long, kind: AgentEventKind, payload: String) = AgentEventEnvelope(
        1, "event:$sequence", kind, sequence, time, "thread", "turn", null, "attempt", null, null,
        "hash", payload, "key:$sequence"
    )

    @Test fun duplicatePauseAndCancellationCountWaitOnce() {
        val events = listOf(
            event(1, 100, AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED, """{"state":"PAUSED_USER"}"""),
            event(2, 150, AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED, """{"state":"PAUSED_USER"}"""),
            event(3, 400, AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED, """{"state":"CANCELED"}"""),
            event(4, 500, AgentEventKind.TURN_COMPLETED, """{"totalDurationMillis":500}""")
        )
        val metrics = TaskAnalysisMetrics.project(events)
        assertEquals(300L, metrics.humanWaitDurationMs)
        assertEquals(200L, metrics.activeDurationMs)
        assertFalse(metrics.unclosedHumanWait)
        assertNull(metrics.semanticSuccess)
    }

    @Test fun terminalClosesWaitAndPendingWaitIsMarkedIncomplete() {
        val pause = event(1, 100, AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED, """{"state":"PAUSED_USER"}""")
        assertTrue(TaskAnalysisMetrics.project(listOf(pause)).unclosedHumanWait)
        val metrics = TaskAnalysisMetrics.project(listOf(pause,
            event(2, 400, AgentEventKind.TURN_COMPLETED, """{"totalDurationMillis":400}""")))
        assertEquals(300L, metrics.humanWaitDurationMs)
        assertEquals(100L, metrics.activeDurationMs)
    }
}
