// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.event

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*

import com.droidance.agent.event.*

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestConversationQueryTest {
    @Test fun restorationReplaysOnlyTheLatestValidNonDeletedConversation() = runBlocking {
        val reads = AtomicInteger()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentEventDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql == "SELECT * FROM agent_events WHERE thread_id = ? ORDER BY sequence") reads.incrementAndGet()
            }, Executor { it.run() }).build()
        try {
            val store = RoomAgentEventStore(database)
            assertNull(store.latestConversationThreadId())
            repeat(40) { index ->
                val id = "thread-$index"
                val scope = TurnEventScope(id, "turn-$index")
                store.createThread(id, id)
                store.startTurn(scope, identity(id), "snapshot")
                store.recordUserMessage(scope, "message-$index", "Retained conversation $index")
                store.completeTurn(id, scope.turnId, TurnResult("done", assistantReply = "Reply $index"))
            }
            store.createThread("empty", "Empty thread")
            store.changeThreadPin("thread-0", "pin", true)
            store.changeThreadMetadata("thread-0", "rename", "Renamed", null)
            reads.set(0)
            assertEquals("thread-39", store.latestConversationThreadId())
            assertEquals("Restoration must not replay all 40 conversations", 1, reads.get())
            store.tombstoneThread("thread-39")
            reads.set(0)
            assertEquals("thread-38", store.latestConversationThreadId())
            assertEquals(1, reads.get())
            // Preserve the existing quarantine behavior when the newest stream is invalid.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE agent_events SET payload_json = '{}' WHERE thread_id = 'thread-38' AND event_kind = 'USER_MESSAGE'"
            )
            reads.set(0)
            assertEquals("thread-37", store.latestConversationThreadId())
            assertEquals(2, reads.get())
            val scope = TurnEventScope("thread-0", "open-turn")
            store.startTurn(scope, identity(scope.threadId), "snapshot")
            store.recordUserMessage(scope, "new-input", "Still running")
            assertEquals("thread-0", store.latestConversationThreadId())
            store.recordUserSteer(scope, "guidance", "Additional guidance")
            assertEquals("thread-0", store.latestConversationThreadId())
        } finally {
            database.close()
        }
    }

    private fun identity(threadId: String) = ModelSessionIdentity(threadId, "provider",
        ModelTransport.OPENAI_RESPONSES, "1".repeat(64), "2".repeat(64), "model", "3".repeat(64))
}
