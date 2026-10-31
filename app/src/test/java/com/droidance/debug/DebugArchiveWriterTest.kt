// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import com.droidance.core.agent.prompt.sha256Hex
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugArchiveWriterTest {
    private val json = Json { encodeDefaults = true }
    private val blobs = InMemoryBlobStore()
    private var sequence = 0L
    private inline fun <reified T> event(thread: String, turn: String?, kind: AgentEventKind, payload: T): AgentEventEnvelope {
        val body = canonicalizeJson(json.encodeToString(payload))
        val seq = ++sequence
        return AgentEventEnvelope(AGENT_EVENT_SCHEMA_VERSION, "event-$seq", kind, seq, seq * 10,
            thread, turn, null, null, null, null, sha256Hex(body), body, "event-$seq")
    }
    private fun turn(thread: String, id: String, text: String, complete: Boolean, attachments: List<UserAttachmentPayload> = emptyList()) = listOfNotNull(
        event(thread, null, AgentEventKind.THREAD_CREATED, ThreadCreatedEventPayload(1, thread)),
        event(thread, id, AgentEventKind.TURN_STARTED, TurnStartedEventPayload(1,
            ModelSessionIdentity(thread, "provider", ModelTransport.GENERIC_CHAT_COMPLETIONS,
                "1".repeat(64), "2".repeat(64), "model", "3".repeat(64)), "snapshot")),
        event(thread, id, AgentEventKind.USER_MESSAGE, UserTextEventPayload(1, "message-$id", text, attachments)),
        if (complete) event(thread, id, AgentEventKind.TURN_COMPLETED, TurnCompletedEventPayload(1, "done", assistantReply = "reply")) else null
    )
    private fun archive(events: List<AgentEventEnvelope>): Map<String, ByteArray> = runBlocking {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { DebugArchiveWriter(blobs).write(it, events, buildJsonObject {}) }
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        files
    }
    private fun Map<String, ByteArray>.objectAt(path: String) = json.parseToJsonElement(getValue(path).decodeToString()).jsonObject

    @Test fun greetingDoesNotHideEarlierHistoryAndOpenTurnsOrSharedImages() {
        val image = blobs.put(byteArrayOf(1, 2, 3, 4))
        val attachment = UserAttachmentPayload("image", image.contentRef, image.contentHash, image.sizeBytes, "image/png")
        val old = turn("old-thread", "old", "Investigate the earlier failure", true, listOf(attachment))
        val hello = turn("greeting-thread", "greeting", "你好", true)
        val active = turn("active-thread", "active", "Still running", false, listOf(attachment))
        val events = old + hello + active
        val files = archive(events)
        val index = files.objectAt("index.json")
        assertEquals(3, index["task_count"]!!.jsonPrimitive.int)
        assertEquals(events.size, index["event_count"]!!.jsonPrimitive.int)
        val tasks = index["tasks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("old", "greeting", "active"), tasks.map { it["turn_id"]!!.jsonPrimitive.content }.toSet())
        val activePath = tasks.single { it["turn_id"]!!.jsonPrimitive.content == "active" }["path"]!!.jsonPrimitive.content
        assertEquals(JsonNull, files.objectAt("$activePath/eventstore/provider-harness.json")["turnResult"])
        assertTrue(files.getValue("eventstore/agent-events.jsonl").decodeToString().contains("Investigate the earlier failure"))
        assertEquals(1, files.keys.count { it.endsWith(".png") })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), files.getValue("attachments/${image.contentHash}.png"))
        val manifest = files.objectAt("manifest.json")
        assertTrue(manifest["complete"]!!.jsonPrimitive.boolean)
        assertEquals(files.keys, manifest["files"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet())
    }

    @Test fun unavailableScreenshotIsExplicitAndDoesNotDiscardOtherTasks() {
        val image = blobs.put("missing image".toByteArray())
        val events = turn("t", "turn", "task", true, listOf(UserAttachmentPayload("image", image.contentRef, image.contentHash, image.sizeBytes, "image/png")))
        blobs.delete(image.contentRef)
        val files = archive(events)
        assertFalse(files.objectAt("manifest.json")["complete"]!!.jsonPrimitive.boolean)
        val index = json.parseToJsonElement(files.getValue("attachments/index.json").decodeToString()).jsonArray
        assertEquals("unavailable", index.single().jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(files.containsKey("eventstore/agent-events.jsonl"))
    }

    @Test fun projectionFailureStillExportsRawEventsAndAllOtherTasks() {
        val first = turn("t1", "bad", "bad", true).map { if (it.eventKind == AgentEventKind.TURN_COMPLETED) it.copy(payloadJson = "{}") else it }
        val second = turn("t2", "good", "good", true)
        val files = archive(first + second)
        assertFalse(files.objectAt("manifest.json")["complete"]!!.jsonPrimitive.boolean)
        val tasks = files.objectAt("index.json")["tasks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, tasks.size)
        assertEquals(JsonNull, tasks.single { it["turn_id"]!!.jsonPrimitive.content == "good" }["projection_error"])
        assertEquals(first.size + second.size, files.getValue("eventstore/agent-events.jsonl").decodeToString().lineSequence().count(String::isNotBlank))
    }
}
