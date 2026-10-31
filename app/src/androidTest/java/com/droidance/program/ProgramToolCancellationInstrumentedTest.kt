// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.program

import com.droidance.core.device.program.*

import com.droidance.program.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.agent.TurnSessionContext
import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.tools.SessionReadService
import com.droidance.core.agent.tools.ToolInvocation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgramToolCancellationInstrumentedTest {
    @Test
    fun canceledProgramInvocationClosesWorkerAndRecordsRevocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = TurnSessionContext("program-cancel-turn", "program-cancel-thread")
        val events = InMemoryAgentEventStore()
        val hash = "c".repeat(64)
        events.createThread(owner.threadId, "Program cancellation test")
        events.startTurn(
            TurnEventScope(owner.threadId, owner.turnId),
            ModelSessionIdentity(owner.threadId, "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS, hash, hash, "fixture", hash),
            "fixture"
        )
        val root = File(context.cacheDir, "program-cancel-blobs-${UUID.randomUUID()}")
        val blobs = FileBlobStore(root)
        val workspace = TaskWorkspace(events, blobs)
        val workspaceRules = context.assets.open("rules/workspace.md").bufferedReader().use { it.readText() }
        val workspacePolicy = Json.decodeFromString<WorkspacePolicy>(
            workspaceRules.substringAfter("## policy\n").substringBefore("\n## ").trim()
        )
        val programRules = context.assets.open("rules/program.md").bufferedReader().use { it.readText() }
        val reader = SessionReadService(events, blobs)
        val tool = ProgramTool(
            context,
            { section -> programRules.substringAfter("## $section\n").substringBefore("\n## ").trim() },
            workspace,
            { workspacePolicy },
            { reader.read(owner.threadId, owner.turnId, it, false) },
            { owner },
            { null },
            { null },
            { null },
            { _, _, _ -> error("cancellation fixture must not request a device") },
            events,
            blobs
        )
        try {
            val job: Job = launch {
                withContext(ToolInvocation(UUID.randomUUID().toString())) {
                    tool.execute("""{"op":"run","code":"while(true){}"}""")
                }
            }
            withTimeout(10_000) {
                while (events.eventsForTurn(owner.turnId).none { event ->
                        event.eventKind == AgentEventKind.PROGRAM_EXECUTION &&
                            Json.parseToJsonElement(event.payloadJson).jsonObject["phase"]?.jsonPrimitive?.content == "started"
                    }) {
                    delay(10)
                }
            }
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)

            val revoked = events.eventsForTurn(owner.turnId).single { event ->
                event.eventKind == AgentEventKind.PROGRAM_EXECUTION &&
                    Json.parseToJsonElement(event.payloadJson).jsonObject["phase"]?.jsonPrimitive?.content == "revoked"
            }
            val data = Json.parseToJsonElement(revoked.payloadJson).jsonObject["data"]!!.jsonObject
            assertEquals("invocation_canceled", data["reason"]!!.jsonPrimitive.content)
        } finally {
            owner.clear()
            root.deleteRecursively()
        }
    }
}
