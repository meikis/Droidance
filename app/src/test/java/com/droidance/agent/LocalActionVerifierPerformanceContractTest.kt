// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.device.tools.DeviceScreenFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalActionVerifierPerformanceContractTest {
    @Test
    fun foregroundVerificationReadsOnlyTheFrozenVirtualDisplay() {
        val source = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()
        assertTrue(source.contains("sidecarBridge.getForegroundComponent("))
        assertFalse(source.contains("dumpsys activity activities"))
        assertFalse(source.contains("adbShell.exec"))
    }

    @Test
    fun quickScreenHashUsesRawFrameBytesInsteadOfBase64Encoding() {
        val source = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()

        assertTrue(
            "screen hash should use raw PNG bytes from the shared frame cache",
                source.contains("val context = executionContextStore.requireCurrent()") &&
                source.contains("deviceBackend.captureScreen(forceFresh = forceFresh)") &&
                source.contains(".imagePng") &&
                source.contains("?.contentHashCode()")
        )
        assertFalse(
            "screen hash must not base64-encode a full screenshot only to compute a local hash",
            source.contains("captureBase64(")
        )
    }

    @Test
    fun tapBeforeObservationCanReuseLastCapturedFrame() {
        val verifier = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()
        val executor = (com.droidance.agent.executorSources() + "\n" +
            projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText())

        assertTrue(
            "tap verification should be able to use the previous observe/auto-capture frame as the before hash",
            verifier.contains("preferLastFrame: Boolean = false") &&
                verifier.contains("deviceBackend.lastScreenFrame()?.takeIf { it.displayId == captureDisplayId }") &&
                executor.contains("val before = captureLocalObservation(preferLastFrame = true)")
        )
    }

    @Test
    fun postActionSettleExplicitlyRequestsFreshFrames() {
        val verifier = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()
        val executor = (com.droidance.agent.executorSources() + "\n" +
            projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText())

        assertTrue(verifier.contains("forceFresh: Boolean = false"))
        assertTrue(verifier.contains("deviceBackend.captureScreen(forceFresh = forceFresh)"))
        assertTrue(executor.contains("captureDisplaySnapshot(preferLastFrame = false, forceFresh = true)"))
    }

    @Test
    fun postActionObservationUsesTheBoundedSamplingPolicyWithoutSemanticSettling() {
        val executor = (com.droidance.agent.executorSources() + "\n" +
            projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText())
        val body = executor.substringAfter("suspend fun captureSettledObservation(")
            .substringBefore("private suspend fun captureGestureObservation(")
        val bounded = executor.substringAfter("suspend fun captureBoundedObservation(")
            .substringBefore("private suspend fun ")
        val policy = projectFile("app/src/main/assets/rules/observation_recovery.md").readText()
        assertTrue(body.contains("\"action_capture_policy\", waitBeforeFrameMs, onCapture, actionEndedAtElapsedMs = actionEndedAtElapsedMs"))
        assertTrue(bounded.contains("PostActionFrameCapture(sampler, { SystemClock.elapsedRealtime() })"))
        assertTrue(bounded.contains("verifyFrame = { snapshot ->"))
        assertTrue(bounded.contains("it.pngBytes.contentEquals(snapshot.imagePng)"))
        assertTrue(bounded.contains("deviceBackend.samplePixels(reference.samples.map"))
        assertTrue(bounded.contains("deviceBackend.sampleScreen()"))
        assertTrue(bounded.contains("forceFresh = true"))
        assertTrue(policy.contains("## action_capture_policy"))
        assertTrue(policy.contains("\"stableSamples\""))
        assertFalse(bounded.contains("semantic readiness"))
    }

    @Test
    fun tapAfterFrameIsReusedByPostActionAutoCapture() {
        val executor = (com.droidance.agent.executorSources() + "\n" +
            projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText())
        val rules = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
        val autoCaptureBody = executor.substringAfter("if (!toolNotExecuted && !sequenceContinues && autoCaptureAfterTool && shouldAutoCaptureAfterTool(toolCall, result)) {")
            .substringBefore("\n            coroutineContext.ensureActive()")

        assertTrue(
            "post-action auto-capture should consume the tap verifier frame before forcing a new screenshot",
            executor.contains("private var reusablePostActionFrame: DeviceScreenFrame?") &&
                executor.contains("stageReusablePostActionFrame(localActionVerifier.describeSnapshot(latest, visualFingerprint = fingerprint))") &&
                executor.contains("consumeReusablePostActionFrame(boundDisplayId)") &&
                autoCaptureBody.contains("if (reusableFrame == null)") &&
                autoCaptureBody.contains("val autoFrame = reusableFrame ?: invocationState.observations.captureSettledAutoFrame(frameWaitMs)")
        )
        assertTrue(
            "reusable frame age should be controlled by Markdown rules and scoped by displayId/hash",
            rules.contains("post_action_reusable_frame_ttl_ms") &&
                executor.contains("postActionReusableFrameTtlMs") &&
                executor.contains("it.displayId == activeDisplayId") &&
                executor.contains("it.contentHash == observationHash") &&
                executor.contains("it.displayId == displayId && ageMs in 0..maxAgeMs")
        )
    }

    @Test
    fun routeAttestationPollsForegroundBeforeTakingAnExpensiveFrame() {
        val source = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()
        val route = source.substringAfter("suspend fun awaitRouteTransition(")
            .substringBefore("private suspend fun captureDisplaySnapshot(")

        assertTrue(route.contains("currentForegroundPackageForContext()"))
        assertTrue(route.contains("if (attempt == attempts - 1)"))
        assertTrue(route.contains("capture(forceFresh = true)"))
        assertTrue(route.indexOf("currentForegroundPackageForContext()") < route.indexOf("capture(forceFresh = true)"))
    }

    @Test
    fun explicitDeepLinksRequireAFrameThatIsNewerThanThePreRouteObservation() {
        val verifier = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()

        assertTrue(verifier.contains("requireFreshFrame: Boolean = false"))
        assertTrue(verifier.contains("routeFrameIsFresh(before, candidate)"))
        assertTrue(verifier.contains("deviceBackend.invalidateScreenCache()"))
        assertTrue(registrar.contains("val deepLink = !uri.isNullOrBlank()"))
        assertTrue(registrar.contains("requireFreshFrame = true"))
    }

    @Test
    fun expectedPackageRoutesRequireBoundedFrameEvidenceAfterForegroundMatch() {
        val verifier = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val rules = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertTrue(verifier.contains("routeAttestationTimeoutMs"))
        assertTrue(verifier.contains("return@awaitRouteTransition packageObservation(before, foregroundPackage)"))
        assertTrue(registrar.contains("localActionVerifier.capture(forceFresh = true)"))
        assertTrue(registrar.contains("options = ExplicitIntentOptions(categories = listOf(Intent.CATEGORY_LAUNCHER), expectScreenChange = false)"))
        assertTrue(rules.contains("route_attestation_timeout_ms"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
