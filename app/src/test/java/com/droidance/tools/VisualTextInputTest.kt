// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.LocalActionEvidence
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.core.device.agent.device.InputFocusReadiness
import com.droidance.core.agent.event.ToolExecutionStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisualTextInputTest {
    @Test fun agentSelectsTheDeliveryMethodWithoutRuntimeRetries() = runBlocking {
        for (method in TextInputMethod.entries) {
            val fixture = Fixture()
            val target = if (method == TextInputMethod.IME) ",\"x\":300,\"y\":200" else ""
            val result = fixture.service.typeText("""{"text":"sample","clear":false,"method":"${method.wireName}"$target}""", fixture.before)
            assertEquals(listOf(method), fixture.methods)
            assertEquals(1, fixture.injections)
            assertTrue(result.outputText.contains("method=${method.wireName}"))
            assertTrue(result.outputText.contains("delivery_verified=false"))
            if (method == TextInputMethod.IME) assertEquals(0, fixture.taps)
        }
        val invalid = Fixture()
        invalid.service.typeText("""{"text":"sample","clear":false,"method":"unknown"}""", invalid.before)
        assertEquals(0, invalid.injections)
        assertEquals(0, invalid.taps)
        val missingImeTarget = Fixture()
        val missing = missingImeTarget.service.typeText("""{"text":"sample","clear":false,"method":"ime"}""", missingImeTarget.before)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, missing.executionStatus)
        assertEquals(0, missingImeTarget.injections)
    }

    @Test fun rejectedInputOnALockedDeviceRequiresHumanHelpAndRetainsTheDriverFacts() = runBlocking {
        for (batch in listOf(false, true)) {
            val f = Fixture(payloadRejected = true, locked = true)
            val result = f.service.typeText("""{"text":"sample","clear":true}""", if (batch) f.before else null)
            assertEquals(ToolExecutionStatus.INTERRUPTED, result.executionStatus)
            assertEquals("INPUT_BLOCKED_BY_KEYGUARD", result.diagnosticCode)
            assertTrue(result.outputText.contains("requires_human"))
            assertTrue(result.outputText.contains("clipboard unavailable"))
            assertEquals(0L, result.metrics["text_input_injected"])
        }
        val unlocked = Fixture(payloadRejected = true)
        val rejected = unlocked.service.typeText("""{"text":"sample","clear":true}""", unlocked.before)
        assertEquals("TEXT_INPUT_EFFECT_UNKNOWN", rejected.diagnosticCode)
        assertTrue(rejected.outputText.contains("PAYLOAD_REJECTED"))
        assertTrue(rejected.outputText.contains("clipboard unavailable"))
    }

    @Test fun causalBatchFocusContinuesWithoutRetappingAndStopsOnEditorChange() = runBlocking {
        val unchanged = Fixture()
        val result = unchanged.service.typeText("""{"text":"sample","clear":true}""",
            unchanged.before, expectedFocusTarget = "input_window=true;displayId=1;window_id=editor")
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(0, unchanged.taps)
        assertEquals(1, unchanged.injections)
        for (fixture in listOf(Fixture(afterDisplay = 2),
            Fixture(focus = "input_window=true;displayId=1"))) {
            val rejected = fixture.service.typeText("""{"text":"sample","clear":true}""",
                fixture.before, expectedFocusTarget = "input_window=true;displayId=1;window_id=editor")
            assertEquals(0, fixture.injections)
            assertEquals(0, fixture.taps)
            assertEquals("TEXT_INPUT_TARGET_FOCUS_UNVERIFIED", rejected.diagnosticCode)
        }
    }
    @Test fun aTappedPageThatHandsFocusToItsOwnWindowIsBoundOnceWithoutRetapping() = runBlocking {
        val fixture = Fixture(focus = "input_window=true;displayId=1;window_id=search")
        val result = fixture.service.typeText("""{"text":"sample","clear":true}""",
            fixture.before, expectedFocusTarget = "input_window=true;displayId=1;window_id=editor")
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(0, fixture.taps)
        assertEquals(1, fixture.injections)
        assertEquals(1L, result.metrics["text_input_focus_rebinds"])
    }

    @Test fun focusThatMovedOutsideTheTappedAppIsNeverBoundOrTyped() = runBlocking {
        for (window in listOf("3c4d other.app/.Dialog", "StatusBar", null)) {
            val fixture = Fixture(focus = "input_window=true;displayId=1;window_id=search", focusedWindow = window)
            val rejected = fixture.service.typeText("""{"text":"sample","clear":true}""",
                fixture.before, expectedFocusTarget = "input_window=true;displayId=1;window_id=editor")
            assertEquals(0, fixture.injections)
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejected.executionStatus)
        }
        val unknownOrigin = Fixture(focus = "input_window=true;displayId=1;window_id=search")
        val rejected = unknownOrigin.service.typeText("""{"text":"sample","clear":true}""",
            unknownOrigin.before.copy(foregroundPackage = null),
            expectedFocusTarget = "input_window=true;displayId=1;window_id=editor")
        assertEquals(0, unknownOrigin.injections)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejected.executionStatus)
    }

    @Test fun twoTargetedBatchInputsFocusWithoutKeyboardOrIntermediateObservation() = runBlocking {
        val fixture = Fixture()
        for (x in listOf(300, 700)) {
            val result = fixture.service.typeText("""{"text":"sample","clear":true,"x":$x,"y":200}""", fixture.before)
            assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
            assertTrue(result.outputText.contains("observation_deferred=true"))
            assertFalse(result.isVerifiedSuccess)
        }
        assertEquals(2, fixture.taps)
        assertEquals(2, fixture.injections)
        assertEquals(0, fixture.observations)
        assertEquals(0, fixture.settledObservations)
    }

    @Test fun unchangedInputConnectionWithoutViewBoundsCanReceiveText() = runBlocking {
        val fixture = Fixture()
        val result = fixture.service.typeText("""{"text":"sample","clear":true,"x":300,"y":200}""")
        assertEquals(1, fixture.injections)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertFalse(result.isVerifiedSuccess)
        assertTrue(result.outputText.contains("delivery_verified=false"))
    }

    @Test fun changedDisplayOrMissingInputConnectionDoesNotInject() = runBlocking {
        for (fixture in listOf(Fixture(afterDisplay = 2), Fixture(focus = null))) {
            val result = fixture.service.typeText("""{"text":"sample","clear":true,"x":300,"y":200}""")
            assertEquals(0, fixture.injections)
            assertEquals("TEXT_INPUT_TARGET_FOCUS_UNVERIFIED", result.diagnosticCode)
            assertTrue(result.outputText.contains("\"retryable\":true"))
        }
    }

    @Test fun currentEditorCanReceiveTextWithoutRetappingOldCoordinates() = runBlocking {
        val fixture = Fixture()
        val result = fixture.service.typeText("""{"text":"sample","clear":true}""")
        assertEquals(0, fixture.taps)
        assertEquals(1, fixture.injections)
        assertFalse(result.isVerifiedSuccess)
    }

    @Test fun unavailableBackendDoesNotInjectOrSuggestAnotherVisualFocusAttempt() = runBlocking {
        for (diagnostic in listOf("INPUT_FOCUS_QUERY_TIMEOUT", "INPUT_WINDOW_FORMAT_UNAVAILABLE", "INPUT_DISPLAY_UNAVAILABLE")) {
            val fixture = Fixture(focus = null, focusDiagnostic = diagnostic)
            val result = fixture.service.typeText("""{"text":"sample","clear":true}""")
            assertEquals(0, fixture.injections)
            assertEquals(0, fixture.taps)
            assertEquals("TEXT_INPUT_BACKEND_NOT_READY", result.diagnosticCode)
            assertEquals(com.droidance.core.agent.ToolRecovery.REPLAN, com.droidance.core.agent.ToolRecovery.classify(result))
        }
    }

    @Test fun missingCurrentEditorWithoutCoordinatesDoesNotInject() = runBlocking {
        val fixture = Fixture(focus = null)
        fixture.service.typeText("""{"text":"sample","clear":true}""")
        assertEquals(0, fixture.taps)
        assertEquals(0, fixture.injections)
    }

    @Test fun unresolvedExplicitQueryDoesNotWriteToUnrelatedCurrentEditor() = runBlocking {
        val fixture = Fixture()
        val result = fixture.service.typeText("""{"text":"sample","clear":true,"query":"missing target"}""")
        assertEquals("TEXT_INPUT_TARGET_NOT_FOUND", result.diagnosticCode)
        assertEquals(0, fixture.injections)
    }

    @Test fun currentEditorReadbackVerifiesOnlyAnImplicitCurrentTarget() = runBlocking {
        val implicit = Fixture(readback = true)
        val result = implicit.service.typeText("""{"text":"sample","clear":true}""")
        assertTrue(result.outputText.contains("delivery_verified=true"))
        val explicit = Fixture(readback = true)
        val explicitResult = explicit.service.typeText("""{"text":"sample","x":300,"y":200,"clear":true}""")
        assertTrue(explicitResult.outputText.contains("delivery_verified=false"))
    }

    @Test fun batchReadbackVerifiesOnlyAnImplicitCurrentTarget() = runBlocking {
        val implicit = Fixture(readback = true)
        val result = implicit.service.typeText("""{"text":"sample","clear":true}""", implicit.before)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(result.isVerifiedSuccess)
        assertTrue(result.outputText.contains("delivery_verified=true"))
        assertTrue(result.outputText.contains("current_editor_readback_verified=true"))
        assertTrue(result.outputText.contains("observation_deferred=true"))
        assertEquals(com.droidance.core.agent.event.ActionDeliveryEvidence.ACTION_DELIVERED, result.actionEvidence?.delivery)
        assertEquals(1L, result.metrics["text_input_delivery_verified"])
        assertEquals(0, implicit.observations + implicit.settledObservations)

        val explicit = Fixture(readback = true)
        val explicitResult = explicit.service.typeText("""{"text":"sample","x":300,"y":200,"clear":true}""", explicit.before)
        assertFalse(explicitResult.isVerifiedSuccess)
        assertTrue(explicitResult.outputText.contains("delivery_verified=false"))
        assertEquals(0L, explicitResult.metrics["text_input_delivery_verified"])

        for ((fixture, clear) in listOf(Fixture() to true, Fixture(readback = true) to false)) {
            val unverified = fixture.service.typeText("""{"text":"sample","clear":$clear}""", fixture.before)
            assertFalse(unverified.isVerifiedSuccess)
            assertTrue("A missing readback or an append cannot verify delivery",
                unverified.outputText.contains("delivery_verified=false"))
        }
    }

    @Test fun previousCurrentEditorReceiptCannotReplaceFreshReadback() = runBlocking {
        val fixture = Fixture(previouslyVerified = true)
        val result = fixture.service.typeText("""{"text":"sample","clear":true}""")
        assertEquals(1, fixture.injections)
        assertTrue(result.outputText.contains("delivery_verified=false"))
    }

    @Test fun priorReplacementCannotSuppressAnAppend() = runBlocking {
        val fixture = Fixture(readback = true, previouslyVerified = true)
        val result = fixture.service.typeText("""{"text":"sample","clear":false}""")
        assertEquals(1, fixture.injections)
        assertTrue(result.outputText.contains("delivery_verified=false"))
    }

    @Test fun implicitEditorChangeBeforeInjectionIsRejected() = runBlocking {
        val fixture = Fixture(changeFocus = true)
        val result = fixture.service.typeText("""{"text":"sample","clear":true}""")
        assertEquals(0, fixture.injections)
        assertEquals("TEXT_INPUT_TARGET_FOCUS_UNVERIFIED", result.diagnosticCode)
    }

    @Test fun missingEditModeCannotTapProbeOrInject() = runBlocking {
        val fixture = Fixture()
        val result = fixture.service.typeText("""{"text":"replacement","x":300,"y":200}""")
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", result.diagnosticCode)
        assertTrue(result.outputText.contains("missing_parameter"))
        assertTrue(result.outputText.contains("clear"))
        assertEquals(0, fixture.taps)
        assertEquals(0, fixture.focusProbes)
        assertEquals(0, fixture.injections)
    }

    private class Fixture(
        val afterDisplay: Int = 1,
        val focus: String? = "input_window=true;displayId=1;window_id=editor",
        val focusedWindow: String? = "1a2b generic.app/.Editor",
        val readback: Boolean = false,
        val previouslyVerified: Boolean = false,
        val changeFocus: Boolean = false,
        val focusDiagnostic: String = "INPUT_CONNECTION_NOT_READY",
        val payloadRejected: Boolean = false,
        val locked: Boolean = false
    ) {
        var observations = 0
        var settledObservations = 0
        var focusProbes = 0
        val methods = mutableListOf<TextInputMethod>()
        var injections = 0
        var taps = 0
        val before = LocalActionEvidence.ObservationLite(1L, "generic.app", observationId = "before", backendSessionId = "session", displayId = 1)
        val after = before.copy(screenHash = 2L, observationId = "after", displayId = afterDisplay)
        val backend = Proxy.newProxyInstance(DeviceBackend::class.java.classLoader, arrayOf(DeviceBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getActiveDisplayId" -> afterDisplay
                "awaitInputFocus" -> {
                    focusProbes++
                    val editor = if (changeFocus && focusProbes > 1) "other-editor" else focus
                    InputFocusReadiness(editor, if (editor == null) focusDiagnostic else "INPUT_CONNECTION_READY",
                        focusedWindowName = focusedWindow)
                }
                "tap" -> { taps++; DeviceInputResult(true, "delivered") }
                "typeText" -> {
                    methods += args!![3] as TextInputMethod
                    val nativeFocus = if (changeFocus) "other-window" else focus
                    if (args?.get(2) != nativeFocus) DeviceInputResult(false, "input window changed",
                        textInputEvidence = TextInputEvidence(false, false, false, false, false))
                    else { injections++
                    DeviceInputResult(!payloadRejected, if (payloadRejected) "clipboard unavailable" else "delivered", textInputEvidence = TextInputEvidence(true, !payloadRejected, !payloadRejected, false, false, readback = if (readback) TextInputReadback(1, "session", focus.orEmpty(), focus.orEmpty(), TextInputReadback.hash("sample"), true, true) else null)) }
                }
                else -> error("Unexpected backend operation ${method.name}")
            }
        } as DeviceBackend
        val service = TextInputService(
            deviceBackend = backend,
            captureObservation = { fresh -> observations++; if (fresh) after else before },
            captureSettledObservation = { settledObservations++; after },
            stagePostActionObservation = {},
            verifyTextDelivery = { _, _, _, _, _ -> false },
            inputFocusProbeTimeoutMs = { 100 },
            resolveSemanticTarget = { null },
            wasTextInputVerified = { _, _, _ -> previouslyVerified },
            recordTextInputVerification = { _, _, _, _ -> },
            recordInputDiagnostic = { _, _, _ -> },
            validate = { _, _, _, _, _ -> LocalActionEvidence.ValidationResult("type_text", LocalActionEvidence.ProgressState.UNKNOWN, "unknown", 1L, 2L, "generic.app") },
            appendValidation = { result, _ -> result },
            recordExecutedActionResult = {},
            deviceLocked = { locked }
        )
    }
}
