// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.event.*
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event and protocol coverage for the execution-plan guard.
 *
 * The fixtures below are real serialized AgentEvent payloads.  They exercise the same durable
 * call/settlement facts that the coordinator consumes, rather than replacing the event stream
 * with a mutable test counter or declaring a business result by fiat.
 */
class TaskExecutionProgressTest {
    private val scope = TurnEventScope("execution-thread", "execution-turn")
    private val policy = TaskExecutionPolicy(
        reviewAfterCalls = 6,
        maxObservationRecoveries = 24,
        maxCallsPerTask = 120,
        maxRejectedCalls = 3
    )

    @Test fun aMemoNeedsOnlyItsStateAndRequirements() {
        val parsed = TaskExecutionPlan.parse("""{"state":"Searching","requirements":["r1: list five items"]}""")
        val plan = checkNotNull(parsed.plan) { parsed.issues.toString() }
        assertEquals(TaskExecutionPlan.DEFAULT_RECORD_PATH, plan.record_path)
        assertTrue(plan.steps.isEmpty() && plan.requested_deliverables.isEmpty() && plan.protected_values.isEmpty())
    }

    @Test fun pendingStepsNeedNoInventedSourcesButCompletedStepsStillDo() {
        val input = """{"record_path":"work.md","state":"Starting","requirements":["Read items"],
            "steps":[{"id":"s1","objective":"Read","acceptance":"Sources collected","status":"active"}]}"""
        val parsed = TaskExecutionPlan.parse(input)
        assertTrue(parsed.issues.isEmpty())
        assertTrue(checkNotNull(parsed.plan).steps.single().evidence_refs.isEmpty())
        val completed = TaskExecutionPlan.parse(input.replace("\"active\"", "\"complete\""))
        assertTrue(completed.issues.any { it.reason == "completed_step_requires_sources" })
        val fields = com.droidance.core.agent.tools.TaskMemoTool.definition("").parameters.getValue("steps")
            .items!!.properties!!
        assertNull(com.droidance.core.agent.harness.ToolArgumentSchemaValidator.mismatchDetail(fields,
            """{"id":"s1","objective":"Read","acceptance":"Sources","status":"active"}"""))
        assertTrue(com.droidance.core.agent.harness.ToolArgumentSchemaValidator.mismatchDetail(fields,
            """{"id":"s1","objective":"Read","acceptance":"Sources","status":"complete"}""") != null)
    }

    @Test
    fun settledMemoThenActionMakesThePlanAvailableWithinTheSameResponse() {
        val plan = plan()
        val events = memo(1, plan) + device(3, "action-1")

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(plan, progress.plan)
        assertEquals(mapOf("inspect" to 1), progress.attempts)
        assertEquals(1, progress.totalCalls)
        assertNull(
            progress.check(
                tool = "screen_act",
                arguments = "{\"actions\":[{\"op\":\"click\"}]}",
                deviceMutation = true,
                policy = policy,
                instruction = "inspect and continue",
                sourceExists = { false }
            )
        )
    }

    @Test
    fun rejectedMutationsAreDurableFactsAndDoNotConsumeAttemptBudget() {
        val plan = plan()
        val events = memo(1, plan) + rejected(3, "blocked-1", "TASK_EXECUTION_BUDGET_EXHAUSTED")

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(0, progress.totalCalls)
        assertEquals(0, progress.attempts["inspect"] ?: 0)
        assertEquals(1, progress.rejectedCalls)
        assertNull(
            "A rejected mutation does not consume the attempt interval; coordinator rejection budget remains separate.",
            progress.check(
                "screen_act", "{\"actions\":[{\"op\":\"click\"}]}", true, policy,
                "record a method review", { false }
            )
        )
    }

    @Test
    fun normalCheckpointsAndObservationRecoveryDoNotConsumeProtocolFailureBudget() {
        val events = memo(1, plan()) +
            rejected(3, "task-rejected", "TASK_EXECUTION_BUDGET_EXHAUSTED") +
            rejected(5, "observation-rejected", "OBSERVATION_RECOVERY_REQUIRED")

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(
            "Checkpoints and fresh observations are continuation requirements, not protocol failures.",
            0,
            progress.repeatedProtocolRejections
        )
        assertEquals(
            "Plan checkpoint facts remain available independently of protocol failures.",
            1,
            progress.rejectedCalls
        )
    }

