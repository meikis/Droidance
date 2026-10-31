// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.context.ContextCompactionExecutionPolicy
import com.droidance.core.agent.context.ContextCompactionUnavailable
import com.droidance.core.agent.context.ContextPolicySnapshot
import com.droidance.core.agent.context.ContextTokenEstimatorCoverage
import com.droidance.core.agent.context.ContextTokenEstimatorIdentity
import com.droidance.core.agent.context.ContextUnit
import com.droidance.core.agent.context.DurableContextProjection
import com.droidance.core.agent.context.ObservationProjectionMode
import com.droidance.core.agent.context.ObservationProjectionRef
import com.droidance.core.agent.context.ObservationRefAgentItem
import com.droidance.core.agent.context.ScreenshotObservationContentRef
import com.droidance.core.agent.context.UserInputAgentItem
import com.droidance.core.agent.context.UserInputKind
import com.droidance.core.agent.ports.RuleSource
import com.droidance.core.agent.debug.DebugLogAppender
import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolDefinition
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContextCompactorTest {
    @Test fun summaryForkPreservesParentMessagesAndRejectsToolCallsWithoutExecution() = runBlocking {
        val fixture = Fixture()
        assertEquals("complete", fixture.compact())
        val request = fixture.client.delegate.recordedRequests.single()
        assertEquals(fixture.parent, request.take(fixture.parent.size))
        assertEquals(listOf("Frozen operator rules"), fixture.client.systemPrompts)
        assertEquals(listOf(fixture.tools), fixture.client.delegate.recordedTools)
        assertTrue(request.last().content.contains("Summarize retained evidence"))
        val rejected = Fixture()
        rejected.client.delegate.cannedStreamEvents = listOf(LLMStreamEvent.ToolCallChunk(0, "call", "read", "{}"))
        val failure = runCatching { rejected.compact() }.exceptionOrNull()
        assertEquals("COMPACTION_TOOL_CALL_REJECTED", (failure as ContextCompactionUnavailable).diagnostic)
    }

    @Test fun oversizedSourceChunksKeepEverySourceByteAndTheFrozenSchema() = runBlocking {
        val fixture = Fixture()
        val text = "source with exact values ".repeat(30_000)
        val projection = DurableContextProjection(listOf(ContextUnit("round", listOf(
            UserInputAgentItem("user", 1, "round", UserInputKind.MESSAGE, text)))), emptyList())
        fixture.parent = listOf(ChatMessage(com.droidance.core.model.llm.models.Role.USER, text))
        assertEquals("complete", fixture.compact(projection, text))
        val requests = fixture.client.delegate.recordedRequests
        assertTrue(requests.size > 1)
        val chunks = requests.joinToString("") { it.first().content.substringAfter("<history_data>\n").substringBefore("\n</history_data>") }
        assertEquals(TaskCompactionInput.build(projection, text, emptyMap()).source, chunks)
        assertTrue(fixture.client.delegate.recordedTools.all { it == fixture.tools })
        assertTrue(fixture.client.systemPrompts.all { it == "Frozen operator rules" })
    }

    @Test fun reasoningOnlyLengthTerminationRetriesWithTheExistingLargerBudget() = runBlocking {
        val fixture = Fixture()
        fixture.client.delegate.roundResponses += listOf(
            LLMStreamEvent.ReasoningChunk("reasoning without summary"), metadata("length"),
            metadata(null, outputTokens = 2048)
        )
        fixture.client.delegate.roundResponses += listOf(LLMStreamEvent.Content("complete summary"), metadata("stop"))
        assertEquals("complete summary", fixture.compact())
        assertEquals(listOf(2048, 4096), fixture.client.delegate.recordedMaxOutputTokens)
        assertEquals(1, fixture.client.delegate.recordedRequests.map { messages -> messages.map { it.content } }.distinct().size)
        assertEquals(0, fixture.client.delegate.contextCheckpointResetCount)
    }

    @Test fun partialLengthTerminatedTextIsDiscardedBeforeTheNextAttempt() = runBlocking {
        val fixture = Fixture()
        fixture.client.delegate.roundResponses += listOf(LLMStreamEvent.Content("incomplete claims"), metadata("length"))
        fixture.client.delegate.roundResponses += listOf(LLMStreamEvent.Content("verified summary"), metadata("stop"))
        assertEquals("verified summary", fixture.compact())
        assertEquals(listOf(2048, 4096), fixture.client.delegate.recordedMaxOutputTokens)
        assertTrue(fixture.client.delegate.recordedRequests.last().none { it.content.contains("incomplete claims") })
    }

    @Test fun outputLimitWithoutUsageStopsAtTheExistingRetryCeiling() = runBlocking {
        val fixture = Fixture()
        fixture.client.delegate.cannedStreamEvents = listOf(LLMStreamEvent.Content("unfinished"), metadata("length"))
        val failure = runCatching { fixture.compact() }.exceptionOrNull()
        assertTrue(failure is ContextCompactionUnavailable)
        assertEquals("COMPACTION_OUTPUT_LIMIT", (failure as ContextCompactionUnavailable).diagnostic)
        assertEquals(listOf(2048, 4096), fixture.client.delegate.recordedMaxOutputTokens)
        assertEquals(0, fixture.client.delegate.contextCheckpointResetCount)
    }

    @Test fun naturalStopAtTheExactOutputBudgetIsAcceptedWithoutRetry() = runBlocking {
        val fixture = Fixture()
        fixture.client.delegate.cannedStreamEvents = listOf(LLMStreamEvent.Content("complete"), metadata("stop", 2048))
        assertEquals("complete", fixture.compact())
        assertEquals(listOf(2048), fixture.client.delegate.recordedMaxOutputTokens)
    }

    @Test fun successfulContentWithoutChatFinishMetadataRemainsSupported() = runBlocking {
        val fixture = Fixture()
        fixture.client.delegate.cannedStreamEvents = listOf(LLMStreamEvent.Content("complete"), metadata(null, 2048))
        assertEquals("complete", fixture.compact())
        assertEquals(listOf(2048), fixture.client.delegate.recordedMaxOutputTokens)
    }

    @Test fun emptyNonTruncatedSummaryHasATypedFailureWithoutAnOutputBudgetRetry() = runBlocking {
        for (reason in listOf("stop", null)) {
            val fixture = Fixture()
            fixture.client.delegate.cannedStreamEvents = listOf(LLMStreamEvent.Content(" \n"), metadata(reason))
            val failure = runCatching { fixture.compact() }.exceptionOrNull()
            assertTrue(failure is ContextCompactionUnavailable)
            assertEquals("COMPACTION_EMPTY_OUTPUT", (failure as ContextCompactionUnavailable).diagnostic)
            assertEquals(listOf(2048), fixture.client.delegate.recordedMaxOutputTokens)
        }
    }

    @Test fun otherExplicitIncompleteTerminationsCannotBecomeASummary() = runBlocking {
        val fixture = Fixture()
        fixture.client.delegate.cannedStreamEvents = listOf(LLMStreamEvent.Content("filtered partial text"), metadata("content_filter"))
        val failure = runCatching { fixture.compact() }.exceptionOrNull()
        assertTrue(failure is ContextCompactionUnavailable)
        assertEquals("COMPACTION_RESPONSE_INCOMPLETE", (failure as ContextCompactionUnavailable).diagnostic)
        assertEquals(listOf(2048), fixture.client.delegate.recordedMaxOutputTokens)
    }

    @Test fun historicalImagesEnableVisionWithoutBecomingCurrentActionFrames() = runBlocking {
        for (includeImage in listOf(false, true)) {
            val fixture = Fixture()
            val bytes = "retained image bytes".toByteArray()
            val ref = fixture.blobs.put(bytes).contentRef
            val items = listOf(UserInputAgentItem("user-1", 1, "round-1", UserInputKind.MESSAGE, "task")) +
                if (includeImage) listOf(ObservationRefAgentItem("image-1", 2, "round-1", "observation-1",
                    ScreenshotObservationContentRef(ref, null))) else emptyList()
            val projection = DurableContextProjection(listOf(ContextUnit("round-1", items)),
                if (includeImage) listOf(ObservationProjectionRef(2, "observation-1", ObservationProjectionMode.IMAGE, ref)) else emptyList())
            assertEquals("complete", fixture.compact(projection))
            assertEquals(listOf(includeImage), fixture.client.imageRequests)
            assertEquals(listOf(true), fixture.client.isolatedRequests)
            assertEquals(listOf(fixture.tools), fixture.client.delegate.recordedTools)
            val images = fixture.client.delegate.recordedRequests.single().filter { it.imageBase64 != null }
            assertEquals(if (includeImage) 1 else 0, images.size)
            images.forEach {
                assertTrue(it.historicalImage)
                assertTrue(it.content.contains("current_screen=false"))
                assertEquals(Base64.getEncoder().encodeToString(bytes), it.imageBase64)
            }
            assertEquals(1, fixture.blobs.count())
            assertEquals(0, fixture.client.delegate.contextCheckpointResetCount)
        }
    }

    private fun metadata(reason: String?, outputTokens: Int? = null) =
        LLMStreamEvent.ResponseMetadata(null, null, outputTokens, finishReason = reason)

    private class Fixture {
        val blobs = InMemoryBlobStore()
        val client = RecordingClient()
        val tools = listOf(ToolDefinition("read", "Read facts", emptyMap()))
        var parent = listOf(ChatMessage(com.droidance.core.model.llm.models.Role.USER, "task"))
        private val compactor = TaskContextCompactor(client, RuleSource { _, _ -> "Summarize retained evidence." }, blobs,
            object : DebugLogAppender {
                override fun appendEvent(event: String) = Unit
                override fun appendPersistentEvent(stream: String, event: String) = Unit
            })
        private val policy = ContextPolicySnapshot(128000, 32768, 8000, 9, 10, 3,
            ContextTokenEstimatorIdentity("generic_chat_completions", "adapter-v1", "model-v1", "serializer-v1",
                "tokenizer-v1", "image-v1", ContextTokenEstimatorCoverage(true, true, true, true, true, true, true)))
        private val execution = ContextCompactionExecutionPolicy(0, 0, 1, 2048, 4096, 24000, 1, 4096)
        suspend fun compact(projection: DurableContextProjection = DurableContextProjection(emptyList(), emptyList()), prompt: String = "task") =
            compactor.compact(projection, policy, prompt, execution, "Frozen operator rules", tools, parent)
    }

    private class RecordingClient(val delegate: FakeModelClient = FakeModelClient(listOf(LLMStreamEvent.Content("complete")))) :
        ModelClient by delegate {
        val imageRequests = mutableListOf<Boolean>()
        val isolatedRequests = mutableListOf<Boolean>()
        val systemPrompts = mutableListOf<String?>()
        override suspend fun streamChat(
            messages: List<ChatMessage>, tools: List<ToolDefinition>, systemPrompt: String?, hasImage: Boolean,
            enableComputerTool: Boolean, computerFrameSize: Pair<Int, Int>?, maxOutputTokens: Int?, requestTimeoutMs: Long?,
            requestCancellationGeneration: Long?, requestPlan: ModelStreamPlan?, isolatedContext: Boolean, reasoningEffort: ReasoningEffort
        ): Flow<LLMStreamEvent> {
            imageRequests += hasImage
            isolatedRequests += isolatedContext
            systemPrompts += systemPrompt
            return delegate.streamChat(messages, tools, systemPrompt, hasImage, enableComputerTool, computerFrameSize,
                maxOutputTokens, requestTimeoutMs, requestCancellationGeneration, requestPlan, isolatedContext, reasoningEffort)
        }
    }
}
