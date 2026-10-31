// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.model.llm.*
import com.droidance.core.model.llm.models.*
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Real transport and production coordinator; only the physical device and tool execution are fixtures. */
class ImageInputRejectionIntegrationTest {
    @Test fun rejectedImageStopsBeforeRetryingWithoutPixelsDespiteRetainedMetadataAndLease() = runBlocking {
        assertImageFailureStops()
    }

    @Test fun imageEndpointRejectionStopsWhenOnlyUserAttachmentSuppliesTheImage() = runBlocking {
        assertImageFailureStops(routeUnavailable = true, currentFrame = false)
    }

    @Test fun aTaskAlreadyUsingTextCanStillExecuteItsGroundedScreenAction() = runBlocking {
        val operator = FakeModelClient(visionAvailable = false)
        operator.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "text-click", "screen_act",
            """{"actions":[{"op":"click","x":500,"y":500}]}"""))
        operator.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "stop", "done", STOP))
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = operator)
        fixture.toolExecutor.preparedObservationText = "<untrusted_screen_content>Continue @(500,500)</untrusted_screen_content>"
        val started = fixture.startTurn("configured-text", "Inspect the current screen")
        val result = fixture.coordinator.coordinateTask(taskGoal = "Inspect the current screen",
            additionalContext = "", durableTurn = started.active, turnContext = "",
            expectedBackend = started.expectedBackend, executionStrategy = started.executionStrategy,
            initialScreenRequired = true, initialScreenFrame = FRAME,
            toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "screen_act" })
        assertTrue(operator.recordedRequests.first().any { it.content.contains("Continue @(500,500)") })
        assertTrue(operator.recordedRequests.all { request -> request.all { it.imageBase64 == null && it.imageBase64List.isEmpty() } })
        assertTrue(result.failureReason != TurnFailureReason.MODEL_UNAVAILABLE)
    }

    @Test fun imageRejectionDuringCheckpointKeepsTheSameLocalStopWithoutAnExplanationRequest() = runBlocking {
        for (routeUnavailable in listOf(false, true)) {
            val requests = mutableListOf<JsonObject>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                val index = requests.lastIndex
                val failed = index == 2
                val body = when {
                    index < 2 -> toolResponse("read-$index", "android_read", """{"op":"screen"}""")
                    failed && routeUnavailable -> """{"error":{"code":404,"message":"No endpoints found that support image input"}}"""
                    failed -> """{"error":{"code":"unsupported_image_input"}}"""
                    else -> "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Stopped\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (failed) { if (routeUnavailable) 404 else 400 } else 200).message("fixture")
                    .body(body.toResponseBody((if (failed) "application/json" else "text/event-stream").toMediaType())).build()
            }.build()
            val router = LLMRouter(http, Configuration(), Usage(), Diagnostics())
            val session = router.createSession()
            val operator = object : ModelClient by router {
                override fun currentContextWindowTokens() = if (requests.size < 2) 1_000_000 else 128_000
            }
            try {
                val fixture = TaskCoordinatorTestFixture(modelClientOverride = operator, compactorModelOverride = router,
                    compactionChunkInputTokens = 4_000, retainedImageLimit = 1, stoppedReplyEnabled = true,
                    stoppedReplyReasons = setOf(TurnFailureReason.RUNTIME_EXCEPTION))
                var observation = 0L
                fixture.toolExecutor.resultOverride = { call, _ ->
                    if (call.name == "android_read") fixture.toolExecutor.stageObservation(FRAME,
                        "source-${++observation}", "[PURE_VLM_SCREEN]\nA screenshot is attached", observation)
                    else null
                }
                val started = fixture.startTurn("checkpoint-image-$routeUnavailable", "Inspect the source")
                repeat(6) { index -> fixture.recordClosedHistory(started.active.scope, "old-$index",
                    if (index == 0 || index == 5) "Earlier task state" else "OBSOLETE_HISTORY ".repeat(10_000)) }
                val result = fixture.coordinator.coordinateTask(taskGoal = "Inspect the source", additionalContext = "",
                    durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
                    executionStrategy = started.executionStrategy, initialScreenRequired = true,
                    initialScreenFrame = FRAME, toolProfile = started.profile, mode = started.mode)
                fixture.complete(started, result)
                assertTrue("The rejected request must be the actual image-bearing compaction call: ${fixture.diagnostics.takeLast(12)}",
                    requests.size >= 3 && requests[2].toString().contains("<history_data>") && requests[2].toString().contains("image_url"))
                assertEquals("A checkpoint image rejection must use the model failure lifecycle: $result",
                    TurnFailureReason.MODEL_UNAVAILABLE, result.failureReason)
                assertEquals("No stopped-task explanation should repeat or strip the images", 3, requests.size)
                assertEquals("termination_model_image_unavailable", result.runtimeFailureSummary)
                assertEquals(result.runtimeFailureSummary, RuntimeUiResultPolicy.failureSummary(result) { "wrong fallback" })
                assertFalse(fixture.events(started).any { it.eventKind == AgentEventKind.CONTEXT_CHECKPOINT })
                assertFalse(fixture.toolExecutor.deviceCalls.any { it.name == "screen_act" })
            } finally { session.close() }
        }
    }

    private suspend fun assertImageFailureStops(routeUnavailable: Boolean = false,
        currentFrame: Boolean = true) {
        val requests = mutableListOf<JsonObject>()
        val configuration = Configuration()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val index = requests.lastIndex
            val body = when (index) {
                0 -> if (routeUnavailable) """{"error":{"code":404,"message":"No endpoints found that support image input"}}"""
                    else """{"error":{"code":"unsupported_image_input","message":"Image input is unavailable"}}"""
                1 -> toolResponse("blind-tap", "screen_act", """{"actions":[{"op":"click","x":500,"y":500}]}""")
                else -> toolResponse("stop", "done", STOP)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (index == 0) { if (routeUnavailable) 404 else 400 } else 200).message("fixture")
                .body(body.toResponseBody((if (index == 0) "application/json" else "text/event-stream").toMediaType()))
                .build()
        }.build()
        val router = LLMRouter(http, configuration, Usage(), Diagnostics())
        val session = router.createSession()
        try {
            val fixture = TaskCoordinatorTestFixture(modelClientOverride = router, stoppedReplyEnabled = true)
            // This is a real success-receipt shape, not readable screen content. It must not justify an image-free retry.
            fixture.toolExecutor.preparedObservationText =
                "[PURE_VLM_SCREEN]\nA clean Android screenshot is attached to the next vision model call.\n" +
                    "Attached frame dimensions: 2x2 pixels (rendering metadata only)."
            val started = fixture.startTurn("image-rejected-$routeUnavailable", "Inspect the current screen")
            if (!currentFrame) fixture.turnLedger.recordUserSteer(started.active, "user-image", "Inspect this image",
                listOf(java.util.Base64.getEncoder().encodeToString(FRAME)))
            val result = fixture.coordinator.coordinateTask(taskGoal = "Inspect the current screen",
                additionalContext = "", durableTurn = started.active, turnContext = "",
                expectedBackend = started.expectedBackend, executionStrategy = started.executionStrategy,
                initialScreenRequired = currentFrame, initialScreenFrame = FRAME.takeIf { currentFrame },
                toolProfile = started.profile, mode = started.mode)
            fixture.complete(started, result)
            assertEquals("Rejected image was followed by GUI dispatch: ${fixture.toolExecutor.deviceCalls}",
                0, fixture.toolExecutor.deviceCalls.count { it.name == "screen_act" })
            assertEquals("No additional image-free model decision is permitted: $result\n${fixture.diagnostics.takeLast(10)}", 1, requests.size)
            assertTrue(requests.single().toString().contains("image_url"))
            assertEquals(0, configuration.fallbackAttempts)
            assertEquals(TurnTerminal.SYSTEM_ERROR, result.terminal)
            assertEquals(TurnFailureReason.MODEL_UNAVAILABLE, result.failureReason)
            assertEquals("termination_model_image_unavailable", result.runtimeFailureSummary)
            assertEquals(result.runtimeFailureSummary, RuntimeUiResultPolicy.failureSummary(result) { "wrong fallback" })
            val events = fixture.events(started)
            assertEquals(1, events.count { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED })
            assertEquals(1, events.count { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED })
            assertEquals(1, events.count { it.eventKind == AgentEventKind.TURN_COMPLETED })
            val metadata = Json.parseToJsonElement(events.single {
                it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED
            }.payloadJson).jsonObject.getValue("finishMetadata").jsonObject
            assertEquals(if (routeUnavailable) "IMAGE_INPUT_UNAVAILABLE" else "IMAGE_UNSUPPORTED",
                metadata.getValue("response_error_category").jsonPrimitive.content)
            assertEquals(if (routeUnavailable) "404" else "400",
                metadata.getValue("response_http_status").jsonPrimitive.content)
        } finally { session.close() }
    }

    private fun toolResponse(id: String, name: String, arguments: String): String {
        val json = buildJsonObject {
            putJsonArray("choices") { addJsonObject {
                put("index", 0)
                putJsonObject("delta") { putJsonArray("tool_calls") { addJsonObject {
                    put("index", 0); put("id", id); put("type", "function")
                    putJsonObject("function") { put("name", name); put("arguments", arguments) }
                } } }
                put("finish_reason", "tool_calls")
            } }
        }
        return "data: $json\n\ndata: [DONE]\n\n"
    }

    private class Configuration : TransportConfiguration {
        private val provider = ProviderConfig(id = "test", name = "test", apiType = ApiType.OPENAI_COMPATIBLE,
            transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS, baseUrl = "https://example.invalid/v1",
            apiKey = "test-key", defaultModel = "selected-model")
        var fallbackAttempts = 0
        override fun resolveProvider() = provider
        override fun getCurrentModel(config: ProviderConfig) = config.defaultModel
        override fun advanceToNextModel(config: ProviderConfig): String? { fallbackAttempts++; return "other-model" }
        override fun resetModelFallback(configId: String) = Unit
        override fun reasoningCapability(config: ProviderConfig, model: String) = ReasoningCapability()
        override fun recordReasoningRejected(config: ProviderConfig, model: String) = Unit
        override fun imageDetailCandidates(config: ProviderConfig, model: String) = listOf(ImageDetailOption.HIGH)
        override fun imageDetailCapabilityStatus(config: ProviderConfig, model: String) =
            ImageDetailCapabilityStatus(ImageDetailOption.HIGH, 1L, null, false)
        override fun recordImageDetailAccepted(config: ProviderConfig, model: String, detail: ImageDetailOption, returnedModel: String?) = Unit
        override fun recordImageDetailRejected(config: ProviderConfig, model: String, detail: ImageDetailOption) = Unit
    }

    private class Usage : TransportUsageRecorder {
        override fun recordRequest(provider: String, model: String, promptTokens: Long, completionTokens: Long,
            cachedInputTokens: Long, cacheFieldsReported: Boolean) = Unit
    }

    private class Diagnostics : TransportDiagnostics {
        override fun openExchange(scope: ModelDiagnosticScope?, provider: String, model: String, request: JsonObject) =
            object : TransportExchangeTrace {
                override fun response(status: Int, requestId: String?, retryAfter: String?) = Unit
                override fun timing(name: String, elapsedMs: Long) = Unit
                override fun line(value: String) = Unit
                override fun finish(error: Throwable?) = Unit
            }
        override fun requestStarted() = Unit
        override fun recordTokenUsage(inputTokens: Int, outputTokens: Int) = Unit
        override fun warning(tag: String, message: String) = Unit
        override fun debug(tag: String, message: String) = Unit
    }

    companion object {
        private val FRAME = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private const val STOP = """{"status":"need_more_info","deliverables":[],"results":[],"unresolved_requirements":["Source unavailable"],"requirement_checks":[]}"""
    }
}
