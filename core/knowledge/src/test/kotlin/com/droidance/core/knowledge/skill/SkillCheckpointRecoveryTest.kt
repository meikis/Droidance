// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SkillCheckpointRecoveryTest {
    @Test fun terminalWorkerPreservesCompletedAndUnknownItemsAcrossReopen() = fixture { storage, engine, snapshot ->
        val token = engine.beginExecution(snapshot, parameters, "operation", entryPath = "scripts/main.js")
        engine.checkpoint(token, "a", "begin", "worker")
        engine.recordCheckpointCall(token, "worker:1", "android_act", "args-a", "start-a", "started")
        engine.recordCheckpointCall(token, "worker:1", "android_act", "args-a", "end-a", "succeeded")
        engine.checkpoint(token, "a", "complete", "worker")
        engine.checkpoint(token, "b", "begin", "worker")
        engine.recordCheckpointCall(token, "worker:2", "android_act", "args-b", "start-b", "started")
        engine.recordCheckpointCall(token, "worker:2", "android_act", "args-b", "unknown-b", "unknown")
        engine.settleExecution(token, "canceled", "worker")
        engine.settleExecution(token, "canceled", "worker")
        assertThrows(IllegalStateException::class.java) { engine.settleExecution(token, "waiting_for_input", "worker") }
        val reopened = SkillEngine(storage, RecordingSkillScriptValidator())
        assertThrows(IllegalStateException::class.java) {
            reopened.beginExecution(snapshot, parameters, "operation", resume = true, entryPath = "scripts/main.js")
        }
        assertEquals("completed", reopened.checkpoint(token, "a", "read")["state"]!!.jsonPrimitive.content)
        assertThrows(IllegalStateException::class.java) { reopened.checkpoint(token, "b", "begin") }
        assertThrows(IllegalStateException::class.java) { reopened.checkpoint(token, "b", "complete") }
        assertThrows(IllegalStateException::class.java) { reopened.checkpoint(token, "new-identity", "begin") }
        val calls = reopened.executionRecord("recovery", "operation")!!["items"]!!.jsonObject["b"]!!.jsonObject["calls"]!!.jsonObject
        assertEquals("unknown-b", calls["worker:2"]!!.jsonObject["event_ref"]!!.jsonPrimitive.content)
    }

    @Test fun unresolvedAssessmentCannotUnlockReplayAndConfirmedNonExecutionKeepsItsAudit() = fixture { _, engine, snapshot ->
        val token = engine.beginExecution(snapshot, parameters, "operation", entryPath = "scripts/main.js")
        engine.checkpoint(token, "item", "begin", "worker")
        engine.recordCheckpointCall(token, "worker:1", "android_act", "args", "start", "started")
        assertThrows(IllegalStateException::class.java) { engine.checkpoint(token, "item", "complete") }
        engine.settleExecution(token, "interrupted", "worker")
        val unknown = engine.reconcileCheckpoint("recovery", "operation", snapshot.revision, "item", "unknown", resolution())
        assertEquals("pending", unknown["state"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            engine.reconcileCheckpoint("recovery", "operation", snapshot.revision, "item", "completed", resolution())
        }
        assertThrows(IllegalStateException::class.java) {
            engine.reconcileCheckpoint("recovery", "operation", "0".repeat(64), "item", "completed", resolution("current-frame"))
        }
        engine.reconcileCheckpoint("recovery", "operation", snapshot.revision, "item", "not_executed", resolution("current-frame"))
        engine.beginExecution(snapshot, parameters, "operation", resume = true, entryPath = "scripts/main.js")
        assertTrue(engine.checkpoint(token, "item", "begin", "new-worker")["execute"]!!.jsonPrimitive.boolean)
        val item = engine.executionRecord("recovery", "operation")!!["items"]!!.jsonObject["item"]!!.jsonObject
        assertEquals(2, item["resolutions"]!!.jsonArray.size)
        assertEquals("start", item["resolutions"]!!.jsonArray.last().jsonObject["original_calls"]!!.jsonObject["worker:1"]!!.jsonObject["event_ref"]!!.jsonPrimitive.content)
    }

    @Test fun confirmedCompletionSkipsItemAndFailedReceiptCannotBeCompletedByScript() = fixture { _, engine, snapshot ->
        val token = engine.beginExecution(snapshot, parameters, "operation", entryPath = "scripts/main.js")
        engine.checkpoint(token, "item", "begin", "worker")
        engine.recordCheckpointCall(token, "worker:1", "android_act", "args", "start", "started")
        engine.recordCheckpointCall(token, "worker:1", "android_act", "args", "failed", "failed")
        assertThrows(IllegalStateException::class.java) { engine.checkpoint(token, "item", "complete") }
        engine.settleExecution(token, "device_stopped", "worker")
        engine.reconcileCheckpoint("recovery", "operation", snapshot.revision, "item", "completed", resolution("current-frame"))
        engine.beginExecution(snapshot, parameters, "operation", resume = true, entryPath = "scripts/main.js")
        assertFalse(engine.checkpoint(token, "item", "begin")["execute"]!!.jsonPrimitive.boolean)
    }

    private val parameters = JsonObject(emptyMap())
    private fun resolution(vararg refs: String) = buildJsonObject { put("evidence_refs", JsonArray(refs.map(::JsonPrimitive))) }
    private fun fixture(block: (TempSkillDocumentStorage, SkillEngine, SkillExecutionSnapshot) -> Unit) {
        TempSkillDocumentStorage().use { storage ->
            val engine = SkillEngine(storage, RecordingSkillScriptValidator())
            val saved = engine.saveDraft("""
                ---
                name: recovery
                description: Recover a parameterized operation.
                ---
                Check current evidence before resuming.
            """.trimIndent(), mapOf("scripts/main.js" to "print('result');"))
            engine.publishDraft(saved.id, saved.revision)
            block(storage, engine, engine.findExecutionSnapshot(saved.id)!!)
        }
    }
}
