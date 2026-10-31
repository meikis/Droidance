// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.config.markdownSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreActionObservationContractTest {
    @Test fun scrollChecksThePlannedPhysicalStartAndExplicitSwipeUsesItsDeclaredStart() {
        val source = (com.droidance.agent.executorSources() + "\n" +
            projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText())
        val callback = source.substringAfter("preflightScrollTarget = { physicalStart ->").substringBefore("scrollDuration =")
        assertTrue(callback.contains("toModel(physicalStart.x, physicalStart.y)"))
        assertTrue(callback.contains("targetX = target.x, targetY = target.y, usePendingTarget = false"))
        assertTrue(callback.contains("invalidateModelFrameBinding()"))
        assertTrue(source.contains("setOf(\"batch\", \"press_key\", \"scroll\")"))
        assertTrue(source.contains("ScreenBatchOperation.PRESS_KEY, ScreenBatchOperation.SCROLL"))
        assertTrue(source.contains("if (swipe) \"startX\" else \"x\""))
        assertTrue(source.contains("if (swipe) \"startY\" else \"y\""))
    }
    @Test
    fun screenActionsTakeAForcedFreshSampleAfterTheExistingLeaseChecks() {
        val source = (com.droidance.agent.executorSources() + "\n" +
            projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText())
        val actionGate = source
            .substringAfter("if (UnifiedDeviceToolProtocol.isScreenAction(toolCall.name))")
            .substringBefore("val tool = toolRegistry.getTool(toolCall.name)")
        val sampleCheck = source
            .substringAfter("suspend fun checkPreActionObservation(")
            .substringBefore("private fun preActionObservationMetrics(")

        assertTrue(actionGate.contains("hasCurrentModelObservationLease(boundExecution)"))
        assertTrue(actionGate.contains("checkPreActionObservation(boundExecution)"))
        assertTrue(sampleCheck.contains("deviceBackend.samplePixels(reference.samples.map { it.physicalX to it.physicalY })"))
        assertFalse(sampleCheck.contains("deviceBackend.captureScreen"))
        assertTrue(source.contains("if (!hasCurrentModelObservationLease(execution))"))
        assertTrue(source.contains("lease.rotationDegrees == currentRotation"))
        assertTrue(sampleCheck.contains("currentSample.displayId != execution.displayId"))
        assertTrue(sampleCheck.contains("currentSample.backendSessionId != execution.backendSessionId"))
        assertTrue(sampleCheck.contains("currentSample.capturedElapsedMs < startedAt"))
        assertTrue(sampleCheck.contains("currentSample.rotationDegrees != lease.rotationDegrees"))
        assertTrue(sampleCheck.contains("currentSample.width != expectedSize.first"))
        assertTrue(sampleCheck.contains("currentSample.height != expectedSize.second"))
        assertTrue(source.contains("code = \"PRE_ACTION_OBSERVATION_UNAVAILABLE\""))
        assertTrue(source.contains("physical_frame_size_missing"))
        assertTrue(source.contains("invalidateModelFrameBinding()"))
        assertTrue(source.contains("catch (cancelled: CancellationException)"))
        assertTrue(source.contains("throw cancelled"))
    }

    @Test
    fun decisionAndExecutionFramesUseTheCurrentMapperAndEnforcePreActionCheck() {
        val source = (com.droidance.agent.executorSources() + "\n" +
            projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText())
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
        val rules = markdownSection(
            projectFile("app/src/main/assets/rules/agent_core.md").readText(),
            "operator_behavior"
        )

        assertTrue(source.contains("observation.pixelReference"))
        assertTrue(source.contains("ObservationPixelComparator.compare"))
        assertTrue(source.contains("PreActionFreshness.decide"))
        assertTrue(projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/PreActionFreshness.kt")
            .readText().contains("ObservationPixelComparator.allowsAction"))
        assertTrue(source.contains("currentModelCoordinateMapper"))
        assertTrue(source.contains("preActionObservationMode == \"enforce\""))
        assertTrue(policy.contains("pre_action_observation_mode: enforce"))
        val batch = markdownSection(
            projectFile("app/src/main/assets/rules/completion_recovery.md").readText(), "batch_contract"
        )
        assertTrue(rules.contains("Visual actions use the current bound screenshot"))
        assertTrue(rules.contains("historical observations support facts, not current controls"))
        assertTrue(batch.contains("reveals controls you have not seen; act on them in the next batch"))
        assertTrue(batch.contains("stops the batch untapped if it changed"))
        assertTrue(batch.contains("A rejected or interrupted suffix does not undo completed_prefix"))
        assertTrue(rules.contains("Read actual execution and partial-effect receipts"))
        assertTrue(rules.contains("inspect unknown effects before choosing remaining work"))
        assertTrue(rules.contains("preserve completed effects"))
        assertFalse(source.contains("AccessibilityService"))
    }

    @Test
    fun preActionMetricsRecordAvailabilityAndPixelDifferenceWithoutSemanticClaims() {
        val source = (com.droidance.agent.executorSources() + "\n" +
            projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText())

        assertTrue(source.contains("pre_action_observation_check_ms"))
        assertTrue(source.contains("pre_action_observation_check_to_handler_gap_ms"))
        assertTrue(source.contains("pre_action_observation_decision_captured_elapsed_ms"))
        assertTrue(source.contains("pre_action_observation_execution_captured_wall_ms"))
        assertTrue(source.contains("pre_action_observation_display_id"))
        assertTrue(source.contains("pre_action_observation_changed_fraction_ppm"))
        assertTrue(source.contains("pre_action_observation_sampled_pixels"))
        assertTrue(source.contains("pre_action_observation_total_channel_delta"))
        assertTrue(source.contains("pixelAt = { x, y -> colors.getValue(x to y) }"))
        assertTrue(source.contains("captureObservationPixelReference(observation)"))
        assertFalse(source.contains("IntArray(pixelCount.toInt())"))
    }

    @Test
    fun failedClickCheckAfterAnotherClickIsRepeatedOnceAfterRenderingSettles() {
        val source = com.droidance.agent.executorSources()
        val batch = source.substringAfter("var observationCheck = checkTarget()").substringBefore("observationCheck?.metrics")
        assertTrue(batch.contains("observationCheck?.rejection != null && mappedAction.followsClick(previousAction)"))
        assertTrue(batch.contains("awaitRenderingSettled(\"action_capture_policy\")"))
        assertEquals("The click target is checked again exactly once", 1,
            Regex("observationCheck = checkTarget\\(\\)").findAll(batch).count())
        assertTrue(source.contains("previousAction = mappedAction"))
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
