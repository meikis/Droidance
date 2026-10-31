// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

/** Removes runtime-only transport content without inferring or rewriting user-facing status. */
object PublicAssistantText {
    fun sanitize(text: String): String {
        var result = pureVisionEnvelope.replace(text, "\n")
        reasoningBlocks.forEach { result = it.replace(result, "") }
        internalBlocks.forEach { pattern -> result = pattern.replace(result, "") }
        result = result.lineSequence()
            .filterNot { line ->
                val value = line.trim()
                value.isNotBlank() && internalLinePrefixes.any(value::startsWith)
            }
            .joinToString("\n")
        return result
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    // Recognized transport delimiters only; ordinary markup remains user content.
    private val reasoningBlocks = listOf(
        Regex("""(?is)<(think(?:_never_used_[a-f0-9]+)?)\b[^>]*>.*?</\1\s*>"""),
        Regex("""(?is)<think(?:_never_used_[a-f0-9]+)?\b[^>]*>.*?\z"""),
        Regex("""(?is)</think(?:_never_used_[a-f0-9]+)?\s*>""")
    )

    private val internalBlocks = listOf(
        Regex("""(?is)<conversation_title\b[^>]*>.*?</conversation_title\s*>"""),
        Regex("""(?is)<conversation_title\b[^>]*>.*?\z"""),
        Regex("""(?is)<(?:operator_)?task_state\b[^>]*>.*?</(?:operator_)?task_state\s*>"""),
        Regex("""(?is)<(?:operator_)?task_state\b[^>]*>.*?\z"""),
        Regex("""(?is)\[\[DROIDANCE_TASK_EVIDENCE\]\].*?\[\[/DROIDANCE_TASK_EVIDENCE\]\]"""),
        Regex("""(?is)\[\[DROIDANCE_TASK_EVIDENCE\]\].*?\z"""),
        Regex("""(?im)^[ \t]*PROGRESS[ \t]*[:：][ \t]*\r?\n(?:[ \t]*(?:phase|evidence_state|next_intent|reason)[ \t]*[:：][^\n]*(?:\r?\n|$))+"""),
        Regex("""[ \t]*PROGRESS[ \t]*[:：][^\n]*""")
    )

    private val pureVisionEnvelope = Regex(
        """(?is)(?:^|\n)\s*\[PURE_VLM_SCREEN\].*?\z"""
    )

    private val internalLinePrefixes = listOf(
        "[PURE_VLM_SCREEN]",
        "[COMPUTER_CALL_OUTPUT]",
        "[UPDATED_SCREEN_STATE]",
        "[SCREEN_STATE]",
        "[LOCAL_VALIDATION]",
        "[HARNESS]",
        "[HARNESS_REFLECTION]",
        "[RECOVERY]",
        "[VISION_PROVIDER_UNAVAILABLE]",
        "A clean Android screenshot is attached",
        "Screenshot grid:",
        "Attached frame dimensions:",
        "Attached crop dimensions:",
        "Action coordinate space:",
        "All tap/click/swipe coordinates",
        "Physical device pixels are mapped",
        "Frame and physical device pixels are mapped",
        "no element ids or coordinate grid",
        "For click, pass",
        "Foreground:",
        "Coordinates below use the current screenshot grid.",
        "Coordinates below use the normalized"
    )
}

class PublicAssistantTextStream {
    private val raw = StringBuilder()
    private var published = ""

    val publishedText: String get() = published

    fun append(chunk: String): String {
        raw.append(chunk)
        val text = raw.toString()
        val open = text.lastIndexOf('<')
        val complete = if (open > text.lastIndexOf('>')) text.substring(0, open) else text
        val clean = PublicAssistantText.sanitize(complete)
        if (!clean.startsWith(published)) return ""
        return clean.substring(published.length).also { published = clean }
    }

    fun finish(): String {
        val text = raw.toString()
        val open = text.lastIndexOf('<')
        val suffix = if (open >= 0) text.substring(open) else ""
        val incompleteControl = open > text.lastIndexOf('>') &&
            ("<think".startsWith(suffix) || "</think".startsWith(suffix) ||
                suffix.startsWith("<think") || suffix.startsWith("</think") ||
                ConversationTitleProtocol.OPEN.startsWith(suffix) ||
                ConversationTitleProtocol.CLOSE.startsWith(suffix) ||
                suffix.startsWith("<conversation_title") || suffix.startsWith("</conversation_title"))
        val clean = PublicAssistantText.sanitize(if (incompleteControl) text.substring(0, open) else text)
        if (!clean.startsWith(published)) return ""
        return clean.substring(published.length).also { published = clean }
    }
}
