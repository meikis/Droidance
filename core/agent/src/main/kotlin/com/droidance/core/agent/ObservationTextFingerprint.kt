// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.LocalActionEvidence.TextFingerprint
import com.droidance.core.agent.ports.AgentConfiguration
import javax.inject.Inject

/** Normalizes observations using the same Markdown policy for every evidence consumer. */
class ObservationTextFingerprint @Inject constructor(private val rules: AgentConfiguration) {
    fun buildTextFingerprint(rawText: String): TextFingerprint {
        val policy = rules.harnessPolicy().observationFingerprint
        val volatileText = volatileTextRegex(policy.volatileTextPattern)
        val lines = rawText.lineSequence()
            .map { normalizeLine(it, volatileText, policy.volatileTextPlaceholder) }
            .filter { it.length in 2..120 }
            .filterNot { line ->
                policy.toolReceiptMarkers.any { line.contains(it, ignoreCase = true) }
            }
            .distinct()
            .take(80)
            .toList()
        val joined = lines.joinToString("|")
        return TextFingerprint(
            topTexts = lines.take(6),
            bottomTexts = lines.takeLast(6),
            allTextHash = joined.hashCode(),
            lineCount = lines.size
        )
    }

    private companion object {
        private val QUOTED_SPAN = Regex("""「(.{2,160}?)」""")
        private val COORDINATE_SUFFIX = Regex("""@\(-?\d+,-?\d+\)""")
        private val INDEX_MARKER = Regex("""\[[0-9]+]""")
        private val WHITESPACE_RUN = Regex("""\s+""")
    }
    private fun normalizeLine(
        line: String,
        volatileText: Regex,
        volatilePlaceholder: String
    ): String {
        val quoted = QUOTED_SPAN.find(line)?.groupValues?.getOrNull(1)
        return (quoted ?: line)
            .replace(COORDINATE_SUFFIX, "")
            .replace(INDEX_MARKER, "")
            .replace(volatileText, volatilePlaceholder)
            .replace(WHITESPACE_RUN, " ")
            .trim(' ', '-', '*', ':', '：', '"', '\'')
            .take(160)
    }

    /** Regex compilation is not free; the pattern only changes when the policy file reloads. */
    private fun volatileTextRegex(pattern: String): Regex {
        volatileTextRegexCache?.takeIf { it.first == pattern }?.let { return it.second }
        return Regex(pattern).also { volatileTextRegexCache = pattern to it }
    }

    @Volatile
    private var volatileTextRegexCache: Pair<String, Regex>? = null

}
