// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCatalogContextTest {
    @Test fun localizedPurposeRanksARelevantSkillBeforeAlphabeticalEntries() {
        val relevant = skill("z-workflow", "A reusable workflow", "hidden").copy(
            displayNames = mapOf("zh" to "比较结果"), displayDescriptions = mapOf("zh" to "比较多个页面的结果"))
        val unrelated = skill("a-workflow", "An unrelated workflow", "hidden")
        assertTrue(skillRelevance(relevant, "比较结果") > skillRelevance(unrelated, "比较结果"))
        val context = buildSkillCatalogContext(listOf(unrelated, relevant), emptySet(), query = "比较结果")
        assertTrue(context.indexOf("z-workflow") < context.indexOf("a-workflow"))
        assertTrue(context.contains("比较结果"))
        assertFalse(context.contains("hidden"))
    }

    @Test
    fun catalogExposesOnlyEnabledPublishedMetadataWithoutSkillBodies() {
        val enabled = skill(
            id = "direct-navigation",
            description = "Use a reliable native route before visual navigation.",
            rawContent = "SECRET FULL PROCEDURE"
        )
        val disabled = skill(
            id = "disabled-skill",
            description = "This skill is disabled.",
            rawContent = "DISABLED BODY",
            enabled = false
        )
        val draft = skill(
            id = "draft-skill",
            description = "A draft should stay out of implicit context.",
            rawContent = "DRAFT BODY",
            isDraft = true
        )

        val context = buildSkillCatalogContext(listOf(enabled, disabled, draft), emptySet())

        assertTrue(context.contains("direct-navigation"))
        assertTrue(context.contains(enabled.description))
        assertFalse(context.contains("SECRET FULL PROCEDURE"))
        assertFalse(context.contains("disabled-skill"))
        assertFalse(context.contains("DISABLED BODY"))
        assertFalse(context.contains("draft-skill"))
        assertFalse(context.contains("run_skill"))
    }

    @Test
    fun implicitCatalogOmitsOptOutAndUnavailableSkills() {
        val explicitOnly = skill(
            id = "explicit-only",
            description = "Explicit invocation only.",
            rawContent = "hidden",
            allowImplicit = false
        )
        val unavailable = skill(
            id = "needs-screen",
            description = "Needs a screen action.",
            rawContent = "hidden",
            requiredCapabilities = listOf("screen_act")
        )

        val context = buildSkillCatalogContext(
            listOf(explicitOnly, unavailable),
            availableCapabilities = setOf("android_read")
        )

        assertFalse(context.contains("explicit-only"))
        assertFalse(context.contains("needs-screen"))
        assertEquals(listOf("screen_act"), missingRequiredCapabilities(unavailable, setOf("android_read")))
    }

    @Test
    fun catalogIsBoundedAndNeverCutsAnEntryInHalf() {
        val context = buildSkillCatalogContext(
            skills = (1..40).map { index ->
                skill(
                    id = "skill-$index",
                    description = "Reusable workflow $index " + "detail ".repeat(30),
                    rawContent = "hidden"
                )
            },
            availableCapabilities = emptySet(),
            maxChars = 900
        )

        assertTrue(context.length <= 900)
        assertTrue(context.endsWith("</available_skills>"))
        assertTrue(context.lineSequence().filter { it.startsWith("- name=") }.all { it.contains(": ") })
    }

    @Test
    fun catalogBudgetIncludesEnvelopeAndSelectionIsStableAcrossStorageOrder() {
        val skills = (1..12).map { index ->
            skill("method-${index.toString().padStart(2, '0')}", "Method ".repeat(12), "hidden")
        }

        val forward = buildSkillCatalogContext(skills, emptySet(), maxChars = 256)
        val reverse = buildSkillCatalogContext(skills.reversed(), emptySet(), maxChars = 256)

        assertEquals(forward, reverse)
        assertTrue(forward.isNotEmpty())
        assertTrue(forward.length <= 256)
        assertTrue(forward.contains("method-01"))
        assertFalse(forward.contains("method-12"))
    }

    @Test
    fun catalogIncludesACompleteEntryAtTheExactEnvelopeBoundary() {
        val entry = skill("boundary", "x".repeat(220), "hidden")
        val full = buildSkillCatalogContext(listOf(entry), emptySet())

        assertEquals(full, buildSkillCatalogContext(listOf(entry), emptySet(), full.length))
        assertEquals("", buildSkillCatalogContext(listOf(entry), emptySet(), full.length - 1))
    }

    @Test
    fun skillBecomesDiscoverableOnlyWhenEveryDeclaredCapabilityIsPresent() {
        val entry = skill("visual-method", "Inspect and act on a current screen.", "hidden",
            requiredCapabilities = listOf("android_read", "screen_act"))

        assertEquals("", buildSkillCatalogContext(listOf(entry), setOf("android_read")))
        assertTrue(buildSkillCatalogContext(listOf(entry), setOf("android_read", "screen_act"))
            .contains("visual-method"))
    }

    @Test
    fun referencesNormalizeOnlyTheSupportedRuntimePrefixes() {
        assertEquals("quick native alarm", normalizeSkillReference("@Quick Native Alarm"))
        assertEquals("quick native alarm", normalizeSkillReference("/skill Quick Native Alarm"))
        assertEquals("quick native alarm", normalizeSkillReference("Quick Native Alarm"))
        assertEquals("/技能 quick native alarm", normalizeSkillReference("/技能 Quick Native Alarm"))
    }

    private fun skill(
        id: String,
        description: String,
        rawContent: String,
        enabled: Boolean = true,
        allowImplicit: Boolean = true,
        requiredCapabilities: List<String> = emptyList(),
        isDraft: Boolean = false
    ) = SkillInfo(
        id = id,
        name = id,
        description = description,
        enabled = enabled,
        allowImplicitInvocation = allowImplicit,
        requiredCapabilities = requiredCapabilities,
        source = "user",
        isDraft = isDraft,
        rawContent = rawContent
    )
}
