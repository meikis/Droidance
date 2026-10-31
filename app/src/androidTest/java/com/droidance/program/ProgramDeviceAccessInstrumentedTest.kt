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
import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*
import com.droidance.agent.device.*
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.agent.llm.session.*
import com.droidance.core.model.llm.session.*
import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*
import com.droidance.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Runs the actual isolated worker and host bridge; the backend sink performs no phone action. */
@RunWith(AndroidJUnit4::class)
class ProgramDeviceAccessInstrumentedTest {
    @Test fun omittedAccessCannotInheritAnExistingBackend() = runBlocking {
        assertExplicitGrantRequired(null)
    }

    @Test fun falseAccessCannotInheritAnExistingBackend() = runBlocking {
        assertExplicitGrantRequired(false)
    }

    @Test fun explicitAccessWithoutABackendFailsBeforeAnyScriptEffect() = runBlocking {
        val fixture = Fixture.create()
        try {
            fixture.binding = null
            val result = fixture.invoke(buildJsonObject {
                put("op", "run"); put("device_access", true)
                put("code", "workspace.write('must-not-exist.txt','unauthorized prefix');android.call('android_read',{op:'system',service:'battery'});")
            })
            assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
            assertTrue(result.outputText.contains("DEVICE_BINDING_REQUIRED"))
            assertTrue(fixture.workspace.list(fixture.scope).isEmpty())
            assertTrue(fixture.deviceCalls.isEmpty())
            assertFalse(fixture.events.eventsForTurn(fixture.owner.turnId).any {
                it.eventKind == AgentEventKind.PROGRAM_EXECUTION &&
                    Json.parseToJsonElement(it.payloadJson).jsonObject["phase"]?.jsonPrimitive?.content == "started"
            })
        } finally { fixture.close() }
    }

    @Test fun appUidReadDoesNotRequireBackendAccess() = runBlocking {
        val fixture = Fixture.create()
        try {
            fixture.binding = null
            val result = fixture.invoke(buildJsonObject {
                put("op", "run"); put("device_access", false)
                put("code", "android.call('android_read',{op:'device'});workspace.write('local.txt','read completed');")
            })
            assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
            assertEquals("exited", Json.parseToJsonElement(result.outputText).jsonObject["status"]?.jsonPrimitive?.content)
            assertEquals(listOf("android_read:device"), fixture.deviceCalls)
            assertEquals("read completed", fixture.workspace.read(fixture.scope, "local.txt", fixture.workspacePolicy))
        } finally { fixture.close() }
    }

