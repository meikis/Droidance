// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.tools.CompletionState
import com.droidance.tools.CompletionToolRegistrar
import com.droidance.core.agent.tools.Tool
import com.droidance.core.agent.harness.ResultCardToolArguments
import com.droidance.core.agent.harness.renderResultCardMarkdown
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActionControlProtocolTest {
    private fun completionParameters() = mapOf(
        "status" to ToolParameter("string", "", required = true),
        "results" to ToolParameter("array", "", required = true),
        "unresolved_requirements" to ToolParameter("array", "", required = false),
        "requirement_checks" to ToolParameter("array", "", required = false)
    )

    @Test fun completionReferencesHaveOneArrayShapeAndCitationsStayOptional() {
        val params = com.droidance.core.agent.harness.PublicResultEntry.schema("Text", "References").properties!!
        assertEquals("array", params.getValue("evidence_refs").type)
        assertFalse(params.getValue("evidence_refs").required)
        val supported = extract("""{"status":"done","results":[{"text":"Value","evidence_refs":["obs_1","obs_2"]}],"unresolved_requirements":[]}""")
        assertEquals(listOf("obs_1", "obs_2"), supported.controls.getValue("c").claims!!.single().evidenceRefs)
        val oldString = extract("""{"status":"done","results":[{"text":"Value","evidence_refs":"obs_1,obs_2"}],"unresolved_requirements":[]}""")
        assertNotNull(oldString.controls.getValue("c").invalidArgument)
        val oldEntry = extract("""{"status":"done","results":["Value"],"unresolved_requirements":[]}""")
        assertNotNull(oldEntry.controls.getValue("c").invalidArgument)
        val omitted = extract("""{"status":"done","results":[{"text":"Value"}],"unresolved_requirements":[]}""")
        assertTrue(omitted.controls.getValue("c").claims!!.single().evidenceRefs.isEmpty())
    }

    @Test fun fileBodyRepairReportsAllMissingFieldsWithoutFillingThem() {
        val raw = "{}"
        val control = ActionControlProtocol.extract(listOf(ToolCall("c", "done", raw))).controls.getValue("c")
        val feedback = Json.parseToJsonElement(ActionControlProtocol.argumentFailure(control, completionParameters()).outputText)
            .jsonObject.getValue("details").jsonObject
        assertEquals(setOf("status", "results"),
            feedback.getValue("missing_parameters").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("draft", feedback.getValue("repair_target").jsonPrimitive.content)
        assertEquals(raw, control.originalArguments)
    }

    @Test fun repairUsesTheSameRequiredContentSchema() {
        val control = ActionControlProtocol.Control("c", null,
            invalidArgument = CompletionSubmissionProtocol.ARGUMENT, invalidReason = "submit_exactly_one_draft_or_review",
            originalArguments = "{}")
        val wire = CompletionSubmissionProtocol.parameters(completionParameters())
        assertTrue(wire.getValue("results").required)
        val parameters = CompletionSubmissionProtocol.parameters(completionParameters())
        val feedback = Json.parseToJsonElement(ActionControlProtocol.argumentFailure(control, parameters).outputText)
            .jsonObject.getValue("details").jsonObject
        assertTrue(feedback.getValue("required_parameters").jsonArray.contains(JsonPrimitive("results")))
        assertEquals("tool_arguments", feedback.getValue("repair_target").jsonPrimitive.content)
    }

    @Test fun fileRepairNamesMalformedResultSourcesAndTheExactVersionToEdit() {
        val descriptions = ActionControlProtocol.Descriptions("refs", "terminal", "entries", "text", "unresolved", "checks")
        val params = ActionControlProtocol.addControlParameters(
            listOf(ToolDefinition("done", "done", completionParameters())), descriptions, setOf("done"))
            .single().parameters
        val raw = """{"status":"done","results":[{"text":"Saved","evidence_refs":3}],"unresolved_requirements":[],"requirement_checks":[{"requirement_id":"r1","status":"supported","result_indices":[0],"unresolved_index":-1}]}"""
        val ref = buildJsonObject { put("path", "result.json"); put("content_ref", "sha256:original") }
        val control = ActionControlProtocol.extract(listOf(ToolCall("c", "done", raw))).controls.getValue("c")
        val details = Json.parseToJsonElement(ActionControlProtocol.argumentFailure(control, params, ref).outputText)
            .jsonObject.getValue("details").jsonObject
        assertEquals("draft", details.getValue("repair_target").jsonPrimitive.content)
        assertEquals(ref, details.getValue("file_reference"))
        val issues = details.getValue("schema_issues").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(issues.any { it.contains("results[0].evidence_refs") })
        assertFalse(issues.any { it.endsWith("evidence_refs is required") })
        assertFalse(issues.contains("field requirement_checks[0].evidence_refs is required"))
        val schema = details.getValue("expected_schema").jsonObject
        assertFalse(schema.getValue("required").jsonArray.contains(JsonPrimitive("requirement_checks")))
        val check = schema.getValue("properties").jsonObject.getValue("requirement_checks").jsonObject.getValue("items").jsonObject
        assertFalse(check.getValue("required").jsonArray.contains(JsonPrimitive("evidence_refs")))
    }

    private fun extract(body: String, tool: String = "done"): ActionControlProtocol.Result {
        val args = Json.parseToJsonElement(body).jsonObject.toMutableMap()
        val remaining = args.getOrPut("unresolved_requirements") { JsonArray(emptyList()) } as JsonArray
        val entries = (args["results"] ?: args["items"]) as? JsonArray
        args.putIfAbsent("requirement_checks", buildJsonArray {
            if (entries?.isNotEmpty() == true) addJsonObject {
                put("result_indices", JsonArray(listOf(JsonPrimitive(0))))
                put("requirement_id", "Observed object value"); put("status", "supported")
                put("unresolved_index", -1)
            }
            remaining.forEachIndexed { index, value -> addJsonObject {
                put("result_indices", JsonArray(emptyList()))
                put("requirement_id", value); put("status", "unverified")
                put("unresolved_index", index)
            } }
        })
        return ActionControlProtocol.extract(listOf(ToolCall("c", tool, JsonObject(args).toString())))
    }

    private fun done(text: String, refs: String = "obs_1", status: String = "done") = buildJsonObject {
        put("status", status)
        put("unresolved_requirements", JsonArray(emptyList()))
        putJsonArray("requirement_checks") { addJsonObject {
            put("result_indices", JsonArray(listOf(JsonPrimitive(0))))
            put("requirement_id", "Observed object value"); put("status", "supported")
            put("unresolved_index", -1)
        } }
        putJsonArray("results") { addJsonObject { put("text", text); put("evidence_refs", JsonArray(refs.split(',').map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive))) } }
    }.toString()

    private fun renderExecuted(arguments: String): String = runBlocking {
        val state = CompletionState(); val tools = mutableListOf<Tool>()
        CompletionToolRegistrar({ tools.add(it) }, { state }, "body", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) com.droidance.core.agent.tools.ToolProtocolResult.success("recorded")
            else com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        tools.single().execute(arguments)
        requireNotNull(state.result).summary
    }

    @Test fun displayedBodyAndEvidenceAreAuthoredOnceWithoutNormalizingMeaning() = runBlocking {
        val body = "## Findings\n\n- **A**: -1.25 kg; not verified elsewhere.\n- B: 1–2 kg.\n\nRecommendation: choose A only if the condition holds."
        val parsed = extract(done(body))
        val control = parsed.controls.getValue("c")
        assertNull(control.invalidArgument)
        assertEquals(body, control.claims!!.single().result)
        assertTrue(ActionControlProtocol.completionReferencesValid(control) { it == "obs_1" })
        val tools = mutableListOf<Tool>()
        val state = CompletionState()
        CompletionToolRegistrar({ tools.add(it) }, { state }, "body", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) com.droidance.core.agent.tools.ToolProtocolResult.success("recorded")
            else com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        tools.single().execute(parsed.toolCalls.single().arguments)
        assertEquals(body, state.result!!.summary)
        assertEquals("done", state.result!!.status)
        val originalArguments = Json.parseToJsonElement(parsed.finishMetadata.getValue(
            ActionControlProtocol.CONTENT_ARGUMENTS_METADATA)).jsonObject
        assertEquals(done(body), originalArguments.getValue("0").jsonPrimitive.content)
        val archive = Json.parseToJsonElement(parsed.finishMetadata.getValue(ActionControlProtocol.CLAIMS_METADATA))
            .jsonArray.single().jsonObject["control"]!!.jsonObject
        assertEquals(body, archive["results"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun internalReferencesStayArchivedWithoutBeingAppendedToPublicResults() {
        val refs = listOf(
            "a43f8e35-006a-487f-9d57-153a811bfa5f",
            "df917f00-1a2e-4b7e-af7e-4cf12cbbbdd3:call:0:capture:2/4/6",
            "call_1c83da03-c4af-41d3-82d9-ce862db3cb69"
        )
        val body = "Source: the app's filtered results page. Values reflect the captured list; details remain unchecked."
        val completed = extract(done(body, refs.joinToString(",")))
        val control = completed.controls.getValue("c")
        assertEquals(refs, control.claims!!.single().evidenceRefs)
        assertTrue(ActionControlProtocol.completionReferencesValid(control) { selected -> selected.split(',').all { it in refs } })
        assertEquals(body, renderExecuted(completed.toolCalls.single().arguments))
        val archived = Json.parseToJsonElement(completed.finishMetadata.getValue(ActionControlProtocol.CLAIMS_METADATA))
            .jsonArray.single().jsonObject.getValue("control").jsonObject
        assertEquals(refs, archived.getValue("results").jsonArray.single().jsonObject.getValue("evidence_refs")
            .jsonArray.map { it.jsonPrimitive.content })

        val cardBody = buildJsonObject {
            put("title", "Findings")
            putJsonArray("items") { addJsonObject {
                put("text", body)
                put("evidence_refs", JsonArray(refs.map(::JsonPrimitive)))
            } }
        }.toString()
        val completedCard = extract(cardBody, "present_result_card")
        assertEquals(refs, completedCard.controls.getValue("c").claims!!.single().evidenceRefs)
        val card = ResultCardToolArguments.parseCompletion(completedCard.toolCalls.single().arguments)
        assertEquals("**Findings**\n\n$body", renderResultCardMarkdown(card.title, card.items.map { it.text to it.deeplink }))
    }

    @Test fun cardRecommendationsCannotBeHiddenOnlyInMetadata() {
        val parsed = extract("""{"title":"Comparison","items":[
          {"text":"A: 12; B: 15","deeplink":"","evidence_refs":["obs_a", "obs_b"]},
          {"text":"Inference: choose A for lower cost.","deeplink":"","evidence_refs":["obs_a", "obs_b"]}]}""", "present_result_card")
        val card = ResultCardToolArguments.parse(parsed.toolCalls.single().arguments)
        val rendered = renderResultCardMarkdown(card.title, card.items.map { it.text to it.deeplink })
        assertTrue(rendered.contains("Inference: choose A for lower cost."))
        assertEquals(2, parsed.controls.getValue("c").claims!!.size)
        assertTrue(parsed.toolCalls.single().arguments.contains("evidence_refs"))
    }

    @Test fun separateSummaryOrClaimsAreRejectedRatherThanMergedOrSilentlyPublished() {
        for (extra in listOf("\"summary\":\"Wrong object\"", "\"claims\":[]")) {
            val parsed = extract(done("Source A: 12").dropLast(1) + ",$extra}")
            assertNotNull(parsed.controls.getValue("c").invalidArgument)
            assertFalse(ActionControlProtocol.completionReferencesValid(parsed.controls["c"]) { true })
        }
        assertNotNull(extract("""{"status":"done","summary":"old body","claims":[]}""")
            .controls.getValue("c").invalidArgument)
    }

    @Test fun uncitedEntriesAreAcceptedAndSuppliedSourcesAreValidated() {
        val ledger = TaskEvidenceLedger()
        ledger.recordObservation(observationId = "obs_1", toolName = "read", rawText = "Object A: 12",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE)
        val good = extract(done("Object A: 12")).controls.getValue("c")
        assertTrue(ActionControlProtocol.completionReferencesValid(good, ledger::hasCompletionEvidence))
        assertTrue(ActionControlProtocol.completionReferencesValid(extract(done("Value", "")).controls["c"], ledger::hasCompletionEvidence))
        for (refs in listOf("unknown", "obs_1,unknown")) {
            assertFalse(ActionControlProtocol.completionReferencesValid(extract(done("Value", refs)).controls["c"],
                ledger::hasCompletionEvidence))
        }
        assertFalse(ActionControlProtocol.completionReferencesValid(extract(
            """{"status":"done","results":[{"text":"Value","evidence_refs":["obs_1",""]}]}""")
            .controls["c"], ledger::hasCompletionEvidence))
        // Existence checks intentionally do not pretend to perform a visual semantic judgment.
        val wrongSubject = extract(done("Object B: 12")).controls.getValue("c")
        assertTrue(ActionControlProtocol.completionReferencesValid(wrongSubject, ledger::hasCompletionEvidence))
    }

    @Test fun unsuccessfulResultPreservesPartialFindingsAndAnExplicitUnverifiedRequirement() = runBlocking {
        val parsed = extract("""{"status":"need_more_info","results":[
          {"text":"Object A: 12","evidence_refs":["obs_1"]},
          {"text":"The requested order and second object remain unverified.","evidence_refs":[]}]}""")
        assertNull(parsed.controls.getValue("c").invalidArgument)
        assertTrue(ActionControlProtocol.completionReferencesValid(parsed.controls["c"]) { true })
        val tools = mutableListOf<Tool>(); val state = CompletionState()
        CompletionToolRegistrar({ tools.add(it) }, { state }, "body", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) com.droidance.core.agent.tools.ToolProtocolResult.success("recorded")
            else com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        tools.single().execute(parsed.toolCalls.single().arguments)
        assertEquals("need_more_info", state.result!!.status)
        assertEquals("Object A: 12\n\nThe requested order and second object remain unverified.", state.result!!.summary)
    }

    @Test fun malformedEntriesAndControlsCannotDisappearDuringExtraction() {
        for (body in listOf("[]", "{}", "null", "[{}]",
            """[{"text":false,"evidence_refs":["obs_1"]}]""",
            """[{"text":"","evidence_refs":["obs_1"]}]""",
            """[{"text":"A","evidence_refs":3}]""",
            """[{"text":"A","evidence_refs":["obs_1"],"extra":true}]""")) {
            val result = extract("""{"status":"done","results":$body}""")
            assertNotNull(body, result.controls.getValue("c").invalidArgument)
            assertTrue(result.finishMetadata.isNotEmpty())
        }
        for (field in listOf("\"evidence_refs\":7", "\"terminal_observation_ref\":null")) {
            assertNotNull(extract(done("A").dropLast(1) + ",$field}").controls.getValue("c").invalidArgument)
        }
    }

    @Test fun removedTopLevelAggregateReferencesAreRejectedForBothCompletionTools() {
        for (tool in listOf("done", "present_result_card")) {
            val base = if (tool == "done") done("Observed result") else
                """{"title":"Observed result","items":[{"text":"Observed result","evidence_refs":["obs_1"]}]}"""
            for (refs in listOf("obs_1", "")) {
                val result = extract(base.dropLast(1) + ",\"evidence_refs\":[\"$refs\"]}", tool)
                val control = result.controls.getValue("c")
                assertEquals("evidence_refs", control.invalidArgument)
                assertFalse(ActionControlProtocol.completionReferencesValid(control) { true })
            }
        }
    }

    @Test fun optionalTerminalObservationReferenceIsRetainedWithoutDuplicatingResultSources() {
        val result = extract(done("Observed result").dropLast(1) + ",\"terminal_observation_ref\":\"obs_terminal\"}")
        val control = result.controls.getValue("c")
        assertNull(control.invalidArgument)
        assertEquals("obs_terminal", control.terminalObservationRef)
        assertEquals("obs_1", ActionControlProtocol.completionEvidenceReferences(control))
        assertFalse(result.toolCalls.single().arguments.contains("terminal_observation_ref"))
    }

    @Test fun boundChecksAggregateMultipleResultsWhileProcessChecksKeepTheirOwnSource() {
        val parsed = extract("""{"status":"done","results":[
          {"text":"A","evidence_refs":["obs_a"]},{"text":"B","evidence_refs":["obs_b"]}],
          "requirement_checks":[
          {"requirement_id":"Compare A and B","status":"supported","unresolved_index":-1,"result_indices":[0,1]},
          {"requirement_id":"Preserve page order","status":"supported","evidence_refs":["obs_list"],"unresolved_index":-1,"result_indices":[]}]}""")
        val control = parsed.controls.getValue("c")
        assertNull(control.invalidArgument)
        assertEquals(listOf(listOf("obs_a", "obs_b"), listOf("obs_list")), control.requirementChecks.map { it.evidenceRefs })
        assertFalse(ActionControlProtocol.completionReferencesValid(control) { refs ->
            refs.split(',').all { it in setOf("obs_a", "obs_b") }
        })
        assertTrue(ActionControlProtocol.completionReferencesValid(control) { refs ->
            refs.split(',').all { it in setOf("obs_a", "obs_b", "obs_list") }
        })
        val archived = Json.parseToJsonElement(parsed.finishMetadata.getValue(ActionControlProtocol.CLAIMS_METADATA))
            .jsonArray.single().jsonObject.getValue("control").jsonObject
        val boundCheck = archived.getValue("requirement_checks").jsonArray.first().jsonObject
        assertFalse(boundCheck.containsKey("evidence_refs"))
        assertEquals(listOf("obs_list"), archived.getValue("requirement_checks").jsonArray.last().jsonObject
            .getValue("evidence_refs").jsonArray.map { it.jsonPrimitive.content })
        val restored = requireNotNull(ActionControlProtocol.archivedControl(parsed.finishMetadata, "done", 0, "c"))
        assertEquals(control.requirementChecks, restored.requirementChecks)
        assertEquals(control.claims, restored.claims)
        assertNull(ActionControlProtocol.archivedControl(parsed.finishMetadata, "done", 1, "c"))
        assertNull(ActionControlProtocol.archivedControl(parsed.finishMetadata, "done", 0, "another-call"))
        assertNull(ActionControlProtocol.archivedControl(parsed.finishMetadata, "present_result_card", 0, "c"))
    }

    @Test fun redundantSourcesCannotAddAuthorityToBoundChecks() {
        for (refs in listOf("obs_1", "obs_1,obs_1", "", "other", "obs_1,other")) {
            val body = JsonObject(Json.parseToJsonElement(done("Observed result")).jsonObject.toMutableMap().apply {
                val row = getValue("requirement_checks").jsonArray.single().jsonObject
                put("requirement_checks", JsonArray(listOf(JsonObject(row + ("evidence_refs" to JsonArray(refs.split(',').map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive)))))))
            })
            val control = extract(body.toString()).controls.getValue("c")
            if (refs.contains("other")) {
                assertEquals(CompletionCoverage.ARGUMENT, control.invalidArgument)
                assertEquals("result_bound_check_sources_must_match_selected_results", control.invalidReason)
                assertFalse(ActionControlProtocol.completionReferencesValid(control) { true })
            } else {
                assertNull(control.invalidArgument)
                assertEquals(listOf("obs_1"), control.requirementChecks.single().evidenceRefs)
                assertTrue(ActionControlProtocol.completionReferencesValid(control) { it == "obs_1" })
                assertFalse(ActionControlProtocol.completionReferencesValid(control) { false })
            }
        }
    }

    @Test fun schemaExposesOneBodyAndInlineReferences() {
        val descriptions = ActionControlProtocol.Descriptions("refs", "terminal", "entries", "text", "unresolved", "checks")
        val base = listOf(
            ToolDefinition("screen_act", "act", emptyMap()),
            ToolDefinition("done", "done", mapOf("status" to ToolParameter("string", "status", required = true))),
            ToolDefinition("present_result_card", "card", mapOf("items" to ToolParameter("array", "items",
                items = ToolParameter("object", "row", properties = mapOf(
                    "text" to ToolParameter("string", "text"), "deeplink" to ToolParameter("string", "link"))))))
        )
        val schemas = ActionControlProtocol.addControlParameters(base, descriptions, setOf("done", "present_result_card"))
        assertTrue(schemas[0].parameters.isEmpty())
        assertFalse(schemas[1].parameters.containsKey("summary"))
        assertFalse(schemas[1].parameters.containsKey("claims"))
        assertFalse(schemas[1].parameters.containsKey("evidence_refs"))
        assertFalse(schemas[2].parameters.containsKey("evidence_refs"))
        assertFalse(schemas[1].parameters.getValue("terminal_observation_ref").required)
        val results = schemas[1].parameters.getValue("results")
        assertTrue(results.required)
        assertFalse(schemas[1].parameters.getValue("requirement_checks").required)
        assertTrue(schemas[1].parameters.getValue("requirement_checks")
            .items!!.properties!!.getValue("evidence_refs").description.contains("refs"))
        assertFalse(schemas[1].parameters.getValue("requirement_checks")
            .items!!.properties!!.getValue("evidence_refs").required)
        assertTrue(schemas[1].parameters.getValue("unresolved_requirements").required)
        assertTrue(schemas[2].parameters.getValue("unresolved_requirements").required)
        val wire = ActionControlProtocol.submissionTools(schemas)
        assertTrue(wire[1].parameters.keys.containsAll(setOf("results", "requirement_checks", "status")))
        assertTrue(wire[2].parameters.keys.containsAll(setOf("items", "requirement_checks")))
        assertEquals(schemas[0], wire[0])
        assertTrue(wire[1].parameters.getValue("results").required)
        assertFalse(wire[1].parameters.containsKey("candidate_id"))
        assertFalse(wire[1].parameters.containsKey("draft"))
        assertFalse(wire[1].parameters.containsKey("review"))
        assertEquals(setOf("text", "evidence_refs", "deeplink"), results.items!!.properties!!.keys)
        assertEquals(results.items, schemas[2].parameters.getValue("items").items)
        assertFalse(schemas[2].parameters.getValue("items").items!!.properties!!.getValue("evidence_refs").required)
    }

    @Test fun repairFeedbackLocatesUnavailableSourcesAndAcceptsOmittedSources() {
        val checks = listOf(
            CompletionCoverage.Check("r1", "supported", listOf("obs_1"), -1, listOf(0)),
            CompletionCoverage.Check("c1", "supported", emptyList(), -1, emptyList()),
            CompletionCoverage.Check("c2", "supported", listOf("sha256:file"), -1, listOf(0)),
            CompletionCoverage.Check("r2", "unverified", emptyList(), 0, emptyList())
        )
        val control = ActionControlProtocol.Control("call", null,
            claims = listOf(ActionControlProtocol.Claim("Observed result", listOf("obs_1"))),
            requirementChecks = checks)
        fun unavailable(refs: String) = refs.split(',').map(String::trim)
            .filter { it.isNotEmpty() && it != "obs_1" }
        val feedback = ActionControlProtocol.completionClaimIssues(control, listOf("obs_1"), ::unavailable)
        assertEquals(listOf(2), feedback.getValue("invalid_requirement_indices").jsonArray
            .map { it.jsonPrimitive.int })
        val issues = feedback.getValue("invalid_requirement_issues").jsonArray.map { it.jsonObject }
        assertEquals(listOf("c2"), issues.map { it.getValue("requirement_id").jsonPrimitive.content })
        assertEquals("results", issues[0].getValue("field").jsonPrimitive.content)
        assertFalse(issues[0].getValue("blank_reference").jsonPrimitive.boolean)
        assertEquals(listOf("sha256:file"), issues[0].getValue("unavailable_references").jsonArray
            .map { it.jsonPrimitive.content })
        assertTrue(feedback.getValue("source_binding_issues").jsonArray.isEmpty())
        assertEquals(checks, control.requirementChecks)
        assertFalse(ActionControlProtocol.completionReferencesValid(control) { it == "obs_1" })
    }

    @Test fun mixedValidAndUnavailableSourcesAreRejectedWithoutDemandingTheUnavailableCitation() {
        val checks = listOf(CompletionCoverage.Check("r1", "supported", listOf("obs_1","sha256:file"), -1, listOf(0)))
        val control = ActionControlProtocol.Control("call", null,
            claims = listOf(ActionControlProtocol.Claim("Observed result", listOf("obs_1"))), requirementChecks = checks)
        val feedback = ActionControlProtocol.completionClaimIssues(control, listOf("obs_1")) { refs ->
            refs.split(',').filter { it != "obs_1" }
        }
        assertEquals(listOf("sha256:file"), feedback.getValue("invalid_requirement_issues").jsonArray.single()
            .jsonObject.getValue("unavailable_references").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(feedback.getValue("source_binding_issues").jsonArray.isEmpty())
        assertEquals(listOf(0), feedback.getValue("unbound_requirement_indices").jsonArray.map { it.jsonPrimitive.int })
        assertFalse(ActionControlProtocol.completionReferencesValid(control) { refs ->
            refs.split(',').all { it == "obs_1" }
        })
        assertEquals(checks, control.requirementChecks)
    }

    @Test fun validSourceBindingMismatchStillNamesTheOriginalSourceAndRequiresARealRepair() {
        val control = ActionControlProtocol.Control("call", null,
            claims = listOf(ActionControlProtocol.Claim("Observed result", listOf("obs_1"))),
            requirementChecks = listOf(CompletionCoverage.Check("r1", "supported", listOf("obs_1","obs_2"), -1, listOf(0))))
        val feedback = ActionControlProtocol.completionClaimIssues(control, listOf("obs_1", "obs_2")) { emptyList() }
        assertTrue(feedback.getValue("invalid_requirement_indices").jsonArray.isEmpty())
        val binding = feedback.getValue("source_binding_issues").jsonArray.single().jsonObject
        assertEquals(listOf("obs_1", "obs_2"), binding.getValue("required_evidence_refs").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("obs_2"), binding.getValue("missing_evidence_refs").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(ActionControlProtocol.completionReferencesValid(control) { true })
        val repaired = control.copy(claims = listOf(ActionControlProtocol.Claim("Observed result", listOf("obs_1","obs_2"))))
        assertTrue(ActionControlProtocol.completionReferencesValid(repaired) { true })
        assertEquals(control.requirementChecks, repaired.requirementChecks)
    }

    @Test fun referenceAggregationDoesNotInventOrRepairReferences() {
        val parsed = extract("""{"status":"done","results":[
          {"text":"A","evidence_refs":["obs_1", "obs_2"]},{"text":"B","evidence_refs":["obs_2"]}]}""")
        val c = parsed.controls.getValue("c")
        assertEquals("obs_1,obs_2", ActionControlProtocol.completionEvidenceReferences(c))
        assertEquals(listOf("obs_1", "obs_2"), c.requirementChecks.single().evidenceRefs)
        assertTrue(ActionControlProtocol.completionReferencesValid(c) { true })
        assertEquals("", ActionControlProtocol.completionEvidenceReferences(null))
        assertTrue(ActionControlProtocol.referencesContain(listOf("fact_1", "obs_2"), "obs_2"))
        assertFalse(ActionControlProtocol.referencesContain(listOf("obs_20"), "obs_2"))
    }

    @Test fun declaredGapsAreDeliveredWithTheAnswerWhateverStatusTheModelChose() {
        val body = """{"status":"done","results":[{"text":"A: 12","evidence_refs":["obs_1"]}],"unresolved_requirements":["The requested order is unverified."]}"""
        val done = extract(body)
        assertNull(done.controls.getValue("c").invalidArgument)
        assertEquals("A: 12\n\nThe requested order is unverified.", renderExecuted(done.toolCalls.single().arguments))
        val partial = extract(body.replace("\"status\":\"done\"", "\"status\":\"need_more_info\""))
        assertNull(partial.controls.getValue("c").invalidArgument)
        assertEquals("A: 12\n\nThe requested order is unverified.",
            renderExecuted(partial.toolCalls.single().arguments))
        val onlyBlocker = extract("""{"status":"need_more_info","results":[],"unresolved_requirements":["Source is unavailable."]}""")
        assertNull(onlyBlocker.controls.getValue("c").invalidArgument)
        assertEquals("Source is unavailable.", renderExecuted(onlyBlocker.toolCalls.single().arguments))
    }

    @Test fun omittedGapDeclarationRemainsAbsentWithoutInventingGaps() {
        val result = ActionControlProtocol.extract(listOf(ToolCall("c", "done",
            """{"status":"done","results":[{"text":"A: 12","evidence_refs":["obs_1"]}]}""")))
        assertNull(result.controls.getValue("c").invalidArgument)
        assertFalse(Json.parseToJsonElement(result.controls.getValue("c").originalArguments!!)
            .jsonObject.containsKey("unresolved_requirements"))
    }

    @Test fun nonCompletionToolsAreUntouched() {
        val call = ToolCall("read", "android_read", """{"op":"screen","summary":"not a control"}""")
        val result = ActionControlProtocol.extract(listOf(call))
        assertEquals(call, result.toolCalls.single())
        assertTrue(result.controls.isEmpty())
        assertTrue(result.finishMetadata.isEmpty())
    }

    @Test fun repairDiagnosticsIdentifyEachMissingSource() {
        val c = ActionControlProtocol.Control("c", null, claims = listOf(
            ActionControlProtocol.Claim("A", emptyList()), ActionControlProtocol.Claim("B", listOf("unknown"))))
        val diagnostics = ActionControlProtocol.completionClaimIssues(c) { listOf(it) }
        assertTrue(diagnostics.toString().contains("unknown"))
        assertEquals(JsonArray(emptyList()), diagnostics["invalid_result_indices"])
        assertEquals(JsonArray(listOf(JsonPrimitive("unknown"))), diagnostics["unresolved_evidence_refs"])
    }

    @Test fun omittedRequirementChecksAcceptCompletionWithoutInventingCoverage() {
        val result = ActionControlProtocol.extract(listOf(ToolCall("c", "done",
            """{"status":"done","results":[{"text":"Result text","evidence_refs":["obs_1"]}],"unresolved_requirements":[]}""")))
        val control = result.controls.getValue("c")
        assertNull(control.invalidArgument)
        assertTrue(control.requirementChecks.isEmpty())
        assertFalse(control.requirementChecksDeclared)
        assertTrue(ActionControlProtocol.completionReferencesValid(control) { it == "obs_1" })
    }

    @Test fun removedSummaryAliasIsRejectedAndObjectEntriesAreAccepted() {
        val summaryResult = ActionControlProtocol.extract(listOf(ToolCall("c", "done",
            """{"status":"done","summary":"Direct summary content","unresolved_requirements":[],"requirement_checks":[{"requirement_id":"summary","status":"supported","unresolved_index":-1,"result_indices":[0]}]}""")))
        val summaryControl = summaryResult.controls.getValue("c")
        assertNotNull(summaryControl.invalidArgument)
        assertNull(summaryControl.claims)
        assertFalse(ActionControlProtocol.completionReferencesValid(summaryControl) { true })

        val stringListResult = ActionControlProtocol.extract(listOf(ToolCall("c", "done",
            """{"status":"done","results":[{"text":"Entry 1"},{"text":"Entry 2"}],"unresolved_requirements":[],"requirement_checks":[{"requirement_id":"entry-1","status":"supported","unresolved_index":-1,"result_indices":[0]},{"requirement_id":"entry-2","status":"supported","unresolved_index":-1,"result_indices":[1]}]}""")))
        val stringListControl = stringListResult.controls.getValue("c")
        assertNull(stringListControl.invalidArgument)
        assertEquals(2, stringListControl.claims!!.size)
        assertEquals("Entry 1", stringListControl.claims!![0].result)
        assertEquals("Entry 2", stringListControl.claims!![1].result)
        assertTrue(ActionControlProtocol.completionReferencesValid(stringListControl) { true })
    }
}