    @Test
    fun passiveObservationAfterRejectionsDoesNotResetRecoveryCountersOrTheActionBudget() {
        val events = memo(1, plan()) +
            rejected(3, "observation-rejected", "STALE_OBSERVATION") +
            rejected(5, "protocol-rejected", "ARGUMENT_SCHEMA_MISMATCH") +
            observationOnly(7, "wait-frame")

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(1, progress.consecutiveObservationRecoveries)
        assertEquals(1, progress.repeatedProtocolRejections)
        assertEquals(1, progress.totalCalls)
        assertEquals(1, progress.attempts["inspect"])
        assertEquals(
            "TASK_EXECUTION_BUDGET_EXHAUSTED",
            progress.mutationBlockCode(policy.copy(maxCallsPerTask = 1))
        )

        val afterMutation = TaskExecutionProgress.project(events + device(9, "real-action"), scope)
        assertEquals(
            "A successful mutation closes the current incident without erasing its durable receipts.",
            0,
            afterMutation.consecutiveObservationRecoveries
        )
        assertEquals(0, afterMutation.repeatedProtocolRejections)
        assertEquals(2, afterMutation.totalCalls)
        assertEquals(2, afterMutation.attempts["inspect"])

        val afterAnotherRecovery = TaskExecutionProgress.project(
            events + device(9, "real-action") + rejected(11, "second-observation-rejected", "STALE_OBSERVATION"),
            scope
        )
        assertEquals(1, afterAnotherRecovery.consecutiveObservationRecoveries)
        assertEquals(2, afterAnotherRecovery.totalCalls)
        assertEquals(2, events.count { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED })
    }

    @Test
    fun separateClosedObservationIncidentsDoNotExhaustTheCurrentIncidentBudget() {
        val events = (0 until policy.maxObservationRecoveries).flatMap { index ->
            val sequence = 1L + index * 4
            rejected(sequence, "stale-$index", "STALE_OBSERVATION") +
                device(sequence + 2, "recovered-$index")
        } + rejected(1L + policy.maxObservationRecoveries * 4, "current-stale", "STALE_OBSERVATION")

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(1, progress.consecutiveObservationRecoveries)
        assertEquals(policy.maxObservationRecoveries, progress.totalCalls)
        assertEquals(policy.maxObservationRecoveries + 1,
            events.count { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED })
        assertEquals("TASK_EXECUTION_BUDGET_EXHAUSTED",
            progress.mutationBlockCode(policy.copy(maxCallsPerTask = policy.maxObservationRecoveries)))
    }

    @Test
    fun readsMemoChangesAndUserSteerDoNotCloseAnUnresolvedObservationIncident() {
        val events = memo(1, plan()) + rejected(3, "stale-action", "STALE_OBSERVATION") +
            listOf(
                call(5, "new-frame", "android_read", "{\"op\":\"screen\"}",
                    ToolBinding.DEVICE_BOUND, ToolEffect.READ_ONLY),
                finished(6, "new-frame", ToolExecutionResult(
                    "fresh pixels", ToolExecutionStatus.SUCCEEDED, ToolEvidenceStatus.CAPTURED,
                    observationId = "new-observation"
                )),
                workspaceWrite(7),
                userSteer(8)
            ) + memo(9, plan().copy(state = "another method")) + observationOnly(11, "wait-frame")

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(1, progress.consecutiveObservationRecoveries)
        assertEquals(1, progress.totalCalls)
    }

    @Test
    fun failedOrInterruptedMutationsDoNotCloseAnObservationIncident() {
        val unresolved = rejected(1, "stale-action", "STALE_OBSERVATION")
        val receipts = listOf(
            ToolExecutionResult("failed", ToolExecutionStatus.FAILED, ToolEvidenceStatus.CAPTURED,
                diagnosticCode = "DEVICE_EXECUTION_FAILED"),
            ToolExecutionResult("interrupted", ToolExecutionStatus.INTERRUPTED, ToolEvidenceStatus.UNKNOWN,
                diagnosticCode = "DEVICE_EXECUTION_INTERRUPTED"),
            ToolExecutionResult("canceled", ToolExecutionStatus.CANCELED, ToolEvidenceStatus.NOT_REQUIRED,
                diagnosticCode = "DEVICE_EXECUTION_CANCELED")
        )
        receipts.forEachIndexed { index, receipt ->
            val callId = "unresolved-$index"
            val events = unresolved + listOf(
                call(3, callId, "screen_act", "{\"actions\":[{\"op\":\"click\"}]}",
                    ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING),
                finished(4, callId, receipt)
            )
            val progress = TaskExecutionProgress.project(events, scope)
            assertEquals(receipt.toString(), 1, progress.consecutiveObservationRecoveries)
            assertEquals(1, progress.totalCalls)
        }
    }

