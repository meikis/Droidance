// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.*
import com.droidance.core.agent.workspace.*
import com.droidance.core.model.llm.models.*
import com.droidance.core.agent.llm.session.*
import com.droidance.core.model.llm.session.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompletionSubmissionProtocolTest {
    private val scope = TurnEventScope("thread", "turn")
    private val policy = WorkspacePolicy(8, 4096, 32768, 128)
    private val content = mapOf("done" to mapOf(
        "status" to ToolParameter("string", ""),
        "results" to ToolParameter("array", "", items = com.droidance.core.agent.harness.PublicResultEntry.schema("", "")),
        "unresolved_requirements" to ToolParameter("array", "", items = ToolParameter("string", ""))
    ))
    private fun body(text: String = "Observed title", files: JsonArray? = null) = buildJsonObject {
        put("status", "done")
        put("results", buildJsonArray { addJsonObject { put("text", text) } })
        put("unresolved_requirements", JsonArray(emptyList()))
        files?.let { put("deliverables", it) }
    }
    private fun file(path: String = "report.md", index: Int = 0) = buildJsonArray {
        addJsonObject { put("path", path); put("result_index", index) }
    }
    private suspend fun workspace(turn: TurnEventScope = scope): TaskWorkspace {
        val events = InMemoryAgentEventStore()
        events.createThread(turn.threadId, "completion")
        events.startTurn(turn, ModelSessionIdentity(threadId = turn.threadId, providerId = "fixture", transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
            modelId = "fixture", transportConfigHash = "a".repeat(64), authScopeHash = "b".repeat(64), stablePrefixHash = "c".repeat(64)), "snapshot")
        return TaskWorkspace(events, InMemoryBlobStore())
    }
    private suspend fun resolve(w: TaskWorkspace, args: JsonObject, id: String = "call", turn: TurnEventScope = scope,
        canOpen: suspend (String) -> Boolean = { false }) = CompletionSubmissionProtocol.resolve(
        listOf(ToolCall(id, "done", args.toString())), w, turn, policy, content, canOpen)

    @Test fun sameContentKeepsExactStoredVersionWithoutIdentifierEcho() = runBlocking {
        val w = workspace(); val first = resolve(w, body())
        assertTrue(first.errors.toString(), first.errors.isEmpty())
        val version = w.list(scope).single()
        val second = resolve(w, body(), "second")
        assertEquals(version, w.list(scope).single())
        assertEquals(first.calls.single().arguments, second.calls.single().arguments)
        assertEquals(setOf("second"), second.reviews.keys)
        assertEquals(setOf("candidate_id"), second.reviews.getValue("second").keys)
    }
    @Test fun changedContentCreatesNewCandidateRatherThanConfirmingPreviousOne() = runBlocking {
        val w = workspace(); resolve(w, body()); val first = w.list(scope).single()
        val changed = resolve(w, body("Corrected title"), "changed")
        assertTrue(changed.errors.isEmpty()); assertTrue(changed.reviews.isEmpty())
        assertNotEquals(first.contentRef, w.list(scope).single().contentRef)
        val facts = CompletionSubmissionProtocol.currentCandidateFacts(w, scope, "done")!!
        assertEquals(body("Corrected title"), facts.getValue("submitted_content"))
    }
    @Test fun invalidCorrectionDoesNotSaveOrConfirmIt() = runBlocking {
        val w = workspace(); resolve(w, body()); val previous = w.list(scope).single()
        val result = resolve(w, JsonObject(body() - "results"), "invalid")
        assertTrue(result.errors.isNotEmpty()); assertTrue(result.reviews.isEmpty())
        assertEquals(previous, w.list(scope).single())
        assertEquals(body(), result.errorDetails.getValue("invalid").getValue("submitted_content"))
    }
    @Test fun oldConfirmationAndReplacementShapesAreRejectedWithoutAliases() = runBlocking {
        val w = workspace(); resolve(w, body())
        for (field in listOf("candidate_id", "assessment", "output_review", "replacement", "requirement_ids")) {
            assertTrue(resolve(w, JsonObject(body() + (field to JsonPrimitive("old"))), field).errors.isNotEmpty())
        }
    }
    @Test fun schemaHasOneRequiredContentShapeAndNoReviewEnvelope() {
        val fields = CompletionSubmissionProtocol.parameters(content.getValue("done"))
        assertEquals(setOf("status", "results", "unresolved_requirements"), fields.filterValues { it.required }.keys)
        assertFalse(fields.containsKey("replacement")); assertFalse(fields.containsKey("candidate_id"))
    }
    @Test fun anotherTurnCannotConfirmPreviousCandidate() = runBlocking {
        val next = TurnEventScope("thread", "next"); val w = workspace(next)
        assertNull(CompletionSubmissionProtocol.currentCandidateFacts(w, next, "done"))
        assertTrue(resolve(w, body(), turn = next).reviews.isEmpty())
    }
    @Test fun filesAreVersionBoundAndRuntimeLinksDoNotChangeNativeSubmission() = runBlocking {
        val w = workspace(); w.write(scope, "report.md", "original", false, null, "report", policy)
        val native = body(files = file())
        val first = resolve(w, native); assertTrue(first.errors.isEmpty())
        val reference = Json.parseToJsonElement(first.references).jsonObject.getValue("call").jsonObject
        assertEquals(native, reference.getValue("native_submission"))
        assertTrue(first.calls.single().arguments.contains("droidance://deliverable"))
        assertTrue(resolve(w, native, "same").reviews.isNotEmpty())
        w.write(scope, "report.md", "changed", false, null, "edit", policy)
        val revised = resolve(w, native, "changed-file")
        assertTrue(revised.errors.isEmpty()); assertTrue(revised.reviews.isEmpty())
        assertNotEquals(first.calls.single().arguments, revised.calls.single().arguments)
    }
    @Test fun missingFileAndInvalidMappingNeverStoreCandidate() = runBlocking {
        val w = workspace(); assertTrue(resolve(w, body(files = file())).errors.isNotEmpty())
        w.write(scope, "report.md", "plain [label]", false, null, "file", policy)
        val result = resolve(w, body(files = file(index = 1)))
        assertEquals("deliverable_result_index_out_of_range", result.errors["call"])
        assertEquals("deliverables[0].result_index", result.errorDetails.getValue("call").getValue("field").jsonPrimitive.content)
        assertTrue(w.list(scope).none { it.path.startsWith(".completion/") })
    }
    @Test fun fileLinkAddedOnceAndPlainBracketsDoNotNeedResolver() = runBlocking {
        val w = workspace(); val v = w.write(scope, "report.md", "[plain label]", false, null, "file", policy)
        val result = resolve(w, body(files = JsonArray(file() + file())))
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        val text = Json.parseToJsonElement(result.calls.single().arguments).jsonObject.getValue("results").jsonArray[0]
            .jsonObject.getValue("text").jsonPrimitive.content
        val link = WorkspaceDeliverable.link(scope.turnId, "report.md", v.contentRef)
        assertEquals(1, text.split(link).size - 1)
    }
    @Test fun invalidLinkRetainsLatestProposalButDoesNotPublishOrEchoItsDestination() = runBlocking {
        val w = workspace(); resolve(w, body())
        val invalid = body("[source](unresolved://private)")
        val result = resolve(w, invalid, "invalid-link")
        assertEquals("completion_link_unresolvable", result.errors["invalid-link"])
        assertEquals("results[0].text", result.errorDetails.getValue("invalid-link").getValue("field").jsonPrimitive.content)
        assertTrue(result.reviews.isEmpty())
        assertEquals(invalid, CompletionSubmissionProtocol.currentCandidateFacts(w, scope, "done")!!.getValue("submitted_content"))
    }
    @Test fun staleOrFabricatedDeliverableLinksCannotBePublished() = runBlocking {
        val w = workspace()
        assertEquals("completion_link_unresolvable", resolve(w, body("[file](droidance://deliverable?turn=other&path=x&ref=fake)")).errors["call"])
    }
    @Test fun fileContentsAreAlsoValidatedWithoutExposingPrivateLink() = runBlocking {
        val w = workspace(); w.write(scope, "report.md", "[x](unresolved://private)", false, null, "file", policy)
        val result = resolve(w, body(files = file()))
        assertEquals("deliverables[0].content", result.errorDetails.getValue("call").getValue("field").jsonPrimitive.content)
        assertTrue(result.reviews.isEmpty())
    }
    @Test fun externalLinksUseResolverRatherThanSchemeWhitelist() = runBlocking {
        val w = workspace()
        assertTrue(resolve(w, body("[source](sampleapp://observed/item)"), canOpen = { it == "sampleapp://observed/item" }).errors.isEmpty())
    }
    @Test fun optionalReviewUsesSameContentButStillRequiresSourceDelivery() = runBlocking {
        val w = workspace(); val first = resolve(w, body())
        val candidate = CompletionReviewCandidate.from("done", first.calls.single().arguments)
        val gate = CompletionReviewGate(true)
        assertNotNull(gate.request("goal", "review", setOf("source"), candidate))
        val same = resolve(w, body(), "again")
        assertNotNull(gate.request("goal", "review", setOf("source"), candidate, confirmation = same.reviews["again"]))
        gate.recordDeliveredSources(mapOf("source" to "retained"))
        assertNull(gate.request("goal", "review", setOf("source"), candidate, confirmation = same.reviews["again"]))
    }
}
