// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.ScreenBatchCaptures
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import kotlin.coroutines.coroutineContext

class BatchCaptureIntegrationTest {
    @Test fun trailingScreenshotDoesNotEvictTheStartingViewOfAScrollGroup() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("scroll-trailing-capture", "Collect the list")
        installOrdinaryScrolls(fixture)
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "scroll", "screen_act",
            """{"actions":[{"op":"scroll","direction":"down"},{"op":"screenshot"}]}"""))
        run(fixture, started)
        val request = fixture.model.recordedRequests[1]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals(setOf(encoded(0), encoded(1)), images.toSet())
        assertEquals(1, request.count { it.historicalImage && it.imageBase64 == encoded(0) })
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == encoded(1) })
    }

    @Test fun ordinaryScrollsCarryStartingAndPriorViewsWithoutRebindingTheirControls() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("ordinary-scroll-history", "Collect the list")
        installOrdinaryScrolls(fixture)
        repeat(3) { scroll(fixture) }
        run(fixture, started)
        assertTrue(fixture.diagnostics.joinToString("\n"), fixture.model.recordedRequests.size >= 4)
        val request = fixture.model.recordedRequests[3]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals((0..3).map(::encoded).toSet(), images.toSet())
        assertEquals(4, images.size)
        for (page in 0..2) assertTrue(request.any { it.historicalImage && it.imageBase64 == encoded(page) })
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == encoded(3) })
        assertEquals("scroll-page-3", fixture.evidenceLedger.latestGroundedObservation()?.id)
        assertEquals(3, fixture.toolExecutor.deviceCalls.size)
    }

    @Test fun ordinaryScrollHistoryUsesTheSharedImageBudget() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 1,
            runtimeThresholdsOverride = { it.copy(contextCaptureHistoryMaxImages = 2) })
        val started = fixture.startTurn("bounded-scroll-history", "Collect the list")
        installOrdinaryScrolls(fixture)
        repeat(3) { scroll(fixture) }
        run(fixture, started)
        assertTrue(fixture.diagnostics.joinToString("\n"), fixture.model.recordedRequests.size >= 4)
        val request = fixture.model.recordedRequests[3]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals(setOf(encoded(2), encoded(3)), images.toSet())
        assertEquals(2, images.size)
        assertTrue(request.any { it.content.contains("\"omitted_images\":2") })
    }

    @Test fun navigationEndsAutomaticScrollHistoryWithoutDeletingOriginals() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("scroll-navigation-boundary", "Collect the list and open another view")
        installOrdinaryScrolls(fixture)
        scroll(fixture)
        scroll(fixture)
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "navigate", "android_act",
            """{"op":"open","package_name":"example.destination"}"""))
        val scrollOverride = fixture.toolExecutor.beforeDeviceExecute
        fixture.toolExecutor.beforeDeviceExecute = { call ->
            if (call.name == "android_act") fixture.toolExecutor.stageObservation(bytes(9), "destination", "New view", 9, call.id)
            else scrollOverride(call)
        }
        run(fixture, started)
        assertTrue(fixture.diagnostics.joinToString("\n"), fixture.model.recordedRequests.size >= 4)
        val request = fixture.model.recordedRequests[3]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals(listOf(encoded(9)), images)
        val originals = fixture.events(started).filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
            .map { Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson) }
        assertTrue(originals.any { fixture.blobStore.read(it.contentRef, 100).contentEquals(bytes(1)) })
    }

    private fun installOrdinaryScrolls(fixture: TaskCoordinatorTestFixture) {
        fixture.toolExecutor.postActionCaptureTools = setOf("screen_act", "android_act")
        var page = 0
        fixture.toolExecutor.beforeDeviceExecute = { call ->
            if (call.name == "screen_act") {
                page++
                fixture.toolExecutor.stageObservation(bytes(page), "scroll-page-$page", "Viewport $page", page + 1L, call.id)
            }
        }
        fixture.toolExecutor.resultOverride = { call, _ ->
            ToolProtocolResult.success("Action delivered").takeIf { call.name in setOf("screen_act", "android_act") }
        }
    }

    private fun scroll(fixture: TaskCoordinatorTestFixture) {
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "scroll", "screen_act",
            """{"actions":[{"op":"scroll","direction":"down"}]}"""))
    }

    @Test fun successiveScreenReadsKeepPriorFactsAndOneCurrentActionFrame() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 1)
        val started = fixture.startTurn("ordinary-image-budget", "Read the current state")
        var index = 0
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "android_read") {
                index++
                fixture.toolExecutor.stageObservation("viewport-$index".toByteArray(), "viewport-$index", "Observed viewport $index")
            } else null
        }
        repeat(2) { round -> fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0,
            "read-$round", "android_read", """{"op":"screen"}""")) }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "finish", "done",
            """{"status":"done","results":[{"text":"Current state read"}],"unresolved_requirements":[]}"""))
        run(fixture, started)
        val request = fixture.model.recordedRequests[2]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        val prior = Base64.getEncoder().encodeToString("viewport-1".toByteArray())
        val current = Base64.getEncoder().encodeToString("viewport-2".toByteArray())
        assertEquals(2, images.size)
        assertTrue(request.any { it.historicalImage && it.imageBase64 == prior })
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == current })
        assertFalse(request.any { !it.historicalImage && it.imageBase64 == prior })
        assertTrue(request.any { it.content.contains("current_screen=true coordinate_space=normalized_0_1000") })
    }
    @Test fun disabledCaptureHistoryDoesNotBypassItsLimitThroughOrdinaryImageSelection() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 1,
            runtimeThresholdsOverride = { it.copy(contextCaptureHistoryMaxImages = 0) })
        val started = fixture.startTurn("bounded-prior-page", "Collect readable entries from the list")
        installCapture(fixture, 3)
        script(fixture)
        script(fixture)
        run(fixture, started)
        val request = fixture.model.recordedRequests[2]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertFalse(images.contains(encoded(3)))
        assertFalse(images.contains(encoded(1)))
        assertFalse(images.contains(encoded(2)))
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == encoded(6) })
    }
    @Test fun threePagesArriveInOneRequestWithFinalImageOnceAndOriginalSources() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("batch-pages", "Collect readable entries from the list")
        installCapture(fixture, 3)
        script(fixture)
        run(fixture, started)
        val request = fixture.model.recordedRequests[1]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals((1..3).map(::encoded), images.filter { it in (1..3).map(::encoded) })
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == encoded(3) })
        val result = batchResult(fixture, started)
        assertEquals(listOf(1, 3, 5), result.batchScreenshots.map { it.actionIndex })
        result.batchScreenshots.forEach { frame ->
            val ref = checkNotNull(frame.attachment.observationId)
            assertTrue(fixture.evidenceLedger.hasCompletionEvidence(ref))
            assertEquals(setOf(ref), fixture.evidenceLedger.completionImageSources(ref))
        }
        assertEquals("last-page-1", fixture.evidenceLedger.latestGroundedObservation()?.id)
        assertEquals(3L, result.metrics["batch_captured_images"])
    }

    @Test fun nextBatchCanOmitPriorGroupsWhenCarryoverIsDisabled() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0,
            runtimeThresholdsOverride = { it.copy(contextCaptureHistoryMaxImages = 0) })
        val started = fixture.startTurn("batch-incremental", "Collect readable entries from the list")
        installCapture(fixture, 3)
        script(fixture)
        script(fixture)
        run(fixture, started)
        val request = fixture.model.recordedRequests[2]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals((4..6).map(::encoded), images)
        assertTrue(request.joinToString("\n") { it.content }.contains("capture:1"))
        assertEquals(2, fixture.toolExecutor.deviceCalls.count { it.name == "screen_act" })
    }

    @Test fun nextCaptureGroupKeepsTheCompletePreviousGroupWithoutRecordWrites() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("batch-carryover", "Collect readable entries from the list")
        installCapture(fixture, 3)
        script(fixture)
        script(fixture)
        run(fixture, started)
        val request = fixture.model.recordedRequests[2]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals((1..6).map(::encoded).toSet(), images.toSet())
        assertEquals(6, images.size)
        for (page in 1..3) assertTrue(request.any { it.historicalImage && it.imageBase64 == encoded(page) })
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == encoded(6) })
        assertEquals(listOf("screen_act", "screen_act"), fixture.toolExecutor.deviceCalls.map { it.name })
    }

    @Test fun thirdCaptureGroupKeepsTheFirstPagesForOneCombinedAnalysis() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("batch-carryover-window", "Collect readable entries from the list")
        installCapture(fixture, 3)
        repeat(3) { script(fixture) }
        run(fixture, started)
        val request = fixture.model.recordedRequests[3]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals((1..9).map(::encoded).toSet(), images.toSet())
        assertEquals(9, images.size)
        for (page in 1..8) assertTrue(request.any { it.historicalImage && it.imageBase64 == encoded(page) })
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == encoded(9) })
    }

    @Test fun sourceReadKeepsTheWholeCaptureHistoryWithOneRepresentationPerImage() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("capture-history-read", "Analyze the captured list")
        installCapture(fixture, 3, modelRepresentations = true)
        val batchOverride = fixture.toolExecutor.resultOverride
        fixture.toolExecutor.resultOverride = { call, local ->
            if (call.name == "session_read") {
                val first = fixture.events(started).filter { it.eventKind == AgentEventKind.TOOL_FINISHED }
                    .map { Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result }
                    .first { it.batchScreenshots.isNotEmpty() }.batchScreenshots.first().attachment
                ToolProtocolResult.success("Retained source").copy(historicalAttachment = first)
            } else batchOverride?.invoke(call, local)
        }
        repeat(3) { script(fixture) }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "read", "session_read",
            """{"mode":"image","ref":"retained-first"}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "finish", "done",
            """{"status":"done","results":[{"text":"Combined analysis of the retained pages"}],"unresolved_requirements":[]}"""))
        run(fixture, started)
        assertTrue(fixture.diagnostics.joinToString("\n"), fixture.model.recordedRequests.size >= 5)
        val request = fixture.model.recordedRequests[4]
        val images = request.flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertEquals((1..9).map(::modelEncoded).toSet(), images.toSet())
        assertEquals(9, images.size)
        assertFalse(images.contains(encoded(1)))
        assertEquals(1, request.count { !it.historicalImage && it.imageBase64 == modelEncoded(9) })
        assertEquals(3, fixture.toolExecutor.deviceCalls.size)
        assertEquals(listOf("session_read", "done"), fixture.toolExecutor.localCalls.map { it.name })
    }

    @Test fun failedSuffixStillDeliversCapturedPagesAndDurableReferences() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("batch-failure", "Collect readable entries from the list")
        installCapture(fixture, 2) { throw IllegalStateException("Later scroll failed") }
        script(fixture)
        run(fixture, started)
        val result = batchResult(fixture, started)
        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertEquals(2, result.batchScreenshots.size)
        val images = fixture.model.recordedRequests[1].flatMap { listOfNotNull(it.imageBase64) + it.imageBase64List }
        assertTrue(images.containsAll((1..2).map(::encoded)))
        assertSaved(fixture, started, result)
    }

    @Test fun cancellationSettlesCapturedPrefixWithoutPretendingTheBatchSucceeded() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        val started = fixture.startTurn("batch-cancel", "Collect readable entries from the list")
        installCapture(fixture, 2) { throw CancellationException("User canceled") }
        script(fixture)
        try { run(fixture, started) } catch (_: CancellationException) { }
        val result = batchResult(fixture, started)
        assertEquals(ToolExecutionStatus.CANCELED, result.executionStatus)
        assertEquals(2, result.batchScreenshots.size)
        assertSaved(fixture, started, result)
    }

    private fun installCapture(fixture: TaskCoordinatorTestFixture, pages: Int,
        modelRepresentations: Boolean = false, after: () -> Unit = {}) {
        fixture.toolExecutor.postActionCaptureTools = setOf("screen_act")
        var batch = 0
        var lastPage = pages
        fixture.toolExecutor.beforeDeviceExecute = { call ->
            if (call.name == "screen_act") {
                batch++
                val captures = checkNotNull(coroutineContext[ScreenBatchCaptures])
                for (page in 1..pages) {
                    lastPage = (batch - 1) * pages + page
                    assertTrue(captures.save(page * 2 - 1, page.toLong(), "execution:fixture", "fixture-backend",
                        41, 0, bytes(lastPage), 2, 2,
                        modelImageBytes = modelBytes(lastPage).takeIf { modelRepresentations }).isVerifiedSuccess)
                }
                fixture.toolExecutor.stageObservation(if (modelRepresentations) modelBytes(lastPage) else bytes(lastPage),
                    "last-page-$batch", "Latest collected page", batch * 5L, call.id)
                after()
            } else if (call.name == "android_read") {
                fixture.toolExecutor.stageObservation(bytes(lastPage), "recovered-page", "Recovered screen", 6, call.id)
            }
        }
        fixture.toolExecutor.resultOverride = { call, _ ->
            ToolProtocolResult.success("Batch delivered").takeIf { call.name == "screen_act" }
        }
    }

    private fun script(fixture: TaskCoordinatorTestFixture) {
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "collect", "screen_act",
            """{"actions":[{"op":"screenshot"},{"op":"scroll","direction":"down"},{"op":"screenshot"},{"op":"scroll","direction":"down"},{"op":"screenshot"}]}"""))
    }

    private suspend fun run(fixture: TaskCoordinatorTestFixture, started: TaskCoordinatorTestFixture.StartedTurn) {
        fixture.coordinator.coordinateTask(
taskGoal = "Collect readable entries from the list",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
initialScreenRequired = true,
initialScreenFrame = bytes(0),
host = object : com.droidance.core.agent.TurnHost {
    override val waitIfPaused: suspend () -> Unit = {
                if (fixture.model.roundResponses.isEmpty()) fixture.model.nextStreamFailure = IllegalStateException("End script")
            }
}
)
    }

    private fun batchResult(fixture: TaskCoordinatorTestFixture, started: TaskCoordinatorTestFixture.StartedTurn) =
        fixture.events(started).filter { it.eventKind == AgentEventKind.TOOL_FINISHED }
            .map { Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result }
            .single { it.batchScreenshots.isNotEmpty() }

    private fun assertSaved(fixture: TaskCoordinatorTestFixture, started: TaskCoordinatorTestFixture.StartedTurn, result: ToolExecutionResult) {
        val attachments = fixture.events(started).filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
            .map { Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson) }
        result.batchScreenshots.forEach { frame ->
            assertTrue(attachments.contains(frame.attachment))
            assertArrayEquals(bytes((frame.actionIndex + 1) / 2), fixture.blobStore.read(frame.attachment.contentRef, 100))
        }
    }

    private fun bytes(page: Int) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), page.toByte()) +
        ByteArray(24) { (page * 31 + it * 17).toByte() }
    private fun encoded(page: Int) = Base64.getEncoder().encodeToString(bytes(page))
    private fun modelBytes(page: Int) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 100.toByte(), page.toByte())
    private fun modelEncoded(page: Int) = Base64.getEncoder().encodeToString(modelBytes(page))
}
