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
import com.droidance.core.agent.workspace.*
import com.droidance.core.agent.llm.session.*
import com.droidance.core.model.llm.session.*
import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*
import com.droidance.tools.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProgramShellInstrumentedTest {
    @Test fun isolatedRhinoConsumesRealShellDataAndLogsIndividualHostCalls() = runBlocking {
        Fixture().use { fixture ->
            val body = fixture.invoke(buildJsonObject {
                put("op", "run"); put("device_access", true)
                put("code", "for(var i=0;i<3;i++){var r=android.call('shell',{command:'printf '+i,expect_screen_change:false});print(r.data.stdout);}")
            }).data!!.jsonObject
            assertEquals("exited", body.getValue("status").jsonPrimitive.content)
            assertEquals("0\n1\n2\n", body.getValue("stdout").jsonObject.getValue("text").jsonPrimitive.content)
            val calls = ProgramShellCallProjector.project(fixture.events.eventsForTurn(fixture.owner.turnId), fixture.owner.turnId)
            assertEquals(listOf("printf 0", "printf 1", "printf 2"), calls.map { it.command })
            assertTrue(calls.all { it.result?.executionStatus == ToolExecutionStatus.SUCCEEDED })
        }
    }

    @Test fun previouslyBoundHandleCannotDispatchDuringLocalPoll() = runBlocking {
        Fixture().use { fixture ->
            val first = fixture.invoke(buildJsonObject {
                put("op", "run"); put("device_access", true)
                put("code", "android.call('shell',{command:'printf once',expect_screen_change:false});")
            }, allowed = false).data!!.jsonObject
            assertEquals("waiting_for_device", first.getValue("status").jsonPrimitive.content)
            val handle = first.getValue("handle")
            val withheld = fixture.invoke(buildJsonObject { put("op", "poll"); put("handle", handle) }, allowed = false)
            assertEquals("waiting_for_device", withheld.data!!.jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(0, fixture.calls)
            val completed = fixture.invoke(buildJsonObject { put("op", "poll"); put("handle", handle); put("device_access", true) })
            assertEquals("exited", completed.data!!.jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(1, fixture.calls)
        }
    }

    @Test fun visualShellBoundaryWithholdsTheNextCommand() = runBlocking {
        Fixture().use { fixture ->
            val result = fixture.invoke(buildJsonObject {
                put("op", "run"); put("device_access", true)
                put("code", "android.call('shell',{command:'printf first'});android.call('shell',{command:'printf second'});")
            })
            assertEquals("waiting_for_observation", result.data!!.jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(1, fixture.calls)
            assertFalse(result.data!!.jsonObject.getValue("boundary").jsonObject.getValue("pending_executed").jsonPrimitive.boolean)
        }
    }

    private class Fixture : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = TurnSessionContext(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val events = InMemoryAgentEventStore()
        val root = File(context.cacheDir, "program-shell-${UUID.randomUUID()}")
        val blobs = FileBlobStore(root)
        val workspace = TaskWorkspace(events, blobs)
        val shell = ManagedShellCommands()
        var calls = 0
        private val programRules = context.assets.open("rules/program.md").bufferedReader().use { it.readText() }
        private val workspaceRules = context.assets.open("rules/workspace.md").bufferedReader().use { it.readText() }
        private val policy = Json.decodeFromString<WorkspacePolicy>(section(workspaceRules, "policy"))
        private val binding = ExecutionContext.fromStrategy("execution", "backend", DeviceExecutionStrategy(
            DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE)), 123)
        private val tool: ProgramTool
        init {
            val hash = "4".repeat(64)
            runBlocking {
                events.createThread(owner.threadId, "Program shell test")
                events.startTurn(TurnEventScope(owner.threadId, owner.turnId), ModelSessionIdentity(owner.threadId,
                    "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS, hash, hash, "fixture", hash), "fixture")
            }
            val reader = SessionReadService(events, blobs)
            tool = ProgramTool(context, { section(programRules, it) }, workspace, { policy },
                { reader.read(owner.threadId, owner.turnId, it, false) }, { owner }, { binding }, { null }, { null },
                { name, args, _ ->
                    assertEquals("shell", name)
                    val budget = currentCoroutineContext()[ToolExecutionBudget]!!
                    assertTrue(budget.deviceAccessAllowed)
                    assertTrue(budget.mutationsAllowed)
                    assertTrue(budget.remainingMs() in 1..12_000)
                    calls++
                    val receipt = withContext(Dispatchers.IO) { shell.execute(UUID.randomUUID().toString(),
                        args.getValue("command").jsonPrimitive.content, 5000) }
                    val data = buildJsonObject {
                        put("stdout", receipt.stdout); put("stderr", receipt.stderr); put("exit_code", receipt.exitCode)
                    }
                    ToolExecutionResult(data.toString(), ToolExecutionStatus.valueOf(receipt.executionStatus),
                        ToolEvidenceStatus.NOT_REQUIRED, data = data)
                }, events, blobs)
        }
        suspend fun invoke(args: JsonObject, allowed: Boolean = true): ToolExecutionResult =
            withContext(ToolExecutionBudget(12_000, allowed, true) + ToolInvocation(UUID.randomUUID().toString())) {
                tool.execute(args.toString())
            }
        override fun close() { owner.clear(); root.deleteRecursively() }
        private fun section(markdown: String, name: String) = markdown.substringAfter("## $name\n").substringBefore("\n## ").trim()
    }
}
