// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.TaskRequirementPlan
import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.workspace.TaskExecutionPlan
import com.droidance.core.agent.workspace.TaskStep
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.core.agent.tools.TaskMemoTool
import com.droidance.core.agent.tools.ToolInvocation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TaskRequirementGuardTest {
    @Test fun bundledRequirementCannotReplaceIndependentConstraintCoverage() {
        val scope = TaskRequirementPlan("memo", listOf("read current state without disclosing identifiers"),
            TaskOutputConstraints(listOf("omit identifiers", "keep settings unchanged"), emptyList(), emptyList()))
        assertEquals(listOf("r1", "c1", "c2"), scope.requirementIds)
        val rejection = TaskRequirementGuard.check(scope, "{}", false, true, listOf("r1"), "instruction", null)!!
        assertEquals("TASK_REQUIREMENTS_UNCOVERED", rejection.diagnosticCode)
        assertTrue(rejection.outputText.contains("c1"))
        assertNull(TaskRequirementGuard.check(scope, "{}", false, true, listOf("c2", "r1", "c1"), "instruction", null))
    }

    @Test fun coverageUsesStableIdsRatherThanLabels() {
        val plan = TaskRequirementPlan("event", listOf("open settings and battery page"))
        assertTrue(plan.isCoveredBy(listOf("r1")))
        assertFalse(plan.isCoveredBy(listOf("open settings and battery page")))
        val extended = plan.copy(eventRef = "later", requirements = plan.requirements + "read value")
        assertEquals(plan.requirementIds, extended.requirementIds.take(1))
        assertFalse(extended.isCoveredBy(listOf("r1")))
        assertTrue(extended.isCoveredBy(listOf("r1", "r2")))
    }
    private val plan = TaskRequirementPlan("memo-event", listOf("requested selection", "requested findings"))

    @Test fun actionsAndCompletionNeedNoMemoWhileDeclaredScopeStillRequiresCoverage() {
        assertNull(TaskRequirementGuard.check(null, "{}", false, true, null, "instruction", null))
        assertNull(TaskRequirementGuard.check(null, "{}", false, true, emptyList(), "instruction", null))
        assertNull(TaskRequirementGuard.check(null, "{}", false, false, null, "instruction", null))
        assertNull(TaskRequirementGuard.check(null, """{"op":"launch"}""", false, false,
            null, "instruction", null))
        assertEquals("TASK_REQUIREMENTS_UNCOVERED", TaskRequirementGuard.check(plan, "{}", false,
            true, null, "instruction", null)!!.diagnosticCode)
    }

    @Test
    fun fullCompletionWithoutMemoDoesNotReplaceAnExistingRequirementBaseline() {
        val fullCompletion = """{"status":"done","results":[],"unresolved_requirements":[],"requirement_checks":[]}"""
        assertNull(TaskRequirementGuard.check(
            plan = null,
            arguments = fullCompletion,
            memo = false,
            requiresCoverage = true,
            completionRequirements = null,
            instruction = "instruction",
            pendingRefreshPlan = null
        ))
        assertEquals("TASK_REQUIREMENTS_UNCOVERED", TaskRequirementGuard.check(
            plan = plan,
            arguments = fullCompletion,
            memo = false,
            requiresCoverage = true,
            completionRequirements = null,
            instruction = "instruction",
            pendingRefreshPlan = null
        )!!.diagnosticCode)
        assertEquals("TASK_REQUIREMENTS_UNCOVERED", TaskRequirementGuard.check(
            plan = plan,
            arguments = fullCompletion,
            memo = false,
            requiresCoverage = true,
            completionRequirements = emptyList(),
            instruction = "instruction",
            pendingRefreshPlan = null
        )!!.diagnosticCode)

        val partial = """{"status":"need_more_info","results":[{"text":"partial finding"}],"unresolved_requirements":["requested selection"]}"""
        assertNull(TaskRequirementGuard.check(
            plan = null,
            arguments = partial,
            memo = false,
            requiresCoverage = false,
            completionRequirements = null,
            instruction = "instruction",
            pendingRefreshPlan = null
        ))
    }

    @Test fun memoCanReplaceFindingsWithoutReplacingRequirements() {
        assertNull(TaskRequirementGuard.check(plan, """{"state":"new findings"}""", true, false, null, "instruction", null))
        assertEquals("TASK_REQUIREMENTS_CHANGED", TaskRequirementGuard.check(plan,
            """{"state":"new findings","requirements":["requested findings"]}""", true, false, null, "instruction", null)!!.diagnosticCode)
        assertNull(TaskRequirementGuard.check(plan,
            """{"state":"new findings","requirements":["requested selection","requested findings","previously missed requirement"]}""",
            true, false, null, "instruction", null))
    }

    @Test fun onlyPreviouslyDeclaredScopeNeedsRefreshAfterAUserUpdate() {
        val prior = plan.copy(constraints = TaskOutputConstraints(listOf("keep identifiers private"), emptyList(), emptyList()))
        val rejection = TaskRequirementGuard.check(null, "{}", false, true, emptyList(), "instruction", prior)!!
        assertEquals("TASK_REQUIREMENTS_REFRESH_REQUIRED", rejection.diagnosticCode)
        assertTrue(rejection.outputText.contains("keep identifiers private"))
        assertTrue(rejection.outputText.contains("previous_task_requirements"))
        assertNull(TaskRequirementGuard.check(null, "{}", false, false, null, "instruction", prior))
        assertNull(TaskRequirementGuard.check(null, "{}", true, false, null, "instruction", prior))
        assertNull(TaskRequirementGuard.check(null, "{}", false, true, emptyList(), "instruction", null))
        assertNull(TaskRequirementGuard.check(prior, "{}", false, true, prior.requirementIds, "instruction", null))
    }

    @Test fun optionalPlanningDoesNotPermitRemovingADeclaredOutputRestriction() {
        val constrained = plan.copy(constraints = TaskOutputConstraints(listOf("keep identifiers private"), emptyList(), emptyList()))
        val replacement = """{"requirements":["requested selection","requested findings"],"output_constraints":[],"requested_deliverables":[],"protected_values":[]}"""
        assertEquals("TASK_REQUIREMENTS_CHANGED", TaskRequirementGuard.check(constrained,
            replacement, true, false, null, "instruction", null)!!.diagnosticCode)
    }

    @Test fun changingLabelsOrDroppingARequirementCannotPassCoverage() {
        val rejection = TaskRequirementGuard.check(plan, "{}", false, true,
            listOf("r2"), "instruction", null)!!
        assertEquals("TASK_REQUIREMENTS_UNCOVERED", rejection.diagnosticCode)
        assertTrue(rejection.outputText.contains("requested selection"))
        assertNull(TaskRequirementGuard.check(plan, "{}", false, true, plan.requirementIds, "instruction", null))
    }

    @Test fun malformedRequirementsCannotBecomeAPersistedPlan() = runBlocking {
        val scope = TurnEventScope("requirement-thread", "requirement-turn")
        val events = InMemoryAgentEventStore()
        val blobs = InMemoryBlobStore()
        val workspace = TaskWorkspace(events, blobs)
        events.createThread(scope.threadId, "Requirement guard")
        val hash = "1".repeat(64)
        events.startTurn(scope, ModelSessionIdentity(
            scope.threadId, "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS,
            hash, hash, "fixture", hash
        ), "fixture")
        val tool = TaskMemoTool(workspace, { scope }) { section ->
            if (section == "policy") Json { encodeDefaults = true }.encodeToString(WorkspacePolicy(4, 1024, 4096, 32))
            else "execution plan"
        }
        for (raw in listOf("[]", "[\"\"]", "[\"A\",\"A\"]", "[1]", "null")) {
            assertNull(TaskRequirementPlan.parse(Json.parseToJsonElement(raw)))
            assertFalse(tool.execute("""{"state":"note","requirements":$raw}""").isVerifiedSuccess)
        }
        val valid = TaskExecutionPlan(
            record_path = "requirements/record.md",
            state = "note",
            requirements = listOf("A"),
            steps = listOf(TaskStep(
                id = "record", objective = "Record A", acceptance = "The source contains A",
                status = "active", evidence_refs = emptyList()
            ))
        )
        assertTrue(withContext(ToolInvocation("memo-valid")) {
            tool.execute(Json { encodeDefaults = true }.encodeToString(valid))
        }.isVerifiedSuccess)
    }
}
