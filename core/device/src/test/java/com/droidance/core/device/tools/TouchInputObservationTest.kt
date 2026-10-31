// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.LocalActionEvidence

import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInputObservationTest {
    @Test fun scrollChecksTheSinglePlannedStartAndNeverInjectsARejectedGesture() = runBlocking {
        for (allowed in listOf(true, false)) {
            val events = mutableListOf<String>()
            val backend = BackendProbe(successResult(), events)
            var planned = 0
            var checked: ModelCoordinateMapper.Point? = null
            val service = service(backend.backend,
                planScroll = { _, _, _ -> planned++; intArrayOf(13, 27, 52, 91, 100) },
                preflightScrollTarget = { point ->
                    checked = point
                    (if (allowed) ToolProtocolResult.success("stable") else
                        ToolProtocolResult.notExecuted("PRE_ACTION_OBSERVATION_CHANGED", true))
                        .copy(metrics = mapOf("pre_action_target_sampled_pixels" to 25L))
                },
                captureObservation = { error("deferred") }, captureSettledObservation = { error("deferred") },
                stage = { error("rejected/deferred gesture must not stage an image") },
                validate = { _, _, _, _, _ -> error("deferred") }, append = { _, _ -> error("deferred") },
                record = { events += "receipt" })
            val result = service.scroll("""{"direction":"down"}""", observeAfter = false)
            assertEquals(1, planned)
            assertEquals(ModelCoordinateMapper.Point(13, 27), checked)
            assertEquals(25L, result.metrics["pre_action_target_sampled_pixels"])
            assertEquals(if (allowed) 1 else 0, backend.swipeArguments.size)
            assertEquals(if (allowed) listOf("swipe", "receipt") else emptyList<String>(), events)
            if (allowed) assertEquals(listOf(13, 27, 52, 91, 100), backend.swipeArguments.single())
            else assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
        }
    }
    @Test
    fun swipeStagesAndValidatesOnlyTheSettledObservation() = runBlocking {
        val before = observation(100L)
        val settled = observation(300L)
        val events = mutableListOf<String>()
        val beforeForceFresh = mutableListOf<Boolean>()
        var staged: LocalActionEvidence.ObservationLite? = null
        var validatedAfter: LocalActionEvidence.ObservationLite? = null

        val backendProbe = BackendProbe(successResult(), events)
        val service = service(
            backend = backendProbe.backend,
            captureObservation = { forceFresh ->
                beforeForceFresh += forceFresh
                events += "before"
                before
            },
            captureSettledObservation = {
                events += "settled"
                // The stable collector may inspect intermediate frames, but only its returned
                // observation is allowed to enter the reusable/validation path.
                settled
            },
            stage = {
                events += "stage"
                staged = it
            },
            validate = { toolName, _, actualBefore, actualAfter, _ ->
                events += "validate"
                assertSame(before, actualBefore)
                validatedAfter = actualAfter
                validation(toolName, actualBefore, actualAfter)
            },
            append = { baseResult, _ ->
                events += "append"
                assertEquals(ToolExecutionStatus.SUCCEEDED, baseResult.executionStatus)
                assertEquals(ToolEvidenceStatus.UNKNOWN, baseResult.evidenceStatus)
                ToolProtocolResult.success("published")
            },
            record = { events += "receipt" }
        )

        val result = service.swipe(
            """{"startX":100,"startY":200,"endX":300,"endY":400,"duration":275}"""
        )

        assertEquals("published", result.outputText)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(ToolEvidenceStatus.NOT_REQUIRED, result.evidenceStatus)
        assertEquals(listOf(false), beforeForceFresh)
        assertSame(settled, staged)
        assertSame(settled, validatedAfter)
        assertEquals(listOf(100, 200, 300, 400, 275), backendProbe.swipeArguments.single())
        assertEquals(listOf("before", "swipe", "receipt", "settled", "stage", "validate", "append"), events)
    }

    @Test
    fun scrollStagesAndValidatesOnlyTheSettledObservationUsingBeforeHash() = runBlocking {
        val before = observation(10L)
        val settled = observation(40L)
        val events = mutableListOf<String>()
        var plannedDirection: String? = null
        var plannedRatio: Float? = null
        var plannedRegion: HumanGesturePlanner.SwipeRegion? = null
        var durationSpeed: String? = null
        var staged: LocalActionEvidence.ObservationLite? = null
        var validatedAfter: LocalActionEvidence.ObservationLite? = null

        val backendProbe = BackendProbe(successResult(), events)
        val service = service(
            backend = backendProbe.backend,
            planScroll = { direction, ratio, region ->
                plannedDirection = direction
                plannedRatio = ratio
                plannedRegion = region
                intArrayOf(11, 22, 33, 44, 123)
            },
            scrollDuration = { speed ->
                durationSpeed = speed
                456
            },
            captureObservation = { forceFresh ->
                events += "before:$forceFresh"
                before
            },
            captureSettledObservation = {
                events += "settled"
                settled
            },
            stage = {
                events += "stage"
                staged = it
            },
            validate = { toolName, _, actualBefore, actualAfter, _ ->
                events += "validate"
                assertSame(before, actualBefore)
                validatedAfter = actualAfter
                validation(toolName, actualBefore, actualAfter)
            },
            append = { baseResult, _ ->
                events += "append"
                assertEquals(ToolExecutionStatus.SUCCEEDED, baseResult.executionStatus)
                assertEquals(ToolEvidenceStatus.UNKNOWN, baseResult.evidenceStatus)
                ToolProtocolResult.success("published")
            },
            record = { events += "receipt" }
        )

        val result = service.scroll(
            """{"direction":"down","amount":"large","speed":"slow"}"""
        )

        assertEquals("published", result.outputText)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(ToolEvidenceStatus.NOT_REQUIRED, result.evidenceStatus)
        assertEquals("down", plannedDirection)
        assertEquals(0.75f, plannedRatio)
        assertEquals(null, plannedRegion)
        assertEquals("slow", durationSpeed)
        assertSame(settled, staged)
        assertSame(settled, validatedAfter)
        assertEquals(listOf(11, 22, 33, 44, 456), backendProbe.swipeArguments.single())
        assertEquals(
            listOf("before:false", "swipe", "receipt", "settled", "stage", "validate", "append"),
            events
        )
    }

    @Test
    fun failedSwipeDoesNotCaptureOrPublishAnAfterObservation() = runBlocking {
        val before = observation(100L)
        var settledCalls = 0
        var stagedCalls = 0
        var validationCalls = 0
        val backendProbe = BackendProbe(failureResult(), mutableListOf())
        val service = service(
            backend = backendProbe.backend,
            captureObservation = { before },
            captureSettledObservation = {
                settledCalls += 1
                error("settled observation must not be captured after failed injection")
            },
            stage = { stagedCalls += 1 },
            validate = { _, _, _, _, _ ->
                validationCalls += 1
                error("failed injection must not be validated")
            },
            append = { _, _ -> error("failed injection must not append validation") }
        )

        val result = service.swipe(
            """{"startX":100,"startY":200,"endX":300,"endY":400}"""
        )

        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertEquals("SWIPE_FAILED", result.diagnosticCode)
        assertTrue(result.outputText.contains("SWIPE_FAILED"))
        assertEquals(0, settledCalls)
        assertEquals(0, stagedCalls)
        assertEquals(0, validationCalls)
    }

    @Test
    fun failedSwipePropagatesBackendDiagnosticAndSkipsAfterObservation() = runBlocking {
        val before = observation(100L)
        var settledCalls = 0
        val backendProbe = BackendProbe(
            DeviceInputResult(
                success = false,
                message = "arbitrary backend text",
                diagnosticCode = "CHANNEL_DOWN"
            ),
            mutableListOf()
        )
        val service = service(
            backend = backendProbe.backend,
            captureObservation = { before },
            captureSettledObservation = {
                settledCalls += 1
                error("channel failure must not start settled observation")
            },
            stage = { error("channel failure must not stage an observation") },
            validate = { _, _, _, _, _ -> error("channel failure must not validate") },
            append = { _, _ -> error("channel failure must not append validation") }
        )

        val result = service.swipe(
            """{"startX":100,"startY":200,"endX":300,"endY":400}"""
        )

        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertEquals("CHANNEL_DOWN", result.diagnosticCode)
        assertTrue(result.outputText.contains("arbitrary backend text"))
        assertEquals(0, settledCalls)
    }

    @Test
    fun cancellationDuringSettledScrollObservationIsNotPublishedOrValidated() = runBlocking {
        val cancellation = CancellationException("stop during settle")
        val before = observation(10L)
        var settledCalls = 0
        var stagedCalls = 0
        var validationCalls = 0
        var appendCalls = 0
        val backendProbe = BackendProbe(successResult(), mutableListOf())
        val service = service(
            backend = backendProbe.backend,
            captureObservation = { before },
            captureSettledObservation = {
                settledCalls += 1
                throw cancellation
            },
            stage = { stagedCalls += 1 },
            validate = { _, _, _, _, _ ->
                validationCalls += 1
                error("cancellation must not validate an observation")
            },
            append = { _, _ ->
                appendCalls += 1
                error("cancellation must not publish validation")
            }
        )

        val thrown = runCatching {
            service.scroll("""{"direction":"up"}""")
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, settledCalls)
        assertEquals(0, stagedCalls)
        assertEquals(0, validationCalls)
        assertEquals(0, appendCalls)
    }

    private fun service(
        backend: DeviceBackend,
        planScroll: (String, Float, HumanGesturePlanner.SwipeRegion?) -> IntArray =
            { _, _, _ -> intArrayOf(1, 2, 3, 4, 100) },
        preflightScrollTarget: suspend (ModelCoordinateMapper.Point) -> ToolExecutionResult = { ToolProtocolResult.success("checked") },
        scrollDuration: (String) -> Int = { 100 },
        captureObservation: suspend (Boolean) -> LocalActionEvidence.ObservationLite,
        captureSettledObservation: suspend () -> LocalActionEvidence.ObservationLite,
        stage: (LocalActionEvidence.ObservationLite) -> Unit,
        validate: (
            String,
            String,
            LocalActionEvidence.ObservationLite,
            LocalActionEvidence.ObservationLite,
            Boolean
        ) -> LocalActionEvidence.ValidationResult,
        append: (ToolExecutionResult, LocalActionEvidence.ValidationResult?) -> ToolExecutionResult,
        record: (DeviceInputResult) -> Unit = {}
    ): TouchInputService = TouchInputService(
        deviceBackend = backend,
        planScroll = planScroll,
        preflightScrollTarget = preflightScrollTarget,
        scrollDuration = scrollDuration,
        describeTarget = { _, _ -> null },
        performVerifiedTap = { _, _, _, _, _, _ -> error("unexpected tap") },
        captureObservation = captureObservation,
        captureSettledObservation = captureSettledObservation,
        stagePostActionObservation = stage,
        validate = validate,
        appendValidation = append,
        recordExecutedActionResult = record
    )

    private fun observation(hash: Long): LocalActionEvidence.ObservationLite =
        LocalActionEvidence.ObservationLite(
            screenHash = hash,
            foregroundPackage = "com.example",
            observationId = "obs-$hash"
        )

    private fun validation(
        toolName: String,
        before: LocalActionEvidence.ObservationLite,
        after: LocalActionEvidence.ObservationLite
    ): LocalActionEvidence.ValidationResult = LocalActionEvidence.ValidationResult(
        toolName = toolName,
        progressState = LocalActionEvidence.ProgressState.RAW_CHANGED,
        reason = "settled frame",
        beforeHash = before.screenHash,
        afterHash = after.screenHash,
        foregroundPackage = after.foregroundPackage
    )

    private class BackendProbe(
        private val result: DeviceInputResult,
        private val events: MutableList<String>
    ) {
        val swipeArguments = mutableListOf<List<Int>>()

        val backend: DeviceBackend = Proxy.newProxyInstance(
            DeviceBackend::class.java.classLoader,
            arrayOf(DeviceBackend::class.java)
        ) { _, method, args ->
            when (method.name) {
                "swipe" -> {
                    events += "swipe"
                    swipeArguments += requireNotNull(args).take(5).map { (it as Number).toInt() }
                    result
                }
                else -> error("Unexpected DeviceBackend call: ${method.name}")
            }
        } as DeviceBackend
    }

    private fun successResult(): DeviceInputResult = DeviceInputResult(
        success = true,
        message = "swipe injected",
        actionEvent = ExecutedActionEvent.Swipe(1, 2, 3, 4, 275)
    )

    private fun failureResult(): DeviceInputResult = DeviceInputResult(
        success = false,
        message = "injection failed"
    )
}
