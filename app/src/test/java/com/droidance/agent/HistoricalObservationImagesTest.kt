// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.context.*
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import com.droidance.core.agent.harness.*
import com.droidance.agent.harness.*
import com.droidance.core.model.llm.OpenAIResponsesProtocol
import com.droidance.core.model.llm.OpenAIResponsesTurnState
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HistoricalObservationImagesTest {
    @Test fun ordinaryHistoryUsesModelBytesAndExplicitOriginalReadsPreserveNativePixels() {
        val root = Files.createTempDirectory("source-model-history").toFile()
        try {
            val blobs = FileBlobStore(root)
            val native = blobs.put("native-pixels".toByteArray())
            val model = blobs.put("model-pixels".toByteArray())
            val initial = projection(listOf(native.contentRef))
            val units = ContextUnitBuilder.build(initial.units.flatMap { it.items }.map { item ->
                if (item is ObservationRefAgentItem) item.copy(content =
                    (item.content as ScreenshotObservationContentRef).copy(modelContentRef = model.contentRef)) else item
            })
            val source = DurableContextProjection(units, ContextObservationProjection.initial(units))
            val loader = HistoricalObservationImages(blobs)
            val ordinary = loader.load(source, 1, null)
            assertEquals("model-pixels", String(Base64.getDecoder().decode(ordinary.images.getValue(4))))
            assertEquals(model.contentRef, ordinary.imageContentRefs.getValue(4))
            val message = DurableContextMessageProjector.build(source, "task", imagesByObservationSequence = ordinary.images,
                imageContentRefsByObservationSequence = ordinary.imageContentRefs).single { it.historicalImage }
            assertTrue(message.content.contains("source_ref=${native.contentRef}"))
            assertTrue(message.content.contains("displayed_image_ref=${model.contentRef}"))
            val explicit = loader.load(source, 0, null, setOf(native.contentRef))
            assertEquals("native-pixels", String(Base64.getDecoder().decode(explicit.images.getValue(4))))
            assertEquals(native.contentRef, explicit.imageContentRefs.getValue(4))
            val explicitModel = loader.load(source, 0, null, setOf(model.contentRef))
            assertEquals("model-pixels", String(Base64.getDecoder().decode(explicitModel.images.getValue(4))))
            assertEquals(1, loader.load(source, 0, null, setOf(native.contentRef, model.contentRef)).images.size)
            val current = Base64.getEncoder().encodeToString("model-pixels".toByteArray())
            assertTrue(loader.load(source, 1, current).images.isEmpty())
            assertEquals(native.contentRef, source.observations.single().contentRef)
            assertEquals(model.contentRef, source.observations.single().modelContentRef)
        } finally { root.deleteRecursively() }
    }
    @Test fun derivedDetailsKeepTheirOriginalBesideThemWithinTheExistingImageBudget() {
        val root = Files.createTempDirectory("derived-image-context").toFile()
        try {
            val blobs = FileBlobStore(root)
            val refs = listOf("original", "left-detail", "right-detail").map { blobs.put(it.toByteArray()).contentRef }
            val initial = projection(refs)
            val items = initial.units.flatMap { it.items }.map { item ->
                if (item is ObservationRefAgentItem && item.sequence > 4) item.copy(content =
                    (item.content as ScreenshotObservationContentRef).copy(sourceObservationId = "obs-4",
                        sourceContentRef = refs[0], sourceRegion = ImageSourceRegion(50, 0, 50, 100, 100, 100))) else item
            }
            val units = ContextUnitBuilder.build(items)
            val source = DurableContextProjection(units, ContextObservationProjection.initial(units))
            val loaded = HistoricalObservationImages(blobs).load(source, 2, null)
            assertEquals(setOf(4L, 6L), loaded.images.keys)
            assertEquals(loaded.images.keys, HistoricalImageSelection.budgetedSequences(source.observations, 2))
            val messages = DurableContextMessageProjector.build(source, "task", imagesByObservationSequence = loaded.images)
                .filter { it.historicalImage }
            assertEquals(2, messages.size)
            val derived = messages.last().content
            assertTrue(derived.contains("source_observation_id")); assertTrue(derived.contains("obs-4"))
            assertTrue(derived.contains("source_region_px")); assertTrue(derived.contains("sourceWidth"))
            assertTrue(derived.contains("current_screen=false"))
            val explicit = HistoricalObservationImages(blobs).load(source, 0, null, refs.toSet())
            assertEquals(setOf(4L, 5L, 6L), explicit.images.keys)
        } finally { root.deleteRecursively() }
    }
    @Test fun unavailableOriginalCannotSatisfyACompletionReview() {
        val root = Files.createTempDirectory("review-missing-image").toFile()
        try {
            val ref = "sha256:" + "a".repeat(64)
            val source = projection(listOf(ref))
            val gate = CompletionReviewGate(true)
            val required = setOf(source.observations.single().observationId)
            assertNotNull(gate.request("task", "review", required, CompletionReviewCandidate.from("done", """{"results":[{"text":"finding","evidence_refs":["source"]}]}""")))
            val loaded = HistoricalObservationImages(FileBlobStore(root)).load(source, 1, null, setOf(ref))
            gate.recordDeliveredSources(source.observations.filter { it.itemSequence in loaded.images }
                .mapTo(linkedSetOf()) { it.observationId })
            assertEquals(CompletionReviewGate.SOURCE_CODE, gate.request("task", "review", required, CompletionReviewCandidate.from("done", """{"results":[{"text":"finding","evidence_refs":["source"]}]}"""))!!.diagnosticCode)
            assertTrue(loaded.requiredFailures.containsKey(ref))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun retained_images_reach_responses_wire_with_their_source_and_do_not_replace_current_frame() {
        val root = Files.createTempDirectory("historical-image-wire").toFile()
        try {
            val blobs = FileBlobStore(root)
            val a = blobs.put("first-source".toByteArray())
            val b = blobs.put("second-source".toByteArray())
            val current = blobs.put("current".toByteArray())
            val refs = listOf(a.contentRef, a.contentRef, b.contentRef, current.contentRef)
            val projection = projection(refs)
            val currentBase64 = Base64.getEncoder().encodeToString("current".toByteArray())
            val loaded = HistoricalObservationImages(blobs).load(projection, 2, currentBase64)
            assertEquals(setOf(5L, 6L), loaded.images.keys)
            assertTrue(loaded.failures.isEmpty())
            val historicalMessages = DurableContextMessageProjector.build(
                projection, "task", imagesByObservationSequence = loaded.images
            ).filter { it.historicalImage }
            assertEquals(2, historicalMessages.size)
            assertEquals(listOf("obs-5", "obs-6"), historicalMessages.map {
                it.content.substringAfter("id=").substringBefore(' ')
            })
            assertEquals(
                listOf(
                    Base64.getEncoder().encodeToString("first-source".toByteArray()),
                    Base64.getEncoder().encodeToString("second-source".toByteArray())
                ),
                historicalMessages.map { it.imageBase64 }
            )
            historicalMessages.forEach { message ->
                assertFalse(message.content.contains(message.imageBase64.orEmpty()))
            }
            val isolated = OpenAIResponsesProtocol.buildRequest("test", historicalMessages,
                emptyList(), null, true, 1000, OpenAIResponsesTurnState())
            assertFalse(isolated.toString().contains("computer_call_output"))
            assertEquals(2, isolated.getValue("input").jsonArray.flatMap {
                it.jsonObject["content"]?.jsonArray.orEmpty()
            }.count { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" })
            val messages = DurableContextMessageProjector.build(projection, "task",
                imagesByObservationSequence = loaded.images) + ChatMessage(Role.USER, "current frame", imageBase64 = currentBase64)
            assertEquals(listOf("obs-5", "obs-6"), messages.filter { it.historicalImage }.map {
                it.content.substringAfter("id=").substringBefore(' ')
            })
            val state = OpenAIResponsesTurnState().apply { commitResponse("previous"); rememberComputerCall("computer") }
            val wire = OpenAIResponsesProtocol.buildRequest("test", messages, emptyList(), null, true, 1000, state)
            val input = wire.getValue("input").jsonArray
            val computer = input.single { it.jsonObject["type"]?.jsonPrimitive?.content == "computer_call_output" }.jsonObject
            assertTrue(computer.getValue("output").jsonObject.getValue("image_url").jsonPrimitive.content.endsWith(currentBase64))
            val images = input.flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
            assertEquals(2, images.size)
            assertTrue(images[0].jsonObject.getValue("image_url").jsonPrimitive.content.endsWith(Base64.getEncoder().encodeToString("first-source".toByteArray())))
            assertTrue(images[1].jsonObject.getValue("image_url").jsonPrimitive.content.endsWith(Base64.getEncoder().encodeToString("second-source".toByteArray())))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun missing_blob_is_reported_without_claiming_image_was_loaded_and_checkpoint_selection_is_respected() {
        val root = Files.createTempDirectory("historical-image-missing").toFile()
        try {
            val blobs = FileBlobStore(root)
            val projection = projection(listOf("sha256:" + "a".repeat(64)))
            val missing = HistoricalObservationImages(blobs).load(projection, 1, null)
            assertTrue(missing.images.isEmpty())
            assertEquals("BlobNotFoundException", missing.failures[4L])
            val summarized = projection.copy(observations = projection.observations.map { it.copy(mode = ObservationProjectionMode.TEXT_SUMMARY) })
            assertTrue(HistoricalObservationImages(blobs).load(summarized, 1, null).failures.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                DurableContextMessageProjector.build(summarized, "task", imagesByObservationSequence = mapOf(4L to "image"))
            }
            assertTrue(HistoricalObservationImages(blobs).load(projection, 0, null).failures.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun historical_frame_alone_is_never_used_as_a_computer_action_result() {
        val state = OpenAIResponsesTurnState().apply { commitResponse("previous"); rememberComputerCall("computer") }
        val wire = OpenAIResponsesProtocol.buildRequest("test", listOf(
            ChatMessage(Role.USER, "old", imageBase64 = "old-image", historicalImage = true)
        ), emptyList(), null, true, 1000, state)
        assertFalse(wire.toString().contains("computer_call_output"))
        assertTrue(wire.toString().contains("input_image"))
    }

    @Test
    fun historicalImageBudgetIsBoundedWithoutDroppingAnyTextOrReferences() {
        val projection = projection((1..40).map { "sha256:image-$it" })
        val estimator = StructuralContextTokenEstimator.forSession("test", "v1", "model")
        val selected = HistoricalImageSelection.budgetedSequences(projection.observations, 3)
        assertEquals(3, selected.size)
        val bounded = projection.observations.filter { it.itemSequence in selected }.associateBy { it.itemSequence }
        val withImages = projection.units.sumOf { estimator.estimateUnit(it, bounded) }
        val textOnly = projection.units.sumOf { estimator.estimateUnit(it, emptyMap()) }
        assertEquals(3 * StructuralContextTokenEstimator.DEFAULT_IMAGE_TOKENS, withImages - textOnly)
        assertEquals(40, projection.observations.size)
        assertEquals(40, projection.units.flatMap { it.items }.filterIsInstance<ObservationRefAgentItem>().size)
        val duplicates = projection(listOf("sha256:a", "sha256:a", "sha256:b"))
        assertEquals(setOf(5L, 6L), HistoricalImageSelection.budgetedSequences(duplicates.observations, 3))
    }

    @Test
    fun explicit_reads_override_recent_budget_and_keep_same_pixels_as_current_as_history() {
        val root = Files.createTempDirectory("historical-image-explicit").toFile()
        try {
            val blobs = FileBlobStore(root)
            val first = blobs.put("first-explicit".toByteArray())
            val second = blobs.put("second-explicit".toByteArray())
            val current = blobs.put("same-current".toByteArray())
            val projection = projection(listOf(first.contentRef, second.contentRef, current.contentRef))
            val currentBase64 = Base64.getEncoder().encodeToString("same-current".toByteArray())

            val loaded = HistoricalObservationImages(blobs).load(
                projection = projection,
                limit = 0,
                currentImage = currentBase64,
                requiredContentRefs = setOf(first.contentRef, second.contentRef, current.contentRef)
            )

            assertEquals(setOf(4L, 5L, 6L), loaded.images.keys)
            assertEquals(currentBase64, loaded.images.getValue(6L))
            assertTrue(loaded.requiredFailures.isEmpty())

            val messages = DurableContextMessageProjector.build(
                projection,
                "task",
                imagesByObservationSequence = loaded.images
            )
            val wire = OpenAIResponsesProtocol.buildRequest(
                "test",
                messages,
                emptyList(),
                null,
                true,
                1000,
                OpenAIResponsesTurnState()
            )
            val images = wire.getValue("input").jsonArray.flatMap {
                it.jsonObject["content"]?.jsonArray.orEmpty()
            }.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
            assertEquals(3, images.size)
            assertTrue(images.any { it.jsonObject.getValue("image_url").jsonPrimitive.content.endsWith(currentBase64) })
        } finally { root.deleteRecursively() }
    }

    @Test fun requiredBatchPagesOverrideZeroRetentionAndReuseCurrentBytesOnlyWhenAttached() {
        val root = Files.createTempDirectory("batch-current-image").toFile()
        try {
            val blobs = FileBlobStore(root)
            val pages = (1..3).map { "page-$it".toByteArray() }
            val refs = pages.map { blobs.put(it).contentRef }
            val current = Base64.getEncoder().encodeToString(pages.last())
            val loaded = HistoricalObservationImages(blobs).load(projection(refs), 0, current, refs.toSet(),
                deduplicateCurrentImage = true)
            assertEquals(setOf(4L, 5L), loaded.images.keys)
            assertEquals(setOf(refs.last()), loaded.currentImageRefs)
            assertTrue(loaded.requiredFailures.isEmpty())
            val withoutCurrent = HistoricalObservationImages(blobs).load(projection(refs), 0, current, refs.toSet())
            assertEquals(3, withoutCurrent.images.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun explicit_reference_outside_active_projection_is_reported() {
        val root = Files.createTempDirectory("historical-image-explicit-missing").toFile()
        try {
            val blobs = FileBlobStore(root)
            val active = blobs.put("active".toByteArray())
            val projection = projection(listOf(active.contentRef))
            val missingRef = "sha256:${"f".repeat(64)}"

            val loaded = HistoricalObservationImages(blobs).load(
                projection,
                limit = 0,
                currentImage = null,
                requiredContentRefs = setOf(missingRef)
            )

            assertTrue(loaded.images.isEmpty())
            assertEquals(
                HistoricalObservationImages.REQUIRED_REFERENCE_NOT_ACTIVE,
                loaded.requiredFailures[missingRef]
            )
        } finally { root.deleteRecursively() }
    }

    private fun projection(refs: List<String>): DurableContextProjection {
        val call = PlannedToolCall(0, "call", ProviderToolCall("provider", "observe_screen", "{}"),
            RegisteredToolContract("observe_screen", "Observe", ToolBinding.DEVICE_BOUND,
                ToolEffect.READ_ONLY, emptyMap()))
        val items = listOf<AgentItem>(
            UserInputAgentItem("user", 1, "round", UserInputKind.MESSAGE, "task"),
            ToolCallAgentItem("call-item", 2, "round", call),
            ToolResultAgentItem("result", 3, "round", "call", ToolExecutionResult(
                outputText = "observed", executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = ToolEvidenceStatus.UNKNOWN
            ))
        ) +
            refs.mapIndexed { index, ref ->
                val sequence = index + 4L
                ObservationRefAgentItem("image-$sequence", sequence, "round", "obs-$sequence", ScreenshotObservationContentRef(ref, null))
            }
        val units = ContextUnitBuilder.build(items)
        return DurableContextProjection(units, ContextObservationProjection.initial(units))
    }
    private fun CompletionReviewGate.recordDeliveredSources(sources: Set<String>) =
        recordDeliveredSources(sources.associateWith { "hash:$it" })
}
