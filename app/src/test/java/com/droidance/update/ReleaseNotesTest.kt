// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    private val body = """
        ## English

        ### Fixed

        - Input.

        ## 简体中文

        ### 修复

        - 输入。
    """.trimIndent()

    @Test
    fun selectsTheSectionForTheAppLanguage() {
        assertEquals("### Fixed\n\n- Input.", ReleaseNotes.forLanguage(body, "en"))
        assertEquals("### 修复\n\n- 输入。", ReleaseNotes.forLanguage(body, "zh"))
    }

    @Test
    fun followsSectionsInEitherOrder() {
        val reversed = "## 简体中文\n\n- 输入。\n\n## English\n\n- Input."

        assertEquals("- Input.", ReleaseNotes.forLanguage(reversed, "en"))
        assertEquals("- 输入。", ReleaseNotes.forLanguage(reversed, "zh"))
    }

    @Test
    fun keepsBodiesWithoutBothLanguagesUnchanged() {
        assertEquals("### Fixed\n\n- Input.", ReleaseNotes.forLanguage("\n### Fixed\n\n- Input.\n", "zh"))
        assertEquals("## English\n\n- Input.", ReleaseNotes.forLanguage("## English\n\n- Input.", "zh"))
    }
}
