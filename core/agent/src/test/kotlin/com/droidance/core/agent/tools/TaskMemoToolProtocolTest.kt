// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*
import kotlinx.serialization.json.JsonPrimitive

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.harness.ToolArgumentSchemaValidator
import com.droidance.core.agent.workspace.TaskExecutionPlan
import com.droidance.core.agent.workspace.TaskStep
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.model.llm.models.toJsonSchema
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol coverage for task_memo through the real EventStore, BlobStore and TaskWorkspace.
 *
 * The tool is intentionally exercised with a ToolInvocation context because the invocation id is
 * the idempotency boundary for its atomic workspace bootstrap/focus operation.
 */
class TaskMemoToolProtocolTest {
    private val policy = WorkspacePolicy(
        maxFiles = 8,
        maxFileChars = 2048,
        maxRetainedBytes = 8192,
        pageChars = 64
    )
    private val scope = TurnEventScope("memo-thread", "memo-turn")

    @Test fun observedValueCanBindToAnIndependentOutputConstraint() = runBlocking {
        val fixture = fixture()
        val declared = plan().copy(output_constraints = listOf("omit the observed identifier"),
            protected_values = listOf(com.droidance.core.agent.ProtectedTaskValue("c1", "original-source", "internal-value", "omit", 0, 0)))
        val result = execute(fixture.tool, Json { encodeDefaults = true }.encodeToString(declared), "constraint-memo")
        assertTrue(result.isVerifiedSuccess)
        assertEquals(declared.record_path, fixture.workspace.activeContext(
            fixture.events.eventsForTurn(scope.turnId), scope, 4096)!!.path)
        val invalid = declared.copy(protected_values = listOf(declared.protected_values.single().copy(requirement_id = "c2")))
        assertFalse(execute(fixture.tool, Json { encodeDefaults = true }.encodeToString(invalid), "unlisted-constraint").isVerifiedSuccess)
    }

    @Test
    fun validMemoAtomicallyCreatesAndActivatesTheTaskRecord() = runBlocking {
        val fixture = fixture()
        val arguments = Json { encodeDefaults = true }.encodeToString(plan())

        val result = execute(fixture.tool, arguments, "memo-1")

        assertTrue(result.isVerifiedSuccess)
        assertEquals("working", result.outputText)
        val file = fixture.workspace.list(scope).single()
        assertEquals("execution/record.md", file.path)
        val content = fixture.workspace.read(scope, file.path, policy)
        assertTrue(content.contains("inspect the requested state"))
        assertTrue(content.contains("- inspect: Inspect the requested state"))
        assertEquals(
            listOf(AgentEventKind.WORKSPACE_FILE_WRITTEN, AgentEventKind.WORKSPACE_FOCUS_CHANGED),
            fixture.events.eventsForTurn(scope.turnId).map { it.eventKind }
                .filter { it in setOf(AgentEventKind.WORKSPACE_FILE_WRITTEN, AgentEventKind.WORKSPACE_FOCUS_CHANGED) }
        )
        assertEquals("execution/record.md", fixture.workspace.activeContext(
            fixture.events.eventsForTurn(scope.turnId), scope, 4096
        )!!.path)
    }

    @Test
    fun resubmittingAPlanFocusesTheExistingRecordWithoutErasingFindings() = runBlocking {
        val fixture = fixture()
        val first = execute(fixture.tool, Json { encodeDefaults = true }.encodeToString(plan()), "memo-1")
        assertTrue(first.isVerifiedSuccess)
        val original = fixture.workspace.list(scope).single()
        fixture.workspace.write(
            scope,
            original.path,
            "\nObserved source ref=source-a; unresolved detail remains.\n",
            append = true,
            expectedRef = original.contentRef,
            writeId = "finding-1",
            policy = policy
        )
        val updated = plan().copy(state = "retained finding")

        val result = execute(fixture.tool, Json { encodeDefaults = true }.encodeToString(updated), "memo-2")

        assertTrue(result.isVerifiedSuccess)
        val content = fixture.workspace.read(scope, original.path, policy)
        assertTrue(content.contains("Observed source ref=source-a"))
        assertEquals(
            2,
            fixture.events.eventsForTurn(scope.turnId).count {
                it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN
            }
        )
        assertEquals(
            2,
            fixture.events.eventsForTurn(scope.turnId).count {
                it.eventKind == AgentEventKind.WORKSPACE_FOCUS_CHANGED
            }
        )
    }

    @Test
    fun malformedOrUnsafePlansCannotCreateWorkspaceFacts() = runBlocking {
        val fixture = fixture()
        val invalidArguments = listOf(
            "{}",
            Json { encodeDefaults = true }.encodeToString(plan().copy(record_path = "../record.md")),
            Json { encodeDefaults = true }.encodeToString(plan().copy(requirements = emptyList()))
        )

        invalidArguments.forEachIndexed { index, arguments ->
            val result = execute(fixture.tool, arguments, "invalid-$index")
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
            assertFalse(result.isVerifiedSuccess)
        }
        assertTrue(fixture.workspace.list(scope).isEmpty())
        assertTrue(fixture.events.eventsForTurn(scope.turnId).none {
            it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN ||
                it.eventKind == AgentEventKind.WORKSPACE_FOCUS_CHANGED
        })
    }
    @Test fun scopeOnlyMemoCommitsWithoutInventedCompletedStepEvidence() = runBlocking {
        val fixture = fixture()
        val result = execute(fixture.tool, Json { encodeDefaults = true }.encodeToString(plan().copy(steps = emptyList())), "scope-only")
        assertTrue(result.outputText, result.isVerifiedSuccess)
        assertTrue(fixture.workspace.list(scope).isNotEmpty())
    }

