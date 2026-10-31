// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.TaskRequirementPlan
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskOutputConstraintsTest {
    private val restricted = ProtectedTaskValue("c1", "original-observation", "room-香港-42", "mask", 2, 2)
    private val contract = TaskOutputConstraints(listOf("mask the observed identifier"), listOf(RequestedTaskFile("report.md", "r1")), listOf(restricted))
    private val plan = TaskRequirementPlan("original-memo", listOf("deliver report", "mask identifier"), contract)
    private fun body(text: String = restricted.masked(), remaining: String = "") = buildJsonObject {
        put("results", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        put("unresolved_requirements", buildJsonArray { if (remaining.isNotEmpty()) add(remaining) })
        put("requirement_checks", buildJsonArray {
            plan.requirementIds.forEach { id -> add(buildJsonObject {
                put("requirement_id", id); put("status", "supported"); put("result_indices", buildJsonArray { add(0) })
            }) }
        })
    }
    private fun file(text: String = restricted.masked()) = listOf(buildJsonObject { put("path", "report.md"); put("text", text) })
    @Test fun masksUseUnicodeCharactersAndAreBoundToRetainedRequirements() {
        assertEquals("ro******42", restricted.masked())
        assertTrue(contract.valid(plan.requirementIds))
        contract.validateCompletion(body(), file(), plan)
        contract.validateCompletion(body("Requested state; identifier omitted"), file("Requested state"), plan)
        assertFalse(contract.copy(protected_values = listOf(restricted.copy(visible_prefix = 99))).valid(plan.requirementIds))
    }
    @Test fun invalidDisclosureFeedbackNamesTheExactIndexWithoutEchoingValues() {
        val invalid = contract.copy(protected_values = listOf(restricted,
            restricted.copy(value = "Q", visible_prefix = 1, visible_suffix = 0)))
        val issues = invalid.validationIssues(plan.requirementIds)
        assertEquals("protected_values[1]", issues.single().field)
        assertEquals("mask_reveals_entire_value", issues.single().reason)
        assertFalse(issues.toString().contains(restricted.value))
        assertTrue(runCatching { contract.validatePublicText("Failure reply ${restricted.value}") }.isFailure)
    }
    @Test fun originalValueCannotEscapeThroughPublicTextUnresolvedOrAnAttachedFile() {
        for ((body, files) in listOf(body(restricted.value) to file(), body(remaining = restricted.value) to file(), body() to file(restricted.value))) {
            val error = runCatching { contract.validateCompletion(body, files, plan) }.exceptionOrNull()!!
            assertTrue(error.message!!.startsWith("protected_value_disclosed:"))
            assertFalse(error.message!!.contains(restricted.value))
        }
    }
    @Test fun missingRequestedFileAndMissingRestrictionCoverageCannotPass() {
        assertEquals("requested_deliverables_not_attached", runCatching { contract.validateCompletion(body(), emptyList(), plan) }.exceptionOrNull()!!.message)
        val missing = JsonObject(body() + ("requirement_checks" to JsonArray(body().getValue("requirement_checks").jsonArray.take(1))))
        assertEquals("task_requirements_uncovered", runCatching { contract.validateCompletion(missing, file(), plan) }.exceptionOrNull()!!.message)
        val partial = JsonObject(body() + ("status" to JsonPrimitive("need_more_info")) + ("requirement_checks" to buildJsonArray {
            add(buildJsonObject { put("requirement_id", "r1"); put("status", "incomplete"); put("result_indices", JsonArray(emptyList())) })
            body().getValue("requirement_checks").jsonArray.drop(1).forEach(::add)
        }))
        contract.validateCompletion(partial, emptyList(), plan)
    }
    @Test fun anUpdateCanOnlyExtendTheSavedContract() {
        assertTrue(contract.copy(output_constraints = contract.output_constraints + "keep order").extends(contract))
        assertFalse(contract.copy(protected_values = emptyList()).extends(contract))
        assertFalse(contract.copy(protected_values = listOf(restricted.copy(visible_prefix = 4))).extends(contract))
        assertFalse(contract.copy(requested_deliverables = emptyList()).extends(contract))
    }
    @Test fun optionalCoverageStillEnforcesDisclosureAndRequestedFiles() {
        val minimal = buildJsonObject {
            put("status", "done")
            putJsonArray("results") { addJsonObject { put("text", restricted.masked()) } }
        }
        contract.validateCompletion(minimal, file(), plan)
        assertEquals("requested_deliverables_not_attached", runCatching {
            contract.validateCompletion(minimal, emptyList(), plan)
        }.exceptionOrNull()!!.message)
        val disclosed = JsonObject(minimal + ("results" to buildJsonArray {
            addJsonObject { put("text", restricted.value) }
        }))
        val error = runCatching { contract.validateCompletion(disclosed, file(), plan) }.exceptionOrNull()!!
        assertTrue(error.message!!.startsWith("protected_value_disclosed:"))
        assertFalse(error.message!!.contains(restricted.value))
    }
}
