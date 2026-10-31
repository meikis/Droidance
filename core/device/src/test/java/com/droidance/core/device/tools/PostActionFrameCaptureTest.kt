// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PostActionFrameCaptureTest {
    private fun comparison(changed: Int) = ObservationPixelComparison(100, changed, 0, 0)
    private fun unchanged() = comparison(0)

    private val policy = PostActionFrameCapture.Policy(250, 600, 120, 2, 8, 0.005)

    @Test fun anIntermediateCaptureUsesOnlyRemainingActionDelay() = runBlocking {
        var now = 1900L
        var frameId = 0L
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++frameId) },
            { now }, { now += it })
        PostActionFrameCapture(sampler, { now }) { now += it }.capture(policy, 1500,
            actionEndedAtElapsedMs = 1000, verifyFrame = { unchanged() }) {
            assertTrue(now >= 2500)
        }
        assertEquals(0L, PostActionFrameCapture.remainingWaitMs(1500, 1000, now))
        // Another delivered action resets the minimum, even when a previous frame exists.
        assertEquals(1500L, PostActionFrameCapture.remainingWaitMs(1500, now, now))
    }

    @Test fun localizedMotionStillDeliversTheFullFrameIncludingAnOverlayOnce() = runBlocking {
        var now = 0L
        var samples = 0
        var captures = 0
        val sampler = VisualFrameSampler({
            samples++
            VisualFrameSampler.Frame(ByteArray(100) { if (it < 10) (samples * 40).toByte() else 80 }, samples.toLong())
        }, { now }, { now += it })
        val (readiness, frame) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy.copy(localizedMotionMaxFraction = 0.15), verifyFrame = { comparison(10) }) {
                captures++
                "full current frame including popup"
            }
        assertTrue(readiness.localizedMotion)
        assertFalse(readiness.stable)
        assertEquals(1, captures)
        assertEquals("full current frame including popup", frame)
        assertEquals(490L, now)
    }

    @Test fun animationSettlesBeforeOneFullCapture() = runBlocking {
        var now = 0L
        var samples = 0
        var fullCaptures = 0
        val sampler = VisualFrameSampler({
            assertTrue(now >= 250)
            samples++
            VisualFrameSampler.Frame(ByteArray(64) { if (samples == 1) 20 else 80 }, samples.toLong())
        }, { now }, { now += it })
        val (result, frame) = PostActionFrameCapture(sampler, { now }) { now += it }.capture(policy, verifyFrame = { unchanged() }) {
            fullCaptures++
            assertEquals(3, samples)
            "fresh full frame"
        }
        assertTrue(result.stable)
        assertEquals(1, fullCaptures)
        assertEquals("fresh full frame", frame)
    }

    @Test fun unavailableSampleStillCapturesCurrentEvidenceOnce() = runBlocking {
        var now = 0L
        var fullCaptures = 0
        val sampler = VisualFrameSampler({ null }, { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }.capture(policy, verifyFrame = { unchanged() }) { fullCaptures++ }
        assertFalse(result.stable)
        assertEquals(1, fullCaptures)
    }

    @Test fun explicitWaitCannotExitEarlyOnAStableLoadingSkeleton() = runBlocking {
        var now = 0L
        var fullCaptures = 0
        var samples = 0
        val sampler = VisualFrameSampler({
            assertTrue(now >= 1500)
            samples++
            VisualFrameSampler.Frame(ByteArray(64) { if (samples == 1) 20 else 80 }, samples.toLong())
        }, { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }.capture(policy, 1500, verifyFrame = { unchanged() }) {
            assertEquals(1740L, now)
            fullCaptures++
        }
        assertTrue(result.stable)
        assertEquals(3, result.captures)
        assertEquals(1740L, result.elapsedMs)
        assertEquals(1, fullCaptures)
    }

    @Test fun movingSamplesStayBoundedAndStillDeliverOneFrame() = runBlocking {
        var now = 0L
        var samples = 0
        var fullCaptures = 0
        val sampler = VisualFrameSampler({
            samples++
            VisualFrameSampler.Frame(ByteArray(64) { if (samples % 2 == 0) 20 else 80 }, samples.toLong())
        }, { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }.capture(policy, verifyFrame = { comparison(65) }) { fullCaptures++ }
        assertFalse(result.stable)
        assertEquals(850L, now)
        assertEquals(1, fullCaptures)
    }

    private class PlaceholderScenario(extensionMs: Long, private val settleAgain: Boolean = true) {
        var now = 0L
        private var samples = 0L
        private var verifications = 0
        var captures = 0
        val policy = PostActionFrameCapture.Policy(250, 600, 120, 2, 8, 0.005, settleExtensionMs = extensionMs)
        // A quiet placeholder is sampled first; the full capture takes 900 ms, after which the
        // content has replaced it. Later verifications see the settled content.
        private val sampler = VisualFrameSampler({
            samples++
            val moving = !settleAgain && verifications > 0
            VisualFrameSampler.Frame(ByteArray(64) { if (moving && samples % 2 == 0L) 20 else 80 }, samples)
        }, { now }, { now += it })

        suspend fun run() = PostActionFrameCapture(sampler, { now }) { now += it }.capture(policy,
            verifyFrame = { verifications++; if (verifications == 1) comparison(40) else unchanged() }) {
            now += 900
            captures++
            "frame-$captures"
        }

        private fun comparison(changed: Int) = ObservationPixelComparison(100, changed, 0, 0)
        private fun unchanged() = comparison(0)
    }

    @Test fun contentReplacingAQuietPlaceholderIsWaitedOutWithinTheExtension() = runBlocking {
        val scenario = PlaceholderScenario(extensionMs = 2500)
        val (result, frame) = scenario.run()
        assertTrue(result.stable)
        assertEquals("frame-2", frame)
        assertEquals(2, scenario.captures)
        assertEquals(1L, result.diagnostics["settle_extension_captures"])
        assertEquals(1L, result.diagnostics["final_frame_confirmed"])
        assertTrue(scenario.now <= 850 + 2500 + 900)
    }

    @Test fun withoutAnExtensionTheTransientFrameIsReportedUnconfirmed() = runBlocking {
        val scenario = PlaceholderScenario(extensionMs = 0)
        val (result, frame) = scenario.run()
        assertFalse(result.stable)
        assertEquals("frame-1", frame)
        assertEquals(1, scenario.captures)
        assertEquals(0L, result.diagnostics["final_frame_confirmed"])
    }

    private class LateLoadScenario(private val changedFromDecision: Double, private val contentArrives: Boolean) {
        var now = 0L
        private var samples = 0L
        private var verifications = 0
        var captures = 0
        val policy = PostActionFrameCapture.Policy(250, 600, 120, 2, 8, 0.005, settleExtensionMs = 2500,
            lateChangeProbeMs = 600, newViewChangedFraction = 0.3, quietViewChangedFraction = 0.01)
        // A quiet placeholder is confirmed first; content replaces it before the late probe.
        private val sampler = VisualFrameSampler({ samples++; VisualFrameSampler.Frame(ByteArray(64) { 80 }, samples) },
            { now }, { now += it })

        suspend fun run() = PostActionFrameCapture(sampler, { now }) { now += it }.capture(policy,
            changedFromDecision = { changedFromDecision },
            verifyFrame = { verifications++; if (verifications == 2 && contentArrives) comparison(40) else comparison(0) }) {
            captures++
            "frame-$captures"
        }

        private fun comparison(changed: Int) = ObservationPixelComparison(100, changed, 0, 0)
    }

    @Test fun contentArrivingAfterANewViewWentQuietIsRecaptured() = runBlocking {
        val scenario = LateLoadScenario(changedFromDecision = 0.9, contentArrives = true)
        val (result, frame) = scenario.run()
        assertTrue(result.stable)
        assertEquals("frame-2", frame)
        assertEquals(1L, result.diagnostics["late_change_recaptures"])
        assertEquals(1L, result.diagnostics["final_frame_confirmed"])
    }

    @Test fun aQuietNewViewIsProbedOnceAndKeptWhenNothingArrives() = runBlocking {
        val scenario = LateLoadScenario(changedFromDecision = 0.9, contentArrives = false)
        val (result, frame) = scenario.run()
        assertEquals("frame-1", frame)
        assertEquals(0L, result.diagnostics["late_change_recaptures"])
        assertTrue("Only the single probe delay is added", scenario.now in 600L..1500L)
    }

    @Test fun inPlaceChangesAreNotProbedForLateContent() = runBlocking {
        val scenario = LateLoadScenario(changedFromDecision = 0.02, contentArrives = true)
        val (result, frame) = scenario.run()
        assertEquals("frame-1", frame)
        assertEquals(1, scenario.captures)
        assertEquals(0L, result.diagnostics["late_change_recaptures"])
        assertTrue(scenario.now < 600L)
    }

    @Test fun aScreenThatHasNotAnsweredTheActionIsProbedForALateResponse() = runBlocking {
        val scenario = LateLoadScenario(changedFromDecision = 0.002, contentArrives = true)
        val (result, frame) = scenario.run()
        assertEquals("frame-2", frame)
        assertEquals(1L, result.diagnostics["late_change_recaptures"])
        assertEquals(1L, result.diagnostics["final_frame_confirmed"])
    }

    @Test fun continuousMotionIsNotExtended() = runBlocking {
        var now = 0L
        var samples = 0
        var fullCaptures = 0
        val sampler = VisualFrameSampler({
            samples++
            VisualFrameSampler.Frame(ByteArray(64) { if (samples % 2 == 0) 20 else 80 }, samples.toLong())
        }, { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy.copy(settleExtensionMs = 2500), verifyFrame = { comparison(65) }) { fullCaptures++ }
        assertFalse(result.stable)
        assertEquals(1, fullCaptures)
        assertEquals(0L, result.diagnostics["settle_extension_captures"])
        assertEquals(850L, now)
    }

    @Test fun cancellationNeverStartsTheFullCapture() = runBlocking {
        val sampler = VisualFrameSampler({ throw CancellationException("cancelled") }, { 0L })
        try {
            PostActionFrameCapture(sampler, { 0L }) {}.capture(policy, verifyFrame = { unchanged() }) { error("capture after cancellation") }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun stableEarlierSamplesCannotCertifyAnAnimationInTheDeliveredFrame() = runBlocking {
        var now = 0L
        var frameId = 0L
        var fullCaptures = 0
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++frameId) },
            { now }, { now += it })
        val (result, frame) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy, verifyFrame = { captured ->
                if (captured == "transition") comparison(65) else unchanged()
            }) {
                fullCaptures++
                if (fullCaptures == 1) "transition" else "settled destination"
            }
        assertEquals("settled destination", frame)
        assertEquals(2, fullCaptures)
        assertTrue(result.stable)
        assertEquals(2L, result.diagnostics["final_frame_captures"])
        assertEquals(1L, result.diagnostics["final_frame_confirmed"])
    }

    @Test fun continuouslyChangingFinalFramesKeepTheLatestEvidenceWithinTheVerificationBudget() = runBlocking {
        var now = 0L
        var frameId = 0L
        var fullCaptures = 0
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++frameId) },
            { now }, { now += it })
        val (result, frame) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy, verifyFrame = { comparison(65) }) {
                now += 20
                ++fullCaptures
            }
        assertFalse(result.stable)
        assertEquals(fullCaptures, frame)
        assertTrue(fullCaptures > 1)
        assertTrue(now <= policy.initialWaitMs + policy.timeoutMs + 40)
        assertEquals(0L, result.diagnostics["final_frame_confirmed"])
        assertEquals(1L, result.diagnostics["final_frame_deadline_exhausted"])
    }

    @Test fun unavailableFinalVerificationDoesNotCertifyOrRecaptureAnUnreachableChannel() = runBlocking {
        var now = 0L
        var frameId = 0L
        var fullCaptures = 0
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++frameId) },
            { now }, { now += it })
        val (result, frame) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy, verifyFrame = { null }) { ++fullCaptures }
        assertFalse(result.stable)
        assertEquals(1, frame)
        assertEquals(1, fullCaptures)
        assertEquals(1L, result.diagnostics["final_frame_unavailable_checks"])
    }

    @Test fun cancellationDuringFinalVerificationDoesNotRecapture() = runBlocking {
        var now = 0L
        var frameId = 0L
        var fullCaptures = 0
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++frameId) },
            { now }, { now += it })
        try {
            PostActionFrameCapture(sampler, { now }) { now += it }
                .capture(policy, verifyFrame = { throw CancellationException("verification cancelled") }) { ++fullCaptures }
            fail("cancellation swallowed")
        } catch (_: CancellationException) {
            assertEquals(1, fullCaptures)
        }
    }

    @Test fun samplingAndEncodingLeaveOnlyTheRemainderForFinalVerification() = runBlocking {
        var now = 0L
        var samples = 0L
        var captures = 0
        val sampler = VisualFrameSampler({
            now += 100
            VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++samples)
        }, { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy, verifyFrame = { comparison(65) }) { captures++; now += 200; "current" }
        // 250 minimum + 320 sampling + 200 encoding leaves 80, never another 600 ms window.
        assertEquals(850L, now)
        assertEquals(1, captures)
        assertFalse(result.stable)
        assertEquals(850L, result.elapsedMs)
        assertEquals(1L, result.diagnostics["final_frame_deadline_exhausted"])
    }

    @Test fun timeSinceTheLastActionConsumesTheMinimumDelayAndReadinessBudget() = runBlocking {
        var now = 500L
        var samples = 0L
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++samples) },
            { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy, actionEndedAtElapsedMs = 0, verifyFrame = { unchanged() }) { "fresh" }
        assertEquals(620L, now)
        assertEquals(120L, result.elapsedMs)
        assertEquals(0L, result.diagnostics["initial_wait_ms"])
        assertTrue(result.stable)
    }

    @Test fun anExpiredActionBudgetRetainsNewEvidenceWithoutRestartingReadiness() = runBlocking {
        var now = 1000L
        var captures = 0
        val sampler = VisualFrameSampler({ error("expired readiness restarted") }, { now }, { now += it })
        val (result, frame) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy, actionEndedAtElapsedMs = 0, verifyFrame = { unchanged() }) { ++captures }
        assertEquals(1000L, now)
        assertEquals(1, frame)
        assertFalse(result.stable)
        assertEquals(0L, result.diagnostics["final_frame_confirmed"])
        assertEquals(1L, result.diagnostics["final_frame_deadline_exhausted"])
    }

    @Test fun encodingPastTheWaitDeadlinePreservesFreshlyVerifiedStableRendering() = runBlocking {
        var now = 0L
        var samples = 0L
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++samples) },
            { now }, { now += it })
        val (result, frame) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy, verifyFrame = { unchanged() }) { now += 500; "latest evidence" }
        assertEquals("latest evidence", frame)
        assertEquals(870L, result.elapsedMs)
        assertTrue(result.stable)
        assertEquals(1L, result.diagnostics["final_frame_confirmed"])
        assertEquals(1L, result.diagnostics["final_frame_deadline_exhausted"])
    }

    // Timing regression from the saved 2026-10-15 event 155: three stable
    // samples, zero final pixel change, and about 1.1 seconds of PNG encoding.
    @Test fun collectionReceiptWithStableSamplesAndSlowEncodingDoesNotRequireAnotherCapture() = runBlocking {
        var now = 1505L
        var samples = 0L
        var captures = 0
        val sampler = VisualFrameSampler({
            now += 70
            VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++samples)
        }, { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }.capture(
            policy.copy(initialWaitMs = 350, timeoutMs = 2000, stableSamples = 3),
            actionEndedAtElapsedMs = 0,
            verifyFrame = { now += 59; unchanged() }
        ) { captures++; now += 1115; "retained page" }
        assertTrue(result.stable)
        assertEquals(3L, result.diagnostics["stable_samples"])
        assertEquals(1L, result.diagnostics["final_frame_deadline_exhausted"])
        assertEquals(1, captures)
    }

    @Test fun changingFinalPixelsPastTheDeadlineDoNotRenewWaitingOrCertifyTheFrame() = runBlocking {
        var now = 0L
        var samples = 0L
        var captures = 0
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++samples) },
            { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }.capture(
            policy, verifyFrame = { comparison(65) }
        ) { captures++; now += 500; "changing page" }
        assertFalse(result.stable)
        assertEquals(1, captures)
        assertEquals(870L, now)
    }

    @Test fun aNewActionStartsANewBudgetAfterAnEarlierBudgetExpired() = runBlocking {
        var now = 1000L
        var samples = 0L
        val sampler = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(64) { 80 }, ++samples) },
            { now }, { now += it })
        val (result, _) = PostActionFrameCapture(sampler, { now }) { now += it }
            .capture(policy, actionEndedAtElapsedMs = now, verifyFrame = { unchanged() }) { "new action" }
        assertEquals(1370L, now)
        assertTrue(result.stable)
        assertEquals(250L, result.diagnostics["initial_wait_ms"])
    }
}