    @Test
    fun schemaExposesTheFullPlanAndOptionalReviewContract() {
        val definition = TaskMemoTool.definition("execution plan")

        assertEquals(
            setOf("record_path", "state", "requirements", "steps", "review", "output_constraints", "requested_deliverables", "protected_values"),
            definition.parameters.keys
        )
        assertTrue(definition.parameters.getValue("steps").items!!.properties!!.containsKey("acceptance"))
        assertTrue(definition.parameters.getValue("steps").items!!.properties!!.containsKey("evidence_refs"))
        assertFalse(definition.parameters.getValue("review").required)
        assertTrue(definition.parameters.getValue("review").properties!!.containsKey("outcome"))
        assertTrue(definition.parameters.getValue("review").properties!!.getValue("outcome").required)
        assertTrue(definition.parameters.getValue("review").properties!!.containsKey("next_method"))
    }

    @Test fun schemaDeclaresNonemptyReviewFieldsWithoutRequiringReview() {
        val parameters = TaskMemoTool.definition("execution plan").parameters
        val wire = ToolParameter("object", "", properties = parameters).toJsonSchema()
        assertFalse(wire.getValue("required").jsonArray.any { it.jsonPrimitive.content == "review" })
        val review = wire.getValue("properties").jsonObject.getValue("review").jsonObject
            .getValue("properties").jsonObject
        assertEquals("1", review.getValue("finding").jsonObject["minLength"]?.jsonPrimitive?.content)
        assertEquals("1", review.getValue("next_method").jsonObject["minLength"]?.jsonPrimitive?.content)
        val evidence = review.getValue("evidence_refs").jsonObject
        assertEquals("1", evidence["minItems"]?.jsonPrimitive?.content)
        assertEquals("1", evidence.getValue("items").jsonObject["minLength"]?.jsonPrimitive?.content)
    }

    @Test fun sharedSchemaRejectsEmptyReviewFieldsButAllowsOmittingReview() {
        val parameters = TaskMemoTool.definition("execution plan").parameters
        val initial = JsonObject(Json.parseToJsonElement(Json { encodeDefaults = true }.encodeToString(plan())).jsonObject - "review")
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(parameters, initial.toString()))
        val review = Json.parseToJsonElement("""{"outcome":"progress","finding":"","next_method":"","evidence_refs":[]}""")
        val issues = ToolArgumentSchemaValidator.mismatchDetails(parameters, JsonObject(initial + ("review" to review)).toString())
        assertEquals(setOf("review.finding", "review.next_method", "review.evidence_refs"),
            issues.map { it.removePrefix("field ").substringBefore(' ') }.toSet())
    }

    @Test fun lengthConstraintsDoNotReplaceNonblankReviewSemantics() {
        val parameters = TaskMemoTool.definition("execution plan").parameters
        val initial = Json.parseToJsonElement(Json { encodeDefaults = true }.encodeToString(plan())).jsonObject
        val review = Json.parseToJsonElement("""{"outcome":"progress","finding":" ","next_method":" ","evidence_refs":[" "]}""")
        val arguments = JsonObject(initial + ("review" to review)).toString()
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(parameters, arguments))
        assertEquals(setOf("review.finding", "review.next_method", "review.evidence_refs"),
            TaskExecutionPlan.parse(arguments).issues.map { it.field }.toSet())
    }

    @Test fun anUnknownFieldIsNamedWithTheAllowedFieldsInsteadOfDecoderAdvice() {
        val initial = Json.parseToJsonElement(Json { encodeDefaults = true }.encodeToString(plan())).jsonObject
        val arguments = JsonObject(initial + ("action_title_suffix" to JsonPrimitive("x"))).toString()
        val issue = TaskExecutionPlan.parse(arguments).issues.single()
        assertEquals("action_title_suffix", issue.field)
        assertEquals("unknown_field", issue.reason)
        assertTrue(issue.recovery.contains("requirements"))
        assertFalse(issue.recovery.contains("ignoreUnknownKeys") || issue.reason.contains("ignoreUnknownKeys"))
    }

    private suspend fun fixture(): Fixture {
        val events = InMemoryAgentEventStore()
        val blobs = InMemoryBlobStore()
        val workspace = TaskWorkspace(events, blobs)
        events.createThread(scope.threadId, "Task memo protocol")
        events.startTurn(scope, identity(scope.threadId), "memo-protocol")
        val tool = TaskMemoTool(
            workspace = workspace,
            scope = { scope },
            rules = { section -> if (section == "policy") Json { encodeDefaults = true }.encodeToString(policy) else "execution plan" }
        )
        return Fixture(events, workspace, tool)
    }

    private suspend fun execute(tool: TaskMemoTool, arguments: String, callId: String) =
        withContext(ToolInvocation(callId)) { tool.execute(arguments) }

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

    private fun plan() = TaskExecutionPlan(
        record_path = "execution/record.md",
        state = "working",
        requirements = listOf("inspect the requested state"),
        steps = listOf(
            TaskStep(
                id = "inspect",
                objective = "Inspect the requested state",
                acceptance = "The current source contains the requested state.",
                status = "active",
                evidence_refs = emptyList()
            )
        )
    )

    private data class Fixture(
        val events: InMemoryAgentEventStore,
        val workspace: TaskWorkspace,
        val tool: TaskMemoTool
    )
}
