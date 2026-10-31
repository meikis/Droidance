// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.LocalActionEvidence

import com.droidance.agent.LocalActionVerifier
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.core.agent.event.ToolExecutionResult
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class TextInputCancellationTest {
    @Test
    fun canceledObservationEscapesWithoutReturningAnArgumentErrorOrInjectingInput() {
        val cancellation = CancellationException("stop")
        val backend = Proxy.newProxyInstance(
            DeviceBackend::class.java.classLoader,
            arrayOf(DeviceBackend::class.java)
        ) { _, method, _ -> error("Unexpected backend call: ${method.name}") } as DeviceBackend
        val service = TextInputService(
            deviceBackend = backend,
            captureObservation = { throw cancellation },
            captureSettledObservation = { error("unexpected settled observation") },
            stagePostActionObservation = { error("unexpected staged observation") },
            verifyTextDelivery = { _, _, _, _, _ -> error("unexpected verification") },
            inputFocusProbeTimeoutMs = { 100 },
            resolveSemanticTarget = { error("unexpected target resolution") },
            wasTextInputVerified = { _, _, _ -> error("unexpected duplicate check") },
            recordTextInputVerification = { _, _, _, _ -> error("unexpected verification record") },
            recordInputDiagnostic = { _, _, _ -> error("unexpected diagnostic") },
            validate = { _, _, _, _, _ -> error("unexpected validation") },
            appendValidation = { _: ToolExecutionResult, _: LocalActionEvidence.ValidationResult? ->
                error("unexpected validation output")
            },
            recordExecutedActionResult = { error("unexpected injection receipt") }
        )

        try {
            runBlocking { service.typeText("""{"text":"example","clear":true}""") }
            fail("Canceled input must reach the execution lifecycle owner")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
