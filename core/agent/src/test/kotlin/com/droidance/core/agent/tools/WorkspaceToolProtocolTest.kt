// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.ToolArgumentSchemaFeedback
import com.droidance.core.agent.harness.ToolArgumentSchemaValidator
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol coverage from the provider-shaped schema through WorkspaceTool and its durable stores.
 *
 * These tests deliberately use the real in-memory EventStore and BlobStore implementations. They
 * do not replace the tool, the schema validator, or the workspace persistence boundary with a
 * model/business fake.
 */
class WorkspaceToolProtocolTest {
    @Test fun editSchemaRequiresInspectedVersionAndReplacementBeforeDispatch() = runBlocking {
        val schema = fixture().tool.definition.parameters
        ToolArgumentSchemaValidator.requireValidSchema(schema)
        assertEquals(listOf("field text is required", "field expected_ref is required"),
            ToolArgumentSchemaValidator.mismatchDetails(schema,
                """{"op":"edit","path":"work.md","old_text":"original"}"""))
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(schema,
            """{"op":"append","path":"work.md","text":"new finding"}"""))
    }

    @Test fun malformedEditReportsAllMissingFieldsWithoutChangingStoredContent() = runBlocking {
        val fixture = fixture()
        execute(fixture, """{"op":"write","path":"work.md","text":"retained","activate":true}""", "seed")
        val before = fixture.workspace.activeFile(currentScope)
        val result = execute(fixture, """{"op":"edit","path":"work.md","old_text":"retained","new_text":"addition"}""", "bad")
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        assertEquals(Json.parseToJsonElement("""["text","expected_ref"]"""), details["missing_parameters"])
        assertEquals(Json.parseToJsonElement("""["new_text"]"""), details["unexpected_parameters"])
        assertEquals(before, fixture.workspace.activeFile(currentScope))
        assertEquals("retained", fixture.workspace.read(currentScope, "work.md", policy))
    }

    private val policy = WorkspacePolicy(
        maxFiles = 8,
        maxFileChars = 1024,
        maxRetainedBytes = 4096,
        pageChars = 32
    )
    private val currentScope = TurnEventScope("workspace-thread", "current-turn")
    private val historyScope = TurnEventScope("workspace-thread", "history-turn")

    @Test fun readsReportNoStateChangeAndPreserveTheSelectedRecord() = runBlocking {
        val fixture = fixture()
        execute(fixture, """{"op":"write","path":"record.md","text":"retained","activate":true}""", "seed")
        val original = fixture.workspace.activeFile(currentScope)
        for (arguments in listOf("""{"op":"list"}""", """{"op":"read","path":"record.md"}""",
            """{"op":"journal"}""")) {
            val result = fixture.tool.execute(arguments)
            assertTrue(result.isVerifiedSuccess)
            assertEquals(false, result.stateChanged)
            assertEquals(original, fixture.workspace.activeFile(currentScope))
        }
    }

    @Test fun mutationReceiptsDistinguishContentAndSelectionChangesFromNoOps() = runBlocking {
        val fixture = fixture()
        suspend fun changed(arguments: String, callId: String, expected: Boolean) {
            val result = execute(fixture, arguments, callId)
            assertTrue(result.outputText, result.isVerifiedSuccess)
            assertEquals(callId, expected, result.stateChanged)
        }
        changed("""{"op":"write","path":"record.md","text":"A","activate":true}""", "seed", true)
        changed("""{"op":"write","path":"record.md","text":"A","activate":true}""", "same", false)
        changed("""{"op":"append","path":"record.md","text":""}""", "empty-append", false)
        changed("""{"op":"append","path":"record.md","text":"B"}""", "append", true)
        val ref = fixture.workspace.list(currentScope).single().contentRef
        changed("""{"op":"edit","path":"record.md","old_text":"AB","text":"AB","expected_ref":"$ref"}""", "same-edit", false)
        changed("""{"op":"edit","path":"record.md","old_text":"AB","text":"C","expected_ref":"$ref"}""", "edit", true)
        changed("""{"op":"write","path":"other.md","text":"other"}""", "other", true)
        changed("""{"op":"focus","path":"record.md"}""", "same-focus", false)
        changed("""{"op":"focus","path":"other.md"}""", "switch-focus", true)
        changed("""{"op":"write","path":"record.md","text":"C","activate":true}""", "activate-record", true)
        assertEquals("record.md", fixture.workspace.activeFile(currentScope)?.path)
        assertEquals("C", fixture.workspace.read(currentScope, "record.md", policy))
    }

    @Test fun replayedWriteAndFocusDoNotClaimNewEffectsAfterLaterChanges() = runBlocking {
        val fixture = fixture()
        val firstWrite = """{"op":"write","path":"record.md","text":"A","activate":true}"""
        execute(fixture, firstWrite, "first-write")
        execute(fixture, """{"op":"write","path":"other.md","text":"other"}""", "other")
        val firstFocus = """{"op":"focus","path":"record.md"}"""
        execute(fixture, firstFocus, "first-focus")
        execute(fixture, """{"op":"write","path":"record.md","text":"B"}""", "later-write")
        execute(fixture, """{"op":"focus","path":"other.md"}""", "later-focus")
        val before = fixture.events.eventsForTurn(currentScope.turnId)
        assertEquals(false, execute(fixture, firstWrite, "first-write").stateChanged)
        assertEquals(false, execute(fixture, firstFocus, "first-focus").stateChanged)
        assertEquals(before, fixture.events.eventsForTurn(currentScope.turnId))
        assertEquals("B", fixture.workspace.read(currentScope, "record.md", policy))
        assertEquals("other.md", fixture.workspace.activeFile(currentScope)?.path)
    }

    @Test fun modelCannotOverwriteRuntimeCompletionState() = runBlocking {
        val fixture = fixture()
        for (op in listOf("write", "append", "edit", "focus")) {
            val result = execute(fixture, """{"op":"$op","path":".completion/done.json","text":"{}","old_text":"previous","expected_ref":"sha256:${"0".repeat(64)}"}""", "reserved-$op")
            assertFalse(result.isVerifiedSuccess)
            assertTrue(result.outputText.contains("WORKSPACE_RESERVED_RUNTIME_PATH"))
        }
        assertTrue(fixture.workspace.list(currentScope).isEmpty())
    }

    @Test
    fun minimalActivatedWritePassesTheRealSchemaAndCommitsFileAndFocus() = runBlocking {
        val fixture = fixture()
        val arguments = """{"op":"write","path":"notes.md","text":"observed","activate":true}"""

        assertNull(ToolArgumentSchemaValidator.diagnosticFor(fixture.contract, arguments))
        assertTrue(fixture.contract.parameters.getValue("op").required)
        assertFalse(fixture.contract.parameters.getValue("path").required)
        assertFalse(fixture.contract.parameters.getValue("text").required)
        assertFalse(fixture.contract.parameters.getValue("activate").required)
        assertFalse(fixture.contract.parameters.getValue("expected_ref").required)
        assertFalse(fixture.contract.parameters.getValue("source_turn_id").required)

        val result = execute(fixture, arguments, "write-1")
        assertTrue(result.isVerifiedSuccess)

        val file = fixture.workspace.list(currentScope).single()
        assertEquals("notes.md", file.path)
        assertEquals("observed", fixture.workspace.read(currentScope, file.path, policy))
        assertEquals(
            AgentEventKind.WORKSPACE_FOCUS_CHANGED,
            fixture.events.eventsForTurn(currentScope.turnId).last().eventKind
        )
        val active = fixture.workspace.activeContext(
            fixture.events.eventsForTurn(currentScope.turnId), currentScope, 1024
        )
        assertEquals("notes.md", requireNotNull(active).path)
        assertEquals("observed", active.text)
        assertEquals(file.contentRef, active.contentRef)
    }

    @Test
    fun stringBooleanActivationPassesSchemaAndExecutesSuccessfully() = runBlocking {
        val fixture = fixture()
        val arguments = """{"op":"write","path":"notes.md","text":"observed","activate":"True"}"""

        assertNull(ToolArgumentSchemaValidator.diagnosticFor(fixture.contract, arguments))
        val result = execute(fixture, arguments, "write-1")
        assertTrue(result.isVerifiedSuccess)

        val file = fixture.workspace.list(currentScope).single()
        assertEquals("notes.md", file.path)
        assertEquals("observed", fixture.workspace.read(currentScope, file.path, policy))
        val active = fixture.workspace.activeContext(
            fixture.events.eventsForTurn(currentScope.turnId), currentScope, 1024
        )
        assertEquals("notes.md", requireNotNull(active).path)
        assertEquals("observed", active.text)
    }

    @Test
    fun emptyOptionalReferencesAndFalseActivationWorkForListReadAndFocus() = runBlocking {
        val fixture = fixture()
        val write = execute(
            fixture,
            """{"op":"write","path":"record.md","text":"A","expected_ref":"","source_turn_id":"","activate":false}""",
            "write-1"
        )
        assertTrue(write.isVerifiedSuccess)
        assertNull(
            fixture.workspace.activeContext(
                fixture.events.eventsForTurn(currentScope.turnId), currentScope, 1024
            )
        )

        val listArguments = """{"op":"list","activate":false,"expected_ref":"","source_turn_id":""}"""
        assertNull(ToolArgumentSchemaValidator.diagnosticFor(fixture.contract, listArguments))
        val listed = fixture.tool.execute(listArguments)
        assertTrue(listed.isVerifiedSuccess)
        assertTrue(listed.outputText.contains("record.md"))

        val readArguments =
            """{"op":"read","path":"record.md","activate":false,"expected_ref":"","source_turn_id":""}"""
        assertNull(ToolArgumentSchemaValidator.diagnosticFor(fixture.contract, readArguments))
        val read = fixture.tool.execute(readArguments)
        assertTrue(read.isVerifiedSuccess)
        assertEquals("A", Json.parseToJsonElement(read.outputText).jsonObject.getValue("text").jsonPrimitive.content)

        val focus = execute(
            fixture,
            """{"op":"focus","path":"record.md","activate":false,"expected_ref":""}""",
            "focus-1"
        )
        assertTrue(focus.isVerifiedSuccess)
        assertTrue(focus.outputText.contains("record.md"))

        val active = fixture.workspace.activeContext(
            fixture.events.eventsForTurn(currentScope.turnId), currentScope, 1024
        )
        assertEquals("record.md", requireNotNull(active).path)

        val invalidActivation = fixture.tool.execute("""{"op":"list","activate":true}""")
        assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.FAILED, invalidActivation.executionStatus)
        assertTrue(invalidActivation.outputText.contains("WORKSPACE_ACTIVATE_REQUIRES_WRITE"))
    }

    @Test
    fun updateUsesExpectedReferenceAndRejectsStaleReadAndAppend() = runBlocking {
        val fixture = fixture()
        val first = execute(
            fixture,
            """{"op":"write","path":"record.md","text":"A","activate":true}""",
            "write-1"
        )
        assertTrue(first.isVerifiedSuccess)
        val originalRef = fixture.workspace.list(currentScope).single().contentRef

        val appended = execute(
            fixture,
            """{"op":"append","path":"record.md","text":"B","expected_ref":"$originalRef","activate":false}""",
            "append-1"
        )
        assertTrue(appended.isVerifiedSuccess)
        val updatedRef = fixture.workspace.list(currentScope).single().contentRef
        assertTrue(updatedRef != originalRef)
        assertEquals("AB", fixture.workspace.read(currentScope, "record.md", policy))

        val staleAppend = execute(
            fixture,
            """{"op":"append","path":"record.md","text":"C","expected_ref":"$originalRef"}""",
            "append-stale"
        )
        assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.FAILED, staleAppend.executionStatus)
        assertTrue(staleAppend.outputText.contains("WORKSPACE_VERSION_CHANGED"))

        val staleRead = fixture.tool.execute(
            """{"op":"read","path":"record.md","expected_ref":"$originalRef"}"""
        )
        assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.FAILED, staleRead.executionStatus)
        assertTrue(staleRead.outputText.contains("WORKSPACE_VERSION_CHANGED"))
        assertEquals("AB", fixture.workspace.read(currentScope, "record.md", policy))
        assertEquals(1, fixture.workspace.list(currentScope).size)
        assertEquals(
            2,
            fixture.events.eventsForTurn(currentScope.turnId).count {
                it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN
            }
        )
    }

    @Test
    fun editReplacesOneExactSpanWithExpectedVersionAndRetriesIdempotently() = runBlocking {
        val fixture = fixture()
        val initialText = "Observed label: old value. Keep this surrounding evidence."
        val initial = execute(
            fixture,
            """{"op":"write","path":"record.md","text":"$initialText","activate":true}""",
            "write-record"
        )
        assertTrue(initial.isVerifiedSuccess)
        val original = fixture.workspace.list(currentScope).single()
        val editArguments = """{"op":"edit","path":"record.md","old_text":"old value","text":"new value","expected_ref":"${original.contentRef}","activate":true}"""

        assertNull(ToolArgumentSchemaValidator.diagnosticFor(fixture.contract, editArguments))
        assertFalse(fixture.contract.parameters.getValue("old_text").required)
        val edited = execute(fixture, editArguments, "edit-record")
        assertTrue(edited.isVerifiedSuccess)

        val updated = fixture.workspace.list(currentScope).single()
        assertNotEquals(original.contentRef, updated.contentRef)
        assertEquals("Observed label: new value. Keep this surrounding evidence.",
            fixture.workspace.read(currentScope, "record.md", policy))
        val active = fixture.workspace.activeContext(
            fixture.events.eventsForTurn(currentScope.turnId), currentScope, 1024
        )
        assertEquals(updated.contentRef, active?.contentRef)
        assertEquals("Observed label: new value. Keep this surrounding evidence.", active?.text)

        val retry = execute(fixture, editArguments, "edit-record")
        assertTrue(retry.isVerifiedSuccess)
        assertEquals(updated, fixture.workspace.list(currentScope).single())
        assertEquals(2, fixture.events.eventsForTurn(currentScope.turnId).count {
            it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN
        })
    }

    @Test
    fun editRejectsStaleMissingAndAmbiguousSpansWithoutWritingEvents() = runBlocking {
        val fixture = fixture()
        execute(fixture, """{"op":"write","path":"record.md","text":"A unique phrase remains."}""", "seed-record")
        val originalRef = fixture.workspace.list(currentScope).single().contentRef
        execute(fixture, """{"op":"append","path":"record.md","text":" Added." ,"expected_ref":"$originalRef"}""", "append-record")
        val currentRef = fixture.workspace.list(currentScope).single().contentRef
        execute(fixture, """{"op":"write","path":"ambiguous.md","text":"same phrase; same phrase"}""", "seed-ambiguous")
        val ambiguousRef = fixture.workspace.list(currentScope).single { it.path == "ambiguous.md" }.contentRef
        val eventCount = fixture.events.eventsForTurn(currentScope.turnId).count {
            it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN
        }

        val rejectedEdits = listOf(
            """{"op":"edit","path":"record.md","old_text":"A unique phrase","text":"changed","expected_ref":"$originalRef"}""" to "WORKSPACE_VERSION_CHANGED",
            """{"op":"edit","path":"record.md","old_text":"not present","text":"changed","expected_ref":"$currentRef"}""" to "WORKSPACE_EDIT_MATCH_NOT_UNIQUE",
            """{"op":"edit","path":"ambiguous.md","old_text":"same phrase","text":"changed","expected_ref":"$ambiguousRef"}""" to "WORKSPACE_EDIT_MATCH_NOT_UNIQUE",
            """{"op":"edit","path":"record.md","old_text":"","text":"changed","expected_ref":"$currentRef"}""" to "WORKSPACE_EDIT_REQUIRES_VERSION_AND_NONEMPTY_MATCH"
        )
        rejectedEdits.forEachIndexed { index, (arguments, reason) ->
            val result = execute(fixture, arguments, "invalid-edit-$index")
            assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.FAILED, result.executionStatus)
            assertTrue("Expected $reason, got ${result.outputText}", result.outputText.contains(reason))
        }

        assertEquals(eventCount, fixture.events.eventsForTurn(currentScope.turnId).count {
            it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN
        })
        assertEquals("A unique phrase remains. Added.", fixture.workspace.read(currentScope, "record.md", policy))
        assertEquals("same phrase; same phrase", fixture.workspace.read(currentScope, "ambiguous.md", policy))
    }

    @Test
    fun missingEditVersionReturnsRepairableFeedbackAndCanBeRepairedFromRead() = runBlocking {
        val fixture = fixture()
        execute(fixture,
            """{"op":"write","path":"record.md","text":"private original","activate":true}""",
            "seed-record")
        val original = fixture.workspace.list(currentScope).single()
        val beforeEvents = fixture.events.eventsForTurn(currentScope.turnId)
        val beforeFocus = fixture.workspace.activeContext(beforeEvents, currentScope, 1024)
        var recoveryRead: JsonObject? = null

        listOf("", ",\"expected_ref\":null", ",\"expected_ref\":\"\"", ",\"expected_ref\":\"  \"").forEachIndexed { index, reference ->
            val arguments = """{"op":"edit","path":"record.md","old_text":"private original","text":"private replacement","activate":true$reference}"""
            assertEquals(if (index < 2) "ARGUMENT_SCHEMA_MISMATCH" else null,
                ToolArgumentSchemaValidator.diagnosticFor(fixture.contract, arguments))
            val rejection = execute(fixture, arguments, "missing-edit-version-$index")
            val feedback = ToolArgumentSchemaFeedback.forModel(
                fixture.contract, arguments, fixture.tool.definition.parameters, rejection
            )
            assertEquals(rejection, feedback)
            assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED, feedback.executionStatus)
            assertEquals("ARGUMENT_SCHEMA_MISMATCH", feedback.diagnosticCode)
            val output = Json.parseToJsonElement(feedback.outputText).jsonObject
            assertEquals("true", output.getValue("retryable").jsonPrimitive.content)
            assertEquals("expected_ref", output.getValue("details").jsonObject.getValue("missing_parameter").jsonPrimitive.content)
            val recovery = output.getValue("details").jsonObject["recovery_tool_call"] as? JsonObject
            assertNotNull("Missing edit version must expose a read dependency, not only the field name", recovery)
            assertEquals("workspace", requireNotNull(recovery).getValue("name").jsonPrimitive.content)
            recoveryRead = recovery.getValue("arguments").jsonObject
            assertEquals("read", recoveryRead!!.getValue("op").jsonPrimitive.content)
            assertEquals("record.md", recoveryRead!!.getValue("path").jsonPrimitive.content)
            assertNull(ToolArgumentSchemaValidator.diagnosticFor(fixture.contract, recoveryRead.toString()))
            assertFalse(recoveryRead!!.containsKey("expected_ref"))
            assertFalse(feedback.outputText.contains("private original"))
            assertFalse(feedback.outputText.contains("private replacement"))
        }
        assertEquals(listOf(original), fixture.workspace.list(currentScope))
        assertEquals(beforeEvents, fixture.events.eventsForTurn(currentScope.turnId))
        assertEquals(beforeFocus, fixture.workspace.activeContext(beforeEvents, currentScope, 1024))

        val read = fixture.tool.execute(requireNotNull(recoveryRead).toString())
        assertTrue(read.isVerifiedSuccess)
        val reference = Json.parseToJsonElement(read.outputText).jsonObject.getValue("content_ref").jsonPrimitive.content
        val repaired = execute(fixture,
            """{"op":"edit","path":"record.md","old_text":"private original","text":"private replacement","expected_ref":"$reference","activate":true}""",
            "repaired-edit-version")
        assertTrue(repaired.isVerifiedSuccess)
        assertEquals("private replacement", fixture.workspace.read(currentScope, "record.md", policy))
        assertNotEquals(original.contentRef, fixture.workspace.list(currentScope).single().contentRef)
        assertEquals(2, fixture.events.eventsForTurn(currentScope.turnId).count {
            it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN
        })
    }

    @Test
    fun missingEditReplacementReturnsRepairableFeedbackWithoutChangingFileOrFocus() = runBlocking {
        val fixture = fixture()
        execute(fixture, """{"op":"write","path":"record.md","text":"private original","activate":true}""", "seed-record")
        val original = fixture.workspace.list(currentScope).single()
        val beforeEvents = fixture.events.eventsForTurn(currentScope.turnId)
        val beforeFocus = fixture.workspace.activeContext(beforeEvents, currentScope, 1024)
        val invalid = """{"op":"edit","path":"record.md","old_text":"private original","new_text":"private replacement","expected_ref":"${original.contentRef}","activate":true}"""
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", ToolArgumentSchemaValidator.diagnosticFor(fixture.contract, invalid))

        repeat(4) { attempt ->
            val rejection = execute(fixture, invalid, "invalid-edit-$attempt")
            val feedback = ToolArgumentSchemaFeedback.forModel(
                fixture.contract, invalid, fixture.tool.definition.parameters, rejection
            )
            assertEquals(rejection, feedback)
            assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED, feedback.executionStatus)
            assertEquals("ARGUMENT_SCHEMA_MISMATCH", feedback.diagnosticCode)
            val output = Json.parseToJsonElement(feedback.outputText).jsonObject
            assertEquals("true", output.getValue("retryable").jsonPrimitive.content)
            assertEquals("text", output.getValue("details").jsonObject.getValue("missing_parameter").jsonPrimitive.content)
            assertFalse(feedback.outputText.contains("private original"))
            assertFalse(feedback.outputText.contains("private replacement"))
        }
        assertEquals(listOf(original), fixture.workspace.list(currentScope))
        assertEquals(beforeEvents, fixture.events.eventsForTurn(currentScope.turnId))
        assertEquals(beforeFocus, fixture.workspace.activeContext(beforeEvents, currentScope, 1024))

        val repaired = execute(fixture,
            """{"op":"edit","path":"record.md","old_text":"private original","text":"private replacement","expected_ref":"${original.contentRef}","activate":true}""",
            "repaired-edit")
        assertTrue(repaired.isVerifiedSuccess)
        assertEquals("private replacement", fixture.workspace.read(currentScope, "record.md", policy))
        val updated = fixture.workspace.list(currentScope).single()
        assertNotEquals(original.contentRef, updated.contentRef)
        val active = fixture.workspace.activeContext(fixture.events.eventsForTurn(currentScope.turnId), currentScope, 1024)
        assertEquals(updated.contentRef, active?.contentRef)
        assertEquals("private replacement", active?.text)
    }

    @Test
    fun emptyCanonicalEditReplacementStillDeletesOnlyTheMatchedSpan() = runBlocking {
        val fixture = fixture()
        execute(fixture, """{"op":"write","path":"record.md","text":"before remove after"}""", "seed-record")
        val original = fixture.workspace.list(currentScope).single()
        val deleted = execute(fixture,
            """{"op":"edit","path":"record.md","old_text":"remove ","text":"","expected_ref":"${original.contentRef}"}""",
            "delete-span")
        assertTrue(deleted.isVerifiedSuccess)
        assertEquals("before after", fixture.workspace.read(currentScope, "record.md", policy))
    }

    @Test
    fun historicalReadIsAllowedButHistoricalWriteIsRejected() = runBlocking {
        val fixture = fixture(withHistory = true)

        val historyList = fixture.tool.execute(
            """{"op":"list","source_turn_id":"${historyScope.turnId}","activate":false}"""
        )
        assertTrue(historyList.isVerifiedSuccess)
        assertTrue(historyList.outputText.contains("previous.md"))

        val historyRead = fixture.tool.execute(
            """{"op":"read","path":"previous.md","source_turn_id":"${historyScope.turnId}","expected_ref":""}"""
        )
        assertTrue(historyRead.isVerifiedSuccess)
        assertEquals(
            "historical",
            Json.parseToJsonElement(historyRead.outputText).jsonObject.getValue("text").jsonPrimitive.content
        )

        val historyWrite = execute(
            fixture,
            """{"op":"write","path":"current.md","text":"must not write","source_turn_id":"${historyScope.turnId}"}""",
            "history-write-attempt"
        )
        assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.FAILED, historyWrite.executionStatus)
        assertTrue(historyWrite.outputText.contains("WORKSPACE_HISTORY_READ_ONLY"))
        assertTrue(fixture.workspace.list(currentScope).isEmpty())
    }

    private suspend fun execute(fixture: Fixture, arguments: String, callId: String): com.droidance.core.agent.event.ToolExecutionResult =
        withContext(ToolInvocation(callId)) { fixture.tool.execute(arguments) }

    private suspend fun fixture(withHistory: Boolean = false): Fixture {
        val events = InMemoryAgentEventStore()
        val blobs = InMemoryBlobStore()
        val workspace = TaskWorkspace(events, blobs)
        events.createThread(currentScope.threadId, "Workspace protocol")
        if (withHistory) {
            events.startTurn(historyScope, identity(historyScope.threadId), "history-snapshot")
            workspace.write(historyScope, "previous.md", "historical", false, null, "history-seed", policy)
        }
        events.startTurn(currentScope, identity(currentScope.threadId), "current-snapshot")
        val tool = WorkspaceTool(
            workspace = workspace,
            scope = { currentScope },
            rules = { section -> if (section == "policy") Json.encodeToString(policy) else "Workspace protocol" },
            readArtifact = { error("artifact is outside this protocol fixture") }
        )
        val contract = RegisteredToolContract(
            name = tool.name,
            description = tool.definition.description,
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.STATE_CHANGING,
            parameters = tool.definition.parameters
        )
        return Fixture(events, workspace, tool, contract)
    }

    private fun identity(threadId: String): ModelSessionIdentity {
        val hash = "1".repeat(64)
        return ModelSessionIdentity(
            threadId = threadId,
            providerId = "fixture",
            transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
            transportConfigHash = hash,
            authScopeHash = hash,
            modelId = "fixture",
            stablePrefixHash = hash
        )
    }

    private data class Fixture(
        val events: InMemoryAgentEventStore,
        val workspace: TaskWorkspace,
        val tool: WorkspaceTool,
        val contract: RegisteredToolContract
    )
}
