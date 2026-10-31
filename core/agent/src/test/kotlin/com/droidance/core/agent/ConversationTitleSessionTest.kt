// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleSessionTest {
    @Test fun aGeneratedTitleMatchingTheInitialSnippetStillRecordsItsOrigin() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-origin", "杭州行程规划")
        val session = ConversationTitleSession(fixture.eventStore, started.active.scope)
        session.commit(session.request(), "杭州行程规划", "origin-round")
        fixture.eventStore.recordUserSteer(started.active.scope, "refine", "增加一天")
        assertTrue(session.request().context.contains("\"title_state\":\"generated\""))
        assertEquals(1, fixture.eventStore.eventsForThread(started.active.scope.threadId)
            .count { it.eventKind == AgentEventKind.THREAD_METADATA_CHANGED })
    }

    @Test fun noNewInputMeansNoRenamingWithinTheSession() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-frequency", "Plan the trip")
        val session = ConversationTitleSession(fixture.eventStore, started.active.scope)
        val request = session.request()
        assertTrue(request.updateAllowed)
        session.commit(request, "杭州行程规划", "first-round")
        assertFalse(session.request().updateAllowed)
        assertEquals("杭州行程规划", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
        // The committed input sequence is carried across Phase A and the activated loop.
        assertEquals(request.inputSequence.toString(), request.finishMetadata(null)[ConversationTitleSession.INPUT_SEQUENCE])
    }

    @Test fun newerUserInputAllowsOneNewDecisionAndAStaleResponseCannotRename() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-steer", "Compare headphones")
        val session = ConversationTitleSession(fixture.eventStore, started.active.scope)
        val stale = session.request()
        fixture.eventStore.recordUserSteer(started.active.scope, "new-goal", "Plan Hangzhou instead")
        session.commit(stale, "耳机对比", "stale-round")
        assertTrue(fixture.eventStore.eventsForThread(started.active.scope.threadId)
            .none { it.eventKind == AgentEventKind.THREAD_METADATA_CHANGED })
        val current = session.request()
        assertTrue(current.updateAllowed)
        session.commit(current, "杭州行程规划", "current-round")
        assertFalse(session.request().updateAllowed)
        assertEquals("杭州行程规划", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
    }

    @Test fun anInitialSnippetStaysPendingAfterOmissionButRecoveryCannotRequestNaming() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-greeting", "Hello")
        val session = ConversationTitleSession(fixture.eventStore, started.active.scope)
        assertFalse(session.request(eligible = false).updateAllowed)
        val request = session.request()
        session.commit(request, null, "greeting-round")
        assertTrue(session.request().updateAllowed)
        assertFalse(session.request(eligible = false).updateAllowed)
        assertTrue(ConversationTitleSession(fixture.eventStore, started.active.scope).request().updateAllowed)
        fixture.eventStore.recordUserSteer(started.active.scope, "substantive", "Plan my trip")
        assertTrue(session.request().updateAllowed)
        assertEquals("Hello", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
    }

    @Test fun aNewInputCanRefineTheMainTaskTitleWithoutAReplacementDeclaration() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-evolving-topic", "Improve conversations")
        val session = ConversationTitleSession(fixture.eventStore, started.active.scope)
        session.commit(session.request(), "优化对话体验", "initial")
        fixture.eventStore.recordUserSteer(started.active.scope, "refinement", "Focus on concise sidebar titles")
        val refinement = session.request()
        assertEquals("精简侧边栏对话标题", refinement.finishMetadata("精简侧边栏对话标题")["conversation_title"])
        session.commit(refinement, "精简侧边栏对话标题", "refinement")
        assertEquals("精简侧边栏对话标题", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
        fixture.eventStore.recordUserSteer(started.active.scope, "replacement", "Now compare headphones")
        val replacement = session.request()
        session.commit(replacement, "耳机选购对比", "replacement")
        assertEquals("耳机选购对比", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
    }

    @Test fun anExistingVerboseAutomaticTitleCanBeShortenedAndAnUnchangedTitleDoesNotWriteMetadata() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-shorten", "Please simplify the sidebar conversation titles")
        fixture.eventStore.changeThreadMetadata(started.active.scope.threadId, "auto-title:previous",
            "Please simplify the sidebar conversation titles and update them as the main task evolves", null)
        val session = ConversationTitleSession(fixture.eventStore, started.active.scope)
        val request = session.request()
        assertTrue(request.updateAllowed)
        assertTrue(request.context.contains("\"max_title_chars\":36"))
        session.commit(request, "精简侧边栏对话标题", "shorten")
        fixture.eventStore.recordUserSteer(started.active.scope, "retry", "Please try again")
        session.commit(session.request(), "精简侧边栏对话标题", "unchanged")
        assertFalse(session.request().updateAllowed)
        assertEquals("精简侧边栏对话标题", fixture.eventStore.threadReadModel(started.active.scope.threadId)!!.metadata.title)
        assertEquals(2, fixture.eventStore.eventsForThread(started.active.scope.threadId)
            .count { it.eventKind == AgentEventKind.THREAD_METADATA_CHANGED })
    }

    @Test fun manualMetadataIsPreservedIncludingAnEditDuringTheModelRequest() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("title-manual", "Plan a trip")
        val session = ConversationTitleSession(fixture.eventStore, started.active.scope)
        val request = session.request()
        fixture.eventStore.changeThreadMetadata(started.active.scope.threadId, "user-edit", "My title", "My summary")
        fixture.eventStore.changeThreadPin(started.active.scope.threadId, "pin", true)
        session.commit(request, "Automatic title", "model-round")
        val protected = session.request()
        assertFalse(protected.updateAllowed)
        val thread = fixture.eventStore.threadReadModel(started.active.scope.threadId)!!
        assertEquals("My title", thread.metadata.title)
        assertEquals("My summary", thread.metadata.summary)
        assertTrue(thread.metadata.pinned)
    }
}