    @Test
    fun acceptedDeviceMutationClosesTheIncidentWithoutProvingGoalProgress() {
        val receipt = ToolProtocolResult.accepted("action delivered")
        val events = rejected(1, "stale-action", "STALE_OBSERVATION") + listOf(
            call(3, "delivered", "screen_act", "{\"actions\":[{\"op\":\"click\"}]}",
                ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING),
            finished(4, "delivered", receipt)
        )

        val progress = TaskExecutionProgress.project(events, scope)

        assertFalse(receipt.isVerifiedSuccess)
        assertEquals(ToolEvidenceStatus.UNKNOWN, receipt.evidenceStatus)
        assertEquals(GoalProgressEvidence.NOT_PROVEN, receipt.actionEvidence?.goalProgress)
        assertEquals(0, progress.consecutiveObservationRecoveries)
        assertEquals(1, progress.totalCalls)
    }

    @Test
    fun successfulDeliveryWithMissingPostActionObservationRemainsInTheCurrentIncident() {
        val events = rejected(1, "stale-action", "STALE_OBSERVATION") + listOf(
            call(3, "delivered-without-frame", "screen_act", "{\"actions\":[{\"op\":\"click\"}]}",
                ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING),
            finished(4, "delivered-without-frame", ToolExecutionResult(
                "input delivered; final frame unavailable", ToolExecutionStatus.SUCCEEDED,
                ToolEvidenceStatus.UNKNOWN, diagnosticCode = "POST_ACTION_OBSERVATION_UNAVAILABLE",
                actionEvidence = ToolActionEvidence(ActionDeliveryEvidence.ACTION_DELIVERED,
                    StateTransitionEvidence.UNKNOWN, GoalProgressEvidence.NOT_PROVEN)
            ))
        )

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(2, progress.consecutiveObservationRecoveries)
        assertEquals(1, progress.totalCalls)
        assertEquals("TASK_EXECUTION_BUDGET_EXHAUSTED",
            progress.mutationBlockCode(policy.copy(maxCallsPerTask = 1)))
    }

    @Test
    fun acceptedReviewOpensOnlyTheNextIntervalAndRetainsEarlierAttemptsAndReview() {
        val initial = plan()
        val reviewed = initial.copy(
            state = "reviewed mismatch",
            review = TaskMethodReview(
                outcome = "replan",
                finding = "The first method left the selected control unchanged.",
                next_method = "Inspect the revealed control region before using a different action.",
                evidence_refs = listOf("obs-review")
            )
        )
        val events = memo(1, initial) + (0 until 6).flatMap { device(3L + it * 2, "action-$it") } +
            memo(20, reviewed) + device(22, "action-after-review")

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(7, progress.attempts["inspect"])
        assertEquals(7, progress.totalCalls)
        assertEquals(mapOf("inspect" to 6), progress.reviewedAt)
        assertEquals(1, progress.failedMethods.size)
        assertTrue(progress.failedMethods.single().toString().contains("obs-review"))
        assertNull(
            progress.check(
                "screen_act", "{\"actions\":[{\"op\":\"click\"}]}", true, policy,
                "inspect before retrying", { it == "obs-review" }
            )
        )
    }

    @Test
    fun userSteerStartsANewPlanEpochButDoesNotErasePastAttempts() {
        val first = plan()
        val second = first.copy(
            record_path = "execution/new-record.md",
            state = "updated request",
            steps = listOf(first.steps.single().copy(id = "updated"))
        )
        val events = memo(1, first) + device(3, "before-steer") + userSteer(5) +
            memo(6, second) + device(8, "after-steer")

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(second, progress.plan)
        assertEquals(1, progress.attempts["inspect"])
        assertEquals(1, progress.attempts["updated"])
        assertEquals(2, progress.totalCalls)
        assertTrue(progress.reviewedAt.isEmpty())
    }

