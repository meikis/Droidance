// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.knowledge.skill.SkillInfo
import com.droidance.core.knowledge.skill.buildSkillCatalogContext
import com.droidance.core.agent.tools.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuildArchitectureContractTest {
    @Test
    fun defaultAndroidPhaseIncludesDurableTaskMemo() {
        assertTrue("program" in ToolCatalog.operatorToolNames)
        assertTrue("update_memory" in ToolCatalog.operatorToolNames)
        assertTrue("session_read" in ToolCatalog.operatorToolNames)
        assertFalse("record_collection_items" in ToolCatalog.operatorToolNames)
        assertFalse("wait_for_content" in ToolCatalog.operatorToolNames)
        assertFalse("recall_memory" in ToolCatalog.operatorToolNames)
        assertFalse("recall_app_knowledge" in ToolCatalog.operatorToolNames)
        assertEquals(setOf("manage_app_takeover"), ToolCatalog.backgroundAutomationToolNames.intersect(ToolCatalog.operatorToolNames))
    }


    @Test
    fun skillCatalogIncludesOnlyAvailableImplicitSkillsWithoutTheirBodies() {
        val visibleRunSkill = skill(
            id = "compiled-navigation",
            name = "Compiled Navigation",
            version = 7,
            description = "Execute a verified local workflow through the available device tools.",
            rawContent = "SECRET COMPILED NAVIGATION BODY",
            requiredCapabilities = listOf("android_read")
        )
        val visibleVerifySkill = skill(
            id = "prepared-review",
            name = "Prepared Review",
            version = 3,
            description = "Check a prepared workflow with the current device capabilities.",
            rawContent = "SECRET PREPARED REVIEW BODY",
            requiredCapabilities = listOf("screen_act")
        )
        val visibleRecallSkill = skill(
            id = "reusable-summary",
            name = "Reusable Summary",
            version = 2,
            description = "Recall a reusable workflow description when the model needs its method.",
            rawContent = "SECRET REUSABLE SUMMARY BODY"
        )
        val disabledSkill = skill(
            id = "disabled-workflow",
            name = "Disabled Workflow",
            version = 5,
            description = "This workflow is disabled and must remain outside the implicit catalog.",
            rawContent = "SECRET DISABLED WORKFLOW BODY",
            enabled = false
        )
        val unavailableSkill = skill(
            id = "needs-unavailable-capability",
            name = "Needs Unavailable Capability",
            version = 6,
            description = "This workflow requires a capability that is absent from this route.",
            rawContent = "SECRET UNAVAILABLE CAPABILITY BODY",
            requiredCapabilities = listOf("android_act")
        )
        val explicitOnlySkill = skill(
            id = "explicit-only-workflow",
            name = "Explicit Only Workflow",
            version = 4,
            description = "This workflow opts out of implicit invocation and stays hidden here.",
            rawContent = "SECRET EXPLICIT ONLY WORKFLOW BODY",
            allowImplicitInvocation = false
        )
        val skillCatalog = buildSkillCatalogContext(
            skills = listOf(
                visibleRunSkill,
                visibleVerifySkill,
                visibleRecallSkill,
                disabledSkill,
                unavailableSkill,
                explicitOnlySkill
            ),
            availableCapabilities = setOf("android_read", "screen_act")
        )
        val prompt = skillCatalog

        listOf(
            "<available_skills>",
            "- name=Compiled Navigation; version=7:",
            "- name=Prepared Review; version=3:",
            "- name=Reusable Summary; version=2:"
        ).forEach { field -> assertTrue("missing $field", prompt.contains(field)) }
        assertEquals(3, Regex("(?m)^- name=").findAll(prompt).count())
        listOf(
            disabledSkill,
            unavailableSkill,
            explicitOnlySkill
        ).forEach { hidden ->
            assertFalse("hidden skill leaked: ${hidden.id}", prompt.contains(hidden.id))
            assertFalse("hidden skill name leaked: ${hidden.id}", prompt.contains(hidden.name))
            assertFalse("hidden skill description leaked: ${hidden.id}", prompt.contains(hidden.description))
            assertFalse("hidden skill body leaked: ${hidden.id}", prompt.contains(hidden.rawContent))
        }
        listOf(
            visibleRunSkill,
            visibleVerifySkill,
            visibleRecallSkill
        ).forEach { visible ->
            assertFalse("skill body leaked: ${visible.id}", prompt.contains(visible.rawContent))
            assertTrue("skill description missing: ${visible.id}", prompt.contains(visible.description))
        }
        assertFalse(prompt.contains("Loadable skill index"))
    }

    private fun skill(
        id: String,
        name: String,
        version: Int,
        description: String,
        rawContent: String,
        enabled: Boolean = true,
        allowImplicitInvocation: Boolean = true,
        requiredCapabilities: List<String> = emptyList()
    ): SkillInfo = SkillInfo(
        id = id,
        name = name,
        version = version,
        description = description,
        appsRequired = emptyList(),
        requiredCapabilities = requiredCapabilities,
        enabled = enabled,
        allowImplicitInvocation = allowImplicitInvocation,
        source = "agent",
        rawContent = rawContent
    )

}
