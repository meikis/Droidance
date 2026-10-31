// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.TaskCompactionInput
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.SessionEvidenceProjector
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolCallSource
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.harness.PlannedToolCall
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.prompt.StablePrefixBuilder
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultContextRendererTest {
    private val estimator = StructuralContextTokenEstimator.forSession("transport", "adapter", "model")

    @Test
    fun shortReceiptIncludingTheInlineBoundaryRemainsVerbatim() {
        val original = "r".repeat(ToolResultContextRenderer.MAX_INLINE_CHARS)
        val item = receipt(original)

        assertEquals(original, ToolResultContextRenderer.render(item))
        val messages = live(units(original))
        assertEquals(original, messages.single { it.role == Role.TOOL }.content)
    }

    @Test
    fun longReceiptUsesItsOriginalEventReferenceWithoutInventingASafePreview() {
        val original = "PRIVATE_HEAD" + "x".repeat(97_000) + "PRIVATE_TAIL"
        val item = receipt(original).copy(result = receipt(original).result.copy(diagnosticCode = "READ_DIAGNOSTIC"))
        val rendered = Json.parseToJsonElement(ToolResultContextRenderer.render(item)).jsonObject

        assertEquals(item.itemId, rendered.getValue("event_ref").jsonPrimitive.content)
        assertEquals(item.callId, rendered.getValue("call_ref").jsonPrimitive.content)
        assertEquals(original.length, rendered.getValue("original_text_chars").jsonPrimitive.int)
        assertEquals("true", rendered.getValue("context_text_omitted").jsonPrimitive.content)
        assertEquals("true", rendered.getValue("retained_source_unchanged").jsonPrimitive.content)
        assertEquals("SUCCEEDED", rendered.getValue("execution_status").jsonPrimitive.content)
        assertEquals("READ_DIAGNOSTIC", rendered.getValue("diagnostic_code").jsonPrimitive.content)
        assertEquals("session_read", rendered.getValue("read_tool").jsonPrimitive.content)
        val read = rendered.getValue("read_arguments").jsonObject
        assertEquals("artifact", read.getValue("mode").jsonPrimitive.content)
        assertEquals(item.itemId, read.getValue("ref").jsonPrimitive.content)
        assertEquals(0, read.getValue("offset").jsonPrimitive.int)
        assertFalse(rendered.toString().contains("PRIVATE_HEAD"))
        assertFalse(rendered.toString().contains("PRIVATE_TAIL"))
        assertFalse(rendered.containsKey("original_text_truncated"))
        assertEquals(original, item.result.outputText)

        val unsafeDiagnostic = item.copy(result = item.result.copy(diagnosticCode = "private error " + original))
        assertFalse(Json.parseToJsonElement(ToolResultContextRenderer.render(unsafeDiagnostic)).jsonObject
            .containsKey("diagnostic_code"))
        assertEquals(unsafeDiagnostic.result.outputText, ToolResultContextRenderer.fullText(unsafeDiagnostic))
    }

    @Test
    fun liveProjectionPreservesProtocolPairsAndRawUnitHashesForBothProtectedRounds() {
        val original = "x".repeat(97_000) + "long-tail-A"
        val source = units(original, original)
        val hashes = source.map { it.toRef().unitHash }
        val messages = live(source)
        val calls = messages.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls.orEmpty() }
        val results = messages.filter { it.role == Role.TOOL }

        assertEquals(listOf("provider-1", "provider-2"), calls.map { it.id })
        assertEquals(calls.map { it.id }, results.map { it.toolCallId })
        assertEquals(listOf("finished-1", "finished-2"), results.map {
            Json.parseToJsonElement(it.content).jsonObject.getValue("event_ref").jsonPrimitive.content
        })
        assertTrue(results.all { it.content.length < ToolResultContextRenderer.MAX_INLINE_CHARS })
        assertEquals(hashes, source.map { it.toRef().unitHash })
        assertEquals(listOf(original, original), source.flatMap { it.items }
            .filterIsInstance<ToolResultAgentItem>().map { it.result.outputText })

        val changedTail = units(original.dropLast(1) + "B", original)
        assertNotEquals(hashes.first(), changedTail.first().toRef().unitHash)
        assertEquals(results.first().content, live(changedTail).first { it.role == Role.TOOL }.content)
    }

    @Test
    fun structuralBudgetEstimatesTheSameFiniteViewThatTheProviderReceives() {
        val ascii = units("a".repeat(97_000))
        val unicode = units("界".repeat(97_000))

        assertEquals(live(ascii).single { it.role == Role.TOOL }.content,
            live(unicode).single { it.role == Role.TOOL }.content)
        assertEquals(estimator.estimateUnit(ascii.single(), emptyMap()),
            estimator.estimateUnit(unicode.single(), emptyMap()))
        assertTrue(estimator.estimateUnit(unicode.single(), emptyMap()) < 1_000)
        assertTrue(estimator.estimateText(ToolResultContextRenderer.fullText(receipt("界".repeat(97_000)))) > 90_000)
        assertNotEquals(ascii.single().toRef().unitHash, unicode.single().toRef().unitHash)
    }

    @Test
    fun repeatedLongReceiptsFitTheExistingWindowWithoutRemovingProtectedSourceUnits() {
        val original = "界".repeat(97_000)
        val source = units(original, original)
        val stable = StablePrefixBuilder.build("界".repeat(35_000), emptyList(), emptyList())
        val policy = ContextPolicySnapshot(128_000, 16_384, 24_384, 9, 10, 1, estimator.identity)
        val decision = ContextCheckpointPlanner(estimator).plan(ContextCheckpointPlanInput(
            parentCheckpoint = null,
            stablePrefix = stable,
            dynamicRequestSuffix = ContextDynamicRequestSuffix.empty(),
            sortedUnits = source,
            currentObservationProjections = ContextObservationProjection.initial(source),
            contextPolicy = policy
        ))

        assertTrue(decision is ContextCheckpointDecision.Unchanged)
        val unchanged = decision as ContextCheckpointDecision.Unchanged
        assertEquals(ContextUnchangedReason.BELOW_TRIGGER, unchanged.reason)
        assertTrue(unchanged.tokensBefore < policy.triggerTokens)
        assertTrue(estimator.estimateStablePrefix(stable) + estimator.estimateText(original) * 2 > policy.hardInputTokens)
        assertEquals(2, source.size)
        assertEquals(listOf(original, original), source.flatMap { it.items }
            .filterIsInstance<ToolResultAgentItem>().map { it.result.outputText })
    }

    @Test
    fun compactionStillReceivesTheFullRetainedTextAndCountsThatTextSeparately() {
        val original = "head \"quoted\"\n" + "界".repeat(97_000) + "\ntail retained"
        val source = units(original)
        val input = TaskCompactionInput.build(DurableContextProjection(source, emptyList()), "task", emptyMap())
        val messages = input.source.lineSequence().filter { it.startsWith("{") }
            .map { Json.decodeFromString(ChatMessage.serializer(), it) }.toList()
        val full = messages.single { it.role == Role.TOOL }

        assertEquals(original, full.content)
        assertEquals("provider-1", full.toolCallId)
        assertTrue(input.images.isEmpty())
        assertTrue(estimator.estimateText(input.source) > estimator.estimateText(original))
        assertTrue(estimator.estimateText(input.source) > estimator.estimateUnit(source.single(), emptyMap()))
        assertFalse(live(source).single { it.role == Role.TOOL }.content.contains("tail retained"))
    }

    @Test
    fun escapedTwelveThousandCharacterArtifactPageEntersLiveContextWithItsTextAndCursor() {
        val original = "\"\\\n中文".repeat(20_000)
        val item = receipt(original)
        val described = SessionEvidenceProjector.describe(step(item), true).toString()
        val pageText = described.substring(0, 12_000)
        val page = buildJsonObject {
            put("source_ref", item.itemId)
            put("text", pageText)
            put("next_offset", 12_000)
            put("has_more", true)
            put("total_chars", described.length)
        }.toString()
        assertTrue(page.length > 12_000)
        assertTrue(page.length <= ToolResultContextRenderer.MAX_INLINE_CHARS)

        val projected = live(units(page)).single { it.role == Role.TOOL }.content
        assertEquals(page, projected)
        val recovered = Json.parseToJsonElement(projected).jsonObject
        assertEquals(pageText, recovered.getValue("text").jsonPrimitive.content)
        assertEquals(12_000, recovered.getValue("next_offset").jsonPrimitive.int)
        assertFalse(recovered.containsKey("context_text_omitted"))
    }

    @Test
    fun emptyOutputWithLargeStructuredDataUsesTheSameBoundedAndRecoverableReceiptText() {
        val data = buildJsonObject { put("payload", "x".repeat(97_000) + "RETAINED_DATA_TAIL") }
        val result = receipt("").result.copy(data = data, coverage = mapOf("scope" to "full"), preview = "structured preview")
        val item = receipt("").copy(result = result)
        val original = result.asReceiptText()
        val rendered = Json.parseToJsonElement(ToolResultContextRenderer.render(item)).jsonObject
        val source = units(listOf(result))

        assertEquals(original.length, rendered.getValue("original_text_chars").jsonPrimitive.int)
        assertEquals(item.itemId, rendered.getValue("read_arguments").jsonObject.getValue("ref").jsonPrimitive.content)
        assertFalse(rendered.toString().contains("RETAINED_DATA_TAIL"))
        assertTrue(estimator.estimateUnit(source.single(), emptyMap()) < 1_000)
        val retained = Json.parseToJsonElement(SessionEvidenceProjector.describe(step(item), true)
            .getValue("result").jsonPrimitive.content).jsonObject
        assertEquals(data, retained.getValue("data"))
        assertEquals("structured preview", retained.getValue("preview").jsonPrimitive.content)

        val index = SessionEvidenceProjector.sourceIndex(history(step(item)), "thread", "turn", 32,
            includeTextContent = true, preferredRefs = setOf(item.itemId))
        val indexed = index.getValue("sources").jsonArray.single().jsonObject
        assertEquals(original.length, indexed.getValue("original_text_chars").jsonPrimitive.int)
        assertEquals("", indexed.getValue("original_text").jsonPrimitive.content)
        assertEquals("true", indexed.getValue("original_text_truncated").jsonPrimitive.content)
        assertEquals(item.itemId, indexed.getValue("read_arguments").jsonObject.getValue("ref").jsonPrimitive.content)

        val input = TaskCompactionInput.build(DurableContextProjection(source, emptyList()), "task", emptyMap())
        val full = input.source.lineSequence().filter { it.startsWith("{") }
            .map { Json.decodeFromString(ChatMessage.serializer(), it) }.single { it.role == Role.TOOL }
        assertEquals(original, full.content)
        assertEquals(data, source.single().items.filterIsInstance<ToolResultAgentItem>().single().result.data)
    }

    @Test
    fun structuredReceiptFallbackCannotBypassHistoricalSensitiveFieldRedaction() {
        val item = receipt("").copy(result = receipt("").result.copy(
            data = buildJsonObject { put("payload", "PRIVATE_DATA" + "x".repeat(97_000)) }))
        val source = step(item)
        val sensitive = source.copy(call = source.call.copy(argumentsJson = "{\"password\":\"PRIVATE_PASSWORD\"}",
            presentation = source.call.presentation.copy(sensitiveFieldPaths = listOf("password"))))

        val described = SessionEvidenceProjector.describe(sensitive, true)
        assertEquals("true", described.getValue("content_redacted").jsonPrimitive.content)
        assertFalse(described.containsKey("result"))
        assertFalse(described.toString().contains("PRIVATE_DATA"))
        val index = SessionEvidenceProjector.sourceIndex(history(sensitive), "thread", "turn", 32, true)
        assertFalse(index.toString().contains("PRIVATE_DATA"))
        assertFalse(index.toString().contains("PRIVATE_PASSWORD"))
        assertFalse(index.getValue("sources").jsonArray.single().jsonObject.containsKey("original_text"))
        assertFalse(ToolResultContextRenderer.render(item).contains("PRIVATE_DATA"))
        assertTrue(item.result.asReceiptText().contains("PRIVATE_DATA"))
    }

    private fun live(source: List<ContextUnit>) =
        DurableContextMessageProjector.build(DurableContextProjection(source, emptyList()), "task")

    private fun units(vararg outputs: String): List<ContextUnit> = units(outputs.map { receipt(it).result })

    private fun units(results: List<ToolExecutionResult>): List<ContextUnit> {
        val items = mutableListOf<AgentItem>(UserInputAgentItem("user", 1, "round-1", UserInputKind.MESSAGE, "task"))
        results.forEachIndexed { index, output ->
            val number = index + 1
            val result = receipt("", number).copy(result = output)
            val call = PlannedToolCall(0, result.callId, ProviderToolCall("provider-$number", "read_source", "{}"),
                RegisteredToolContract("read_source", "Read retained facts", ToolBinding.DEVICE_BOUND, ToolEffect.READ_ONLY, emptyMap()))
            items += ToolCallAgentItem("call-item-$number", result.sequence - 1, result.roundId, call)
            items += result
        }
        return ContextUnitBuilder.build(items)
    }

    private fun receipt(output: String, number: Int = 1) = ToolResultAgentItem(
        itemId = "finished-$number", sequence = number * 2L + 1L, roundId = "round-$number", callId = "call-$number",
        result = ToolExecutionResult(outputText = output, executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.CAPTURED)
    )

    private fun step(item: ToolResultAgentItem) = SessionEvidenceProjector.Step(
        event = AgentEventEnvelope(1, item.itemId, AgentEventKind.TOOL_FINISHED, item.sequence, 1,
            "thread", "turn", item.roundId, "attempt", "execution", item.callId, "a".repeat(64), "{}", "receipt"),
        call = ToolCallEventPayload(1, 0, "provider-1", "read_source", "{}", ToolCallSource.MODEL_REQUESTED,
            ToolBinding.DEVICE_BOUND, ToolEffect.READ_ONLY, "a".repeat(64),
            ToolPresentationSnapshot("tool.read", ToolPresentationCategory.PRIMARY, ResultImageRole.NONE), "round-detail"),
        result = item.result,
        attachments = emptyList()
    )

    private fun history(step: SessionEvidenceProjector.Step) = listOf(
        step.event.copy(eventId = "declaration", eventKind = AgentEventKind.TOOL_CALL, sequence = step.event.sequence - 1,
            payloadJson = Json.encodeToString(step.call)),
        step.event.copy(payloadJson = Json.encodeToString(ToolFinishedEventPayload(1, step.result, "round-detail")))
    )
}
