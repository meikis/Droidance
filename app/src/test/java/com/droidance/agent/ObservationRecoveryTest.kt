// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import com.droidance.core.agent.event.ActionDeliveryEvidence
import com.droidance.core.agent.event.GoalProgressEvidence
import com.droidance.core.agent.event.StateTransitionEvidence
import com.droidance.core.agent.event.ToolActionEvidence
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import com.droidance.config.markdownSection
import com.droidance.core.agent.tools.ScreenActionBatchExecutor
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ObservationRecoveryTest {
    private val policy = Json.decodeFromString<ObservationRecoveryPolicy>(markdownSection(
        File("src/main/assets/rules/observation_recovery.md").readText(), "policy"
    )).also { it.validate() }
    private var observationSequence = 0
    private fun observe(guard: ObservationRecovery, session: String, fingerprint: Long?) {
        guard.observe(session, "full", "obs_${observationSequence++}", fingerprint, "same-frame")
    }
    private val up = """{"actions":[{"op":"scroll","direction":"up","amount":"large"}]}"""

    @Test fun v11UnavailableObservationAndExactRepeatBothRequireANewFrame() {
        for (diagnostic in listOf("PRE_ACTION_OBSERVATION_UNAVAILABLE", "PRE_ACTION_OBSERVATION_CHANGED",
            "EXACT_ACTION_REPEATED", "POST_ACTION_OBSERVATION_UNAVAILABLE",
            "OBSERVATION_RECOVERY_REQUIRED")) assertTrue(diagnostic in policy.refreshAfterDiagnostics)
        assertFalse("ACTION_DELIVERED" in policy.refreshAfterDiagnostics)
        val source = com.droidance.agent.coordinationSources()
        val invalidation = source.substringAfter("if (recoveryRejected || observationFreshnessPolicy")
            .substringBefore("completedToolCallCount += 1")
        for (operation in listOf("currentObservationLease = null", "terminalObservationRefVisibleToModel = null",
            "invalidateModelFrameBinding()", "invalidatePendingObservation()", "forceFreshObservationCapture = true")) {
            assertTrue("Recovery must discard every stale binding: $operation", invalidation.contains(operation))
        }
    }

    @Test fun swipeEndpointJitterDoesNotEvadeStationaryViewportBudget() {
        val guard = ObservationRecovery(policy)
        observe(guard, "display:1", 0L)
        listOf(210, 220, 230).forEach { end ->
            val args = """{"actions":[{"op":"swipe","startX":150,"startY":655,"endX":$end,"endY":655}]}"""
            assertFalse(guard.blocked("screen_act", args))
            guard.executed("screen_act", args, deliveredActionResult())
            observe(guard, "display:1", 0L)
        }
        val repeated = """{"actions":[{"op":"swipe","startX":155,"startY":656,"endX":225,"endY":655}]}"""
        assertTrue(guard.blocked("screen_act", repeated))
        observe(guard, "display:1", -1L)
        assertFalse(guard.blocked("screen_act", repeated))
    }

    @Test fun repeatedPullsWithIdenticalContentSurviveObservationIds() {
        val guard = ObservationRecovery(policy)
        observe(guard, "display:1", 0L)
        repeat(policy.attempts) { index ->
            guard.executed("screen_act", up, deliveredActionResult())
            observe(guard, "display:1", 1L shl index)
        }
        assertTrue(guard.blocked("screen_act", up))
        assertTrue(guard.blocked("screen_act", up.replace("large", "small")))
        assertFalse(guard.blocked("screen_act", up.replace("up", "down")))
    }

    @Test fun actualViewportChangeAllowsFurtherScrolling() {
        val guard = ObservationRecovery(policy)
        repeat(12) { index ->
            observe(guard, "display:1", 0xffffL shl (index * 4))
            assertFalse(guard.blocked("screen_act", up))
            guard.executed("screen_act", up, deliveredActionResult())
        }
    }

    @Test fun nearbyClicksAcrossCoordinateBucketsAreStillTheSameAttempt() {
        val guard = ObservationRecovery(policy)
        observe(guard, "display:1", 0L)
        listOf(880, 890, 900).forEach { x ->
            guard.executed("screen_act", """{"actions":[{"op":"click","x":$x,"y":140}]}""", deliveredActionResult())
            observe(guard, "display:1", 0L)
        }
        assertTrue(guard.blocked("screen_act", """{"actions":[{"op":"click","x":889,"y":141}]}"""))
        assertFalse(guard.blocked("screen_act", """{"actions":[{"op":"click","x":500,"y":500}]}"""))
    }

    @Test fun batchClicksDoNotEvadeStationaryViewportBudget() {
        val guard = ObservationRecovery(policy)
        observe(guard, "display:1", 0L)
        listOf(740, 750, 760).forEach { x ->
            val batchArgs = """{"actions":[{"op":"click","x":$x,"y":920}]}"""
            assertFalse(guard.blocked("screen_act", batchArgs))
            guard.executed("screen_act", batchArgs, successfulBatchReceipt(batchArgs))
            observe(guard, "display:1", 0L)
        }
        val repeatedBatch = """{"actions":[{"op":"click","x":750,"y":920}]}"""
        assertTrue(guard.blocked("screen_act", repeatedBatch))
        assertTrue(guard.blocked("screen_act", """{"actions":[{"op":"click","x":750,"y":920}]}"""))
    }

    @Test fun oneScrollBatchConsumesOneObservedStationaryAttemptInsteadOfEveryPrimitive() {
        val guard = ObservationRecovery(policy)
        val group = """{"actions":[{"op":"scroll","direction":"up"},{"op":"scroll","direction":"up"},{"op":"scroll","direction":"up"}]}"""
        observe(guard, "session", 0L)
        guard.executed("screen_act", group, successfulBatchReceipt(group))
        observe(guard, "session", 0L)
        assertFalse(guard.blocked("screen_act", up))
        assertEquals(1, guard.recoveryFacts("screen_act", up)
            .getValue("repeated_visual_attempts").jsonPrimitive.int)
        repeat(policy.attempts - 1) {
            guard.executed("screen_act", group, successfulBatchReceipt(group))
            observe(guard, "session", 0L)
        }
        assertTrue("Repeated observed stationary groups still exhaust their budget", guard.blocked("screen_act", up))
    }

    @Test fun repeatedSameMethodActionsInOneBatchEachCountAgainstTheObservedFrame() {
        val guard = ObservationRecovery(policy)
        val repeated = """{"actions":[{"op":"click","x":420,"y":360},{"op":"click","x":420,"y":360}]}"""
        val single = """{"actions":[{"op":"click","x":420,"y":360}]}"""
        observe(guard, "session", 0L)

        val pairReceipt = successfulBatchReceipt(repeated)
        assertEquals(2L, pairReceipt.metrics["batch_attempted_actions"])
        guard.executed("screen_act", repeated, pairReceipt)
        observe(guard, "session", 0L)
        assertFalse(guard.blocked("screen_act", """{"actions":[{"op":"click","x":420,"y":360}]}"""))

        val singleReceipt = successfulBatchReceipt(single)
        assertEquals(1L, singleReceipt.metrics["batch_attempted_actions"])
        guard.executed("screen_act", single, singleReceipt)
        observe(guard, "session", 0L)
        assertTrue(guard.blocked("screen_act", """{"actions":[{"op":"click","x":420,"y":360}]}"""))
        assertEquals(policy.attempts,
            guard.recoveryFacts("screen_act", """{"actions":[{"op":"click","x":420,"y":360}]}""")
                .getValue("repeated_visual_attempts").jsonPrimitive.int)
    }

    @Test fun batchCountsAttemptedPrefixAfterValidationAndLeavesUnexecutedSuffixUncounted() = runBlocking {
        val guard = ObservationRecovery(policy)
        val arguments = """{"actions":[{"op":"click","x":420,"y":360},{"op":"scroll","direction":"down","amount":"medium"},{"op":"click","x":780,"y":800}]}"""
        observe(guard, "session", 0L)

        repeat(policy.attempts) {
            val executorReceipt = runBlocking { executeBatchReceipt(arguments, failAtAction = 2) }
            assertEquals(2L, executorReceipt.metrics["batch_attempted_actions"])
            assertEquals(1L, executorReceipt.metrics["batch_completed_actions"])

            // This is the production post-action feedback shape: it changes outputText only.
            val resultWithValidation = executorReceipt.copy(
                outputText = executorReceipt.outputText + "\nstate=RAW_CHANGED"
            )
            guard.executed("screen_act", arguments, resultWithValidation)
            observe(guard, "session", 0L)
        }

        val attemptedSecondAction = """{"actions":[{"op":"scroll","direction":"down","amount":"medium"}]}"""
        val unexecutedSuffix = """{"actions":[{"op":"click","x":780,"y":800}]}"""
        assertTrue(guard.blocked("screen_act", attemptedSecondAction))
        assertFalse("The third action did not run and must not gain recovery history.",
            guard.blocked("screen_act", unexecutedSuffix))
        assertEquals(policy.attempts,
            guard.recoveryFacts("screen_act", attemptedSecondAction)
                .getValue("repeated_visual_attempts").jsonPrimitive.int)
    }

    @Test fun canonicalBatchTypeAndKeypressMatchTheirStandaloneFailureMethods() {
        val typeGuard = ObservationRecovery(policy)
        val typeBatch = """{"actions":[{"op":"type_text","text":"5000","x":100,"y":200,"clear":true}]}"""
        val typeAction = """{"actions":[{"op":"type_text","text":"5000","x":100,"y":200,"clear":true}]}"""
        observe(typeGuard, "session", 0L)
        repeat(policy.attempts) { typeGuard.settled("screen_act", typeBatch, failedBatchReceipt(typeBatch)) }
        assertTrue(typeGuard.blocked("screen_act", typeAction))

        val keyGuard = ObservationRecovery(policy)
        val keyBatch = """{"actions":[{"op":"press_key","key":"BACK"}]}"""
        val keyAction = """{"actions":[{"op":"press_key","key":"BACK"}]}"""
        observe(keyGuard, "session", 0L)
        repeat(policy.attempts) { keyGuard.settled("screen_act", keyBatch, failedBatchReceipt(keyBatch)) }
        assertTrue(keyGuard.blocked("screen_act", keyAction))
    }

    @Test fun waitsCannotEvadeRecoveryByChangingDurationOrAddingReads() {
        val guard = ObservationRecovery(policy)
        observe(guard, "display:1", 1L)
        repeat(policy.attempts) { index ->
            guard.executed("wait", """{"ms":${1000 + index}}""", observationSuccess())
            observe(guard, "display:1", 1L)
            guard.executed("android_read", """{"op":"screen"}""", observationSuccess())
            observe(guard, "display:1", 1L)
        }
        assertTrue(guard.blocked("wait", "{}"))
    }

    @Test fun unknownImagesDoNotEstablishStagnation() {
        val guard = ObservationRecovery(policy)
        observe(guard, "display:1", null)
        repeat(20) { guard.executed("screen_act", up, deliveredActionResult()); observe(guard, "display:1", null) }
        assertFalse(guard.blocked("screen_act", up))
    }

    @Test fun newDisplayAndManualResumeResetHistory() {
        val guard = ObservationRecovery(policy)
        observe(guard, "display:1", 0L)
        repeat(policy.attempts) { guard.executed("screen_act", up, deliveredActionResult()); observe(guard, "display:1", 0L) }
        observe(guard, "display:2", 0L)
        assertFalse(guard.blocked("screen_act", up))
        repeat(policy.attempts) { guard.executed("screen_act", up, deliveredActionResult()); observe(guard, "display:2", 0L) }
        guard.reset()
        observe(guard, "display:2", 0L)
        assertFalse(guard.blocked("screen_act", up))
    }

    @Test fun repeatedMethodRejectionsLeaveOtherGroundedMethodsAvailable() {
        val guard = ObservationRecovery(policy)
        val click = """{"actions":[{"op":"click","x":420,"y":360}]}"""
        val alternative = """{"actions":[{"op":"click","x":780,"y":800}]}"""
        observe(guard, "display:1", 0L)
        repeat(policy.attempts) {
            guard.executed("screen_act", click, deliveredActionResult())
            observe(guard, "display:1", 0L)
        }
        repeat(20) { index ->
            val repeated = """{"action_title":"Retry $index","actions":[{"op":"click","x":425,"y":365}]}"""
            assertTrue(guard.blocked("screen_act", repeated))
            val facts = guard.recoveryFacts("screen_act", repeated)
            assertEquals("method", facts.getValue("restriction_scope").jsonPrimitive.content)
            assertEquals(policy.attempts, facts.getValue("repeated_visual_attempts").jsonPrimitive.int)
            assertFalse(guard.blocked("screen_act", alternative))
        }
        observe(guard, "display:1", -1L)
        assertFalse(guard.blocked("screen_act", click))
    }
    @Test fun cropRoundTripRetainsExhaustedTargetAndItsOriginalEvidence() {
        val guard = ObservationRecovery(policy)
        observe(guard, "session", 0L)
        repeat(policy.attempts) { guard.executed("screen_act", up, deliveredActionResult()); observe(guard, "session", 0L) }
        assertTrue(guard.blocked("screen_act", up))
        guard.observe("session", "crop", "obs_crop", 0L, "same-frame")
        assertFalse(guard.blocked("screen_act", up))
        guard.observe("session", "full", "obs_return", 0L, "same-frame")
        assertTrue(guard.blocked("screen_act", up))
        val facts = guard.recoveryFacts("screen_act", up)
        assertEquals("method", facts.getValue("restriction_scope").jsonPrimitive.content)
        assertEquals(policy.attempts.toString(), facts["repeated_visual_attempts"].toString())
        assertTrue(facts["first_observation_ref"].toString().contains("obs_0"))
        assertTrue(facts["latest_observation_ref"].toString().contains("obs_3"))
    }

    @Test fun sameObservationCannotCountAnUnobservedReturn() {
        val guard = ObservationRecovery(policy)
        guard.observe("session", "full", "obs_same", 0L, "same-frame")
        repeat(8) {
            guard.executed("screen_act", up, deliveredActionResult())
            guard.observe("session", "full", "obs_same", 0L, "same-frame")
        }
        assertFalse(guard.blocked("screen_act", up))
    }

    @Test fun anotherScrollRegionRemainsAvailable() {
        val guard = ObservationRecovery(policy)
        val left = """{"actions":[{"op":"scroll","direction":"up","region":{"left":0,"top":0,"right":400,"bottom":1000}}]}"""
        observe(guard, "session", 0L)
        repeat(policy.attempts) { guard.executed("screen_act", left, deliveredActionResult()); observe(guard, "session", 0L) }
        assertTrue(guard.blocked("screen_act", left))
        assertFalse(guard.blocked("screen_act", left.replace("\"left\":0", "\"left\":500").replace("\"right\":400", "\"right\":1000")))
    }

    @Test fun scopeCacheIsBoundedAndReturningToAnotherSessionClearsIt() {
        val guard = ObservationRecovery(policy)
        observe(guard, "session", 0L)
        repeat(policy.attempts) { guard.executed("screen_act", up, deliveredActionResult()); observe(guard, "session", 0L) }
        repeat(policy.scopeHistoryLimit) { guard.observe("session", "crop_$it", "obs_crop_$it", 0L, "same-frame") }
        guard.observe("session", "full", "obs_return", 0L, "same-frame")
        assertFalse(guard.blocked("screen_act", up))
    }

    @Test fun successfulDetailVisitsDoNotExhaustTheStationaryActionBudget() {
        val guard = ObservationRecovery(policy)
        val click = """{"actions":[{"op":"click","x":925,"y":83}]}"""
        observe(guard, "session", 0L)
        repeat(policy.attempts) { index ->
            guard.executed("screen_act", click, deliveredActionResult())
            observe(guard, "session", (index + 1) * 0x1111111111111111L)
            guard.executed("screen_act", click, deliveredActionResult())
            observe(guard, "session", 0L)
        }
        assertFalse(guard.blocked("screen_act", click))
        assertFalse(guard.blocked("screen_act", click.replace("925", "500")))
    }

    @Test fun aStateThatKeepsReturningUnderOneCoarseViewportIsReportedAsRevisited() {
        val guard = ObservationRecovery(policy)
        // A picker: the coarse fingerprint never changes while its values move back and forth.
        fun value(level: Int) = ScreenChangeSignature(20, 20, ByteArray(400) { if (it < 40) (level * 60).toByte() else 0 })
        fun show(level: Int) = guard.observe("display:1", "full", "obs_${observationSequence++}", 0L, "frame-$level", value(level))
        show(1); show(2); show(1)
        assertTrue("Two appearances are still ordinary navigation", guard.navigationFacts().isEmpty())
        show(1)
        assertTrue("Staying on a state is not a return", guard.navigationFacts().isEmpty())
        show(3); show(1)
        val facts = guard.navigationFacts()
        assertEquals(true, facts["current_state_revisited"]?.jsonPrimitive?.boolean)
        assertEquals(3, facts["state_appearances"]?.jsonPrimitive?.int)
        assertEquals("obs_${observationSequence - 6}", facts["first_appearance_ref"]?.jsonPrimitive?.content)
        assertEquals("none", facts["restriction_scope"]?.jsonPrimitive?.content)
        show(2)
        assertTrue("A state seen twice is not reported", guard.navigationFacts().isEmpty())
    }

    @Test fun navigationCyclesRemainAdvisoryUntilADifferentViewIsObserved() {
        val guard = ObservationRecovery(policy)
        val click = """{"actions":[{"op":"click","x":495,"y":541}]}"""
        val backClick = """{"actions":[{"op":"click","x":50,"y":50}]}"""
        observe(guard, "session", 0L)
        repeat(policy.maxOscillationCycles) {
            guard.executed("screen_act", click, deliveredActionResult())
            observe(guard, "session", -1L)
            guard.executed("screen_act", backClick, deliveredActionResult())
            observe(guard, "session", 0L)
        }
        assertFalse("View revisits cannot establish semantic stagnation", guard.blocked("screen_act", click))
        val facts = guard.navigationFacts()
        assertEquals(true, facts["state_oscillation_detected"]?.jsonPrimitive?.boolean)
        assertEquals("none", facts.getValue("restriction_scope").jsonPrimitive.content)
        assertEquals("UNKNOWN", facts.getValue("goal_progress").jsonPrimitive.content)
        assertEquals(policy.maxOscillationCycles * 2, facts.getValue("observation_refs").jsonArray.size)

        val newMethod = """{"actions":[{"op":"press_key","key":"ENTER"}]}"""
        assertFalse(guard.blocked("screen_act", newMethod))
        guard.executed("screen_act", newMethod, deliveredActionResult())
        observe(guard, "session", 0L)
        assertFalse(guard.blocked("screen_act", click))
        assertFalse(guard.navigationFacts().isEmpty())

        observe(guard, "session", 0xffffL)
        assertTrue(guard.navigationFacts().isEmpty())
        assertFalse(guard.blocked("screen_act", click))
    }

    @Test fun rawChangesAndAnUnrelatedActionReceiptDoNotEraseRepeatedAttempts() {
        val guard = ObservationRecovery(policy)
        val click = """{"actions":[{"op":"click","x":425,"y":346}]}"""
        observe(guard, "session", 0L)
        repeat(policy.attempts) {
            guard.executed("screen_act", click, deliveredActionResult())
            guard.observe("session", "full", "frame-$it", 0L, "bytes-$it")
        }
        assertTrue(guard.blocked("screen_act", click))
        guard.executed("screen_act", """{"actions":[{"op":"type_text","target_id":"field","text":"new value","clear":true}]}""", deliveredActionResult())
        assertTrue(guard.blocked("screen_act", click))
        guard.observe("session", "full", "different-viewport", 0xffffL, "new-frame")
        assertFalse(guard.blocked("screen_act", click))
    }

    @Test fun newStationaryRecoveryMethodRetainsItsOwnAttemptBudget() {
        val guard = ObservationRecovery(policy)
        val open = """{"actions":[{"op":"click","x":495,"y":541}]}"""
        val close = """{"actions":[{"op":"click","x":50,"y":50}]}"""
        val recovery = """{"actions":[{"op":"press_key","key":"ENTER"}]}"""
        observe(guard, "session", 0L)
        repeat(policy.maxOscillationCycles) {
            guard.executed("screen_act", open, deliveredActionResult())
            observe(guard, "session", -1L)
            guard.executed("screen_act", close, deliveredActionResult())
            observe(guard, "session", 0L)
        }
        assertFalse(guard.blocked("screen_act", open))
        repeat(policy.attempts) {
            assertFalse("Navigation remains available", guard.blocked("screen_act", open))
            assertFalse("An unchanged recovery method uses its own bounded budget", guard.blocked("screen_act", recovery))
            guard.executed("screen_act", recovery, deliveredActionResult())
            observe(guard, "session", 0L)
            assertFalse("The stationary budget belongs to the recovery method", guard.blocked("screen_act", open))
        }
        assertTrue(guard.blocked("screen_act", recovery))
        assertFalse(guard.blocked("screen_act", open))
        val facts = guard.recoveryFacts("screen_act", recovery)
        assertEquals(false, facts["action_in_observed_cycle"]?.jsonPrimitive?.boolean)
        assertEquals(true, facts["state_oscillation_detected"]?.jsonPrimitive?.boolean)
        assertEquals(policy.attempts, facts.getValue("repeated_visual_attempts").jsonPrimitive.int)
    }

    @Test fun aMethodFromBeforeTheCurrentCycleIsNotACycleParticipant() {
        val guard = ObservationRecovery(policy)
        val earlier = """{"actions":[{"op":"click","x":850,"y":300}]}"""
        val open = """{"actions":[{"op":"click","x":495,"y":541}]}"""
        val close = """{"actions":[{"op":"click","x":50,"y":50}]}"""
        observe(guard, "session", 0L)
        guard.executed("screen_act", earlier, deliveredActionResult())
        observe(guard, "session", 0xffffL)
        observe(guard, "session", 0L)
        repeat(policy.maxOscillationCycles) {
            guard.executed("screen_act", open, deliveredActionResult())
            observe(guard, "session", -1L)
            guard.executed("screen_act", close, deliveredActionResult())
            observe(guard, "session", 0L)
        }
        assertFalse(guard.blocked("screen_act", open))
        assertFalse("Earlier navigation outside the observed cycle remains available", guard.blocked("screen_act", earlier))
    }

    @Test fun aRecoveryMethodCanRevisitViewsWithoutBeingRestrictedByTheCycle() {
        val guard = ObservationRecovery(policy)
        val open = """{"actions":[{"op":"click","x":495,"y":541}]}"""
        val close = """{"actions":[{"op":"click","x":50,"y":50}]}"""
        val recovery = """{"actions":[{"op":"press_key","key":"ENTER"}]}"""
        observe(guard, "session", 0L)
        repeat(policy.maxOscillationCycles) {
            guard.executed("screen_act", open, deliveredActionResult())
            observe(guard, "session", -1L)
            guard.executed("screen_act", close, deliveredActionResult())
            observe(guard, "session", 0L)
        }
        assertFalse(guard.blocked("screen_act", recovery))
        guard.executed("screen_act", recovery, deliveredActionResult())
        observe(guard, "session", -1L)
        guard.executed("screen_act", close, deliveredActionResult())
        observe(guard, "session", 0L)
        assertFalse("A productive revisit is the Agent's decision", guard.blocked("screen_act", recovery))
        assertEquals(true, guard.recoveryFacts("screen_act", recovery)
            .getValue("action_in_observed_cycle").jsonPrimitive.boolean)
    }

    @Test fun changedOutcomeResetsEarlierStationaryAttemptsForThatTarget() {
        val guard = ObservationRecovery(policy)
        observe(guard, "session", 0L)
        repeat(policy.attempts - 1) {
            guard.executed("screen_act", up, deliveredActionResult())
            observe(guard, "session", 0L)
        }
        guard.executed("screen_act", up, deliveredActionResult())
        observe(guard, "session", -1L)
        observe(guard, "session", 0L)
        assertFalse(guard.blocked("screen_act", up))
        guard.executed("screen_act", up, deliveredActionResult())
        observe(guard, "session", 0L)
        assertFalse(guard.blocked("screen_act", up))
    }

    @Test fun contentEntryPreservesNavigationFactsAndUnrelatedFailedMethods() {
        for (injected in listOf(0L, 1L)) {
            val guard = ObservationRecovery(policy)
            val open = """{"actions":[{"op":"click","x":495,"y":541}]}"""
            val back = """{"actions":[{"op":"click","x":50,"y":50}]}"""
            observe(guard, "session", 0L)
            repeat(policy.maxOscillationCycles) {
                guard.executed("screen_act", open, deliveredActionResult()); observe(guard, "session", -1L)
                guard.executed("screen_act", back, deliveredActionResult()); observe(guard, "session", 0L)
            }
            assertFalse(guard.blocked("screen_act", open))
            repeat(policy.attempts) { guard.settled("screen_act", up, staleResult()) }
            guard.settled("screen_act", """{"actions":[{"op":"type_text","text":"New content","clear":true}]}""",
                deliveredActionResult().copy(metrics = mapOf("action_1_text_input_injected" to injected)))
            assertFalse(guard.blocked("screen_act", open))
            assertFalse("Input does not judge whether navigation was productive", guard.navigationFacts().isEmpty())
            assertTrue("Unrelated failed methods remain guarded", guard.blocked("screen_act", up))
        }
    }

    @Test fun passiveObservationBatchRetainsFailureVisualBudgetsAndNavigationFacts() {
        val guard = ObservationRecovery(policy)
        val failedClick = """{"actions":[{"op":"click","x":420,"y":360}]}"""
        observe(guard, "session", 0L)
        repeat(policy.attempts) {
            guard.settled("screen_act", failedClick, staleResult())
        }
        assertEquals(policy.attempts, guard.failedMethodHistory().single()
            .jsonObject.getValue("failed_attempts").jsonPrimitive.int)

        repeat(policy.attempts) {
            guard.settled("screen_act", up, deliveredWithoutEffect())
            guard.executed("screen_act", up, deliveredActionResult())
            observe(guard, "session", 0L)
        }
        assertTrue(guard.blocked("screen_act", up))

        val passiveBatches = listOf(
            """{"actions":[{"op":"wait","ms":250}]}""",
            """{"actions":[{"op":"screenshot"}]}""",
            """{"actions":[{"op":"wait","ms":250},{"op":"screenshot"}]}"""
        )
        passiveBatches.forEach { arguments ->
            guard.settled("screen_act", arguments, observationSuccess())
            guard.executed("screen_act", arguments, observationSuccess())
            observe(guard, "session", 0L)
        }
        assertEquals(policy.attempts, guard.failedMethodHistory().single()
            .jsonObject.getValue("failed_attempts").jsonPrimitive.int)
        assertTrue("passive batches must not clear a visual repetition budget", guard.blocked("screen_act", up))

        val oscillating = ObservationRecovery(policy)
        val open = """{"actions":[{"op":"click","x":495,"y":541}]}"""
        val back = """{"actions":[{"op":"click","x":50,"y":50}]}"""
        observe(oscillating, "session", 0L)
        repeat(policy.maxOscillationCycles) {
            oscillating.executed("screen_act", open, deliveredActionResult())
            observe(oscillating, "session", -1L)
            oscillating.executed("screen_act", back, deliveredActionResult())
            observe(oscillating, "session", 0L)
        }
        assertFalse(oscillating.blocked("screen_act", open))
        passiveBatches.forEach { arguments ->
            oscillating.settled("screen_act", arguments, observationSuccess())
            oscillating.executed("screen_act", arguments, observationSuccess())
            observe(oscillating, "session", 0L)
        }
        assertFalse(oscillating.blocked("screen_act", open))
        assertFalse("Passive reads preserve advisory navigation facts", oscillating.navigationFacts().isEmpty())
    }

    @Test fun successfulMutationClearsOnlyItsMatchingFailureAndSessionResetClearsAll() {
        val guard = ObservationRecovery(policy)
        val failedClick = """{"actions":[{"op":"click","x":420,"y":360}]}"""
        val failedScroll = up
        observe(guard, "session", 0L)
        repeat(policy.attempts) {
            guard.settled("screen_act", failedClick, staleResult())
            guard.settled("screen_act", failedScroll, staleResult())
        }
        val history = guard.failedMethodHistory()
        assertEquals(2, history.size)
        assertTrue(history.all { it.jsonObject.getValue("failed_attempts").jsonPrimitive.int == policy.attempts })
        assertTrue(history.all { it.jsonObject.getValue("diagnostic_code").jsonPrimitive.content == "STALE_OBSERVATION" })

        guard.observe("session", "crop", "crop-observation", 0L, "same-frame")
        assertTrue(guard.failedMethodHistory().isEmpty())
        guard.observe("session", "full", "return-observation", 0L, "same-frame")
        assertEquals(2, guard.failedMethodHistory().size)

        guard.settled("screen_act", failedClick, deliveredActionResult())
        val remaining = guard.failedMethodHistory()
        assertEquals(1, remaining.size)
        assertEquals(Json.parseToJsonElement(failedScroll), remaining.single().jsonObject.getValue("arguments"))
        assertTrue(guard.blocked("screen_act", failedScroll))

        val unrelatedMutation = """{"actions":[{"op":"press_key","key":"BACK"}]}"""
        guard.settled("screen_act", unrelatedMutation, deliveredActionResult())
        assertEquals(1, guard.failedMethodHistory().size)
        assertTrue(guard.blocked("screen_act", failedScroll))

        guard.settled("screen_act", failedScroll, deliveredActionResult())
        assertTrue(guard.failedMethodHistory().isEmpty())
        assertFalse(guard.blocked("screen_act", failedClick))

        guard.settled("screen_act", failedClick, staleResult())
        assertFalse(guard.failedMethodHistory().isEmpty())
        observe(guard, "next-session", 0L)
        assertTrue(guard.failedMethodHistory().isEmpty())
    }

    @Test fun freshImagesCoordinateJitterAndRenamingCannotRenewUnknownInputAttempts() {
        for (diagnostic in listOf("TEXT_INPUT_EFFECT_UNKNOWN", "TEXT_INPUT_TARGET_FOCUS_UNVERIFIED")) {
            val guard = ObservationRecovery(policy)
            val unknown = ToolExecutionResult(
                outputText = "The input effect is unknown.",
                executionStatus = ToolExecutionStatus.FAILED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                diagnosticCode = diagnostic,
                actionEvidence = ToolActionEvidence(
                    delivery = ActionDeliveryEvidence.UNKNOWN,
                    transition = StateTransitionEvidence.UNKNOWN,
                    goalProgress = GoalProgressEvidence.NOT_PROVEN
                )
            )
            observe(guard, "session", 0L)
            repeat(policy.attempts) { index ->
                val arguments = """{"actions":[{"op":"type_text","x":${245 + index * 5},"y":675,"text":"10","clear":true}],"action_title":"Input $index"}"""
                assertFalse(guard.blocked("screen_act", arguments))
                guard.settled("screen_act", arguments, unknown)
                guard.executed("screen_act", arguments, unknown)
                guard.observe("session", "full", "frame-$index", (index + 1) * 0x1111111111111111L, "bytes-$index")
            }
            repeat(20) { index ->
                guard.observe("session", "full", "retry-frame-$index", index.toLong(), "retry-bytes-$index")
                val scalar = """{"action_title":"New method $index","actions":[{"op":"type_text","x":255,"y":680,"text":"10","clear":true}]}"""
                val batch = """{"actions":[{"op":"type_text","x":255,"y":680,"text":"10","clear":true}],"action_title":"Batch retry $index"}"""
                assertTrue(guard.blocked("screen_act", scalar))
                assertTrue(guard.blocked("screen_act", batch))
                val facts = guard.recoveryFacts("screen_act", scalar)
                assertEquals("method", facts.getValue("restriction_scope").jsonPrimitive.content)
                assertEquals(policy.attempts, facts.getValue("repeated_failed_attempts").jsonPrimitive.int)
                assertEquals(diagnostic, facts.getValue("diagnostic_code").jsonPrimitive.content)
                assertFalse(guard.blocked("screen_act", """{"actions":[{"op":"click","x":800,"y":900}]}"""))
            }
        }
    }

    private fun signature(changedSamples: Int, value: Int = 200): ScreenChangeSignature =
        ScreenChangeSignature(100, 100, ByteArray(10_000) { index -> (if (index < changedSamples) value else 20).toByte() })

    @Test fun keypadTapsThatChangeTheDisplayAreNotStationaryRepeats() {
        val guard = ObservationRecovery(policy)
        val nine = """{"actions":[{"op":"click","x":625,"y":592}]}"""
        guard.observe("session", "full", "obs-0", 0L, "frame-0", signature(0))
        repeat(policy.attempts * 3) { index ->
            assertFalse(guard.blocked("screen_act", nine))
            guard.executed("screen_act", nine, deliveredActionResult())
            // The coarse fingerprint stays identical while one more digit appears in the display.
            guard.observe("session", "full", "obs-${index + 1}", 0L, "frame-${index + 1}", signature((index + 1) * 40))
        }
        assertFalse(guard.blocked("screen_act", nine))
    }

    @Test fun changesBelowTheEffectThresholdStillCountAsStationary() {
        val guard = ObservationRecovery(policy)
        val click = """{"actions":[{"op":"click","x":420,"y":360}]}"""
        val caret = (policy.effectChangeFraction * 10_000).toInt() - 1
        guard.observe("session", "full", "obs-0", 0L, "frame-0", signature(0))
        repeat(policy.attempts) { index ->
            guard.executed("screen_act", click, deliveredActionResult())
            // A blinking caret alternates a few samples without any effect of the click.
            guard.observe("session", "full", "obs-${index + 1}", 0L, "frame-${index + 1}",
                signature(if (index % 2 == 0) caret else 0))
        }
        assertTrue(guard.blocked("screen_act", click))
    }

    @Test fun anExhaustedMethodIsReleasedOnceItsViewChangesMaterially() {
        val guard = ObservationRecovery(policy)
        val click = """{"actions":[{"op":"click","x":420,"y":360}]}"""
        val clear = """{"actions":[{"op":"click","x":137,"y":484}]}"""
        guard.observe("session", "full", "obs-0", 0L, "frame-0", signature(500))
        repeat(policy.attempts) { index ->
            guard.executed("screen_act", click, deliveredActionResult())
            guard.observe("session", "full", "obs-${index + 1}", 0L, "frame-${index + 1}", signature(500))
        }
        assertTrue(guard.blocked("screen_act", click))
        guard.executed("screen_act", clear, deliveredActionResult())
        guard.observe("session", "full", "obs-cleared", 0L, "frame-cleared", signature(0))
        assertFalse("The same coarse viewport with cleared content is a different view", guard.blocked("screen_act", click))
    }

    private fun staleTargetRejection(moving: Boolean) = ToolExecutionResult(
        outputText = """{"details":{"attempted":1}}""",
        executionStatus = ToolExecutionStatus.FAILED,
        evidenceStatus = ToolEvidenceStatus.UNKNOWN,
        diagnosticCode = "PRE_ACTION_OBSERVATION_CHANGED",
        metrics = mapOf("batch_attempted_actions" to 1L, "action_1_pre_action_motion_moving" to if (moving) 1L else 0L)
    )

    @Test fun staleRejectionsWhileTheScreenMovesOnItsOwnNeverExhaustTheTap() {
        val guard = ObservationRecovery(policy)
        val search = """{"actions":[{"op":"click","x":920,"y":78}]}"""
        observe(guard, "session", 0L)
        repeat(policy.attempts * 3) {
            assertFalse(guard.blocked("screen_act", search))
            guard.settled("screen_act", search, staleTargetRejection(moving = true))
        }
        assertFalse("A playing video must not ban the control behind it", guard.blocked("screen_act", search))
        assertTrue(guard.failedMethodHistory().isEmpty())
    }

    @Test fun staleRejectionsOnAStillScreenStillExhaustTheTap() {
        val guard = ObservationRecovery(policy)
        val search = """{"actions":[{"op":"click","x":920,"y":78}]}"""
        observe(guard, "session", 0L)
        repeat(policy.attempts) { guard.settled("screen_act", search, staleTargetRejection(moving = false)) }
        assertTrue(guard.blocked("screen_act", search))
    }

    private fun successfulBatchReceipt(arguments: String) = runBlocking {
        executeBatchReceipt(arguments)
    }

    private fun failedBatchReceipt(arguments: String) = runBlocking {
        executeBatchReceipt(arguments, failAtAction = 1)
    }

    private suspend fun executeBatchReceipt(
        arguments: String,
        failAtAction: Int? = null
    ): ToolExecutionResult {
        var primitive = 0
        return ScreenActionBatchExecutor(
            ScreenActionBatchPolicy(
                enabled = true,
                allowedOperations = listOf("click", "type_text", "press_key", "scroll", "wait", "screenshot"),
                defaultWaitMs = 20,
                minWaitMs = 10,
                maxWaitMs = 100,
                maxTotalWaitMs = 100, maxCaptureImages = 10, maxCaptureBytes = 8 * 1024 * 1024L, maxDragRepeat = 64
            )
        ) { _, _, _ ->
            primitive++
            if (primitive == failAtAction) ToolProtocolResult.notExecuted("STALE_OBSERVATION", retryable = true)
            else deliveredActionResult()
        }.execute(arguments)
    }

    private fun deliveredActionResult() = ToolExecutionResult(
        outputText = "Action was delivered; visual or goal progress is not claimed.",
        executionStatus = ToolExecutionStatus.SUCCEEDED,
        evidenceStatus = ToolEvidenceStatus.CAPTURED,
        observationId = "post-action-observation",
        actionEvidence = ToolActionEvidence(
            delivery = ActionDeliveryEvidence.ACTION_DELIVERED,
            transition = StateTransitionEvidence.UNCHANGED,
            goalProgress = GoalProgressEvidence.NOT_PROVEN
        )
    )

    private fun staleResult() = ToolExecutionResult(
        outputText = """{"details":{"attempted":1}}""",
        executionStatus = ToolExecutionStatus.NOT_EXECUTED,
        evidenceStatus = ToolEvidenceStatus.UNKNOWN,
        diagnosticCode = "STALE_OBSERVATION"
    )

    private fun deliveredWithoutEffect() = ToolExecutionResult(
        outputText = "Effect was not confirmed",
        executionStatus = ToolExecutionStatus.FAILED,
        evidenceStatus = ToolEvidenceStatus.UNKNOWN,
        diagnosticCode = "ACTION_EFFECT_UNKNOWN"
    )

    private fun observationSuccess() = ToolExecutionResult(
        outputText = "Observation captured",
        executionStatus = ToolExecutionStatus.SUCCEEDED,
        evidenceStatus = ToolEvidenceStatus.CAPTURED,
        observationId = "passive-observation"
    )

    private fun projectFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path")
    }

}
