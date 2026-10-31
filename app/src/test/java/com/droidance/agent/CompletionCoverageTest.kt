// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.model.llm.models.ToolCall
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompletionCoverageTest {
    private val claims = listOf(ActionControlProtocol.Claim("Object A value", listOf("obs_a")))
    private fun check(status: String = "supported", index: Int = -1, refs: String? = null,
        resultIndices: List<Int> = if (status == "supported") listOf(0) else emptyList()) = buildJsonObject {
        put("requirement_id", "Object A requested attribute")
        put("result_indices", JsonArray(resultIndices.map(::JsonPrimitive)))
        put("status", status); put("unresolved_index", index)
        refs?.let { put("evidence_refs", JsonArray(it.split(',').map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive))) }
    }

    @Test fun paraphrasesAndProcessRequirementsDoNotDuplicatePublicFacts() {
        val checks = listOf(CompletionCoverage.Check("Summarize first video", "supported", listOf("first"), -1, listOf(0)),
            CompletionCoverage.Check("Search and inspect two distinct items", "supported", listOf("list"), -1, emptyList()))
        val claims = listOf(ActionControlProtocol.Claim("课程总共15课时，包含2场直播。", listOf("first")))
        assertTrue(CompletionCoverage.unboundResults(checks, claims).isEmpty())
        assertEquals(listOf(0), CompletionCoverage.unboundResults(checks, listOf(claims.single().copy(evidenceRefs = listOf("second")))))
        assertEquals(listOf(0), CompletionCoverage.unboundResults(checks.map { it.copy(resultIndices = listOf(99)) }, claims).take(1))
        assertFalse(CompletionCoverage.suppliedReferencesValid(checks) { it == "first" })
    }

    @Test fun oneRequirementCanBindMultipleResultsWithoutDuplicatingTheirSources() {
        val checks = listOf(CompletionCoverage.Check("Compare the requested findings", "supported",
            listOf("obs_a","obs_b"), -1, listOf(0, 1)))
        val claims = listOf(ActionControlProtocol.Claim("A finding", listOf("obs_a")),
            ActionControlProtocol.Claim("B finding", listOf("obs_b")))
        assertTrue(CompletionCoverage.unboundResults(checks, claims).isEmpty())
        assertEquals(listOf(0), CompletionCoverage.unboundResults(checks.map {
            it.copy(resultIndices = listOf(0))
        }, claims))
        assertEquals(listOf(0), CompletionCoverage.unboundResults(checks.map {
            it.copy(resultIndices = listOf(0, 2))
        }, claims))
        assertEquals(listOf(0), CompletionCoverage.unboundResults(checks,
            claims.map { it.copy(evidenceRefs = listOf("obs_a")) }))
    }

    @Test fun resultBoundChecksAggregateOnlyTheSelectedPublicSources() {
        val publicClaims = listOf(ActionControlProtocol.Claim("A", listOf("obs_a","obs_shared")),
            ActionControlProtocol.Claim("B", listOf("obs_b","obs_shared")),
            ActionControlProtocol.Claim("Unselected", listOf("obs_other")))
        val parsed = CompletionCoverage.parse(JsonArray(listOf(check(resultIndices = listOf(0, 1)))),
            0, publicClaims)!!
        assertEquals(listOf("obs_a","obs_shared","obs_b"), parsed.single().evidenceRefs)
        assertTrue(CompletionCoverage.unboundResults(parsed, publicClaims).isEmpty())
        assertTrue(CompletionCoverage.suppliedReferencesValid(parsed) { refs ->
            refs.split(',').all { it in setOf("obs_a", "obs_shared", "obs_b") }
        })
    }

    @Test fun optionalResultSourcesRemainAbsentAfterAggregation() {
        val emptySources = listOf(ActionControlProtocol.Claim("Unsupported value", emptyList()))
        val parsed = CompletionCoverage.parse(JsonArray(listOf(check())), 0, emptySources)!!
        assertEquals(emptyList<String>(), parsed.single().evidenceRefs)
        assertTrue(CompletionCoverage.suppliedReferencesValid(parsed) { error("No source was supplied") })
    }

    @Test fun processSourcesAreIndependentOfResultSources() {
        val process = JsonObject(check(refs = "obs_list", resultIndices = emptyList()).toMutableMap().apply {
            put("requirement_id", JsonPrimitive("Preserve the original order"))
        })
        val parsed = CompletionCoverage.parse(JsonArray(listOf(check(), process)), 0, claims)!!
        assertEquals(listOf(listOf("obs_a"), listOf("obs_list")), parsed.map { it.evidenceRefs })
        assertFalse(CompletionCoverage.suppliedReferencesValid(parsed) { it == "obs_a" })
        assertTrue(CompletionCoverage.suppliedReferencesValid(parsed) { it in setOf("obs_a", "obs_list") })
        assertTrue(CompletionCoverage.unboundResults(parsed, claims).isEmpty())
        val missingProcess = CompletionCoverage.parse(JsonArray(listOf(check(resultIndices = emptyList()))),
            0, claims)!!
        assertTrue(CompletionCoverage.suppliedReferencesValid(missingProcess) { error("No source was supplied") })
    }

    @Test fun redundantResultSourcesCannotAddAuthorityBeyondSelectedResults() {
        for (refs in listOf("obs_a", "")) {
            val parsed = CompletionCoverage.parse(JsonArray(listOf(check(refs = refs))), 0, claims)!!
            assertEquals(listOf("obs_a"), parsed.single().evidenceRefs)
        }
        val selected = claims + ActionControlProtocol.Claim("Object B value", listOf("obs_b"))
        val repeated = check(refs = "obs_a,obs_b", resultIndices = listOf(0, 1))
        assertEquals(listOf("obs_a","obs_b"), CompletionCoverage.parse(JsonArray(listOf(repeated)), 0, selected)!!.single().evidenceRefs)
        assertNull(CompletionCoverage.parse(JsonArray(listOf(check(refs = "obs_b"))), 0, selected))
        assertNull(CompletionCoverage.parse(JsonArray(listOf(check(refs = "obs_a,obs_other"))), 0, selected))
    }

    @Test fun resultIndicesRejectInvalidValuesAndTheRemovedSingleIndexProtocol() {
        for (value in listOf("[0,0]", "[-1]", "[\"0\"]", "[0.5]", "[null]", "0")) {
            val row = JsonObject(check().toMutableMap().apply {
                put("result_indices", Json.parseToJsonElement(value))
            })
            assertNull(value, CompletionCoverage.parse(JsonArray(listOf(row)), 0, claims))
        }
        val oldShape = JsonObject(check().toMutableMap().apply {
            remove("result_indices"); put("result_index", JsonPrimitive(0))
        })
        assertNull(CompletionCoverage.parse(JsonArray(listOf(oldShape)), 0, claims))
    }

    @Test fun rejectedSourceBindingReportsTheActualMissingReference() {
        val control = ActionControlProtocol.Control("c", null,
            claims = listOf(ActionControlProtocol.Claim("Same value", listOf("other"))),
            requirementChecks = listOf(CompletionCoverage.Check("Object A requested attribute", "supported",
                listOf("obs_a"), -1, listOf(0))))
        val issue = ActionControlProtocol.completionClaimIssues(control) { emptyList() }
            .getValue("source_binding_issues").jsonArray.single().jsonObject
        val match = issue.getValue("result_matches").jsonArray.single().jsonObject
        assertEquals("obs_a", match.getValue("missing_evidence_refs").jsonArray.single().jsonPrimitive.content)
        assertFalse(issue.containsKey("value"))
    }

    @Test fun missingObjectAttributeMustLinkToAPublicGap() {
        assertNull(CompletionCoverage.parse(JsonArray(listOf(check("unverified"))), 0, claims))
        assertNull(CompletionCoverage.parse(JsonArray(listOf(check("incomplete", 1))), 1, claims))
        val partial = check("incomplete", 0, resultIndices = listOf(0))
        assertNotNull(CompletionCoverage.parse(JsonArray(listOf(partial)), 1, claims))
        val secondRequirement = JsonObject(check("unverified", 0, "").toMutableMap().apply {
            put("requirement_id", JsonPrimitive("Object A second requested attribute"))
        })
        val checks = CompletionCoverage.parse(JsonArray(listOf(check(), secondRequirement)), 1, claims)!!
        assertEquals(0, checks.last().unresolvedIndex)
        assertEquals("unverified", checks.last().status)
        assertNull(CompletionCoverage.parse(JsonArray(listOf(check())), 1, claims))
    }

    @Test fun emptyCoverageDeclaresNoChecksAndDoesNotSupplyResultEvidence() {
        val checks = CompletionCoverage.parse(JsonArray(emptyList()), 0, claims)!!
        assertTrue(checks.isEmpty())
        assertTrue(CompletionCoverage.suppliedReferencesValid(checks) { error("No check has a source") })
        assertNotNull(CompletionCoverage.parse(JsonArray(emptyList()), 1, claims))

        val parsed = ActionControlProtocol.extract(listOf(ToolCall("c", "done",
            """{"status":"done","results":[{"text":"A","evidence_refs":["obs_a"]}],"unresolved_requirements":[],"requirement_checks":[]}""")))
        val control = parsed.controls.getValue("c")
        assertNull(control.invalidArgument)
        assertTrue(control.requirementChecksDeclared)
        assertTrue(control.requirementChecks.isEmpty())
        assertTrue(ActionControlProtocol.completionReferencesValid(control) { it == "obs_a" })
        assertFalse(ActionControlProtocol.completionReferencesValid(control) { false })
        assertTrue(ActionControlProtocol.completionReferencesValid(control.copy(
            claims = listOf(claims.single().copy(evidenceRefs = emptyList())))) { true })
    }

    @Test fun malformedAuditIsNeverAssumedComplete() {
        for (value in listOf(null, JsonNull, JsonArray(listOf(JsonObject(emptyMap()))))) {
            assertNull(CompletionCoverage.parse(value, 0, claims))
        }
        assertNull(CompletionCoverage.parse(JsonArray(listOf(check("unknown"))), 0, claims))
        assertNull(CompletionCoverage.parse(JsonArray(listOf(check(index = 0))), 1, claims))
        val stringIndex = JsonObject(check().toMutableMap().apply { put("unresolved_index", JsonPrimitive("-1")) })
        assertNull(CompletionCoverage.parse(JsonArray(listOf(stringIndex)), 0, claims))
    }

    @Test fun requirementIdsAreUniqueAndCoverageSchemaIsOptional() {
        val first = check()
        val duplicate = JsonObject(check().toMutableMap().apply {
            put("result_indices", JsonArray(emptyList()))
        })
        assertNull(CompletionCoverage.parse(JsonArray(listOf(first, duplicate)), 0, claims))
        assertFalse(CompletionCoverage.schema("coverage", "sources").required)
    }

    @Test fun validResultCitationDoesNotRepairAnUnsupportedOrderingCitation() {
        val body = buildJsonObject {
            put("status", "done"); put("unresolved_requirements", JsonArray(emptyList()))
            val ordering = JsonObject(check(refs = "missing_list", resultIndices = emptyList()).toMutableMap().apply {
                put("requirement_id", JsonPrimitive("Preserve original order"))
            })
            put("requirement_checks", JsonArray(listOf(check(), ordering)))
            putJsonArray("results") { addJsonObject { put("text", "Object A value"); put("evidence_refs", JsonArray(listOf(JsonPrimitive("obs_a")))) } }
        }
        val result = ActionControlProtocol.extract(listOf(ToolCall("c", "done", body.toString())))
        assertNull(result.controls.getValue("c").invalidArgument)
        assertFalse(ActionControlProtocol.completionReferencesValid(result.controls["c"]) { it == "obs_a" })
        val issues = ActionControlProtocol.completionClaimIssues(result.controls["c"]) { refs ->
            refs.split(',').filter { it == "missing_list" }
        }
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), issues["invalid_requirement_indices"])
        val blank = result.controls.getValue("c").copy(requirementChecks = listOf(
            CompletionCoverage.Check("Ordering", "supported", emptyList(), -1, listOf(0))))
        val blankIssues = ActionControlProtocol.completionClaimIssues(blank) { emptyList() }
        assertEquals(JsonArray(emptyList()), blankIssues["invalid_requirement_indices"])
        assertFalse(result.toolCalls.single().arguments.contains("requirement_checks"))
        assertTrue(result.finishMetadata.getValue(ActionControlProtocol.CLAIMS_METADATA).contains("missing_list"))
    }

    @Test fun partialAdviceMayBeUncitedAndAnOnlyBlockerDeliveryCanBeEmpty() {
        val supportedCheck = check()
        val unresolvedCheck = JsonObject(check("unverified", 0, "").toMutableMap().apply {
            put("requirement_id", JsonPrimitive("Object A ordering requirement"))
        })
        val checks = JsonArray(listOf(supportedCheck, unresolvedCheck))
        fun parse(refs: String, emptyResults: Boolean = false) = ActionControlProtocol.extract(listOf(ToolCall("c", "done", buildJsonObject {
            put("status", "need_more_info")
            put("requirement_checks", if (emptyResults) JsonArray(listOf(unresolvedCheck)) else checks)
            put("unresolved_requirements", JsonArray(listOf(JsonPrimitive("Ordering is not verified."))))
            put("results", if (emptyResults) JsonArray(emptyList()) else buildJsonArray { addJsonObject {
                put("text", "Object A value: inference from observed values"); put("evidence_refs", JsonArray(refs.split(',').map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive)))
            } })
        }.toString()))).controls.getValue("c")
        assertTrue(ActionControlProtocol.completionReferencesValid(parse("")) { it == "obs_a" })
        assertTrue(ActionControlProtocol.completionReferencesValid(parse("obs_a")) { it == "obs_a" })
        val onlyBlockers = parse("", emptyResults = true)
        assertNull(onlyBlockers.invalidArgument)
        assertTrue(onlyBlockers.claims!!.isEmpty())
        assertTrue(CompletionCoverage.suppliedReferencesValid(onlyBlockers.requirementChecks) { it == "obs_a" })
    }

    @Test fun omittedCoverageIsAcceptedAndMalformedCoverageRemainsRejected() {
        val omitted = ActionControlProtocol.extract(listOf(ToolCall("c", "done",
            """{"status":"done","results":[{"text":"A","evidence_refs":["obs_a"]}],"unresolved_requirements":[]}""")))
        assertNull(omitted.controls.getValue("c").invalidArgument)
        assertFalse(omitted.controls.getValue("c").requirementChecksDeclared)
        assertTrue(omitted.controls.getValue("c").requirementChecks.isEmpty())

        val malformed = ActionControlProtocol.extract(listOf(ToolCall("c", "done",
            """{"status":"done","requirement_checks":"invalid","results":[{"text":"A","evidence_refs":["obs_a"]}],"unresolved_requirements":[]}""")))
        assertEquals(CompletionCoverage.ARGUMENT, malformed.controls.getValue("c").invalidArgument)

        val duplicateId = ActionControlProtocol.extract(listOf(ToolCall("c", "done", buildJsonObject {
            put("status", "done")
            put("unresolved_requirements", JsonArray(emptyList()))
            put("requirement_checks", JsonArray(listOf(check(), JsonObject(check().toMutableMap().apply {
                put("result_indices", JsonArray(emptyList()))
            }))))
            put("results", JsonArray(listOf(buildJsonObject {
                put("text", "A"); put("evidence_refs", JsonArray(listOf(JsonPrimitive("obs_a"))))
            })))
        }.toString())))
        assertEquals(CompletionCoverage.ARGUMENT, duplicateId.controls.getValue("c").invalidArgument)
    }
}
