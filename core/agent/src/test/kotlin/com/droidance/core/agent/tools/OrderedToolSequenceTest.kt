// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.harness.*
import org.junit.Assert.*
import org.junit.Test

class OrderedToolSequenceTest {
    @Test fun failedWorkingStateAllowsIndependentActionsButNotDependentWritesOrCompletion() {
        val sequence = OrderedToolSequence()
        sequence.record("records", ToolProtocolResult.notExecuted("WORKSPACE_VERSION_CHANGED", true), workingStateOnly = true)
        assertNull(sequence.rejection(independentOfWorkingState = true))
        assertNotNull(sequence.rejection())
        sequence.record("launch", ToolProtocolResult.accepted("delivered"))
        assertNull(sequence.rejection(independentOfWorkingState = true))
        sequence.record("input", ToolProtocolResult.failed("INPUT_CONNECTION_NOT_READY", false))
        assertNotNull(sequence.rejection(independentOfWorkingState = true))
    }

    @Test fun uncertainOrChangedWorkingStateStillStopsTheWholeSuffix() {
        for (result in listOf(ToolProtocolResult.failed("PARTIAL_WRITE", false).copy(stateChanged = true),
            ToolProtocolResult.success("uncertain").copy(evidenceStatus = ToolEvidenceStatus.UNKNOWN))) {
            val sequence = OrderedToolSequence()
            sequence.record("records", result, workingStateOnly = true)
            assertNotNull(sequence.rejection(independentOfWorkingState = true))
        }
    }
    @Test fun explicitFrameWaitEndsTheSharedObservationGroup() {
        val members = screenActionSequenceMembers(listOf(call(0, "screen_act", arguments = """{"actions":[{"op":"type_text"}]}"""),
            call(1, "screen_act", arguments = """{"wait_for_frame_ms":300,"actions":[{"op":"click"}]}"""),
            call(2, "screen_act", arguments = """{"actions":[{"op":"click"}]}""")), frame)
        assertEquals(setOf("durable-0", "durable-1"), members.keys)
        assertTrue(members.getValue("durable-1").last)
        assertTrue(screenActionSequenceMembers(listOf(
            call(0, "screen_act", arguments = """{"wait_for_frame_ms":300,"actions":[{"op":"click"}]}"""),
            call(1, "screen_act")), frame).isEmpty())
    }

    @Test fun deliveredActionsDoNotRequireSemanticSuccessToContinue() {
        val sequence = OrderedToolSequence()
        sequence.record("note", ToolProtocolResult.success("saved"))
        sequence.record("action", ToolProtocolResult.accepted("delivered"))
        assertNull(sequence.rejection())
    }

    @Test fun failedOrUnknownEffectsStopSuffixAndRetainFirstFailure() {
        val results = listOf(
            ToolProtocolResult.failed("FAILED", false),
            ToolProtocolResult.notExecuted("STALE", true),
            ToolProtocolResult.success("invalid evidence").copy(evidenceStatus = ToolEvidenceStatus.INVALID),
            ToolProtocolResult.success("unknown evidence").copy(evidenceStatus = ToolEvidenceStatus.UNKNOWN),
            ToolProtocolResult.accepted("unknown").copy(actionEvidence = ToolActionEvidence(
                ActionDeliveryEvidence.UNKNOWN, StateTransitionEvidence.UNKNOWN, GoalProgressEvidence.NOT_PROVEN)),
            ToolProtocolResult.accepted("undelivered").copy(actionEvidence = ToolActionEvidence(
                ActionDeliveryEvidence.NOT_DELIVERED, StateTransitionEvidence.UNKNOWN, GoalProgressEvidence.NOT_PROVEN))
        )
        for (result in results) {
            val sequence = OrderedToolSequence()
            sequence.record("first", result)
            sequence.record("later", ToolProtocolResult.failed("OTHER", false))
            val rejection = requireNotNull(sequence.rejection())
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejection.executionStatus)
            assertEquals("PRIOR_CALL_FAILED", rejection.diagnosticCode)
            assertTrue(rejection.outputText.contains("first"))
        }
    }

    @Test fun rejectionCarriesDiagnosticReasonFromFirstFailedCall() {
        val sequence = OrderedToolSequence()
        sequence.record("action-1", ToolProtocolResult.failed("PRE_ACTION_TARGET_OCCLUDED", false))
        val rejection = requireNotNull(sequence.rejection())
        assertTrue(rejection.outputText.contains("action-1"))
        assertTrue(rejection.outputText.contains("PRE_ACTION_TARGET_OCCLUDED"))
    }

    private val frame = ObservationEnvelope("observed", "execution", "backend", 0, 0,
        "capture", 1, 1, null, "AIR", 1f, 100 to 200, 100 to 200, 0 to 0)

    private fun call(index: Int, name: String, local: Boolean = false, arguments: String = """{"actions":[{"op":"click","x":1,"y":1}]}""") = PlannedToolCall(index,
        "durable-$index", ProviderToolCall("provider-$index", name, arguments), RegisteredToolContract(
            name, "Protocol test", if (local) ToolBinding.TURN_LOCAL else ToolBinding.DEVICE_BOUND,
            ToolEffect.STATE_CHANGING, emptyMap()))

    @Test fun adjacentScreensShareFrameAndKeepDurableIdentitiesAfterLocalWrite() {
        val members = screenActionSequenceMembers(listOf(call(0, "task_memo", true),
            call(1, "screen_act"), call(2, "screen_act")), frame)
        assertEquals(setOf("durable-1", "durable-2"), members.keys)
        assertTrue(members.getValue("durable-1").first)
        assertFalse(members.getValue("durable-1").last)
        assertTrue(members.getValue("durable-2").last)
        assertSame(members.getValue("durable-1").sequence, members.getValue("durable-2").sequence)
        assertSame(frame, members.getValue("durable-1").sequence.observation)
    }

    @Test fun neverShareOldFrameAcrossNativeActionExplicitBatchGapOrMissingObservation() {
        for (prefix in listOf("android_act", "android_read")) {
            assertTrue(screenActionSequenceMembers(listOf(call(0, prefix),
                call(1, "screen_act"), call(2, "screen_act")), frame).isEmpty())
        }
        assertTrue(screenActionSequenceMembers(listOf(call(0, "screen_act", arguments = """{"actions":[]}"""),
            call(1, "screen_act"), call(2, "screen_act")), frame).isEmpty())
        assertTrue(screenActionSequenceMembers(listOf(call(0, "screen_act"), call(2, "screen_act")), frame).isEmpty())
        assertTrue(screenActionSequenceMembers(listOf(call(0, "screen_act"), call(1, "screen_act")), null).isEmpty())
        assertTrue(screenActionSequenceMembers(listOf(call(0, "screen_act"), call(1, "task_memo", true),
            call(2, "screen_act")), frame).isEmpty())
    }
}
