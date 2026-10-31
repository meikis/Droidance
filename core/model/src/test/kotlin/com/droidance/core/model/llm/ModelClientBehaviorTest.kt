// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelClientBehaviorTest {

    @Test
    fun fakeModelClientSatisfiesModelClientContract() = runBlocking {
        val fake = FakeModelClient(
            cannedStreamEvents = listOf(
                LLMStreamEvent.Content("Hello from fake model!"),
                LLMStreamEvent.ResponseMetadata(returnedModel = "fake_model", inputTokens = 10, outputTokens = 5)
            )
        )

        val plan = fake.planStreamChat(
            messages = listOf(ChatMessage(role = Role.USER, content = "Hi")),
            hasImage = true
        )
        assertEquals("fake_model", plan.initialModel)
        assertTrue(plan.imageIncluded)

        val events = fake.streamChat(
            messages = listOf(ChatMessage(role = Role.USER, content = "Hi"))
        ).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is LLMStreamEvent.Content)
        assertEquals("Hello from fake model!", (events[0] as LLMStreamEvent.Content).text)
        assertTrue(events[1] is LLMStreamEvent.ResponseMetadata)

        fake.beginTurn()
        assertEquals(1, fake.turnDepth)
        fake.endTurn()
        assertEquals(0, fake.turnDepth)

        val gen = fake.currentCancellationGeneration()
        assertFalse(fake.wasCancellationRequestedSince(gen))
        fake.cancelActiveRequests()
        assertTrue(fake.wasCancellationRequestedSince(gen))
        assertTrue(fake.activeRequestsCancelled)
    }
}
