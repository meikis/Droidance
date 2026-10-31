// SPDX-FileCopyrightText: 2026 Ashin (YBBLab)
// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Production coordination and source selection; image transformation is a scripted device port. */
class ImportedImageSourceIntegrationTest {
    @Test fun importedModelBundleKeepsNativeCropParentActiveInTheFollowingRequest() = runBlocking {
        inspectImportedSource(protocolRepair = false)
    }

    @Test fun protocolRepairSendsTheRetainedModelRepresentationOnlyOnce() = runBlocking {
        inspectImportedSource(protocolRepair = true)
    }

    private suspend fun inspectImportedSource(protocolRepair: Boolean) {
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0)
        fixture.toolExecutor.definitionOverride = { name ->
            if (name == "workspace") com.droidance.core.model.llm.models.ToolDefinition(name, "Read working state", mapOf(
                "op" to com.droidance.core.model.llm.models.ToolParameter("string", "Operation", true),
                "path" to com.droidance.core.model.llm.models.ToolParameter("string", "Task path", true))) else null
        }
        val goal = "Inspect a retained image detail."
        val started = fixture.startTurn("imported-image-source", goal)
        val nativeBytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 4, 4)
        val modelBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 4)
        val cropBytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 2, 2)
        val native = fixture.blobStore.put(nativeBytes)
        val model = fixture.blobStore.put(modelBytes)
        val crop = fixture.blobStore.put(cropBytes)
        val imported = DetailAttachmentAddedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            attachmentId = "retained-image",
            role = DetailAttachmentRole.INPUT_OBSERVATION,
            contentRef = native.contentRef,
            contentHash = native.contentHash,
            sizeBytes = native.sizeBytes,
            mimeType = "image/png",
            width = 4,
            height = 4,
            observationId = "imported-native",
            modelContentRef = model.contentRef,
            modelSizeBytes = model.sizeBytes
        )
        var resolved: DetailAttachmentAddedEventPayload? = null
        fixture.toolExecutor.beforeLocalExecute = { call ->
            if (call.name == "android_read") {
                val sourceRef = Json.parseToJsonElement(call.arguments).jsonObject.getValue("source_ref").jsonPrimitive.content
                resolved = RetainedReviewSources.resolveImage(
                    fixture.eventStore.eventsForTurn(started.active.scope.turnId), started.active.scope, sourceRef)
                assertEquals(native.contentRef, requireNotNull(resolved).contentRef)
                assertArrayEquals(nativeBytes, fixture.blobStore.read(requireNotNull(resolved).contentRef, 100))
            }
        }
        fixture.toolExecutor.resultOverride = { call, _ -> when (call.name) {
            "session_read" -> ToolProtocolResult.success("Exact retained model image").copy(
                historicalAttachment = imported, observationId = imported.observationId, sourceRef = imported.observationId)
            "android_read" -> {
                val source = requireNotNull(resolved)
                val detail = DetailAttachmentAddedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    attachmentId = "retained-crop",
                    role = DetailAttachmentRole.RESULT,
                    contentRef = crop.contentRef,
                    contentHash = crop.contentHash,
                    sizeBytes = crop.sizeBytes,
                    mimeType = "image/png",
                    width = 2,
                    height = 2,
                    observationId = "imported-crop",
                    sourceObservationId = source.observationId,
                    sourceContentRef = source.contentRef,
                    sourceRegion = ImageSourceRegion(1, 1, 2, 2, 4, 4)
                )
                ToolProtocolResult.success("Retained source detail").copy(
                    historicalAttachment = detail, observationId = detail.observationId, sourceRef = detail.observationId)
            }
            else -> null
        } }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "import", "session_read",
            """{"mode":"artifact","ref":"${model.contentRef}"}"""))
        if (protocolRepair) fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "bad-path", "workspace",
            """{"op":"read","path":["work.md"]}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "crop", "android_read",
            """{"op":"crop","source_ref":"${model.contentRef}","left":1,"top":1,"right":3,"bottom":3}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "finish", "done",
            """{"status":"done","deliverables":[],"results":[{"text":"The retained source detail was inspected.","evidence_refs":["imported-crop"]}],"unresolved_requirements":[],"requirement_checks":[]}"""))
        val currentBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)
        val result = fixture.coordinator.coordinateTask(
taskGoal = goal,
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
toolProfile = started.profile,
mode = started.mode,
initialScreenRequired = true,
initialScreenFrame = currentBytes,
host = object : com.droidance.core.agent.TurnHost {
    override val waitIfPaused: suspend () -> Unit = {
                if (fixture.model.roundResponses.isEmpty()) fixture.model.nextStreamFailure = IllegalStateException("End script")
            }
}
)

        assertEquals("$result\n" + fixture.diagnostics.joinToString("\n"),
            if (protocolRepair) 4 else 3, fixture.model.recordedRequests.size)
        val afterImport = fixture.model.recordedRequests[1]
        assertEquals("The exact requested model representation must appear once", 1,
            afterImport.count { it.historicalImage && it.imageBase64 == encoded(modelBytes) })
        if (protocolRepair) {
            val repair = fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
                .map { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson).finishMetadata }[2]
            assertEquals("true", repair["protocol_repair_active"])
            assertEquals("false", repair["source_context_isolated"])
            assertEquals("The repair request must send its retained source once", 1,
                fixture.model.recordedRequests[2].count { it.historicalImage && it.imageBase64 == encoded(modelBytes) })
        }
        val afterCrop = fixture.model.recordedRequests.last()
        val parentIndex = afterCrop.indexOfFirst { it.historicalImage && it.imageBase64 == encoded(nativeBytes) }
        val cropIndex = afterCrop.indexOfFirst { it.historicalImage && it.imageBase64 == encoded(cropBytes) }
        assertTrue("The actual native parent must remain active\n" + afterCrop.joinToString("\n") {
            "${it.role} historical=${it.historicalImage} image=${it.imageBase64}\n${it.content}"
        } + "\n" + fixture.diagnostics.joinToString("\n"), parentIndex >= 0)
        assertTrue("The parent must precede its retained detail", cropIndex > parentIndex)
        assertEquals(1, afterCrop.count { !it.historicalImage && it.imageBase64 == encoded(currentBytes) })
        assertFalse(afterCrop.any { !it.historicalImage && it.imageBase64 in setOf(encoded(nativeBytes), encoded(modelBytes), encoded(cropBytes)) })
        assertEquals(listOf("session_read", "android_read", "done"), fixture.toolExecutor.localCalls.map { it.name })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        val saved = fixture.events(started).filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
            .map { Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson) }
        assertTrue(saved.any { it.contentRef == native.contentRef && it.modelContentRef == model.contentRef })
        assertTrue(saved.any { it.contentRef == crop.contentRef && it.sourceContentRef == native.contentRef })
    }

    private fun encoded(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
