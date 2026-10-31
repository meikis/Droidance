// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.*
import com.droidance.core.agent.workspace.*
import com.droidance.core.model.llm.models.*
import com.droidance.core.agent.llm.session.*
import com.droidance.core.model.llm.session.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRecordsIntegrationTest {
    @Test fun recordsAreDurableActivatedVersionedAndAtomicOnInvalidSource() = runBlocking {
        val events = InMemoryAgentEventStore()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        val scope = TurnEventScope("records-thread", "records-turn")
        val hash = "1".repeat(64)
        events.createThread(scope.threadId, "Records test")
        events.startTurn(scope, ModelSessionIdentity(scope.threadId, "fixture",
            ModelTransport.GENERIC_CHAT_COMPLETIONS, hash, hash, "fixture", hash), "fixture")
        val policy = WorkspacePolicy(8, 4096, 16384, 4096)
        val full = ToolDefinition("example", "Full exact documentation", mapOf("op" to ToolParameter("string", "Operation")))
        val tool = WorkspaceTool(workspace, { scope }, { if (it == "policy") Json.encodeToString(policy) else "Workspace" },
            { error("No artifact read expected") }, { full.takeIf { name -> name.name == it } }, { it == "original" })
        com.droidance.core.agent.harness.ToolArgumentSchemaValidator.requireValidSchema(tool.definition.parameters)
        suspend fun call(id: String, args: String) = withContext(ToolInvocation(id)) { tool.execute(args) }
        fun input(id: String, source: String = "original", expected: String? = null) = buildJsonObject {
            put("op", "records"); put("path", "objects.json"); put("activate", true)
            expected?.let { put("expected_ref", it) }
            put("records", Json.parseToJsonElement("""[{"record_id":"$id","fields":{"title":{"value":"Observed value","source_ref":"$source","region":"top card"}},"uncertainties":["metric missing"]}]"""))
        }.toString()
        assertTrue(call("first", input("a")).isVerifiedSuccess)
        val first = workspace.activeFile(scope)!!
        assertEquals("objects.json", first.path)
        assertTrue(call("second", input("b", expected = first.contentRef)).isVerifiedSuccess)
        val beforeSupplement = workspace.activeFile(scope)!!
        val supplemental = buildJsonObject {
            put("op", "records"); put("path", "objects.json"); put("activate", true)
            put("expected_ref", beforeSupplement.contentRef)
            put("records", Json.parseToJsonElement("""[{"record_id":"a","fields":{"metric":{"value":"42 units","source_ref":"original"}},"uncertainties":[]}]"""))
        }.toString()
        assertTrue(call("supplement", supplemental).isVerifiedSuccess)
        val second = workspace.activeFile(scope)!!
        val supplemented = Json.parseToJsonElement(workspace.readVersion(second)).jsonObject.getValue("records").jsonArray.first().jsonObject
        assertEquals("Observed value", supplemented.getValue("fields").jsonObject.getValue("title").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("42 units", supplemented.getValue("fields").jsonObject.getValue("metric").jsonObject.getValue("value").jsonPrimitive.content)
        assertTrue(supplemented.getValue("uncertainties").jsonArray.isEmpty())
        assertFalse(workspace.readVersion(beforeSupplement).contains("42 units"))
        assertEquals(2, Json.parseToJsonElement(workspace.readVersion(second)).jsonObject.getValue("records").jsonArray.size)
        assertEquals(1, Json.parseToJsonElement(workspace.readVersion(first)).jsonObject.getValue("records").jsonArray.size)
        assertFalse(call("bad-source", input("c", "invented", second.contentRef)).isVerifiedSuccess)
        assertFalse(call("conflict", input("c", expected = first.contentRef)).isVerifiedSuccess)
        val conflict = call("missing-version", input("c"))
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, conflict.executionStatus)
        assertEquals("WORKSPACE_VERSION_CHANGED", conflict.diagnosticCode)
        assertTrue(conflict.outputText.contains("recovery_tool_call"))
        assertTrue(conflict.outputText.contains(second.contentRef))
        assertEquals(second, workspace.activeFile(scope))
        assertTrue(call("text-file", """{"op":"write","path":"work.md","text":""}""").isVerifiedSuccess)
        val textFile = workspace.list(scope).single { it.path == "work.md" }
        val incompatible = call("wrong-format", input("c").let {
            val args = Json.parseToJsonElement(it).jsonObject
            JsonObject(args + mapOf("path" to JsonPrimitive("work.md"),
                "expected_ref" to JsonPrimitive(textFile.contentRef))).toString()
        })
        assertEquals("RECORD_FILE_SHAPE_INVALID", incompatible.diagnosticCode)
        assertEquals(textFile, workspace.list(scope).single { it.path == "work.md" })
        assertEquals("", workspace.readVersion(textFile))
        val help = call("help", """{"op":"help","ref":"example"}""")
        assertTrue(help.isVerifiedSuccess)
        assertEquals(false, help.stateChanged)
        assertEquals(full, Json.decodeFromString<ToolDefinition>(help.outputText))
        assertEquals(second, workspace.activeFile(scope))
    }
}
