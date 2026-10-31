// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.program

import com.droidance.core.device.program.*

import com.droidance.program.*

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.agent.TurnSessionContext
import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*
import com.droidance.agent.device.*
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
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
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProgramToolInstrumentedTest {
    @Test fun visualResumeRequiresTheExactBoundaryAndFreshBoundScreenshotAndPreservesPrefix() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = TurnSessionContext("program-resume-turn", "program-resume-thread")
        val events = InMemoryAgentEventStore()
        val hash = "7".repeat(64)
        events.createThread(owner.threadId, "Program visual resume test")
        events.startTurn(TurnEventScope(owner.threadId, owner.turnId), ModelSessionIdentity(owner.threadId,
            "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS, hash, hash, "fixture", hash), "fixture")
        val root = File(context.cacheDir, "program-resume-blobs-" + UUID.randomUUID())
        val blobs = FileBlobStore(root)
        val workspace = TaskWorkspace(events, blobs)
        val workspaceMarkdown = context.assets.open("rules/workspace.md").bufferedReader().use { it.readText() }
        val workspacePolicy = Json.decodeFromString<WorkspacePolicy>(
            workspaceMarkdown.substringAfter("## policy\n").substringBefore("\n## ").trim())
        val programMarkdown = context.assets.open("rules/program.md").bufferedReader().use { it.readText() }
        val reader = SessionReadService(events, blobs)
        val strategy = DeviceExecutionStrategy(DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE))
        val binding = ExecutionContext.fromStrategy("resume-execution", "resume-backend", strategy, 123)
        var inspectedSequence = 1L
        var captureSequence = 1L
        var currentFrame: ObservationEnvelope? = null
        val deviceObservations = mutableListOf<String>()
        val tool = ProgramTool(context,
            { section -> programMarkdown.substringAfter("## $section\n").substringBefore("\n## ").trim() },
            workspace, { workspacePolicy }, { reader.read(owner.threadId, owner.turnId, it, false) },
            { owner }, { binding }, { currentFrame }, { inspectedSequence },
            { name, args, child ->
                assertEquals("android_read", name)
                assertEquals("screen", args["op"]?.jsonPrimitive?.content)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val frameSize = bitmap.width to bitmap.height
                val bytes = ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
                bitmap.recycle()
                val observationId = "real-screen-${deviceObservations.size + 1}"
                val frameSequence = ++captureSequence
                currentFrame = ObservationEnvelope(observationId, binding.executionId, binding.backendSessionId,
                    binding.displayId, 0, child, frameSequence, SystemClock.elapsedRealtime(),
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), "AIR", 1f,
                    frameSize, frameSize, 0 to 0)
                deviceObservations += blobs.put(bytes).contentRef
                ToolExecutionResult("Captured current device screen (${bytes.size} bytes)",
                    ToolExecutionStatus.SUCCEEDED, ToolEvidenceStatus.CAPTURED, observationId = observationId,
                    dataRef = deviceObservations.last(), preview = "Current device screen captured")
            }, events, blobs)

        suspend fun invoke(arguments: JsonObject) = withContext(ToolInvocation(UUID.randomUUID().toString())) {
            tool.execute(arguments.toString())
        }
        fun ack(boundary: JsonObject, decision: String = "continue", observationRef: String? =
            boundary["observation_ref"]?.jsonPrimitive?.contentOrNull, boundaryId: String? =
            boundary["boundary_id"]?.jsonPrimitive?.contentOrNull, actionHash: String? =
            boundary["pending_action_hash"]?.jsonPrimitive?.contentOrNull) = buildJsonObject {
            put("boundary_id", boundaryId); put("pending_action_hash", actionHash)
            put("observation_ref", observationRef); put("decision", decision)
        }
        fun poll(handle: String, resume: JsonObject? = null, stdin: String? = null) = buildJsonObject {
            put("op", "poll"); put("handle", handle)
            resume?.let { put("resume", it) }; stdin?.let { put("stdin", it) }
        }
        try {
            val first = invoke(buildJsonObject {
                put("op", "run"); put("device_access", true)
                put("code", "var first=android.call('android_read',{op:'screen'});var target=readLine();workspace.write('resumed-input.txt',target);var second=android.call('android_read',{op:'screen'});var third=android.call('android_read',{op:'screen'});print('unreachable after replan');")
                put("stdin", "stale-prequeued-value")
            })
            assertTrue(first.isVerifiedSuccess)
            val firstBody = Json.parseToJsonElement(first.outputText).jsonObject
            assertEquals("waiting_for_observation", firstBody["status"]?.jsonPrimitive?.content)
            val firstBoundary = firstBody["boundary"]!!.jsonObject
            assertEquals("stdin", firstBoundary["pending_operation"]?.jsonPrimitive?.content)
            assertEquals(1, deviceObservations.size)

            val handle = firstBody["handle"]!!.jsonPrimitive.content
            assertEquals("PROGRAM_RESUME_DECISION_REQUIRED", invoke(poll(handle)).diagnosticCode)
            assertEquals("PROGRAM_RESUME_BOUNDARY_MISMATCH", invoke(poll(handle,
                ack(firstBoundary, boundaryId = "stale-boundary"))).diagnosticCode)
            assertEquals("NEW_MODEL_OBSERVATION_REQUIRED", invoke(poll(handle, ack(firstBoundary))).diagnosticCode)
            assertEquals(1, deviceObservations.size)

            inspectedSequence = requireNotNull(currentFrame).sequence
            val frame = requireNotNull(currentFrame)
            currentFrame = frame.copy(backendSessionId = "wrong-backend")
            assertEquals("NEW_MODEL_OBSERVATION_REQUIRED", invoke(poll(handle, ack(firstBoundary))).diagnosticCode)
            currentFrame = frame
            assertEquals("NEW_MODEL_OBSERVATION_REQUIRED", invoke(poll(handle,
                ack(firstBoundary, observationRef = "wrong-observation"))).diagnosticCode)
            assertEquals(1, deviceObservations.size)

            val continued = invoke(poll(handle, ack(firstBoundary), stdin = "fresh-observed-input"))
            assertTrue(continued.isVerifiedSuccess)
            val secondBody = Json.parseToJsonElement(continued.outputText).jsonObject
            assertEquals("waiting_for_observation", secondBody["status"]?.jsonPrimitive?.content)
            val secondBoundary = secondBody["boundary"]!!.jsonObject
            assertEquals("android", secondBoundary["pending_operation"]?.jsonPrimitive?.content)
            assertEquals(2, deviceObservations.size)
            assertEquals("fresh-observed-input", workspace.read(TurnEventScope(owner.threadId, owner.turnId),
                "resumed-input.txt", workspacePolicy))

            inspectedSequence = requireNotNull(currentFrame).sequence
            val replanned = invoke(poll(handle, ack(secondBoundary, decision = "replan")))
            val finalBody = Json.parseToJsonElement(replanned.outputText).jsonObject
            assertEquals("replanned", finalBody["status"]?.jsonPrimitive?.content)
            assertEquals(1, finalBody["replanned_boundaries"]?.jsonPrimitive?.int)
            assertEquals(2, finalBody["completed_child_calls"]?.jsonPrimitive?.int)
            assertEquals(2, deviceObservations.size)
            assertEquals(0, finalBody["unknown_effects"]?.jsonPrimitive?.int)
        } finally {
            owner.clear()
            root.deleteRecursively()
        }
    }

    @Test fun pureJavascriptRunsWithoutDeviceBinding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = TurnSessionContext("pure-program-turn", "pure-program-thread")
        val events = InMemoryAgentEventStore()
        val hash = "2".repeat(64)
        events.createThread(owner.threadId, "Pure program test")
        events.startTurn(
            TurnEventScope(owner.threadId, owner.turnId),
            ModelSessionIdentity(
                owner.threadId,
                "fixture",
                ModelTransport.GENERIC_CHAT_COMPLETIONS,
                hash,
                hash,
                "fixture",
                hash
            ),
            "fixture"
        )
        val root = File(context.cacheDir, "pure-program-blobs-" + UUID.randomUUID())
        val blobs = FileBlobStore(root)
        val workspace = TaskWorkspace(events, blobs)
        val workspaceMarkdown = context.assets.open("rules/workspace.md").bufferedReader().use { it.readText() }
        val workspacePolicy = Json.decodeFromString<WorkspacePolicy>(
            workspaceMarkdown.substringAfter("## policy\n").substringBefore("\n## ").trim()
        )
        val reader = SessionReadService(events, blobs)
        val markdown = context.assets.open("rules/program.md").bufferedReader().use { it.readText() }
        val tool = ProgramTool(
            context,
            { section -> markdown.substringAfter("## $section\n").substringBefore("\n## ").trim() },
            workspace,
            { workspacePolicy },
            { reader.read(owner.threadId, owner.turnId, it, false) },
            { owner },
            { null },
            { null },
            { null },
            { _, _, _ -> error("Pure JavaScript must not request a device") },
            events,
            blobs
        )
        try {
            val environment = withContext(ToolInvocation(UUID.randomUUID().toString())) {
                tool.execute("{\"op\":\"environment\"}")
            }
            val environmentBody = Json.parseToJsonElement(environment.outputText).jsonObject
            assertEquals(ToolExecutionStatus.SUCCEEDED, environment.executionStatus)
            assertFalse(environmentBody["device_bound"]!!.jsonPrimitive.boolean)

            val result = withContext(ToolInvocation(UUID.randomUUID().toString())) {
                tool.execute(
                    """{"op":"run","code":"workspace.write('pure.txt','ready');print(workspace.read('pure.txt'));"}"""
                )
            }
            val body = Json.parseToJsonElement(result.outputText).jsonObject
            assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
            assertTrue(result.isVerifiedSuccess)
            assertEquals("exited", body["status"]!!.jsonPrimitive.content)
            assertEquals("ready\n", body["stdout"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(
                "ready",
                workspace.read(TurnEventScope(owner.threadId, owner.turnId), "pure.txt", workspacePolicy)
            )
        } finally {
            owner.clear()
            root.deleteRecursively()
        }
    }

    @Test fun workspaceCompositionReceiptsAndArtifactPagingSurviveWorkerReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = TurnSessionContext("program-turn", "program-thread")
        val events = InMemoryAgentEventStore()
        val hash = "1".repeat(64)
        events.createThread(owner.threadId, "Program test")
        events.startTurn(TurnEventScope(owner.threadId, owner.turnId), ModelSessionIdentity(owner.threadId,
            "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS, hash, hash, "fixture", hash), "fixture")
        val root = File(context.cacheDir, "program-blobs-" + UUID.randomUUID())
        val blobs = FileBlobStore(root)
        val strategy = DeviceExecutionStrategy(DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE))
        var binding = ExecutionContext.fromStrategy("execution", "backend", strategy, 123)
        val markdown = context.assets.open("rules/program.md").bufferedReader().use { it.readText() }
        val calls = mutableListOf<String>()
        val workspace = TaskWorkspace(events, blobs)
        val workspaceMarkdown = context.assets.open("rules/workspace.md").bufferedReader().use { it.readText() }
        val workspacePolicy = Json.decodeFromString<WorkspacePolicy>(workspaceMarkdown.substringAfter("## policy\n").substringBefore("\n## ").trim())
        val reader = SessionReadService(events, blobs)
        val files = WorkspaceTool(workspace, { TurnEventScope(owner.threadId, owner.turnId) },
            { section -> workspaceMarkdown.substringAfter("## $section\n").substringBefore("\n## ").trim() },
            { reader.read(owner.threadId, owner.turnId, it, false) })
        val tool = ProgramTool(context, { section -> markdown.substringAfter("## $section\n").substringBefore("\n## ").trim() },
            workspace, { workspacePolicy }, { reader.read(owner.threadId, owner.turnId, it, false) },
            { owner }, { binding }, { null }, { null }, { name, args, child ->
                assertEquals("android_read", name)
                assertEquals("device", args["op"]!!.jsonPrimitive.content)
                calls.add(child)
                ToolProtocolResult.success("{\"value\":21}")
            }, events, blobs)
        suspend fun invoke(vararg values: Pair<String, String>, deviceAccess: Boolean? = null): JsonObject {
            val target = if (values.any { it.first == "op" && it.second in setOf("write", "read") }) files else tool
            val result = withContext(ToolInvocation(UUID.randomUUID().toString())) {
                target.execute(buildJsonObject {
                    values.forEach { put(it.first, it.second) }
                    deviceAccess?.let { put("device_access", it) }
                }.toString())
            }
            val body = Json.parseToJsonElement(result.outputText).jsonObject
            if (body["status"]?.jsonPrimitive?.content == "failed") {
                assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
                assertEquals("PROGRAM_RUN_FAILED", result.diagnosticCode)
            } else {
                assertEquals(result.outputText, ToolExecutionStatus.SUCCEEDED, result.executionStatus)
                assertTrue(result.isVerifiedSuccess)
            }
            return body
        }
        suspend fun finish(first: JsonObject, expected: String = "exited"): JsonObject {
            var value = first
            withTimeout(10000) {
                while (value["status"]!!.jsonPrimitive.content == "running") {
                    delay(20); value = invoke("op" to "poll", "handle" to value["handle"]!!.jsonPrimitive.content)
                }
            }
            assertEquals(expected, value["status"]!!.jsonPrimitive.content)
            return value
        }
        try {
            // Actual isolated-worker work lasting longer than the removed polling slice.
            // No device callback or model request services these host operations.
            val uninterrupted = invoke("op" to "run", "code" to
                "var end=Date.now()+1700;while(Date.now()<end){};workspace.write('continued.txt','ready');print(workspace.read('continued.txt'));")
            assertEquals("exited", uninterrupted["status"]!!.jsonPrimitive.content)
            assertEquals("ready\n", uninterrupted["stdout"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(3L, uninterrupted["host_requests"]!!.jsonPrimitive.long)
            assertTrue(calls.isEmpty())
            val suspended = invoke("op" to "run", "code" to "readLine();print('must not run');")
            assertEquals("waiting_for_input", suspended["status"]!!.jsonPrimitive.content)
            val canceled = invoke("op" to "cancel", "handle" to suspended["handle"]!!.jsonPrimitive.content)
            assertEquals("canceled", canceled["status"]!!.jsonPrimitive.content)
            assertEquals("", canceled["stdout"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            invoke("op" to "write", "path" to "main.js", "text" to "throw Error('fixture bug');")
            val failed = finish(invoke("op" to "run", "path" to "main.js"), "failed")
            assertTrue(failed["error"]!!.jsonPrimitive.content.contains("fixture bug"))
            invoke("op" to "write", "path" to "main.js", "text" to "var r=android.call('android_read',{op:'device'});workspace.write('result.txt',String(JSON.parse(r.outputText).value*2));print(new Array(15002).join('x'));")
            val finished = finish(invoke("op" to "run", "path" to "main.js"))
            assertEquals(1, calls.size)
            assertEquals("42", invoke("op" to "read", "path" to "result.txt")["text"]!!.jsonPrimitive.content)
            val ref = finished["stdout_ref"]!!.jsonPrimitive.content
            val first = reader.read(owner.threadId, owner.turnId, "{\"mode\":\"artifact\",\"ref\":\"$ref\"}", false)
            val page = Json.parseToJsonElement(first.outputText).jsonObject
            assertEquals(12000, page["text"]!!.jsonPrimitive.content.length)
            assertTrue(page["has_more"]!!.jsonPrimitive.boolean)
            val tail = reader.read(owner.threadId, owner.turnId, "{\"mode\":\"artifact\",\"ref\":\"$ref\",\"offset\":12000}", false)
            assertEquals(3002, Json.parseToJsonElement(tail.outputText).jsonObject["text"]!!.jsonPrimitive.content.length)
            assertEquals("HISTORY_REFERENCE_NOT_FOUND", reader.read("other-thread", owner.turnId,
                "{\"mode\":\"artifact\",\"ref\":\"$ref\"}", false).diagnosticCode)
            val history = events.eventsForTurn(owner.turnId)
            assertTrue(history.any { it.eventKind == AgentEventKind.PROGRAM_EXECUTION && it.payloadJson.contains("device_finished") })
            finish(invoke("op" to "run", "code" to "if(workspace.read('result.txt')!=='42')throw Error('lost');"))
            val waiting = invoke("op" to "run", "code" to "readLine();android.call('android_read',{op:'device'});", deviceAccess = true)
            assertEquals("waiting_for_input", waiting["status"]!!.jsonPrimitive.content)
            binding = binding.copy(backendSessionId = "replaced")
            val rejected = tool.execute(buildJsonObject { put("op", "poll");put("handle", waiting["handle"]!!);put("stdin", "continue") }.toString())
            assertFalse(rejected.isVerifiedSuccess)
            assertEquals(1, calls.size)
            owner.clear()
            assertEquals("42", TaskWorkspace(events, blobs).read(TurnEventScope(owner.threadId, owner.turnId), "result.txt", workspacePolicy))
        } finally { owner.clear(); root.deleteRecursively() }
    }
}
