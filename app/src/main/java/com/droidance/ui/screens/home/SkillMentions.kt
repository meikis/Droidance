// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import com.droidance.core.knowledge.skill.SkillInfo
import com.droidance.core.agent.RequestedSkill
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A draft owns the exact selection, independently of editable text and catalog refreshes. */
@Serializable
internal data class ComposerSkillReference(
    val id: String,
    val name: String,
    val revision: String,
    val draft: Boolean,
    val title: String,
    val description: String,
    val parameters: JsonObject
) {
    val selectionKey: String get() = "$id:$draft"
    fun requestedSkill() = RequestedSkill(name, revision, draft)
    fun mentionLabel(draftLabel: (String) -> String): String = "@" + if (draft) draftLabel(title) else title

    companion object {
        fun from(skill: SkillInfo, language: String) = ComposerSkillReference(
            skill.id, skill.name, skill.revision, skill.isDraft, skill.displayName(language),
            skill.displayDescription(language), skill.parameters
        )
    }
}

internal fun selectableSkills(skills: List<SkillInfo>, query: String = ""): List<SkillInfo> =
    skills.filter { skill ->
        !skill.isReadOnly && (query.isBlank() ||
            (listOf(skill.name, skill.description) + skill.displayNames.values + skill.displayDescriptions.values)
                .any { it.contains(query.trim(), ignoreCase = true) })
    }

internal fun skillSubmissionText(
    text: String,
    skills: List<ComposerSkillReference>,
    draftLabel: (String) -> String
): String = listOf(skills.joinToString("\n") { it.mentionLabel(draftLabel) }, text.trim())
    .filter(String::isNotBlank).joinToString("\n\n")

internal data class SkillMention(val start: Int, val end: Int, val skill: SkillInfo)

internal fun skillMessageContent(
    text: String,
    skills: List<SkillInfo>,
    language: String,
    draftLabel: (String) -> String
): Pair<List<SkillMention>, String> {
    var end = 0
    val leading = findSkillMentions(text, skills, language, draftLabel).takeWhile { mention ->
        text.substring(end, mention.start).isBlank().also { if (it) end = mention.end }
    }
    return leading.distinctBy { it.skill.selectionKey } to if (leading.isEmpty()) text else text.substring(end).trimStart()
}

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

private fun Char.isEmailNameCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "_.+-"

internal fun activeSkillQuery(text: String, cursor: Int, mentions: List<SkillMention>): IntRange? {
    val end = cursor.coerceIn(0, text.length)
    val start = text.lastIndexOf('@', end - 1)
    if (start < 0 || (start > 0 && text[start - 1].isEmailNameCharacter())) return null
    if (mentions.any { start >= it.start && start < it.end }) return null
    if (text.substring(start, end).any { it == '\n' || it == '\r' }) return null
    return start until end
}
