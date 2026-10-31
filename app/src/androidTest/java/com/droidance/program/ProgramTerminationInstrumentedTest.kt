// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.program

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.TurnSessionContext
import com.droidance.core.agent.device.*
import com.droidance.core.agent.event.*
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.agent.tools.ToolInvocation
import com.droidance.core.agent.tools.ToolProtocolResult
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.knowledge.skill.SkillEngine
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.memory.MarkdownFileManager
import com.droidance.skill.RhinoSkillScriptValidator
import com.droidance.tools.SessionReadService
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgramTerminationInstrumentedTest {
    @Test fun failedChildReturnsEveryReceiptAndRepeatedCleanupKeepsOneTerminalEvent() = runBlocking {
        Fixture { call ->
            if (call == 12) ToolProtocolResult.failed("FIXTURE_FAILURE", false)
            else ToolProtocolResult.success("read-$call")
        }.use { fixture ->
            val value = fixture.run("for(var i=0;i<15;i++)android.call('shell',{command:'fixture',expect_screen_change:false});")
            assertEquals("device_stopped", value.getValue("status").jsonPrimitive.content)
            assertEquals(12, fixture.calls)
            val receipts = Json.parseToJsonElement(String(fixture.blobs.read(
                value.getValue("receipts_ref").jsonPrimitive.content, 1_000_000))).jsonArray
            assertEquals(12, receipts.size)
            assertEquals(8, value.getValue("recent_receipts").jsonArray.size)
            assertEquals("FAILED", value.getValue("failed_child").jsonObject.getValue("execution_status").jsonPrimitive.content)
            fixture.tool.release(fixture.owner)
            fixture.tool.release(fixture.owner)
            val polled = fixture.invoke(buildJsonObject { put("op", "poll"); put("handle", value.getValue("handle")) })
            assertEquals(value.getValue("terminal_event_ref"), polled.getValue("terminal_event_ref"))
            assertEquals(1, fixture.terminals().size)
            assertEquals(12, fixture.calls)
        }
    }

    @Test fun thrownDeviceCallRetainsUnknownChildAndStopsTheSuffix() = runBlocking {
        Fixture { call -> if (call == 2) error("connection lost") else ToolProtocolResult.success("first") }.use { fixture ->
            val value = fixture.run("for(var i=0;i<3;i++)android.call('shell',{command:'fixture',expect_screen_change:false});")
            assertEquals("device_stopped", value.getValue("status").jsonPrimitive.content)
            assertEquals(1, value.getValue("unknown_effects").jsonPrimitive.int)
            val unknown = value.getValue("failed_child").jsonObject
            assertEquals("unknown", unknown.getValue("effect_status").jsonPrimitive.content)
            assertNotNull(unknown["arguments_ref"])
            assertNotNull(unknown["event_ref"])
            assertEquals(2, fixture.calls)
            assertEquals(1, fixture.terminals().size)
        }
    }

    @Test fun failedChildReceiptWriteIsRetriedBeforeTheImmutableTerminalAndReceiptIndex() = runBlocking {
        Fixture(failFirstUnknownReceipt = true) { error("device connection lost") }.use { fixture ->
            val value = fixture.run("android.call('shell',{command:'fixture',expect_screen_change:false});")
            assertEquals("device_stopped", value.getValue("status").jsonPrimitive.content)
            assertEquals(1, fixture.calls)
            assertEquals(1, fixture.failedReceiptWrites)
            val history = fixture.events.eventsForTurn(fixture.owner.turnId)
            fun phase(event: AgentEventEnvelope) = if (event.eventKind == AgentEventKind.PROGRAM_EXECUTION)
                Json.parseToJsonElement(event.payloadJson).jsonObject["phase"]?.jsonPrimitive?.content else null
            val child = history.single { phase(it) == "device_unknown" }
            val terminal = history.single { phase(it) == "terminal" }
            assertTrue(child.sequence < terminal.sequence)
            val receipts = Json.parseToJsonElement(String(fixture.blobs.read(
                value.getValue("receipts_ref").jsonPrimitive.content, 1_000_000))).jsonArray
            assertEquals(child.eventId, receipts.single().jsonObject.getValue("event_ref").jsonPrimitive.content)
            assertEquals(1, value.getValue("unknown_effects").jsonPrimitive.int)
            fixture.tool.release(fixture.owner)
            assertEquals(1, fixture.terminals().size)
            assertEquals(1, fixture.calls)
        }
    }

    @Test fun canceledSkillKeepsCompletedAndUnknownItemsSeparateAfterEngineReopen() = runBlocking {
        val secondDispatched = CompletableDeferred<Unit>()
        Fixture { call ->
            if (call == 2) { secondDispatched.complete(Unit); awaitCancellation() }
            ToolProtocolResult.success("reported")
        }.use { fixture ->
            val code = """
                if(skill.checkpoint('a','begin').execute){
                    android.call('shell',{command:'effect-a',expect_screen_change:false});
                    skill.checkpoint('a','complete');
                }
                if(skill.checkpoint('b','begin').execute){
                    android.call('shell',{command:'effect-b',expect_screen_change:false});
                    skill.checkpoint('b','complete');
                }
            """.trimIndent()
            fixture.engine.saveDraft("---\nname: termination-test\ndescription: Test controlled effects\n---\nTest", mapOf("scripts/run.js" to code))
            val snapshot = requireNotNull(fixture.engine.findExecutionSnapshot("termination-test", true))
            val original = CancellationException("user stopped exact invocation")
            var received: CancellationException? = null
            val job = launch {
                try {
                    fixture.tool.executeSkill(buildJsonObject {
                        put("op", "run"); put("code", code); put("skill_entry", "scripts/run.js"); put("device_access", true)
                    }.toString(), snapshot, JsonObject(emptyMap()), "operation")
                } catch (error: CancellationException) { received = error; throw error }
            }
            withTimeout(10_000) { secondDispatched.await() }
            job.cancel(original)
            job.join()
            assertSame(original, received)
            fixture.tool.release(fixture.owner)
            assertEquals(1, fixture.terminals().size)
            val reopened = SkillEngine(fixture.files, RhinoSkillScriptValidator())
            val record = requireNotNull(reopened.executionRecord("termination-test", "operation"))
            assertEquals("canceled", record.getValue("state").jsonPrimitive.content)
            val items = record.getValue("items").jsonObject
            assertEquals("completed", items.getValue("a").jsonObject.getValue("state").jsonPrimitive.content)
            assertEquals("pending", items.getValue("b").jsonObject.getValue("state").jsonPrimitive.content)
            try {
                reopened.beginExecution(snapshot, JsonObject(emptyMap()), "operation", true, "scripts/run.js")
                fail("Unknown effect must block restarting the worker")
            } catch (error: IllegalStateException) { assertEquals("SKILL_CHECKPOINT_RECONCILIATION_REQUIRED", error.message) }
            assertEquals(2, fixture.calls)
        }
    }

    @Test fun waitingRunIsNotTerminalUntilResourcesAreReleased() = runBlocking {
        Fixture { error("No device request expected") }.use { fixture ->
            val waiting = fixture.run("print('prefix');readLine();print('suffix');", deviceAccess = false)
            assertEquals("waiting_for_input", waiting.getValue("status").jsonPrimitive.content)
            assertTrue(fixture.terminals().isEmpty())
            fixture.tool.release(fixture.owner)
            fixture.tool.release(fixture.owner)
            val terminal = fixture.terminals().single()
            assertEquals("interrupted", terminal.getValue("status").jsonPrimitive.content)
            assertEquals("prefix\n", terminal.getValue("stdout").jsonObject.getValue("text").jsonPrimitive.content)
        }
    }

    @Test fun pureComputationTimeoutIsRecordedAsTimeout() = runBlocking {
        Fixture(timeoutMs = 1000) { error("No device request expected") }.use { fixture ->
            val value = fixture.run("while(true){}", deviceAccess = false)
            assertEquals("timed_out", value.getValue("status").jsonPrimitive.content)
            fixture.tool.release(fixture.owner)
            assertEquals(1, fixture.terminals().size)
        }
    }

    private class Fixture(timeoutMs: Long = 12_000, failFirstUnknownReceipt: Boolean = false,
                          private val device: suspend (Int) -> ToolExecutionResult) : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = TurnSessionContext(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        private val storedEvents = InMemoryAgentEventStore()
        var failedReceiptWrites = 0
        val events: AgentEventStore = object : AgentEventStore by storedEvents {
            override suspend fun append(event: AgentEventDraft): AgentEventEnvelope {
                if (failFirstUnknownReceipt && failedReceiptWrites == 0 &&
                    event.eventKind == AgentEventKind.PROGRAM_EXECUTION &&
                    Json.parseToJsonElement(event.payloadJson).jsonObject["phase"]?.jsonPrimitive?.content == "device_unknown") {
                    failedReceiptWrites++
                    throw IOException("fixture rejected first child receipt")
                }
                return storedEvents.append(event)
            }
        }
        private val root = File(context.cacheDir, "program-termination-${UUID.randomUUID()}")
        val blobs = FileBlobStore(File(root, "blobs"))
        val files = MarkdownFileManager(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = File(root, "skills")
            override fun getExternalFilesDir(type: String?): File = File(root, "skills")
        })
        val engine = SkillEngine(files, RhinoSkillScriptValidator())
        var calls = 0
        val tool: ProgramTool
        init {
            val hash = "8".repeat(64)
            runBlocking {
                events.createThread(owner.threadId, "Program terminal behavior test")
                events.startTurn(TurnEventScope(owner.threadId, owner.turnId), ModelSessionIdentity(owner.threadId,
                    "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS, hash, hash, "fixture", hash), "fixture")
            }
            val workspace = TaskWorkspace(events, blobs)
            val workspaceRules = context.assets.open("rules/workspace.md").bufferedReader().use { it.readText() }
            val programRules = context.assets.open("rules/program.md").bufferedReader().use { it.readText() }
            fun section(text: String, name: String) = text.substringAfter("## $name\n").substringBefore("\n## ").trim()
            val workspacePolicy = Json.decodeFromString<WorkspacePolicy>(section(workspaceRules, "policy"))
            val reader = SessionReadService(events, blobs)
            val binding = ExecutionContext.fromStrategy("execution", "backend", DeviceExecutionStrategy(
                DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
                ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE)), 123)
            tool = ProgramTool(context, { name ->
                if (name == "policy") JsonObject(Json.parseToJsonElement(section(programRules, name)).jsonObject +
                    ("timeoutMs" to JsonPrimitive(timeoutMs))).toString() else section(programRules, name)
            }, workspace, { workspacePolicy }, { reader.read(owner.threadId, owner.turnId, it, false) },
                { owner }, { binding }, { null }, { null }, { _, _, _ -> device(++calls) }, events, blobs, engine)
        }
        suspend fun run(code: String, deviceAccess: Boolean = true) = invoke(buildJsonObject {
            put("op", "run"); put("code", code); put("device_access", deviceAccess)
        })
        suspend fun invoke(args: JsonObject): JsonObject = withContext(ToolInvocation(UUID.randomUUID().toString())) {
            requireNotNull(tool.execute(args.toString()).data).jsonObject
        }
        suspend fun terminals(): List<JsonObject> = events.eventsForTurn(owner.turnId).mapNotNull {
            if (it.eventKind != AgentEventKind.PROGRAM_EXECUTION) null else Json.parseToJsonElement(it.payloadJson).jsonObject
        }.filter { it["phase"]?.jsonPrimitive?.content == "terminal" }.map { it.getValue("data").jsonObject }
        override fun close() { owner.clear(); root.deleteRecursively() }
    }
}
