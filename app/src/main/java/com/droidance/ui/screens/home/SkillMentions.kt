// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import com.droidance.core.knowledge.skill.SkillInfo

internal data class SkillMention(val start: Int, val end: Int, val skill: SkillInfo)

internal fun skillMentionLabel(skill: SkillInfo, language: String, draftLabel: (String) -> String): String =
    "@" + skill.displayName(language).let { if (skill.isDraft) draftLabel(it) else it }

internal fun findSkillMentions(
    text: String,
    skills: List<SkillInfo>,
    language: String,
    draftLabel: (String) -> String
): List<SkillMention> {
    val candidates = skills.flatMap { skill ->
        (listOf(skill.displayName(language), skill.name) + skill.displayNames.values).distinct().map { name ->
            ("@" + if (skill.isDraft) draftLabel(name) else name) to skill
        }
    }.sortedByDescending { it.first.length }
    val result = mutableListOf<SkillMention>()
    var index = 0
    while (index < text.length) {
        if (text[index] != '@' || (index > 0 && !text[index - 1].isWhitespace() &&
                    !text[index - 1].isMentionSeparator())) {
            index++
            continue
        }
        val match = candidates.firstOrNull { (label, _) ->
            text.regionMatches(index, label, 0, label.length, ignoreCase = true) &&
                (index + label.length == text.length || text[index + label.length].isWhitespace() ||
                    text[index + label.length].isMentionSeparator())
        }
        if (match == null) index++ else {
            val end = index + match.first.length
            result += SkillMention(index, end, match.second)
            index = end
        }
    }
    return result
}

private fun Char.isMentionSeparator(): Boolean = this in ",，。.!！?？;；:：()（）[]【】"

internal fun activeSkillQuery(text: String, cursor: Int, mentions: List<SkillMention>): IntRange? {
    val end = cursor.coerceIn(0, text.length)
    val start = text.lastIndexOf('@', end - 1)
    if (start < 0 || (start > 0 && !text[start - 1].isWhitespace() && !text[start - 1].isMentionSeparator())) return null
    if (mentions.any { start >= it.start && start < it.end }) return null
    if (text.substring(start, end).any { it == '\n' || it == '\r' }) return null
    return start until end
}
