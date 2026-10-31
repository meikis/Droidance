// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.ui.screens.home

import com.droidance.core.knowledge.skill.SkillInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillMentionsTest {
    private val short = SkillInfo("a", "reader", description = "", rawContent = "", revision = "a")
    private val long = SkillInfo("b", "reader pro", description = "", rawContent = "", revision = "b")
    private val draft = SkillInfo("c", "planner", description = "", rawContent = "", revision = "c", isDraft = true)
    private val skills = listOf(short, long, draft)
    private val draftLabel: (String) -> String = { "Draft: $it" }

    @Test fun findsLongestReferencesWithoutMatchingEmailOrPartialNames() {
        val text = "Use @reader pro, @reader and @Draft: planner. x@reader @readership"
        val mentions = findSkillMentions(text, skills, "en", draftLabel)
        assertEquals(listOf("b", "a", "c"), mentions.map { it.skill.id })
        assertEquals(listOf("@reader pro", "@reader", "@Draft: planner"), mentions.map { text.substring(it.start, it.end) })
    }

    @Test fun queryOnlyAppearsForAnUnfinishedReferenceAtTheCursor() {
        val text = "Use @reader then @plan"
        val matches = findSkillMentions(text, skills, "en", draftLabel)
        assertEquals(17 until 22, activeSkillQuery(text, text.length, matches))
        assertNull(activeSkillQuery(text, 8, matches))
        assertNull(activeSkillQuery("user@example.com", 16, emptyList()))
    }
}
