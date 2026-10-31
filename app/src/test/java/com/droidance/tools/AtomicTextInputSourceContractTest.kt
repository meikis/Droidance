// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicTextInputSourceContractTest {

    @Test
    fun consumptionCheckPreservesThePastePayloadAndAcceptedEventReceipt() {
        val source = projectFile(
            "core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java"
        ).readText()
        val inject = source.substringAfter("private Bundle injectTextOnDisplay")
            .substringBefore("private boolean confirmClipboardPasteConsumed")
        val check = source.substringAfter("private boolean confirmClipboardPasteConsumed")
            .substringBefore("private static boolean dispatchClipboardKeyCombo")

        assertTrue(inject.contains("if (clearExisting && target.isEmpty())"))
        assertTrue(inject.indexOf("boolean payloadConfirmed") < inject.indexOf("if (clearExisting)"))
        assertTrue(inject.contains("evidence.putBoolean(\"eventInjected\", pasteAccepted)"))
        assertFalse(inject.contains("evidence.putBoolean(\"eventInjected\", false)"))
        assertTrue(inject.contains("if (clipboardRestoreSafe) restoreClipboardIfOwned"))
        assertFalse(check.contains("sentinel"))
        assertTrue(check.contains("getTimestamp() > previousTimestamp"))
        assertFalse(check.substringBefore("copiedEvent.await").contains("writeAndReadBackClipboard"))
    }

    @Test
    fun rootInputUsesOneFrozenDisplaySidecarRoute() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()
        val body = source.substringAfter("suspend fun inputText(text: String, clearExisting: Boolean, expectedFocusTarget: String, method: TextInputMethod): DeviceInputResult")
            .substringBefore("/**")

        assertTrue(body.contains("executionContextStore.requireCurrent()"))
        assertTrue(body.contains("context.backendSessionId"))
        assertTrue(body.contains("sidecarBridge.injectText("))
        assertFalse(body.contains("tryMainScreenText"))
        assertFalse(body.contains("clipboardPaste"))
        assertFalse(body.contains("inputTextRootOnly"))
        assertFalse(body.contains("TextInputRoutes.viaShell"))
        assertFalse(body.contains("input text"))
        assertFalse(body.contains("binaryPath"))
        assertFalse(body.contains("imeEnsureReady"))
    }

    @Test
    fun sidecarUsesOrderedInputWithoutFixedBuffersOrPayloadReplay() {
        val source = projectFile(
            "core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarService.java"
        ).readText()
        val inject = source.substringAfter("private Bundle injectTextOnDisplay")
            .substringBefore("private ClipboardManager shellClipboardManager")
        assertFalse(inject.substringBefore("// 剪贴板回退").contains("SystemClock.sleep"))
        assertFalse(inject.contains("eraseFocusedTextBounded"))
        assertFalse(inject.contains("runTextInputCommand(targetDisplayId, \"text\""))
        assertTrue(source.contains("injectInputEventWithRetry(event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)"))
        assertFalse(inject.contains("KeyCharacterMap.load"))
        assertTrue(inject.contains("dispatchClipboardKeyCombo("))
        assertTrue(inject.contains("ClipboardTextVerifier.matches"))
        assertTrue(inject.contains("restoreClipboardIfOwned(clipboard, target, original)"))
    }

    @Test
    fun typeTextReturnsPostObservationEvidenceInsteadOfInjectionExitCode() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/tools/TextInputService.kt").readText()
        val body = source.substringAfter("private fun registerTypeText()")
            .substringBefore("private fun registerTapAliases")

        assertTrue(body.contains("val before = batchObservation ?: captureObservation(false)"))
        assertTrue(body.contains("val after = captureSettledObservation()"))
        assertTrue(body.contains("stagePostActionObservation(after)"))
        assertTrue(body.contains("val visualDestinationConfirmed = focusPoint != null && verifyTextDelivery("))
        assertTrue(body.contains("focusTargetAttested"))
        assertTrue(body.contains("withPostObservation"))
        assertTrue(body.contains("evidence.outcome"))
        assertTrue(body.contains("validate(\"type_text\", arguments, before, after, evidence.deliveryVerified)"))
        assertTrue(body.contains("appendValidation(message, validation)"))
        assertTrue(body.contains("deviceBackend.typeText(text, clear, focusAfter, method,"))
        assertTrue(body.contains("focusPoint?.takeIf { method == TextInputMethod.IME }"))
        assertTrue(body.contains("coordinateMapper().mapToPhysical(focusX, focusY)"))
        assertTrue(body.contains("targetQuery?.let(resolveSemanticTarget)"))
        assertFalse(body.contains("consumeRecentEditableTarget"))
        assertTrue(body.contains("deviceBackend.tap(focusPoint.x, focusPoint.y, TapPrecision.NORMAL)"))
        assertTrue(body.contains("var focusDeadline = System.nanoTime() + inputFocusProbeTimeoutMs() * 1_000_000L"))
        assertTrue(body.contains("deviceBackend.awaitInputFocus(remainingMs)"))
        assertFalse(body.contains("InputFocusAttestationPolicy"))
        assertFalse(body.contains("probeFocusedEditableBounds"))
        assertFalse(body.contains("InputFocusTargetBindingPolicy"))
        assertTrue(body.contains("target_focus_bound=\$targetFocusBound"))
        assertTrue(body.contains("val boundTargetKey = \"field:\$targetField\""))
        assertTrue(body.contains("wasTextInputVerified(pageKey, boundTargetKey, text)"))
        assertTrue(body.contains("duplicateVerified(targetField, targetFocusBound = true)"))
        assertTrue(body.contains("recordTextInputVerification("))
        assertTrue(body.contains("\"TEXT_INPUT_TARGET_FOCUS_UNVERIFIED\""))
        assertTrue(body.contains("code = \"TEXT_INPUT_TARGET_NOT_FOUND\""))
        assertTrue(body.contains("code = \"TEXT_INPUT_DUPLICATE_VERIFIED\""))
        assertFalse(body.contains("TEXT_INPUT_RETRY_EXHAUSTED"))
        assertFalse(body.contains("use a grounded semantic control or report need_more_info"))
        assertTrue(body.contains("normalized.x / 160"))
        assertFalse(body.contains("rememberEditableTarget"))
        assertTrue(body.contains("recordExecutedActionResult(focusResult)"))
        assertTrue(body.contains("recordExecutedActionResult(result)"))
        assertTrue(body.contains("put(\"constraint\", JsonPrimitive(\"both_or_neither\"))"))
        assertFalse(body.contains("sidecarInput.clearFocusedText()"))
        assertFalse(body.contains("if (result.success) \"Typed successfully"))
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