    private suspend fun assertExplicitGrantRequired(access: Boolean?) {
        val fixture = Fixture.create()
        try {
            val first = fixture.invoke(buildJsonObject {
                put("op", "run"); access?.let { put("device_access", it) }
                put("code", "workspace.write('before.txt','prefix');android.call('android_read',{op:'system',service:'battery'});workspace.write('after.txt','suffix');print('done');")
            })
            assertEquals(ToolExecutionStatus.SUCCEEDED, first.executionStatus)
            val body = Json.parseToJsonElement(first.outputText).jsonObject
            assertEquals("waiting_for_device", body["status"]?.jsonPrimitive?.content)
            assertEquals(0, body["attempted_child_calls"]?.jsonPrimitive?.int)
            assertEquals("prefix", fixture.workspace.read(fixture.scope, "before.txt", fixture.workspacePolicy))
            assertFalse(fixture.workspace.list(fixture.scope).any { it.path == "after.txt" })
            assertTrue(fixture.deviceCalls.isEmpty())
            val workerUid = body["worker"]?.jsonObject?.get("uid")?.jsonPrimitive?.int
            assertNotNull(workerUid)
            assertNotEquals(android.os.Process.myUid(), workerUid)
            assertTrue(body.getValue("worker").jsonObject.getValue("isolated").jsonPrimitive.boolean)

            val handle = body.getValue("handle").jsonPrimitive.content
            for (grant in listOf<Boolean?>(null, false)) {
                val held = fixture.invoke(buildJsonObject {
                    put("op", "poll"); put("handle", handle)
                    grant?.let { put("device_access", it) }
                })
                assertEquals("waiting_for_device", Json.parseToJsonElement(held.outputText)
                    .jsonObject["status"]?.jsonPrimitive?.content)
                assertTrue(fixture.deviceCalls.isEmpty())
            }
            val granted = fixture.invoke(buildJsonObject {
                put("op", "poll"); put("handle", handle); put("device_access", true)
            })
            val completed = Json.parseToJsonElement(granted.outputText).jsonObject
            assertEquals(ToolExecutionStatus.SUCCEEDED, granted.executionStatus)
            assertEquals("exited", completed["status"]?.jsonPrimitive?.content)
            assertEquals(1, completed["completed_child_calls"]?.jsonPrimitive?.int)
            assertEquals(listOf("android_read:system"), fixture.deviceCalls)
            assertEquals("suffix", fixture.workspace.read(fixture.scope, "after.txt", fixture.workspacePolicy))
            assertEquals(1, fixture.events.eventsForTurn(fixture.owner.turnId).count {
                it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN &&
                    Json.parseToJsonElement(it.payloadJson).jsonObject["path"]?.jsonPrimitive?.content == "before.txt"
            })
        } finally { fixture.close() }
    }

    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = TurnSessionContext("grant-turn-${UUID.randomUUID()}", "grant-thread-${UUID.randomUUID()}")
        val scope = TurnEventScope(owner.threadId, owner.turnId)
        val events = InMemoryAgentEventStore()
        val root = File(context.cacheDir, "program-grant-${UUID.randomUUID()}")
        val blobs = FileBlobStore(root)
        val workspace = TaskWorkspace(events, blobs)
        val workspaceMarkdown = context.assets.open("rules/workspace.md").bufferedReader().use { it.readText() }
        val workspacePolicy = Json.decodeFromString<WorkspacePolicy>(section(workspaceMarkdown, "policy"))
        val programMarkdown = context.assets.open("rules/program.md").bufferedReader().use { it.readText() }
        val deviceCalls = mutableListOf<String>()
        var binding: ExecutionContext? = ExecutionContext.fromStrategy("fixture-execution", "fixture-backend",
            DeviceExecutionStrategy(DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
                ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE)), 123)
        val tool: ProgramTool
        init {
            val reader = SessionReadService(events, blobs)
            tool = ProgramTool(context, { section(programMarkdown, it) }, workspace, { workspacePolicy },
                { reader.read(owner.threadId, owner.turnId, it, false) }, { owner }, { binding }, { null }, { null },
                { name, args, _ ->
                    assertEquals("android_read", name)
                    val operation = args.getValue("op").jsonPrimitive.content
                    assertTrue(operation in setOf("device", "system"))
                    if (operation == "system") assertEquals("battery", args["service"]?.jsonPrimitive?.content)
                    deviceCalls += "$name:$operation"
                    ToolProtocolResult.success("read-only backend fixture")
                }, events, blobs)
        }
        suspend fun invoke(arguments: JsonObject) = withContext(ToolInvocation(UUID.randomUUID().toString())) {
            tool.execute(arguments.toString())
        }
        fun close() { owner.clear(); root.deleteRecursively() }
        companion object {
            suspend fun create(): Fixture = Fixture().also { fixture ->
                val hash = "3".repeat(64)
                fixture.events.createThread(fixture.owner.threadId, "Explicit program device grant")
                fixture.events.startTurn(fixture.scope, ModelSessionIdentity(fixture.owner.threadId, "fixture",
                    ModelTransport.GENERIC_CHAT_COMPLETIONS, hash, hash, "fixture", hash), "fixture")
            }
            private fun section(markdown: String, name: String) =
                markdown.substringAfter("## $name\n").substringBefore("\n## ").trim()
        }
    }
}
