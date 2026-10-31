// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.context.DurableContextMessageProjector
import com.droidance.core.agent.context.DurableContextProjector
import com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION
import com.droidance.core.persistence.agent.event.AgentEventDatabase
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.ModelRoundScope
import com.droidance.core.agent.event.PreparedModelRoundCommit
import com.droidance.core.agent.event.PreparedModelRoundStart
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.SubmittedItemSequenceRange
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolLifecycleEventScope
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.event.TurnResult
import com.droidance.core.agent.attempt.ExecutionRoundPlanner
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.HistoricalObservationImages
import com.droidance.core.model.llm.OpenAIResponsesProtocol
import com.droidance.core.model.llm.OpenAIResponsesTurnState
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionReadInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AgentEventDatabase
    private lateinit var blobRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "session-read-${UUID.randomUUID()}.db"
        blobRoot = context.cacheDir.resolve("session-read-${UUID.randomUUID()}")
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        blobRoot.deleteRecursively()
    }

    @Test
    fun reopened_room_and_blob_read_session_history_and_responses_wire_keep_exact_png_hash() = runBlocking {
        val initialStore = store("initial-")
        val initialBlobs = FileBlobStore(blobRoot)
        val png = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
        assertTrue(png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        val sourceMetadata = initialBlobs.put(png)
        val missingHash = "b".repeat(64)
        val sourceAttachment = DetailAttachmentAddedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            attachmentId = "source-attachment",
            role = DetailAttachmentRole.RESULT,
            contentRef = sourceMetadata.contentRef,
            contentHash = sourceMetadata.contentHash,
            sizeBytes = sourceMetadata.sizeBytes,
            mimeType = "image/png",
            width = 1,
            height = 1,
            observationId = "source-observation"
        )
        val missingAttachment = DetailAttachmentAddedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            attachmentId = "missing-attachment",
            role = DetailAttachmentRole.RESULT,
            contentRef = "sha256:$missingHash",
            contentHash = missingHash,
            sizeBytes = png.size.toLong(),
            mimeType = "image/png",
            width = 1,
            height = 1,
            observationId = "missing-observation"
        )
        val sourceTurn = persistRound(
            store = initialStore,
            threadId = "history-thread",
            turnId = "source-turn",
            callId = "source-call",
            toolName = "observe_screen",
            argumentsJson = "{}",
            result = ToolExecutionResult(
                outputText = "source observation",
                executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = ToolEvidenceStatus.CAPTURED,
                observationId = "source-observation"
            ),
            attachments = listOf(sourceAttachment, missingAttachment),
            createThread = true
        )

        database.close()
        database = openDatabase()
        val reopenedStore = store("reopened-")
        val reopenedBlobs = FileBlobStore(blobRoot)
        val readService = SessionReadService(reopenedStore, reopenedBlobs)

        val sourceRead = readService.read(
            threadId = sourceTurn.threadId,
            turnId = sourceTurn.turnId,
            arguments = "{\"mode\":\"image\",\"ref\":\"source-attachment\",\"role\":\"post\"}",
            imagesSupported = true
        )
        assertEquals(ToolExecutionStatus.SUCCEEDED, sourceRead.executionStatus)
        assertEquals(sourceMetadata.contentHash, sourceRead.historicalAttachment?.contentHash)
        assertEquals(sourceMetadata.contentRef, sourceRead.historicalAttachment?.contentRef)
        assertEquals(sourceMetadata.contentHash, Json.parseToJsonElement(sourceRead.outputText)
            .jsonObject.getValue("sha256").jsonPrimitive.content)
        assertArrayEquals(png, reopenedBlobs.read(sourceMetadata.contentRef, 8L * 1024L * 1024L))

        val missingRead = readService.read(
            threadId = sourceTurn.threadId,
            turnId = sourceTurn.turnId,
            arguments = "{\"mode\":\"image\",\"ref\":\"missing-attachment\",\"role\":\"post\"}",
            imagesSupported = true
        )
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, missingRead.executionStatus)
        assertEquals("HISTORY_IMAGE_UNAVAILABLE", missingRead.diagnosticCode)
        assertEquals(null, missingRead.historicalAttachment)

        val projection = requireNotNull(
            DurableContextProjector.project(reopenedStore.eventsForTurn(sourceTurn.turnId))
        )
        val loaded = HistoricalObservationImages(reopenedBlobs).load(
            projection = projection,
            limit = 1,
            currentImage = null,
            requiredContentRefs = setOf(sourceMetadata.contentRef)
        )
        assertEquals(setOf(sourceMetadata.contentHash), loaded.images.values.map { sha256Base64(it) }.toSet())
        assertEquals(1, loaded.images.size)
        assertTrue(loaded.failures.isEmpty())
        assertTrue(loaded.requiredFailures.isEmpty())

        val messages = DurableContextMessageProjector.build(
            projection = projection,
            firstTaskPrompt = "read the persisted observation",
            imagesByObservationSequence = loaded.images
        )
        assertTrue(messages.any { it.historicalImage && it.imageBase64 == loaded.images.values.single() })
        val wire = OpenAIResponsesProtocol.buildRequest(
            model = "test-model",
            messages = messages,
            tools = emptyList(),
            systemPrompt = null,
            supportsVision = true,
            maxOutputTokens = 1000,
            state = OpenAIResponsesTurnState()
        )
        val inputImages = wire.getValue("input").jsonArray.flatMap {
            it.jsonObject["content"]?.jsonArray.orEmpty()
        }.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
        assertEquals(1, inputImages.size)
        val wireBase64 = inputImages.single().jsonObject.getValue("image_url")
            .jsonPrimitive.content.substringAfterLast(',')
        assertEquals(sourceMetadata.contentHash, sha256Base64(wireBase64))

        val deniedCrossTurn = readService.read(
            threadId = sourceTurn.threadId,
            turnId = "later-turn",
            arguments = "{\"mode\":\"image\",\"ref\":\"source-attachment\",\"role\":\"post\"}",
            imagesSupported = true
        )
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, deniedCrossTurn.executionStatus)
        assertEquals("HISTORY_REFERENCE_NOT_FOUND", deniedCrossTurn.diagnosticCode)
        assertFalse(deniedCrossTurn.outputText.contains(sourceMetadata.contentHash))
    }

    private fun openDatabase(): AgentEventDatabase = Room.databaseBuilder(
        context,
        AgentEventDatabase::class.java,
        databaseName
    ).build()

    private fun store(prefix: String): com.droidance.core.persistence.agent.event.RoomAgentEventStore {
        var id = 0
        var time = 1_000L
        return com.droidance.core.persistence.agent.event.RoomAgentEventStore(
            database = database,
            nowMs = { time++ },
            eventIdFactory = { "$prefix${id++}" }
        )
    }

    private suspend fun persistRound(
        store: com.droidance.core.persistence.agent.event.RoomAgentEventStore,
        threadId: String,
        turnId: String,
        callId: String,
        toolName: String,
        argumentsJson: String,
        result: ToolExecutionResult,
        attachments: List<DetailAttachmentAddedEventPayload>,
        createThread: Boolean
    ): TurnEventScope {
        if (createThread) store.createThread(threadId, "Session history")
        val turnScope = TurnEventScope(threadId, turnId)
        store.startTurn(turnScope, modelIdentity(threadId), "provider-config")
        val user = store.recordUserMessage(turnScope, "message-$turnId", "persist session history")
        val roundScope = ModelRoundScope(threadId, turnId, "round-$callId")
        val roundDetailRef = "detail:${roundScope.roundId}"
        val submitted = SubmittedItemSequenceRange(user.sequence, user.sequence)
        store.recordModelRoundStarted(
            PreparedModelRoundStart.create(
                scope = roundScope,
                repairPurpose = RepairPurpose.NONE,
                startCommitted = false,
                submittedItems = submitted,
                roundDetailRef = roundDetailRef
            )
        )
        val normalized = NormalizedModelRound.create(
            publicBlocks = listOf("persisted session history"),
            toolCalls = listOf(ProviderToolCall("provider-$callId", toolName, argumentsJson))
        )
        val contract = RegisteredToolContract(
            name = toolName,
            description = "Session history test tool",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY,
            parameters = emptyMap()
        )
        val prepared = ExecutionRoundPlanner(
            registrations = listOf(contract),
            capabilityRequirements = emptyList(),
            callIdFactory = { _, _ -> callId }
        ).plan(normalized, startCommitted = false, repairPurpose = RepairPurpose.NONE)
        store.recordModelRoundPlan(
            PreparedModelRoundCommit.create(
                scope = roundScope,
                expectedStartCommitted = false,
                repairPurpose = RepairPurpose.NONE,
                submittedItems = submitted,
                roundDetailRef = roundDetailRef,
                normalizedRound = normalized,
                preparedExecution = prepared,
                presentationsByCallId = mapOf(
                    callId to ToolPresentationSnapshot(
                        labelResourceKey = "tool.session_history_test",
                        category = com.droidance.core.agent.event.ToolPresentationCategory.SUPPORTING,
                        resultImageRole = ResultImageRole.NONE
                    )
                )
            )
        )
        store.finishToolWithAttachments(
            scope = ToolLifecycleEventScope(threadId, turnId, roundScope.roundId, callId),
            result = result,
            roundDetailRef = roundDetailRef,
            attachments = attachments
        )
        store.completeTurn(
            threadId = threadId,
            turnId = turnId,
            result = TurnResult(status = "COMPLETED", assistantReply = "persisted")
        )
        return turnScope
    }

    private fun modelIdentity(threadId: String) = ModelSessionIdentity(
        threadId = threadId,
        providerId = "provider",
        transportKind = ModelTransport.OPENAI_RESPONSES,
        transportConfigHash = "1".repeat(64),
        authScopeHash = "2".repeat(64),
        modelId = "model",
        stablePrefixHash = "3".repeat(64)
    )

    private fun sha256Base64(base64: String): String {
        val bytes = Base64.getDecoder().decode(base64)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