    @Test
    fun changingRecordPathIsRejectedAsPlanIdentityChanged() {
        val current = plan()
        val changed = current.copy(record_path = "different/record.md")
        val progress = TaskExecutionProgress(current, mapOf("inspect" to 2), emptyMap(), 2, 0, emptyList())

        val result = progress.check(
            "task_memo", Json.encodeToString(changed), false, policy,
            "retain the stable record path", { false }
        )

        assertEquals("TASK_PLAN_IDENTITY_CHANGED", result?.diagnosticCode)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, result?.executionStatus)
    }

    @Test
    fun reorderingOrRemovingPendingStepsIsPermitted() {
        val current = plan().copy(steps = listOf(
            plan().steps[0],
            TaskStep("verify", "Verify the result", "The source confirms the result.", "pending", emptyList())
        ))
        val deleted = current.copy(steps = listOf(current.steps[0]))
        val progress = TaskExecutionProgress(current, emptyMap(), emptyMap(), 0, 0, emptyList())

        val result = progress.check("task_memo", Json.encodeToString(deleted), false, policy,
            "adjust steps", { false })
        assertNull(result)
    }

    @Test
    fun activeStepTransitioningToSupersededIsPermitted() {
        val current = plan().copy(steps = listOf(
            plan().steps[0],
            TaskStep("verify", "Verify the result", "The source confirms the result.", "pending", emptyList())
        ))
        val next = current.copy(steps = listOf(
            current.steps[0].copy(status = "superseded"),
            current.steps[1].copy(status = "active")
        ))
        val progress = TaskExecutionProgress(current, emptyMap(), emptyMap(), 0, 0, emptyList())
        val result = progress.check("task_memo", Json.encodeToString(next), false, policy,
            "supersede old step and activate next", { false })
        assertNull(result)
    }

    @Test
    fun completionCanPassWhenObsoleteStepsAreMarkedSuperseded() {
        val current = plan().copy(steps = listOf(
            TaskStep("inspect", "Inspect state", "Acceptance", "complete", listOf("obs-1")),
            TaskStep("extra_query", "Extra query", "Acceptance", "superseded", emptyList())
        ))
        val progress = TaskExecutionProgress(current, emptyMap(), emptyMap(), 0, 0, emptyList())
        val result = progress.check(
            "done", "{\"status\":\"done\"}", false, policy,
            "complete with superseded step", { it == "obs-1" }
        )
        assertNull(result)
    }

    @Test
    fun changingStepObjectiveOrAcceptanceDoesNotResetTheTaskBudget() {
        val current = plan()
        val revised = current.copy(steps = listOf(current.steps.single().copy(
            objective = "Inspect the newly observed source",
            acceptance = "The newly observed source contains the requested state."
        )))
        val progress = TaskExecutionProgress(
            plan = current,
            attempts = mapOf("inspect" to 24),
            reviewedAt = emptyMap(),
            totalCalls = policy.maxCallsPerTask,
            rejectedCalls = 0,
            failedMethods = emptyList()
        )

        assertNull(
            progress.check("task_memo", Json.encodeToString(revised), false, policy,
                "preserve stable step identity", { false })
        )
        assertEquals(
            "TASK_EXECUTION_BUDGET_EXHAUSTED",
            progress.check("screen_act", "{\"actions\":[{\"op\":\"click\"}]}", true, policy,
                "report the exhausted scope", { false })?.diagnosticCode
        )
    }

    @Test
    fun activeStepCannotChangeUntilThePreviousStepIsCompleteOrBlocked() {
        val current = plan().copy(
            steps = listOf(
                plan().steps.single(),
                TaskStep("verify", "Verify the result", "The source confirms the result.", "pending", emptyList())
            )
        )
        val next = current.copy(steps = listOf(
            current.steps[0].copy(status = "pending"),
            current.steps[1].copy(status = "active")
        ))
        val progress = TaskExecutionProgress(current, emptyMap(), emptyMap(), 0, 0, emptyList())

        assertEquals(
            "TASK_STEP_TRANSITION_REQUIRED",
            progress.check("task_memo", Json.encodeToString(next), false, policy,
                "complete the current step first", { false })?.diagnosticCode
        )

        val completed = next.copy(steps = listOf(
            current.steps[0].copy(status = "complete", evidence_refs = listOf("source-a")),
            current.steps[1].copy(status = "active")
        ))
        assertNull(
            progress.check("task_memo", Json.encodeToString(completed), false, policy,
                "continue with the next step", { it == "source-a" })
        )
    }

    @Test
    fun reviewCanCompleteTheOldStepAndActivateTheNextStepInOneMemo() {
        val first = plan().steps.single()
        val initial = plan().copy(steps = listOf(
            first,
            TaskStep("verify", "Verify the result", "The source confirms the result.", "pending", emptyList())
        ))
        val reviewed = initial.copy(
            state = "the first source is confirmed",
            steps = listOf(
                first.copy(status = "complete", evidence_refs = listOf("source-a")),
                initial.steps[1].copy(status = "active")
            ),
            review = TaskMethodReview(
                outcome = "progress",
                finding = "The first source confirms the requested state.",
                next_method = "Inspect the verification source.",
                evidence_refs = listOf("obs-review")
            )
        )
        val beforeReviewEvents = memo(1, initial) +
            (0 until 6).flatMap { device(3L + it * 2, "inspect-$it") }
        val beforeReview = TaskExecutionProgress.project(beforeReviewEvents, scope)

        assertNull(
            beforeReview.check(
                "task_memo", Json.encodeToString(reviewed), false, policy,
                "retain the source-backed review", { raw ->
                    raw.split(',').map(String::trim).all { it == "source-a" || it == "obs-review" }
                }
            )
        )

        val progress = TaskExecutionProgress.project(beforeReviewEvents + memo(30, reviewed), scope)

        assertEquals(reviewed, progress.plan)
        assertEquals(6, progress.attempts["inspect"])
        assertEquals(0, progress.attempts["verify"] ?: 0)
        assertEquals(6, progress.totalCalls)
        assertEquals(mapOf("inspect" to 6), progress.reviewedAt)
        val retained = progress.failedMethods.single()
        assertEquals("inspect", retained["step_id"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("obs-review"),
            retained["review"]?.jsonObject?.get("evidence_refs")?.jsonArray
                ?.map { it.jsonPrimitive.content }
        )
        assertNull(
            progress.check(
                "screen_act", "{\"actions\":[{\"op\":\"click\"}]}", true, policy,
                "continue with the activated step", { false }
            )
        )
    }

    @Test
    fun reviewCannotActivateTheNextStepWhileTheOldStepIsStillUnresolved() {
        val first = plan().steps.single()
        val current = plan().copy(steps = listOf(
            first,
            TaskStep("verify", "Verify the result", "The source confirms the result.", "pending", emptyList())
        ))
        val invalid = current.copy(
            steps = listOf(
                first.copy(status = "pending"),
                current.steps[1].copy(status = "active")
            ),
            review = TaskMethodReview(
                outcome = "replan",
                finding = "The first method did not establish the criterion.",
                next_method = "Inspect a different source.",
                evidence_refs = listOf("obs-review")
            )
        )
        val progress = TaskExecutionProgress(current, emptyMap(), emptyMap(), 0, 0, emptyList())

        assertEquals(
            "TASK_STEP_TRANSITION_REQUIRED",
            progress.check(
                "task_memo", Json.encodeToString(invalid), false, policy,
                "complete or block the old step", { true }
            )?.diagnosticCode
        )
    }

    @Test
    fun reviewedTransitionStillRequiresOriginalEvidenceForTheCompletedStep() {
        val first = plan().steps.single()
        val current = plan().copy(steps = listOf(
            first,
            TaskStep("verify", "Verify the result", "The source confirms the result.", "pending", emptyList())
        ))
        val incompleteEvidence = current.copy(
            steps = listOf(
                first.copy(status = "complete", evidence_refs = listOf("missing-source")),
                current.steps[1].copy(status = "active")
            ),
            review = TaskMethodReview(
                outcome = "progress",
                finding = "The source was inspected but does not yet support completion.",
                next_method = "Inspect the verification source.",
                evidence_refs = listOf("obs-review")
            )
        )
        val progress = TaskExecutionProgress(current, emptyMap(), emptyMap(), 0, 0, emptyList())

        assertEquals(
            "TASK_STEP_EVIDENCE_REQUIRED",
            progress.check(
                "task_memo", Json.encodeToString(incompleteEvidence), false, policy,
                "cite the original source", { it == "obs-review" }
            )?.diagnosticCode
        )
    }

    @Test
    fun reactivatingStepsPreservesCountersWithoutCreatingAStepBudgetGate() {
        val first = plan().steps.single()
        val initial = plan().copy(steps = listOf(
            first,
            TaskStep("verify", "Verify the result", "The source confirms the result.", "pending", emptyList())
        ))
        val firstTransition = initial.copy(steps = listOf(
            first.copy(status = "complete", evidence_refs = listOf("source-a")),
            initial.steps[1].copy(status = "active")
        ))
        val blockSecond = initial.copy(steps = listOf(
            first.copy(status = "active"),
            initial.steps[1].copy(status = "blocked")
        ))
        val finalTransition = initial.copy(
            state = "verification is still required",
            steps = listOf(
                first.copy(status = "complete", evidence_refs = listOf("source-b")),
                initial.steps[1].copy(status = "active")
            ),
            review = TaskMethodReview(
                outcome = "replan",
                finding = "The first method completed its source-backed step.",
                next_method = "Use the verification source for the remaining step.",
                evidence_refs = listOf("obs-review")
            )
        )
        val initialEvents = memo(1, initial) + memo(10, firstTransition) +
            (0 until 24).flatMap { device(20L + it * 2, "verify-$it") }
        val afterBlockedTransition = initialEvents + memo(80, blockSecond) + device(90, "inspect-after-block")
        val beforeFinalTransition = TaskExecutionProgress.project(afterBlockedTransition, scope)

        assertEquals(24, beforeFinalTransition.attempts["verify"])
        assertNull(
            beforeFinalTransition.check(
                "task_memo", Json.encodeToString(finalTransition), false, policy,
                "retain the existing verification budget", { raw ->
                    raw.split(',').map(String::trim).all { it == "source-b" || it == "obs-review" }
                }
            )
        )

        val progress = TaskExecutionProgress.project(afterBlockedTransition + memo(100, finalTransition), scope)

        assertEquals("verify", progress.plan?.active?.id)
        assertEquals(24, progress.attempts["verify"])
        assertEquals(24 + 1, progress.totalCalls)
        assertNull(progress.check(
            "screen_act", "{\"actions\":[{\"op\":\"click\"}]}", true, policy,
            "the turn budget remains available", { false }
        ))
    }

    @Test
    fun reviewIsRejectedWhenThereIsNoCurrentActiveStep() {
        val completed = plan().copy(steps = listOf(
            plan().steps.single().copy(status = "complete", evidence_refs = listOf("source-a"))
        ))
        val reviewed = completed.copy(review = TaskMethodReview(
            outcome = "progress",
            finding = "The source was inspected.",
            next_method = "No further method is available.",
            evidence_refs = listOf("obs-review")
        ))
        val progress = TaskExecutionProgress(completed, emptyMap(), emptyMap(), 0, 0, emptyList())

        assertEquals(
            "TASK_METHOD_REVIEW_INVALID",
            progress.check(
                "task_memo", Json.encodeToString(reviewed), false, policy,
                "review requires an active step", { true }
            )?.diagnosticCode
        )
    }

    @Test
    fun repeatingAReviewedMethodIsRejectedFromDurableReviewHistory() {
        val current = plan()
        val review = current.copy(review = TaskMethodReview(
            outcome = "replan",
            finding = "The control did not change the source.",
            next_method = "Inspect the actual control region first.",
            evidence_refs = listOf("obs-review")
        ))
        val existingReview = buildJsonObject {
            put("step_id", "inspect")
            put("review", Json.encodeToJsonElement(requireNotNull(review.review)))
            put("event_ref", "finish-memo-review")
        }
        val progress = TaskExecutionProgress(
            plan = current,
            attempts = mapOf("inspect" to 6),
            reviewedAt = emptyMap(),
            totalCalls = 6,
            rejectedCalls = 0,
            failedMethods = listOf(existingReview)
        )

        assertEquals(
            "TASK_METHOD_REVIEW_INVALID",
            progress.check("task_memo", Json.encodeToString(review), false, policy,
                "choose a new method", { it == "obs-review" })?.diagnosticCode
        )
    }

    @Test
    fun progressReviewMayKeepTheMethodWhenItCitesNewSourceEvidence() {
        val current = plan()
        val previous = TaskMethodReview(
            outcome = "progress",
            finding = "The first source shows partial progress.",
            next_method = "Inspect the same source after the state update.",
            evidence_refs = listOf("obs-old")
        )
        val next = current.copy(review = previous.copy(
            finding = "The new source shows the requested control is now visible.",
            evidence_refs = listOf("obs-new")
        ))
        val retained = buildJsonObject {
            put("step_id", "inspect")
            put("review", Json.encodeToJsonElement(previous))
            put("event_ref", "finish-old-review")
        }
        val progress = TaskExecutionProgress(
            plan = current,
            attempts = mapOf("inspect" to 6),
            reviewedAt = emptyMap(),
            totalCalls = 6,
            rejectedCalls = 0,
            failedMethods = listOf(retained)
        )

        assertNull(
            progress.check("task_memo", Json.encodeToString(next), false, policy,
                "retain the progressing method", { it == "obs-new" })
        )
    }

    @Test
    fun progressReviewRepeatingItsPreviousEvidenceIsRejected() {
        val current = plan()
        val previous = TaskMethodReview(
            outcome = "progress",
            finding = "The source still shows partial progress.",
            next_method = "Inspect the same source after the state update.",
            evidence_refs = listOf("obs-old")
        )
        val retained = buildJsonObject {
            put("step_id", "inspect")
            put("review", Json.encodeToJsonElement(previous))
            put("event_ref", "finish-old-review")
        }
        val progress = TaskExecutionProgress(
            plan = current,
            attempts = mapOf("inspect" to 6),
            reviewedAt = emptyMap(),
            totalCalls = 6,
            rejectedCalls = 0,
            failedMethods = listOf(retained)
        )

        assertEquals(
            "TASK_METHOD_REVIEW_INVALID",
            progress.check("task_memo", Json.encodeToString(current.copy(review = previous)), false, policy,
                "cite a new source observation", { it == "obs-old" })?.diagnosticCode
        )
    }

    @Test
    fun aMemoOrEmptyWorkingStateCannotClearTheTaskBudget() {
        val current = plan()
        val progress = TaskExecutionProgress(
            plan = current,
            attempts = mapOf("inspect" to 24),
            reviewedAt = emptyMap(),
            totalCalls = policy.maxCallsPerTask,
            rejectedCalls = 0,
            failedMethods = emptyList()
        )

        // Re-submitting the same plan is a state update, not a budget reset.  The next mutation
        // is still rejected from the durable counters projected above.
        assertNull(
            progress.check(
                "task_memo", Json.encodeToString(current.copy(state = "empty working note")), false, policy, "preserve counters", { false }
            )
        )
        assertEquals(
            "TASK_EXECUTION_BUDGET_EXHAUSTED",
            progress.check(
                "screen_act", "{\"actions\":[{\"op\":\"click\"}]}", true, policy,
                "report the exhausted scope", { false }
            )?.diagnosticCode
        )
    }

    @Test
    fun samePlanWorkingUpdateAndEmptyWorkspaceWriteDoNotResetRejectedCalls() {
        val current = plan()
        val events = memo(1, current) + rejected(3, "blocked-1", "TASK_EXECUTION_BUDGET_EXHAUSTED") +
            rejected(5, "blocked-2", "TASK_EXECUTION_BUDGET_EXHAUSTED") +
            listOf(workspaceWrite(7)) + memo(9, current.copy(state = "empty update"))

        val progress = TaskExecutionProgress.project(events, scope)

        assertEquals(2, progress.rejectedCalls)
        assertEquals(0, progress.totalCalls)
        assertEquals(0, progress.attempts.values.sum())
    }

    @Test
    fun workspaceOrModelNotesAreNotCompletionEvidence() {
        val complete = plan().copy(steps = listOf(plan().steps.single().copy(
            status = "complete", evidence_refs = listOf("execution/record.md", "model-note:1")
        )))
        val progress = TaskExecutionProgress(plan(), emptyMap(), emptyMap(), 0, 0, emptyList())

        val result = progress.check(
            "task_memo", Json.encodeToString(complete), false, policy,
            "use original source evidence", { it == "source-a" }
        )

        assertEquals("TASK_STEP_EVIDENCE_REQUIRED", result?.diagnosticCode)
    }

    @Test
    fun completeStepRequiresEveryOriginalEvidenceReferenceFromTheSourceLedger() {
        val complete = plan().copy(
            steps = listOf(plan().steps.single().copy(
                status = "complete",
                evidence_refs = listOf("source-a", "source-b")
            ))
        )
        val progress = TaskExecutionProgress(plan(), emptyMap(), emptyMap(), 0, 0, emptyList())

        val result = progress.check(
            "task_memo", Json.encodeToString(complete), false, policy,
            "retain source evidence", { raw ->
                raw.split(',').map(String::trim).all { it == "source-a" || it == "source-b" }
            }
        )

        assertNull(
            "Each original reference is checked independently; working notes are not evidence.",
            result
        )
    }

    @Test
    fun pendingMethodStepsDoNotGateCompletion() {
        val progress = TaskExecutionProgress(plan(), emptyMap(), emptyMap(), 0, 0, emptyList())
        val result = progress.check(
            "done", "{\"status\":\"done\"}", false, policy,
            "complete the remaining step", { false }
        )

        assertNull(result)
    }

    private fun plan(): TaskExecutionPlan = TaskExecutionPlan(
        record_path = "execution/record.md",
        state = "working",
        requirements = listOf("inspect the requested state"),
        steps = listOf(
            TaskStep(
                id = "inspect",
                objective = "Inspect the requested state",
                acceptance = "The current source contains the requested state.",
                status = "active",
                evidence_refs = emptyList()
            )
        )
    )

    private fun memo(sequence: Long, plan: TaskExecutionPlan): List<AgentEventEnvelope> {
        val callId = "memo-$sequence"
        return listOf(
            call(sequence, callId, "task_memo", Json.encodeToString(plan), ToolBinding.TURN_LOCAL,
                ToolEffect.STATE_CHANGING),
            finished(sequence + 1, callId, ToolExecutionResult(
                outputText = plan.state,
                executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
            ))
        )
    }

    private fun device(sequence: Long, callId: String): List<AgentEventEnvelope> = listOf(
        call(sequence, callId, "screen_act", "{\"actions\":[{\"op\":\"click\"}]}", ToolBinding.DEVICE_BOUND,
            ToolEffect.STATE_CHANGING),
        finished(sequence + 1, callId, ToolExecutionResult(
            outputText = "action settled",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
        ))
    )

    private fun observationOnly(sequence: Long, callId: String): List<AgentEventEnvelope> = listOf(
        call(sequence, callId, "screen_act",
            """{"actions":[{"op":"wait","ms":50},{"op":"screenshot"}]}""",
            ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING),
        finished(sequence + 1, callId, ToolExecutionResult(
            outputText = "observation captured",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.CAPTURED,
            observationId = "obs-$callId"
        ))
    )

    private fun rejected(sequence: Long, callId: String, code: String): List<AgentEventEnvelope> = listOf(
        call(sequence, callId, "screen_act", "{\"actions\":[{\"op\":\"click\"}]}", ToolBinding.DEVICE_BOUND,
            ToolEffect.STATE_CHANGING),
        event(sequence + 1, AgentEventKind.TOOL_CALL_REJECTED, callId,
            Json.encodeToString(ToolCallRejectedEventPayload(
                contractVersion = 1,
                result = ToolProtocolResult.notExecuted(code, retryable = true),
                candidateCapabilitySnapshotRef = null,
                candidateCapabilitySnapshotHash = null,
                roundDetailRef = "round-$callId"
            )),
            "rejected-$callId"
        )
    )

    private fun userSteer(sequence: Long): AgentEventEnvelope = event(
        sequence,
        AgentEventKind.USER_STEER,
        null,
        Json.encodeToString(UserTextEventPayload(1, "steer-$sequence", "update the request")),
        "steer-$sequence"
    )

    private fun call(
        sequence: Long,
        callId: String,
        name: String,
        arguments: String,
        binding: ToolBinding,
        effect: ToolEffect
    ): AgentEventEnvelope = event(
        sequence,
        AgentEventKind.TOOL_CALL,
        callId,
        Json.encodeToString(ToolCallEventPayload(
            contractVersion = 1,
            providerOrder = 0,
            providerCallId = callId,
            name = name,
            argumentsJson = arguments,
            source = ToolCallSource.MODEL_REQUESTED,
            binding = binding,
            effect = effect,
            contractHash = "a".repeat(64),
            presentation = ToolPresentationSnapshot(
                labelResourceKey = if (name == "task_memo") "tool.task_memo" else "tool.screen_act",
                category = ToolPresentationCategory.SUPPORTING,
                resultImageRole = ResultImageRole.NONE
            ),
            roundDetailRef = "round-$callId"
        )),
        "call-$callId"
    )

    private fun finished(sequence: Long, callId: String, result: ToolExecutionResult): AgentEventEnvelope = event(
        sequence,
        AgentEventKind.TOOL_FINISHED,
        callId,
        Json.encodeToString(ToolFinishedEventPayload(1, result, "round-$callId")),
        "finish-$callId"
    )

    private fun workspaceWrite(sequence: Long): AgentEventEnvelope = event(
        sequence,
        AgentEventKind.WORKSPACE_FILE_WRITTEN,
        null,
        Json.encodeToString(WorkspaceFileVersion(
            path = "execution/record.md",
            contentRef = "sha256:${"b".repeat(64)}",
            sizeBytes = 0,
            chars = 0,
            requestHash = "empty-update"
        )),
        "workspace-$sequence"
    )

    private fun event(
        sequence: Long,
        kind: AgentEventKind,
        callId: String?,
        payload: String,
        key: String
    ): AgentEventEnvelope = AgentEventEnvelope(
        schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
        eventId = "event-$sequence-$key",
        eventKind = kind,
        sequence = sequence,
        occurredAt = sequence,
        threadId = scope.threadId,
        turnId = scope.turnId,
        roundId = if (kind in setOf(AgentEventKind.TOOL_CALL, AgentEventKind.TOOL_FINISHED,
                AgentEventKind.TOOL_CALL_REJECTED)) "round-$callId" else null,
        attemptId = null,
        executionId = null,
        callId = callId,
        payloadHash = "a".repeat(64),
        payloadJson = payload,
        idempotencyKey = key
    )
}
