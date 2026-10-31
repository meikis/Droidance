// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.models.LLMStreamEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OperatorStreamCollectorTest {
    @Test fun localReasoningConfigurationRecordsFallbackWithoutPretendingTheProviderResponded() = runBlocking {
        val model = FakeModelClient(cannedStreamEvents = listOf(LLMStreamEvent.ReasoningConfiguration(
            com.droidance.core.model.llm.models.ReasoningEffort.MEDIUM,
            com.droidance.core.model.llm.models.ReasoningEffort.DEFAULT,
            "unsupported_or_unknown_capability", listOf(com.droidance.core.model.llm.models.ReasoningEffort.DEFAULT))))
        var metrics = emptyMap<String, String>()
        OperatorStreamCollector(model) {}.collect(
            requestPlan = model.planStreamChat(emptyList(), false), messages = emptyList(),
            tools = emptyList(), systemPrompt = "fixture", hasImage = false, timeoutMs = 5000,
            cancellationGeneration = 0, maxOutputTokens = 100, enableComputerTool = false,
            computerFrameSize = null, startedAt = System.currentTimeMillis(),
            onMetrics = { metrics = it }, onContent = {})
        assertEquals("default", metrics["sent_reasoning_effort"])
        assertEquals("unsupported_or_unknown_capability", metrics["reasoning_fallback_reason"])
        assertEquals("1", metrics["reasoning_configuration_attempts"])
        assertEquals("-1", metrics["first_stream_event_ms"])
        assertEquals("", metrics["first_provider_event_ms"])
        assertEquals("", metrics["last_stream_event_ms"])
    }

    @Test fun activityAndReasoningUsageAreMeasuredWithoutEnteringThePublicReply() = runBlocking {
        val model = FakeModelClient(cannedStreamEvents = listOf(
            LLMStreamEvent.Activity(LLMStreamEvent.Activity.Phase.RESPONSE_STARTED),
            LLMStreamEvent.Activity(LLMStreamEvent.Activity.Phase.REASONING, 17),
            LLMStreamEvent.Activity(LLMStreamEvent.Activity.Phase.REASONING_FINISHED),
            LLMStreamEvent.Content("public result"),
            LLMStreamEvent.ResponseMetadata("fixture", 100, 30, 0, 20, cacheWriteTokens = 80)))
        var metrics = emptyMap<String, String>()
        val public = StringBuilder()
        val result = OperatorStreamCollector(model) {}.collect(
            requestPlan = model.planStreamChat(emptyList(), false), messages = emptyList(),
            tools = emptyList(), systemPrompt = "fixture", hasImage = false, timeoutMs = 5000,
            cancellationGeneration = 0, maxOutputTokens = 100, enableComputerTool = false,
            computerFrameSize = null, startedAt = System.currentTimeMillis(),
            onMetrics = { metrics = it }, onContent = { public.append(it) })
        assertEquals("public result", public.toString())
        assertEquals("", result.reasoning)
        assertEquals("20", metrics["response_reasoning_tokens"])
        assertEquals("80", metrics["response_cache_write_tokens"])
        assertEquals("0", metrics["response_cached_input_tokens"])
        assertEquals(metrics["first_content_event_ms"], metrics["first_actionable_output_ms"])
        assertEquals("17", metrics["response_reasoning_chars"])
        assertTrue(metrics.getValue("first_provider_event_ms").toLong() >= 0)
        assertTrue(metrics.getValue("first_content_event_ms").toLong() >= metrics.getValue("first_provider_event_ms").toLong())
        assertTrue(metrics.getValue("reasoning_stream_ms").toLong() >= 0)
    }
}
