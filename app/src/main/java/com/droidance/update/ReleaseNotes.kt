// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

/** A release body holds one section per language; bodies without both are shown unchanged. */
object ReleaseNotes {
    private const val ENGLISH_HEADING = "## English"
    private const val CHINESE_HEADING = "## 简体中文"

    fun forLanguage(notes: String, languageTag: String): String {
        val lines = notes.lines()
        val english = lines.indexOfFirst { it.trim() == ENGLISH_HEADING }
        val chinese = lines.indexOfFirst { it.trim() == CHINESE_HEADING }
        if (english < 0 || chinese < 0) return notes.trim()
        val start = if (languageTag == "zh") chinese else english
        val other = if (languageTag == "zh") english else chinese
        val end = if (other > start) other else lines.size
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }
}
