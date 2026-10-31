// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationFreshnessPolicyTest {
    private val policy = ObservationFreshnessPolicy()

    @Test fun shellOnlyTasksDoNotStartAnUnrequestedScreenWorkflow() {
        assertFalse(policy.requiresFrameAfterMutation("shell", """{"command":"df /data","expect_screen_change":false}""", false))
        assertTrue(policy.requiresFrameAfterMutation("shell", """{"command":"id"}""", false))
        assertTrue(policy.requiresFrameAfterMutation("shell", """{"command":"input keyevent BACK"}""", true))
        assertTrue(policy.requiresFrameAfterMutation("android_act", """{"op":"open"}""", false))
        assertFalse(policy.requiresFrameAfterMutation("android_act",
            """{"op":"open","expect_screen_change":false}""", false))
        assertFalse(policy.requiresFrameAfterMutation("android_act", """{"op":"content"}""", true))
    }

    @Test
    fun configuredWaitRefreshesOnlyAnAlreadyObservedDeviceTask() {
        val tools = setOf("wait")
        assertTrue(policy.mustInvalidateAfter("wait", true, true, tools))
        assertFalse(policy.mustInvalidateAfter("wait", true, false, tools))
        assertFalse(policy.mustInvalidateAfter("wait", false, true, tools))
        assertFalse(policy.mustInvalidateAfter("task_memo", true, true, tools))
        assertFalse(policy.mustInvalidateAfter("wait", true, true, emptySet()))
    }

    @Test
    fun pageChangingToolsRequireFreshObservation() {
        assertTrue(policy.shouldObserveAfter("android_act", null))
    }

    @Test
    fun nativeReceiptDoesNotTriggerScreenObservation() {
        assertFalse(
            policy.shouldObserveAfter(
                "android_act",
                null,
                arguments = """{"op":"open","expect_screen_change":false}"""
            )
        )
        assertTrue(
            policy.shouldObserveAfter(
                "android_act",
                null,
                arguments = """{"op":"open","expect_screen_change":true}"""
            )
        )
    }

    @Test
    fun failedPageLaunchStillRequestsFreshObservation() {
        assertTrue(
            policy.shouldObserveAfter(
                "android_act",
                null,
                toolSucceeded = false,
                launchAccepted = true
            )
        )
        assertFalse(policy.shouldObserveAfter("screen_act", null, toolSucceeded = false))
    }

    @Test
    fun notExecutedPolicyResultDoesNotObserveAnEmptyVirtualDisplay() {
        val result = com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("POLICY_REJECTED", false)

        assertFalse(
            policy.shouldObserveAfter(
                "android_act",
                null,
                toolSucceeded = ToolResultStatus.isSuccess(result)
            )
        )
    }

    @Test
    fun observationToolsDoNotRequestAnotherObservation() {
        assertFalse(policy.shouldObserveAfter("android_read", null))
    }

    @Test
    fun visionModeStillAttachesPostActionScreenStateForActuatingTools() {
        assertTrue(policy.shouldObserveAfter("screen_act", null, visionPerception = true))
    }

    @Test
    fun executorScheduledCapturePreventsDuplicateExplicitObservation() {
        assertFalse(
            policy.shouldObserveAfter(
                "screen_act",
                null,
                postActionCaptureScheduled = true
            )
        )
        assertTrue(
            policy.shouldObserveAfter(
                "android_act",
                null,
                postActionCaptureScheduled = false
            )
        )
    }

    @Test
    fun textModeStillRefreshesAfterActuatingTools() {
        assertTrue(policy.shouldObserveAfter("screen_act", null, visionPerception = false))
    }

    @Test
    fun repeatedNoProgressRequiresFreshObservation() {
        val validation = com.droidance.core.agent.event.ToolValidationEvidence(
            progressState = "UNCHANGED",
            reason = "no move",
            foregroundPackage = "com.demo",
            expectedPackage = null,
            noProgressCount = 2
        )

        assertTrue(policy.shouldObserveAfter("screen_act", validation))
    }
}
