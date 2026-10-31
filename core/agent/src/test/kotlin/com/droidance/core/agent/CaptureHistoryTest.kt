// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CaptureHistoryTest {
    @Test fun historyUsesModelByteCostAndCaptureOrderAcrossUnevenGroups() {
        val pages = (1..6).map { frame(it, nativeBytes = 1_200_000, modelBytes = 500_000) }
        val history = RetainedReviewSources.captureHistory(pages, 32, 3_000_000)
        assertEquals((1..6).map(::ref), history.images.map { it.contentRef })
        assertEquals(3_000_000L, history.bytes)
        assertEquals(0, history.facts().getValue("omitted_images").jsonPrimitive.int)
    }

    @Test fun resourceLimitReportsOmittedPagesWithoutPretendingToDeliverTheWholeHistory() {
        val pages = (1..6).map { frame(it, nativeBytes = 100, modelBytes = 10) }
        val byteLimited = RetainedReviewSources.captureHistory(pages, 32, 25)
        assertEquals(listOf(ref(5), ref(6)), byteLimited.images.map { it.contentRef })
        assertEquals(4, byteLimited.facts().getValue("omitted_images").jsonPrimitive.int)
        assertEquals(20L, byteLimited.bytes)
        assertEquals(listOf(ref(4), ref(5), ref(6)),
            RetainedReviewSources.captureHistory(pages, 3, 1_000).images.map { it.contentRef })
        assertTrue(RetainedReviewSources.captureHistory(pages, 0, 1_000).images.isEmpty())
        assertTrue(RetainedReviewSources.captureHistory(pages, 32, 0).images.isEmpty())
    }

    @Test fun repeatedPixelsConsumeOneSlotAndNativeOnlySourcesRemainAvailable() {
        val first = frame(1, nativeBytes = 100)
        val second = frame(2, nativeBytes = 200)
        val history = RetainedReviewSources.captureHistory(listOf(first, second, first), 2, 300)
        assertEquals(listOf(ref(1), ref(2)), history.images.map { it.contentRef })
        assertEquals(2, history.totalImages)
        assertEquals(300L, history.bytes)
    }

    @Test fun explicitScreenshotsFormTheCollectionWorkingSet() {
        fun call(sequence: Long, id: String, name: String, actions: String) = AgentEventEnvelope(1, "event-$sequence",
            AgentEventKind.TOOL_CALL, sequence, sequence, "thread", "turn", "round", null, null, id, "a".repeat(64),
            kotlinx.serialization.json.Json.encodeToString(ToolCallEventPayload.serializer(), ToolCallEventPayload(
                contractVersion = 1, providerOrder = 0, providerCallId = id, name = name,
                argumentsJson = """{"actions":$actions}""", source = ToolCallSource.MODEL_REQUESTED,
                binding = ToolBinding.DEVICE_BOUND, effect = ToolEffect.STATE_CHANGING, contractHash = "a".repeat(64),
                presentation = ToolPresentationSnapshot("tool.act", ToolPresentationCategory.PRIMARY, ResultImageRole.INPUT),
                roundDetailRef = "round-detail")), "key-$sequence")
        val events = listOf(
            call(1, "navigate", "screen_act", """[{"op":"click","x":1,"y":1},{"op":"wait","ms":500},{"op":"screenshot"}]"""),
            call(2, "collect", "screen_act", """[{"op":"scroll","direction":"down"},{"op":"screenshot"}]"""),
            call(3, "compare", "screen_act", """[{"op":"screenshot"},{"op":"click","x":1,"y":1},{"op":"screenshot"}]"""),
            call(4, "other-tool", "workspace", """[{"op":"scroll"}]"""),
            call(5, "round-trip", "screen_act", """[{"op":"click","x":1,"y":1},{"op":"screenshot"},{"op":"press_key","key":"BACK"}]"""))
        assertEquals(setOf("navigate", "collect", "compare", "round-trip"), RetainedReviewSources.collectionCaptureCalls(events))
    }

    private fun frame(index: Int, nativeBytes: Long, modelBytes: Long? = null) =
        DetailAttachmentAddedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION, "page-$index",
            DetailAttachmentRole.RESULT, ref(index), index.toString(16).padStart(64, '0'), nativeBytes, "image/png",
            observationId = "page-$index", modelContentRef = modelBytes?.let { ref(index + 100) }, modelSizeBytes = modelBytes)

    private fun ref(index: Int) = "sha256:" + index.toString(16).padStart(64, '0')
}
