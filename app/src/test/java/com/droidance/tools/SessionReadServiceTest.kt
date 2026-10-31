// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionBackendKind
import com.droidance.core.agent.blob.BlobMetadata
import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReadServiceTest {
    @Test fun modelArtifactReadsItsExactBytesAndKeepsTheNativeSourceLineage() = runBlocking {
        val root = Files.createTempDirectory("model-artifact-source").toFile()
        try {
            val blobs = FileBlobStore(root)
            val native = blobs.put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val modelBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2)
            val model = blobs.put(modelBytes)
            val source = attachment(13, "read-call", "original", "original-observation",
                DetailAttachmentRole.RESULT, native)
            val paired = source.copy(payloadJson = Json.encodeToString(
                Json.decodeFromString<DetailAttachmentAddedEventPayload>(source.payloadJson)
                    .copy(modelContentRef = model.contentRef, modelSizeBytes = model.sizeBytes)))
            val history = finishedStep(10, "read-call", "android_read", observationId = "original-observation") + paired
            val service = SessionReadService(eventStore(history), blobs)
            val result = service.read(THREAD, TURN, """{"mode":"artifact","ref":"${model.contentRef}"}""", true)
            assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
            val returned = requireNotNull(result.historicalAttachment)
            assertEquals(native.contentRef, returned.contentRef)
            assertEquals(native.sizeBytes, returned.sizeBytes)
            assertEquals("image/png", returned.mimeType)
            assertNull(returned.sourceContentRef)
            assertNull(returned.sourceObservationId)
            assertEquals(model.contentRef, returned.modelContentRef)
            assertEquals(model.sizeBytes, returned.modelSizeBytes)
            assertFalse(returned.observationId == "original-observation")
            assertTrue(blobs.read(requireNotNull(returned.modelContentRef), requireNotNull(returned.modelSizeBytes)).contentEquals(modelBytes))
            val receipt = Json.parseToJsonElement(result.outputText).jsonObject
            assertEquals(model.contentRef, receipt.getValue("displayed_image_ref").jsonPrimitive.content)
            assertEquals("image/jpeg", receipt.getValue("displayed_mime_type").jsonPrimitive.content)
            assertEquals(native.contentRef, receipt.getValue("native_source_ref").jsonPrimitive.content)
            val raw = service.read(THREAD, TURN, """{"mode":"artifact","ref":"${native.contentRef}"}""", true)
            assertEquals(native.contentRef, raw.historicalAttachment?.contentRef)
            assertEquals("image/png", raw.historicalAttachment?.mimeType)
            assertNull(raw.historicalAttachment?.modelContentRef)
        } finally { root.deleteRecursively() }
    }
    @Test fun immutableShellDataIsReadableAfterCompactionWithoutCrossTaskAccess() = runBlocking {
        val root = Files.createTempDirectory("shell-data-source").toFile()
        try {
            val blobs = FileBlobStore(root)
            val text = "{\"stdout\":\"retained shell output\",\"exit_code\":0}"
            val ref = blobs.put(text.toByteArray()).contentRef
            val history = finishedStep(10, "shell-call", "shell", outputText = text,
                effect = ToolEffect.STATE_CHANGING).map { event ->
                if (event.eventKind != AgentEventKind.TOOL_FINISHED) event else {
                    val payload = Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson)
                    event.copy(payloadJson = Json.encodeToString(payload.copy(result = payload.result.copy(dataRef = ref, sourceRef = ref))))
                }
            }
            val service = SessionReadService(eventStore(history), blobs)
            val result = service.read(THREAD, TURN, """{"mode":"artifact","ref":"$ref"}""", false)
            assertTrue(result.isVerifiedSuccess)
            assertEquals(text, Json.parseToJsonElement(result.outputText).jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("HISTORY_REFERENCE_NOT_FOUND", service.read(THREAD, OTHER_TURN,
                """{"mode":"artifact","ref":"$ref"}""", false).diagnosticCode)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun longNativeReceiptCanBeRecoveredThroughEverySourcePageWithoutCrossTaskLeakage() = runBlocking {
        val root = Files.createTempDirectory("native-receipt-pages").toFile()
        try {
            val stdout = (1..3000).joinToString("\n") { "native row $it: retained \"value\"" } +
                "\nlate field: 完整来源"
            val output = buildJsonObject { put("stdout", stdout); put("truncated", false) }.toString()
            val history = finishedStep(10, "native-call", "android_read", outputText = output,
                effect = ToolEffect.READ_ONLY)
            val service = SessionReadService(eventStore(history), FileBlobStore(root))
            val steps = SessionEvidenceProjector.steps(history, THREAD, TURN)
            val ref = steps.single().event.eventId
            val index = SessionEvidenceProjector.sourceIndex(history, THREAD, TURN, limit = 8,
                includeTextContent = true, preferredRefs = setOf("native-call"))
            val source = index.getValue("sources").jsonArray.single().jsonObject
            assertTrue(source.getValue("original_text_truncated").jsonPrimitive.boolean)
            assertEquals(ref, source.getValue("read_arguments").jsonObject.getValue("ref").jsonPrimitive.content)
            assertEquals("HISTORY_STEP_EXCEEDS_BUDGET", service.read(THREAD, TURN,
                """{"mode":"step","ref":"$ref"}""", false).diagnosticCode)
            val reconstructed = StringBuilder()
            var offset = 0
            do {
                val page = service.read(THREAD, TURN,
                    """{"mode":"artifact","ref":"$ref","offset":$offset}""", false)
                assertEquals(ToolExecutionStatus.SUCCEEDED, page.executionStatus)
                val value = Json.parseToJsonElement(page.outputText).jsonObject
                val text = value.getValue("text").jsonPrimitive.content
                assertTrue(text.length <= 12_000)
                reconstructed.append(text)
                val next = value.getValue("next_offset").jsonPrimitive.content.toInt()
                assertTrue(next > offset)
                offset = next
            } while (value.getValue("has_more").jsonPrimitive.boolean)
            val retained = Json.parseToJsonElement(reconstructed.toString()).jsonObject
                .getValue("result").jsonPrimitive.content
            assertEquals(output, retained)
            assertEquals(stdout, Json.parseToJsonElement(retained).jsonObject.getValue("stdout").jsonPrimitive.content)
            for ((thread, turn) in listOf(OTHER_THREAD to TURN, THREAD to OTHER_TURN)) {
                assertEquals("HISTORY_REFERENCE_NOT_FOUND", service.read(thread, turn,
                    """{"mode":"artifact","ref":"$ref"}""", false).diagnosticCode)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun structuredReceiptWithoutOutputCanBeRecoveredFromItsOriginalEventPages() = runBlocking {
        val root = Files.createTempDirectory("structured-receipt-pages").toFile()
        try {
            val data = buildJsonObject { put("detail", "quoted \"value\"\n中文".repeat(8_000) + "TAIL_RETAINED") }
            val history = finishedStep(10, "structured-call", "android_read", outputText = "",
                effect = ToolEffect.READ_ONLY, data = data)
            val step = SessionEvidenceProjector.steps(history, THREAD, TURN).single()
            val expected = Json.encodeToString(ToolExecutionResult.serializer(), step.result)
            val ref = step.event.eventId
            val service = SessionReadService(eventStore(history), FileBlobStore(root))
            val source = SessionEvidenceProjector.sourceIndex(history, THREAD, TURN, 8,
                includeTextContent = true, preferredRefs = setOf(ref))
                .getValue("sources").jsonArray.single().jsonObject
            assertTrue(source.getValue("original_text_truncated").jsonPrimitive.boolean)
            assertEquals(expected.length, source.getValue("original_text_chars").jsonPrimitive.content.toInt())
            assertEquals(ref, source.getValue("read_arguments").jsonObject.getValue("ref").jsonPrimitive.content)
            val reconstructed = StringBuilder()
            var offset = 0
            do {
                val page = service.read(THREAD, TURN,
                    """{"mode":"artifact","ref":"$ref","offset":$offset}""", false)
                assertEquals(ToolExecutionStatus.SUCCEEDED, page.executionStatus)
                assertTrue(page.outputText.length <= com.droidance.core.agent.context.ToolResultContextRenderer.MAX_INLINE_CHARS)
                val value = Json.parseToJsonElement(page.outputText).jsonObject
                reconstructed.append(value.getValue("text").jsonPrimitive.content)
                val next = value.getValue("next_offset").jsonPrimitive.content.toInt()
                assertTrue(next > offset)
                offset = next
            } while (value.getValue("has_more").jsonPrimitive.boolean)
            val retained = Json.parseToJsonElement(reconstructed.toString()).jsonObject
                .getValue("result").jsonPrimitive.content
            assertEquals(expected, retained)
            assertEquals(data, Json.parseToJsonElement(retained).jsonObject.getValue("data"))
            assertEquals("HISTORY_REFERENCE_NOT_FOUND", service.read(OTHER_THREAD, TURN,
                """{"mode":"artifact","ref":"$ref"}""", false).diagnosticCode)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun workspaceSourcesCanBeReadFromExplicitSameConversationHistoryOnly(): Unit = runBlocking {
        val root = Files.createTempDirectory("workspace-sources").toFile()
        try {
            val blobs = FileBlobStore(root)
            val text = "保留的任务资料"
            val blob = blobs.put(text.toByteArray())
            val version = com.droidance.core.agent.workspace.WorkspaceFileVersion(
                "notes.md", blob.contentRef, blob.sizeBytes, text.length, "request")
            val committed = event(10, AgentEventKind.WORKSPACE_FILE_WRITTEN, "file", Json.encodeToString(version), null, null)
            val service = SessionReadService(eventStore(listOf(committed)), blobs)
            val args = """{"mode":"artifact","ref":"${blob.contentRef}","source_turn_id":"$TURN"}"""
            val read = service.read(THREAD, OTHER_TURN, args, false)
            assertEquals(text, Json.parseToJsonElement(read.outputText).jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("HISTORY_SOURCE_NOT_IN_CONVERSATION", service.read(OTHER_THREAD, OTHER_TURN, args, false).diagnosticCode)
            assertEquals("HISTORY_REFERENCE_NOT_FOUND", service.read(THREAD, OTHER_TURN,
                """{"mode":"artifact","ref":"${blob.contentRef}"}""", false).diagnosticCode)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun programArtifactsRequireTaskManifestAndRetrieveEveryPage() = runBlocking {
        val root = Files.createTempDirectory("program-artifacts").toFile()
        try {
            val blobs = FileBlobStore(root)
            val text = "中".repeat(16001)
            val ref = blobs.put(text.toByteArray()).contentRef
            val hidden = blobs.put("unrelated credential".toByteArray()).contentRef
            val payload = """{"phase":"snapshot","data_ref":"$ref","data":{"untrusted_ref":"$hidden"},"artifacts":[{"content_ref":"$ref","mime_type":"text/plain"}]}"""
            val ev = event(10, AgentEventKind.PROGRAM_EXECUTION, "program", payload, null, null)
            val service = SessionReadService(eventStore(listOf(ev)), blobs)
            val first = service.read(THREAD, TURN, """{"mode":"artifact","ref":"$ref"}""", false)
            val next = service.read(THREAD, TURN, """{"mode":"artifact","ref":"$ref","offset":12000}""", false)
            assertEquals(text, Json.parseToJsonElement(first.outputText).jsonObject.getValue("text").jsonPrimitive.content +
                Json.parseToJsonElement(next.outputText).jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("HISTORY_REFERENCE_NOT_FOUND", service.read(THREAD, TURN,
                """{"mode":"artifact","ref":"$hidden"}""", false).diagnosticCode)
            assertEquals("HISTORY_REFERENCE_NOT_FOUND", service.read(OTHER_THREAD, TURN,
                """{"mode":"artifact","ref":"$ref"}""", false).diagnosticCode)
            assertEquals("HISTORY_REFERENCE_NOT_FOUND", service.read(THREAD, OTHER_TURN,
                """{"mode":"artifact","ref":"$ref"}""", false).diagnosticCode)
            val page = service.read(THREAD, TURN, """{"mode":"programs","limit":1}""", false)
            assertEquals(1, Json.parseToJsonElement(page.outputText).jsonObject.getValue("events").jsonArray.size)
            assertNull(ProgramEvidence.manifest(listOf(ev.copy(eventKind = AgentEventKind.USER_MESSAGE)), THREAD, TURN, ref))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun listAndStepReadCompleteSettledHistoryWithoutCrossThreadOrTurnLeakage() = runBlocking {
        val root = Files.createTempDirectory("session-read-list").toFile()
        try {
            val history = finishedStep(
                baseSequence = 10,
                callId = "current-finished",
                tool = "observe_screen",
                observationId = "current-observation"
            ) + rejectedStep(
                baseSequence = 30,
                callId = "current-rejected",
                tool = "tap",
                diagnostic = "ARGUMENT_SCHEMA_MISMATCH"
            ) + finishedStep(
                baseSequence = 50,
                callId = "other-turn",
                tool = "observe_screen"
            ).map { it.copy(turnId = OTHER_TURN) } + finishedStep(
                baseSequence = 70,
                callId = "other-thread",
                tool = "observe_screen"
            ).map { it.copy(threadId = OTHER_THREAD) }
            val service = SessionReadService(eventStore(history), FileBlobStore(root))

            val list = service.read(THREAD, TURN, "{\"mode\":\"list\",\"limit\":8}", imagesSupported = true)
            val listJson = Json.parseToJsonElement(list.outputText).jsonObject
            assertEquals(ToolExecutionStatus.SUCCEEDED, list.executionStatus)
            assertEquals(2, listJson.getValue("steps").jsonArray.size)
            assertEquals("current-finished", listJson.getValue("steps").jsonArray[0].jsonObject.getValue("call_ref").jsonPrimitive.content)
            assertEquals("TOOL_CALL_REJECTED", listJson.getValue("steps").jsonArray[1].jsonObject.getValue("settlement_kind").jsonPrimitive.content)
            assertEquals("ARGUMENT_SCHEMA_MISMATCH", listJson.getValue("steps").jsonArray[1].jsonObject.getValue("diagnostic_code").jsonPrimitive.content)

            val step = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"step\",\"ref\":\"current-observation\"}",
                imagesSupported = true
            )
            assertEquals(ToolExecutionStatus.SUCCEEDED, step.executionStatus)
            assertTrue(step.outputText.contains("current-observation"))
            assertTrue(step.outputText.contains("observe_screen"))
            assertFalse(step.outputText.contains("other-turn"))
            assertFalse(step.outputText.contains("other-thread"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sensitiveStepReadRedactsArgumentsAndResultButKeepsAttachmentHash() = runBlocking {
        val root = Files.createTempDirectory("session-read-sensitive").toFile()
        try {
            val history = finishedStep(
                baseSequence = 10,
                callId = "sensitive-call",
                tool = "type_text",
                argumentsJson = "{\"password\":\"secret-value\"}",
                outputText = "echoed secret-value",
                sensitiveFieldPaths = listOf("password")
            ) + attachment(
                sequence = 13,
                callId = "sensitive-call",
                attachmentId = "sensitive-attachment",
                observationId = "sensitive-observation",
                role = DetailAttachmentRole.RESULT,
                metadata = BlobMetadata(
                    contentRef = "sha256:${"a".repeat(64)}",
                    contentHash = "a".repeat(64),
                    sizeBytes = 12
                )
            )
            val service = SessionReadService(eventStore(history), FileBlobStore(root))

            val result = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"step\",\"ref\":\"sensitive-call\"}",
                imagesSupported = true
            )

            assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
            assertTrue(result.outputText.contains("content_redacted"))
            assertFalse(result.outputText.contains("secret-value"))
            assertTrue(result.outputText.contains("sensitive-attachment"))
            assertTrue(result.outputText.contains("${"a".repeat(64)}"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun imageReadLoadsExactBlobAndReturnsHistoricalHashAndAttachmentMetadata() = runBlocking {
        val root = Files.createTempDirectory("session-read-image").toFile()
        try {
            val blobs = FileBlobStore(root)
            val bytes = "historical-image-bytes".toByteArray()
            val metadata = blobs.put(bytes)
            val history = finishedStep(
                baseSequence = 10,
                callId = "image-call",
                tool = "observe_screen",
                observationId = "image-observation"
            ) + attachment(
                sequence = 13,
                callId = "image-call",
                attachmentId = "image-attachment",
                observationId = "image-observation",
                role = DetailAttachmentRole.RESULT,
                metadata = metadata
            )
            val service = SessionReadService(eventStore(history), blobs)

            val result = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"image\",\"ref\":\"image-attachment\",\"role\":\"post\"}",
                imagesSupported = true
            )
            val output = Json.parseToJsonElement(result.outputText).jsonObject

            assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
            assertEquals(true, output.getValue("historical").jsonPrimitive.boolean)
            assertEquals(false, output.getValue("current_screen").jsonPrimitive.boolean)
            assertEquals(metadata.contentHash, output.getValue("sha256").jsonPrimitive.content)
            assertEquals(metadata.contentRef, output.getValue("content_ref").jsonPrimitive.content)
            assertEquals("image-attachment", output.getValue("attachment_ref").jsonPrimitive.content)
            assertEquals("image-attachment", result.historicalAttachment?.attachmentId)
            assertEquals(metadata.contentHash, result.historicalAttachment?.contentHash)
            assertFalse(result.outputText.contains(java.util.Base64.getEncoder().encodeToString(bytes)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun specific_non_image_or_result_observation_ref_cannot_fallback_to_another_image() = runBlocking {
        val root = Files.createTempDirectory("session-read-exact-image-ref").toFile()
        try {
            val blobs = FileBlobStore(root)
            val pre = blobs.put("pre-image".toByteArray())
            val post = blobs.put("post-image".toByteArray())
            val receipt = blobs.put("text-receipt".toByteArray())
            val history = finishedStep(
                baseSequence = 10,
                callId = "mixed-call",
                tool = "observe_screen",
                observationId = "text-result-observation"
            ) + attachment(
                sequence = 13,
                callId = "mixed-call",
                attachmentId = "pre-attachment",
                observationId = "pre-observation",
                role = DetailAttachmentRole.INPUT_OBSERVATION,
                metadata = pre
            ) + attachment(
                sequence = 14,
                callId = "mixed-call",
                attachmentId = "post-attachment",
                observationId = "post-observation",
                role = DetailAttachmentRole.RESULT,
                metadata = post
            ) + attachment(
                sequence = 15,
                callId = "mixed-call",
                attachmentId = "text-receipt",
                observationId = "text-receipt-observation",
                role = DetailAttachmentRole.RESULT,
                metadata = receipt,
                mimeType = "text/plain"
            )
            val service = SessionReadService(eventStore(history), blobs)

            val resultObservation = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"image\",\"ref\":\"text-result-observation\",\"role\":\"pre\"}",
                imagesSupported = true
            )
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, resultObservation.executionStatus)
            assertEquals("HISTORY_IMAGE_NOT_FOUND", resultObservation.diagnosticCode)

            val nonImageAttachment = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"image\",\"ref\":\"text-receipt\",\"role\":\"pre\"}",
                imagesSupported = true
            )
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, nonImageAttachment.executionStatus)
            assertEquals("HISTORY_IMAGE_NOT_FOUND", nonImageAttachment.diagnosticCode)
            assertNull(nonImageAttachment.historicalAttachment)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingImageWrongReferenceAndNoImageCapabilityReturnExplicitFailures() = runBlocking {
        val root = Files.createTempDirectory("session-read-failures").toFile()
        try {
            val missingMetadata = BlobMetadata(
                contentRef = "sha256:${"b".repeat(64)}",
                contentHash = "b".repeat(64),
                sizeBytes = 12
            )
            val history = finishedStep(
                baseSequence = 10,
                callId = "missing-image-call",
                tool = "observe_screen",
                observationId = "missing-image-observation"
            ) + attachment(
                sequence = 13,
                callId = "missing-image-call",
                attachmentId = "missing-image-attachment",
                observationId = "missing-image-observation",
                role = DetailAttachmentRole.INPUT_OBSERVATION,
                metadata = missingMetadata
            )
            val service = SessionReadService(eventStore(history), FileBlobStore(root))

            val missing = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"image\",\"ref\":\"missing-image-attachment\",\"role\":\"pre\"}",
                imagesSupported = true
            )
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, missing.executionStatus)
            assertEquals("HISTORY_IMAGE_UNAVAILABLE", missing.diagnosticCode)
            assertNull(missing.historicalAttachment)

            val wrongRef = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"image\",\"ref\":\"does-not-exist\",\"role\":\"pre\"}",
                imagesSupported = true
            )
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, wrongRef.executionStatus)
            assertEquals("HISTORY_REFERENCE_NOT_FOUND", wrongRef.diagnosticCode)

            val unsupported = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"image\",\"ref\":\"missing-image-attachment\",\"role\":\"pre\"}",
                imagesSupported = false
            )
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, unsupported.executionStatus)
            assertEquals("HISTORY_IMAGE_UNSUPPORTED", unsupported.diagnosticCode)
            assertNull(unsupported.historicalAttachment)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedAndUnsupportedArgumentsAreStructuredInsteadOfThrowing() = runBlocking {
        val root = Files.createTempDirectory("session-read-arguments").toFile()
        try {
            val service = SessionReadService(eventStore(emptyList()), FileBlobStore(root))

            val malformed = service.read(THREAD, TURN, "not-json", imagesSupported = true)
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, malformed.executionStatus)
            assertEquals("ARGUMENT_SCHEMA_MISMATCH", malformed.diagnosticCode)

            val badMode = service.read(THREAD, TURN, "{\"mode\":\"unsupported\"}", imagesSupported = true)
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, badMode.executionStatus)
            assertEquals("ARGUMENT_SCHEMA_MISMATCH", badMode.diagnosticCode)

            val badLimit = service.read(THREAD, TURN, "{\"mode\":\"list\",\"limit\":0}", imagesSupported = true)
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, badLimit.executionStatus)
            assertEquals("ARGUMENT_SCHEMA_MISMATCH", badLimit.diagnosticCode)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitLatestImageReadUsesLatestAttachmentEvenWhenContentIsSharedWithOlderStep() = runBlocking {
        val root = Files.createTempDirectory("session-read-latest").toFile()
        try {
            val blobs = FileBlobStore(root)
            val metadata = blobs.put("shared-image".toByteArray())
            val old = finishedStep(
                baseSequence = 10,
                callId = "old-call",
                tool = "observe_screen",
                observationId = "old-observation"
            ) + attachment(
                sequence = 13,
                callId = "old-call",
                attachmentId = "old-attachment",
                observationId = "old-observation",
                role = DetailAttachmentRole.RESULT,
                metadata = metadata
            )
            val latest = finishedStep(
                baseSequence = 30,
                callId = "latest-call",
                tool = "observe_screen",
                observationId = "latest-observation"
            ) + attachment(
                sequence = 33,
                callId = "latest-call",
                attachmentId = "latest-attachment",
                observationId = "latest-observation",
                role = DetailAttachmentRole.RESULT,
                metadata = metadata
            )
            val service = SessionReadService(eventStore(old + latest), blobs)

            val result = service.read(
                THREAD,
                TURN,
                "{\"mode\":\"image\",\"ref\":\"latest-observation\",\"role\":\"post\"}",
                imagesSupported = true
            )

            assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
            assertEquals("latest-attachment", result.historicalAttachment?.attachmentId)
            assertTrue(result.outputText.contains("latest-call"))
            assertFalse(result.outputText.contains("old-call"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun eventStore(history: List<AgentEventEnvelope>): AgentEventStore =
        Proxy.newProxyInstance(
            AgentEventStore::class.java.classLoader,
            arrayOf(AgentEventStore::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "eventsForTurn" -> history
                else -> error("Unexpected EventStore method: ${method.name}")
            }
        } as AgentEventStore

    private fun finishedStep(
        baseSequence: Long,
        callId: String,
        tool: String,
        observationId: String? = null,
        argumentsJson: String = "{}",
        outputText: String = "result",
        sensitiveFieldPaths: List<String> = emptyList(),
        effect: ToolEffect = ToolEffect.STATE_CHANGING,
        data: kotlinx.serialization.json.JsonElement? = null
    ): List<AgentEventEnvelope> {
        val result = ToolExecutionResult(
            outputText = outputText,
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.CAPTURED,
            observationId = observationId,
            data = data
        )
        return listOf(
            declaration(baseSequence, callId, tool, argumentsJson, sensitiveFieldPaths, effect),
            event(
                sequence = baseSequence + 1,
                kind = AgentEventKind.TOOL_FINISHED,
                callId = callId,
                payloadJson = Json.encodeToString(ToolFinishedEventPayload(1, result, "round-detail")),
                attemptId = "attempt-1",
                executionId = "execution-1"
            )
        )
    }

    private fun rejectedStep(
        baseSequence: Long,
        callId: String,
        tool: String,
        diagnostic: String
    ): List<AgentEventEnvelope> = listOf(
        declaration(baseSequence, callId, tool),
        event(
            sequence = baseSequence + 1,
            kind = AgentEventKind.TOOL_CALL_REJECTED,
            callId = callId,
            payloadJson = Json.encodeToString(ToolCallRejectedEventPayload(
                contractVersion = 1,
                result = ToolExecutionResult(
                    outputText = "rejected",
                    executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                    diagnosticCode = diagnostic
                ),
                candidateCapabilitySnapshotRef = null,
                candidateCapabilitySnapshotHash = null,
                roundDetailRef = "round-detail"
            )),
            attemptId = "attempt-1",
            executionId = "execution-1"
        )
    )

    private fun attachment(
        sequence: Long,
        callId: String,
        attachmentId: String,
        observationId: String,
        role: DetailAttachmentRole,
        metadata: BlobMetadata,
        mimeType: String = "image/png"
    ): AgentEventEnvelope = event(
        sequence = sequence,
        kind = AgentEventKind.DETAIL_ATTACHMENT_ADDED,
        callId = callId,
        payloadJson = Json.encodeToString(DetailAttachmentAddedEventPayload(
            contractVersion = 1,
            attachmentId = attachmentId,
            role = role,
            contentRef = metadata.contentRef,
            contentHash = metadata.contentHash,
            sizeBytes = metadata.sizeBytes,
            mimeType = mimeType,
            observationId = observationId
        )),
        attemptId = "attempt-1",
        executionId = "execution-1"
    ).copy(eventId = "event-$callId-attachment-$attachmentId")

    private fun declaration(
        sequence: Long,
        callId: String,
        tool: String,
        argumentsJson: String = "{}",
        sensitiveFieldPaths: List<String> = emptyList(),
        effect: ToolEffect = ToolEffect.STATE_CHANGING
    ): AgentEventEnvelope = event(
        sequence = sequence,
        kind = AgentEventKind.TOOL_CALL,
        callId = callId,
        payloadJson = Json.encodeToString(ToolCallEventPayload(
            contractVersion = 1,
            providerOrder = 0,
            providerCallId = callId,
            name = tool,
            argumentsJson = argumentsJson,
            source = ToolCallSource.MODEL_REQUESTED,
            binding = ToolBinding.DEVICE_BOUND,
            effect = effect,
            contractHash = HASH,
            presentation = ToolPresentationSnapshot(
                labelResourceKey = "tool.test",
                category = ToolPresentationCategory.PRIMARY,
                resultImageRole = ResultImageRole.BEFORE_AND_AFTER,
                sensitiveFieldPaths = sensitiveFieldPaths
            ),
            roundDetailRef = "round-detail"
        )),
        attemptId = "attempt-1",
        executionId = "execution-1"
    )

    private fun event(
        sequence: Long,
        kind: AgentEventKind,
        callId: String?,
        payloadJson: String,
        attemptId: String?,
        executionId: String?,
        threadId: String = THREAD,
        turnId: String = TURN
    ): AgentEventEnvelope = AgentEventEnvelope(
        schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
        eventId = "event-${callId ?: kind.name.lowercase()}-${kind.name.lowercase()}",
        eventKind = kind,
        sequence = sequence,
        occurredAt = sequence,
        threadId = threadId,
        turnId = turnId,
        roundId = "round",
        attemptId = attemptId,
        executionId = executionId,
        callId = callId,
        payloadHash = HASH,
        payloadJson = payloadJson,
        idempotencyKey = "key-$sequence-${kind.name}"
    )

    private companion object {
        const val THREAD = "thread"
        const val TURN = "turn"
        const val OTHER_THREAD = "other-thread"
        const val OTHER_TURN = "other-turn"
        val HASH = "a".repeat(64)
    }
}
